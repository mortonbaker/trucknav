package com.morton.trucknav.books

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.os.Build
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import com.morton.trucknav.AppModule
import com.morton.trucknav.BuildConfig
import com.morton.trucknav.media.AbsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The truck's own audiobook player. One ExoPlayer, one MediaSession, streaming
// straight from the Audiobookshelf server: a book's tracks become one playlist,
// playback starts where the server says, and the position is synced back every
// 15 s so the phone app resumes where the truck stopped. The ABS Android app
// is never involved. A downloaded book (BookDownloads) plays from local files
// with the same timeline math; positions reached offline are queued and pushed
// to the server the next time a sync gets through.
class BooksPlayerService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncJob: Job? = null

    // The open ABS play session for the current book (null while offline on a downloaded book).
    private var absSessionId: String? = null
    private var bookId: String? = null
    private var bookDuration = 0.0
    private var offsets = doubleArrayOf()          // startOffset per track, seconds
    private var lastSyncBookTime = 0.0
    private var lastSyncWall = 0L
    private var source = "stream"                  // "stream" | "local", for the log and the pane

    override fun onCreate() {
        super.onCreate()
        http = DefaultHttpDataSource.Factory()
            .setUserAgent("TruckNav")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${AbsClient.tokenOrEmpty()}"))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setPlaybackParameters(PlaybackParameters(Speed.get(this), 1f))
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason") }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) startSync() else { syncNow("pause"); stopSync() }; if (bookId != null) holdForeground() }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { syncNow("ended"); closeSession() } }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Log.w(TAG, "player error: ${error.errorCodeName}") ; scheduleRetry() }
        })
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onCustomCommand(s: MediaSession, controller: MediaSession.ControllerInfo, customCommand: androidx.media3.session.SessionCommand, args: Bundle): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                return super.onCustomCommand(s, controller, customCommand, args)
            }
        }).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // media3 drops the service out of the foreground when the player pauses; on
    // Android 14 that leaves a pending foreground start unanswered and the OS
    // stops the service ("Bringing down service while still waiting for start
    // foreground"), taking the session with it. So while a book is loaded and
    // not playing, we hold the foreground ourselves with a quiet notification.
    private fun holdForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Audiobook", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH).setSmallIcon(com.morton.trucknav.R.drawable.ic_headphones)
            .setContentTitle(currentTitle ?: "Audiobook").setContentText(if (player.isPlaying) "Playing" else "Paused").setOngoing(true).build()
        try { ServiceCompat.startForeground(this, HOLD_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) } catch (e: Exception) { Log.w(TAG, "holdForeground: $e") }
    }
    private var currentTitle: String? = null
    private lateinit var http: DefaultHttpDataSource.Factory

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        holdForeground()
        when (intent?.action) {
            ACTION_PLAY_BOOK -> intent.getStringExtra(EXTRA_BOOK_ID)?.let { openBook(it) }
            ACTION_PLAY_LAST -> lastBook(this)?.let { openBook(it) }
            ACTION_SET_SPEED -> { val s = intent.getFloatExtra(EXTRA_SPEED, 1f); Speed.set(this, s); player.setPlaybackParameters(PlaybackParameters(s, 1f)) }
            ACTION_SEEK_BY -> seekBy(intent.getLongExtra(EXTRA_SEEK_MS, 0L))
            ACTION_SEEK_TO -> { seekToBookTime((intent.getLongExtra(EXTRA_BOOK_MS, 0L) / 1000.0).coerceIn(0.0, bookDuration)); Log.i(TAG, "seekTo ${bookTime().toInt()}s") }
            ACTION_DOWNLOAD -> intent.getStringExtra(EXTRA_BOOK_ID)?.let { BookDownloads.start(this, it) }
            ACTION_DELETE_DOWNLOAD -> intent.getStringExtra(EXTRA_BOOK_ID)?.let { BookDownloads.delete(this, it) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // --- book lifecycle ---------------------------------------------------

    private fun openBook(id: String) {
        scope.launch {
            try {
                if (bookId != null && bookId != id) { syncNow("switch"); closeSession() }
                val local = BookDownloads.manifest(this@BooksPlayerService, id)
                // Positions reached offline go up first so the server's resume point is not stale.
                withContext(Dispatchers.IO) { flushQueue() }
                val s = withContext(Dispatchers.IO) { AbsClient.openPlaySession(id) }
                if (s == null && local == null) { Log.w(TAG, "no play session for $id and no download"); return@launch }
                // The service can start cold (play-last) before anyone has logged in, so the
                // bearer baked in at onCreate may be empty. openPlaySession has logged in by now;
                // the factory applies new defaults to every data source created after this.
                http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${AbsClient.tokenOrEmpty()}"))
                absSessionId = s?.sessionId; bookId = id
                bookDuration = s?.duration ?: local!!.duration
                currentTitle = s?.title ?: local!!.title
                val author = s?.author ?: local?.author
                getSharedPreferences("books", MODE_PRIVATE).edit().putString("last_book", id).apply()
                val resume = s?.currentTime ?: localPosition(id)
                val items: List<MediaItem>
                if (local != null) {
                    source = "local"
                    offsets = local.tracks.map { it.startOffset }.toDoubleArray()
                    val dir = BookDownloads.dir(this@BooksPlayerService, id)
                    val art = BookDownloads.coverFile(this@BooksPlayerService, id).let { if (it.exists()) Uri.fromFile(it) else s?.coverUrl?.let(Uri::parse) }
                    items = local.tracks.map { t ->
                        MediaItem.Builder().setUri(Uri.fromFile(File(dir, t.file))).setMediaId("${id}#${t.index}")
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).setArtist(author).setArtworkUri(art).setIsPlayable(true).build()).build()
                    }
                } else {
                    source = "stream"
                    offsets = s!!.tracks.map { it.startOffset }.toDoubleArray()
                    items = s.tracks.map { t ->
                        MediaItem.Builder().setUri(Uri.parse(BuildConfig.absUrl.trimEnd('/') + t.contentUrl)).setMediaId("${id}#${t.index}")
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.author).setArtworkUri(Uri.parse(s.coverUrl)).setIsPlayable(true).build()).build()
                    }
                }
                player.setMediaItems(items)
                player.prepare()
                seekToBookTime(resume)
                player.setPlaybackParameters(PlaybackParameters(Speed.get(this@BooksPlayerService), 1f))
                player.play()
                lastSyncBookTime = resume; lastSyncWall = System.currentTimeMillis()
                Log.i(TAG, "opened $currentTitle: ${items.size} tracks, resume ${resume.toInt()}s of ${bookDuration.toInt()}s source=$source session=${absSessionId != null}")
            } catch (e: Exception) { Log.w(TAG, "openBook: $e") }
        }
    }

    // Book-level position = track start offset + position inside the track.
    fun bookTime(): Double {
        val i = player.currentMediaItemIndex
        if (i < 0 || i >= offsets.size) return 0.0
        return offsets[i] + player.currentPosition / 1000.0
    }

    private fun seekToBookTime(t: Double) {
        if (offsets.isEmpty()) return
        var idx = 0
        for (i in offsets.indices) if (offsets[i] <= t) idx = i
        player.seekTo(idx, ((t - offsets[idx]) * 1000).toLong().coerceAtLeast(0))
    }

    private fun seekBy(ms: Long) {
        val target = (bookTime() + ms / 1000.0).coerceIn(0.0, bookDuration)
        seekToBookTime(target)
    }

    // --- progress sync ----------------------------------------------------

    private fun startSync() {
        if (syncJob != null) return
        syncJob = scope.launch { while (isActive) { delay(15_000); syncNow("tick") } }
    }
    private fun stopSync() { syncJob?.cancel(); syncJob = null }

    // Every sync also lands in prefs, so a downloaded book resumes correctly
    // with no server at all. A failed (or session-less) sync is queued per book;
    // the queue is flushed on the next tick that reaches the server.
    private fun syncNow(why: String) {
        val id = bookId ?: return
        val t = bookTime(); val now = System.currentTimeMillis()
        val listened = if (player.isPlaying || why != "tick") ((now - lastSyncWall) / 1000.0).coerceIn(0.0, 60.0) else 0.0
        lastSyncBookTime = t; lastSyncWall = now
        val prefs = getSharedPreferences("books", MODE_PRIVATE)
        prefs.edit().putFloat("pos_$id", t.toFloat()).apply()
        val sid = absSessionId; val dur = bookDuration
        scope.launch(Dispatchers.IO) {
            val ok = sid != null && AbsClient.sync(sid, t, listened, dur)
            if (!ok) prefs.edit().putString("queued_$id", "$t|$dur|$now").apply()
            Log.i(TAG, "sync $why t=${t.toInt()} source=$source ${if (ok) "ok" else "queued"}")
            flushQueue()
        }
    }

    private fun localPosition(id: String): Double = getSharedPreferences("books", MODE_PRIVATE).getFloat("pos_$id", 0f).toDouble()

    private suspend fun flushQueue() {
        val prefs = getSharedPreferences("books", MODE_PRIVATE)
        val queued = prefs.all.filterKeys { it.startsWith("queued_") }
        for ((k, v) in queued) {
            val id = k.removePrefix("queued_"); val p = (v as String).split("|")
            val t = p[0].toDouble(); val dur = p[1].toDouble()
            if (AbsClient.patchProgress(id, t, dur)) { prefs.edit().remove(k).apply(); Log.i(TAG, "flushed queued progress $id t=${t.toInt()}") }
            else return
        }
    }

    private fun closeSession() {
        val sid = absSessionId ?: return
        absSessionId = null
        scope.launch(Dispatchers.IO) { AbsClient.close(sid) }
    }

    private fun scheduleRetry() {
        // Dead zone: keep the position, try again in 10 s, forever, quietly.
        scope.launch { delay(10_000); if (bookId != null) { player.prepare(); player.play() } }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { /* keep playing: this is a car */ }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy called (book=$bookId playing=${player.isPlaying})", Exception("trace"))
        syncNow("destroy"); closeSession()
        stopSync(); scope.cancel()
        session?.release(); session = null
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BooksPlayer"
        const val ACTION_PLAY_BOOK = "com.morton.trucknav.books.PLAY"
        const val ACTION_PLAY_LAST = "com.morton.trucknav.books.PLAY_LAST"
        private const val CH = "audiobook"
        private const val HOLD_ID = 2
        fun lastBook(ctx: Context): String? = ctx.getSharedPreferences("books", Context.MODE_PRIVATE).getString("last_book", null)
        fun playLast(ctx: Context) = ctx.startForegroundService(Intent(ctx, BooksPlayerService::class.java).setAction(ACTION_PLAY_LAST))
        const val ACTION_SET_SPEED = "com.morton.trucknav.books.SPEED"
        const val ACTION_SEEK_BY = "com.morton.trucknav.books.SEEK_BY"
        const val ACTION_SEEK_TO = "com.morton.trucknav.books.SEEK_TO"          // --el book_ms N (harness)
        const val ACTION_DOWNLOAD = "com.morton.trucknav.books.DOWNLOAD"        // --es book_id
        const val ACTION_DELETE_DOWNLOAD = "com.morton.trucknav.books.DELETE_DOWNLOAD"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_BOOK_MS = "book_ms"

        fun play(ctx: Context, bookId: String) = ctx.startForegroundService(Intent(ctx, BooksPlayerService::class.java).setAction(ACTION_PLAY_BOOK).putExtra(EXTRA_BOOK_ID, bookId))
        fun setSpeed(ctx: Context, speed: Float) = ctx.startService(Intent(ctx, BooksPlayerService::class.java).setAction(ACTION_SET_SPEED).putExtra(EXTRA_SPEED, speed))
        fun seekBy(ctx: Context, ms: Long) = ctx.startService(Intent(ctx, BooksPlayerService::class.java).setAction(ACTION_SEEK_BY).putExtra(EXTRA_SEEK_MS, ms))
    }
}

// The speed ladder the operator asked for, remembered across books and restarts.
object Speed {
    val steps = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f)
    fun get(ctx: Context): Float = ctx.getSharedPreferences("books", Context.MODE_PRIVATE).getFloat("speed", 1.0f)
    fun set(ctx: Context, v: Float) { ctx.getSharedPreferences("books", Context.MODE_PRIVATE).edit().putFloat("speed", v).apply() }
    fun next(ctx: Context): Float { val cur = get(ctx); val i = steps.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }; return steps[(i + 1) % steps.size] }
    fun label(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}.0×" else "${v}×"
}
