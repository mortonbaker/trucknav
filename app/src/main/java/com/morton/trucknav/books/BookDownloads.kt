package com.morton.trucknav.books

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.morton.trucknav.AppModule
import com.morton.trucknav.BuildConfig
import com.morton.trucknav.media.AbsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.File

// Books on the tablet for the dead zones. A downloaded book is a directory under
// <externalFiles>/books/<id>/ holding one file per ABS audio track, the cover,
// and manifest.json (the track table the player needs for its timeline math).
// manifest.json is written last, so its presence means the download is complete;
// a *.part file means "resume from here". externalFilesDir rather than filesDir
// so adb can verify the files on a release build (same lifetime, same partition).
object BookDownloads {
    private const val TAG = "BookDownloads"

    // Hand-rolled JSON, like AbsClient: this project does not apply the serialization compiler plugin.
    data class Track(val index: Int, val startOffset: Double, val duration: Double, val file: String)
    data class Manifest(val id: String, val title: String, val author: String?, val duration: Double, val tracks: List<Track>) {
        fun toJson(): String = buildJsonObject {
            put("id", id); put("title", title); author?.let { put("author", it) }; put("duration", duration)
            put("tracks", buildJsonArray { tracks.forEach { t -> add(buildJsonObject { put("index", t.index); put("startOffset", t.startOffset); put("duration", t.duration); put("file", t.file) }) } })
        }.toString()
        companion object {
            fun parse(text: String): Manifest {
                val o = Json.parseToJsonElement(text).jsonObject
                return Manifest(
                    o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["author"]?.jsonPrimitive?.content,
                    o["duration"]!!.jsonPrimitive.content.toDouble(),
                    o["tracks"]!!.jsonArray.map { val t = it.jsonObject; Track(t["index"]!!.jsonPrimitive.content.toInt(), t["startOffset"]!!.jsonPrimitive.content.toDouble(), t["duration"]!!.jsonPrimitive.content.toDouble(), t["file"]!!.jsonPrimitive.content) },
                )
            }
        }
    }

    sealed class State {
        data class Downloading(val fraction: Float, val bytes: Long) : State()
        data class Done(val bytes: Long) : State()
        data class Failed(val why: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states

    fun root(ctx: Context): File = File(ctx.getExternalFilesDir(null), "books").apply { mkdirs() }
    fun dir(ctx: Context, id: String): File = File(root(ctx), id)
    fun coverFile(ctx: Context, id: String): File = File(dir(ctx, id), "cover.jpg")
    private fun manifestFile(ctx: Context, id: String) = File(dir(ctx, id), "manifest.json")

    // Complete download or null. Every track file must be present; a manifest
    // without its files (someone deleted them by hand) is treated as absent.
    fun manifest(ctx: Context, id: String): Manifest? {
        val f = manifestFile(ctx, id)
        if (!f.exists()) return null
        return try {
            val m = Manifest.parse(f.readText())
            if (m.tracks.all { File(dir(ctx, id), it.file).let { t -> t.exists() && t.length() > 0 } }) m else null
        } catch (e: Exception) { Log.w(TAG, "manifest $id: $e"); null }
    }
    fun isComplete(ctx: Context, id: String) = manifest(ctx, id) != null
    fun downloaded(ctx: Context): List<Manifest> = root(ctx).listFiles()?.mapNotNull { manifest(ctx, it.name) } ?: emptyList()

    // Called once at app start so the UI knows what is already on disk.
    fun scan(ctx: Context) {
        val done = downloaded(ctx).associate { it.id to (State.Done(dirSize(dir(ctx, it.id))) as State) }
        _states.update { it + done }
    }

    fun start(ctx: Context, id: String) {
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            if (isComplete(ctx, id)) { _states.update { it + (id to State.Done(dirSize(dir(ctx, id)))) }; return }
            _states.update { it + (id to State.Downloading(0f, 0L)) }
            jobs[id] = scope.launch { try { download(ctx.applicationContext, id) } catch (e: Exception) { Log.w(TAG, "download $id: $e"); _states.update { it + (id to State.Failed(e.message ?: "failed")) } } }
        }
    }

    fun delete(ctx: Context, id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        dir(ctx, id).deleteRecursively()
        _states.update { it - id }
        Log.i(TAG, "deleted $id")
    }

    private suspend fun download(ctx: Context, id: String) {
        // The play session is the one source that lists the tracks exactly as the
        // streaming player sees them (same contentUrls, same offsets). Closed right after.
        val s = AbsClient.openPlaySession(id) ?: throw IllegalStateException("no play session (offline?)")
        AbsClient.close(s.sessionId)
        val d = dir(ctx, id).apply { mkdirs() }
        val base = com.morton.trucknav.settings.Configuration.value("absUrl").trimEnd('/')
        val token = AbsClient.ensureToken() ?: throw IllegalStateException("no token")
        val tracks = s.tracks.map { t -> Track(t.index, t.startOffset, t.duration, "${t.index}${t.ext ?: ".audio"}") }
        val total = s.tracks.size
        var bytesDone = 0L
        try { fetch(base + "/api/items/$id/cover?token=$token&width=600", token, File(d, "cover.jpg")) { _, _ -> } } catch (e: Exception) { Log.w(TAG, "cover: $e") }
        s.tracks.forEachIndexed { i, t ->
            val out = File(d, tracks[i].file)
            if (out.exists() && out.length() > 0) { bytesDone += out.length(); return@forEachIndexed }
            val got = fetch(base + t.contentUrl, token, out) { have, len ->
                val frac = (i + if (len > 0) have.toFloat() / len else 0f) / total
                _states.update { it + (id to State.Downloading(frac, bytesDone + have)) }
            }
            bytesDone += got
            Log.i(TAG, "track ${t.index}/$total: $got bytes -> ${out.name}")
        }
        manifestFile(ctx, id).writeText(Manifest(id, s.title, s.author, s.duration, tracks).toJson())
        _states.update { it + (id to State.Done(dirSize(d))) }
        Log.i(TAG, "complete $id: ${s.title}, $total tracks, ${dirSize(d)} bytes")
    }

    // Resumable GET to <out>.part, renamed on completion. Returns the final size.
    private fun fetch(url: String, token: String, out: File, progress: (have: Long, len: Long) -> Unit): Long {
        val part = File(out.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (have > 0) req.header("Range", "bytes=$have-")
        AppModule.okHttp.newBuilder().callTimeout(java.time.Duration.ZERO).build().newCall(req.build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} for ${out.name}")
            val append = r.code == 206 && have > 0
            val body = r.body ?: throw IllegalStateException("empty body")
            val len = (if (append) have else 0L) + body.contentLength().coerceAtLeast(0L)
            var n = if (append) have else 0L
            java.io.FileOutputStream(part, append).use { fos ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024); var last = 0L
                    while (true) {
                        val k = ins.read(buf); if (k < 0) break
                        fos.write(buf, 0, k); n += k
                        if (n - last > 512 * 1024) { last = n; progress(n, len) }
                    }
                }
            }
            if (len > 0 && n != len) throw IllegalStateException("short read ${out.name}: $n of $len")
        }
        if (!part.renameTo(out)) throw IllegalStateException("rename ${part.name}")
        return out.length()
    }

    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun usedBytes(ctx: Context): Long = dirSize(root(ctx))
    fun freeBytes(ctx: Context): Long = StatFs(root(ctx).path).availableBytes
    fun storageLine(ctx: Context): String = "${gb(usedBytes(ctx))} used, ${gb(freeBytes(ctx))} free"
    fun gb(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "%d MB".format(b shr 20)
}
