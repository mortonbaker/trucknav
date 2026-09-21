package com.morton.trucknav.settings

import android.content.Context
import com.morton.trucknav.routing.HybridRouteProvider
import com.morton.trucknav.routing.RouteSource
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import okhttp3.OkHttpClient
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RuntimeRouting(private val context: Context, private val client: OkHttpClient) : CustomRouteProvider {
    private val lock = Mutex()
    private var endpoint: String? = null
    private var provider: HybridRouteProvider? = null
    private val sources = java.util.IdentityHashMap<Route, RouteSource>()
    @Synchronized fun sourceOf(route: Route): RouteSource? = sources[route]
    override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> = lock.withLock {
        val value = Settings.get("valhallaUrl").orEmpty()
        if (provider == null || value != endpoint) {
            provider?.close()
            endpoint = value
            // A blank server uses an immediately refused loopback endpoint: the
            // hybrid provider then invokes its on-device path without WAN traffic.
            provider = HybridRouteProvider(context, value.ifBlank { "http://127.0.0.1:1/route" }, client,
                log = { com.morton.trucknav.nav.NavLog.log("routing", it) })
        }
        val active = provider!!
        active.getRoutes(userLocation, waypoints).also { routes ->
            synchronized(this) {
                if (sources.size > 32) sources.clear()
                routes.forEach { route -> active.sourceOf(route)?.let { sources[route] = it } }
            }
        }
    }
}
