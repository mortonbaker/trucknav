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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// The 4Runner's ESPHome relay board ("4runner-relay", LC 8-relay). Relay 1
// powers the Starlink, relay 2 the rear lights; names come from 4runner.yaml
// and are read back from the board so renaming a pin renames its tile. The
// board takes DHCP on whichever Wi-Fi it joined and the tablet has no mDNS
// resolver we can trust, so we find it the blunt way: hit /switch/<id> on
// every host of our own /24 and keep the one that answers (same trick as
// /data/starlink/relay.sh on the Venus Pi).
//
// Once found, state is pushed, not polled: one long-lived GET /events (ESPHome's
// Server-Sent Events, what its own web page uses) delivers every switch on connect
// and every change within ~0.3 s, whoever made it (tablet, web page, HA, a panel
// button). The board pings every 10 s; 25 s of silence means the link is gone.
data class RelaySwitch(val id: String, val name: String, val on: Boolean)

data class RelayState(
    val host: String? = null,                    // where the board answered last
    val switches: List<RelaySwitch> = emptyList(),
    val updatedAt: Long = 0L,
    val busy: Boolean = false,                   // looking for the board, nothing known yet
    val pending: Set<String> = emptySet(),       // commands sent, board not yet confirmed
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
    // The event stream: no call deadline, but a read gap longer than 2.5 pings is a dead link.
    private val stream = http.newBuilder().readTimeout(25, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(RelayState(host = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("host", null)))
    val state: StateFlow<RelayState> = _state
    private var polling = false

    fun start() {
        if (polling) return; polling = true
        scope.launch {
            while (true) {
                if (_state.value.switches.isEmpty()) _state.value = _state.value.copy(busy = true)
                val host = discover()
                if (host == null) {
                    _state.value = RelayState(host = _state.value.host, updatedAt = System.currentTimeMillis(), error = "relay board not on this network")
                    delay(10_000); continue
                }
                if (host != _state.value.host) ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("host", host).apply()
                try { listen(host) } catch (e: Exception) { Log.w(TAG, "events $host: $e") }
                // Stream ended: grey the tiles rather than show state we can no longer vouch for.
                _state.value = RelayState(host = host, updatedAt = System.currentTimeMillis(), error = "relay board connection lost, reconnecting")
                delay(1_000)
            }
        }
    }

    // Blocks for as long as the board keeps talking. Every "state" event for a switch
    // replaces that tile; its arrival also clears the tile's pending flag.
    private fun listen(host: String) {
        stream.newCall(Request.Builder().url("http://$host/events").header("Accept", "text/event-stream").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            Log.i(TAG, "events connected to $host")
            val src = r.body!!.source()
            var event = ""; val data = StringBuilder()
            while (true) {
                val line = src.readUtf8Line() ?: return
                when {
                    line.isEmpty() -> { if (event == "state") onState(host, data.toString()); event = ""; data.setLength(0) }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                }
            }
        }
    }

    private fun onState(host: String, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        // Change events carry only name_id/id/value/state (ESPHome DETAIL_STATE); "name" comes
        // with the first dump only. Losing it sent 0.40.0's turn_off to /switch//turn_off (404).
        // name_id ("switch/Relay 1 (Starlink)") is in every event; "id" moves to a new format in
        // ESPHome 2026.8, so our key is derived from the name the way ESPHome makes object ids.
        val nameId = o.optString("name_id")
        if (!nameId.startsWith("switch/")) return
        val name = nameId.removePrefix("switch/")
        synchronized(this) {
            val cur = _state.value
            val sw = RelaySwitch(objectId(name), name, o.optBoolean("value"))
            val list = (cur.switches.filter { it.id != sw.id } + sw).sortedBy { IDS.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
            _state.value = cur.copy(host = host, switches = list, busy = false, error = null, pending = cur.pending - sw.id, updatedAt = System.currentTimeMillis())
        }
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


    // Tile flips at once; the board's state event confirms it (pending clears). A failed
    // POST puts the tile back. ESPHome wants the entity name in the URL (the object-id
    // form is deprecated); OkHttp percent-encodes "Relay 1 (Starlink)".
    fun set(id: String, on: Boolean) { scope.launch {
        val cur = _state.value
        val host = cur.host?.takeIf { cur.switches.isNotEmpty() } ?: discover() ?: run { _state.value = _state.value.copy(error = "relay board not on this network"); return@launch }
        val name = cur.switches.firstOrNull { it.id == id }?.name ?: read(host, id)?.name ?: return@launch
        val before = _state.value.on(id)
        synchronized(this@RelayClient) {
            _state.value = _state.value.copy(pending = _state.value.pending + id, switches = _state.value.switches.map { if (it.id == id) it.copy(on = on) else it })
        }
        try {
            val url = "http://$host/".toHttpUrl().newBuilder().addPathSegment("switch").addPathSegment(name).addPathSegment(if (on) "turn_on" else "turn_off").build()
            http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}") }
            Log.i(TAG, "$id -> $on via $host")
            // No event comes if the relay was already in that state; stop waiting after 3 s.
            delay(3_000); synchronized(this@RelayClient) { _state.value = _state.value.copy(pending = _state.value.pending - id) }
        } catch (e: Exception) {
            Log.w(TAG, "set $id: $e")
            synchronized(this@RelayClient) {
                _state.value = _state.value.copy(error = e.message, pending = _state.value.pending - id,
                    switches = _state.value.switches.map { if (it.id == id && before != null) it.copy(on = before) else it })
            }
        }
    } }

    fun toggle(id: String) = set(id, _state.value.on(id) != true)

    // ESPHome's object id: lower-case, anything but [a-z0-9-] becomes '_'. "Relay 1 (Starlink)" -> "relay_1__starlink_".
    private fun objectId(name: String) = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '_' }.joinToString("")
}
