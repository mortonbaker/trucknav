package com.morton.trucknav.map

import android.content.Context
import android.util.Log
import com.morton.trucknav.AppModule
import com.morton.trucknav.MapStyle
import com.morton.trucknav.MapStyles
import com.stadiamaps.ferrostar.core.NavigationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import uniffi.ferrostar.GeographicCoordinate
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

// Warms the online layers along the route so satellite/hybrid never grey out
// behind the truck. MapLibre only fetches what is in the viewport; on Starlink
// that is a few hundred ms per tile column at highway speed, and every new
// column pops in. So while navigating on an online style we hand MapLibre two
// offline regions (they land in the same tile database the map renders from):
//   far  - the whole remaining route, z11-13, once per route
//   near - the next NEAR_M metres, z14-16 (nav zoom is 16), re-cut every
//          RECUT_M metres of progress and rebuilt on reroute
// Regions carry our tag in their metadata and are deleted when navigation ends
// or the style goes offline, so they never accumulate. The download style is
// the imagery-only satellite style (hybrid's imagery is the same source, so
// the cache serves both); terrain warms its own DEM.
object RoutePrefetcher {
    private const val TAG = "RoutePrefetch"
    private const val TAG_BYTES_PREFIX = "trucknav-prefetch:"
    private const val NEAR_M = 30_000.0
    private const val RECUT_M = 10_000.0
    private const val BUFFER_M = 900.0            // half-width of the corridor: viewport at z16/45° sees ~1 km ahead and to the sides
    private const val SAMPLE_M = 700.0            // one bbox square per this much route
    val AMBIENT_CACHE_BYTES = 1024L * 1024 * 1024 // default is 50 MB: a Denton-Tulsa drive in hybrid is ~150 MB of imagery

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var om: OfflineManager
    private lateinit var appCtx: Context
    private var routeKey = 0
    private var far: OfflineRegion? = null
    private var near: OfflineRegion? = null
    private var nearStart: GeographicCoordinate? = null
    private var active = false

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        MapLibre.getInstance(appCtx)
        om = OfflineManager.getInstance(appCtx)
        om.setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { Log.i(TAG, "ambient cache ${AMBIENT_CACHE_BYTES shr 20} MB") }
            override fun onError(message: String) { Log.w(TAG, "ambient cache: $message") }
        })
        clearOurs("start")
        scope.launch {
            combine(AppModule.viewModel.navigationUiState, MapStyles.current) { s, st -> s to st }.collect { (s, st) -> onState(s, st) }
        }
    }

    // A file the harness can drop to measure the baseline (no warm-up).
    private fun disabled() = File(appCtx.getExternalFilesDir(null), "no-prefetch").exists()

    private fun onState(s: NavigationUiState, style: MapStyle) {
        val geom = s.routeGeometry
        val want = s.isNavigating() && !style.offline && geom != null && geom.size >= 2 && !disabled()
        if (!want) { if (active) { active = false; clearOurs("nav ended / offline style") }; return }
        val key = geom.hashCode() * 31 + style.ordinal
        if (key != routeKey || !active) {
            routeKey = key; active = true
            clearOurs("new route")
            far = null; near = null; nearStart = null
            create("far", geom, style, 11.0, 13.0) { far = it }
        }
        val here = s.location?.coordinates ?: geom.first()
        val ns = nearStart
        if (ns == null || dist(ns, here) > RECUT_M) {
            nearStart = here
            near?.let { old -> old.setDownloadState(OfflineRegion.STATE_INACTIVE); old.delete(quietDelete) }
            create("near", ahead(geom, here, NEAR_M), style, 14.0, 16.0) { near = it }
        }
    }

    private fun styleFor(style: MapStyle) = MapStyles.url(if (style == MapStyle.Terrain) MapStyle.Terrain else MapStyle.Satellite)

    private fun create(kind: String, line: List<GeographicCoordinate>, style: MapStyle, minZ: Double, maxZ: Double, keep: (OfflineRegion) -> Unit) {
        if (line.size < 2) return
        val def = OfflineGeometryRegionDefinition(styleFor(style), corridor(line), minZ, maxZ, appCtx.resources.displayMetrics.density)
        val meta = "$TAG_BYTES_PREFIX$kind:$routeKey".toByteArray()
        om.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                keep(offlineRegion)
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    var last = 0L
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) Log.i(TAG, "$kind complete: ${status.completedResourceCount} tiles, ${status.completedResourceSize shr 10} KB")
                        else if (System.currentTimeMillis() - last > 5000) { last = System.currentTimeMillis(); Log.i(TAG, "$kind ${status.completedResourceCount}/${status.requiredResourceCount}") }
                    }
                    override fun onError(error: OfflineRegionError) { Log.w(TAG, "$kind error: ${error.reason} ${error.message}") }
                    override fun mapboxTileCountLimitExceeded(limit: Long) { Log.w(TAG, "$kind tile limit $limit") }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                Log.i(TAG, "$kind region ${offlineRegion.id}: ${line.size} pts z$minZ-$maxZ")
            }
            override fun onError(error: String) { Log.w(TAG, "$kind create: $error") }
        })
    }

    private val quietDelete = object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() {}
        override fun onError(error: String) { Log.w(TAG, "delete: $error") }
    }

    private fun clearOurs(why: String) {
        om.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val ours = offlineRegions?.filter { String(it.metadata).startsWith(TAG_BYTES_PREFIX) } ?: emptyList()
                if (ours.isNotEmpty()) Log.i(TAG, "deleting ${ours.size} regions ($why)")
                ours.forEach { it.setDownloadState(OfflineRegion.STATE_INACTIVE); it.delete(quietDelete) }
            }
            override fun onError(error: String) { Log.w(TAG, "list: $error") }
        })
    }

    // --- geometry --------------------------------------------------------------

    private fun dist(a: GeographicCoordinate, b: GeographicCoordinate): Double {
        val kx = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2)); val ky = 111_320.0
        val dx = (a.lng - b.lng) * kx; val dy = (a.lat - b.lat) * ky
        return Math.sqrt(dx * dx + dy * dy)
    }

    // The route from the vertex nearest to `here`, for `metres` ahead.
    private fun ahead(geom: List<GeographicCoordinate>, here: GeographicCoordinate, metres: Double): List<GeographicCoordinate> {
        var best = 0; var bd = Double.MAX_VALUE
        geom.forEachIndexed { i, p -> val d = dist(p, here); if (d < bd) { bd = d; best = i } }
        val out = ArrayList<GeographicCoordinate>(); var acc = 0.0
        out.add(geom[best])
        for (i in best + 1 until geom.size) { acc += dist(geom[i - 1], geom[i]); out.add(geom[i]); if (acc >= metres) break }
        return out
    }

    // Corridor = a square of +-BUFFER_M around a sample every SAMPLE_M along the line.
    // Squares rather than one buffered polygon: MapLibre's tile cover is exact for simple
    // polygons and a self-intersecting buffer at a hairpin would be anything but.
    private fun corridor(line: List<GeographicCoordinate>): MultiPolygon {
        val squares = ArrayList<Polygon>()
        var acc = SAMPLE_M
        for (i in line.indices) {
            if (i > 0) acc += dist(line[i - 1], line[i])
            if (acc < SAMPLE_M && i != line.size - 1) continue
            acc = 0.0
            val p = line[i]
            val dlat = BUFFER_M / 111_320.0; val dlng = BUFFER_M / (111_320.0 * max(0.2, cos(Math.toRadians(p.lat))))
            squares.add(Polygon.fromLngLats(listOf(listOf(
                Point.fromLngLat(p.lng - dlng, p.lat - dlat), Point.fromLngLat(p.lng + dlng, p.lat - dlat),
                Point.fromLngLat(p.lng + dlng, p.lat + dlat), Point.fromLngLat(p.lng - dlng, p.lat + dlat),
                Point.fromLngLat(p.lng - dlng, p.lat - dlat)))))
        }
        return MultiPolygon.fromPolygons(squares)
    }
}
