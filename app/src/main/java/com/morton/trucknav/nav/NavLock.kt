package com.morton.trucknav.nav

import android.location.Location
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// FerrostarCore 0.56 mutates its session and NavState from two threads with
// no lock: the location collector (Dispatchers.IO) and whoever calls
// replaceRoute (the reroute coroutine, or the UI). onLocationUpdated reads the
// old NavState, runs the old session, then writes the result back
// unconditionally, so a replaceRoute that lands in between is overwritten by a
// state belonging to the route that was just discarded. Seen 2026-09-20 on the
// emulator: reroute to a 5 m route, arrival announced, and 350 ms later the
// original 11 mi route was back, stuck at "10.94 mi" forever (B11).
// Every core mutation and every fix delivered to the core takes this lock.
object NavLock {
    val mutex = Mutex()
    fun <T> sync(block: () -> T): T = runBlocking { mutex.withLock { block() } }
}

// Delivers fixes to the core one at a time, under NavLock. Ferrostar's collector
// body runs inside emit(), so the whole onLocationUpdated is covered.
class LockedLocationProvider(private val inner: NavigationLocationProviding) : NavigationLocationProviding {
    override suspend fun lastLocation(): Location? = inner.lastLocation()
    override fun locationUpdates(interval: Long): Flow<Location> = flow {
        inner.locationUpdates(interval).collect { l -> NavLock.mutex.withLock { emit(l) } }
    }
}
