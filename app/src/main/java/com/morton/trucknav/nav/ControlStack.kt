package com.morton.trucknav.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.morton.trucknav.LayersIcon
import com.morton.trucknav.R
import com.stadiamaps.ferrostar.composeui.views.components.controls.NavigationUIButton
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.models.CameraControlState
import com.stadiamaps.ferrostar.composeui.runtime.paddingForGridView
import com.stadiamaps.ferrostar.composeui.views.overlays.LandscapeNavigationOverlayView
import com.stadiamaps.ferrostar.composeui.views.overlays.PortraitNavigationOverlayView
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.NavigationViewModel
import com.stadiamaps.ferrostar.maplibreui.NavigationMapView
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickHandler
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable

/** Screen corners belong to actions; this overlay never changes the map's camera insets. */
@Composable
fun ControlStack(
    navigating: Boolean,
    muted: Boolean,
    map: NavigationMapState,
    onMute: () -> Unit,
    onLayers: () -> Unit,
    onAddStop: () -> Unit,
) {
    val size = DpSize(56.dp, 56.dp)
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        // A portrait media pane can leave less height than seven 56 dp buttons need.
        // Keep complete rows in a scrollable top group instead of overlapping the camera group.
        val density = LocalDensity.current
        val topHeight = with(density) {
            // Measure the sum of individually rounded children (56dp -> 74px at 210dpi),
            // not 260dp rounded once, which clips the fourth button by three pixels.
            val button = 56.dp.roundToPx()
            val gap = 12.dp.roundToPx()
            val available = maxHeight.roundToPx() - (3 * button + 2 * gap) - gap
            val rows = ((available + gap) / (button + gap)).coerceIn(1, if (navigating) 4 else 1)
            (rows * button + (rows - 1) * gap).toDp()
        }
        Column(
            Modifier.align(Alignment.TopEnd).heightIn(max = topHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (navigating) {
                NavigationUIButton(onClick = { map.cameraMode = NavigationCameraMode.OVERVIEW }, buttonSize = size) {
                    Icon(Icons.Default.Route, contentDescription = "Route Overview")
                }
                NavigationUIButton(onClick = onMute, buttonSize = size) {
                    Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (muted) "Unmute" else "Mute")
                }
            }
            NavigationUIButton(onClick = onLayers, buttonSize = size) {
                Icon(LayersIcon, contentDescription = "Map style")
            }
            if (navigating) {
                NavigationUIButton(onClick = onAddStop, buttonSize = size) {
                    Icon(Icons.Default.AddLocation, contentDescription = "Add stop")
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavigationUIButton(onClick = { map.recenter(isNavigating = navigating) }, buttonSize = size) {
                Icon(painterResource(R.drawable.my_location_24px), contentDescription = "Center on my location")
            }
            NavigationUIButton(onClick = { map.zoomIn() }, buttonSize = size) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in")
            }
            NavigationUIButton(onClick = { map.zoomOut() }, buttonSize = size) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
            }
        }
    }
}


// Ferrostar 0.56.0's DynamicallyOrientingNavigationView always creates a camera button:
// its cameraControlState() does not read showRecenter. Use the same public overlays with
// Hidden, keeping their information layout, inset measurements, route rendering and camera.
@Composable
fun CornerNavigationView(
    modifier: Modifier,
    baseStyle: BaseStyle,
    navigationMapState: NavigationMapState,
    navigationCameraOptions: NavigationCameraOptions,
    mapHeight: androidx.compose.ui.unit.Dp,
    showDefaultPuck: Boolean,
    mapViewInsets: MutableState<PaddingValues>,
    viewModel: NavigationViewModel,
    config: VisualNavigationViewConfig,
    views: NavigationViewComponentBuilder,
    onTapExit: () -> Unit,
    onMapLongClick: NavigationMapClickHandler,
    mapOptions: MapOptions,
    mapContent: @Composable @MaplibreComposable (NavigationUiState) -> Unit,
) {
    val ui by viewModel.navigationUiState.collectAsState()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val direction = LocalLayoutDirection.current
    val grid = paddingForGridView()
    var progressHeight by remember { mutableStateOf(0.dp) }

    // Same bottom-clearance calculation as Ferrostar 0.56.0 NavigationCamera.kt.
    // Crucially, this uses information insets, never the action stack's dimensions.
    val base = navigationCameraOptions.navigationPadding
    val screenHeight = configuration.screenHeightDp.dp
    // Baseline 123 puts the puck at y=.587 with the portrait Music pane open:
    // its fixed 175 dp bottom reserve consumes too much of that short map.
    // Keep S2's lower-third contract there (70%) without changing the scene's templates.
    val baseTarget = (mapHeight + base.calculateTopPadding() - base.calculateBottomPadding()) / 2
    val top = if (ui.isNavigating() && !landscape && mapHeight > 0.dp &&
        baseTarget < mapHeight * (2f / 3f))
        mapHeight * 0.4f + base.calculateBottomPadding()
        else base.calculateTopPadding()
    val extraBottom = if (ui.isNavigating()) maxOf(0.dp,
        ((screenHeight + top - base.calculateBottomPadding()) / 2 -
            (screenHeight - mapViewInsets.value.calculateBottomPadding() - 24.dp)) * 2) else 0.dp
    val camera = navigationCameraOptions.copy(navigationPadding = PaddingValues(
        start = base.calculateStartPadding(direction), top = top,
        end = base.calculateEndPadding(direction), bottom = base.calculateBottomPadding() + extraBottom))
    val ornaments = mapOptions.copy(ornamentOptions = mapOptions.ornamentOptions.copy(padding =
        PaddingValues(end = grid.calculateEndPadding(direction) + 16.dp, top = 8.dp,
            bottom = grid.calculateBottomPadding() + 8.dp +
                if (!landscape && ui.isNavigating()) progressHeight else 0.dp)))
    Box(modifier) {
        NavigationMapView(
            baseStyle = baseStyle, navigationMapState = navigationMapState, uiState = ui,
            navigationCameraOptions = camera, mapOptions = ornaments,
            showDefaultPuck = showDefaultPuck, onMapLongClick = onMapLongClick,
            content = mapContent,
        )
        if (ui.isNavigating()) {
            if (landscape) {
                LandscapeNavigationOverlayView(
                    modifier = Modifier.padding(grid), viewModel = viewModel,
                    cameraControlState = CameraControlState.Hidden, config = config,
                    views = views, mapViewInsets = mapViewInsets,
                    contentPadding = PaddingValues(0.dp), onTapExit = onTapExit,
                )
            } else {
                PortraitNavigationOverlayView(
                    modifier = Modifier.padding(grid), viewModel = viewModel,
                    cameraControlState = CameraControlState.Hidden, config = config,
                    views = views, mapViewInsets = mapViewInsets,
                    contentPadding = PaddingValues(0.dp), onTapExit = onTapExit,
                    onProgressViewHeightChange = { progressHeight = it },
                )
            }
        }
        views.getCustomOverlayView()?.invoke(this, Modifier.padding(grid))
    }
}
