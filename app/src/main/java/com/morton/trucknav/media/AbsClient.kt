package com.morton.trucknav.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.morton.trucknav.BuildConfig
import com.morton.trucknav.AppModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Audiobookshelf over its server API. The ABS Android app refuses media
// browsing from any package it does not know, so the cockpit lists books
// itself (covers, progress, library) and only asks the ABS app to *play*.
data class Book(val id: String, val title: String, val author: String?, val coverUrl: String, val progress: Float?, val durationSec: Double?)
data class AbsLibrary(val id: String, val name: String)
data class AbsTrack(val index: Int, val startOffset: Double, val duration: Double, val contentUrl: String)
data class AbsPlaySession(val sessionId: String, val title: String, val author: String?, val coverUrl: String, val currentTime: Double, val duration: Double, val tracks: List<AbsTrack>)

object AbsClient {
    private const val TAG = "AbsClient"
    private val base = BuildConfig.absUrl.trimEnd('/')
    @Volatile private var token: String? = null
    fun tokenOrEmpty(): String = token ?: ""
    suspend fun ensureToken(): String? = token ?: login()

    private suspend fun login(): String? = withContext(Dispatchers.IO) {
        try {
            val body = """{"username":"${BuildConfig.absUser}","password":"${BuildConfig.absPass}"}""".toRequestBody("application/json".toMediaType())
            val res = AppModule.okHttp.newCall(Request.Builder().url("$base/login").post(body).build()).execute().use { it.body?.string() } ?: return@withContext null
            Json.parseToJsonElement(res).jsonObject["user"]!!.jsonObject["token"]!!.jsonPrimitive.content.also { token = it }
        } catch (e: Exception) { Log.w(TAG, "login: $e"); null }
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val t = token ?: login() ?: return@withContext null
        try {
            val r = AppModule.okHttp.newCall(Request.Builder().url("$base$path").header("Authorization", "Bearer $t").build()).execute()
            if (r.code == 401) { token = null; return@withContext null }
            r.use { it.body?.string() }
        } catch (e: Exception) { Log.w(TAG, "get $path: $e"); null }
    }

    private fun cover(id: String) = "$base/api/items/$id/cover?token=$token&width=400"

    private fun book(o: kotlinx.serialization.json.JsonObject, progress: Float? = null): Book? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        val media = o["media"]?.jsonObject ?: return null
        val meta = media["metadata"]?.jsonObject
        val title = meta?.get("title")?.jsonPrimitive?.content ?: return null
        val author = meta["authorName"]?.jsonPrimitive?.content ?: meta["author"]?.jsonPrimitive?.content
        val dur = media["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()
        return Book(id, title, author, cover(id), progress, dur)
    }

    suspend fun continueListening(): List<Book> {
        val res = get("/api/me/items-in-progress") ?: return emptyList()
        return try {
            Json.parseToJsonElement(res).jsonObject["libraryItems"]!!.jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val p = o["userMediaProgress"]?.jsonObject?.get("progress")?.jsonPrimitive?.content?.toFloatOrNull()
                book(o, p)
            }
        } catch (e: Exception) { Log.w(TAG, "continue: $e"); emptyList() }
    }

    suspend fun libraries(): List<AbsLibrary> {
        val res = get("/api/libraries") ?: return emptyList()
        return try {
            Json.parseToJsonElement(res).jsonObject["libraries"]!!.jsonArray.map { val o = it.jsonObject; AbsLibrary(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content) }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun items(libraryId: String, limit: Int = 200): List<Book> {
        val res = get("/api/libraries/$libraryId/items?limit=$limit&sort=media.metadata.title") ?: return emptyList()
        return try {
            Json.parseToJsonElement(res).jsonObject["results"]!!.jsonArray.mapNotNull { book(it.jsonObject) }
        } catch (e: Exception) { Log.w(TAG, "items: $e"); emptyList() }
    }

    private suspend fun post(path: String, json: String): String? = withContext(Dispatchers.IO) {
        val t = token ?: login() ?: return@withContext null
        try {
            val r = AppModule.okHttp.newCall(Request.Builder().url("$base$path").header("Authorization", "Bearer $t").post(json.toRequestBody("application/json".toMediaType())).build()).execute()
            if (r.code == 401) { token = null; return@withContext null }
            r.use { if (it.isSuccessful) (it.body?.string() ?: "") else null }
        } catch (e: Exception) { Log.w(TAG, "post $path: $e"); null }
    }

    suspend fun openPlaySession(itemId: String): AbsPlaySession? {
        val body = """{"deviceInfo":{"clientName":"TruckNav","deviceId":"trucknav-tablet","manufacturer":"Samsung","model":"SM-T220"},"supportedMimeTypes":["audio/mpeg","audio/mp4","audio/aac","audio/flac","audio/ogg","audio/x-m4b"],"mediaPlayer":"exoplayer","forceDirectPlay":true}"""
        val res = post("/api/items/$itemId/play", body) ?: return null
        return try {
            val o = Json.parseToJsonElement(res).jsonObject
            val meta = o["mediaMetadata"]?.jsonObject
            AbsPlaySession(
                o["id"]!!.jsonPrimitive.content,
                meta?.get("title")?.jsonPrimitive?.content ?: o["displayTitle"]?.jsonPrimitive?.content ?: "Audiobook",
                meta?.get("authorName")?.jsonPrimitive?.content ?: o["displayAuthor"]?.jsonPrimitive?.content,
                cover(itemId),
                o["currentTime"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                o["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                o["audioTracks"]!!.jsonArray.map { t -> val x = t.jsonObject; AbsTrack(x["index"]!!.jsonPrimitive.content.toInt(), x["startOffset"]!!.jsonPrimitive.content.toDouble(), x["duration"]!!.jsonPrimitive.content.toDouble(), x["contentUrl"]!!.jsonPrimitive.content) },
            )
        } catch (e: Exception) { Log.w(TAG, "play session parse: $e"); null }
    }

    suspend fun sync(sessionId: String, currentTime: Double, timeListened: Double, duration: Double) {
        post("/api/session/$sessionId/sync", """{"currentTime":$currentTime,"timeListened":$timeListened,"duration":$duration}""")
    }

    suspend fun close(sessionId: String) { post("/api/session/$sessionId/close", "{}") }
}
