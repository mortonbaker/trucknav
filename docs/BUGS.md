# TruckNav — open bugs and field reports

## RESOLVED 2026-09-20 (drive home, first NavLog): B1, B2, B3, B4 were OsmAnd, not TruckNav
`files/navlog/nav-20260920.log` shows one route (gen=1, home, 7.4 mi / 10 min), every visual/spoken instruction on the correct side, no deviation, no reroute. Meanwhile `dumpsys activity services net.osmand.plus` showed `NavigationService` foreground with an active navigation notification and `dumpsys audio` showed OsmAnd taking `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` focus at 11:42. OsmAnd was still routing (its own destination, ~30 mi) from the 09-19 tests and talking over TruckNav: "rerouting", the wrong-side turn, the 32 mi / 40 min, railroad/crosswalk alerts, and TruckNav's mute not affecting it. Fixed by `am force-stop net.osmand.plus`; S12's guard stays (harmless, and the generation check is right anyway). Follow-up: S17 makes sure no foreign navigation app can run alongside (see BUILD-PLAN).


Reported from a real drive on 2026-09-20 (v0.12.2). None of these are reproduced yet; the tablet's logcat buffer had rolled over by the time it was pulled (only ~40 min of history and the app logs no navigation events). **First task before any fix: instrumentation** (B0).

## B0 — Navigation event log (prerequisite)
Write a rolling `files/nav-YYYYMMDD.log` (and logcat tag `NavLog`) with: route requests + Valhalla summary (distance, duration, waypoints), every `VisualInstruction` / `SpokenInstruction` as issued (text, maneuver type/modifier, distance-to, step index, current step road name, snapped location, bearing), trip progress every 10 s (distance remaining, duration remaining), deviation/reroute events, start/stop calls with their caller, and mute toggles. Cap 5 MB, keep 7 days. `adb pull` it after a drive.
- Done when: a 5-minute drive yields a log with all of the above, and each bug below can be answered from it.

## B1 — "End Navigation" does not end navigation
Operator: after tapping End Navigation the app kept rerouting and "taking me home" (arrival bar 33 mi / 42 min). Audit the start/stop path: `DemoNavigationViewModel.stopNavigation()` → `ferrostarCore.stopNavigation()`; check the Ferrostar foreground service notification (id 501) is cancelled, the location provider isn't re-triggering `startNavigation` (e.g. destination sheet still holding `selectedDestination` and a reroute), `DestinationSelectionCameraEffect`, and the rotation/recreate path (`LaunchedEffect(landscape)` recenter with `isNavigating = true`). Also check whether the S2 mid-route helpers could re-enter navigation.
- Done when: after End Navigation, `uiState.isNavigating()` is false within 1 s, notification 501 gone, no `fetching route` lines for 5 min while driving, arrival bar and turn card gone; verified from the NavLog on a real drive and by the harness (`s2-camera.sh` end step + 60 s watch).

## B2 — Wrong turn direction spoken/shown
Operator: "turn right on 377" when the route actually turned left. Candidates: instruction from a *previous* route after a reroute; Valhalla maneuver `type`/`modifier` vs. banner text mismatch; TTS reading the next-next instruction. Needs B0 to see which route and step produced it.
- Done when: NavLog shows, for every maneuver on a test drive, banner text == spoken text == geometry turn direction (computed from the route polyline bearing change at the maneuver point).

## B3 — Distance/ETA disagree
Operator: arrival bar said ~32 mi / 40 min while "the map" showed 8.8 mi / 11 min. Two different numbers means two different sources (trip remaining vs route summary, or two routes alive at once — see B1). Needs B0.
- Done when: NavLog shows one active route; bar values equal the route's remaining distance/duration within 2 %.

## B4 — Mute doesn't mute
`toggleMute()` calls `AndroidTtsObserver.setMuted(!isMuted)`. Verify the observer instance used by the core is `AppModule.ttsObserver` (it is set in MainActivity.onCreate; after a recreate the observer may be restarted by `onStart` and lose the muted flag), that `uiState.isMuted` reflects it, and whether Ferrostar 0.56's observer honours mute for already-queued utterances.
- Done when: tap Mute → `isMuted=true` logged, next instruction produces no TTS (`dumpsys audio` shows no TTS stream; `TextToSpeechManagerPerUserService` shows no speak), icon shows muted; survives rotation and a pause/resume.

