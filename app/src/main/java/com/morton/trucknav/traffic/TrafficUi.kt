package com.morton.trucknav.traffic

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morton.trucknav.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.*
import org.maplibre.compose.layers.*
import org.maplibre.compose.sources.*
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.*
import org.maplibre.spatialk.geojson.Feature

/** Insert before pins/route overlays so transparent flow sits above base roads. */
@Composable @MaplibreComposable
fun TrafficLayer() {
    val generation by Traffic.generation.collectAsState()
    val online by Traffic.online.collectAsState()
    val incidents by Traffic.incidents.collectAsState()
    if (!online || !Traffic.flowVisible()) return
    key(generation) {
        val source = rememberRasterSource(tiles = listOf(Traffic.tileTemplate()), tileSize = 512,
            options = TileSetOptions(maxZoom = 14, attributionHtml = "Traffic © TomTom"))
        RasterLayer(id = "trucknav-traffic-flow", source = source)
    }
    if (incidents.isNotEmpty()) {
        val source = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(incidents.map {
            Feature(geometry = Point(it.position.lng, it.position.lat), properties = buildJsonObject { put("label", it.label) })
        })))
        CircleLayer(id = "trucknav-traffic-incidents", source = source, color = const(Color(0xFFE8A441)), radius = const(9.dp), strokeWidth = const(2.dp), strokeColor = const(Color.Black))
        SymbolLayer(id = "trucknav-traffic-labels", source = source, textField = feature["label"].asString(), textSize = const(24.sp), textColor = const(Color.White), textHaloColor = const(Color.Black), textHaloWidth = const(2.dp))
    }
}

@Composable
fun TrafficLayerToggle() {
    val setting by Settings.flow("trafficLayer").collectAsState()
    val provider by Settings.flow("trafficProvider").collectAsState()
    val online by Traffic.online.collectAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Traffic", color = Color.White, fontSize = 24.sp)
            Text(when { provider == "google" -> "Google Maps: ETA only"; provider != "tomtom" -> "Choose a provider in Settings"; !online -> "Hidden offline"; else -> "TomTom flow and incidents" }, color = Color.White, fontSize = 16.sp)
        }
        Switch(checked = setting == "true", enabled = provider == "tomtom", onCheckedChange = Traffic::setOverlay,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Traffic " + if (setting == "true") "on" else "off" })
    }
}

/** Owned by traffic; the S20 pane calls this once. Never displays saved keys in plaintext. */
@Composable
fun TrafficSettings() {
    val provider by Settings.flow("trafficProvider").collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Traffic", fontSize = 24.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off", "tomtom", "google").forEach { id ->
                FilterChip(selected = (provider ?: "off") == id, onClick = { Settings.set("trafficProvider", id) },
                    label = { Text(if (id == "google") "Google (ETA only)" else id.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        TrafficKeyField("tomtom", "TomTom", "tomtomKey")
        TrafficKeyField("google", "Google Maps", "googleMapsKey")
        Text("Your own API keys are required. Google supplies ETA only. Provider usage may be billed to your account.")
    }
}

@Composable private fun TrafficKeyField(id: String, label: String, setting: String) {
    val saved by Settings.flow(setting).collectAsState()
    var value by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), label = { Text("$label API key") },
            supportingText = { Text(if (saved.isNullOrBlank()) "Not configured" else if (saved.orEmpty().length <= 4) "Saved" else "Saved ending ${saved.orEmpty().takeLast(4)}") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = value.isNotBlank() && !busy, onClick = { Settings.set(setting, value.trim()); value = ""; status = "Saved" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save $label key") }
            OutlinedButton(enabled = !saved.isNullOrBlank() && !busy, onClick = { Settings.set(setting, null); status = "Removed" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
            OutlinedButton(enabled = !busy, onClick = { busy = true; scope.launch { try { status = Traffic.testKey(id) } finally { busy = false } } }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Test $label key" }) { Text(if (busy) "Testing…" else "Test key") }
        }
        if (status.isNotEmpty()) Text("$label: $status", fontSize = 24.sp)
    }
}

/** Optional nav consumer: one independent request per candidate; timeout leaves no extra line. */
@Composable
fun TrafficEtaText(polyline: List<TrafficPoint>, modifier: Modifier = Modifier) {
    val generation by Traffic.generation.collectAsState()
    val online by Traffic.online.collectAsState()
    var eta by remember(polyline, online, Settings.get("trafficProvider")) { mutableStateOf<TrafficEta?>(null) }
    LaunchedEffect(polyline, online, generation) {
        eta = Traffic.etaWithTraffic(polyline)
        // A late result is never displayed; TTL expiry is enforced even when nothing else changes.
        if (eta != null) { delay(600_000); eta = null }
    }
    eta?.takeIf { online && it.fresh(android.os.SystemClock.elapsedRealtime()) }?.let {
        Column(modifier) { Text(it.label, fontSize = 24.sp); Text(it.attribution, fontSize = 12.sp) }
    }
}
