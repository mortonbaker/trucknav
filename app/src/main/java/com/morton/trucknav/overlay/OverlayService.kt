package com.morton.trucknav.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.morton.trucknav.Apps
import com.morton.trucknav.R
import com.morton.trucknav.media.SessionWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

// The only thing that lives outside the cockpit: when a foreign app (OsmAnd,
// NewPipe video, a player's own UI) is full-screen, a small dock pill and a
// now-playing bar float over it so the cockpit is one tap away. Both hide
// while the cockpit itself is in front. Positions are draggable and remembered.
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: android.content.SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: SessionWatcher? = null
    private var dock: View? = null
    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var barAttached = false
    private lateinit var art: ImageView; private lateinit var title: TextView; private lateinit var artist: TextView; private lateinit var playPause: ImageView
    private val handler = Handler(Looper.getMainLooper())
    private val d get() = resources.displayMetrics.density
    private val fgPoll = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 2_000) } }
    private var job: Job? = null
    private var lastFg: String? = "init"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        buildDock(); buildBar()
        watcher = SessionWatcher(this).also { it.start() }
        job = scope.launch { watcher!!.active.collect { refresh() } }
        handler.post(fgPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(fgPoll); job?.cancel(); watcher?.stop()
        dock?.let { runCatching { wm.removeView(it) } }; if (barAttached) runCatching { wm.removeView(bar) }
        super.onDestroy()
    }

    private fun buildDock() {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = getDrawable(R.drawable.overlay_bg)
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
        }
        pill.addView(glyph(R.drawable.ic_home, Color.WHITE) { Apps.openSelf(this) })
        Apps.external.filter { it.pkg != "com.android.settings" && it.pkg != "com.morton.venuskiosk" }.forEach { a ->
            pill.addView(glyph(a.icon, a.color) { Apps.launch(this, a.pkg) })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = prefs.getInt("dock_x", (8 * d).toInt()); y = prefs.getInt("dock_y", 0) }
        drag(pill, lp) { prefs.edit().putInt("dock_x", lp.x).putInt("dock_y", lp.y).apply() }
        wm.addView(pill, lp); dock = pill
        pill.visibility = View.GONE
    }

    private fun buildBar() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.overlay_bg)
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        }
        art = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()); scaleType = ImageView.ScaleType.CENTER_CROP; setOnClickListener { watcher?.active?.value?.pkg?.let { Apps.launch(this@OverlayService, it) } } }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (12 * d).toInt() } }
        title = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        artist = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#9aa4b2")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        text.addView(title); text.addView(artist)
        root.addView(art); root.addView(text)
        root.addView(button(R.drawable.ic_skip_previous) { watcher?.active?.value?.controller?.transportControls?.skipToPrevious() })
        playPause = button(R.drawable.ic_play) {
            val c = watcher?.active?.value?.controller ?: return@button
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        root.addView(playPause)
        root.addView(button(R.drawable.ic_skip_next) { watcher?.active?.value?.controller?.transportControls?.skipToNext() })
        val screenW = resources.displayMetrics.widthPixels
        barLp = WindowManager.LayoutParams(
            (screenW * 0.42f).toInt().coerceAtLeast((300 * d).toInt()), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = prefs.getInt("bar_x", (8 * d).toInt()); y = prefs.getInt("bar_y", (8 * d).toInt()) }
        drag(root, barLp!!) { prefs.edit().putInt("bar_x", barLp!!.x).putInt("bar_y", barLp!!.y).apply() }
        bar = root
    }

    private fun refresh() {
        val fg = foregroundPackage()
        val inCockpit = fg == null || fg == Apps.SELF
        if (fg != lastFg) { android.util.Log.i("OverlayService", "refresh fg=$fg inCockpit=$inCockpit session=${watcher?.active?.value?.pkg}"); lastFg = fg }
        dock?.visibility = if (inCockpit) View.GONE else View.VISIBLE
        val a = watcher?.active?.value
        val showBar = !inCockpit && a != null && fg != a.pkg
        if (showBar) {
            title.text = a!!.title ?: ""; artist.text = a.artist ?: ""
            if (a.art != null) art.setImageBitmap(a.art) else art.setImageResource(R.drawable.ic_music_note)
            playPause.setImageResource(if (a.playing) R.drawable.ic_pause else R.drawable.ic_play)
            if (!barAttached) { wm.addView(bar, barLp); barAttached = true }
        } else if (barAttached) { wm.removeView(bar); barAttached = false }
    }

    private fun foregroundPackage(): String? {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 20_000, now)
        val e = UsageEvents.Event(); var last: String? = null
        while (events.hasNextEvent()) { events.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName }
        return last
    }

    private fun glyph(icon: Int, color: Int, onTap: () -> Unit): View =
        Apps.iconView(this, icon, color, (30 * d).toInt()).apply {
            val pad = (13 * d).toInt(); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams((56 * d).toInt(), (52 * d).toInt()); setOnClickListener { onTap() }
        }

    private fun button(icon: Int, onTap: () -> Unit): ImageView =
        Apps.iconView(this, icon, Color.WHITE, (32 * d).toInt()).apply {
            val pad = (10 * d).toInt(); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()); setOnClickListener { onTap() }
        }

    // Drag anywhere on an overlay (including over its buttons); a still press still clicks.
    private fun drag(view: ViewGroup, lp: WindowManager.LayoutParams, onSettle: () -> Unit) {
        val slop = 12 * d
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var dragging = false
        val h = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; dragging = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) { lp.x = (sx - dx.toInt()).coerceAtLeast(0); lp.y = (sy - dy.toInt()).coerceAtLeast(0); runCatching { wm.updateViewLayout(view, lp) } }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) { onSettle(); dragging = false; true } else false
                else -> false
            }
        }
        view.setOnTouchListener(h)
        for (i in 0 until view.childCount) {
            val c = view.getChildAt(i); c.setOnTouchListener { _, e -> h.onTouch(view, e) }
            if (c is ViewGroup) for (j in 0 until c.childCount) c.getChildAt(j).setOnTouchListener { _, e -> h.onTouch(view, e) }
        }
    }

    private fun notification(): Notification {
        val ch = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "Cockpit overlay", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, ch).setSmallIcon(R.drawable.ic_navigation).setContentTitle("TruckNav overlay").setOngoing(true).build()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
