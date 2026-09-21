package com.morton.trucknav.nav

import com.morton.trucknav.AppModule
import com.morton.trucknav.PhotonHit
import com.stadiamaps.ferrostar.core.NavigationUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.ferrostar.GeographicCoordinate
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

enum class AlongCategory(val label: String, val tag: String) {
    Gas("Gas", "amenity:fuel"), Food("Food", "amenity:restaurant"),
    Coffee("Coffee", "amenity:cafe"), Groceries("Groceries", "shop:supermarket")
}

/** Corridor geocoding and directional detours. No route or trip mutations. */
object AlongRoute {
    const val MAX_DISTANCE_M = 3218.688
    private val client by lazy { AppModule.okHttp.newBuilder().callTimeout(3500, TimeUnit.MILLISECONDS).build() }
    private val permits = Semaphore(4)
    private var pickJob: Job? = null
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun observePick(viewModel: com.morton.trucknav.DemoNavigationViewModel,
                    map: com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState, hit: PhotonHit) {
        pickJob?.cancel()
        pickJob = observationScope.launch {
            repeat(60) {
                delay(250)
                val state = viewModel.navigationUiState.value
                val next = (state.tripState as? uniffi.ferrostar.TripState.Navigating)?.remainingWaypoints?.firstOrNull()?.coordinate
                if (next != null && distance(next,hit.coordinate)<100 && !viewModel.sceneState.value.addingStop) {
                    NavLog.log("along-added", "letter=${hit.letter} nextLat=${next.lat} nextLng=${next.lng} navigating=${state.isNavigating()} camera=${map.cameraMode}")
                    return@launch
                }
            }
            NavLog.log("along-added", "letter=${hit.letter} next-stop confirmation timed out")
        }
    }
    private val jsonType = "application/json".toMediaType()

    fun remaining(state: NavigationUiState): List<GeographicCoordinate> {
        val steps = state.remainingSteps.orEmpty()
        if (steps.isEmpty()) return emptyList()
        val first = steps.first().geometry
        val index = (state.currentStepGeometryIndex ?: 0).coerceIn(0, (first.size - 1).coerceAtLeast(0))
        return buildList {
            state.location?.coordinates?.let { add(it) }
            addAll(first.drop(index))
            steps.drop(1).forEach { addAll(it.geometry) }
        }.fold(mutableListOf()) { out, c -> out.apply { if (lastOrNull() != c) add(c) } }
    }

    fun distance(a: GeographicCoordinate, b: GeographicCoordinate): Double {
        val dlat = Math.toRadians(b.lat-a.lat); val dlon = Math.toRadians(b.lng-a.lng)
        val h = sin(dlat/2).pow(2) + cos(Math.toRadians(a.lat))*cos(Math.toRadians(b.lat))*sin(dlon/2).pow(2)
        return 12742000.0 * asin(sqrt(h.coerceIn(0.0,1.0)))
    }

    /** Local tangent-plane projection, including segment endpoints and zero-length segments. */
    fun corridorDistance(p: GeographicCoordinate, line: List<GeographicCoordinate>): Double {
        if (line.size == 1) return distance(p,line.first())
        val scale = cos(Math.toRadians(p.lat)); val meters = 6371000.0 * PI / 180
        fun dx(lon: Double) = ((lon-p.lng+540)%360-180) * meters * scale
        return line.zipWithNext().minOfOrNull { (a,b) ->
            val ax=dx(a.lng); val ay=(a.lat-p.lat)*meters
            val bx=dx(b.lng); val by=(b.lat-p.lat)*meters
            val vx=bx-ax; val vy=by-ay; val norm=vx*vx+vy*vy
            val t=if(norm==0.0) 0.0 else (-(ax*vx+ay*vy)/norm).coerceIn(0.0,1.0)
            hypot(ax+t*vx,ay+t*vy)
        } ?: Double.POSITIVE_INFINITY
    }

    fun samples(line: List<GeographicCoordinate>, spacing: Double = 10000.0): List<GeographicCoordinate> {
        require(spacing>0)
        if(line.isEmpty()) return emptyList()
        val out=mutableListOf(line.first()); var accumulated=0.0; var next=spacing
        line.zipWithNext().forEach { (a,b) ->
            val length=distance(a,b)
            while(length>0 && accumulated+length>=next) {
                val t=(next-accumulated)/length
                val dlng=(b.lng-a.lng+540)%360-180
                out.add(GeographicCoordinate(a.lat+(b.lat-a.lat)*t, (a.lng+dlng*t+540)%360-180))
                next+=spacing
            }
            accumulated+=length
        }
        if(distance(out.last(),line.last())>1) out.add(line.last())
        return out
    }

    private suspend fun request(req: Request): JsonObject {
        return try { requestOnce(req) } catch (e: IOException) {
            // One bounded retry for a transient upstream outage, never retry rate limits.
            if (e.message != "HTTP 503") throw e
            delay(100)
            requestOnce(req)
        }
    }

    private suspend fun requestOnce(req: Request): JsonObject = permits.withPermit {
        val bytes = suspendCancellableCoroutine<String> { continuation ->
            val call=client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use {
                        if(!it.isSuccessful) {
                            NavLog.log("along-http", "host=${req.url.host} path=${req.url.encodedPath} status=${it.code} protocol=${it.protocol}")
                            throw IOException("HTTP ${it.code}")
                        }
                        val body=it.body?.string() ?: throw IOException("Empty response")
                        if(continuation.isActive) continuation.resume(body)
                    } } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
        Json.parseToJsonElement(bytes).jsonObject
    }

