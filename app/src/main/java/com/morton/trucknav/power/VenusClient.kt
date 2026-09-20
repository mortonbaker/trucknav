package com.morton.trucknav.power

import android.util.Log
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

// Live numbers from the Venus OS Pi over its local MQTT broker. Venus only
// publishes while something sends a keepalive, so we do, every 30 s. Values
// arrive as {"value": x}; null means the source is not present (no battery
// monitor wired, no inverter, etc.) and is shown as a dash, never as zero.
data class PowerState(
    val connected: Boolean = false,
    // battery
    val soc: Double? = null,
    val voltage: Double? = null,
    val current: Double? = null,
    val power: Double? = null,
    val consumedAh: Double? = null,
    val timeToGoSec: Double? = null,
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
}

class VenusClient(private val host: String, private val portalId: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state
    private var client: MqttClient? = null
    private var job: Job? = null

    private val topics: Map<String, (PowerState, Double?) -> PowerState> = mapOf(
        "system/0/Dc/Battery/Soc" to { s, v -> s.copy(soc = v) },
        "system/0/Dc/Battery/Voltage" to { s, v -> s.copy(voltage = v) },
        "system/0/Dc/Battery/Current" to { s, v -> s.copy(current = v) },
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
    )

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { runOnce() } catch (e: Exception) { Log.w(TAG, "mqtt: ${e.message}") }
                _state.update { it.copy(connected = false) }
                delay(5_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null; try { client?.disconnect() } catch (e: Exception) {}; client = null }

    private fun matches(pattern: String, key: String): Boolean {
        val p = pattern.split('/'); val k = key.split('/')
        if (p.size != k.size) return false
        return p.indices.all { p[it] == "+" || p[it] == k[it] }
    }

    private suspend fun runOnce() {
        val c = MqttClient("tcp://$host:1883", "trucknav-" + System.currentTimeMillis().toString(36), MemoryPersistence())
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
        _state.update { it.copy(connected = true) }
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
