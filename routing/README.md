# TruckNav routing

`HybridRouteProvider` implements Ferrostar 0.56.0's `CustomRouteProvider`. The existing Valhalla adapter creates requests and parses OSRM responses on both paths. Server requests have a three-second total timeout; transport, status and parsing errors trigger local routing. Coroutine cancellation does not trigger fallback.

`OfflineValhalla` owns one lazy Valhalla Mobile 0.6.3 native actor with a 64 MiB graph cache. A mutex serializes requests on Dispatchers.IO; native calls cannot be forcibly interrupted, but canceled answers are discarded. Never create replacement actors to work around a timeout. Call `close()` when a non-application-scoped provider is discarded. The production provider is application-scoped and lives until process exit.

Provision a verified uncompressed Valhalla tile extract at `getExternalFilesDir(null)/routing/valhalla_tiles.tar`. It is distinct from the PMTiles basemap. Installation must use a `.part` file, verify SHA-256, stop the app, and rename atomically. A running process must not have its mapped extract modified. Missing packs fail visibly; they never trigger a download. The new emulator installer is `docs/s6-install-pack.sh`.

Offline capability is regional; this graph covers Texas, Oklahoma, Arkansas and Missouri. It does not provide offline place search, live traffic, satellite tiles, or truck-specific vehicle restriction routing. Costing intentionally remains the existing `auto` profile.

Route attribution is attached to the returned Kotlin route instance, retained in a bounded list. The view model publishes it only when accepting that route. Reroute integration must also call `acceptRouteSource` after replacing a route. End cancels the initial request and clears source/error state while retaining the existing generation guard.

Checks: `./gradlew :routing:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`. See `docs/S6-ROUTING.md` for controls, acceptance, evidence and remaining gates. Dependency MIT notice is packaged under `assets/notices/`.
