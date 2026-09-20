package com.morton.trucknav

import android.content.res.Configuration
import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morton.trucknav.media.MediaPane
import com.morton.trucknav.media.NowPlayingPane
import com.morton.trucknav.media.BooksBrowser
import com.morton.trucknav.media.YouTubePane
import com.morton.trucknav.power.PowerStrip
import com.morton.trucknav.media.MediaSource
import com.morton.trucknav.media.SessionWatcher
import com.morton.trucknav.overlay.OverlayService
import com.morton.trucknav.power.PowerPane
import com.morton.trucknav.power.VenusClient
import com.morton.trucknav.power.RelayClient

// The head unit. A rail of destinations on the edge, the map as the primary
// pane, and a secondary pane (media / power / apps) beside it. The rail is
// the launcher: there is no other home screen. Whatever is making sound is
// controllable from the strip under the rail regardless of which app owns it.
enum class Pane { Map, Music, Books, YouTube, Power, Vehicle, Apps }

@Composable
fun CockpitScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var pane by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Pane.Music) }  // survives rotation

    val venus = remember { VenusClient(ctx.applicationContext, BuildConfig.venusHost, BuildConfig.venusPortalId, scope) }
    val relay = remember { RelayClient(ctx.applicationContext).also { it.start() } }
    val music = remember { MediaSource(ctx, "com.unicornsonlsd.finamp", "Music") }
    val watcher = remember { SessionWatcher(ctx) }
    DisposableEffect(Unit) {
        venus.start(); watcher.start(); OverlayService.start(ctx)
        onDispose { venus.stop(); watcher.stop(); music.disconnect() }
    }

    val rail: @Composable (Modifier) -> Unit = { m ->
        Rail(m, pane, landscape, watcher, onSelect = { pane = it }, onYouTube = { pane = Pane.YouTube },
            onOpenSession = { pkg -> pane = when (pkg) { BuildConfig.APPLICATION_ID -> Pane.Books; "com.audiobookshelf.app" -> Pane.Books; "org.schabi.newpipe" -> Pane.YouTube; else -> Pane.Music } })
    }
    val side: (@Composable (Modifier) -> Unit)? = when (pane) {
        Pane.Map -> null
        Pane.Music -> { m -> NowPlayingPane(watcher, "com.unicornsonlsd.finamp", browser = { done -> MediaPane(listOf(music), Modifier.fillMaxSize(), onDone = done) }, isBook = false, modifier = m.padding(8.dp)) }
        Pane.Books -> { m -> NowPlayingPane(watcher, BuildConfig.APPLICATION_ID, browser = { done -> BooksBrowser(watcher, Modifier.fillMaxSize(), onDone = done) }, isBook = true, modifier = m.padding(8.dp)) }
        Pane.YouTube -> { m -> YouTubePane(m.padding(8.dp)) }
        Pane.Power -> { m -> Column(m.padding(8.dp)) { PowerPane(venus, relay) } }
        Pane.Vehicle -> { m -> com.morton.trucknav.power.VehiclePane(relay, m.padding(8.dp)) }
        Pane.Apps -> { m -> AppsPane(m.padding(8.dp), onSelect = { pane = it }) }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0b0e12))) {
        StatusStrip()
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                rail(Modifier.width(88.dp).fillMaxHeight())
                Column(Modifier.weight(if (side == null) 1f else 0.6f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { DemoNavigationScene() }
                    PowerStrip(venus, relay)
                }
                side?.let { it(Modifier.weight(0.4f).fillMaxHeight()) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(if (side == null) 1f else 0.5f).fillMaxWidth()) { DemoNavigationScene() }
                PowerStrip(venus, relay)
                side?.let { it(Modifier.weight(0.5f).fillMaxWidth()) }
                rail(Modifier.fillMaxWidth().height(88.dp))
            }
        }
    }
}

@Composable
private fun Rail(modifier: Modifier, current: Pane, vertical: Boolean, watcher: SessionWatcher, onSelect: (Pane) -> Unit, onYouTube: () -> Unit, onOpenSession: (String) -> Unit) {
    val items: List<Triple<Pane?, ImageVector, String>> = listOf(
        Triple(Pane.Map, Icons.Filled.Map, "Map"),
        Triple(Pane.Music, Icons.Filled.MusicNote, "Music"),
        Triple(Pane.Books, Icons.Filled.Headphones, "Books"),
        Triple(Pane.YouTube, Icons.Filled.SmartDisplay, "YouTube"),
        Triple(Pane.Power, Icons.Filled.Bolt, "Power"),
        Triple(Pane.Vehicle, Icons.Filled.ToggleOn, "Vehicle"),
        Triple(Pane.Apps, Icons.Filled.Apps, "Apps"),
    )
    val button: @Composable (Triple<Pane?, ImageVector, String>, Modifier) -> Unit = { (p, icon, label), slot ->
        val selected = p != null && p == current
        Column(
            slot.padding(4.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) Color(0xFF1f5f8b) else Color.Transparent)
                .clickable { if (p != null) onSelect(p) else onYouTube() }
                .semantics { this.selected = selected }
                .heightIn(min = 56.dp).padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    if (vertical) {
        Column(modifier.background(Color(0xFF10141a)).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            items.forEach { button(it, Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(4.dp))
            NowPlayingMini(watcher, vertical = true, onOpen = onOpenSession)
        }
    } else {
        Row(modifier.background(Color(0xFF10141a)), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { button(it, Modifier.weight(1f)) }
            NowPlayingMini(watcher, vertical = false, onOpen = onOpenSession)
        }
    }
}

// Transport for whatever is playing on the device, any app. Compact: art plus
// play/pause in the rail; the full strip lives in the media pane.
@Composable
private fun NowPlayingMini(watcher: SessionWatcher, vertical: Boolean, onOpen: (String) -> Unit) {
    val a by watcher.active.collectAsState()
    val s = a ?: return
    val ctx = LocalContext.current
    // Thumbnail only: transport lives in the pane, where the buttons are big enough to hit at speed.
    val content: @Composable () -> Unit = {
        s.art?.let { Image(it.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpen(s.pkg) }) }
    }
    if (vertical) Column(Modifier.padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    else Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun AppsPane(modifier: Modifier, onSelect: (Pane) -> Unit) {
    val ctx = LocalContext.current
    data class Entry(val label: String, val icon: ImageVector, val pane: Pane?, val color: Long)
    val entries = listOf(
        Entry("Map", Icons.Filled.Map, Pane.Map, 0xFF3fa7ff),
        Entry("Music", Icons.Filled.MusicNote, Pane.Music, 0xFFff6b6b),
        Entry("Audiobooks", Icons.Filled.Headphones, Pane.Books, 0xFFc774ff),
        Entry("YouTube", Icons.Filled.SmartDisplay, Pane.YouTube, 0xFFff3b30),
        Entry("Power", Icons.Filled.Bolt, Pane.Power, 0xFFe8a317),
        Entry("Vehicle", Icons.Filled.ToggleOn, Pane.Vehicle, 0xFF35c76d),
        Entry("Settings", Icons.Filled.Settings, null, 0xFF9aa4b2),
    )
    Card(modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF1a2028)).clickable { if (e.pane != null) onSelect(e.pane) else Apps.launch(ctx, "com.android.settings") }.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(e.icon, contentDescription = e.label, tint = Color(e.color), modifier = Modifier.size(44.dp))
                    Text(e.label, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
