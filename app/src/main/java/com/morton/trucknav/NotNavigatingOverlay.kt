package com.morton.trucknav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.stadiamaps.ferrostar.composeui.views.components.controls.NavigationUIButton
import com.stadiamaps.ferrostar.composeui.views.components.gridviews.InnerGridView
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotNavigatingOverlay(
    modifier: Modifier = Modifier,
    viewModel: DemoNavigationViewModel,
    navigationMapState: NavigationMapState,
    onTopOverlayBottomChanged: (Int) -> Unit = {},
    tilesMaxWidth: androidx.compose.ui.unit.Dp = 560.dp,
) {
  val location by viewModel.location.collectAsState()
  val isSimulating by viewModel.simulated.collectAsState()
  val uiState by viewModel.navigationUiState.collectAsState()
  var showStyles by remember { mutableStateOf(false) }

  val controlsScene by viewModel.sceneState.collectAsState()
  com.morton.trucknav.nav.ControlStack(
      navigating = uiState.isNavigating(),
      muted = uiState.isMuted == true,
      map = navigationMapState,
      onMute = { viewModel.toggleMute() },
      onLayers = { showStyles = true },
      onAddStop = { viewModel.setAddingStop(!controlsScene.addingStop) },
  )
  val foreign by com.morton.trucknav.nav.NavGuard.foreign.collectAsState()
  InnerGridView(
      modifier = modifier.fillMaxSize().padding(bottom = 16.dp, top = 16.dp),
      center = {
        // Only one navigator: if another app is guiding, say so and offer the stop.
        foreign?.let { f ->
          androidx.compose.foundation.layout.Row(
              Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color(0xFF8b1f1f)).padding(horizontal = 18.dp, vertical = 10.dp)
                  .semantics { contentDescription = "Foreign navigator banner" },
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("${f.label} is navigating", color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Button(onClick = { com.morton.trucknav.nav.NavGuard.stopForeign("driver tapped Stop") }, modifier = Modifier.padding(start = 14.dp)) {
              Text("Stop ${f.label}", fontSize = 18.sp)
            }
          }
        }
      },
  )
  if (showStyles) MapStyleSheet(onDismiss = { showStyles = false })

  val sceneNow by viewModel.sceneState.collectAsState()
  if (uiState.isNavigating() && sceneNow.addingStop) {
    // Add a stop: the same search box, below the instruction card.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Box(modifier.fillMaxSize().padding(top = if (landscape) 176.dp else 200.dp, start = 12.dp, end = 12.dp), contentAlignment = if (landscape) Alignment.TopStart else Alignment.TopCenter) {
      Column(Modifier.fillMaxWidth(if (landscape) 0.58f else 1f)) {
        PhotonSearch(userLocation = uiState.location?.coordinates ?: location?.coordinates, onResults = { viewModel.setSearchResults(it) }) { hit ->
          viewModel.setSearchResults(emptyList())
          viewModel.addStop(hit.coordinate, hit.label)
        }
        Text("Pick a stop on the way to your destination", color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp), style = MaterialTheme.typography.bodyLarge.copy(shadow = Shadow(androidx.compose.ui.graphics.Color.Black, blurRadius = 6f)))
      }
    }
  }
  if (!uiState.isNavigating()) {
    // Search sits in its own box so the results card can grow (the grid cell
    // below is a third of the map and was clipping every row after A).
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var panelTab by remember { mutableStateOf<com.morton.trucknav.nav.DestTab?>(null) }
    androidx.compose.runtime.LaunchedEffect(panelTab) { viewModel.setEditing("panel", panelTab != null) }
    var focusTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scene0 by viewModel.sceneState.collectAsState()
    Box(modifier.fillMaxSize().padding(top = 16.dp, start = 12.dp, end = 12.dp), contentAlignment = if (landscape) Alignment.TopStart else Alignment.TopCenter) {
      Box(
          modifier = Modifier.fillMaxWidth(if (landscape) 0.58f else 1f).onGloballyPositioned { coordinates ->
            onTopOverlayBottomChanged(coordinates.boundsInRoot().bottom.roundToInt())
          },
          contentAlignment = Alignment.TopCenter,
      ) {
        Column {
          val scene by viewModel.sceneState.collectAsState()
          scene.arrived?.let { a -> com.morton.trucknav.nav.ArrivalCard(a, onDone = { viewModel.dismissArrival() }); return@Column }
          PhotonSearch(
              userLocation = location?.coordinates, onResults = { viewModel.setSearchResults(it) }, focusTick = focusTick,
              // Tesla's default: an empty, focused search shows where you have been.
              onFocusChanged = { f -> viewModel.setEditing("search", f); if (f && panelTab == null) panelTab = com.morton.trucknav.nav.DestTab.Recents },
          ) { hit ->
            panelTab = null
            viewModel.selectDestination(
                location = android.location.Location("photon").apply { latitude = hit.coordinate.lat; longitude = hit.coordinate.lng },
                label = hit.label,
                origin = DestinationSelectionOrigin.SearchResult,
            )
          }
          if (scene.searchResults.isEmpty()) {
            com.morton.trucknav.nav.QuickPlaces(
                userLocation = location?.coordinates, modifier = Modifier.widthIn(max = tilesMaxWidth),
                onOpen = { panelTab = it },
                onSet = { kind -> panelTab = null; focusTick++; com.morton.trucknav.nav.NavLog.log("quick", "set $kind: focus search") },
            ) { name, c -> panelTab = null; com.morton.trucknav.nav.NavLog.log("quick", "preview $name"); viewModel.selectDestination(c, name, DestinationSelectionOrigin.SearchResult) }   // preview with alternates first (operator 2026-09-20)
          }
        }
      }
    }
    // The destinations panel: right side in landscape (the map keeps its left 55 %), bottom half in portrait.
    panelTab?.takeIf { scene0.searchResults.isEmpty() }?.let { tab ->
      Box(modifier.fillMaxSize().padding(if (landscape) androidx.compose.foundation.layout.PaddingValues(top = 16.dp, end = 12.dp, bottom = 16.dp) else androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)),
          contentAlignment = if (landscape) Alignment.TopEnd else Alignment.BottomCenter) {
        com.morton.trucknav.nav.DestinationsPanel(
            tab = tab, userLocation = location?.coordinates,
            modifier = if (landscape) Modifier.fillMaxWidth(0.42f).fillMaxHeight() else Modifier.fillMaxWidth().fillMaxHeight(0.55f),
            onTab = { panelTab = it }, onClose = { panelTab = null },
        ) { name, c -> panelTab = null; com.morton.trucknav.nav.NavLog.log("quick", "go $name (panel)"); viewModel.startNavigation(c, name) }
      }
    }
  }
}
