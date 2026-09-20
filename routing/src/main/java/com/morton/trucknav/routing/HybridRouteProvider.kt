package com.morton.trucknav.routing

import android.content.Context
import android.os.SystemClock
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.withJsonOptions
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WellKnownRouteProvider

/** Identical Ferrostar-generated requests and parser on both paths, including reroutes. */
class HybridRouteProvider(
    context: Context,
    endpoint: String,
    client: OkHttpClient,
    tiles: File = File(context.getExternalFilesDir(null), "routing/valhalla_tiles.tar"),
    private val log: (String) -> Unit = {},
) : CustomRouteProvider, Closeable {
    private val adapter = RouteAdapter.fromWellKnownRouteProvider(
        WellKnownRouteProvider.Valhalla(endpoint, "auto").withJsonOptions(mapOf("units" to "miles")))
    private val http = client.newBuilder().callTimeout(Duration.ofSeconds(3))
        .connectTimeout(Duration.ofSeconds(3)).retryOnConnectionFailure(false).build().toOkHttpClientProvider()
    private val offline = OfflineValhalla(context.applicationContext, tiles)
    private val lock = Mutex()
    // Bind attribution to returned routes, not the last completed request (which may be stale).
    private val sources = ArrayDeque<Pair<Route, RouteSource>>()

    @Synchronized
    fun sourceOf(route: Route): RouteSource? = sources.lastOrNull { it.first === route }?.second

    @Synchronized
    private fun remember(routes: List<Route>, source: RouteSource) {
        routes.forEach { sources.addLast(it to source) }
        while (sources.size > 8) sources.removeFirst()
    }

    override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                currentCoroutineContext().ensureActive()
                val started = SystemClock.elapsedRealtime()
                val request = adapter.generateRequest(userLocation, waypoints) as RouteRequest.HttpPost
                fun parse(body: ByteArray): List<Route> = adapter.parseResponse(body).also {
                    if (it.isEmpty()) throw RoutingUnavailable("No route was found for this destination.")
                }
                val result = serverThenDevice(
                    server = {
                        val response = http.call(request)
                        val body = response.bodyBytes() // Also closes the response on HTTP errors.
                        if (!response.isSuccessful) throw IOException("Routing server HTTP ${response.code}")
                        parse(body ?: throw IOException("Empty routing response"))
                    },
                    device = {
                        log("fallback to on-device")
                        val body = offline.route(request.body.decodeToString())
                        currentCoroutineContext().ensureActive() // JNI is synchronous; discard canceled results.
                        parse(body.toByteArray())
                    },
                )
                currentCoroutineContext().ensureActive()
                remember(result.value, result.source)
                log("source=${result.source.label} elapsed_ms=${SystemClock.elapsedRealtime() - started} distance_m=${result.value.first().distance}")
                result.value
            }
        }

    override fun close() { offline.close() }
}
