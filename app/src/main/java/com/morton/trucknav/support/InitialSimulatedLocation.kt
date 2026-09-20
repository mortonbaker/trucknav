package com.morton.trucknav.support

import java.time.Instant
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation

val initialSimulatedLocation =
    UserLocation(
        GeographicCoordinate(com.morton.trucknav.BuildConfig.homeLat, com.morton.trucknav.BuildConfig.homeLng),
        6.0,
        null,
        Instant.now(),
        null,
    )
