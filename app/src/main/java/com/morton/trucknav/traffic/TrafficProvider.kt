package com.morton.trucknav.traffic

import java.time.Duration
import kotlin.math.*

data class TrafficPoint(val lat: Double, val lng: Double) {
    init { require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) }
}
data class TrafficBounds(val west: Double, val south: Double, val east: Double, val north: Double)
data class TrafficIncident(val id: String, val position: TrafficPoint, val label: String)

/** A duration for this exact remaining geometry, never a replacement navigation route. */
data class TrafficEta(val duration: Duration, val provider: String, val measuredAt: Long) {
    fun fresh(now: Long) = now >= measuredAt && now - measuredAt < 600_000L
    val minutes: Long get() = ceil(duration.seconds / 60.0).toLong()
    val label: String get() = "· $minutes min w/ traffic"
    val attribution: String get() = "TomTom"
}

interface TrafficProvider {
    val id: String
    /** Internal use only: a signed upstream URL must never reach map style/logs. */
    fun flowTileUrl(z: Int, x: Int, y: Int): String?
    suspend fun etaWithTraffic(polyline: List<TrafficPoint>): Duration?
    suspend fun incidents(bbox: TrafficBounds): List<TrafficIncident>
    suspend fun testKey(): String
}

/** Sampling includes endpoints, is ordered, and never exceeds a provider's waypoint limit. */
internal fun sampleRoute(points: List<TrafficPoint>, limit: Int): List<TrafficPoint> {
    if (points.size <= limit) return points
    return (0 until limit).map { points[it * (points.size - 1) / (limit - 1)] }.distinct()
}

internal fun distanceMeters(a: TrafficPoint, b: TrafficPoint): Double {
    val lat = Math.toRadians((a.lat + b.lat) / 2)
    return hypot((a.lng - b.lng) * cos(lat), a.lat - b.lat) * 111_195
}

/** Match incident geometry against remaining route segments, not the whole original route. */
internal fun onCorridor(p: TrafficPoint, route: List<TrafficPoint>, radius: Double = 100.0): Boolean =
    route.zipWithNext().any { (a, b) ->
        val c = cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * c * 111_195; val ay = (a.lat - p.lat) * 111_195
        val bx = (b.lng - p.lng) * c * 111_195; val by = (b.lat - p.lat) * 111_195
        val dx = bx - ax; val dy = by - ay
        val t = if (dx * dx + dy * dy == 0.0) 0.0 else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        hypot(ax + t * dx, ay + t * dy) <= radius
    }

/** Sliding window, including attempts and failures; no boundary can double the allowance. */
internal class TileBudget(private val limit: Int = 2000, private val windowMs: Long = 1_800_000) {
    private val requests = java.util.ArrayDeque<Long>()
    @Synchronized fun restore(timestamps: List<Long>) { requests.clear(); requests.addAll(timestamps.sorted().takeLast(limit)) }
    @Synchronized fun snapshot(): List<Long> = requests.toList()
    @Synchronized fun take(now: Long): Boolean {
        while (requests.isNotEmpty() && now - requests.first >= windowMs) requests.removeFirst()
        if (requests.size >= limit) return false
        requests.addLast(now)
        return true
    }
}
