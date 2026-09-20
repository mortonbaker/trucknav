package com.morton.trucknav.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.morton.trucknav.DestinationSelection
import com.morton.trucknav.R
import java.util.Locale
import uniffi.ferrostar.GeographicCoordinate

@Composable
fun DestinationSelectionBottomSheet(
    destination: DestinationSelection,
    onClose: () -> Unit,
    onStartNavigation: () -> Unit,
    onSheetHeightChanged: (Int) -> Unit,
) {
  val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  Box(
      modifier = Modifier.fillMaxSize().systemBarsPadding(),
      contentAlignment = if (landscape) Alignment.BottomEnd else Alignment.BottomCenter,
  ) {
    Surface(
        // Landscape: a panel on the right, so the routes being compared stay in view on the left.
        modifier = Modifier.fillMaxWidth(if (landscape) 0.46f else 1f).onSizeChanged { onSheetHeightChanged(if (landscape) 0 else it.height) },
        shape = if (landscape) RoundedCornerShape(topStart = 28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
      DestinationSelectionBottomSheetContent(
          destination = destination,
          onClose = onClose,
          onStartNavigation = onStartNavigation,
      )
    }
  }
}

@Composable
private fun DestinationSelectionBottomSheetContent(
    destination: DestinationSelection,
    onClose: () -> Unit,
    onStartNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier =
          modifier.verticalScroll(rememberScrollState()).padding(
              horizontal = 24.dp,
              vertical = 16.dp,
          )
  ) {
    Text(
        text =
            destination.label?.takeUnless { it.isBlank() }
                ?: stringResource(R.string.dropped_pin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text =
            stringResource(
                R.string.destination_coordinates,
                formatCoordinates(destination.coordinate),
            ),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Route candidates (time · distance · via): tap to choose, Start uses it as-is.
    val scene by com.morton.trucknav.AppModule.viewModel.sceneState.collectAsState()
    if (scene.preview.isEmpty()) {
      Text("Finding routes…", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      val cards: @Composable (Modifier) -> Unit = { m ->
        scene.preview.forEachIndexed { i, c ->
          val sel = i == scene.previewSelected
          androidx.compose.foundation.layout.Column(
              m.heightIn(min = 64.dp)
                  .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                  .background(if (sel) androidx.compose.ui.graphics.Color(0xFF1f5f8b) else androidx.compose.ui.graphics.Color(0xFF1a2028))
                  .clickable { com.morton.trucknav.AppModule.viewModel.selectPreview(i) }
                  .padding(12.dp)
                  .semantics { contentDescription = "Route " + com.morton.trucknav.nav.LETTERS[i] + ": " + c.label },
          ) {
            Text("${com.morton.trucknav.nav.LETTERS[i]}  ·  ${c.minutes} min", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
            Text("${"%.1f".format(c.miles)} mi" + if (c.via.isNotBlank()) " · via ${c.via}" else "", color = androidx.compose.ui.graphics.Color(0xFFaab4c0), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
          }
        }
      }
      val landscapeCards = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
      if (landscapeCards) androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { cards(Modifier.fillMaxWidth()) }
      else androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { cards(Modifier.weight(1f)) }
    }
    Button(
        onClick = onStartNavigation,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
        enabled = scene.preview.isNotEmpty(),
    ) {
      Text(stringResource(R.string.start_navigation))
    }
    // Save this place for one-tap use later.
    var saved by androidx.compose.runtime.remember(destination) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val label = destination.label?.takeUnless { it.isBlank() } ?: stringResource(R.string.dropped_pin_title)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
      for ((kind, title) in listOf(com.morton.trucknav.nav.Favorites.HOME to "Home", com.morton.trucknav.nav.Favorites.WORK to "Work", com.morton.trucknav.nav.Favorites.PLACE to "Save place")) {
        OutlinedButton(onClick = { com.morton.trucknav.nav.Favorites.save(label, destination.coordinate, kind); saved = kind }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
          Text(if (saved == kind) "Saved" else title, maxLines = 1)
        }
      }
    }
    OutlinedButton(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).heightIn(min = 48.dp),
    ) {
      Text(stringResource(R.string.close_destination_sheet))
    }
  }
}

private fun formatCoordinates(coordinate: GeographicCoordinate): String =
    String.format(Locale.getDefault(), "%.5f, %.5f", coordinate.lat, coordinate.lng)

@Preview(showBackground = true)
@Composable
private fun DestinationSelectionBottomSheetContentPreview() {
  MaterialTheme {
    DestinationSelectionBottomSheetContent(
        destination =
            DestinationSelection(
                coordinate =
                    GeographicCoordinate(
                        lat = 51.507778,
                        lng = -0.1275,
                    ),
                label = "Trafalgar Square",
            ),
        onClose = {},
        onStartNavigation = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun DestinationSelectionBottomSheetContentWithoutLabelPreview() {
  MaterialTheme {
    DestinationSelectionBottomSheetContent(
        destination =
            DestinationSelection(
                coordinate =
                    GeographicCoordinate(
                        lat = 34.5678,
                        lng = 45.6789,
                    ),
            ),
        onClose = {},
        onStartNavigation = {},
    )
  }
}
