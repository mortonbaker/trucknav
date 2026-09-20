package com.morton.trucknav.power

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val Muted = Color(0xFF9aa4b2)
private val Good = Color(0xFF35c76d)
private val Warn = Color(0xFFe8a317)
private val Bad = Color(0xFFff6b6b)

private fun w(v: Double?) = v?.let { "${it.roundToInt()} W" } ?: "--"
private fun v(v: Double?) = v?.let { "%.2f V".format(it) } ?: "--"
private fun a(v: Double?) = v?.let { "%.1f A".format(it) } ?: "--"

// The always-on strip: one row under the map with the numbers that matter
// while driving. Never taller than a finger.
@Composable
fun PowerStrip(client: VenusClient, relay: RelayClient, modifier: Modifier = Modifier) {
    val s by client.state.collectAsState()
    val r by relay.state.collectAsState()
    val stale = !s.connected || System.currentTimeMillis() - s.updatedAt > 120_000
    Row(
        modifier.fillMaxWidth().height(56.dp).background(Color(0xFF10141a)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Cell("SOC", s.soc?.let { "${it.roundToInt()}%" } ?: "--", if (s.lowSoc) Bad else Color.White, big = true)
        Cell("Batt", "${v(s.voltage)}  ${a(s.current)}", if ((s.current ?: 0.0) > 0.2) Good else if ((s.current ?: 0.0) < -0.2) Warn else Color.White)
        Cell("Solar", w(s.pvPower), Color.White)
        Cell("Alt", w(s.alternatorPower), Color.White)
        Cell("Load", w(s.dcLoadPower ?: s.acLoadPower), Color.White)
        Cell(if ((s.netWatts ?: 0.0) >= 0) "Net in" else "Net out", w(s.netWatts?.let { abs(it) }), if ((s.netWatts ?: 0.0) >= 0) Good else Warn)
        Cell("Link", if (!s.connected) "offline" else if (stale) "stale" else s.chargerStateText, if (!s.connected || stale) Bad else Muted)
        // Starlink: tap to flip the relay. Amber while a switch/scan is in flight.
        Column(
            Modifier.clip(RoundedCornerShape(10.dp)).background(if (r.on(RelayClient.STARLINK) == true) Color(0xFF1f5f8b) else Color(0xFF1a2028))
                .clickable(enabled = !r.busy) { relay.toggle(RelayClient.STARLINK) }.padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics { contentDescription = "Starlink " + (if (r.on(RelayClient.STARLINK) == true) "ON" else if (r.on(RelayClient.STARLINK) == false) "OFF" else "unknown") },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Starlink", color = Muted, fontSize = 11.sp, lineHeight = 12.sp)
            Text(if (r.busy) "..." else if (r.on(RelayClient.STARLINK) == true) "ON" else if (r.on(RelayClient.STARLINK) == false) "OFF" else "--", color = if (r.busy) Warn else if (r.on(RelayClient.STARLINK) == null) Bad else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Cell(label: String, value: String, color: Color, big: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 11.sp, lineHeight = 12.sp)
        Text(value, color = color, fontSize = if (big) 22.sp else 16.sp, fontWeight = FontWeight.Bold, lineHeight = if (big) 24.sp else 18.sp, maxLines = 1)
    }
}

// The full pane: every number the Pi publishes, grouped. Big SOC up top.
@Composable
fun PowerPane(client: VenusClient, relay: RelayClient, modifier: Modifier = Modifier) {
    val s by client.state.collectAsState()
    val r by relay.state.collectAsState()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("State of charge", color = Muted, style = MaterialTheme.typography.labelLarge)
                    Text(s.soc?.let { "${it.roundToInt()}%" } ?: "--%", fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 68.sp, color = if (s.lowSoc) Bad else Color.Unspecified)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.batteryStateText, style = MaterialTheme.typography.titleMedium)
                    s.timeToGoSec?.let { Text("${(it / 3600).toInt()}h ${((it % 3600) / 60).toInt()}m to go", color = Muted) }
                    if (!s.connected) Text("Pi offline", color = Bad)
                }
            }
            Section("Switches")
            // Big, one-tap, readable at arm's length: the whole row toggles.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1a2028))
                    .clickable(enabled = !r.busy) { relay.toggle(RelayClient.STARLINK) }.padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Starlink switch" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SatelliteAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Starlink", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            when {
                                r.busy -> "working..."
                                r.on(RelayClient.STARLINK) == null -> r.error ?: "not reachable"
                                else -> "relay ${if (r.on(RelayClient.STARLINK) == true) "on" else "off"} · ${r.host}"
                            },
                            color = if (r.on(RelayClient.STARLINK) == null && !r.busy) Bad else Muted, fontSize = 13.sp,
                        )
                    }
                }
                Switch(checked = r.on(RelayClient.STARLINK) == true, onCheckedChange = { relay.set(RelayClient.STARLINK, it) }, enabled = !r.busy,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1f8bd1)))
            }
            Section("Battery")
            Grid(listOf("Voltage" to v(s.voltage), "Current" to a(s.current), "Power" to w(s.power), "Consumed" to (s.consumedAh?.let { "%.1f Ah".format(it) } ?: "--"), "Temperature" to (s.temperature?.let { "%.0f °C".format(it) } ?: "--"), "Low SOC alarm" to if (s.lowSoc) "YES" else "no"))
            Section("Sources")
            Grid(listOf("Solar" to w(s.pvPower), "PV voltage" to v(s.pvVoltage), "PV current" to a(s.pvCurrent), "Charger" to s.chargerStateText, "Solar today" to (s.pvYieldToday?.let { "%.2f kWh".format(it) } ?: "--"), "Alternator" to w(s.alternatorPower), "Shore/grid" to w(s.gridPower)))
            Section("Loads")
            Grid(listOf("DC loads" to w(s.dcLoadPower), "AC loads" to w(s.acLoadPower), "Inverter" to (s.inverterState?.let { inverterText(it) } ?: "--")))
        }
    }
}

private fun inverterText(v: Int) = when (v) { 0 -> "Off"; 1 -> "Low power"; 2 -> "Fault"; 3 -> "Bulk"; 4 -> "Absorption"; 5 -> "Float"; 8 -> "Passthru"; 9 -> "Inverting"; 252 -> "Ext. control"; else -> "State $v" }

@Composable
private fun Section(title: String) {
    Column { HorizontalDivider(); Text(title, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun Grid(items: List<Pair<String, String>>) {
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (k, vv) ->
                Column(Modifier.weight(1f)) {
                    Text(k, color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text(vv, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (row.size == 1) Column(Modifier.weight(1f)) {}
        }
    }
}
