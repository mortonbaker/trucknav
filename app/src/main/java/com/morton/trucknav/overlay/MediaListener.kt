package com.morton.trucknav.overlay

import android.service.notification.NotificationListenerService

// Exists only so Android lets us read active media sessions. Never reads
// notification content. Granted once over adb:
//   cmd notification allow_listener com.morton.trucknav/.overlay.MediaListener
class MediaListener : NotificationListenerService()
