package com.morton.trucknav.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morton.trucknav.PhotonHit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

// Search results on the map: lettered badges (A, B, C…) that match the rows in
// the results list, the way Google Maps / Android Auto place lists do it.
// Tapping a badge is the same as tapping its row.
@Composable
@MaplibreComposable
fun SearchResultsOverlay(hits: List<PhotonHit>, onPick: (PhotonHit) -> Unit) {
    if (hits.isEmpty()) return
    val fc = FeatureCollection(hits.mapIndexed { i, h ->
        Feature(geometry = Point(h.coordinate.lng, h.coordinate.lat), properties = buildJsonObject { put("letter", h.letter); put("idx", i) })
    })
    val source = rememberGeoJsonSource(GeoJsonData.Features(fc))
    CircleLayer(
        id = "trucknav-search-badges",
        source = source,
        radius = const(18.dp),
        color = const(Color(0xFF1f5f8b)),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
        onClick = { features ->
            val idx = features.firstOrNull()?.properties?.get("idx")?.jsonPrimitive?.intOrNull
            if (idx != null && idx in hits.indices) { onPick(hits[idx]); ClickResult.Consume } else ClickResult.Pass
        },
    )
    SymbolLayer(
        id = "trucknav-search-letters",
        source = source,
        textField = feature["letter"].asString(),
        textFont = const(listOf("Noto Sans Medium")),
        textSize = const(18.sp),
        textColor = const(Color.White),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}
