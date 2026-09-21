package com.morton.trucknav.power

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// The 4Runner's ESPHome relay board ("4runner-relay", LC 8-relay). Relay 1
// powers the Starlink, relay 2 the rear lights; names come from 4runner.yaml
// and are read back from the board so renaming a pin renames its tile. The
// board takes DHCP on whichever Wi-Fi it joined and the tablet has no mDNS
// resolver we can trust, so we find it the blunt way: hit /switch/<id> on
// every host of our own /24 and keep the one that answers (same trick as
// /data/starlink/relay.sh on the Venus Pi).
data class RelaySwitch(val id: String, val name: String, val on: Boolean)

data class RelayState(
    val host: String? = null,                    // where the board answered last
    val switches: List<RelaySwitch> = emptyList(),
    val updatedAt: Long = 0L,
    val busy: Boolean = false,                   // a scan or a switch is in flight
    val error: String? = null,
) {
    val reachable get() = switches.isNotEmpty()
    fun on(id: String): Boolean? = switches.firstOrNull { it.id == id }?.on
}

class RelayClient(private val ctx: Context) {
    companion object {
        private const val TAG = "RelayClient"
        const val STARLINK = "relay_1__starlink_"
        const val REAR_LIGHTS = "relay_2_rear_lights"
        // ESPHome object ids for the eight pins as named in 4runner.yaml
        val IDS = listOf(STARLINK, REAR_LIGHTS, "relay_3__pin_14_", "relay_4__pin_25_", "relay_5__pin_26_", "relay_6__pin_27_", "relay_7__pin_32_", "relay_8__pin_33_")
        private const val PREF = "relay"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(RelayState(host = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("host", null)))
    val state: StateFlow<RelayState> = _state
    private var polling = false

    fun start() {
        if (polling) return; polling = true
        scope.launch { while (true) { refresh(); delay(15_000) } }
    }

    // GET /switch/<id> → RelaySwitch, or null when that host is not the board / pin unknown.
    private fun read(host: String, id: String): RelaySwitch? = try {
        http.newCall(Request.Builder().url("http://$host/switch/$id").build()).execute().use { r ->
            val b = r.body?.string() ?: return null
            if (!r.isSuccessful || !b.contains("\"name_id\":\"switch/")) return null
            val name = b.substringAfter("\"name_id\":\"switch/").substringBefore('"')
            RelaySwitch(id, name, b.contains("\"state\":\"ON\""))
        }
    } catch (_: Exception) { null }

    private fun isBoard(host: String) = read(host, STARLINK)?.name?.contains("Starlink") == true

    private fun ownSubnets(): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }
            .flatMap { lp -> lp.linkAddresses }.map(LinkAddress::getAddress)
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .map { it.hostAddress!!.substringBeforeLast('.') }
            .filter { !it.startsWith("100.") }   // not the tailnet
            .distinct()
    }

    // Cached host first, then the board's own AP, then a parallel /24 sweep (64 wide, ~4 s worst case).
    private suspend fun discover(): String? {
        val configured = com.morton.trucknav.settings.Settings.get("relayHost")?.takeIf { it.isNotBlank() }
        if (configured != null) return configured.takeIf { isBoard(it) }
        val candidates = listOfNotNull(_state.value.host, "192.168.4.1")
        for (h in candidates) if (isBoard(h)) return h
        val sem = Semaphore(64)
        for (net in ownSubnets()) {
            Log.i(TAG, "scanning $net.0/24 for the relay board")
            val hit = (1..254).map { i -> scope.async { sem.withPermit { "$net.$i".takeIf { isBoard(it) } } } }.awaitAll().firstOrNull { it != null }
            if (hit != null) return hit
        }
        return null
    }

    private fun readAll(host: String): List<RelaySwitch> = IDS.mapNotNull { read(host, it) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(busy = true)
        val host = discover()
        if (host == null) { _state.value = RelayState(host = _state.value.host, updatedAt = System.currentTimeMillis(), error = "relay board not on this network"); return@withContext }
        if (host != _state.value.host) ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("host", host).apply()
        _state.value = RelayState(host = host, switches = readAll(host), updatedAt = System.currentTimeMillis())
    }

    fun set(id: String, on: Boolean) { scope.launch {
        val host = _state.value.host?.takeIf { isBoard(it) } ?: discover() ?: run { _state.value = _state.value.copy(busy = false, error = "relay board not on this network"); return@launch }
        _state.value = _state.value.copy(busy = true)
        try {
            http.newCall(Request.Builder().url("http://$host/switch/$id/turn_${if (on) "on" else "off"}").post(ByteArray(0).toRequestBody()).build()).execute().close()
            delay(600)
            _state.value = RelayState(host = host, switches = readAll(host), updatedAt = System.currentTimeMillis())
            Log.i(TAG, "$id -> ${_state.value.on(id)} via $host")
        } catch (e: Exception) { Log.w(TAG, "set $id: $e"); _state.value = _state.value.copy(busy = false, error = e.message); refresh() }
    } }

    fun toggle(id: String) = set(id, _state.value.on(id) != true)
}
