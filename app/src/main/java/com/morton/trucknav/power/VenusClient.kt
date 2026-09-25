package com.morton.trucknav.power

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.roundToLong

// Live numbers from the Venus OS Pi over its local MQTT broker. Venus only
// publishes while something sends a keepalive, so we do, every 30 s. Values
// arrive as {"value": x}; null means the source is not present (no battery
// monitor wired, no inverter, etc.) and is shown as a dash, never as zero.
//
// Reaching the Pi: the tailnet address first (works anywhere both ends have
// internet), then the LAN address we last saw it on, then a sweep of our own
// /24 for a broker on 1883 (Everylink with the dish up but no WAN, or the
// board's AP). A phone hotspot with client isolation defeats the LAN paths by
// design; there the tailnet is the only route and the Pi must be online.
data class PowerState(
    val connected: Boolean = false,
    val host: String? = null,           // where the broker answered
    val path: String = "connecting",    // "tailnet" | "lan" | why not
    val socFromBattery: Double? = null, // battery service SOC when the system service has none
    // battery
    val soc: Double? = null,
    val voltage: Double? = null,
    val current: Double? = null,
    val power: Double? = null,
    val consumedAh: Double? = null,
    val timeToGoSec: Double? = null,
    val capacityAh: Double? = null,     // battery monitor's configured capacity
    val currentAvg: Double? = null,     // battery current, smoothed (TAU_SEC); drives the time estimate
    val temperature: Double? = null,
    val batteryState: Int? = null,      // 0 idle 1 charging 2 discharging
    // sources
    val pvPower: Double? = null,
    val pvVoltage: Double? = null,
    val pvCurrent: Double? = null,
    val pvYieldToday: Double? = null,   // kWh
    val chargerState: Int? = null,      // 0 off 3 bulk 4 absorption 5 float ...
    val alternatorPower: Double? = null,
    val gridPower: Double? = null,
    // loads
    val dcLoadPower: Double? = null,
    val acLoadPower: Double? = null,
    val inverterState: Int? = null,
    val systemState: Int? = null,
    val lowSoc: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val chargerStateText get() = when (chargerState) { null -> "--"; 0 -> "Off"; 2 -> "Fault"; 3 -> "Bulk"; 4 -> "Absorption"; 5 -> "Float"; 6 -> "Storage"; 7 -> "Equalize"; 11 -> "Other"; 245 -> "Wake-up"; 247 -> "Auto EQ"; 252 -> "Ext. control"; else -> "State $chargerState" }
    val batteryStateText get() = when (batteryState) { 1 -> "Charging"; 2 -> "Discharging"; 0 -> "Idle"; else -> if ((current ?: 0.0) > 0.2) "Charging" else if ((current ?: 0.0) < -0.2) "Discharging" else "Idle" }
    // Net into the battery = sources - loads; positive is good.
    val netWatts: Double? get() = power
    val socShown: Double? get() = soc ?: socFromBattery

    // Jackery-style estimate for the strip: (label, value). Below IDLE_AMPS either way
    // nothing is shown, because 0.1 A gives a meaningless "400h". Discharging prefers the
    // monitor's own TimeToGo; charging is computed, since Venus publishes none.
    val eta: Pair<String, String> get() {
        val i = currentAvg ?: current; val s = socShown
        if (i == null || s == null) return "Time" to "--"
        if (s >= 99.5 && i > -IDLE_AMPS) return "Battery" to "Full"
        if (i > IDLE_AMPS) return "To full" to (capacityAh?.let { fmtDuration((100 - s) / 100 * it / i * 3600) } ?: "--")
        if (i < -IDLE_AMPS) return "Left" to ((timeToGoSec ?: capacityAh?.let { s / 100 * it / -i * 3600 })?.let(::fmtDuration) ?: "--")
        return "Time" to "--"
    }

    companion object {
        const val IDLE_AMPS = 0.3
        const val TAU_SEC = 60.0
        fun fmtDuration(sec: Double): String {
            val m = (sec / 60).roundToLong()
            return when { m < 1 -> "<1m"; m < 60 -> "${m}m"; m < 100 * 60 -> "${m / 60}h ${m % 60}m"; else -> ">99h" }
        }
    }
}

