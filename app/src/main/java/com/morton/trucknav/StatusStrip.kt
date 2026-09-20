package com.morton.trucknav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Our replacement for the Android status bar, which is hidden. 28 dp, always
// on top: clock, Wi-Fi, tailnet, GPS fix age, tablet battery. Every field is
// a fact the driver may need at a glance; nothing here is interactive.
@Composable
fun StatusStrip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var clock by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm", Locale.US)
        while (true) { now = System.currentTimeMillis(); clock = fmt.format(Date(now)); delay(1_000) }
    }

    var ssid by remember { mutableStateOf<String?>(null) }
    var vpn by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifiCb = object : ConnectivityManager.NetworkCallback(if (Build.VERSION.SDK_INT >= 31) FLAG_INCLUDE_LOCATION_INFO else 0) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null
                val s = info?.ssid?.trim('"')
                ssid = if (s == null || s == UNKNOWN_SSID) "Wi-Fi" else s
            }
            override fun onLost(network: Network) { ssid = null }
        }
        val vpnCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { vpn = true }
            override fun onLost(network: Network) { vpn = false }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb)
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpnCb)
        onDispose { cm.unregisterNetworkCallback(wifiCb); cm.unregisterNetworkCallback(vpnCb) }
    }

    var fixAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val l = LocationListener { loc: Location -> fixAt = loc.time }
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { fixAt = it.time }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, l)
        } catch (_: SecurityException) {}
        onDispose { lm.removeUpdates(l) }
    }

    var battery by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                battery = if (level < 0) -1 else level * 100 / scale
                charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { ctx.unregisterReceiver(r) }
    }

    val rejecting by com.morton.trucknav.nav.SaneLocationProvider.rejecting.collectAsState()
    val fixAge = if (fixAt == 0L) null else (now - fixAt) / 1000
    val gpsText = when { rejecting -> "GPS weak"; fixAge == null -> "no fix"; fixAge < 10 -> "GPS"; fixAge < 3600 -> "${fixAge}s ago"; else -> "stale" }
    val gpsOk = !rejecting && fixAge != null && fixAge < 10

    Row(
        modifier.fillMaxWidth().height(28.dp).background(Color(0xFF07090c)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(clock, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Field(if (ssid != null) Icons.Filled.Wifi else Icons.Filled.WifiOff, ssid ?: "no Wi-Fi", ssid != null)
        Field(Icons.Filled.Shield, if (vpn) "tailnet" else "no tailnet", vpn)
        Field(if (gpsOk) Icons.Filled.GpsFixed else Icons.Filled.GpsOff, gpsText, gpsOk)
        Field(if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull, if (battery < 0) "--" else "$battery%", battery > 20 || charging)
    }
}

private const val UNKNOWN_SSID = "<unknown ssid>"

@Composable
private fun Field(icon: ImageVector, text: String, ok: Boolean) {
    val c = if (ok) Color(0xFFcfd8e3) else Color(0xFFf0a030)
    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(16.dp))
        Text(text, color = c, fontSize = 13.sp, maxLines = 1)
    }
}
