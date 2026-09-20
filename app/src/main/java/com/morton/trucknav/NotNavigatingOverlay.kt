package com.morton.trucknav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
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
) {
  val location by viewModel.location.collectAsState()
  val isSimulating by viewModel.simulated.collectAsState()
  val uiState by viewModel.navigationUiState.collectAsState()
  var showStyles by remember { mutableStateOf(false) }

  // Style switcher lives on the map in every state (centre-left is free in
  // both of Ferrostar's navigating layouts and in ours).
  val foreign by com.morton.trucknav.nav.NavGuard.foreign.collectAsState()
  InnerGridView(
      modifier = modifier.fillMaxSize().padding(bottom = 16.dp, top = 16.dp),
      centerStart = {
        NavigationUIButton(onClick = { showStyles = true }, buttonSize = DpSize(56.dp, 56.dp)) {
          Icon(LayersIcon, contentDescription = "Map style")
        }
      },
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

  if (!uiState.isNavigating()) {
    // Search sits in its own box so the results card can grow (the grid cell
    // below is a third of the map and was clipping every row after A).
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
          PhotonSearch(userLocation = location?.coordinates, onResults = { viewModel.setSearchResults(it) }) { hit ->
            viewModel.selectDestination(
                location = android.location.Location("photon").apply { latitude = hit.coordinate.lat; longitude = hit.coordinate.lng },
                label = hit.label,
                origin = DestinationSelectionOrigin.SearchResult,
            )
          }
          if (scene.searchResults.isEmpty()) {
            com.morton.trucknav.nav.QuickPlaces(userLocation = location?.coordinates, modifier = Modifier.widthIn(max = 560.dp)) { q ->
              com.morton.trucknav.nav.NavLog.log("quick", "go ${q.name}")
              viewModel.startNavigation(q.coordinate, q.name)
            }
          }
        }
      }
    }
    InnerGridView(
        modifier = modifier.fillMaxSize().padding(bottom = 16.dp, top = 16.dp),
        centerEnd = {
          NavigationUIButton(
              onClick = { navigationMapState.recenter(isNavigating = false) },
              buttonSize = DpSize(48.dp, 48.dp),
          ) {
            Icon(
                painter = painterResource(R.drawable.my_location_24px),
                contentDescription = stringResource(R.string.center_on_my_location),
            )
          }
        },
    )
  }
}
