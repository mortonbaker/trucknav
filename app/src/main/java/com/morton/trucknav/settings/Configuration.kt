package com.morton.trucknav.settings

import android.content.Context
import com.morton.trucknav.BuildConfig
import java.security.SecureRandom

object Configuration {
    fun init(context: Context) {
        Settings.init(context)
        val defaults = mapOf(
            "valhallaUrl" to BuildConfig.valhallaUrl, "photonUrl" to BuildConfig.photonUrl,
            "styleUrl" to BuildConfig.styleUrl, "absUrl" to BuildConfig.absUrl,
            "absUser" to BuildConfig.absUser, "absPass" to BuildConfig.absPass,
            "venusHost" to BuildConfig.venusHost, "venusPortalId" to BuildConfig.venusPortalId,
            "relayHost" to "", "units" to "imperial", "trafficProvider" to "off",
            "autoNight" to context.getSharedPreferences("nav", Context.MODE_PRIVATE).getBoolean("auto_night", true).toString(),
            "voiceDisabled" to (context.getSharedPreferences("nav", Context.MODE_PRIVATE).getStringSet("voice_off", emptySet()) ?: emptySet()).sorted().joinToString(",")
        )
        Settings.update(defaults.filterKeys { Settings.get(it) == null })
        if (Settings.get("apiToken").isNullOrBlank()) {
            Settings.set("apiToken", BuildConfig.apiToken.takeIf { it.isNotBlank() } ?: newToken())
        }
        InitialPosition.init(context)
        VehicleImage.init(context)
    }
    fun newToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun regenerateToken() = Settings.set("apiToken", newToken())
    fun value(key: String): String = Settings.get(key).orEmpty()
}
