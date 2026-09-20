package com.morton.trucknav

import android.location.Location
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.AnnotationPublisher
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import com.morton.trucknav.support.initialSimulatedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

data class DestinationSelection(
    val coordinate: GeographicCoordinate,
    val label: String? = null,
    val origin: DestinationSelectionOrigin = DestinationSelectionOrigin.MapLongPress,
)

enum class DestinationSelectionOrigin {
  MapLongPress,
  SearchResult,
}

data class DemoNavigationSceneState(
    val droppedPin: GeographicCoordinate? = null,
    val selectedDestination: DestinationSelection? = null,
    val isDestinationSheetVisible: Boolean = false,
    val destinationSheetHeightPx: Int = 0,
    val searchResults: List<PhotonHit> = emptyList(),
    val preview: List<com.morton.trucknav.nav.RouteCandidate> = emptyList(),
    val previewSelected: Int = 0,
    val arrived: Arrival? = null,
)

// Shown at the end of a trip; navigation ends ARRIVAL_LINGER_MS later or on Done.
data class Arrival(val name: String?, val atMs: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class DemoNavigationViewModel(
    // This is a simple example, but these would typically be dependency injected
    val ferrostarCore: FerrostarCore = AppModule.ferrostarCore,
    val locationProvider: NavigationLocationProvider = AppModule.locationProvider,
    annotationPublisher: AnnotationPublisher<*> = com.stadiamaps.ferrostar.core.annotation.DefaultAnnotationPublisher(
        json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true },
        serializer = com.stadiamaps.ferrostar.core.annotation.valhalla.ValhallaOSRMExtendedAnnotation.serializer(),
        speedLimitMapper = { it?.speedLimit },
        onError = { com.morton.trucknav.nav.NavLog.log("annotation", "decode failed: $it") },
    ),
) : DefaultNavigationViewModel(ferrostarCore, annotationPublisher) {

  private val _hasLocationPermission = MutableStateFlow(false)

  private val _simulated = MutableStateFlow(false)
  val simulated = _simulated.asStateFlow()

  private val locationStateFlow = MutableStateFlow<UserLocation?>(null)
  val location = locationStateFlow.asStateFlow()

  private val _sceneState = MutableStateFlow(DemoNavigationSceneState())
  val sceneState = _sceneState.asStateFlow()

  // Here's an example of injecting a custom location into the navigation UI state when isNavigating
  // is false.
  override val navigationUiState: StateFlow<NavigationUiState> =
      combine(super.navigationUiState, locationStateFlow) { a, b -> Pair(a, b) }
          .map { (uiState, location) ->
            if (uiState.isNavigating()) {
              uiState
            } else {
              uiState.copy(location = location)
            }
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(),
              initialValue = NavigationUiState.empty(),
          )

  // Arrival: Ferrostar flips the trip to Complete within 10 m of the end and
  // then just sits there (session alive, foreground service up). Show the card,
  // then end the trip like Android Auto does.
  private var destinationName: String? = null
  private var arrivalJob: kotlinx.coroutines.Job? = null
  private fun onArrived() {
    val name = destinationName
    com.morton.trucknav.nav.NavLog.log("arrival", "name=$name gen=$navGeneration; ending in ${ARRIVAL_LINGER_MS / 1000}s")
    _sceneState.value = _sceneState.value.copy(arrived = Arrival(name, System.currentTimeMillis()))
    AppModule.voiceGate.sayArrival(name)
    arrivalJob?.cancel()
    arrivalJob = viewModelScope.launch { kotlinx.coroutines.delay(ARRIVAL_LINGER_MS); dismissArrival() }
  }
  fun dismissArrival() {
    arrivalJob?.cancel(); arrivalJob = null
    if (_sceneState.value.arrived == null) return
    _sceneState.value = _sceneState.value.copy(arrived = null)
    if (ferrostarCore.state.value.tripState is uniffi.ferrostar.TripState.Complete) stopNavigation()
  }

  init {
    com.morton.trucknav.nav.NavLog.watch(navigationUiState)
    viewModelScope.launch {
      ferrostarCore.state.map { it.tripState is uniffi.ferrostar.TripState.Complete }.distinctUntilChanged().collect { if (it) onArrived() }
    }
    // While we navigate, a foreign navigator that appears is stopped at once.
    viewModelScope.launch {
      com.morton.trucknav.nav.NavGuard.foreign.collect { f ->
        if (f != null && navigationUiState.value.isNavigating()) com.morton.trucknav.nav.NavGuard.stopForeign("TruckNav is navigating")
      }
    }
    viewModelScope.launch {
      _hasLocationPermission
          .flatMapLatest { hasPermission ->
            if (!hasPermission) {
              flowOf(initialSimulatedLocation)
            } else {
              locationProvider.locationUpdates(5000L).map { it.toUserLocation() }
            }
          }
          .collect { locationStateFlow.emit(it) }
    }
  }

  fun setLocationPermissions(permitted: Boolean) {
    _hasLocationPermission.value = permitted
  }

  fun toggleSimulation() {
    _simulated.value = !_simulated.value
    if (!_simulated.value) {
      locationProvider.disableSimulation()
    }
  }

  fun enableAutoDriveSimulation() {
    _simulated.value = true
  }

  fun selectDestination(
      coordinate: GeographicCoordinate,
      label: String? = null,
      origin: DestinationSelectionOrigin = DestinationSelectionOrigin.MapLongPress,
  ) {
    _sceneState.value =
        _sceneState.value.copy(
            droppedPin = coordinate,
            selectedDestination =
                DestinationSelection(coordinate = coordinate, label = label, origin = origin),
            isDestinationSheetVisible = true,
            preview = emptyList(), previewSelected = 0,
        )
    previewJob?.cancel()
    previewJob = viewModelScope.launch {
      val from = location.value ?: return@launch
      val c = com.morton.trucknav.nav.RoutePreview.candidates(from, coordinate)
      if (_sceneState.value.selectedDestination?.coordinate == coordinate) _sceneState.value = _sceneState.value.copy(preview = c, previewSelected = 0)
    }
  }
  private var previewJob: kotlinx.coroutines.Job? = null
  fun selectPreview(i: Int) { _sceneState.value = _sceneState.value.copy(previewSelected = i) }

  fun selectDestination(
      location: Location,
      label: String? = null,
      origin: DestinationSelectionOrigin = DestinationSelectionOrigin.MapLongPress,
  ) {
    selectDestination(
        coordinate = GeographicCoordinate(location.latitude, location.longitude),
        label = label,
        origin = origin,
    )
  }

  fun setSearchResults(hits: List<PhotonHit>) { _sceneState.value = _sceneState.value.copy(searchResults = hits) }

  fun clearSelectedDestination() {
    _sceneState.value =
        _sceneState.value.copy(
            droppedPin = null,
            selectedDestination = null,
            preview = emptyList(), previewSelected = 0,
            isDestinationSheetVisible = false,
            destinationSheetHeightPx = 0,
        )
  }

  fun hideDestinationSheet() {
    _sceneState.value =
        _sceneState.value.copy(
            isDestinationSheetVisible = false,
            destinationSheetHeightPx = 0,
        )
  }

  fun setDestinationSheetHeight(heightPx: Int) {
    if (_sceneState.value.destinationSheetHeightPx == heightPx) {
      return
    }
    _sceneState.value = _sceneState.value.copy(destinationSheetHeightPx = heightPx)
  }

  fun startSelectedDestinationNavigation() {
    val st = sceneState.value
    val destination = st.selectedDestination ?: return
    val chosen = st.preview.getOrNull(st.previewSelected)
    clearSelectedDestination()
    if (chosen != null) startWithRoute(chosen.route, destination.coordinate, destination.label) else startNavigation(destination.coordinate, destination.label)
  }

  // Start (or replace) with a route the driver already saw and chose: no second fetch.
  private fun startWithRoute(route: uniffi.ferrostar.Route, destination: GeographicCoordinate, name: String?) {
    val gen = ++navGeneration
    com.morton.trucknav.nav.Favorites.noteDestination(name, destination)
    com.morton.trucknav.nav.NavLog.log("start", "gen=$gen preview route ${"%.1f".format(route.distance / 1609.344)}mi to=$destination name=$name")
    com.morton.trucknav.nav.NavLog.route("preview gen=$gen", route)
    if (simulated.value) locationProvider.enableSimulationOn(route)
    if (com.morton.trucknav.nav.NavGuard.foreign.value != null) com.morton.trucknav.nav.NavGuard.stopForeign("TruckNav is starting a route")
    destinationName = name; AppModule.voiceGate.lastClass = null
    com.morton.trucknav.nav.NavLock.sync { if (navigationUiState.value.isNavigating()) ferrostarCore.replaceRoute(route) else ferrostarCore.startNavigation(route) }
    acceptRouteSource(route)
  }

  override fun toggleMute() {
    val spokenInstructionObserver = ferrostarCore.spokenInstructionObserver
    if (spokenInstructionObserver == null) {
      Log.d("NavigationViewModel", "Spoken instruction observer is null, mute operation ignored.")
      return
    }
    spokenInstructionObserver.setMuted(!spokenInstructionObserver.isMuted)
    com.morton.trucknav.nav.NavLog.log("mute", "isMuted=${spokenInstructionObserver.isMuted}")
  }

  fun startNavigation(destination: Location, name: String?) {
    startNavigation(
        destination = GeographicCoordinate(destination.latitude, destination.longitude),
        name = name,
    )
  }

  // Bumped on every start/stop; a route fetch only applies if nobody stopped
  // (or restarted) navigation while it was in flight.
  private var navGeneration = 0
  private var routeJob: kotlinx.coroutines.Job? = null
  private val _routeError = MutableStateFlow<String?>(null)
  val routeError = _routeError.asStateFlow()
  private val _routeSource = MutableStateFlow<com.morton.trucknav.routing.RouteSource?>(null)
  val routeSource = _routeSource.asStateFlow()
  fun dismissRouteError() { _routeError.value = null }
  fun acceptRouteSource(route: uniffi.ferrostar.Route) {
    _routeSource.value = AppModule.routing.sourceOf(route)
  }

  fun startNavigation(destination: GeographicCoordinate, name: String? = null) {
    val gen = ++navGeneration
    routeJob?.cancel()
    _routeError.value = null
    com.morton.trucknav.nav.Favorites.noteDestination(name, destination)
    com.morton.trucknav.nav.NavLog.log("start", "gen=$gen to=$destination name=$name caller=${com.morton.trucknav.nav.NavLog.caller()}")
    // Apply navigation state on Main, so End cannot race between the generation check and start.
    routeJob = viewModelScope.launch {
      try {
        val lastLocation = location.value ?: throw com.morton.trucknav.routing.RoutingUnavailable("Waiting for a GPS location. Try again after a location fix.")
        val routes = ferrostarCore.getRoutes(lastLocation, listOf(Waypoint(coordinate = destination, kind = WaypointKind.BREAK)))
        val route = routes.firstOrNull() ?: throw com.morton.trucknav.routing.RoutingUnavailable("No route was found.")
        if (gen != navGeneration) return@launch
        com.morton.trucknav.nav.NavLog.route("fetched gen=$gen", route)
        if (simulated.value) locationProvider.enableSimulationOn(route)
        if (com.morton.trucknav.nav.NavGuard.foreign.value != null) com.morton.trucknav.nav.NavGuard.stopForeign("TruckNav is starting a route")
        destinationName = name; AppModule.voiceGate.lastClass = null
        com.morton.trucknav.nav.NavLock.sync { if (navigationUiState.value.isNavigating()) ferrostarCore.replaceRoute(route) else ferrostarCore.startNavigation(route) }
        acceptRouteSource(route)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        if (gen == navGeneration) {
          _routeError.value = (e as? com.morton.trucknav.routing.RoutingUnavailable)?.message
              ?: "Could not calculate a route. Check connectivity or the offline routing pack, then retry."
          com.morton.trucknav.nav.NavLog.log("route-error", "gen=$gen ${e.javaClass.simpleName}: ${e.message}")
        }
      }
    }
  }

  override fun stopNavigation() {
    navGeneration++
    routeJob?.cancel()
    _routeSource.value = null
    _routeError.value = null
    com.morton.trucknav.nav.NavLog.log("stop", "gen=$navGeneration caller=${com.morton.trucknav.nav.NavLog.caller()}")
    locationProvider.disableSimulation()
    com.morton.trucknav.nav.NavLock.sync { ferrostarCore.stopNavigation() }
  }

  companion object {
    const val TAG = "DemoNavigationViewModel"
    const val ARRIVAL_LINGER_MS = 10_000L
  }
}
