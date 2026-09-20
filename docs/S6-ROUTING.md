# S6: on-device routing

Branch: routing-s6, based on main 2eaa770. Owner codex-s6. Dedicated AVD trucknav-s6, emulator-5556.

## Audit and implementation decision (2026-09-20)

Current app uses Ferrostar 0.56.0's HTTP Valhalla adapter with a 20-second timeout. Initial request failures escape the view-model coroutine. End already uses a generation guard to prevent late responses restarting navigation. Reroutes already check that navigation remains active.

Use MIT-licensed Rallista valhalla-mobile 0.6.3, with packaged x86_64 and arm64 native engines, in a new :routing Android module. No code copied from the AGPL aus-roads project. Use Ferrostar's existing request generator and response parser for both server and local paths, preserving miles, maneuvers, geometry, costing, and reroute waypoints. Server gets a three-second request budget (the measured cold server request was 2.48 seconds); failure, including malformed responses, falls back to one serialized native actor with a 64 MiB tile cache. Native calls are synchronous and cannot be forcibly canceled: canceled answers are discarded and no concurrent actor is spawned. Ten seconds is a measured acceptance threshold, not a claimed hard JNI deadline.

The server currently reports Valhalla 3.5.1-49b40b7f2. Existing four-state tile extract is 2.6 GB. Compatibility with the newer mobile engine must be demonstrated, not assumed. Install packs atomically as routing/valhalla_tiles.tar under app external files, after comparing SHA-256 with the source. Do not overwrite a mapped pack in a running process. Force-stop this owned emulator before pack replacement. Offline maps and offline routing are separate assets.

## Button/function contract before changes

| Existing action | Preserved behavior | S6 acceptance |
|---|---|---|
| Map long press | Select coordinates and open destination sheet | Works without search/server; Start uses selected coordinates |
| Start navigation | Close sheet, calculate from current location, begin or replace route | Server preferred; local fallback; error shown without crash |
| Close destination sheet | Discard selection without starting a route | No route request |
| End | Stop guidance and simulation; discard pending initial route | No delayed server/local response can restart navigation |
| Route overview / recenter | Fit current route / resume tracking | Same geometry and behavior for either routing source |
| Mute | Toggle spoken instructions | Routing source does not change mute behavior |
| Automatic reroute | Recalculate remaining waypoints; replace only while navigating | Same server/local policy; failed reroute retains current guidance |
| Search | Existing Photon online search | No claim of offline search; long press remains available |

New noninteractive footer: Server / On-device, bound to the route actually accepted. New route error dialog: clear failure explanation and Dismiss; no hidden data deletion or network toggles.

## Falsifiable acceptance

1. Existing server responds: route source Server; no native engine initialization needed.
2. Own emulator cannot reach routing endpoint: route longer than 50 miles produced within 10,000 ms, including server timeout, engine startup and parsing. Record cold and second request.
3. Same start/end and profile on server/local: absolute distance difference / server distance <= 0.05.
4. Two consecutive local routes: no crash; full app process TOTAL PSS < 614400 KiB after each. Record native and total memory. Emulator is preliminary; tablet performance remains a hardware gate.
5. Missing pack, outside coverage and malformed server response: no crash, explicit failure or successful fallback.
6. Cancel/End while fetching: late result cannot start navigation. Cancellation never starts another fallback.
7. Source footer matches accepted route, including reroute. Start/End, overview, recenter and mute retain their contracts.
8. Build debug x86_64 and release arm64; release shrinking must retain native bridge and its resources.

Evidence: unique ~/evidence/s6-<tag> directory. No changes to shared server, emulator-5554 or physical tablet. No version bump on feature branch. Merge only after checks and source claim coordination. Physical tablet installation comes from main.

## Sources

- https://github.com/Rallista/valhalla-mobile (MIT, released 0.6.3 sources inspected)
- https://github.com/stadiamaps/ferrostar/tree/0.56.0 (CustomRouteProvider and Valhalla adapter inspected)
- https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/

## Results — 2026-09-20

S6 is implemented and emulator-tested, not marked DONE or merged. Debug retains the base 0.18.0/code 52 version; no version was allocated on main and no physical tablet was changed.

