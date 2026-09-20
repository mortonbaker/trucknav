package com.morton.trucknav.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class RouteSource(val label: String) { SERVER("Server"), DEVICE("On-device") }
data class Routed<T>(val value: T, val source: RouteSource)

/** Cancellation must never start a new native calculation. Both paths include parsing. */
internal suspend fun <T> serverThenDevice(server: suspend () -> T, device: suspend () -> T): Routed<T> {
    try {
        return Routed(server(), RouteSource.SERVER)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        try {
            return Routed(device(), RouteSource.DEVICE)
        } catch (local: CancellationException) {
            throw local
        } catch (local: Exception) {
            local.addSuppressed(e)
            throw local
        }
    }
}
