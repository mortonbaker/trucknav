package com.morton.trucknav.routing

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FallbackTest {
    @Test fun healthyServerDoesNotOpenLocalEngine() = runTest {
        val route = serverThenDevice({ "server route" }, { error("must not load tiles") })
        assertEquals(RouteSource.SERVER, route.source)
    }
    @Test fun NetworkAndParserFailuresFallBack() = runTest {
        for (error in listOf(IOException("offline"), IllegalArgumentException("bad JSON"))) {
            assertEquals(Routed("local route", RouteSource.DEVICE), serverThenDevice({ throw error }, { "local route" }))
        }
    }
    @Test fun cancellationNeverFallsBack() = runTest {
        var called = false
        try {
            serverThenDevice({ throw CancellationException("End pressed") }, { called = true })
            fail("cancellation swallowed")
        } catch (_: CancellationException) { assertFalse(called) }
    }
    @Test fun localFailurePreservesBothCauses() = runTest {
        val network = IOException("server offline")
        val local = IOException("pack missing")
        try { serverThenDevice({ throw network }, { throw local }); fail("expected failure") }
        catch (e: IOException) { assertSame(local, e); assertSame(network, e.suppressed.single()) }
    }
}
