package com.morton.trucknav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import android.content.res.Configuration
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationActivity
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withSpeedLimitStyle
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SignageStyle
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import com.morton.trucknav.ui.DestinationSelectionBottomSheet
import com.morton.trucknav.ui.DestinationSelectionCameraEffect
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import uniffi.ferrostar.GeographicCoordinate

private val PORTRAIT_BOTTOM_CHROME = 175.dp

@Composable
fun DemoNavigationScene(viewModel: DemoNavigationViewModel = AppModule.viewModel) {
  // Keeps the screen on at consistent brightness while this Composable is in the view hierarchy.
  KeepScreenOnDisposableEffect()

  val context = LocalContext.current

  // Get location permissions.
  // NOTE: This is NOT a robust suggestion for how to get permissions in a production app.
  // This is simply minimal sample code in as few lines as possible.
  val allPermissions =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        )
      } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      }

  val permissionsLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          permissions ->
        when {
          permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
            viewModel.setLocationPermissions(true)
          }
          permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
            // TODO: Probably alert the user that this is unusable for navigation
          }
          // TODO: Foreground service permissions; we should block access until approved on API 34+
          else -> {
            // TODO
          }
        }
      }

  LaunchedEffect(Unit) {
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      viewModel.setLocationPermissions(true)
    } else {
      permissionsLauncher.launch(allPermissions)
    }
  }
  val sceneState by viewModel.sceneState.collectAsState()
  val routeSource by viewModel.routeSource.collectAsState()
  val routeError by viewModel.routeError.collectAsState()
  val mapStyle by MapStyles.current.collectAsState()

  // Ferrostar's default navigation padding is derived from the *screen* size,
  // which puts the puck at the right edge (or under the side panel) when the
  // map is only part of the screen. Derive it from the map's own measured size
  // instead, so the puck sits in the lower-right quadrant of the visible map
  // whatever the rail / panel / orientation state is.
  var mapSize by remember { mutableStateOf(IntSize.Zero) }
  val density = LocalDensity.current
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val cameraOptions = remember(mapSize, landscape) {
    val w = with(density) { mapSize.width.toDp() }
    val h = with(density) { mapSize.height.toDp() }
    val a = NavigationActivity.Automotive
    NavigationCameraOptions(
        browsingZoom = a.zoom,
        navigationZoom = a.zoom,
        navigationTilt = a.tilt,
        browsingPadding = PaddingValues(0.dp),
        // Landscape: instruction card top-left, so the puck goes to the lower-right
        // quadrant. Portrait: Ferrostar stacks the road-name pill and the arrival bar
        // across the bottom ~175 dp of the map, so the target sits just above them
        // (MapLibre centres the target in the padded viewport: top = 2*targetY - h).
        navigationPadding =
            if (landscape) PaddingValues(start = w * 0.5f, top = h * 0.5f)
            else PaddingValues(top = (h - PORTRAIT_BOTTOM_CHROME * 2).coerceAtLeast(0.dp)),
    )
  }
  var destinationPreviewTopPaddingPx by remember { mutableStateOf(0) }
  val navigationMapState = rememberNavigationMapState(navigationCameraOptions = cameraOptions)

  // New search results: fit them all (plus the user) under the results card.
  val resultsKey = sceneState.searchResults.map { it.coordinate }.toString()
  LaunchedEffect(resultsKey) {
    val hits = sceneState.searchResults
    if (hits.isEmpty()) return@LaunchedEffect
    val pts = hits.map { it.coordinate } + listOfNotNull(viewModel.navigationUiState.value.location?.coordinates)
    val west = pts.minOf { it.lng }; val east = pts.maxOf { it.lng }; val south = pts.minOf { it.lat }; val north = pts.maxOf { it.lat }
    navigationMapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FREE
    // The results card can cover most of the map; padding must leave a real viewport.
    val mapH = with(density) { mapSize.height.toDp() }; val mapW = with(density) { mapSize.width.toDp() }
    val cardBottom = with(density) { destinationPreviewTopPaddingPx.toDp() } + 16.dp
    val pad = if (landscape) PaddingValues(start = mapW * 0.6f, top = 24.dp, end = 32.dp, bottom = 32.dp)   // card is on the left
              else PaddingValues(start = 32.dp, top = minOf(cardBottom, mapH * 0.45f), end = 32.dp, bottom = 32.dp)
    navigationMapState.cameraState.animateTo(
        boundingBox = org.maplibre.spatialk.geojson.BoundingBox(west = west, south = south, east = east, north = north),
        padding = pad,
        duration = kotlin.time.Duration.parse("600ms"),
    )
  }

  // Ferrostar's landscape overlay writes map insets computed from the *screen*
  // width (start = screenWidth/2 + 16 dp). With the side panel open our map is
  // 60 % of the screen, so the route overview was being fitted into the
  // right-hand sliver. This state clamps whatever the overlay writes to the
  // map's own size; the overview button then fits the whole route between
  // the turn card and the arrival bar.
  val mapViewInsets = remember { ClampedInsets() }
  mapViewInsets.limitStart = with(density) { (mapSize.width.toDp() / 2) + 16.dp }

  // Route overview, done by hand. Ferrostar asks MapLibre to fit the route's
  // bbox with its insets, but the tracking camera's persistent padding leaks
  // into that fit and the result is zoomed ~2 levels too deep (measured on the
  // emulator: 11 mi route, east end off screen even with zero insets). So when
  // the mode flips to OVERVIEW we compute the camera ourselves: zoom that fits
  // the route + puck into the viewport left free by the turn card / arrival bar.
  LaunchedEffect(navigationMapState.cameraMode) {
    if (navigationMapState.cameraMode != com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.OVERVIEW) return@LaunchedEffect
    kotlinx.coroutines.delay(60)   // let Ferrostar's own animation start, then supersede it
    val st = viewModel.navigationUiState.value
    val pts = (st.routeGeometry ?: emptyList()) + listOfNotNull(st.location?.coordinates)
    if (pts.size < 2 || mapSize.width == 0) return@LaunchedEffect
    val mapW = with(density) { mapSize.width.toDp() }; val mapH = with(density) { mapSize.height.toDp() }
    val pad = if (landscape) PaddingValues(start = mapW * 0.5f + 16.dp, top = 24.dp, end = 32.dp, bottom = 32.dp)
              else PaddingValues(start = 24.dp, top = 150.dp, end = 24.dp, bottom = 200.dp)
    val cam = fitCamera(pts, mapW, mapH, pad)
    navigationMapState.cameraState.animateTo(cam, duration = kotlin.time.Duration.parse("600ms"))
    com.morton.trucknav.nav.NavLog.log("overview", "fit ${pts.size} pts zoom=${"%.2f".format(cam.zoom)} pad=$pad")
  }

  // Preview: fit the chosen candidate and the puck under the sheet.
  LaunchedEffect(sceneState.preview, destinationPreviewTopPaddingPx) {
    sceneState.preview.getOrNull(sceneState.previewSelected) ?: return@LaunchedEffect
    if (mapSize.width == 0) return@LaunchedEffect
    // Every candidate must be on screen: the driver picks by the letters on the map.
    val pts = sceneState.preview.flatMap { it.route.geometry } + listOfNotNull(viewModel.navigationUiState.value.location?.coordinates)
    val mapW = with(density) { mapSize.width.toDp() }; val mapH = with(density) { mapSize.height.toDp() }
    val sheet = with(density) { sceneState.destinationSheetHeightPx.toDp() }
    val topPad = maxOf(100.dp, with(density) { destinationPreviewTopPaddingPx.toDp() } + 16.dp)   // clear the search box + tiles
    val pad = if (landscape) PaddingValues(start = 24.dp, top = topPad, end = mapW * 0.46f + 16.dp, bottom = 24.dp)
              else PaddingValues(start = 24.dp, top = topPad, end = 24.dp, bottom = minOf(sheet + 16.dp, mapH * 0.6f))
    navigationMapState.cameraMode = com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode.FREE
    navigationMapState.cameraState.animateTo(fitCamera(pts, mapW, mapH, pad), duration = kotlin.time.Duration.parse("600ms"))
  }

  // Rotation recreates the activity and the camera comes back in browsing mode
  // (top-down, centred) even though navigation is still running. Put it back in
  // the navigating camera whenever the orientation changes mid-route.
  val uiState by viewModel.navigationUiState.collectAsState()
  // Search-result fits leave the camera free; after a stop is added (or the add is cancelled) go back to following.
  LaunchedEffect(sceneState.recenter) { if (sceneState.recenter > 0) navigationMapState.recenter(isNavigating = uiState.isNavigating()) }
  LaunchedEffect(landscape) {
    if (uiState.isNavigating()) {
      Log.i("DemoNavigationScene", "orientation changed while navigating: cameraMode=${navigationMapState.cameraMode}, recentering")
      navigationMapState.recenter(isNavigating = true)
    }
  }
  DestinationSelectionCameraEffect(
      selectedDestination = sceneState.selectedDestination,
      destinationSheetHeightPx = sceneState.destinationSheetHeightPx,
      topOverlayBottomPx = destinationPreviewTopPaddingPx,
      navigationMapState = navigationMapState,
  )

  DynamicallyOrientingNavigationView(
      modifier = Modifier.fillMaxSize().onSizeChanged { mapSize = it },
      baseStyle = BaseStyle.Uri(MapStyles.url(mapStyle)),
      navigationMapState = navigationMapState,
      navigationCameraOptions = cameraOptions,
      showDefaultPuck = false,   // the 4Runner is the puck; see VehiclePuck.kt
      mapViewInsets = mapViewInsets,
      viewModel = viewModel,
      config = VisualNavigationViewConfig.Default().withSpeedLimitStyle(SignageStyle.MUTCD),
      views =
          NavigationViewComponentBuilder.Default()
              .withInstructionsView { modifier, state ->
                com.morton.trucknav.routingui.RoutingInstructions(modifier, state, routeSource)
              }
              .withCustomOverlayView(
                  customOverlayView = { modifier ->
                    NotNavigatingOverlay(
                        modifier = modifier,
                        viewModel = viewModel,
                        navigationMapState = navigationMapState,
                        onTopOverlayBottomChanged = { destinationPreviewTopPaddingPx = it },
                    )
                  },
              ),
      onTapExit = { viewModel.stopNavigation() },
      onMapLongClick = { position, screenPosition ->
        Log.d(
            "DemoNavigationScene",
            "Long press at lat=${position.lat}, lng=${position.lng}, screen=$screenPosition",
        )
        viewModel.selectDestination(position)
        NavigationMapClickResult.Consume
      },
      mapOptions =
          MapOptions(
              ornamentOptions =
                  OrnamentOptions(
                      isCompassEnabled = false,
                      isScaleBarEnabled = false,
                  ),
          ),
  ) { ui ->
    com.morton.trucknav.traffic.TrafficLayer()
    DemoDroppedPinOverlay(sceneState.droppedPin)
    VehiclePuck(ui)
    com.morton.trucknav.nav.RoutePreviewOverlay(sceneState.preview, sceneState.previewSelected) { viewModel.selectPreview(it) }
    com.morton.trucknav.nav.SearchResultsOverlay(sceneState.searchResults) { hit ->
      viewModel.setSearchResults(emptyList())
      viewModel.selectDestination(
          location = android.location.Location("photon").apply { latitude = hit.coordinate.lat; longitude = hit.coordinate.lng },
          label = hit.label, origin = DestinationSelectionOrigin.SearchResult)
    }
  }

  routeError?.let { message ->
    androidx.compose.material3.AlertDialog(
        onDismissRequest = viewModel::dismissRouteError,
        title = { androidx.compose.material3.Text("Route unavailable") },
        text = { androidx.compose.material3.Text(message) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = viewModel::dismissRouteError) { androidx.compose.material3.Text("Dismiss") } },
    )
  }

  if (sceneState.isDestinationSheetVisible) {
    sceneState.selectedDestination?.let { destination ->
      DestinationSelectionBottomSheet(
          destination = destination,
          onClose = { viewModel.clearSelectedDestination() },
          onStartNavigation = { viewModel.startSelectedDestinationNavigation() },
          onSheetHeightChanged = viewModel::setDestinationSheetHeight,
      )
    }
  }
}