    private suspend fun matrix(sources: List<GeographicCoordinate>, targets: List<GeographicCoordinate>): List<List<Double?>> {
        val body=buildJsonObject {
            fun coords(points: List<GeographicCoordinate>)=buildJsonArray { points.forEach { p -> addJsonObject { put("lat",p.lat); put("lon",p.lng) } } }
            put("sources",coords(sources)); put("targets",coords(targets)); put("costing","auto")
        }
        val base=AppModule.valhallaUrl.trimEnd('/').removeSuffix("/route")
        val data=request(Request.Builder().url("$base/sources_to_targets").post(body.toString().toRequestBody(jsonType)).build())
        val rows=data["sources_to_targets"]?.jsonArray ?: throw IOException("Matrix unavailable")
        require(rows.size==sources.size) { "Matrix sources ${rows.size}/${sources.size}" }
        return rows.map { row -> row.jsonArray.also { require(it.size==targets.size) { "Matrix targets ${it.size}/${targets.size}" } }.map { item ->
            item.jsonObject["time"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it>=0 }
        } }
    }

    suspend fun available(now: GeographicCoordinate, next: GeographicCoordinate): Boolean = try {
        matrix(listOf(now),listOf(next))[0][0]!=null
    } catch(e: CancellationException) { throw e } catch(_: Exception) { false }

    private suspend fun photon(q: String, category: AlongCategory?, sample: GeographicCoordinate): List<PhotonHit> {
        val configured=AppModule.photonUrl.toHttpUrl()
        val url=if(category==null) configured.newBuilder() else configured.newBuilder().encodedPath(configured.encodedPath.trimEnd('/').removeSuffix("/api")+"/reverse")
        url.addQueryParameter("lat",sample.lat.toString()).addQueryParameter("lon",sample.lng.toString())
            .addQueryParameter("limit","20").addQueryParameter("lang","en").addQueryParameter("dedupe","0")
        if(category!=null) url.addQueryParameter("osm_tag",category.tag).addQueryParameter("radius","8")
        else url.addQueryParameter("q",q).addQueryParameter("location_bias_scale","0.1")
        val response=request(Request.Builder().url(url.build()).header("User-Agent", "TruckNav/${com.morton.trucknav.BuildConfig.VERSION_NAME} (+https://github.com/mortonbaker/trucknav)").build())
        return response.getValue("features").jsonArray.mapNotNull { f ->
            val obj=f.jsonObject; val props=obj.getValue("properties").jsonObject
            val id=props["osm_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val type=props["osm_type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val coordinates=obj.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
            val lat=coordinates[1].jsonPrimitive.double; val lon=coordinates[0].jsonPrimitive.double
            if(!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@mapNotNull null
            val label=listOf("name","street","housenumber","city").mapNotNull { props[it]?.jsonPrimitive?.content }.distinct().joinToString(", ")
            PhotonHit(label.ifBlank { category?.label ?: q },GeographicCoordinate(lat,lon),osmId="$type$id")
        }
    }

    suspend fun search(query: String, category: AlongCategory?, now: GeographicCoordinate,
                       corridor: List<GeographicCoordinate>, next: GeographicCoordinate): List<PhotonHit> = withTimeout(12000) {
        val key=category?.label ?: query
        val context=buildJsonObject { put("query",key); put("nowLat",now.lat); put("nowLng",now.lng); put("nextLat",next.lat); put("nextLng",next.lng) }
        NavLog.log("along-context",context.toString())
        corridor.chunked(40).forEachIndexed { i, points ->
            NavLog.log("along-geometry",buildJsonObject { put("chunk",i); put("points",buildJsonArray { points.forEach { add(buildJsonArray { add(it.lat);add(it.lng) }) } }) }.toString())
        }
        val raw=coroutineScope { samples(corridor).map { sample -> async { photon(query,category,sample) } }.awaitAll().flatten() }
        NavLog.log("along-stage", "photon hits=${raw.size}")
        val candidates=raw.distinctBy { it.osmId }.map { it.copy(distanceM=distance(now,it.coordinate),corridorM=corridorDistance(it.coordinate,corridor)) }
            .filter { it.corridorM!!<=MAX_DISTANCE_M }
        val ranked=coroutineScope {
            candidates.chunked(40).map { batch -> async {
                val outbound=async { matrix(listOf(now),batch.map { it.coordinate }+next)[0] }
                val onward=async { matrix(batch.map { it.coordinate },listOf(next)).map { it[0] } }
                val a=outbound.await(); val b=onward.await(); val baseline=a.last() ?: throw IOException("No route to next stop")
                batch.mapIndexedNotNull { i,h ->
                    val to=a[i] ?: return@mapIndexedNotNull null; val from=b[i] ?: return@mapIndexedNotNull null
                    h.copy(detourS=max(0.0,to+from-baseline),toHitS=to,fromHitS=from,baselineS=baseline)
                }
            } }.awaitAll().flatten()
        }.sortedWith(compareBy<PhotonHit> { it.detourS }.thenBy { it.osmId })
        val top=ranked.take(6).mapIndexed { i,h -> h.copy(letter=('A'+i).toString()) }
        NavLog.log("along-orders",buildJsonObject { put("query",key); put("detour",buildJsonArray { ranked.forEach { add(it.osmId!!) } });put("puck",buildJsonArray { ranked.sortedBy { it.distanceM }.forEach { add(it.osmId!!) } }) }.toString())
        top.forEach { h -> NavLog.log("along-hit",buildJsonObject {
            put("query",key);put("id",h.osmId);put("letter",h.letter);put("label",h.label);put("lat",h.coordinate.lat);put("lng",h.coordinate.lng)
            put("corridorM",h.corridorM);put("puckM",h.distanceM);put("detourS",h.detourS);put("toHitS",h.toHitS);put("fromHitS",h.fromHitS);put("baselineS",h.baselineS)
        }.toString()) }
        top
    }
}
