package com.morton.trucknav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.stadiamaps.ferrostar.core.NavigationUiState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import uniffi.ferrostar.TripState

// The 4Runner, top-down, as the location marker. Replaces Ferrostar's dot/arrow
// in both browsing and navigating states. Lies flat on the map (pitch + rotation
// aligned to the map) so it tilts with the navigation camera and points along
// the course. Position and heading glide over ~1 s between fixes; while
// navigating the route-snapped location is used so it stays on the line.
@Composable
@MaplibreComposable
fun VehiclePuck(uiState: NavigationUiState) {
    val loc = (uiState.tripState as? TripState.Navigating)?.snappedUserLocation ?: uiState.location ?: return
    val lat = loc.coordinates.lat
    val lng = loc.coordinates.lng
    val course = loc.courseOverGround?.let { (it.degrees.toInt() and 0xffff).toDouble() }

    // From / to endpoints in doubles; only the blend factor is animated, so no
    // float precision is lost on the coordinates themselves.
    var fromLat by remember { mutableStateOf(lat) }
    var fromLng by remember { mutableStateOf(lng) }
    var fromBearing by remember { mutableStateOf(course ?: 0.0) }
    var toLat by remember { mutableStateOf(lat) }
    var toLng by remember { mutableStateOf(lng) }
    var toBearing by remember { mutableStateOf(course ?: 0.0) }
    val t = remember { Animatable(1f) }

    LaunchedEffect(lat, lng, course) {
        val f = t.value
        fromLat = fromLat + (toLat - fromLat) * f
        fromLng = fromLng + (toLng - fromLng) * f
        fromBearing = fromBearing + shortestArc(fromBearing, toBearing) * f
        toLat = lat; toLng = lng
        course?.let { toBearing = it }   // stationary: keep the last heading
        t.snapTo(0f)
        t.animateTo(1f, tween(1000, easing = LinearEasing))
    }

    val f = t.value.toDouble()
    val dLat = fromLat + (toLat - fromLat) * f
    val dLng = fromLng + (toLng - fromLng) * f
    val bearing = (fromBearing + shortestArc(fromBearing, toBearing) * f + 360.0) % 360.0

    val source = rememberGeoJsonSource(
        GeoJsonData.Features(FeatureCollection(Feature(geometry = Point(dLng, dLat), properties = buildJsonObject { put("bearing", bearing) }))),
        options = GeoJsonOptions(synchronousUpdate = true),
    )
    val custom by com.morton.trucknav.settings.VehicleImage.bitmap.collectAsState()
    val painter = custom?.let { remember(it) { BitmapPainter(it.asImageBitmap()) } } ?: painterResource(R.drawable.vehicle_top)
    SymbolLayer(
        id = "trucknav-vehicle-puck",
        source = source,
        iconImage = image(painter, size = DpSize(84.dp, 84.dp), drawAsSdf = false),
        iconAnchor = const(SymbolAnchor.Center),
        iconRotate = feature["bearing"].asNumber(const(0f)),
        iconPitchAlignment = const(IconPitchAlignment.Map),
        iconRotationAlignment = const(IconRotationAlignment.Map),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
    )
}

private fun shortestArc(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d > 180) d -= 360.0
    if (d < -180) d += 360.0
    return d
}
