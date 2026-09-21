package com.morton.trucknav.traffic

import android.content.Context
import android.net.*
import android.os.SystemClock
import com.morton.trucknav.nav.NavLog
import com.morton.trucknav.settings.Settings
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Traffic is advisory. It cannot start, replace or stop a Ferrostar route. */
object Traffic {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().callTimeout(2800, TimeUnit.MILLISECONDS)
        .connectTimeout(1500, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            if (!online.value) throw java.io.IOException("Offline")
            val path = chain.request().url.encodedPath
            val kind = when { "/tile/" in path -> "tile"; "incidentDetails" in path -> "incidents"; else -> "eta" }
            NavLog.log("traffic-request", "kind=$kind")
            chain.proceed(chain.request())
        }.build()
    private val providers by lazy { mapOf("tomtom" to TomTomTraffic(client)) }
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()
    private val _incidents = MutableStateFlow<List<TrafficIncident>>(emptyList())
    val incidents: StateFlow<List<TrafficIncident>> = _incidents.asStateFlow()
    @Volatile private var initialized = false
    @Volatile private var port = 0
    private var incidentJob: Job? = null
    private lateinit var prefs: android.content.SharedPreferences
    private val budget = TileBudget()
    private val tileWorkers = java.util.concurrent.ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(64))
    private val etas = LinkedHashMap<List<TrafficPoint>, TrafficEta>()
    private var lastConfig: List<Any?> = emptyList()
    private var configEpoch = 0L

    fun providerId(): String = Settings.get("trafficProvider")?.takeIf { it in providers } ?: "off"
    fun overlayEnabled() = Settings.get("trafficLayer") == "true"
    fun flowVisible() = initialized && online.value && overlayEnabled() && providerId() == "tomtom" && !Settings.get("tomtomKey").isNullOrBlank()
    fun tileTemplate(): String = "http://127.0.0.1:$port/${generation.value}/{z}/{x}/{y}.png"
    fun setOverlay(enabled: Boolean) = Settings.set("trafficLayer", enabled.toString())

    @Synchronized fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("traffic-meter", Context.MODE_PRIVATE)
        budget.restore(prefs.getString("window", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() })
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")); port = server.localPort
        initialized = true
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun updateOnline() {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateOnline()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateOnline()
            override fun onLost(network: Network) { _online.value = false }
        })
        updateOnline()
        scope.launch {
            combine(Settings.flow("trafficProvider"), Settings.flow("tomtomKey"), Settings.flow("trafficLayer"), online) { p, t, l, o -> listOf<Any?>(p,t,l,o) }
                .collect { config ->
                    synchronized(this@Traffic) {
                        if (config != lastConfig) {
                            lastConfig = config; configEpoch++; etas.clear()
                            client.dispatcher.cancelAll(); _incidents.value = emptyList()
                            _generation.value++
                        }
                    }
                    NavLog.log("traffic", "provider=${providerId()} online=${online.value} overlay=${flowVisible()}")
                }
        }
        scope.launch { while (isActive) { delay(60_000); if (flowVisible()) _generation.value++ } }
        scope.launch {
            while (isActive) {
                val socket = server.accept()
                // At most four requests perform upstream work; stale/offline queue entries are rejected.
                try { tileWorkers.execute {
                    socket.use { s ->
                        s.soTimeout = 3500
                        runCatching {
                            val input = s.getInputStream().bufferedReader()
                            val line = input.readLine().orEmpty().take(1024)
                            var header = input.readLine(); var count = 0
                            while (!header.isNullOrEmpty() && count++ < 32) header = input.readLine()
                            val match = Regex("GET /([0-9]+)/([0-9]+)/([0-9]+)/([0-9]+)\\.png HTTP/1\\.[01]").matchEntire(line)
                            val bytes = if (match != null) runBlocking {
                                val (epoch, z, x, y) = match.destructured
                                fetchTile(epoch.toLong(), z.toInt(), x.toInt(), y.toInt())
                            } else null
                            val body = bytes ?: ByteArray(0)
                            val status = if (bytes != null) "200 OK" else "204 No Content"
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 $status\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body); out.flush()
                        }
                    }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
            }
        }
    }

    private suspend fun fetchTile(epoch: Long, z: Int, x: Int, y: Int): ByteArray? {
        if (!flowVisible() || epoch != generation.value) return null
        val url = runCatching { providers.getValue("tomtom").flowTileUrl(z, x, y) }.getOrNull() ?: return null
        if (!reserveTile()) return null
        return try {
            val bytes = client.trafficBytes(Request.Builder().url(url).build())
            if (flowVisible() && epoch == generation.value) bytes else null
        } catch (_: Exception) { null }
    }

    @Synchronized private fun reserveTile(): Boolean {
        if (!online.value || !budget.take(System.currentTimeMillis())) return false
        val day = LocalDate.now().toString()
        val oldDay = prefs.getString("day", "")
        val count = (if (oldDay == day) prefs.getInt("count", 0) else 0) + 1
        // Persist before dispatch: a process restart must not reset the daily bill counter.
        prefs.edit().putString("day", day).putInt("count", count).putString("window", budget.snapshot().joinToString(",")).commit()
        NavLog.log("traffic", "tiles=$count day=$day")
        return true
    }

    /** Caller supplies THIS candidate / remaining leg, not the original full-trip geometry. */
    suspend fun etaWithTraffic(polyline: List<TrafficPoint>): TrafficEta? {
        if (!online.value || polyline.size < 2) return null
        val provider = providers[providerId()] ?: return null
        val identity = polyline.toList()
        val epoch = synchronized(this) { configEpoch }
        synchronized(this) { etas[identity]?.takeIf { it.fresh(SystemClock.elapsedRealtime()) } }?.let { return it }
        val start = SystemClock.elapsedRealtime()
        val duration = try { withTimeoutOrNull(3000) { provider.etaWithTraffic(identity) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (duration == null || SystemClock.elapsedRealtime() - start >= 3000 || !online.value) return null
        val result = TrafficEta(duration, provider.id, SystemClock.elapsedRealtime())
        synchronized(this) {
            if (epoch != configEpoch || providerId() != provider.id) return null
            etas[identity] = result
            while (etas.size > 8) etas.remove(etas.keys.first())
        }
        NavLog.log("traffic-eta", "provider=${provider.id} seconds=${duration.seconds} latencyMs=${SystemClock.elapsedRealtime() - start}")
        return result
    }

    suspend fun testKey(provider: String): String {
        if (!online.value) return "Offline — no request sent"
        if (provider == "tomtom" && !Settings.get("tomtomKey").isNullOrBlank() && !reserveTile()) return "Tile budget reached; retry later"
        return try { withTimeout(3000) { providers[provider]?.testKey() ?: "Unknown provider" } }
        catch (_: TimeoutCancellationException) { "Timed out (3 s)" }
        catch (e: CancellationException) { throw e }
        catch (e: TrafficHttpError) { "HTTP ${e.status}" }
        catch (_: Exception) { "Connection failed" }
    }

    /** Called by nav with its remaining leg; empty list clears pins on End/arrival. */
    fun setRemainingRoute(polyline: List<TrafficPoint>) {
        incidentJob?.cancel(); _incidents.value = emptyList()
        if (polyline.size < 2) return
        incidentJob = scope.launch {
            while (isActive) {
                if (flowVisible()) {
                    // Bound to ~25 km ahead to stay below TomTom's 10,000 km² bbox limit.
                    var meters = 0.0
                    val ahead = mutableListOf(polyline.first())
                    for ((a, b) in polyline.zipWithNext()) { meters += distanceMeters(a, b); if (meters > 25_000) break; ahead.add(b) }
                    if (ahead.size > 1) {
                        val bounds = TrafficBounds(ahead.minOf { it.lng } - .001, ahead.minOf { it.lat } - .001, ahead.maxOf { it.lng } + .001, ahead.maxOf { it.lat } + .001)
                        val found = try { withTimeout(3000) { providers.getValue("tomtom").incidents(bounds) } }
                            catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; emptyList() }
                            catch (_: Exception) { emptyList() }
                        if (isActive && flowVisible()) _incidents.value = found.filter { onCorridor(it.position, ahead) }
                    }
                }
                delay(60_000)
            }
        }
    }
}
