package com.morton.trucknav.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

// The driving-friendly audiobook screen: two tabs, big covers, one tap to
// play. "Continue" is what you were listening to; "Library" is everything.
@Composable
fun BooksBrowser(watcher: SessionWatcher, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var continueList by remember { mutableStateOf<List<Book>?>(null) }
    var library by remember { mutableStateOf<List<Book>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        continueList = AbsClient.continueListening()
        val libs = AbsClient.libraries()
        library = libs.flatMap { AbsClient.items(it.id) }
        if (continueList!!.isEmpty() && library!!.isEmpty()) status = "Could not reach Audiobookshelf"
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Continue") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Library") })
                }
            }
            val list = if (tab == 0) continueList else library
            when {
                status != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(status!!, color = Color(0xFFff6b6b)) }
                list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here yet", color = Color(0xFF9aa4b2)) }
                else -> LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.id }) { b ->
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1a2028)).clickable {
                            com.morton.trucknav.books.BooksPlayerService.play(ctx, b.id)
                            onDone()
                        }) {
                            AsyncImage(model = b.coverUrl, contentDescription = b.title, modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                            b.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(4.dp)) }
                            Column(Modifier.padding(10.dp)) {
                                Text(b.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                b.author?.let { Text(it, color = Color(0xFF9aa4b2), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}
