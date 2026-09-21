package com.morton.trucknav.settings

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Process-wide settings. Persist before publishing; null deletes a key. */
object Settings {
    private lateinit var file: AtomicFile
    private val values = linkedMapOf<String, String>()
    private val flows = mutableMapOf<String, MutableStateFlow<String?>>()

    @Synchronized fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.filesDir, "settings.json"))
        if (file.baseFile.exists()) {
            val obj = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            obj.keys().forEach { key -> if (!obj.isNull(key)) values[key] = obj.getString(key) }
        }
        // Retire credentials/provider persisted by pre-TomTom-only builds.
        if (values.containsKey("googleMapsKey") || values["trafficProvider"] == "google") {
            val retired = mutableMapOf<String, String?>("googleMapsKey" to null)
            if (values["trafficProvider"] == "google") retired["trafficProvider"] = "off"
            update(retired)
        }
        flows.forEach { (key, flow) -> flow.value = values[key] }
    }

    @Synchronized fun get(key: String): String? = values[key]

    @Synchronized fun set(key: String, value: String?) = update(mapOf(key to value))

    @Synchronized fun flow(key: String): StateFlow<String?> =
        flows.getOrPut(key) { MutableStateFlow(values[key]) }.asStateFlow()

    @Synchronized fun update(changes: Map<String, String?>) {
        check(::file.isInitialized) { "Settings.init must run before writes" }
        require(changes.keys.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}")) }) { "Invalid setting key" }
        changes["trafficProvider"]?.let { require(it in setOf("tomtom", "off")) { "trafficProvider: tomtom|off" } }
        require(changes["googleMapsKey"] == null) { "Google traffic is no longer supported" }
        changes["units"]?.let { require(it in setOf("imperial", "metric")) { "units: imperial|metric" } }
        changes["autoNight"]?.let { require(it in setOf("true", "false")) { "autoNight: true|false" } }
        changes.filterKeys { it.endsWith("Url") }.values.filterNotNull().filter { it.isNotBlank() }.forEach {
            val uri = java.net.URI(it)
            require(uri.scheme in setOf("http", "https") && uri.host != null) { "Server URL requires http(s) and host" }
        }
        val next = values.toMutableMap()
        changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        val stream = file.startWrite()
        try {
            stream.write(JSONObject(next as Map<*, *>).toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        values.clear()
        values.putAll(next)
        changes.keys.forEach { flows[it]?.value = values[it] }
    }

    fun isSecret(key: String): Boolean = listOf("key", "token", "pass", "password", "secret").any { key.endsWith(it, true) }

    /** Never return a complete short secret, nor a secret embedded in a URL. */
    @Synchronized fun snapshot(): Map<String, String> = values.mapValues { (key, value) ->
        if (value.isNotEmpty() && isSecret(key)) "••••" + (if (value.length > 4) value.takeLast(4) else "")
        else if (key.endsWith("Url", true)) value.replace(Regex("(?<=://)[^/@]+@"), "••••@").substringBefore('?').substringBefore('#')
        else value
    }
}
