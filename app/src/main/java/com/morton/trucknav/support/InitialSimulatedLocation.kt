package com.morton.trucknav.support

import java.time.Instant
import uniffi.ferrostar.UserLocation

val initialSimulatedLocation: UserLocation
    get() = UserLocation(com.morton.trucknav.settings.InitialPosition.coordinate, 6.0, null, Instant.now(), null)
