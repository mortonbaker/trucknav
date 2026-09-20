package com.morton.trucknav.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// One connection to another app's MediaBrowserService (Finamp, Audiobookshelf,
// anything that supports Android Auto). Gives us its library tree and a
// controller for its session, so the cockpit can browse and play without ever
// showing that app's UI. This is exactly the channel Android Auto uses.
data class MediaItem(val id: String, val title: String, val subtitle: String?, val browsable: Boolean, val playable: Boolean, val icon: android.net.Uri?)

data class NowPlaying(val title: String?, val artist: String?, val art: Bitmap?, val playing: Boolean, val position: Long, val duration: Long)

data class BrowseState(
    val connected: Boolean = false,
    val label: String = "",
    val stack: List<Pair<String, String>> = emptyList(), // (id, title) from root down
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val nowPlaying: NowPlaying? = null,
)

class MediaSource(private val ctx: Context, val pkg: String, val label: String) {
    private val _state = MutableStateFlow(BrowseState(label = label))
    val state: StateFlow<BrowseState> = _state
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private var subscribedId: String? = null

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) { publishNowPlaying() }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) { publishNowPlaying() }
        override fun onSessionDestroyed() { _state.update { it.copy(nowPlaying = null) } }
    }

    private val subscription = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            _state.update { s -> s.copy(loading = false, items = children.map {
                MediaItem(it.mediaId ?: "", it.description.title?.toString() ?: "", it.description.subtitle?.toString(), it.isBrowsable, it.isPlayable, it.description.iconUri)
            }) }
        }
        override fun onError(parentId: String) { _state.update { it.copy(loading = false) } }
    }

    fun connect() {
        if (browser?.isConnected == true) return
        val svc = ctx.packageManager.queryIntentServices(Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE).setPackage(pkg), 0).firstOrNull()?.serviceInfo
        if (svc == null) { Log.w(TAG, "$pkg has no MediaBrowserService"); return }
        val b = MediaBrowserCompat(ctx, ComponentName(svc.packageName, svc.name), object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val br = browser ?: return
                controller?.unregisterCallback(controllerCallback)
                controller = MediaControllerCompat(ctx, br.sessionToken).also { it.registerCallback(controllerCallback) }
                _state.update { it.copy(connected = true, stack = listOf(br.root to label)) }
                browse(br.root, label, push = false)
                publishNowPlaying()
            }
            override fun onConnectionSuspended() { _state.update { it.copy(connected = false) } }
            override fun onConnectionFailed() { _state.update { it.copy(connected = false) }; Log.w(TAG, "connect failed: $pkg") }
        }, null)
        browser = b
        try { b.connect() } catch (e: Exception) { Log.w(TAG, "connect: $e") }
    }

    fun disconnect() {
        controller?.unregisterCallback(controllerCallback); controller = null
        subscribedId?.let { browser?.unsubscribe(it) }; subscribedId = null
        browser?.disconnect(); browser = null
        _state.update { BrowseState(label = label) }
    }

    fun browse(id: String, title: String, push: Boolean = true) {
        val b = browser ?: return
        subscribedId?.let { b.unsubscribe(it) }
        subscribedId = id
        _state.update { s -> s.copy(loading = true, items = emptyList(), stack = if (push) s.stack + (id to title) else s.stack) }
        b.subscribe(id, subscription)
    }

    fun back(): Boolean {
        val s = _state.value
        if (s.stack.size <= 1) return false
        val parent = s.stack[s.stack.size - 2]
        _state.update { it.copy(stack = it.stack.dropLast(1)) }
        browse(parent.first, parent.second, push = false)
        return true
    }

    fun play(id: String) { controller?.transportControls?.playFromMediaId(id, null) }
    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    private fun publishNowPlaying() {
        val c = controller ?: return
        val m = c.metadata; val ps = c.playbackState
        val np = if (m == null && ps == null) null else NowPlaying(
            m?.getString(MediaMetadataCompat.METADATA_KEY_TITLE),
            m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST),
            m?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART),
            ps?.state == PlaybackStateCompat.STATE_PLAYING,
            ps?.position ?: 0L,
            m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L,
        )
        _state.update { it.copy(nowPlaying = np) }
    }

    companion object { private const val TAG = "MediaSource" }
}
