package com.morton.trucknav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import androidx.compose.foundation.layout.Column
import uniffi.ferrostar.GeographicCoordinate

// Address / place search against a Photon geocoder (komoot's public instance
// by default; point photonUrl at a self-hosted one later). Results are biased
// toward the current location. No API key, no Google.
data class PhotonHit(
    val label: String,
    val coordinate: GeographicCoordinate,
    val letter: String = "",          // A, B, C… matches the map badge
    val distanceM: Double? = null,    // straight-line from the user, immediate
    val etaS: Double? = null,         // Valhalla matrix, arrives a moment later
) {
    fun distanceText() = distanceM?.let { m -> val mi = m / 1609.344; if (mi < 10) "%.1f mi".format(mi) else "${mi.toInt()} mi" } ?: ""
    fun etaText() = etaS?.let { s -> val m = (s / 60).toInt(); if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" } ?: ""
}

@Composable
fun PhotonSearch(
    userLocation: GeographicCoordinate?,
    modifier: Modifier = Modifier,
    onResults: (List<PhotonHit>) -> Unit = {},   // the map draws badges for these
    onPick: (PhotonHit) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PhotonHit>>(emptyList()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismiss = { focus.clearFocus(); keyboard?.hide() }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.length < 3) { hits = emptyList(); onResults(emptyList()); return@LaunchedEffect }
        delay(350) // debounce typing
        val raw = withContext(Dispatchers.IO) { photon(query, userLocation) }
        // letter + straight-line distance now; ETA from Valhalla's matrix a moment later
        val withDist = raw.map { h -> h.copy(distanceM = userLocation?.let { u -> haversine(u, h.coordinate) }) }
        hits = withDist.sortedBy { it.distanceM ?: Double.MAX_VALUE }.take(6).mapIndexed { i, h -> h.copy(letter = ('A' + i).toString()) }
        onResults(hits)
        val etas = withContext(Dispatchers.IO) { com.morton.trucknav.nav.matrixEtas(userLocation, hits.map { it.coordinate }) }
        if (etas != null) { hits = hits.mapIndexed { i, h -> h.copy(etaS = etas.getOrNull(i)) }; onResults(hits) }
    }

    // Search surface, cockpit rules: an opaque, elevated dark card so it reads
    // the same over a pale vector map, a dark map or satellite imagery; input
    // text 24 sp white (>= 4.5:1), placeholder muted but still >= 4.5:1;
    // 64 dp field, 72 dp result rows (car touch-target territory); capped
    // width so the map stays visible beside it in landscape.
    Column(modifier = modifier.widthIn(max = 560.dp)) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = SEARCH_SURFACE,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Search field" }
                .clickable { fieldFocus.requestFocus(); keyboard?.show() },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = SEARCH_MUTED, modifier = Modifier.size(28.dp))
                Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    if (query.isEmpty()) Text("Where to?", color = SEARCH_MUTED, fontSize = 24.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { dismiss() }),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; hits = emptyList(); onResults(emptyList()); dismiss() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        if (hits.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(20.dp), color = SEARCH_SURFACE, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 330.dp)) {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    hits.forEachIndexed { i, h ->
                        Row(
                            Modifier.semantics { contentDescription = "Result: " + h.label }
                                .fillMaxWidth().heightIn(min = 72.dp)
                                .clickable { query = ""; hits = emptyList(); onResults(emptyList()); dismiss(); onPick(h) }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF1f5f8b)), contentAlignment = Alignment.Center) {
                                Text(h.letter, color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                            Text(h.label, color = Color.White, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(h.distanceText(), color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Text(h.etaText(), color = SEARCH_MUTED, fontSize = 15.sp)
                            }
                        }
                        if (i < hits.lastIndex) HorizontalDivider(color = Color(0xFF2a323c))
                    }
                }
            }
        }
    }
}

private val SEARCH_SURFACE = Color(0xFF10141a)
private val SEARCH_MUTED = Color(0xFFaab4c0)

private fun haversine(a: GeographicCoordinate, b: GeographicCoordinate): Double {
    val r = 6371000.0; val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
    val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLng / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(h))
}

private fun photon(q: String, near: GeographicCoordinate?): List<PhotonHit> = try {
    val bias = near?.let { "&lat=${it.lat}&lon=${it.lng}" } ?: ""
    val url = "${AppModule.photonUrl}?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=6&lang=en$bias"
    val body = AppModule.okHttp.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } ?: return emptyList()
    Json.parseToJsonElement(body).jsonObject["features"]!!.jsonArray.mapNotNull { f ->
        val o = f.jsonObject
        val c = o["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        val p = o["properties"]!!.jsonObject
        val parts = listOf("name", "street", "housenumber", "city", "state").mapNotNull { p[it]?.jsonPrimitive?.content }
        if (parts.isEmpty()) null
        else PhotonHit(parts.distinct().joinToString(", "), GeographicCoordinate(lat = c[1].jsonPrimitive.content.toDouble(), lng = c[0].jsonPrimitive.content.toDouble()))
    }
} catch (e: Exception) { emptyList() }
