package com.morton.trucknav.routing

import android.content.Context
import com.valhalla.valhalla.Valhalla
import java.io.Closeable
import java.io.File
import org.json.JSONObject

class RoutingUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One lazy native actor, one mapped extract. Only called on the provider's IO worker. */
class OfflineValhalla(context: Context, private val tiles: File) : Closeable {
    private val configFile = File(context.filesDir, "routing/valhalla.json")
    private var engine: Valhalla? = null

    @Synchronized
    fun route(request: String): String {
        if (engine == null) {
            if (!tiles.isFile || tiles.length() < 512L || tiles.length() % 512L != 0L) {
                throw RoutingUnavailable("Offline routing pack is missing or incomplete. Connect to the server or install the routing pack.")
            }
            val resource = "/com/valhalla/valhalla/default.json"
            val defaults = Valhalla::class.java.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
                ?: throw RoutingUnavailable("Offline routing engine configuration is unavailable.")
            val config = JSONObject(defaults)
            config.getJSONObject("mjolnir").apply {
                put("tile_extract", tiles.absolutePath)
                put("tile_dir", "")
                put("tile_url", "") // Never silently fetch graph tiles from a network.
                put("traffic_extract", "")
                put("admin", "")
                put("timezone", "")
                put("landmarks", "")
                put("max_cache_size", 64 * 1024 * 1024)
                put("use_lru_mem_cache", true)
                put("lru_mem_cache_hard_control", true)
            }
            configFile.parentFile!!.mkdirs()
            configFile.writeText(config.toString())
            try { engine = Valhalla(configFile.absolutePath) }
            catch (e: LinkageError) { throw RoutingUnavailable("Offline routing engine is unavailable on this device.", e) }
        }
        return try { engine!!.routeRaw(request) }
        catch (e: Exception) {
            throw RoutingUnavailable("No offline route was found. Choose roads inside the installed routing region, or reconnect and retry.", e)
        }
    }

    @Synchronized
    override fun close() { engine?.close(); engine = null }
}
