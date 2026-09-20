package com.morton.trucknav.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.morton.trucknav.AppModule
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.WellKnownRouteProvider
import com.stadiamaps.ferrostar.core.withJsonOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Point
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import org.maplibre.compose.expressions.dsl.offset
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

// Route preview before Start: up to three candidates with time, distance and
// the road they mostly use, drawn on the map; the driver picks one. Server
// with alternates when reachable, otherwise the hybrid provider's single route
// (on-device Valhalla when the server is dark).
data class RouteCandidate(val route: Route, val minutes: Int, val miles: Double, val via: String) {
    val label get() = "$minutes min · ${"%.1f".format(miles)} mi"
}
val LETTERS = "ABCDEF"

// Where to put each route's badge: the sampled point farthest from every other
// candidate, so A/B/C land on the stretch that makes the route different.
internal fun badgePoint(routes: List<List<Position>>, i: Int): Position {
    val mine = routes[i]; if (mine.isEmpty()) return Position(0.0, 0.0)
    val others = routes.filterIndexed { j, _ -> j != i }.flatMap { r -> r.filterIndexed { k, _ -> k % 4 == 0 } }
    if (others.isEmpty()) return mine[mine.size / 2]
    var best = mine[mine.size / 2]; var bestD = -1.0
    for (k in mine.indices step 3) {
        val p = mine[k]
        var d = Double.MAX_VALUE
        for (o in others) { val dx = (p.longitude - o.longitude) * 0.84; val dy = p.latitude - o.latitude; val dd = dx * dx + dy * dy; if (dd < d) d = dd }
        if (d > bestD) { bestD = d; best = p }
    }
    return best
}

object RoutePreview {
    private val adapter by lazy {
        RouteAdapter.fromWellKnownRouteProvider(WellKnownRouteProvider.Valhalla(AppModule.valhallaUrl, "auto").withJsonOptions(mapOf("units" to "miles", "alternates" to 2, "filters" to mapOf("attributes" to listOf("shape_attributes.speed_limit", "shape_attributes.speed", "shape_attributes.length", "shape_attributes.time"), "action" to "include"))))
    }

    suspend fun candidates(from: UserLocation, to: uniffi.ferrostar.GeographicCoordinate): List<RouteCandidate> = withContext(Dispatchers.IO) {
        val wp = listOf(Waypoint(coordinate = to, kind = WaypointKind.BREAK))
        val routes = try {
            val req = adapter.generateRequest(from, wp) as RouteRequest.HttpPost
            val b = Request.Builder().url(req.url).post(req.body.toRequestBody("application/json".toMediaType()))
            req.headers.forEach { (k, v) -> b.header(k, v) }
            val quick = AppModule.okHttp.newBuilder().callTimeout(java.time.Duration.ofSeconds(4)).build()
            quick.newCall(b.build()).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}"); adapter.parseResponse(r.body!!.bytes()) }
        } catch (e: Exception) {
            NavLog.log("preview", "server alternates failed (${e.javaClass.simpleName}); single route via hybrid provider")
            try { AppModule.ferrostarCore.getRoutes(from, wp) } catch (e2: Exception) { NavLog.log("preview", "no route: $e2"); emptyList() }
        }
        routes.take(3).map { r ->
            val secs = r.steps.sumOf { it.duration }
            val via = r.steps.filter { it.roadName?.isNotBlank() == true }.groupBy { it.roadName!! }.maxByOrNull { g -> g.value.sumOf { it.distance } }?.key ?: ""
            RouteCandidate(r, (secs / 60).toInt(), r.distance / 1609.344, via)
        }.also { NavLog.log("preview", "${it.size} candidates: " + it.joinToString(" | ") { c -> "${c.label} via ${c.via}" }) }
    }
}