## B5 — Satellite/hybrid tiles load late while moving
Raster imagery pops in behind the vehicle. Research MapLibre best practice before coding: `prefetchZoomDelta` (default 4 in native — check maplibre-compose exposes it), raster source `maxzoom` so it overzooms cached tiles instead of fetching new ones at every zoom step, `tileSize` 256 vs 512 (fewer requests), OfflineManager ambient cache size (`setMaximumAmbientCacheSize`, default 50 MB — raise to 500 MB+), and heading-ahead prefetch (Ferrostar camera padding already puts the puck low; pre-request tiles along the route polyline ahead — MapLibre `OfflineManager.createOfflineRegion` along the route bbox, or a lightweight in-app prefetcher hitting the tile URLs ahead of the puck). Compare with how Google Maps/OsmAnd prefetch along the route.
- Done when: on the Denton route in hybrid at simulated 60 mph, no grey tile appears within the map area for 5 minutes (screenshot every 5 s, grey-pixel share of map area < 1 %); cache survives app restart.

## B6 — Starlink relay from the Pi (installed, untested end-to-end)
`/data/starlink/relay.sh on|off|toggle|state|find` on venus-4runner + Node-RED flow "Starlink relay" (`GET /starlink/<action>` on 127.0.0.1:1880, state polled every 60 s into `global.starlink`). Discovery scans wlan0's /24 for the ESPHome web API and caches the IP in `/data/starlink/relay.ip`. Not testable while the Pi was on the phone hotspot and the board on Everylink.
- Done when: with Pi and board both on Everylink, `relay.sh state` returns the JSON in < 5 s from cold (discovery) and < 1 s warm; `curl http://127.0.0.1:1880/starlink/toggle` twice leaves the state unchanged; then add a Starlink toggle to the TruckNav Power pane using the same REST calls.
- Follow-ups: `manual_ip` for the board on Everylink (or a router reservation), adopt into HA.

## B7 — Portrait: map is short when the panel is open
Not a bug, a limit: 555 px of map with the panel open in portrait. Consider a collapsed "mini" pane in portrait while navigating.

## B8 — Power strip SOC shows `--` while the SmartShunt reports a value
VictronConnect (BLE) showed SOC 26 %, 14.19 V, −0.40 A, −82.5 Ah on 2026-09-20 12:14; the strip showed SOC `--` with voltage/current present. So the SOC topic in `VenusClient` is wrong (likely needs the battery service instance, e.g. `N/<portal>/battery/<id>/Soc`, or `system/0/Dc/Battery/Soc`). Verify with `mosquitto_sub -t N/2ccf67855c49/#` on the Pi.
- Done when: strip SOC equals VictronConnect within 1 % for 5 minutes.

## B9 — Truck boards drop off Wi-Fi and never come back (2026-09-20)
Pi and relay board both went dark ~11:00 (hotspot vanished) and only returned after a power cycle. Pi: added `/data/starlink/wifi-watchdog.sh` (rescan + reconnect saved networks, bounce wlan0 after 5 min) and `iw dev wlan0 set power_save off`, both in `/data/rc.local`. Board: `EverythingPhone` added to `4runner.yaml` (priority 7), `use_address` removed. Still to watch: whether the board also needs a reboot-on-no-wifi (ESPHome `reboot_timeout` is 0s by operator choice).

## B10 — Implausible GPS fixes are trusted (2026-09-20)
Tablet indoors on USB, 4 satellites, mean C/N0 22: the gps provider delivered 33.1275,-96.2930 (55 mi from the real position), altitude 32 896 m, speed 33 m/s, hAcc 11.6. Ferrostar's AndroidLocationProvider forwarded it; routes were computed from it; the puck would have jumped 55 mi. Fix (S17.6): a plausibility gate in front of the location provider — reject fixes with altitude outside -500…6 000 m, speed > 60 m/s, hAcc > 150 m, or an implied jump > 250 km/h from the last accepted fix; log rejections to NavLog; show "GPS weak" in the status strip while rejecting.
- Done when: replaying a captured garbage fix (emulator geo fix / `cmd location` test provider with alt 30 000) produces a `gps rejected` NavLog line and no puck/route change; a normal drive replay is unaffected.
