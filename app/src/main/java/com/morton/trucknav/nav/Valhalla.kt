package com.morton.trucknav.nav

import com.morton.trucknav.AppModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.ferrostar.GeographicCoordinate

// One Valhalla matrix call: drive time (seconds) from `from` to each target, null where unroutable.
fun matrixEtas(from: GeographicCoordinate?, targets: List<GeographicCoordinate>): List<Double?>? {
    if (from == null || targets.isEmpty()) return null
    return try {
        val base = AppModule.valhallaUrl.removeSuffix("/route").removeSuffix("/")
        val t = targets.joinToString(",") { "{\"lat\":${it.lat},\"lon\":${it.lng}}" }
        val body = "{\"sources\":[{\"lat\":${from.lat},\"lon\":${from.lng}}],\"targets\":[$t],\"costing\":\"auto\"}"
        val res = AppModule.okHttp.newCall(Request.Builder().url("$base/sources_to_targets").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { it.body?.string() } ?: return null
        Json.parseToJsonElement(res).jsonObject["sources_to_targets"]!!.jsonArray[0].jsonArray.map { it.jsonObject["time"]?.jsonPrimitive?.content?.toDoubleOrNull() }
    } catch (e: Exception) { android.util.Log.w("Valhalla", "matrix: $e"); null }
}
