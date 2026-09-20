package com.morton.trucknav.nav

import android.content.Context
import com.morton.trucknav.MapStyle
import com.morton.trucknav.MapStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

// Driver-facing navigation preferences: which voice announcement classes are
// on, and automatic day/night map switching. Persisted in prefs "nav".
object NavPrefs {
    private lateinit var ctx: Context
    @Volatile var gate: VoiceGate? = null      // set by AppModule
    fun context(): Context = ctx
    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    val disabledClasses: StateFlow<Set<String>> = _disabled
    private val _autoNight = MutableStateFlow(true)
    val autoNight: StateFlow<Boolean> = _autoNight

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = ctx.getSharedPreferences("nav", Context.MODE_PRIVATE)
        _disabled.value = p.getStringSet("voice_off", emptySet()) ?: emptySet()
        _autoNight.value = p.getBoolean("auto_night", true)
        gate?.disabledClasses = _disabled.value
    }
    fun setClass(cls: String, enabled: Boolean) {
        _disabled.value = if (enabled) _disabled.value - cls else _disabled.value + cls
        ctx.getSharedPreferences("nav", Context.MODE_PRIVATE).edit().putStringSet("voice_off", _disabled.value).apply()
        gate?.disabledClasses = _disabled.value
        NavLog.log("prefs", "voice class $cls ${if (enabled) "on" else "off"}")
    }
    fun setAutoNight(on: Boolean) {
        _autoNight.value = on
        ctx.getSharedPreferences("nav", Context.MODE_PRIVATE).edit().putBoolean("auto_night", on).apply()
        NavLog.log("prefs", "auto night ${if (on) "on" else "off"}")
        NightMode.evaluate(force = true)
    }
}

// Day/night from the sun's elevation at the last known position: dark below
// civil dusk (-6°). Only touches the Light/Dark pair; satellite/hybrid/terrain
// are left alone. Checked every minute.
object NightMode {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var position: Pair<Double, Double>? = null
    private var lastDark: Boolean? = null

    fun start() { scope.launch { while (true) { evaluate(); delay(60_000) } } }

    fun evaluate(force: Boolean = false) {
        if (!NavPrefs.autoNight.value) return
        val (lat, lng) = position ?: return
        val el = sunElevation(lat, lng, System.currentTimeMillis())
        val dark = el < -6.0
        if (!force && dark == lastDark) return
        lastDark = dark
        val cur = MapStyles.current.value
        val want = if (dark) MapStyle.Dark else MapStyle.Light
        NavLog.log("night", "sun ${"%.1f".format(el)}° -> ${if (dark) "night" else "day"}; style ${cur.name}${if (cur == MapStyle.Light || cur == MapStyle.Dark) " -> ${want.name}" else " (left alone)"}")
        if ((cur == MapStyle.Light || cur == MapStyle.Dark) && cur != want) MapStyles.set(NavPrefs.context(), want)
    }

    // NOAA-style solar position, good to a fraction of a degree: plenty for dusk/dawn.
    fun sunElevation(lat: Double, lng: Double, epochMs: Long): Double {
        val d = epochMs / 86_400_000.0 + 2440587.5 - 2451545.0            // days since J2000
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)          // mean anomaly
        val q = (280.459 + 0.98564736 * d) % 360                          // mean longitude
        val l = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360)   // ecliptic longitude
        val e = Math.toRadians(23.439 - 0.00000036 * d)                   // obliquity
        val ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))).let { if (it < 0) it + 360 else it }
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val lst = ((gmst + lng / 15.0) % 24 + 24) % 24
        val ha = Math.toRadians(lst * 15 - ra)
        val latR = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(ha)))
    }
}
