package com.morton.trucknav.settings

import android.content.Context
import android.location.Location
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import uniffi.ferrostar.GeographicCoordinate

/** PMTiles v3 centre from its header, never a baked-in personal location. */
object InitialPosition {
    private var prefs: android.content.SharedPreferences? = null
    var coordinate = GeographicCoordinate(0.0, 0.0)
        private set
    private var writtenAt = 0L
    fun init(context: Context) {
        prefs = context.getSharedPreferences("last_fix", Context.MODE_PRIVATE)
        val lat = prefs?.getString("lat", null)?.toDoubleOrNull()
        val lng = prefs?.getString("lng", null)?.toDoubleOrNull()
        coordinate = if (lat != null && lng != null) GeographicCoordinate(lat, lng) else {
            context.getExternalFilesDir(null)?.listFiles()?.filter { it.extension == "pmtiles" }?.sortedBy { it.name }?.firstNotNullOfOrNull { file ->
                runCatching {
                    RandomAccessFile(file, "r").use { f ->
                        val header = ByteArray(127); f.readFully(header)
                        require(String(header, 0, 7, Charsets.US_ASCII) == "PMTiles" && header[7].toInt() == 3)
                        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        GeographicCoordinate(buf.getInt(123) / 1e7, buf.getInt(119) / 1e7)
                    }
                }.getOrNull()
            } ?: GeographicCoordinate(0.0, 0.0)
        }
    }
    fun remember(location: Location) {
        coordinate = GeographicCoordinate(location.latitude, location.longitude)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - writtenAt > 10000 || writtenAt == 0L) {
            prefs?.edit()?.putString("lat", location.latitude.toString())?.putString("lng", location.longitude.toString())?.apply()
            writtenAt = now
        }
    }
}
