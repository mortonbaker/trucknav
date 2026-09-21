# Handoff boxes — 2026-09-20 19:00

Paste the whole box for your agent as its first message. Everything else is in `docs/PLAN.md` (§0 rules, §3 slices, §5 table) and `docs/AGENTS.md`.

## claude-vehicle — S20 "Usable by others"

```
You are claude-vehicle. Slice: S20 "Usable by others" — read docs/PLAN.md §0, §3 S20, §5 and docs/AGENTS.md §1–§7 first.
FIRST, same day, as a small separate merge: the Settings contract Astra codes against —
  com.morton.trucknav.settings.Settings { get(key), set(key, value), flow(key) } backed by files/settings.json;
  GET /api/settings (secret values masked to last 4), PUT /api/settings {k: v}; MCP get_settings, set_setting(key, value).
  Keys in use: tomtomKey, googleMapsKey, trafficProvider. Every user brings their own keys: no key ever lands in source or local.properties.
THEN the rest of S20: Settings pane (Places, Vehicle, Servers, Units, Voice/Auto-night moved from the Layers sheet, API token as QR + Regenerate, About),
  delete homeLat/homeLng (initial camera = last fix → extract centre), PUT/GET/DELETE /api/vehicle (PNG/JPEG ≤2 MB → 256 px files/vehicle.png, puck hot-reload),
  MCP upload_vehicle(path) with the image spec in its docstring, set_home/set_work, first-run screens, README "Set up for your own truck", docs/API.md.
Where: atlas01, new worktree:  cd ~/trucknav && git worktree add ~/trucknav-s20 -b settings-s20 main
Claim the tree: docs/claim.sh — then build: cd ~/trucknav-s20 && export ANDROID_HOME=$HOME/Android/Sdk && ./gradlew -q assembleDebug
Smoke on YOUR emulator: emulator-5554 on atlas01 (AVD trucknav-tab, profile ~/.config/emu/trucknav.env)
  ~/bin/emu.sh status trucknav
  adb -s emulator-5554 install -r -d app/build/outputs/apk/debug/app-debug.apk && ~/bin/emu.sh assets trucknav
  adb -s emulator-5554 forward tcp:18782 tcp:8782 ; TOK=$(grep ^apiToken= local.properties | cut -d= -f2-)
  curl -H "Authorization: Bearer $TOK" http://127.0.0.1:18782/api/settings
  curl -H "Authorization: Bearer $TOK" -X PUT -T vehicle.png http://127.0.0.1:18782/api/vehicle     # puck changes ≤2 s, survives force-stop
  A build with an EMPTY local.properties must boot to a usable map and generate a token (done-line a). grep -r homeLat app/ must be empty.
  docs/cockpit-smoke.sh s20 must be 13/13. Write docs/emu-settings.sh proving done-lines a–d; append the numbers to docs/SMOKE-TEST.md.
Tablet: ONLY the final install, under a lease: docs/tablet-lock.sh 100.95.16.47:5555 acquire claude-vehicle 15 "S20 install" || exit 2
  (serial R9PT207J6ZN when the USB cable is in). Never pm clear. Never cut its Wi-Fi.
Merge: versionCode = max across ~/trucknav*/app/build.gradle + 2; never commit in ~/trucknav while git status shows unmerged paths; log it in the AGENTS.md claims table.
Do not touch nav/TripBar.kt, nav/RoutePreview.kt, nav/SearchResults.kt, DemoNavigationScene.kt, NotNavigatingOverlay.kt (claude-nav has them for S19/S21/S23).
```

## astra — S22 "Traffic: TomTom only" (Google dropped 2026-09-20 19:50, operator decision)

