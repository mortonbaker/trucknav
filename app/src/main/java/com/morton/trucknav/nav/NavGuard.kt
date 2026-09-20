package com.morton.trucknav.nav

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Android Auto's rule, enforced by us because on this tablet we are the host:
// exactly one app navigates. Foreign navigators (OsmAnd, Google Maps, Waze,
// HERE, Sygic, TomTom, Magic Earth) all keep an ongoing notification while
// guiding; our NotificationListenerService sees those, and can press the
// notification's own Stop/Exit action — the same button the driver would tap.
// When TruckNav starts a route any foreign navigator is stopped outright;
// otherwise a banner offers a one-tap stop.
data class ForeignNav(val pkg: String, val label: String, val stop: Notification.Action?, val key: String)

object NavGuard {
    private const val TAG = "NavGuard"
    // package -> human label
    val NAVIGATORS = mapOf(
        "net.osmand.plus" to "OsmAnd", "net.osmand" to "OsmAnd", "net.osmand.srtmPlugin.paid" to "OsmAnd",
        "com.google.android.apps.maps" to "Google Maps", "com.waze" to "Waze",
        "com.here.app.maps" to "HERE WeGo", "com.sygic.aura" to "Sygic", "com.tomtom.gplay.navapp" to "TomTom",
        "com.generalmagic.magicearth" to "Magic Earth", "com.mapfactor.navigator" to "MapFactor",
    )
    private val STOP_WORDS = listOf("stop", "exit", "end", "cancel", "beenden", "detener", "quit")

    private val _foreign = MutableStateFlow<ForeignNav?>(null)
    val foreign: StateFlow<ForeignNav?> = _foreign
    private var actions: ((ForeignNav) -> Unit)? = null   // set by the listener service

    fun bind(stopper: (ForeignNav) -> Unit) { actions = stopper }
    fun unbind() { actions = null; _foreign.value = null }

    // Called by the listener with the full active set whenever anything changes.
    fun update(active: Array<StatusBarNotification>?) {
        val hit = active?.firstOrNull { sbn ->
            NAVIGATORS.containsKey(sbn.packageName) && sbn.isOngoing && looksLikeNavigation(sbn.notification)
        }
        val f = hit?.let { sbn ->
            val stop = sbn.notification.actions?.firstOrNull { a -> STOP_WORDS.any { w -> a.title?.toString()?.lowercase()?.contains(w) == true } }
            ForeignNav(sbn.packageName, NAVIGATORS[sbn.packageName] ?: sbn.packageName, stop, sbn.key)
        }
        if (f?.pkg != _foreign.value?.pkg) {
            NavLog.log("guard", if (f != null) "foreign navigator active: ${f.label} (${f.pkg}) stopAction=${f.stop?.title}" else "foreign navigator gone")
        }
        _foreign.value = f
    }

    private fun looksLikeNavigation(n: Notification): Boolean =
        n.category == Notification.CATEGORY_NAVIGATION || n.group?.contains("NAV", ignoreCase = true) == true ||
        (n.actions?.any { a -> STOP_WORDS.any { w -> a.title?.toString()?.lowercase()?.contains(w) == true } } == true)

    // Press the foreign app's own Stop. Returns false if it had none we recognise.
    fun stopForeign(reason: String): Boolean {
        val f = _foreign.value ?: return false
        NavLog.log("guard", "stopping ${f.label}: $reason")
        actions?.invoke(f) ?: return false
        return f.stop != null
    }
}
