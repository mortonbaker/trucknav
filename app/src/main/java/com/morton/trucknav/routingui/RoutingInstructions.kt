package com.morton.trucknav.routingui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morton.trucknav.routing.RouteSource
import com.stadiamaps.ferrostar.composeui.theme.DefaultNavigationUITheme
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.core.NavigationUiState

@Composable
fun RoutingInstructions(modifier: Modifier, state: NavigationUiState, source: RouteSource?) {
    state.visualInstruction?.let { instructions ->
        Column(modifier) {
            InstructionsView(
                instructions = instructions,
                theme = DefaultNavigationUITheme.instructionRowTheme,
                remainingSteps = state.remainingSteps,
                distanceToNextManeuver = state.progress?.distanceToNextManeuver,
            )
            source?.let {
                Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Text("Routing: ${it.label}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
