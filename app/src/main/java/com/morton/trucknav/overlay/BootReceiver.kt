package com.morton.trucknav.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            OverlayService.start(ctx)
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 31 && e is android.app.ForegroundServiceStartNotAllowedException) {
                // Android can deliver a deferred boot broadcast as a stopped app returns.
                // CockpitScreen starts the overlay again once the activity is foreground.
                Log.i("BootReceiver", "Overlay deferred until cockpit is foreground")
            } else throw e
        }
    }
}
