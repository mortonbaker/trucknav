package com.morton.trucknav.routing

import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.morton.trucknav.AppModule
import com.morton.trucknav.BuildConfig
import com.morton.trucknav.MainActivity
import com.morton.trucknav.support.initialSimulatedLocation
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.withJsonOptions
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

@RunWith(AndroidJUnit4::class)
class S6RoutingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val destination = listOf(Waypoint(GeographicCoordinate(35.4676, -97.5164), WaypointKind.BREAK))
    private fun report(message: String) { Log.i("S6Smoke", message) }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(label, condition())
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.equals(text, ignoreCase = true) == true || node.contentDescription?.toString()?.equals(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), text)?.let { return it }
        return null
    }

    private fun tap(text: String) {
        waitFor("Control '$text' appears") { findText(instrumentation.uiAutomation.rootInActiveWindow, text) != null }
        var node = findText(instrumentation.uiAutomation.rootInActiveWindow, text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Control '$text' clicked", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** Runner blocks only this emulator app's external network, restores it even on failure. */
    @Test fun offlineStartEndAndLateResultContract() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(4000)
        if (findText(instrumentation.uiAutomation.rootInActiveWindow, "Got it") != null) tap("Got it")
        waitFor("Map UI ready") { findText(instrumentation.uiAutomation.rootInActiveWindow, "Search field") != null }
        SystemClock.sleep(5000) // Let the initial local tile requests settle before the camera transition.
        val vm = AppModule.viewModel
        waitFor("GPS location received") { vm.location.value != null }
        assertTrue("Test GPS must be inside the region", kotlin.math.abs(vm.location.value!!.coordinates.lat - BuildConfig.homeLat) < 0.02)
        instrumentation.runOnMainSync { vm.selectDestination(destination.single().coordinate, "S6 acceptance destination") }
        tap(context.getString(com.morton.trucknav.R.string.start_navigation))
        waitFor("Offline navigation started") { vm.navigationUiState.value.isNavigating() || vm.routeError.value != null }
        assertNull(vm.routeError.value)
        waitFor("Accepted source is on-device") { vm.routeSource.value == RouteSource.DEVICE }
        waitFor("Source footer rendered") { findText(instrumentation.uiAutomation.rootInActiveWindow, "Routing: On-device") != null }
        tap("Mute")
        waitFor("Mute changed") { AppModule.voiceGate.isMuted }
        tap("Unmute")
        waitFor("Unmute restored") { !AppModule.voiceGate.isMuted }
        tap("Route Overview")
        tap("Recenter Map")
        SystemClock.sleep(5000)
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        report("navigating_total_pss_kib=${memory.totalPss}")
        assertTrue(memory.totalPss < 614400)
        val screenshot = File(context.getExternalFilesDir(null), "s6-offline-routing.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        tap("End Navigation")
        waitFor("End stopped navigation") { !vm.navigationUiState.value.isNavigating() }
        assertNull(vm.routeSource.value)
        instrumentation.runOnMainSync {
            vm.startNavigation(destination.single().coordinate, "S6 canceled request")
            vm.stopNavigation()
        }
        SystemClock.sleep(6000)
        assertFalse("Late answer must not restart navigation", vm.navigationUiState.value.isNavigating())
        instrumentation.runOnMainSync { vm.selectDestination(GeographicCoordinate(51.5074, -0.1278), "Outside offline coverage") }
        tap(context.getString(com.morton.trucknav.R.string.start_navigation))
        waitFor("Route failure visible") { vm.routeError.value != null }
        waitFor("Error dialog rendered") { findText(instrumentation.uiAutomation.rootInActiveWindow, "Route unavailable") != null }
        tap("Dismiss")
        waitFor("Error dismissed") { vm.routeError.value == null }
        assertFalse(vm.navigationUiState.value.isNavigating())
        report("route_error_dialog=PASS")
        report("ui_start_footer_mute_overview_recenter_end_cancel=PASS screenshot=${screenshot.name}")
    }

    @Test fun realNativePackParityRepeatedRoutesAndFailures() = runBlocking {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Include the real map/UI in memory measurements, not just an empty test process.
        kotlinx.coroutines.delay(5000)
        if (findText(instrumentation.uiAutomation.rootInActiveWindow, "Got it") != null) tap("Got it")
        waitFor("Map UI ready") { findText(instrumentation.uiAutomation.rootInActiveWindow, "Search field") != null }
        val client = OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(30)).build()
        val adapter = uniffi.ferrostar.RouteAdapter.fromWellKnownRouteProvider(
            uniffi.ferrostar.WellKnownRouteProvider.Valhalla(BuildConfig.valhallaUrl, "auto").withJsonOptions(mapOf("units" to "miles")))
        val local = HybridRouteProvider(context, "http://127.0.0.1:9/route", client, log = ::report)
        try {
            val serverStart = SystemClock.elapsedRealtime()
            val response = client.toOkHttpClientProvider().call(adapter.generateRequest(initialSimulatedLocation, destination))
            val body = response.bodyBytes()!!
            assertTrue("Server comparison returned HTTP ${response.code}", response.isSuccessful)
            val reference = adapter.parseResponse(body).first()
            report("server_elapsed_ms=${SystemClock.elapsedRealtime()-serverStart} bytes=${body.size}")
            assertTrue("Route must exceed 50 miles", reference.distance > 50 * 1609.344)
            report("server_distance_m=${reference.distance}")
            HybridRouteProvider(context, BuildConfig.valhallaUrl, client, log = ::report).use { preferred ->
                val route = preferred.getRoutes(initialSimulatedLocation, destination).first()
                assertEquals("Reachable server preferred", RouteSource.SERVER, preferred.sourceOf(route))
                report("healthy_server_preferred=PASS")
            }
            repeat(2) { index ->
                val start = SystemClock.elapsedRealtime()
                val route = local.getRoutes(initialSimulatedLocation, destination).first()
                val elapsed = SystemClock.elapsedRealtime() - start
                assertEquals(RouteSource.DEVICE, local.sourceOf(route))
                val delta = kotlin.math.abs(route.distance - reference.distance) / reference.distance
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                report("local_run=${index+1} elapsed_ms=$elapsed distance_m=${route.distance} delta=$delta total_pss_kib=${memory.totalPss} native_pss_kib=${memory.nativePss} steps=${route.steps.size}")
                assertTrue("Local/server distance difference $delta exceeds 5%", delta <= 0.05)
                assertTrue("Local route took $elapsed ms", elapsed <= 10000)
                assertTrue("Process memory ${memory.totalPss} KiB exceeds 600 MiB", memory.totalPss < 614400)
                assertTrue(route.steps.size > 1 && route.geometry.size > 2)
            }
            try {
                local.getRoutes(initialSimulatedLocation, listOf(Waypoint(GeographicCoordinate(51.5074, -0.1278), WaypointKind.BREAK)))
                fail("Outside coverage unexpectedly routed")
            } catch (e: RoutingUnavailable) { report("outside_coverage=PASS ${e.message}") }
            HybridRouteProvider(context, "http://127.0.0.1:9/route", client,
                tiles = File(context.filesDir, "s6-intentionally-absent.tar")).use { missing ->
                try { missing.getRoutes(initialSimulatedLocation, destination); fail("Missing pack unexpectedly routed") }
                catch (e: RoutingUnavailable) { assertTrue(e.message!!.contains("missing")); report("missing_pack=PASS") }
            }
            local.close()
            // A real connected HTTP socket that never answers exercises the full server budget.
            java.net.ServerSocket(0).use { stalled ->
                val sleeper = Thread {
                    try { stalled.accept().use { Thread.sleep(7000) } }
                    catch (_: java.io.IOException) { /* test cleanup */ }
                    catch (_: InterruptedException) { /* test cleanup */ }
                }.apply { isDaemon = true; start() }
                try {
                    HybridRouteProvider(context, "http://127.0.0.1:${stalled.localPort}/route", client, log = ::report).use { timed ->
                        val start = SystemClock.elapsedRealtime()
                        val route = timed.getRoutes(initialSimulatedLocation, destination).first()
                        val elapsed = SystemClock.elapsedRealtime() - start
                        assertEquals(RouteSource.DEVICE, timed.sourceOf(route))
                        report("stalled_server_fallback_ms=$elapsed")
                        assertTrue("Includes real server timeout", elapsed >= 2800 && elapsed <= 10000)
                    }
                } finally { sleeper.interrupt() }
            }
        } finally { local.close() }
    }
}
