package com.morton.trucknav.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate

// One-tap destinations under the search pill: Home, Work, saved places, then
// recents. Each tile shows drive time from where you are (one Valhalla matrix
// call, refreshed every 60 s). Tap = go. Long-press a saved place = remove.
data class QuickPlace(val key: String, val name: String, val coordinate: GeographicCoordinate, val icon: ImageVector, val removable: Boolean, val etaS: Double? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickPlaces(userLocation: GeographicCoordinate?, modifier: Modifier = Modifier, onGo: (QuickPlace) -> Unit) {
    val favs by Favorites.all.collectAsState()
    val recents by Favorites.recent.collectAsState()
    val base = remember(favs, recents) {
        val home = favs.firstOrNull { it.kind == Favorites.HOME }; val work = favs.firstOrNull { it.kind == Favorites.WORK }
        val places = favs.filter { it.kind == Favorites.PLACE }.sortedByDescending { it.addedAt }
        val savedNames = (listOfNotNull(home, work) + places).map { it.name }.toSet()
        listOfNotNull(
            home?.let { QuickPlace(it.id, "Home", it.coordinate, Icons.Filled.Home, false) },
            work?.let { QuickPlace(it.id, "Work", it.coordinate, Icons.Filled.Work, false) },
        ) + places.map { QuickPlace(it.id, it.name, it.coordinate, Icons.Filled.Star, true) } +
            recents.filter { it.name !in savedNames }.take(4).map { QuickPlace("r-" + it.at, it.name, it.coordinate, Icons.Filled.History, false) }
    }
    var tiles by remember { mutableStateOf(base) }
    LaunchedEffect(base, userLocation?.let { "%.3f,%.3f".format(it.lat, it.lng) }) {
        tiles = base
        if (userLocation == null || base.isEmpty()) return@LaunchedEffect
        val etas = withContext(Dispatchers.IO) { matrixEtas(userLocation, base.map { it.coordinate }) }
        if (etas != null) tiles = base.mapIndexed { i, t -> t.copy(etaS = etas.getOrNull(i)) }
    }
    if (tiles.isEmpty()) return
    Row(modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { t ->
            Column(
                Modifier.semantics { contentDescription = "Go: " + t.name }
                    .width(150.dp).height(78.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF10141a))
                    .combinedClickable(onClick = { onGo(t) }, onLongClick = { if (t.removable) Favorites.remove(t.key) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text(t.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
                Text(t.etaS?.let { s -> val m = (s / 60).toInt(); if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" } ?: "", color = Color(0xFFaab4c0), fontSize = 15.sp)
            }
        }
    }
}
