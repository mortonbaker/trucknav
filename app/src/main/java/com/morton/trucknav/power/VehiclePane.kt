package com.morton.trucknav.power

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The truck's switch panel: one big tile per relay the board reports, built
// from the board's own names so 4runner.yaml is the single source of truth.
// Car rules: >= 76 dp targets, one tap, state you can read at arm's length,
// greyed with the reason when the board is not on this network. Unassigned
// pins ("Relay N (Pin xx)") stay hidden until they are named.
@Composable
fun VehiclePane(relay: RelayClient, modifier: Modifier = Modifier) {
    val r by relay.state.collectAsState()
    val shown = r.switches.filter { !it.name.contains("(Pin") }
    Card(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Vehicle", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    when { r.busy -> "working..."; r.reachable -> "relay board · ${r.host}"; else -> r.error ?: "relay board not reachable" },
                    color = if (r.reachable || r.busy) Color(0xFF9aa4b2) else Color(0xFFff6b6b), fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (!r.reachable && !r.busy) {
                Text("Join the same Wi-Fi as the relay board (Everylink when the dish is up, or its own AP \"4Runner Relay Remote\").", color = Color(0xFF9aa4b2), fontSize = 15.sp)
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { sw -> Tile(sw, enabled = !r.busy) { relay.toggle(sw.id) } }
            }
        }
    }
}

private fun iconFor(sw: RelaySwitch): ImageVector = when {
    sw.id == RelayClient.STARLINK -> Icons.Filled.SatelliteAlt
    sw.name.contains("light", ignoreCase = true) -> Icons.Filled.Highlight
    sw.name.contains("power", ignoreCase = true) -> Icons.Filled.PowerSettingsNew
    else -> Icons.Filled.ToggleOn
}

private fun label(sw: RelaySwitch) = sw.name.replace(Regex("^Relay \\d+\\s*"), "").trim('(', ')', ' ').ifEmpty { sw.name }

@Composable
private fun Tile(sw: RelaySwitch, enabled: Boolean, onTap: () -> Unit) {
    Column(
        Modifier.height(112.dp).clip(RoundedCornerShape(18.dp))
            .background(if (sw.on) Color(0xFF1f5f8b) else Color(0xFF1a2028))
            .clickable(enabled = enabled, onClick = onTap).padding(14.dp)
            .semantics { contentDescription = "${label(sw)} ${if (sw.on) "ON" else "OFF"}" },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(sw), contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            Text(if (sw.on) "ON" else "OFF", color = if (sw.on) Color.White else Color(0xFF9aa4b2), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(label(sw), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}