// Candidate polylines: chosen one bright, the rest grey underneath.
@Composable
@MaplibreComposable
fun RoutePreviewOverlay(candidates: List<RouteCandidate>, selected: Int, onPick: (Int) -> Unit = {}) {
    if (candidates.isEmpty()) return
    val others = FeatureCollection(candidates.mapIndexedNotNull { i, c -> if (i == selected) null else Feature(geometry = LineString(c.route.geometry.map { Position(it.lng, it.lat) }), properties = buildJsonObject { put("k", 1); put("idx", i) }) })
    val chosen = candidates.getOrNull(selected)?.let { c -> FeatureCollection(listOf(Feature(geometry = LineString(c.route.geometry.map { Position(it.lng, it.lat) }), properties = buildJsonObject { put("k", 0) }))) } ?: FeatureCollection(emptyList())
    val srcOthers = rememberGeoJsonSource(GeoJsonData.Features(others))
    val srcChosen = rememberGeoJsonSource(GeoJsonData.Features(chosen))
    // Wide, invisible hit line under the grey routes: a finger is fatter than 6 dp.
    LineLayer(id = "trucknav-preview-others-hit", source = srcOthers, color = const(Color(0xFF8a94a3)), width = const(28.dp), opacity = const(0.01f),
        onClick = { fs -> val i = fs.firstOrNull()?.properties?.get("idx")?.jsonPrimitive?.intOrNull; if (i != null) { onPick(i); ClickResult.Consume } else ClickResult.Pass })
    LineLayer(id = "trucknav-preview-others", source = srcOthers, color = const(Color(0xFF8a94a3)), width = const(6.dp), opacity = const(0.8f))
    LineLayer(id = "trucknav-preview-chosen-casing", source = srcChosen, color = const(Color(0xFF0f3d63)), width = const(11.dp))
    LineLayer(id = "trucknav-preview-chosen", source = srcChosen, color = const(Color(0xFF3583DD)), width = const(7.dp))
    // A / B / C badges with the minutes under them, matching the cards in the sheet.
    val routes = candidates.map { c -> c.route.geometry.map { Position(it.lng, it.lat) } }
    val badges = FeatureCollection(candidates.mapIndexed { i, c ->
        Feature(geometry = Point(badgePoint(routes, i)), properties = buildJsonObject { put("letter", LETTERS[i].toString()); put("mins", "${c.minutes} min"); put("idx", i); put("sel", if (i == selected) 1 else 0) })
    })
    val srcBadges = rememberGeoJsonSource(GeoJsonData.Features(badges))
    val srcBadgeSel = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(badges.features.filter { it.properties?.get("sel")?.jsonPrimitive?.intOrNull == 1 })))
    val srcBadgeOth = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(badges.features.filter { it.properties?.get("sel")?.jsonPrimitive?.intOrNull != 1 })))
    val pick: (List<Feature<*, *>>) -> ClickResult = { fs -> val i = (fs.firstOrNull()?.properties as? kotlinx.serialization.json.JsonObject)?.get("idx")?.jsonPrimitive?.intOrNull; if (i != null) { onPick(i); ClickResult.Consume } else ClickResult.Pass }
    CircleLayer(id = "trucknav-preview-badges-oth", source = srcBadgeOth, radius = const(18.dp), color = const(Color(0xFF4a5563)), strokeColor = const(Color.White), strokeWidth = const(3.dp), onClick = pick)
    CircleLayer(id = "trucknav-preview-badges-sel", source = srcBadgeSel, radius = const(18.dp), color = const(Color(0xFF1f5f8b)), strokeColor = const(Color.White), strokeWidth = const(3.dp), onClick = pick)
    SymbolLayer(id = "trucknav-preview-letters", source = srcBadges, textField = feature["letter"].asString(), textFont = const(listOf("Noto Sans Medium")),
        textSize = const(18.sp), textColor = const(Color.White), textAllowOverlap = const(true), textIgnorePlacement = const(true))
    SymbolLayer(id = "trucknav-preview-mins", source = srcBadges, textField = feature["mins"].asString(), textFont = const(listOf("Noto Sans Medium")),
        textSize = const(15.sp), textColor = const(Color.White), textHaloColor = const(Color(0xFF10141a)), textHaloWidth = const(2.dp),
        textOffset = offset(0.em, 1.6.em), textAllowOverlap = const(true), textIgnorePlacement = const(true))
}
