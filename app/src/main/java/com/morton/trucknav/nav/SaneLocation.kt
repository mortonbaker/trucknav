package com.morton.trucknav.nav

import android.location.Location
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter

// Plausibility gate in front of the GPS (B10). Indoors with four weak
// satellites the tablet produced a fix 55 mi away at 32 896 m altitude doing
// 33 m/s, and everything downstream believed it. A truck never does any of:
//   altitude outside -500…6 000 m, speed > 60 m/s (134 mph), horizontal
//   accuracy worse than 150 m, or a jump implying > 250 km/h since the last
//   accepted fix. Such fixes are dropped and logged; the strip shows "GPS weak"
//   while that is happening. The first fix after a long silence is accepted
//   (no reference to judge a jump against).
class SaneLocationProvider(private val inner: NavigationLocationProviding) : NavigationLocationProviding {
    companion object {
        private const val MAX_SPEED_MPS = 60.0
        private const val MAX_HACC_M = 150f
        private const val MAX_JUMP_KMH = 250.0
        private const val STALE_MS = 120_000L
        val rejecting: StateFlow<Boolean> get() = _rejecting
        private val _rejecting = MutableStateFlow(false)
        @Volatile private var lastGood: Location? = null
        // A jump is rejected once; if the following fixes agree with each other
        // (plausible speed between them), the world really did move and we re-anchor.
        @Volatile private var candidate: Location? = null
        @Volatile private var candidateRun = 0
        @Volatile private var candidateStart = 0L

        fun plausible(l: Location): String? {
            if (l.hasAltitude() && (l.altitude < -500 || l.altitude > 6_000)) return "altitude ${l.altitude.toInt()} m"
            if (l.hasSpeed() && l.speed > MAX_SPEED_MPS) return "speed ${"%.0f".format(l.speed)} m/s"
            if (l.hasAccuracy() && l.accuracy > MAX_HACC_M) return "hAcc ${l.accuracy.toInt()} m"
            val g = lastGood
            if (g != null) {
                val dtMs = l.elapsedRealtimeNanos / 1_000_000 - g.elapsedRealtimeNanos / 1_000_000
                if (dtMs in 1..STALE_MS) {
                    val kmh = g.distanceTo(l) / 1000.0 / (dtMs / 3_600_000.0)
                    if (kmh > MAX_JUMP_KMH) return "jump ${g.distanceTo(l).toInt()} m in ${dtMs / 1000} s (${kmh.toInt()} km/h)"
                }
            }
            return null
        }

        fun accept(l: Location): Boolean {
            val why = plausible(l)
            if (why == null) { lastGood = l; candidate = null; candidateRun = 0; if (_rejecting.value) _rejecting.value = false; return true }
            if (why.startsWith("jump")) {
                val c = candidate
                val consistent = c != null && run { val dt = (l.elapsedRealtimeNanos - c.elapsedRealtimeNanos) / 1_000_000; dt in 1..STALE_MS && c.distanceTo(l) / 1000.0 / (dt / 3_600_000.0) <= MAX_JUMP_KMH }
                if (!consistent) candidateStart = l.elapsedRealtimeNanos
                candidateRun = if (consistent) candidateRun + 1 else 1
                candidate = l
                if (candidateRun >= 3 && (l.elapsedRealtimeNanos - candidateStart) / 1_000_000_000 >= 10) {
                    NavLog.log("gps", "re-anchored after $candidateRun consistent fixes @${"%.5f".format(l.latitude)},${"%.5f".format(l.longitude)}")
                    lastGood = l; candidate = null; candidateRun = 0; _rejecting.value = false; return true
                }
            }
            NavLog.log("gps", "rejected: $why @${"%.5f".format(l.latitude)},${"%.5f".format(l.longitude)} sats=${l.extras?.getInt("satellites") ?: -1}")
            _rejecting.value = true
            return false
        }
    }

    override suspend fun lastLocation(): Location? = inner.lastLocation()?.takeIf { plausible(it) == null }
    override fun locationUpdates(interval: Long): Flow<Location> = inner.locationUpdates(interval).filter { accept(it) }
}
