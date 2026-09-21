package com.morton.trucknav.nav

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morton.trucknav.DemoNavigationViewModel
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.morton.trucknav.traffic.Traffic
import com.morton.trucknav.traffic.TrafficPoint
import kotlinx.coroutines.delay
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.TripState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

data class TripStop(val name: String, val coordinate: GeographicCoordinate)
data class LegEstimate(val stop: TripStop, val meters: Double, val seconds: Double,
                       val geometry: List<GeographicCoordinate>)

internal fun distance(a: GeographicCoordinate, b: GeographicCoordinate): Double {
    val out = FloatArray(1)
    Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, out)
    return out[0].toDouble()
}

/** Ferrostar 0.56 progress totals target the final destination. Walk concatenated
 * steps to each snapped waypoint instead. First-step time is the remaining total
 * minus subsequent step durations (there is no durationToNextManeuver field). */
fun remainingLegs(state: NavigationUiState, stops: List<TripStop>): List<LegEstimate> {
    val trip = state.tripState as? TripState.Navigating ?: return emptyList()
    val steps = trip.remainingSteps
    if (steps.isEmpty() || stops.isEmpty()) return emptyList()
    var start = 0
    var meters = 0.0
    var seconds = 0.0
    return stops.mapIndexed { index, stop ->
        val waypoint = trip.remainingWaypoints.getOrNull(index)?.coordinate ?: stop.coordinate
        val end = if (index == stops.lastIndex) steps.lastIndex else
            (start..steps.lastIndex).firstOrNull { i ->
                steps[i].geometry.lastOrNull()?.let { distance(it, waypoint) < 50 } == true
            } ?: -1
        if (end < start) return emptyList() // never silently substitute whole-trip totals for a leg
        val geometry = mutableListOf<GeographicCoordinate>()
        for (i in start..end) {
            val step = steps[i]
            if (i == 0) {
                meters += trip.progress.distanceToNextManeuver.coerceAtLeast(0.0)
                seconds += (trip.progress.durationRemaining - steps.drop(1).sumOf { it.duration }).coerceAtLeast(0.0)
                geometry.add(trip.snappedUserLocation.coordinates)
                geometry.addAll(step.geometry.drop(trip.currentStepGeometryIndex?.toInt() ?: 0))
            } else {
                meters += step.distance
                seconds += step.duration
                geometry.addAll(step.geometry)
            }
        }
        start = end + 1
        // Final totals are authoritative and avoid cumulative floating point drift.
        if (index == stops.lastIndex) {
            meters = trip.progress.distanceRemaining
            seconds = trip.progress.durationRemaining
        }
        LegEstimate(stop, meters, seconds, geometry.distinct())
    }
}

private fun eta(seconds: Double): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + (seconds * 1000).toLong()))

@Composable
fun TripBar(modifier: Modifier, state: NavigationUiState, viewModel: DemoNavigationViewModel, onEnd: () -> Unit) {
    val scene by viewModel.sceneState.collectAsState()
    val legs = remember(state, scene.tripStops) { remainingLegs(state, scene.tripStops) }
    val next = legs.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val latest by rememberUpdatedState(next)
    // Route revision includes replacement even when the stop names remain unchanged.
    val routeKey = scene.tripRevision to scene.tripStops
    LaunchedEffect(routeKey) {
        try {
            while (true) {
                val points = latest?.geometry.orEmpty().map { TrafficPoint(it.lat, it.lng) }
                Traffic.setRemainingRoute(points)
                delay(60_000)
            }
        } finally { Traffic.setRemainingRoute(emptyList()) }
    }
    LaunchedEffect(next?.stop, next?.meters?.toInt(), scene.tripRevision) {
        next?.let {
            NavLog.log("trip-bar", "next=\"" + it.stop.name + "\" meters=" + it.meters +
                " seconds=" + it.seconds + " stops=" + (legs.size - 1) + " revision=" + scene.tripRevision)
        }
    }
    BackHandler(expanded) { expanded = false }
    Column(modifier.onSizeChanged { barSize = it }.clip(RoundedCornerShape(18.dp)).background(Color(0xFF10141A)).padding(12.dp)) {
        if (expanded) {
          Popup(alignment = Alignment.BottomStart, offset = IntOffset(0, -barSize.height),
              onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(with(density) { barSize.width.toDp() }).heightIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xFF10141A)).padding(12.dp).verticalScroll(rememberScrollState())
                .semantics { contentDescription = "Stop list" }) {
                legs.forEachIndexed { index, leg ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .semantics { contentDescription = "Stop row " + (index + 1) + " " + leg.stop.name + " ETA " + eta(leg.seconds) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (index == legs.lastIndex) "Finish" else (index + 1).toString(),
                            color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(leg.stop.name, color = Color.White, fontSize = 24.sp, maxLines = 1)
                            Text(eta(leg.seconds), color = Color(0xFFB8C4D1), fontSize = 24.sp)
                        }
                        if (index != legs.lastIndex) IconButton(
                            onClick = { viewModel.removeStop(leg.stop) },
                            enabled = !scene.stopsUpdating,
                            modifier = Modifier.size(56.dp).semantics { contentDescription = "Remove stop " + leg.stop.name }
                        ) { Icon(Icons.Default.Close, null, tint = Color.White) }
                    }
                }
            }
          }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 64.dp).clickable { expanded = !expanded }
                .semantics { contentDescription = "Trip stops" }) {
                Text("→ " + (next?.stop?.name ?: scene.tripStops.firstOrNull()?.name ?: "Destination"),
                    color = Color.White, fontSize = 28.sp, maxLines = 1)
                Text(next?.let { eta(it.seconds) + " · " + ceil(it.seconds / 60).toInt() +
                    " min · " + String.format(Locale.US, "%.1f", it.meters / 1609.344) + " mi" } ?: "Updating trip…",
                    color = Color.White, fontSize = 26.sp)
                if (legs.size > 1) Text("Final " + eta(legs.last().seconds),
                    color = Color(0xFFB8C4D1), fontSize = 24.sp)
                if (scene.stopsUpdating) Text("Updating stops…", color = Color.White, fontSize = 24.sp)
            }
            IconButton(onClick = onEnd, modifier = Modifier.size(64.dp)
                .semantics { contentDescription = "End Navigation" }) {
                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}
