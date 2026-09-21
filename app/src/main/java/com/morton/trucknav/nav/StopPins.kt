package com.morton.trucknav.nav

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.*
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.*
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point

private class FinishFlag : Painter() {
    override val intrinsicSize = Size(48f, 56f)
    override fun DrawScope.onDraw() {
        val sx = size.width / 48f; val sy = size.height / 56f
        drawLine(Color.White, Offset(8*sx, 4*sy), Offset(8*sx, 54*sy), 5*sx)
        drawRect(Color.White, Offset(7*sx, 3*sy), Size(37*sx, 29*sy))
        for (r in 0..2) for (c in 0..3) {
            drawRect(if ((r+c)%2 == 0) Color.Black else Color.White,
                Offset((9+c*8)*sx, (5+r*8)*sy), Size(8*sx, 8*sy))
        }
    }
}

@Composable
@MaplibreComposable
fun StopPins(stops: List<TripStop>) {
    LaunchedEffect(stops) {
        NavLog.log("stop-pins", "numbered=" + (stops.size - 1).coerceAtLeast(0) +
            " flag=" + if (stops.isEmpty()) "0" else "1")
    }
    if (stops.isEmpty()) return
    val numbered = FeatureCollection(stops.dropLast(1).mapIndexed { i, s ->
        Feature(geometry = Point(s.coordinate.lng, s.coordinate.lat),
            properties = buildJsonObject { put("number", (i+1).toString()) })
    })
    val source = rememberGeoJsonSource(GeoJsonData.Features(numbered))
    CircleLayer(id = "trucknav-stop-badges", source = source, radius = const(20.dp),
        color = const(Color(0xFF1F5F8B)), strokeColor = const(Color.White), strokeWidth = const(3.dp))
    SymbolLayer(id = "trucknav-stop-numbers", source = source, textField = feature["number"].asString(),
        textFont = const(listOf("Noto Sans Medium")), textSize = const(24.sp), textColor = const(Color.White),
        textAllowOverlap = const(true), textIgnorePlacement = const(true))
    val dest = stops.last().coordinate
    val flag = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(
        Feature(geometry = Point(dest.lng, dest.lat), properties = buildJsonObject { put("destination", true) }))))
    val painter = remember { FinishFlag() }
    SymbolLayer(id = "trucknav-destination-flag", source = flag,
        iconImage = image(painter, size = DpSize(48.dp,56.dp), drawAsSdf = false),
        iconAnchor = const(SymbolAnchor.BottomLeft), iconAllowOverlap = const(true), iconIgnorePlacement = const(true))
}