```
You are astra. Slice: S22 "Traffic: TomTom + Google" — read docs/PLAN.md §0, §3 S22 (the Settings contract is at the end of it), §5 and docs/AGENTS.md first.
Rule of the slice: every user brings their own API keys. Keys come ONLY from Settings.get("tomtomKey") / Settings.get("googleMapsKey")
  (claude-vehicle ships that contract first; until it lands, code against the interface with a local stub that reads files/settings.json).
  Done-line (e): grep -rn "AIza\|tomtom.*key" app/src finds no literal key. No key in local.properties either.
Build: traffic/TrafficProvider { flowTileUrl, etaWithTraffic, incidents }; TomTomTraffic (flow raster tiles + Routing ETA); GoogleTraffic (computeRoutes TRAFFIC_AWARE, ETA only);
  setting trafficProvider = tomtom|google|off; raster flow layer above roads, toggle in Layers, auto-hidden offline, NavLog "traffic tiles=N" per day;
  each A/B/C preview card gets "· 19 min w/ traffic" when the provider answers within 3 s; trip-bar ETA uses the traffic number while <10 min old; Settings "Test key" per provider.
While waiting for the contract: merge S7 look-and-feel (branch ui-s7) to main as max(versionCode)+2 after docs/cockpit-smoke.sh 13/13.
Where: build01 (tailnet), clone at ~/trucknav (GitHub main — git pull first), branch traffic-s22.
  Build: cd ~/trucknav && export ANDROID_HOME=$HOME/Android/Sdk && ./gradlew -q assembleDebug
  (a debug build was started for you at 18:52 → ~/build-debug.log; if it failed, fix that first.)
Smoke on YOUR emulator: emulator-5554 on build01 (AVD trucknav-tab, ~/.config/emu/trucknav.env). NOT the tablet — S22 needs no GPS/TTS.
  ~/bin/emu.sh status trucknav
  adb install -r -d app/build/outputs/apk/debug/app-debug.apk && ~/bin/emu.sh assets trucknav      # assets must be re-chowned after every install
  adb forward tcp:18782 tcp:8782 ; TOK=$(grep ^apiToken= local.properties | cut -d= -f2-)
  curl -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" -X PUT -d '{"tomtomKey":"<your key>","trafficProvider":"tomtom"}' http://127.0.0.1:18782/api/settings
  Fake drive on I-35W: adb emu geo fix -97.3 32.9 ; ~/bin/emu.sh drive trucknav <polyline.txt> 3 ; screenshot shows coloured flow segments ≤5 s after Traffic on.
  Offline test is on the EMULATOR only: adb shell svc wifi disable → overlay hidden, 0 requests, no crash; adb shell svc wifi enable → back within 10 s.
  Write docs/emu-traffic.sh proving done-lines a–f; append the numbers to docs/SMOKE-TEST.md. Merge as max(versionCode)+2; claude-nav installs the merged build on the tablet.
Do not touch nav/TripBar.kt, nav/SearchResults.kt, NotNavigatingOverlay.kt; DemoNavigationScene.kt only to add the raster layer call; the preview-card text lives in nav/RoutePreview.kt (claude-nav's) — send the string, don't edit the file.
```

## claude-nav (this session) — merger

```
claude-nav: merges every `ready:` branch into main on build01 (--no-ff under the claim, max+2 versionCode, cockpit-smoke on an emulator, tablet install under a lease, push), keeps PLAN/AGENTS/HANDOFF current, and takes small fixes nobody owns. No slice of its own from 20:10.
```

## astra — S21 "Search along route" (assigned 2026-09-20 20:05, after the Google removal)

