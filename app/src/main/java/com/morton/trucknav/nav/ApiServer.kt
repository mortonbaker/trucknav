package com.morton.trucknav.nav

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.morton.trucknav.AppModule
import com.morton.trucknav.BuildConfig
import com.morton.trucknav.settings.Settings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import com.stadiamaps.ferrostar.core.isNavigating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uniffi.ferrostar.GeographicCoordinate
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// Small HTTP API so an agent (via the trucknav MCP on atlas01) can manage
// favorites and start/stop routes when the tablet is on the tailnet or LAN.
// Bearer token from local.properties (apiToken); no token = server stays off.
//   GET    /api/state                    navigating, route summary, position
//   GET    /api/favorites                [...]          GET /api/recent
//   POST   /api/favorites  {name,lat,lng,kind?}         -> the saved favorite
//   DELETE /api/favorites/<id>
//   POST   /api/navigate   {favorite:<id>} | {lat,lng,name?}
//   POST   /api/add_stop  {favorite:<id>} | {lat,lng,name?}   (409 unless navigating)
//   POST   /api/stop
class ApiServer(val port: Int = 8782) {
    companion object { private const val TAG = "ApiServer" }
    private val token get() = Settings.get("apiToken").orEmpty()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private val json = Json { prettyPrint = true }

    fun start() {
        if (server != null) return
        if (token.isBlank()) { Log.i(TAG, "API token missing"); return }
        val s = ServerSocket(port, 8); server = s
        Thread({ while (!s.isClosed) { try { val c = s.accept(); pool.execute { try { handle(c) } catch (e: Exception) { Log.w(TAG, "handle: $e") } } } catch (e: Exception) { if (!s.isClosed) Log.w(TAG, "accept: $e") } } }, "api-server").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on :$port")
    }

