package com.morton.trucknav.traffic

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Deliberately contains no URL, response body or exception text (all may contain a key). */
internal class TrafficHttpError(val status: Int) : IOException("HTTP $status")

internal suspend fun OkHttpClient.trafficBytes(request: Request): ByteArray = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(IOException("Traffic connection failed")) }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    if (!it.isSuccessful) throw TrafficHttpError(it.code)
                    val bytes = it.body.byteStream().use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer); if (n < 0) break
                            if (out.size() + n > 2 * 1024 * 1024) throw IOException("Traffic response too large")
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (cont.isActive) cont.resume(bytes)
                } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
            }
        }
    })
}
