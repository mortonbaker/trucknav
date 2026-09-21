package com.morton.trucknav.nav

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ferrostar.GeographicCoordinate as Point

@RunWith(AndroidJUnit4::class)
class AlongRouteTest {
    @Test fun distanceUsesSegmentInteriorAndClampsEndpoints() {
        val line=listOf(Point(0.0,0.0),Point(0.0,0.1))
        assertEquals(0.0,AlongRoute.corridorDistance(Point(0.0,0.05),line),0.01)
        assertEquals(1111.95,AlongRoute.corridorDistance(Point(0.01,0.05),line),0.1)
        assertEquals(1111.95,AlongRoute.corridorDistance(Point(0.0,0.11),line),0.1)
        assertTrue(AlongRoute.corridorDistance(Point(0.029,0.05),line)>AlongRoute.MAX_DISTANCE_M)
    }
    @Test fun zeroLengthAndEmptyCorridorsAreDefined() {
        val p=Point(33.0,-97.0)
        assertEquals(0.0,AlongRoute.corridorDistance(p,listOf(p,p)),0.0)
        assertEquals(Double.POSITIVE_INFINITY,AlongRoute.corridorDistance(p,emptyList()),0.0)
    }
    @Test fun samplingInterpolatesEveryTenKmAndIncludesDestination() {
        val line=listOf(Point(0.0,0.0),Point(0.0,0.3))
        val samples=AlongRoute.samples(line)
        assertEquals(5,samples.size)
        assertEquals(line.first(),samples.first());assertEquals(line.last(),samples.last())
        for(i in 0..2) assertEquals(10000.0,AlongRoute.distance(samples[i],samples[i+1]),0.1)
    }
}