Final native evidence: `~/evidence/s6-native-20260920-152558/`. UI evidence: `~/evidence/s6-ui-20260920-152655/` (includes error-dialog test). Portable receipts and screenshot are copied into `docs/evidence/s6/`.

| Criterion | Measured | Result |
|---|---|---|
| Actual server preferred | Server source; 563 ms request after reference connection | PASS |
| >50-mile local route <=10 seconds | 287852.625 m / 178.86 mi; cold actor 1168 ms; second 407 ms | PASS, emulator |
| Distance difference <=5% | Server and local both 287852.625 m, 0% difference, 65 steps | PASS |
| Full connected-but-stalled server fallback <=10 seconds | Real local HTTP socket accepts and does not respond; fallback 3478 ms including three-second timeout | PASS |
| Two routes, process memory <600 MiB | 257681 / 263401 KiB total PSS; native 92164 / 93674 KiB | PASS, emulator |
| Actual navigation UI memory | 275118 KiB after offline Start, overview, recenter | PASS, emulator |
| Missing pack and outside coverage | Explicit RoutingUnavailable; no crash; actual error dialog and Dismiss exercised | PASS |
| Start, footer, mute/unmute, overview, recenter, End, immediate cancel | Actual accessible controls exercised with external traffic blocked only for this app UID; local assets stay reachable | PASS |
| Fallback policy | Four unit tests: healthy server, transport/parser fallback, cancellation, both failures retained | PASS |
| Build | Debug x86_64 and minified release arm64; release vital lint included | PASS |

Both packs were hashed on the host and inside the emulator:

- Routing extract, 2693109760 bytes: `3e327694ee95198f82376d4c0966dc79312d96cf4b45b8a80705855c23a6bf57`.
- PMTiles basemap, 3039501422 bytes: `ca1f1d9fafe542c8c51a69b76849ce5745c19d5f448d10df577e9855eb99231a`.

## Reproduce

Use the dedicated AVD `trucknav-s6`, port 5556; its disk is `/mnt/data/trucknav-s6/avd`. Acquire and verify the `codex-s6` emulator lease. With ANDROID_HOME set, build debug and androidTest APKs, then run `bash docs/s6-smoke.sh native` and `bash docs/s6-smoke.sh ui`. The runner checks AVD identity and lease expiry. It never changes shared services or other devices. The UI outage has both an EXIT trap and a 60-second emulator-side restoration timer. Each run writes a unique evidence directory and fails unless instrumentation reports success.

Routing-pack provisioning: `bash docs/s6-install-pack.sh /mnt/data/trucknav-s6/tiles/valhalla_tiles.tar <source-sha256>`. The source checksum above is the verified pack for this run. The installer verifies source/device hashes and atomically renames a staging file with the app stopped. Physical provisioning remains part of the main-build deployment; this installer deliberately accepts only the S6 emulator.

## Setup findings and remaining gates

- Shell-pushed directories initially belonged to shell and were unreadable to the app. On this userdebug emulator, ownership had to be set on `/data/media/0/Android/data/com.morton.trucknav/files`, not just the FUSE view. `run-as` access to `/sdcard` is not a reliable proxy for the application's scoped-storage access; the native test proves actual access.
- One early UI capture showed a gray basemap with MapLibre `Error parsing PMTiles directory: map::at: key not found`. Pack checksums were correct. A fresh no-routing process rendered both online and with external app traffic blocked. The later fully rendered navigation capture passed after initial tiles settled and overview/recenter ran, with no parser errors in that run. Root cause is **not established**, and no LocalAssetServer change was made. Keep this cold-start/camera observation visible during integration; the passing screenshot is not proof the earlier failure was fixed.
- Nav's newly published ownership covers DemoNavigationScene/ViewModel. The branch's small integration changes must be reconciled with the newer S17 favorites/API work; do not overwrite those additions. Core module and integration should remain separate commits for review. Shared main currently advanced beyond this branch's base. No merge or physical install is authorized by this receipt.
- Physical acceptance still required: ARM64 release actually running with these tiles, two routes and <600 MiB PSS on the Samsung, real GPS reroute/End race, and bounded USB-connected outage. Emulator performance is not a substitute for those checks.
