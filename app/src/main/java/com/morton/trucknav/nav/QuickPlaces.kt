package com.morton.trucknav.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate

// Under the search pill, exactly four tiles and never a scroll: Home, Work,
// Favorites, Recents. Home/Work are one tap to go and show the drive time
// (one Valhalla matrix call, refreshed when you move ~100 m). Favorites and
// Recents open the destinations panel (DestinationsPanel.kt), a vertical list.
// This is the Tesla / Android-Auto shape: fixed shortcuts on the map, lists in
// a panel, nothing horizontal, everything readable in a two-second glance.
enum class DestTab { Favorites, Recents }

fun etaText(s: Double?) = s?.let { val m = (it / 60).toInt(); if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" } ?: ""

@Composable
fun QuickPlaces(
    userLocation: GeographicCoordinate?,
    modifier: Modifier = Modifier,
    onOpen: (DestTab) -> Unit,
    onSet: (kind: String) -> Unit,          // "Set Home" / "Set Work": focus the search, save from the sheet
    onGo: (name: String, c: GeographicCoordinate) -> Unit,
) {
    val favs by Favorites.all.collectAsState()
    val recents by Favorites.recent.collectAsState()
    val home = favs.firstOrNull { it.kind == Favorites.HOME }
    val work = favs.firstOrNull { it.kind == Favorites.WORK }
    val places = favs.count { it.kind == Favorites.PLACE }
    var etas by remember { mutableStateOf<List<Double?>>(emptyList()) }
    LaunchedEffect(home?.id, work?.id, userLocation?.let { "%.3f,%.3f".format(it.lat, it.lng) }) {
        val targets = listOfNotNull(home?.coordinate, work?.coordinate)
        etas = if (userLocation == null || targets.isEmpty()) emptyList() else withContext(Dispatchers.IO) { matrixEtas(userLocation, targets) } ?: emptyList()
    }
    Row(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(Icons.Filled.Home, if (home != null) "Home" else "Set Home", if (home != null) etaText(etas.getOrNull(0)) else "", desc = if (home != null) "Go: Home" else "Set Home",
            onTap = { if (home != null) onGo("Home", home.coordinate) else onSet(Favorites.HOME) })
        Tile(Icons.Filled.Work, if (work != null) "Work" else "Set Work", if (work != null) etaText(etas.getOrNull(if (home != null) 1 else 0)) else "", desc = if (work != null) "Go: Work" else "Set Work",
            onTap = { if (work != null) onGo("Work", work.coordinate) else onSet(Favorites.WORK) })
        Tile(Icons.Filled.Star, "Favorites", if (places == 1) "1 place" else "$places places", desc = "Open favorites", onTap = { onOpen(DestTab.Favorites) })
        Tile(Icons.Filled.History, "Recents", recents.firstOrNull()?.name ?: "", desc = "Open recents", onTap = { onOpen(DestTab.Recents) })
    }
}

@Composable
private fun RowScope.Tile(icon: ImageVector, title: String, sub: String, desc: String, onTap: () -> Unit) {
    // Icon over title over detail: four across fit inside the 58 % search column without truncating.
    Column(
        Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF10141a))
            .clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = desc },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = Color(0xFFaab4c0), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