class VenusClient(private val ctx: Context, private val host: String, private val portalId: String, private val scope: CoroutineScope) {
    private val prefs get() = ctx.getSharedPreferences("venus", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state
    private var client: MqttClient? = null
    private var job: Job? = null
    private var smoother: Job? = null

    private val topics: Map<String, (PowerState, Double?) -> PowerState> = mapOf(
        "system/0/Dc/Battery/Soc" to { s, v -> s.copy(soc = v) },
        "system/0/Dc/Battery/Voltage" to { s, v -> s.copy(voltage = v) },
        // A charge/discharge flip (charger unplugged) restarts the average instead of
        // showing the old direction for a minute.
        "system/0/Dc/Battery/Current" to { s, v ->
            val a = s.currentAvg
            s.copy(current = v, currentAvg = if (v == null) null else if (a == null || (v > PowerState.IDLE_AMPS && a < 0) || (v < -PowerState.IDLE_AMPS && a > 0)) v else a)
        },
        "system/0/Dc/Battery/Power" to { s, v -> s.copy(power = v) },
        "system/0/Dc/Battery/ConsumedAmphours" to { s, v -> s.copy(consumedAh = v) },
        "system/0/Dc/Battery/TimeToGo" to { s, v -> s.copy(timeToGoSec = v) },
        "system/0/Dc/Battery/Temperature" to { s, v -> s.copy(temperature = v) },
        "system/0/Dc/Battery/State" to { s, v -> s.copy(batteryState = v?.toInt()) },
        "system/0/Dc/Pv/Power" to { s, v -> s.copy(pvPower = v) },
        "system/0/Dc/Pv/Current" to { s, v -> s.copy(pvCurrent = v) },
        "system/0/Dc/Alternator/Power" to { s, v -> s.copy(alternatorPower = v) },
        "system/0/Dc/System/Power" to { s, v -> s.copy(dcLoadPower = v) },
        "system/0/Ac/Consumption/L1/Power" to { s, v -> s.copy(acLoadPower = v) },
        "system/0/Ac/Grid/L1/Power" to { s, v -> s.copy(gridPower = v) },
        "system/0/SystemState/State" to { s, v -> s.copy(systemState = v?.toInt()) },
        "system/0/SystemState/LowSoc" to { s, v -> s.copy(lowSoc = (v ?: 0.0) > 0) },
    )
    // Wildcard device topics (the solar charger's own service id varies).
    private val wildcards = listOf(
        "solarcharger/+/State" to { s: PowerState, v: Double? -> s.copy(chargerState = v?.toInt()) },
        "solarcharger/+/Pv/V" to { s: PowerState, v: Double? -> s.copy(pvVoltage = v) },
        "solarcharger/+/History/Daily/0/Yield" to { s: PowerState, v: Double? -> s.copy(pvYieldToday = v) },
        "vebus/+/State" to { s: PowerState, v: Double? -> s.copy(inverterState = v?.toInt()) },
        // B8: a wired battery monitor the system service has not adopted still publishes here.
        "battery/+/Soc" to { s: PowerState, v: Double? -> s.copy(socFromBattery = v) },
        "battery/+/Capacity" to { s: PowerState, v: Double? -> s.copy(capacityAh = v) },
    )

    fun start() {
        if (job != null) return
        // Time-based EMA on a fixed 1 s tick, so the smoothing is the same however often
        // the Pi publishes (it only sends changes).
        smoother = scope.launch {
            val k = 1 - exp(-1.0 / PowerState.TAU_SEC)
            while (isActive) {
                delay(1_000)
                _state.update { s -> val c = s.current; val a = s.currentAvg; if (c == null || a == null) s else s.copy(currentAvg = a + k * (c - a)) }
            }
        }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val hosts = pickHosts()
                if (hosts.isEmpty()) { _state.update { it.copy(connected = false, path = "no Pi on the tailnet or this LAN") }; delay(10_000); continue }
                // Any MQTT broker answers the port probe (Home Assistant's did, 2026-09-25, and got
                // cached as "the Pi"). A host counts only once it accepts us; try each in turn.
                for (h in hosts) {
                    try { runOnce(h.first, h.second); break } catch (e: Exception) {
                        Log.w(TAG, "mqtt ${h.first}: ${e.message}")
                        if (h.second == "lan" && prefs.getString("lan", null) == h.first) prefs.edit().remove("lan").apply()
                    }
                }
                _state.update { it.copy(connected = false) }
                delay(5_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; smoother?.cancel(); smoother = null; try { client?.disconnect() } catch (e: Exception) {}; client = null }

    private fun matches(pattern: String, key: String): Boolean {
        val p = pattern.split('/'); val k = key.split('/')
        if (p.size != k.size) return false
        return p.indices.all { p[it] == "+" || p[it] == k[it] }
    }

    private fun tcpOpen(h: String, ms: Int): Boolean = try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(h, 1883), ms); true } } catch (_: Exception) { false }

    private fun ownSubnets(): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }.flatMap { it.linkAddresses }.map(LinkAddress::getAddress)
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.map { it.hostAddress!!.substringBeforeLast('.') }
            .filter { !it.startsWith("100.") }.distinct()
    }

    // Candidate (host, path)s, best first. Cheap TCP probes; the MQTT connect is the real test.
    private suspend fun pickHosts(): List<Pair<String, String>> {
        if (tcpOpen(host, 2500)) return listOf(host to "tailnet")
        prefs.getString("lan", null)?.let { if (tcpOpen(it, 800)) return listOf(it to "lan") }
        val sem = Semaphore(64)
        for (net in ownSubnets()) {
            Log.i(TAG, "scanning $net.0/24 for a broker on 1883")
            val hits = (1..254).map { i -> scope.async(Dispatchers.IO) { sem.withPermit { "$net.$i".takeIf { tcpOpen(it, 400) } } } }.awaitAll().filterNotNull()
            if (hits.isNotEmpty()) return hits.map { it to "lan" }
        }
        return emptyList()
    }

    private suspend fun runOnce(h: String, path: String) {
        val c = MqttClient("tcp://$h:1883", "trucknav-" + System.currentTimeMillis().toString(36), MemoryPersistence())
        client = c
        c.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallback {
            override fun connectionLost(cause: Throwable?) { _state.update { it.copy(connected = false) } }
            override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            override fun messageArrived(topic: String, message: org.eclipse.paho.client.mqttv3.MqttMessage) {
                val key = topic.removePrefix("N/$portalId/")
                val apply = topics[key] ?: wildcards.firstOrNull { matches(it.first, key) }?.second ?: return
                val v = try { val o = JSONObject(String(message.payload)); if (o.isNull("value")) null else o.optDouble("value") } catch (e: Exception) { null }
                _state.update { apply(it, v).copy(updatedAt = System.currentTimeMillis()) }
            }
        })
        c.connect(MqttConnectOptions().apply { isCleanSession = true; connectionTimeout = 8; keepAliveInterval = 30; isAutomaticReconnect = false })
        if (path == "lan") prefs.edit().putString("lan", h).apply()   // remembered only once it let us in
        _state.update { it.copy(connected = true, host = h, path = path) }
        Log.i(TAG, "connected to $h via $path")
        topics.keys.forEach { c.subscribe("N/$portalId/$it", 0) }
        wildcards.forEach { c.subscribe("N/$portalId/${it.first}", 0) }
        while (c.isConnected && scope.isActive) {
            c.publish("R/$portalId/keepalive", ByteArray(0), 0, false)
            delay(30_000)
        }
        try { c.disconnect() } catch (e: Exception) {}
    }

    companion object { private const val TAG = "VenusClient" }
}
