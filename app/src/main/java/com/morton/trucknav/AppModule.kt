package com.morton.trucknav

import android.content.Context
import android.util.Log
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AlternativeRouteProcessor
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.CorrectiveAction
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.RouteDeviationHandler
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.location.toAndroidLocation
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.core.service.ForegroundServiceManager
import com.stadiamaps.ferrostar.core.withJsonOptions
import com.morton.trucknav.support.initialSimulatedLocation
import java.time.Duration
import okhttp3.OkHttpClient
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.WellKnownRouteProvider

// Wiring for the 4Runner navigator. Everything points at our own
// infrastructure: Valhalla on homebackup over the tailnet, an offline
// Protomaps basemap on the tablet, plain Android GPS (no Play Services).
object AppModule {
    private const val TAG = "AppModule"
    private lateinit var appContext: Context

    val mapStyleUrl: String get() = com.morton.trucknav.settings.Configuration.value("styleUrl")
    val valhallaUrl: String get() = com.morton.trucknav.settings.Configuration.value("valhallaUrl")
    val photonUrl: String get() = com.morton.trucknav.settings.Configuration.value("photonUrl")

    val api: com.morton.trucknav.nav.ApiServer by lazy { com.morton.trucknav.nav.ApiServer() }
    val assets: LocalAssetServer by lazy { LocalAssetServer(appContext.getExternalFilesDir(null)!!) }
    fun init(context: Context) {
        appContext = context.applicationContext
        com.morton.trucknav.settings.Configuration.init(appContext)
        com.morton.trucknav.nav.NavLog.init(appContext)
        com.morton.trucknav.nav.Favorites.init(appContext)
        com.morton.trucknav.nav.NavPrefs.gate = voiceGate
        com.morton.trucknav.nav.NavPrefs.init(appContext)
        com.morton.trucknav.nav.NightMode.start()
        api.start()
        MapStyles.init(appContext)
        assets.start()
        com.morton.trucknav.traffic.Traffic.init(appContext)
    }

    val locationProvider: NavigationLocationProvider by lazy {
        NavigationLocationProvider(
            liveProviding = com.morton.trucknav.nav.SaneLocationProvider(AndroidLocationProvider(appContext)),   // B10: drop implausible fixes
            simulatedProvider = SimulatedLocationProvider(
                warpFactor = 2u,
                initialLocation = initialSimulatedLocation.toAndroidLocation(),
            ),
        )
    }

    val okHttp: OkHttpClient by lazy { OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build() }
    private val httpClient: HttpClientProvider by lazy { okHttp.toOkHttpClientProvider() }

    private val foregroundServiceManager: ForegroundServiceManager by lazy {
        FerrostarForegroundServiceManager(appContext, DefaultForegroundNotificationBuilder(appContext))
    }

    val routing by lazy { com.morton.trucknav.settings.RuntimeRouting(appContext, okHttp) }

    val ferrostarCore: FerrostarCore by lazy {
        val core = FerrostarCore(
            routing,
            httpClient = httpClient,
            locationProvider = com.morton.trucknav.nav.LockedLocationProvider(locationProvider),   // B11
            foregroundServiceManager = foregroundServiceManager,
            navigationControllerConfig = NavigationControllerConfig.demoConfig(),
        )
        core.deviationHandler = RouteDeviationHandler { _, _, remainingWaypoints ->
            com.morton.trucknav.nav.NavLog.log("deviation-handler", "requesting new routes, waypoints=${remainingWaypoints.size}")
            CorrectiveAction.GetNewRoutes(remainingWaypoints)
        }
        // A reroute answer can land after the driver tapped End. Without this
        // check it would restart navigation to the old destination (B1).
        core.alternativeRouteProcessor = AlternativeRouteProcessor { it, routes ->
            val navigating = it.state.value.tripState is uniffi.ferrostar.TripState.Navigating
            com.morton.trucknav.nav.NavLog.log("reroute", "alternates=${routes.size} navigating=$navigating -> ${if (navigating && routes.isNotEmpty()) "replace" else "ignore"}")
            if (navigating && routes.isNotEmpty()) { com.morton.trucknav.nav.NavLog.route("reroute", routes.first()); com.morton.trucknav.nav.NavLock.sync { it.replaceRoute(routes.first()) }; viewModel.acceptRouteSource(routes.first()) }
        }
        core
    }

    val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }
    val voiceGate: com.morton.trucknav.nav.VoiceGate by lazy { com.morton.trucknav.nav.VoiceGate(ttsObserver) }
    val viewModel: DemoNavigationViewModel by lazy { DemoNavigationViewModel() }
}
