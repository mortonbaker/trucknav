package com.morton.trucknav.traffic

import com.morton.trucknav.settings.Settings
import java.time.Duration
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TomTomTraffic internal constructor(private val client: OkHttpClient, private val base: String = "https://api.tomtom.com") : TrafficProvider {
    override val id = "tomtom"
    private fun key() = Settings.get("tomtomKey")?.trim()?.takeIf { it.isNotEmpty() }
    override fun flowTileUrl(z: Int, x: Int, y: Int): String? {
        require(z in 0..22 && x >= 0 && y >= 0 && x < (1 shl z) && y < (1 shl z))
        val credential = key() ?: return null
        return "$base/traffic/map/4/tile/flow/relative0/$z/$x/$y.png".toHttpUrl().newBuilder()
            .addQueryParameter("key", credential).addQueryParameter("tileSize", "512").build().toString()
    }
    override suspend fun etaWithTraffic(polyline: List<TrafficPoint>): Duration? {
        val credential = key() ?: return null
        if (polyline.size < 2) return null
        val first = polyline.first(); val last = polyline.last()
        val url = "$base/routing/1/calculateRoute/${first.lat},${first.lng}:${last.lat},${last.lng}/json".toHttpUrl().newBuilder()
            .addQueryParameter("key", credential).addQueryParameter("traffic", "true")
            .addQueryParameter("departAt", "now").addQueryParameter("routeType", "fastest")
            .addQueryParameter("reconstructionMode", "strict").build()
        val body = buildJsonObject {
            putJsonArray("supportingPoints") { sampleRoute(polyline, 2000).forEach { p -> addJsonObject { put("latitude", p.lat); put("longitude", p.lng) } } }
        }
        val json = Json.parseToJsonElement(client.trafficBytes(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()).decodeToString()).jsonObject
        val seconds = json["routes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("summary")?.jsonObject?.get("travelTimeInSeconds")?.jsonPrimitive?.longOrNull ?: return null
        return seconds.takeIf { it > 0 }?.let(Duration::ofSeconds)
    }
    override suspend fun incidents(bbox: TrafficBounds): List<TrafficIncident> {
        val credential = key() ?: return emptyList()
        val url = "$base/traffic/services/5/incidentDetails".toHttpUrl().newBuilder()
            .addQueryParameter("key", credential).addQueryParameter("bbox", "${bbox.west},${bbox.south},${bbox.east},${bbox.north}")
            .addQueryParameter("fields", "{incidents{geometry{type,coordinates},properties{id,events{description}}}}")
            .addQueryParameter("language", "en-US").addQueryParameter("timeValidityFilter", "present").build()
        val json = Json.parseToJsonElement(client.trafficBytes(Request.Builder().url(url).build()).decodeToString()).jsonObject
        return json["incidents"]?.jsonArray.orEmpty().mapNotNull { item ->
            runCatching {
                val obj = item.jsonObject; val geometry = obj.getValue("geometry").jsonObject
                val coordinates = geometry.getValue("coordinates").jsonArray
                val point = if (geometry["type"]?.jsonPrimitive?.content == "Point") coordinates else coordinates.first().jsonArray
                val props = obj.getValue("properties").jsonObject
                val label = props["events"]?.jsonArray?.firstOrNull()?.jsonObject?.get("description")?.jsonPrimitive?.content ?: "Traffic incident"
                TrafficIncident(props.getValue("id").jsonPrimitive.content, TrafficPoint(point[1].jsonPrimitive.double, point[0].jsonPrimitive.double), label.replace('\n', ' ').take(120))
            }.getOrNull()
        }
    }
    override suspend fun testKey(): String {
        val url = flowTileUrl(0, 0, 0) ?: return "No key configured"
        client.trafficBytes(Request.Builder().url(url).build())
        return "OK — traffic flow access"
    }
}

class GoogleTraffic internal constructor(private val client: OkHttpClient, private val base: String = "https://routes.googleapis.com") : TrafficProvider {
    override val id = "google"
    override fun flowTileUrl(z: Int, x: Int, y: Int): String? = null
    override suspend fun incidents(bbox: TrafficBounds): List<TrafficIncident> = emptyList()
    override suspend fun etaWithTraffic(polyline: List<TrafficPoint>): Duration? {
        val credential = Settings.get("googleMapsKey")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (polyline.size < 2) return null
        val points = sampleRoute(polyline, 27) // max 25 intermediates + origin/destination
        fun waypoint(p: TrafficPoint, via: Boolean) = buildJsonObject {
            putJsonObject("location") { putJsonObject("latLng") { put("latitude", p.lat); put("longitude", p.lng) } }
            if (via) put("via", true)
        }
        val body = buildJsonObject {
            put("origin", waypoint(points.first(), false)); put("destination", waypoint(points.last(), false))
            putJsonArray("intermediates") { points.drop(1).dropLast(1).forEach { add(waypoint(it, true)) } }
            put("travelMode", "DRIVE"); put("routingPreference", "TRAFFIC_AWARE"); put("computeAlternativeRoutes", false)
        }
        val request = Request.Builder().url("$base/directions/v2:computeRoutes")
            .header("X-Goog-Api-Key", credential).header("X-Goog-FieldMask", "routes.duration")
            .post(body.toString().toRequestBody(JSON)).build()
        val json = Json.parseToJsonElement(client.trafficBytes(request).decodeToString()).jsonObject
        val seconds = json["routes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("duration")?.jsonPrimitive?.content?.removeSuffix("s")?.toDoubleOrNull() ?: return null
        return seconds.takeIf { it.isFinite() && it > 0 }?.let { Duration.ofMillis((it * 1000).toLong()) }
    }
    override suspend fun testKey(): String = if (etaWithTraffic(listOf(TrafficPoint(32.9, -97.3), TrafficPoint(32.901, -97.3))) != null) "OK — ETA only (Google Maps)" else "No key or no route returned"
}

private val JSON = "application/json; charset=utf-8".toMediaType()
