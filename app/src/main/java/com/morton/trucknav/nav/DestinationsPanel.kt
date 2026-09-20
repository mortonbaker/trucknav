package com.morton.trucknav.nav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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

// The destinations list: Favorites (Home, Work, then saved places) or Recents
// (newest first). Vertical rows of 76 dp, six on a screen, swipe for more.
// Tap = go. Hold a row = a red Remove appears on it (Tesla's gesture); tap it
// to delete, tap anywhere else to cancel. A recent's star saves it as a place.
// One Valhalla matrix call per open for the drive times.
private data class Dest(val key: String, val name: String, val c: GeographicCoordinate, val icon: ImageVector, val removable: Boolean, val recent: Boolean, val etaS: Double? = null)

private val PANEL = Color(0xFF10141a)
private val MUTED = Color(0xFFaab4c0)
private val ACCENT = Color(0xFF1f5f8b)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DestinationsPanel(
    tab: DestTab,
    userLocation: GeographicCoordinate?,
    modifier: Modifier = Modifier,
    onTab: (DestTab) -> Unit,
    onClose: () -> Unit,
    onGo: (name: String, c: GeographicCoordinate) -> Unit,
) {
    val favs by Favorites.all.collectAsState()
    val recents by Favorites.recent.collectAsState()
    val base = remember(tab, favs, recents) {
        when (tab) {
            DestTab.Favorites -> listOfNotNull(
                favs.firstOrNull { it.kind == Favorites.HOME }?.let { Dest(it.id, "Home", it.coordinate, Icons.Filled.Home, false, false) },
                favs.firstOrNull { it.kind == Favorites.WORK }?.let { Dest(it.id, "Work", it.coordinate, Icons.Filled.Work, false, false) },
            ) + favs.filter { it.kind == Favorites.PLACE }.sortedByDescending { it.addedAt }.map { Dest(it.id, it.name, it.coordinate, Icons.Filled.Star, true, false) }
            DestTab.Recents -> recents.map { Dest("r-" + it.at, it.name, it.coordinate, Icons.Filled.History, false, true) }
        }
    }
    var rows by remember { mutableStateOf(base) }
    var armed by remember { mutableStateOf<String?>(null) }     // row whose Remove is showing
    LaunchedEffect(base, userLocation?.let { "%.3f,%.3f".format(it.lat, it.lng) }) {
        rows = base; armed = null
        if (userLocation == null || base.isEmpty()) return@LaunchedEffect
        val etas = withContext(Dispatchers.IO) { matrixEtas(userLocation, base.map { it.c }) }
        if (etas != null) rows = base.mapIndexed { i, d -> d.copy(etaS = etas.getOrNull(i)) }
    }
    val savedNames = remember(favs) { favs.map { it.name }.toSet() }

    Surface(shape = RoundedCornerShape(24.dp), color = PANEL, shadowElevation = 10.dp, modifier = modifier.semantics { contentDescription = "Destinations panel" }) {
        Column(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null) { armed = null }) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab("Favorites", tab == DestTab.Favorites) { onTab(DestTab.Favorites) }
                Tab("Recents", tab == DestTab.Recents) { onTab(DestTab.Recents) }
                IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close destinations", tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (tab == DestTab.Favorites) "No saved places yet. Search, then Save as Home, Work or a place." else "Nowhere yet. Places you navigate to show up here.", color = MUTED, fontSize = 18.sp)
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.key }) { d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 76.dp)
                            .combinedClickable(onClick = { if (armed != null) armed = null else onGo(d.name, d.c) }, onLongClick = { if (d.removable || d.recent) armed = d.key })
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .semantics { contentDescription = "Go: " + d.name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(d.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(d.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (d.etaS != null) Text(etaText(d.etaS), color = MUTED, fontSize = 16.sp)
                        }
                        when {
                            armed == d.key -> Box(
                                Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF8b1f1f))
                                    .clickable { if (d.recent) Favorites.forgetRecent(d.name) else Favorites.remove(d.key); armed = null }
                                    .padding(horizontal = 16.dp).semantics { contentDescription = "Remove " + d.name },
                                contentAlignment = Alignment.Center,
                            ) { Text("Remove", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                            d.recent && d.name !in savedNames -> IconButton(onClick = { Favorites.save(d.name, d.c) }, modifier = Modifier.size(56.dp).semantics { contentDescription = "Save " + d.name }) {
                                Icon(Icons.Filled.StarBorder, contentDescription = null, tint = MUTED, modifier = Modifier.size(30.dp))
                            }
                            d.recent -> Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFe8c547), modifier = Modifier.size(30.dp).padding(end = 4.dp))
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2a323c))
                }
            }
        }
    }
}

@Composable
private fun RowScope.Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(52.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) ACCENT else Color.Transparent).clickable(onClick = onClick)
            .semantics { contentDescription = "Tab " + label },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}
