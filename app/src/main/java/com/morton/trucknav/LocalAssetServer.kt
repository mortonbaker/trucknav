package com.morton.trucknav

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// Serves the app's external files dir over plain HTTP on localhost with byte
// Range support. MapLibre reads the 3 GB PMTiles basemap, glyphs and sprites
// through this instead of file:// URLs, which the native PMTiles reader does
// not handle on Android. Loopback only; nothing is exposed off-device.
class LocalAssetServer(private val root: File, val port: Int = 8781) {
    private val pool = Executors.newFixedThreadPool(8)
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val s = ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                try { val c = s.accept(); pool.execute { try { handle(c) } catch (e: java.io.IOException) { /* client went away mid-transfer: normal for cancelled tiles */ } catch (e: Exception) { Log.w(TAG, "handle: $e") } } } catch (e: Exception) { if (!s.isClosed) Log.w(TAG, "accept: $e") }
            }
        }, "asset-server").apply { isDaemon = true }.start()
    }

    fun stop() { server?.close(); server = null }

    private fun handle(sock: Socket) = sock.use { c ->
        c.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val out = c.getOutputStream()
        val parts = request.split(' ')
        if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) return respond(out, 400, "text/plain", ByteArray(0))
        val path = java.net.URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8").trimStart('/')
        val f = File(root, path).canonicalFile
        if (!f.path.startsWith(root.canonicalPath) || !f.isFile) { Log.w(TAG, "404 /$path"); return respond(out, 404, "text/plain", ByteArray(0)) }
        val len = f.length()
        var start = 0L; var end = len - 1; var status = 200
        range?.removePrefix("bytes=")?.split('-')?.let { r ->
            val a = r.getOrNull(0)?.toLongOrNull(); val b = r.getOrNull(1)?.toLongOrNull()
            if (a != null) { start = a; end = b?.coerceAtMost(len - 1) ?: (len - 1) } else if (b != null) { start = (len - b).coerceAtLeast(0) }
            status = 206
        }
        if (start > end || start >= len) return respond(out, 416, "text/plain", ByteArray(0))
        val count = end - start + 1
        Log.i(TAG, "${parts[0]} /$path range=$range -> $status $start-$end/$len")
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(if (status == 206) "206 Partial Content" else "200 OK").append("\r\n")
            .append("Content-Type: ").append(mime(f.name)).append("\r\n")
            .append("Content-Length: ").append(count).append("\r\n")
            .append("Accept-Ranges: bytes\r\nCache-Control: no-store\r\n")
        if (status == 206) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (parts[0] == "HEAD") { out.flush(); return }
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n); left -= n
            }
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun mime(name: String) = when (name.substringAfterLast('.', "")) {
        "json" -> "application/json"
        "png" -> "image/png"
        "pbf" -> "application/x-protobuf"
        "pmtiles" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    companion object { private const val TAG = "LocalAssetServer" }
}
