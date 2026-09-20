package com.morton.trucknav.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.morton.trucknav.overlay.MediaListener

// Watches every media session on the device and exposes them by package, plus
// whichever one is playing. Each pane binds to its own app's session (Music
// to Finamp, Books to Audiobookshelf); the rail strip and the overlay bar
// follow the sound. Needs the notification-listener grant; without it the
// flows just stay empty.
data class ActiveSession(val pkg: String, val title: String?, val artist: String?, val art: android.graphics.Bitmap?, val playing: Boolean, val controller: MediaController)

class SessionWatcher(private val ctx: Context) {
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(ctx, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _active = MutableStateFlow<ActiveSession?>(null)
    val active: StateFlow<ActiveSession?> = _active
    private val _byPackage = MutableStateFlow<Map<String, ActiveSession>>(emptyMap())
    val byPackage: StateFlow<Map<String, ActiveSession>> = _byPackage
    private val controllers = mutableMapOf<String, MediaController>()

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
        override fun onSessionDestroyed() { refresh() }
    }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
    private val poll = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 2_000) } }

    fun start() {
        try { msm.addOnActiveSessionsChangedListener(listener, component) } catch (e: SecurityException) { return }
        handler.post(poll)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        try { msm.removeOnActiveSessionsChangedListener(listener) } catch (e: Exception) {}
        controllers.values.forEach { it.unregisterCallback(callback) }; controllers.clear()
    }

    fun controllerFor(pkg: String): MediaController? = controllers[pkg]

    private fun refresh() {
        val sessions = try { msm.getActiveSessions(component) } catch (e: SecurityException) { emptyList() }
        val seen = mutableSetOf<String>()
        sessions.forEach { c ->
            seen += c.packageName
            val old = controllers[c.packageName]
            if (old == null || old.sessionToken != c.sessionToken) {
                old?.unregisterCallback(callback)
                c.registerCallback(callback); controllers[c.packageName] = c
            }
        }
        controllers.keys.filter { it !in seen }.forEach { controllers.remove(it)?.unregisterCallback(callback) }
        publish()
    }

    private fun toSession(c: MediaController): ActiveSession? {
        val st = c.playbackState?.state
        val live = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_STOPPED
        if (!live) return null
        val m = c.metadata
        return ActiveSession(
            c.packageName,
            m?.getString(MediaMetadata.METADATA_KEY_TITLE),
            m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            st == PlaybackState.STATE_PLAYING,
            c,
        )
    }

    private fun publish() {
        val map = controllers.values.mapNotNull { toSession(it) }.associateBy { it.pkg }
        _byPackage.value = map
        val playing = map.values.filter { it.playing }
        _active.value = (playing.ifEmpty { map.values.filter { it.controller.playbackState?.state != PlaybackState.STATE_STOPPED } })
            .maxByOrNull { it.controller.playbackState?.lastPositionUpdateTime ?: 0L }
    }
}