```
You are astra. Next slice: S21 "Search along route" — read docs/PLAN.md §0, §3 S21 and docs/AGENTS.md §8 first. Finish the Google removal on traffic-s22 and post `ready:` before starting this.
What Google does (verified): while navigating, category chips (Gas, Food, Coffee, Groceries); results near the corridor, each with its DETOUR time ("+3 min"); tap = becomes the next stop.
Build, on a new branch along-s21 from origin/main (worktree ~/trucknav-along on build01; FIRST: cp ~/trucknav/local.properties ~/trucknav-along/):
  nav/AlongRoute.kt (new, yours): sample the REMAINING route every ~10 km (route geometry from the current step onward), Photon query per sample with lat/lon bias
    (+ osm_tag=amenity:fuel | amenity:restaurant | amenity:cafe | shop:supermarket for the chips), dedupe by osm id, keep hits ≤ 2 mi from the polyline (distance-to-segment),
    detour minutes = matrix(now→hit) + matrix(hit→next waypoint) − matrix(now→next waypoint) via nav/Valhalla.kt matrixEtas / sources_to_targets; sort by detour; letters A–F.
  The add-stop box (NotNavigatingOverlay.kt, the `addingStop` block only — yours for this slice) gets the four chips above the field; PhotonSearch gets an optional
    `corridor: List<GeographicCoordinate>?` parameter so free text is biased along the route instead of at the puck. Rows show "+N min" instead of straight-line distance
    while navigating. Pick → viewModel.addStop(coordinate, label) (exists). Offline / server down → chips disabled with a one-line reason; no crash.
  Do NOT touch nav/TripBar.kt, nav/RoutePreview.kt, nav/SearchResults.kt, DemoNavigationViewModel.kt beyond addStop's existing signature (claude-nav's S19).
Done when (docs/smoke/s21-along.sh on YOUR emulator, emulator-5554 on build01, built on ~/.claude/skills/slice-build/scripts/smoke-lib.sh + android.sh):
  (a) home → Whole Foods route running (POST /api/navigate), chip "Gas" → ≥ 3 hits, every hit ≤ 2 mi from the polyline (log the distance per hit), first paint < 4 s;
  (b) each row shows "+N min" and N equals the matrix detour ± 1 min (log both);
  (c) pick B → NavLog `route stop-add` with B as the next stop, trip still NAVIGATING, camera back to following;
  (d) free text "Kroger" while navigating returns hits sorted by detour, not by distance from the puck (log the two orders);
  (e) Valhalla stopped on homebackup (docker stop valhalla; it self-restores) → chips disabled with the reason in the strip, 0 crashes;
  (f) crash gate 0. Evidence ~/evidence/s21-along-<tag>/; results appended to docs/SMOKE-TEST.md; PLAN.md status row updated.
Rules: ~/bin/pressure.sh --stop-idle --need 4G || exit 2 before any gradlew or emulator start; flock ~/.gradle/build.lock ./gradlew -q assembleDebug; one agent per emulator (5554 is yours; nav is on 5556);
  never the tablet; versionCode = max across every worktree on both hosts + 2; post `ready: along-s21 <code> <receipt>` in the AGENTS.md claims log — claude-nav merges and installs.
```
## astra-2 — S19 "Trip bar + stops" (assigned 2026-09-20 20:10)

