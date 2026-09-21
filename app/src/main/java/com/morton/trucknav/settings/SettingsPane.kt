package com.morton.trucknav.settings

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morton.trucknav.*
import com.morton.trucknav.nav.Favorites
import com.morton.trucknav.nav.NavPrefs
import com.morton.trucknav.nav.VoiceGate
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsPane(modifier: Modifier = Modifier, onChooseMap: (String) -> Unit = {}) {
    var section by rememberSaveable { mutableStateOf("Places") }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxSize(), color = Color(0xFF10141a), contentColor = Color.White) {
        Column(Modifier.padding(12.dp)) {
            Text("Settings", fontSize = 28.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "Settings sections" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Places", "Vehicle", "Servers", "Units", "Voice", "API", "About").forEach { title ->
                    FilterChip(selected = section == title, onClick = { section = title; error = null }, label = { Text(title, fontSize = 20.sp) }, modifier = Modifier.heightIn(min = 56.dp))
                }
            }
            error?.let { Text(it, color = Color(0xFFffb4ab), fontSize = 20.sp) }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (section) {
                    "Places" -> PlacesSettings(onChooseMap)
                    "Vehicle" -> {
                        val bitmap by VehicleImage.bitmap.collectAsState()
                        val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                            if (uri != null) scope.launch {
                                error = runCatching { withContext(Dispatchers.IO) {
                                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readNBytes(VehicleImage.MAX_BYTES + 1) }
                                    VehicleImage.put(requireNotNull(bytes))
                                } }.exceptionOrNull()?.message
                            }
                        }
                        bitmap?.let { Image(it.asImageBitmap(), "Current vehicle", Modifier.size(160.dp)) }
                        Text("Top view, nose up. Transparent PNG recommended; PNG or JPEG up to 2 MB. Saved at 256 × 256.", fontSize = 24.sp)
                        Action("Replace vehicle") { pick.launch("image/*") }
                        Action("Restore default vehicle") { VehicleImage.delete() }
                    }
                    "Servers" -> {
                        Text("Changes apply to new connections. Restart TruckNav after changing Valhalla to refresh route previews.", fontSize = 24.sp)
                        listOf("valhallaUrl" to "Valhalla URL (blank = on-device only)", "photonUrl" to "Photon URL",
                            "styleUrl" to "Map style URL", "absUrl" to "Audiobookshelf URL", "absUser" to "Audiobookshelf username",
                            "absPass" to "Audiobookshelf password", "venusHost" to "Venus MQTT host",
                            "venusPortalId" to "Venus portal ID", "relayHost" to "Relay host (blank = discover)").forEach { (key, label) -> SettingField(key, label) }
                        // S22 traffic.TrafficSettings() is inserted here after that branch lands.
                        SettingField("tomtomKey", "TomTom key")
                        SettingField("googleMapsKey", "Google Maps key")
                        Text("Traffic provider", fontSize = 24.sp)
                        Choice("trafficProvider", listOf("off", "tomtom", "google"))
                        Text("Google provides traffic ETA only; TomTom also provides flow tiles.", fontSize = 20.sp)
                    }
                    "Units" -> {
                        Text("Distance units", fontSize = 24.sp)
                        Choice("units", listOf("imperial", "metric"))
                        Text("Search distances follow this choice. Route preview and trip displays currently use miles.", fontSize = 20.sp)
                    }
                    "Voice" -> {
                        val disabled by NavPrefs.disabledClasses.collectAsState()
                        val night by NavPrefs.autoNight.collectAsState()
                        VoiceGate.CLASSES.forEach { cls -> Toggle("Voice " + cls, cls !in disabled) { NavPrefs.setClass(cls, it) } }
                        Toggle("Auto night", night) { NavPrefs.setAutoNight(it) }
                    }
                    "API" -> ApiSettings()
                    "About" -> {
                        Text("TruckNav " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", fontSize = 24.sp)
                        PackStatus()
                        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                            if (uri != null) scope.launch {
                                error = runCatching { withContext(Dispatchers.IO) {
                                    ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                                        File(ctx.getExternalFilesDir(null), "navlog").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                                            out.appendLine("=== " + f.name + " ==="); f.forEachLine { out.appendLine(it) }
                                        }
                                    }
                                } }.exceptionOrNull()?.message
                            }
                        }
                        Action("Export navigation logs") { export.launch("trucknav-logs.txt") }
                        Text("Map data © OpenStreetMap contributors. MapLibre / Protomaps. Routing: Valhalla / Ferrostar.", fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable private fun SettingField(key: String, label: String) {
    val persisted by Settings.flow(key).collectAsState()
    var text by remember(key, persisted) { mutableStateOf(persisted.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        visualTransformation = if (Settings.isSecret(key)) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp))
    Action("Save " + label) { error = runCatching { Settings.set(key, text) }.exceptionOrNull()?.message }
    error?.let { Text(it, color = Color(0xFFffb4ab)) }
}
@Composable private fun Choice(key: String, choices: List<String>) {
    val selected by Settings.flow(key).collectAsState()
    choices.forEach { value -> FilterChip(selected == value, { Settings.set(key, value) }, label = { Text(value, fontSize = 24.sp) }, modifier = Modifier.heightIn(min = 56.dp)) }
}
@Composable private fun Toggle(label: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Switch(on, change, modifier = Modifier.semantics { contentDescription = label })
    }
}
@Composable private fun Action(label: String, enabled: Boolean = true, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label, fontSize = 24.sp) }
}

