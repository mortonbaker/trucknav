package com.morton.trucknav.nav

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import uniffi.ferrostar.GeographicCoordinate
import java.io.File

// Saved places and recent destinations. One JSON file each in the app's
// external files dir (survives reinstall, readable over adb, and the same
// files the HTTP API / MCP will edit). Home and Work are just favorites with
// a fixed kind so they can be pinned first.
data class Favorite(val id: String, val name: String, val lat: Double, val lng: Double, val kind: String = "place", val addedAt: Long = System.currentTimeMillis()) {
    val coordinate get() = GeographicCoordinate(lat, lng)
}

data class Recent(val name: String, val lat: Double, val lng: Double, val at: Long) {
    val coordinate get() = GeographicCoordinate(lat, lng)
}

object Favorites {
    private const val TAG = "Favorites"
    const val HOME = "home"; const val WORK = "work"; const val PLACE = "place"
    // No serialization plugin in this project: JSON by hand.
    private val json = Json { prettyPrint = true }
    private fun favToJson(f: Favorite) = buildJsonObject { put("id", f.id); put("name", f.name); put("lat", f.lat); put("lng", f.lng); put("kind", f.kind); put("addedAt", f.addedAt) }
    private fun favFrom(o: JsonObject) = Favorite(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["lat"]!!.jsonPrimitive.doubleOrNull ?: 0.0, o["lng"]!!.jsonPrimitive.doubleOrNull ?: 0.0, o["kind"]?.jsonPrimitive?.content ?: PLACE, o["addedAt"]?.jsonPrimitive?.longOrNull ?: 0L)
    private fun recToJson(r: Recent) = buildJsonObject { put("name", r.name); put("lat", r.lat); put("lng", r.lng); put("at", r.at) }
    private fun recFrom(o: JsonObject) = Recent(o["name"]!!.jsonPrimitive.content, o["lat"]!!.jsonPrimitive.doubleOrNull ?: 0.0, o["lng"]!!.jsonPrimitive.doubleOrNull ?: 0.0, o["at"]?.jsonPrimitive?.longOrNull ?: 0L)
    private lateinit var favFile: File
    private lateinit var recentFile: File
    private val _all = MutableStateFlow<List<Favorite>>(emptyList())
    val all: StateFlow<List<Favorite>> = _all
    private val _recent = MutableStateFlow<List<Recent>>(emptyList())
    val recent: StateFlow<List<Recent>> = _recent

    fun init(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        favFile = File(dir, "favorites.json"); recentFile = File(dir, "recent.json")
        reload()
    }

    // Re-read from disk (the API/MCP may have written the file).
    fun reload() {
        _all.value = runCatching { if (favFile.exists()) Json.parseToJsonElement(favFile.readText()).jsonArray.map { favFrom(it.jsonObject) } else emptyList() }.getOrElse { Log.w(TAG, "favorites: $it"); emptyList() }
        _recent.value = runCatching { if (recentFile.exists()) Json.parseToJsonElement(recentFile.readText()).jsonArray.map { recFrom(it.jsonObject) } else emptyList() }.getOrElse { emptyList() }
    }

    fun home() = _all.value.firstOrNull { it.kind == HOME }
    fun work() = _all.value.firstOrNull { it.kind == WORK }
    fun places() = _all.value.filter { it.kind == PLACE }.sortedByDescending { it.addedAt }

    // Home/Work are singletons: saving replaces the existing one.
    @Synchronized fun save(name: String, c: GeographicCoordinate, kind: String = PLACE): Favorite {
        require(c.lat.isFinite() && c.lng.isFinite() && c.lat in -90.0..90.0 && c.lng in -180.0..180.0)
        require(kind in setOf(HOME, WORK, PLACE))
        val f = Favorite(id = (if (kind == PLACE) "p-" + System.currentTimeMillis().toString(36) else kind), name = name, lat = c.lat, lng = c.lng, kind = kind)
        _all.value = _all.value.filter { it.id != f.id && !(kind != PLACE && it.kind == kind) } + f
        persist()
        if (kind != PLACE) com.morton.trucknav.settings.Settings.set("place." + kind, favToJson(f).toString())
        NavLog.log("favorites", "saved $kind \"$name\" ${c.lat},${c.lng}")
        return f
    }
    fun remove(id: String) { if (id == HOME || id == WORK) com.morton.trucknav.settings.Settings.set("place." + id, null); _all.value = _all.value.filter { it.id != id }; persist(); NavLog.log("favorites", "removed $id") }

    fun noteDestination(name: String?, c: GeographicCoordinate) {
        val label = name?.takeIf { it.isNotBlank() } ?: "%.4f, %.4f".format(c.lat, c.lng)
        _recent.value = (listOf(Recent(label, c.lat, c.lng, System.currentTimeMillis())) + _recent.value.filter { it.name != label }).take(10)
        persistRecent()
    }
    fun forgetRecent(name: String) { _recent.value = _recent.value.filter { it.name != name }; persistRecent(); NavLog.log("favorites", "forgot recent \"$name\"") }
    private fun persistRecent() { runCatching { recentFile.writeText(json.encodeToString(JsonArray.serializer(), buildJsonArray { _recent.value.forEach { add(recToJson(it)) } })) } }

    private fun persist() {
        runCatching { favFile.writeText(json.encodeToString(JsonArray.serializer(), buildJsonArray { _all.value.forEach { add(favToJson(it)) } })) }
            .onSuccess { Log.i(TAG, "persisted ${_all.value.size} to ${favFile.absolutePath} (${favFile.length()} bytes)") }
            .onFailure { Log.w(TAG, "persist to ${favFile.absolutePath}: $it") }
    }
}