```
You are astra-2 (a fresh agent). Slice: S19 "Trip bar + stops" — read docs/PLAN.md §0, §3 S19, §5 and docs/AGENTS.md §8, then docs/NAV-AUDIT.md §9 (what Google Maps and Tesla show). Load the slice-build skill (~/.claude/skills/slice-build) and follow it: write docs/smoke/s19-stops.sh from the done-lines BEFORE any code.
Where: build01. Worktree: cd ~/trucknav && git worktree add ~/trucknav-stops -b stops-s19 origin/main && cp ~/trucknav/local.properties ~/trucknav-stops/   (a worktree without local.properties builds a black map).
Your emulator: emulator-5556 on build01 — create it first: cp ~/.config/emu/trucknav.env ~/.config/emu/s6.env && sed -i 's/AVD=trucknav-tab/AVD=trucknav-s6/; s/PORT=5554/PORT=5556/' ~/.config/emu/s6.env && ~/bin/pressure.sh --stop-idle --need 4G && ~/bin/emu.sh create s6 && ~/bin/emu.sh start s6, then install the debug APK and ~/bin/emu.sh assets s6 (assets from ~/trucknav-assets; re-run assets after EVERY install). emulator-5554 belongs to astra (S22/S21): never touch it. Never the tablet.
What exists (read these first): DemoNavigationViewModel.kt — addStop()/stopsAhead/finalDestination/destinationName, the remainingWaypoints collector that logs `stop-passed`, onArrived()/dismissArrival() (TripState.Complete → card → stopNavigation after 10 s), NavLock.sync{} around every core call (B11 — keep it); nav/ArrivalCard.kt; nav/SearchResults.kt (lettered CircleLayer+SymbolLayer badges — copy the pattern for stop pins); nav/RoutePreview.kt badgePoint(); DemoNavigationScene.kt — NavigationViewComponentBuilder with withInstructionsView and withCustomOverlayView (add withProgressView there for the bar); nav/NavLog.kt.
Ferrostar 0.56 facts: TripState.Navigating has progress (distanceRemaining/durationRemaining to the FINAL destination only), remainingWaypoints, remainingSteps; the route's steps are all legs concatenated — a leg boundary is the step whose last geometry point is within 50 m of a waypoint. Leg-remaining distance = current step's remaining (progress.distanceToNextManeuver) + the following steps up to the boundary; duration by the same walk over step durations. Verify against Valhalla's per-leg summary (RouteCandidate has the response; log both).
Build: nav/TripBar.kt replaces Ferrostar's TripProgressView — line 1 `→ <next stop>` (the destination name when no stops), line 2 ETA · min · mi TO THAT STOP, small final ETA when stops exist, End button unchanged; tap → stop list (rows: number, name, ETA, ✕ remove) over the bar; nav/StopPins.kt — numbered pins 1,2,… + checkered flag at the destination, via mapContent in DemoNavigationScene; stop arrival — on remainingWaypoints drop: card "Arrived at <stop>" + voice "You have arrived at <stop>. Continuing to <next>." through AppModule.voiceGate (class arrival), auto-continue, card clears after 10 s; remove a stop — rebuild the waypoint list without it and NavLock.sync { ferrostarCore.replaceRoute(route) } (same path as addStop). New stop stays NEXT (Maps and Tesla agree).
Do NOT touch: NotNavigatingOverlay.kt addingStop block, PhotonSearch.kt (astra/S21), settings/ (vehicle/S20), traffic/ (astra/S22). Coordinate with S22 through docs/S22-INTEGRATION.md "Nav owner hooks": TripBar hands Traffic.setRemainingRoute() the remaining next-leg geometry on leg change and once per minute — wire the call, keep it null-safe.
Done when (docs/smoke/s19-stops.sh, emulator-5556; API: adb forward tcp:18782 tcp:8782, token from local.properties; route + stops via POST /api/navigate and /api/add_stop; fake drive with ~/bin/emu.sh drive s6 <polyline> 3 — get the polyline from Valhalla like ~/route-wholefoods.txt on atlas01, 322 "lng lat" lines):
  (a) trip with two stops → bar shows `→ <stop1>` and ETA/min/mi to stop 1, final ETA small, stop list has 3 rows with ascending ETAs (uiautomator text, screenshot);
  (b) map shows pins 1, 2 and a flag (screenshot + layer feature count from the dump/log);
  (c) fake-driving past stop 1 → NavLog `stop-passed`, the arrival spoken exactly once (GoogleTTSServiceImpl "Synthesis request" count +1), bar flips to `→ <stop2>` within 2 s (poll, log ms), no `deviation-handler` line;
  (d) ✕ on stop 2 → NavLog `route stop-remove … steps=`, bar and pins update, trip still NAVIGATING (state line absent);
  (e) next-stop numbers within 5 % of Valhalla's leg summary (log both numbers);
  (f) crash gate 0; state restored. Evidence ~/evidence/s19-stops-<tag>/, results table appended to docs/SMOKE-TEST.md, PLAN.md status row updated.
Rules: pressure.sh before every gradlew/emulator; flock ~/.gradle/build.lock ./gradlew -q assembleDebug; versionCode = max across every worktree on BOTH hosts + 2 (check `git ls-remote --heads origin` too); never commit in ~/trucknav; post `ready: stops-s19 <code> <receipt>` in the AGENTS.md claims log — claude-nav merges and installs.
```

