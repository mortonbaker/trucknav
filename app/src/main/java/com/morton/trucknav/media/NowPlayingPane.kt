package com.morton.trucknav.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Android Auto's media screen: the art fills the pane, title and artist under
// it, big transport controls, a progress bar, and a list button that flips to
// the library browser for the pane's own source. "Now playing" follows the
// device-wide session, so a NewPipe video or an audiobook shows here too.
@Composable
fun NowPlayingPane(watcher: SessionWatcher, pkg: String, browser: @Composable (onDone: () -> Unit) -> Unit, isBook: Boolean, modifier: Modifier = Modifier) {
    var browsing by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var speed by remember { mutableStateOf(com.morton.trucknav.books.Speed.get(ctx)) }
    val sessions by watcher.byPackage.collectAsState()
    val active = sessions[pkg]

    if (browsing) {
        Column(modifier) { browser { browsing = false } }
        return
    }

    Card(modifier.fillMaxWidth()) {
        val a = active
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Reserve controls first. Large text may require scrolling the short portrait pane.
        val artHeight = (maxHeight - if (isBook) 420.dp else 270.dp).coerceIn(0.dp, 240.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Art takes whatever height is left after the controls, never more.
            Box(Modifier.fillMaxWidth().height(artHeight), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1a2028)), contentAlignment = Alignment.Center) {
                    if (a?.art != null) Image(a.art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Filled.MusicNote, null, tint = Color(0xFF3a4552), modifier = Modifier.size(96.dp))
                }
            }
            Text(a?.title ?: "Nothing playing", fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(a?.artist ?: (if (isBook) "Pick a book from the list" else "Pick something from the library"), color = Color(0xFF9aa4b2), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (a != null) Progress(a)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (isBook) {
                    IconButton(onClick = { com.morton.trucknav.books.BooksPlayerService.seekBy(ctx, -30_000) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Replay30, "Back 30s", Modifier.size(36.dp)) }
                } else {
                    IconButton(onClick = { a?.controller?.transportControls?.skipToPrevious() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                }
                FilledIconButton(
                    onClick = { val c = a?.controller; if (c == null) { if (isBook) com.morton.trucknav.books.BooksPlayerService.playLast(ctx) } else if (a.playing) c.transportControls.pause() else c.transportControls.play() },
                    modifier = Modifier.size(80.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1f5f8b)),
                ) { Icon(if (a?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", Modifier.size(44.dp), tint = Color.White) }
                if (isBook) {
                    IconButton(onClick = { com.morton.trucknav.books.BooksPlayerService.seekBy(ctx, 30_000) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Forward30, "Forward 30s", Modifier.size(36.dp)) }
                } else {
                    IconButton(onClick = { a?.controller?.transportControls?.skipToNext() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
                }
                IconButton(onClick = { browsing = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.List, "Library", Modifier.size(32.dp)) }
            }
            if (isBook) {
                androidx.compose.material3.Button(
                    onClick = { speed = com.morton.trucknav.books.Speed.next(ctx); com.morton.trucknav.books.BooksPlayerService.setSpeed(ctx, speed) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF1a2028), contentColor = Color.White),
                ) { Text("Speed  " + com.morton.trucknav.books.Speed.label(speed), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
                DownloadRow()
            }
        }
        }
    }
}

// Offline copy of the current book: one big button whose label is its state,
// plus a delete button once it is on disk. The "current book" is the one the
// player last opened (the pane's session title follows it).
@Composable
private fun DownloadRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val id = com.morton.trucknav.books.BooksPlayerService.lastBook(ctx) ?: return
    val states by com.morton.trucknav.books.BookDownloads.states.collectAsState()
    LaunchedEffect(id) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.morton.trucknav.books.BookDownloads.scan(ctx) } }
    val st = states[id]
    val dark = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF1a2028), contentColor = Color.White)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (st) {
            is com.morton.trucknav.books.BookDownloads.State.Downloading ->
                androidx.compose.material3.Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = dark) { Text("Downloading ${(st.fraction * 100).toInt()}%", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            is com.morton.trucknav.books.BookDownloads.State.Done -> {
                androidx.compose.material3.Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = dark) { Text("Downloaded  " + com.morton.trucknav.books.BookDownloads.gb(st.bytes), fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                androidx.compose.material3.Button(onClick = { com.morton.trucknav.books.BookDownloads.delete(ctx, id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = dark) { Text("Delete download", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            is com.morton.trucknav.books.BookDownloads.State.Failed ->
                androidx.compose.material3.Button(onClick = { com.morton.trucknav.books.BookDownloads.start(ctx, id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = dark) { Text("Download failed, retry", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            else ->
                androidx.compose.material3.Button(onClick = { com.morton.trucknav.books.BookDownloads.start(ctx, id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = dark) { Text("Download", fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        }
    }
}


@Composable
private fun Progress(a: ActiveSession) {
    var pos by remember { mutableLongStateOf(0L) }
    val dur = a.controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    LaunchedEffect(a.controller, a.playing) {
        while (true) {
            val ps = a.controller.playbackState
            pos = if (ps == null) 0L else if (ps.state == PlaybackState.STATE_PLAYING) ps.position + ((android.os.SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong() else ps.position
            delay(1000)
        }
    }
    if (dur > 0) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), style = MaterialTheme.typography.labelMedium, color = Color(0xFF9aa4b2))
                Text(fmt(dur), style = MaterialTheme.typography.labelMedium, color = Color(0xFF9aa4b2))
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