    private fun handle(sock: Socket) = sock.use { c ->
        c.soTimeout = 10_000
        val input = c.getInputStream().buffered()
        val out = c.getOutputStream()
        fun reply(code: Int, obj: Any) { val b = (if (obj is String) obj else json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), obj as kotlinx.serialization.json.JsonElement)).toByteArray(); out.write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b); out.flush() }
        fun line(): String {
            val b = java.io.ByteArrayOutputStream()
            while (b.size() < 8192) {
                val v = input.read()
                require(v >= 0) { "Incomplete HTTP header" }
                if (v == 10) return b.toString("ISO-8859-1").trimEnd('\r')
                b.write(v)
            }
            throw IllegalArgumentException("Header too large")
        }
        val req = line().split(' ')
        val method = req[0]
        val path = req.getOrElse(1) { "/" }.substringBefore('?')
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            headerBytes += l.length
            if (headerBytes > 32768) { reply(431, buildJsonObject { put("error", "Headers too large") }); return }
            headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
        }
        if (headers["authorization"] != "Bearer $token") { reply(401, buildJsonObject { put("error", "unauthorized") }); return }
        if (headers.containsKey("transfer-encoding")) { reply(400, buildJsonObject { put("error", "Content-Length required") }); return }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val limit = if (path == "/api/vehicle") 2 * 1024 * 1024 else 65536
        if (len < 0 || len > limit) { reply(413, buildJsonObject { put("error", "Body too large") }); return }
        if (headers["expect"].equals("100-continue", true)) { out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush() }
        val bytes = input.readNBytes(len)
        if (bytes.size != len) { reply(400, buildJsonObject { put("error", "Incomplete body") }); return }
        val body = bytes.toString(Charsets.UTF_8)
        NavLog.log("api", "$method $path")
        try {
            when {
                method == "GET" && path == "/api/settings" -> reply(200, buildJsonObject { Settings.snapshot().forEach { (k, v) -> put(k, v) } })
                method == "PUT" && path == "/api/settings" -> {
                    val obj = Json.parseToJsonElement(body).jsonObject
                    val changes = obj.mapValues { (_, v) ->
                        require(v == JsonNull || v is JsonPrimitive && v.isString) { "Settings values must be strings or null" }
                        if (v == JsonNull) null else v.jsonPrimitive.content
                    }
                    Settings.update(changes)
                    reply(200, buildJsonObject { Settings.snapshot().forEach { (k, v) -> put(k, v) } })
                }
                method == "GET" && path == "/api/vehicle" -> {
                    val png = com.morton.trucknav.settings.VehicleImage.bytes()
                    if (png == null) reply(404, buildJsonObject { put("error", "Using default vehicle") })
                    else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(png); out.flush()
                    }
                }
                method == "PUT" && path == "/api/vehicle" -> {
                    com.morton.trucknav.settings.VehicleImage.put(bytes)
                    reply(200, buildJsonObject { put("ok", true); put("width", 256); put("height", 256) })
                }
                method == "DELETE" && path == "/api/vehicle" -> {
                    com.morton.trucknav.settings.VehicleImage.delete()
                    reply(200, buildJsonObject { put("ok", true) })
                }
                method == "PUT" && path in setOf("/api/favorites/home", "/api/favorites/work") -> {
                    val o = Json.parseToJsonElement(body).jsonObject
                    val lat = o["lat"]!!.jsonPrimitive.doubleOrNull!!
                    val lng = o["lng"]!!.jsonPrimitive.doubleOrNull!!
                    require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0)
                    val kind = path.substringAfterLast('/')
                    reply(200, fav(Favorites.save(o["name"]?.jsonPrimitive?.content ?: kind.replaceFirstChar { it.uppercase() }, GeographicCoordinate(lat, lng), kind)))
                }
                method == "GET" && path == "/api/state" -> reply(200, state())
                method == "GET" && path == "/api/favorites" -> reply(200, buildJsonArray { Favorites.all.value.forEach { add(fav(it)) } })
                method == "GET" && path == "/api/recent" -> reply(200, buildJsonArray { Favorites.recent.value.forEach { add(buildJsonObject { put("name", it.name); put("lat", it.lat); put("lng", it.lng); put("at", it.at) }) } })
                method == "POST" && path == "/api/favorites" -> {
                    val o = Json.parseToJsonElement(body).jsonObject
                    val f = Favorites.save(o["name"]!!.jsonPrimitive.content, GeographicCoordinate(o["lat"]!!.jsonPrimitive.doubleOrNull!!, o["lng"]!!.jsonPrimitive.doubleOrNull!!), o["kind"]?.jsonPrimitive?.content ?: Favorites.PLACE)
                    reply(200, fav(f))
                }
                method == "DELETE" && path.startsWith("/api/favorites/") -> { Favorites.remove(path.substringAfterLast('/')); reply(200, buildJsonObject { put("ok", true) }) }
                method == "POST" && path == "/api/navigate" -> {
                    val o = Json.parseToJsonElement(body).jsonObject
                    val target = o["favorite"]?.jsonPrimitive?.content?.let { id -> Favorites.all.value.firstOrNull { it.id == id }?.let { it.coordinate to it.name } }
                        ?: (GeographicCoordinate(o["lat"]!!.jsonPrimitive.doubleOrNull!!, o["lng"]!!.jsonPrimitive.doubleOrNull!!) to (o["name"]?.jsonPrimitive?.content ?: "API destination"))
                    main.post { AppModule.viewModel.startNavigation(target.first, target.second) }
                    reply(200, buildJsonObject { put("ok", true); put("name", target.second) })
                }
                method == "POST" && path == "/api/add_stop" -> {
                    val o = Json.parseToJsonElement(body).jsonObject
                    val target = o["favorite"]?.jsonPrimitive?.content?.let { id -> Favorites.all.value.firstOrNull { it.id == id }?.let { it.coordinate to it.name } }
                        ?: (GeographicCoordinate(o["lat"]!!.jsonPrimitive.doubleOrNull!!, o["lng"]!!.jsonPrimitive.doubleOrNull!!) to (o["name"]?.jsonPrimitive?.content ?: "API stop"))
                    if (!AppModule.viewModel.navigationUiState.value.isNavigating()) reply(409, buildJsonObject { put("error", "not navigating") })
                    else { main.post { AppModule.viewModel.addStop(target.first, target.second) }; reply(200, buildJsonObject { put("ok", true); put("name", target.second) }) }
                }
                method == "POST" && path == "/api/stop" -> { main.post { AppModule.viewModel.stopNavigation() }; reply(200, buildJsonObject { put("ok", true) }) }
                else -> reply(404, buildJsonObject { put("error", "no such route") })
            }
        } catch (e: Exception) { reply(400, buildJsonObject { put("error", "Invalid request") }) }
    }

    private fun fav(f: Favorite): JsonObject = buildJsonObject { put("id", f.id); put("name", f.name); put("lat", f.lat); put("lng", f.lng); put("kind", f.kind) }

    private fun state(): JsonObject {
        val s = AppModule.viewModel.navigationUiState.value
        return buildJsonObject {
            put("navigating", s.isNavigating())
            s.location?.let { put("lat", it.coordinates.lat); put("lng", it.coordinates.lng) }
            s.progress?.let { put("remainingMi", it.distanceRemaining / 1609.344); put("etaMin", it.durationRemaining / 60.0); put("nextManeuverM", it.distanceToNextManeuver) }
            s.visualInstruction?.let { put("instruction", it.primaryContent.text) }
            put("road", s.currentStepRoadName ?: "")
            put("version", BuildConfig.VERSION_NAME)
        }
    }
}