## astra-3 — S23 a–d "Map control placement" (assigned 2026-09-20 20:10; S23e full-map mode is already done)

```
You are astra-3 (a fresh agent). Slice: S23 a–d "Map control placement" — read docs/PLAN.md §0, §3 S23, §5 and docs/AGENTS.md §8. Load the slice-build skill and write docs/smoke/s23-controls.sh from the done-lines BEFORE any code.
Best practice (Android Auto NavigationTemplate, Google Maps, Waze, Tesla agree): information left, actions right; actions in corners or one right-edge stack; nothing floating mid-edge; the puck's third of the screen stays clear. Today: Layers and Add-stop float at centre-left (Ferrostar InnerGridView centerStart in NotNavigatingOverlay.kt), recenter at centre-right; Ferrostar's own navigating layout puts overview/mute top-right and zoom on the right.
Where: atlas01 (it is back; one emulator max there). Worktree: cd ~/trucknav && git pull -q origin main && git worktree add ~/trucknav-controls -b controls-s23 origin/main && cp ~/trucknav/local.properties ~/trucknav-controls/. Emulator: ~/bin/pressure.sh --stop-idle --need 4G && ~/bin/emu.sh start trucknav (emulator-5554 on atlas01, profile ~/.config/emu/trucknav.env); ~/bin/emu.sh assets trucknav after every install. Never the tablet.
Build: move Layers + Add-stop into the top-right stack under Ferrostar's overview/mute (idle state: top-right stack = Layers; navigating: overview, mute, Layers, Add-stop); recenter into the bottom-right stack with zoom ±; 16 dp corner margins, 56 dp buttons, 12 dp gaps; portrait mirrors the same corners. Ferrostar's InnerGridView slots (topEnd/centerEnd/bottomEnd) and NavigationViewComponentBuilder are the levers; keep DemoNavigationScene's NavigationCameraOptions/ClampedInsets (S2) intact — the S2 puck criteria must still hold. Nothing may overlap the turn card, the trip bar (astra-2 is replacing it in nav/TripBar.kt — leave a 120 dp bottom-left reserve), the search box, the results card, the favorites tiles or the destination sheet.
Do NOT touch: nav/TripBar.kt, nav/StopPins.kt, DemoNavigationViewModel.kt (astra-2/S19); NotNavigatingOverlay.kt addingStop block + PhotonSearch.kt (astra/S21); settings/ (S20); traffic/ (S22). Your edits: NotNavigatingOverlay.kt grid slots, DemoNavigationScene.kt view-builder wiring, a new nav/ControlStack.kt if it helps.
Done when (docs/smoke/s23-controls.sh, emulator-5554 on atlas01; both orientations via `adb shell settings put system user_rotation 0|1`, restored after):
  (a) idle and navigating screenshots in both orientations: every button's uiautomator bounds are within 16 dp of a corner or inside the right-edge stack (compute from the dump; log each button's bounds);
  (b) no button bounds intersect the turn card, the progress bar, the search box, the results card, the tiles or the sheet in any of: idle, results shown, sheet open, navigating, add-stop open (dump-based rectangle test, one row per state);
  (c) every button ≥ 56 dp, gaps ≥ 12 dp (dump);
  (d) docs/s2-camera.sh still passes (puck position criteria);
  (e) crash gate 0. Evidence ~/evidence/s23-controls-<tag>/; results table appended to docs/SMOKE-TEST.md; PLAN.md status row updated.
Rules: pressure.sh before every gradlew/emulator; flock ~/.gradle/build.lock ./gradlew -q assembleDebug; versionCode = max across every worktree on BOTH hosts + 2; never commit in ~/trucknav; post `ready: controls-s23 <code> <receipt>` in the AGENTS.md claims log — claude-nav merges and installs.
```

