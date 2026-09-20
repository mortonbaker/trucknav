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

object RoutePreview {
    private val adapter by lazy {
        RouteAdapter.fromWellKnownRouteProvider(WellKnownRouteProvider.Valhalla(AppModule.valhallaUrl, "auto").withJsonOptions(mapOf("units" to "miles", "alternates" to 2)))
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
fun RoutePreviewOverlay(candidates: List<RouteCandidate>, selected: Int) {
    if (candidates.isEmpty()) return
    val others = FeatureCollection(candidates.filterIndexed { i, _ -> i != selected }.map { c -> Feature(geometry = LineString(c.route.geometry.map { Position(it.lng, it.lat) }), properties = buildJsonObject { put("k", 1) }) })
    val chosen = candidates.getOrNull(selected)?.let { c -> FeatureCollection(listOf(Feature(geometry = LineString(c.route.geometry.map { Position(it.lng, it.lat) }), properties = buildJsonObject { put("k", 0) }))) } ?: FeatureCollection(emptyList())
    val srcOthers = rememberGeoJsonSource(GeoJsonData.Features(others))
    val srcChosen = rememberGeoJsonSource(GeoJsonData.Features(chosen))
    LineLayer(id = "trucknav-preview-others", source = srcOthers, color = const(Color(0xFF8a94a3)), width = const(6.dp), opacity = const(0.8f))
    LineLayer(id = "trucknav-preview-chosen-casing", source = srcChosen, color = const(Color(0xFF0f3d63)), width = const(11.dp))
    LineLayer(id = "trucknav-preview-chosen", source = srcChosen, color = const(Color(0xFF3583DD)), width = const(7.dp))
}
