package com.morton.trucknav

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The five looks the map can take. Light/dark are fully offline (Protomaps on
// the tablet). Satellite/hybrid pull Esri imagery and terrain pulls the AWS
// Terrarium DEM over the tailnet/Starlink; MapLibre's ambient cache keeps what
// was recently seen, so a dropout greys out only unseen tiles.
enum class MapStyle(val file: String, val label: String, val icon: ImageVector, val offline: Boolean) {
    Light("style-light.json", "Light", Icons.Filled.LightMode, true),
    Dark("style-dark.json", "Dark", Icons.Filled.DarkMode, true),
    Satellite("style-satellite.json", "Satellite", Icons.Filled.Satellite, false),
    Hybrid("style-hybrid.json", "Hybrid", Icons.Filled.SatelliteAlt, false),
    Terrain("style-terrain.json", "Terrain", Icons.Filled.Terrain, false),
}

object MapStyles {
    private val _current = MutableStateFlow(MapStyle.Light)
    val current: StateFlow<MapStyle> = _current
    private val base = BuildConfig.styleUrl.substringBeforeLast('/')   // http://127.0.0.1:8781

    fun init(ctx: Context) {
        val saved = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getString("style", null)
        _current.value = MapStyle.entries.firstOrNull { it.name == saved } ?: MapStyle.Light
    }
    fun set(ctx: Context, s: MapStyle) {
        _current.value = s
        ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putString("style", s.name).apply()
    }
    fun url(s: MapStyle) = "$base/${s.file}"
}

// Big tiles, one tap, no scrolling: usable at arm's length while driving.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStyleSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val current by MapStyles.current.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF10141a)) {
        // The sheet is a separate window: without this the Android bars come back while it is up.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Map style", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MapStyle.entries.forEach { s ->
                val selected = s == current
                Column(
                    Modifier.weight(1f).height(112.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Color(0xFF1f5f8b) else Color(0xFF1a2028))
                        .clickable { MapStyles.set(ctx, s); onDismiss() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(s.icon, contentDescription = s.label, tint = Color.White, modifier = Modifier.size(44.dp))
                    Text(s.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    if (!s.offline) Text("online", color = Color(0xFF9aa5b1), fontSize = 12.sp)
                }
            }
        }
        com.morton.trucknav.traffic.TrafficLayerToggle()
        // Voice: which announcement classes are spoken. Big toggles, one tap.
        val off by com.morton.trucknav.nav.NavPrefs.disabledClasses.collectAsState()
        val autoNight by com.morton.trucknav.nav.NavPrefs.autoNight.collectAsState()
        Text("Voice", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.morton.trucknav.nav.VoiceGate.CLASSES.forEach { cls ->
                val on = cls !in off
                Column(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF1f5f8b) else Color(0xFF1a2028))
                        .clickable { com.morton.trucknav.nav.NavPrefs.setClass(cls, !on) }
                        .semantics { contentDescription = "Voice " + cls + " " + (if (on) "on" else "off") },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(cls.replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(if (on) "on" else "off", color = if (on) Color.White else Color(0xFF9aa5b1), fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto night map (dark after civil dusk)", color = Color.White, fontSize = 17.sp)
            androidx.compose.material3.Switch(checked = autoNight, onCheckedChange = { com.morton.trucknav.nav.NavPrefs.setAutoNight(it) },
                modifier = Modifier.semantics { contentDescription = "Auto night" })
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
      }
    }
}

val LayersIcon = Icons.Filled.Layers