@Composable private fun PlacesSettings(onChooseMap: (String) -> Unit) {
    val saved by Favorites.all.collectAsState()
    val loc by AppModule.viewModel.location.collectAsState()
    var searchFor by rememberSaveable { mutableStateOf<String?>(null) }
    listOf(Favorites.HOME, Favorites.WORK).forEach { kind ->
        val f = saved.firstOrNull { it.kind == kind }
        Text(kind.replaceFirstChar { it.uppercase() } + ": " + (f?.name ?: "Not set"), fontSize = 24.sp)
        f?.let { Text("%.5f, %.5f".format(it.lat, it.lng), fontSize = 20.sp) }
        Action("Set " + kind + " from current location", loc != null) { loc?.let { Favorites.save(kind.replaceFirstChar { it.uppercase() }, it.coordinates, kind) } }
        Action("Search for " + kind) { searchFor = kind }
        Action("Choose " + kind + " on map") { onChooseMap(kind) }
        if (f != null) Action("Remove " + kind) { Favorites.remove(f.id) }
    }
    searchFor?.let { kind ->
        Text("Choose a result for " + kind, fontSize = 24.sp)
        PhotonSearch(userLocation = loc?.coordinates, modifier = Modifier.fillMaxWidth(), onPick = {
            Favorites.save(it.label, it.coordinate, kind); searchFor = null
        })
    }
}

@Composable fun ApiSettings() {
    val token by Settings.flow("apiToken").collectAsState()
    var show by remember { mutableStateOf(false) }
    Text("HTTP API :8782 · Bearer token", fontSize = 24.sp)
    Text("Keep the token private. Regenerate disconnects existing clients.", fontSize = 20.sp)
    Action(if (show) "Hide token and QR" else "Show token and QR") { show = !show }
    if (show) token?.let { secret ->
        val qr = remember(secret) {
            val matrix = QRCodeWriter().encode(secret, BarcodeFormat.QR_CODE, 320, 320)
            Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(320 * 320) { n -> if (matrix[n % 320, n / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 320, 0, 0, 320, 320)
            }
        }
        Image(qr.asImageBitmap(), "API token QR", Modifier.size(240.dp).background(Color.White))
        androidx.compose.foundation.text.selection.SelectionContainer { Text(secret, fontSize = 20.sp) }
    }
    Action("Regenerate API token") { Configuration.regenerateToken(); show = true }
}

@Composable fun PackStatus() {
    val ctx = LocalContext.current
    val root = ctx.getExternalFilesDir(null)
    val maps = root?.listFiles()?.filter { it.extension == "pmtiles" }.orEmpty()
    Text("Basemap: " + (maps.firstOrNull()?.let { it.name + " · " + it.length() / 1024 / 1024 + " MB" } ?: "Not installed"), fontSize = 24.sp)
    Text("Routing pack: " + if (File(root, "routing/valhalla_tiles.tar").exists()) "Installed" else "Not installed", fontSize = 24.sp)
    if (maps.isEmpty()) Text("Offline setup (RUNBOOK): copy your PMTiles extract, fonts/, sprites/ and style-*.json into Android/data/com.morton.trucknav/files/. Install routing/valhalla_tiles.tar for offline routes. Then restart TruckNav. The online map is available meanwhile.", fontSize = 24.sp)
}

@Composable fun FirstRun() {
    val done by Settings.flow("setupComplete").collectAsState()
    if (done == "true") return
    AlertDialog(onDismissRequest = {}, title = { Text("Set up your truck", fontSize = 28.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your API token has been generated. Open Settings → API to view its QR code. Set Home, Work, servers and your vehicle in Settings.", fontSize = 24.sp)
            ApiSettings()
            PackStatus()
            Text(if (Configuration.value("valhallaUrl").isBlank()) "Routing: on-device only. Install a routing pack or configure Valhalla in Settings." else "Routing server configured; on-device routing is the fallback.", fontSize = 24.sp)
        } },
        confirmButton = { Button({ Settings.set("setupComplete", "true") }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Continue to map", fontSize = 24.sp) } })
}
