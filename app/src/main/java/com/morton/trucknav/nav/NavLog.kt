package com.morton.trucknav.nav

import android.content.Context
import android.util.Log
import com.stadiamaps.ferrostar.core.NavigationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.ferrostar.Route
import uniffi.ferrostar.TripState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The drive's evidence. One line per event into files/nav-YYYYMMDD.log (and
// logcat tag NavLog): route requests and answers, every instruction as it is
// issued, progress every 10 s, deviations, start/stop with who called, mute.
// Without this a field report is just a memory. adb pull ...files/nav-*.log
object NavLog {
    private const val TAG = "NavLog"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val KEEP_DAYS = 7
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(capacity = 512)
    private lateinit var dir: File
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "navlog").apply { mkdirs() }
        scope.launch {
            prune()
            for (line in queue) {
                val f = File(dir, "nav-${day.format(Date())}.log")
                if (f.length() > MAX_BYTES) f.renameTo(File(dir, f.name + ".1"))
                f.appendText(line + "\n")
            }
        }
        log("init", "navlog ${dir.absolutePath}")
    }

    fun log(kind: String, msg: String) {
        val line = "${ts.format(Date())} $kind $msg"
        Log.i(TAG, line)
        queue.trySend(line)
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun caller(): String = Thread.currentThread().stackTrace.drop(3).take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    fun route(tag: String, r: Route) {
        val dur = r.steps.sumOf { it.duration }
        log("route", "$tag annotated=${r.steps.count { (it.annotations?.size ?: 0) > 0 }}/${r.steps.size} first=${r.steps.firstOrNull()?.annotations?.firstOrNull()?.take(80)} distance=${"%.1f".format(r.distance / 1609.344)}mi duration=${(dur / 60).toInt()}min steps=${r.steps.size} points=${r.geometry.size} to=${r.waypoints.lastOrNull()?.coordinate}")
    }

    // Watches the UI state the panes render from, so what we log is what the driver saw.
    fun watch(state: StateFlow<NavigationUiState>) = scope.launch {
        var lastVisual: String? = null; var lastSpoken: String? = null; var lastNav = false; var lastProgressAt = 0L; var lastDeviation: String? = null
        state.collect { s ->
            val nav = s.isNavigating()
            if (nav != lastNav) { log("state", if (nav) "NAVIGATING trip=${s.tripState?.javaClass?.simpleName}" else "IDLE trip=${s.tripState?.javaClass?.simpleName}"); lastNav = nav }
            val loc = s.location
            val where = loc?.let { "@${"%.5f".format(it.coordinates.lat)},${"%.5f".format(it.coordinates.lng)} brg=${it.courseOverGround?.let { c -> (c.degrees.toInt() and 0xffff) } ?: "-"} spd=${it.speed?.value?.let { v -> "%.1f".format(v) } ?: "-"}" } ?: "@?"
            s.visualInstruction?.let { v ->
                val p = v.primaryContent
                val key = "${p.text}|${p.maneuverType}|${p.maneuverModifier}|${s.currentStepGeometryIndex}"
                if (key != lastVisual) { log("visual", "\"${p.text}\" type=${p.maneuverType} mod=${p.maneuverModifier} trigger=${v.triggerDistanceBeforeManeuver.toInt()}m road=${s.currentStepRoadName} stepIdx=${s.currentStepGeometryIndex} secondary=\"${v.secondaryContent?.text}\" $where"); lastVisual = key }
            }
            s.spokenInstruction?.let { sp -> if (sp.text != lastSpoken) { log("spoken", "\"${sp.text}\" trigger=${sp.triggerDistanceBeforeManeuver.toInt()}m $where"); lastSpoken = sp.text } }
            val dev = s.routeDeviation?.javaClass?.simpleName
            if (dev != lastDeviation) { log("deviation", "${dev ?: "none"} $where"); lastDeviation = dev }
            val now = System.currentTimeMillis()
            if (nav && now - lastProgressAt >= 10_000) {
                s.progress?.let { p -> log("progress", "limit=${s.currentAnnotation?.speedLimit?.toString() ?: "-"} remaining=${"%.2f".format(p.distanceRemaining / 1609.344)}mi eta=${(p.durationRemaining / 60).toInt()}min nextManeuver=${p.distanceToNextManeuver.toInt()}m road=${s.currentStepRoadName} steps=${s.remainingSteps?.size} $where") }
                lastProgressAt = now
            }
        }
    }
}
