package com.morton.trucknav.overlay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.morton.trucknav.nav.ForeignNav
import com.morton.trucknav.nav.NavGuard

// Two jobs. (1) Existing: its mere presence lets us read active media
// sessions. (2) NavGuard: watch for another navigator's ongoing navigation
// notification and, when asked, press that notification's own Stop action
// (or dismiss it) so only TruckNav ever guides. Granted once over adb:
//   cmd notification allow_listener com.morton.trucknav/.overlay.MediaListener
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NavGuard.bind { f -> stop(f) }
        NavGuard.update(safeActive())
    }
    override fun onListenerDisconnected() { NavGuard.unbind() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (NavGuard.NAVIGATORS.containsKey(sbn.packageName)) NavGuard.update(safeActive()) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (NavGuard.NAVIGATORS.containsKey(sbn.packageName)) NavGuard.update(safeActive()) }

    private fun safeActive(): Array<StatusBarNotification>? = try { activeNotifications } catch (e: Exception) { null }

    private fun stop(f: ForeignNav) {
        try {
            if (f.stop != null) f.stop.actionIntent.send() else cancelNotification(f.key)
        } catch (e: Exception) { Log.w("MediaListener", "stop ${f.pkg}: $e") }
    }
}
