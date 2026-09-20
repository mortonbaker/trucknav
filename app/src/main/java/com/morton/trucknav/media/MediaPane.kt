package com.morton.trucknav.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Media pane: pick a source (Music = Finamp, Books = Audiobookshelf), walk its
// library like Android Auto's browse list, tap to play, control from the
// now-playing strip at the bottom. Big rows, no small targets.
@Composable
fun MediaPane(sources: List<MediaSource>, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    var tab by remember { mutableIntStateOf(0) }
    val src = sources.getOrNull(tab) ?: return
    DisposableEffect(src) { src.connect(); onDispose { } }
    val s by src.state.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                sources.forEachIndexed { i, m -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(m.label) }) }
            }
            if (!s.connected) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text("Connecting to ${src.label}...") }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!src.back()) onDone?.invoke() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Text(s.stack.lastOrNull()?.second ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f)) {
                    items(s.items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (item.browsable) src.browse(item.id, item.title) else if (item.playable) { src.play(item.id); onDone?.invoke() } }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                item.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9aa4b2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Icon(if (item.browsable) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF9aa4b2))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
