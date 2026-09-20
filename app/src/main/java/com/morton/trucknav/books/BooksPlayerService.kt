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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// The truck's own audiobook player. One ExoPlayer, one MediaSession, streaming
// straight from the Audiobookshelf server: a book's tracks become one playlist,
// playback starts where the server says, and the position is synced back every
// 15 s so the phone app resumes where the truck stopped. The ABS Android app
// is never involved.
class BooksPlayerService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncJob: Job? = null

    // The open ABS play session for the current book.
    private var absSessionId: String? = null
    private var bookId: String? = null
    private var bookDuration = 0.0
    private var offsets = doubleArrayOf()          // startOffset per track, seconds
    private var lastSyncBookTime = 0.0
    private var lastSyncWall = 0L

    override fun onCreate() {
        super.onCreate()
        http = DefaultHttpDataSource.Factory()
            .setUserAgent("TruckNav")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${AbsClient.tokenOrEmpty()}"))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
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
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // --- book lifecycle ---------------------------------------------------

    private fun openBook(id: String) {
        scope.launch {
            try {
                if (bookId != null && bookId != id) { syncNow("switch"); closeSession() }
                val s = withContext(Dispatchers.IO) { AbsClient.openPlaySession(id) } ?: run { Log.w(TAG, "no play session for $id"); return@launch }
                // The service can start cold (play-last) before anyone has logged in, so the
                // bearer baked in at onCreate may be empty. openPlaySession has logged in by now;
                // the factory applies new defaults to every data source created after this.
                http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${AbsClient.tokenOrEmpty()}"))
                absSessionId = s.sessionId; bookId = id; bookDuration = s.duration; currentTitle = s.title
                getSharedPreferences("books", MODE_PRIVATE).edit().putString("last_book", id).apply()
                offsets = s.tracks.map { it.startOffset }.toDoubleArray()
                val items = s.tracks.map { t ->
                    MediaItem.Builder()
                        .setUri(Uri.parse(BuildConfig.absUrl.trimEnd('/') + t.contentUrl))
                        .setMediaId("${id}#${t.index}")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.author).setArtworkUri(Uri.parse(s.coverUrl)).setIsPlayable(true).build())
                        .build()
                }
                player.setMediaItems(items)
                player.prepare()
                seekToBookTime(s.currentTime)
                player.setPlaybackParameters(PlaybackParameters(Speed.get(this@BooksPlayerService), 1f))
                player.play()
                lastSyncBookTime = s.currentTime; lastSyncWall = System.currentTimeMillis()
                Log.i(TAG, "opened ${s.title}: ${s.tracks.size} tracks, resume ${s.currentTime.toInt()}s of ${s.duration.toInt()}s")
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

    private fun syncNow(why: String) {
        val sid = absSessionId ?: return
        val t = bookTime(); val now = System.currentTimeMillis()
        val listened = if (player.isPlaying || why != "tick") ((now - lastSyncWall) / 1000.0).coerceIn(0.0, 60.0) else 0.0
        lastSyncBookTime = t; lastSyncWall = now
        scope.launch(Dispatchers.IO) { AbsClient.sync(sid, t, listened, bookDuration) }
    }

    private fun closeSession() {
        val sid = absSessionId ?: return
        absSessionId = null
        scope.launch(Dispatchers.IO) { AbsClient.close(sid) }
    }

    private fun scheduleRetry() {
        // Dead zone: keep the position, try again in 10 s, forever, quietly.
        scope.launch { delay(10_000); if (absSessionId != null) { player.prepare(); player.play() } }
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
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_SEEK_MS = "seek_ms"

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