@Composable
@MaplibreComposable
private fun DemoDroppedPinOverlay(droppedPin: GeographicCoordinate?) {
  val pinFeatureCollection = droppedPinFeatureCollectionOrNull(droppedPin) ?: return
  val pointSource = rememberGeoJsonSource(GeoJsonData.Features(pinFeatureCollection))

  CircleLayer(
      id = "demo-dropped-pin",
      source = pointSource,
      color = const(Color.Red),
      radius = const(10.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
}

internal fun droppedPinFeatureCollectionOrNull(pin: GeographicCoordinate?) = pin?.let {
  droppedPinFeatureCollection(it)
}

internal fun droppedPinFeatureCollection(pin: GeographicCoordinate) =
    FeatureCollection(
        Feature(
            geometry = Point(longitude = pin.lng, latitude = pin.lat),
            properties = buildJsonObject {},
        ),
    )

// A MutableState that stores what Ferrostar writes but hands back a version
// whose start inset never exceeds half the map (+16 dp) — see above.
private class ClampedInsets : androidx.compose.runtime.MutableState<PaddingValues> {
  private val raw = androidx.compose.runtime.mutableStateOf(PaddingValues(0.dp))
  var limitStart: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity
  override var value: PaddingValues
    get() {
      val r = raw.value
      val ld = androidx.compose.ui.unit.LayoutDirection.Ltr
      val start = r.calculateStartPadding(ld)
      return if (start <= limitStart) r
      else PaddingValues(start = limitStart, top = r.calculateTopPadding(), end = r.calculateEndPadding(ld), bottom = r.calculateBottomPadding())
    }
    set(v) { raw.value = v }
  override fun component1(): PaddingValues = value
  override fun component2(): (PaddingValues) -> Unit = { value = it }
}

// Web-Mercator fit: the zoom at which the points' bbox fills the padded
// viewport (MapLibre: world = 512 dp * 2^zoom), target = bbox centre.
private fun fitCamera(pts: List<uniffi.ferrostar.GeographicCoordinate>, mapW: androidx.compose.ui.unit.Dp, mapH: androidx.compose.ui.unit.Dp, pad: PaddingValues): org.maplibre.compose.camera.CameraPosition {
  val ld = androidx.compose.ui.unit.LayoutDirection.Ltr
  val w = (mapW - pad.calculateStartPadding(ld) - pad.calculateEndPadding(ld)).value.toDouble().coerceAtLeast(40.0)
  val h = (mapH - pad.calculateTopPadding() - pad.calculateBottomPadding()).value.toDouble().coerceAtLeast(40.0)
  fun mercY(lat: Double): Double { val r = Math.toRadians(lat.coerceIn(-85.0, 85.0)); return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 }
  val west = pts.minOf { it.lng }; val east = pts.maxOf { it.lng }
  val yTop = pts.minOf { mercY(it.lat) }; val yBot = pts.maxOf { mercY(it.lat) }
  val dx = ((east - west) / 360.0).coerceAtLeast(1e-6); val dy = (yBot - yTop).coerceAtLeast(1e-6)
  val zoom = minOf(Math.log(w / (dx * 512)) / Math.log(2.0), Math.log(h / (dy * 512)) / Math.log(2.0)) - 0.15
  val cy = (yTop + yBot) / 2
  val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * cy))))
  return org.maplibre.compose.camera.CameraPosition(
      target = org.maplibre.spatialk.geojson.Position(longitude = (west + east) / 2, latitude = lat),
      zoom = zoom.coerceIn(3.0, 18.0), tilt = 0.0, bearing = 0.0, padding = pad)
}
