package com.morton.trucknav.traffic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.morton.trucknav.settings.Settings
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrafficProviderTest {
    private val points = listOf(TrafficPoint(32.9, -97.3), TrafficPoint(32.95, -97.31), TrafficPoint(33.0, -97.32))
    private val saved = mutableMapOf<String, String?>()
    @Before fun setup() {
        Settings.init(InstrumentationRegistry.getInstrumentation().targetContext)
        listOf("tomtomKey").forEach { saved[it] = Settings.get(it); Settings.set(it, UUID.randomUUID().toString()) }
    }
    @After fun restore() { saved.forEach { (k,v) -> Settings.set(k,v) } }
    private fun client(code: Int = 200, body: String, inspect: (Request) -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(2800, TimeUnit.MILLISECONDS).addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun tomtomPreservesGeometryAndReadsSettings() = runBlocking {
        val provider = TomTomTraffic(client(body = """{"routes":[{"summary":{"travelTimeInSeconds":1140}}]}""") { req ->
            assertEquals(Settings.get("tomtomKey"), req.url.queryParameter("key"))
            assertEquals("true", req.url.queryParameter("traffic"))
            assertEquals("strict", req.url.queryParameter("reconstructionMode"))
            val buffer = okio.Buffer(); req.body!!.writeTo(buffer)
            val sent = Json.parseToJsonElement(buffer.readUtf8()).jsonObject["supportingPoints"]!!.jsonArray
            assertEquals(3, sent.size)
            assertEquals(points[1].lat, sent[1].jsonObject["latitude"]!!.jsonPrimitive.double, 0.000001)
        })
        assertEquals(1140, provider.etaWithTraffic(points)!!.seconds)
        val url = provider.flowTileUrl(12, 100, 200)!!
        assertTrue(url.contains("/relative0/12/100/200.png"))
        Settings.set("tomtomKey", null)
        assertNull(provider.flowTileUrl(12,100,200)); assertNull(provider.etaWithTraffic(points))
    }
    @Test fun unauthorizedReportsOnlyHttpStatus() = runBlocking {
        for (provider in listOf(TomTomTraffic(client(403,"secret must not reflect")))) {
            try { provider.testKey(); fail("must reject") } catch (error: TrafficHttpError) { assertEquals(403,error.status); assertEquals("HTTP 403",error.message) }
        }
    }
    @Test fun emptyProviderResultsLeavePlainEta() = runBlocking {
        assertNull(TomTomTraffic(client(body="""{"routes":[]}""")).etaWithTraffic(points))
    }
    @Test fun deadlineCancelsLateProvider() = runBlocking {
        val provider = TomTomTraffic(client(body="""{"routes":[{"summary":{"travelTimeInSeconds":1140}}]}""") { Thread.sleep(3200) })
        val start = System.nanoTime()
        assertNull(withTimeoutOrNull(3000) { provider.etaWithTraffic(points) })
        assertTrue((System.nanoTime()-start)/1_000_000 < 3150)
    }
    @Test fun retiredGoogleSettingsAreRejected() {
        for (change in listOf("trafficProvider" to "google", "googleMapsKey" to "retired-fixture")) {
            try { Settings.set(change.first, change.second); fail("retired setting accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun freshnessBoundaryAndDisplay() {
        val eta = TrafficEta(Duration.ofSeconds(1140), "tomtom", 1000)
        assertEquals("· 19 min w/ traffic", eta.label)
        assertTrue(eta.fresh(600999)); assertFalse(eta.fresh(601000)); assertFalse(eta.fresh(999))
    }
    @Test fun rollingBudgetCannotBurstAcrossBoundary() {
        val budget = TileBudget()
        repeat(2000) { assertTrue(budget.take(it.toLong())) }
        assertFalse(budget.take(1_799_999)); assertTrue(budget.take(1_800_000)); assertFalse(budget.take(1_800_000))
    }
    @Test fun budgetRestoresProcessRestart() {
        val before = TileBudget(2)
        assertTrue(before.take(1000)); assertTrue(before.take(2000))
        val after = TileBudget(2); after.restore(before.snapshot())
        assertFalse(after.take(3000)); assertTrue(after.take(1_801_000))
    }
    @Test fun deferredBootDoesNotCrashWhenAndroidRejectsForegroundService() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val context = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? {
                throw android.app.ForegroundServiceStartNotAllowedException("test background restriction")
            }
        }
        com.morton.trucknav.overlay.BootReceiver().onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
    }
    @Test fun tomtomParsesPointAndLineIncidents() = runBlocking {
        val provider = TomTomTraffic(client(body="""{"incidents":[
          {"geometry":{"type":"Point","coordinates":[-97.31,32.95]},"properties":{"id":"point","events":[{"description":"Road closed"}]}},
          {"geometry":{"type":"LineString","coordinates":[[-97.30,32.90],[-97.31,32.95]]},"properties":{"id":"line","events":[{"description":"Queue"}]}},
          {"geometry":{},"properties":{}}
        ]}"""))
        val incidents = provider.incidents(TrafficBounds(-97.4,32.8,-97.2,33.1))
        assertEquals(2,incidents.size); assertEquals("Road closed",incidents[0].label)
        assertEquals(TrafficPoint(32.9,-97.3),incidents[1].position)
    }
    @Test fun waypointLimitAndOrdering() {
        val route = (0..100).map { TrafficPoint(32 + it * .001, -97.0) }
        val sampled = sampleRoute(route,27)
        assertEquals(27,sampled.size); assertEquals(route.first(),sampled.first()); assertEquals(route.last(),sampled.last())
        assertTrue(sampled.zipWithNext().all { (a,b) -> a.lat < b.lat })
    }
    @Test fun incidentsOnlyOnRemainingCorridor() {
        assertTrue(onCorridor(TrafficPoint(32.95,-97.31),points))
        assertFalse(onCorridor(TrafficPoint(32.5,-97.3),points))
        assertFalse(onCorridor(TrafficPoint(32.95,-98.0),points))
    }
}
