# TruckNav — build plan for the remaining features, sliced for one Opus session each

Written 2026-09-20 after the audit of v0.9.1. Each slice is self-contained: a fresh session with only this file, the repo, and the tablet can pick it up, finish it, and prove it. Slices are ordered by the operator's priority; S0 first because it fixes what the audit found broken.

## Common context every slice needs

- Repo: `atlas01:~/trucknav` (Ferrostar 0.56 fork; AGP 9.0.1, Gradle 9.2.1, Kotlin 2.3.20, JDK 21). Build: `cd ~/trucknav && ANDROID_SDK_ROOT=$HOME/Android/Sdk ./gradlew -q assembleRelease --no-daemon`. Bump `versionCode`/`versionName` in `app/build.gradle` every build.
- Install routine (every install resets the default home): `adb -s 100.95.16.47:5555 install -r app/build/outputs/apk/release/app-release.apk && adb -s … shell cmd package set-home-activity com.morton.trucknav/.MainActivity && cp …apk ~/apk-drop/trucknav-<ver>.apk`.
- Tablet: Galaxy Tab A7 Lite `100.95.16.47:5555` (tailnet). **Never `pm clear`** (wipes the 3 GB basemap). **Never cut its Wi-Fi from outside** — use `adb shell "nohup sh -c 'svc wifi disable; sleep 60; svc wifi enable' &"`.
- Driving the UI: `~/bin/ui.sh <serial> dump|find|tap "<label>"` (uiautomator; Compose labels are visible). Screenshots: `adb exec-out screencap -p`.
- Pass/fail gate for every slice: `adb logcat -d -b crash | grep -c "Process: com.morton.trucknav"` = 0 after the slice's smoke test, and every "done" line below is true in a screenshot or a log. Anything you had to touch by hand is re-run, not counted.
- Docs: `docs/SMOKE-TEST.md` (cockpit), `docs/BOOKS-PLAYER-PLAN.md` (books), `docs/books-smoke.sh`. Append results per version.
- Services: Valhalla `https://homebackup.tail00ae77.ts.net:8446` (from atlas01 use `--resolve homebackup.tail00ae77.ts.net:443:100.102.188.107`, MagicDNS is dead there); ABS `https://homebackup.tail00ae77.ts.net` (creds in `local.properties`); Venus MQTT `100.112.123.30:1883` portal `2ccf67855c49`.
- Design rules from the operator: Material icons only, no emoji; no Google; driving-first (big targets); nothing leaves the cockpit unless deliberately opening a foreign app.

---

# Status — 2026-09-20 (v0.13.1 on the tablet)

Done today: **S0, S1, S2, S3** (+ vehicle puck, search surface, Starlink control, R8/arm64 build 130 → 26 MB). S4 onward is untouched. Field bugs from the first real drive are in `docs/BUGS.md` and are scheduled as slices S11–S16 below, **after S4–S10**, except S11 (nav logging) which is cheap and should ship before the next drive so the bugs can be diagnosed.

Order of work from here:

| # | Slice | Size | Why this order |
|---|---|---|---|
| S11 | Nav event log (B0) | small | DONE v0.14.0. |
| S17 | Navigation, finished: no foreign navigator, alert prefs, favorites (Home/Work), favorites API + MCP | medium | Operator: before any other slice. |
| S4 | YouTube history, no Live/Shorts | small + sign-in | Needs the operator's Google sign-in on the tablet. |
| S5 | Books offline downloads | medium | Independent of the map. |
| S9 | Harness + runbook | small | Fold the S0–S3 scripts into one `cockpit-smoke.sh`; the skill draft exists in the handoff. |
| S7 | Look and feel (icon, palette, typography) | small | Cosmetic; after the behaviour is right. |
| S6 | On-device routing | large | Two sessions; biggest single win for no-signal driving. |
| S8 | Settings pane | small | Operator said later. |
| S10 | Pi ACL, Photon self-host, Kindles | operator | Desktop tasks. |
| S15 | Tile prefetch along the route (B5) | medium | Research first (MapLibre prefetch, ambient cache, along-route warmup). |
| S16 | Relay board on every network + Pi flow verified (B6) | small | Vehicle pane shipped v0.14.0; yaml flash needs the truck at home. |
| S18 | Voice commands (STT + intents, Spark fallback) | large | After S17. |

Housekeeping, not slices: DHCP reservation / `manual_ip` for the relay board; adopt it into HA; the tablet's Venus MQTT link showed `MqttException` while on the phone hotspot on 2026-09-20 (Pi reachable over the tailnet at that moment — investigate under S16).

---

## S0 — Audit fixes (small) — DONE 2026-09-20, v0.9.5

**Goal:** the two things the audit found broken, plus record the test results.

1. Books session must survive losing audio focus. Today: Finamp starts → book pauses → media3 stops the service → session vanishes → Books "Play" is dead. Fix: keep the service alive while a book is loaded (`MediaSessionService` should not stop on pause; hold the session; foreground notification stays), and make the pane's Play with no live session **reopen the last book** (`BooksPlayerService.play(lastBookId)` from prefs).
2. Music/Books pane in portrait: art box overlaps title/controls. Fix the layout (constrain the art with `weight` + `aspectRatio` inside a column that reserves the controls first, or cap art height at 45% of pane).
3. Append the 0.9.x results to `docs/BOOKS-PLAYER-PLAN.md` (D1–D11 with the numbers already measured) and `docs/SMOKE-TEST.md` (surfaces run so far).

**Done when**
- D9 passes as written: Finamp playing → tap book → book plays, Finamp paused; then tap Music Play → music plays, book paused; both panes show their own item throughout. Log shows one BooksPlayerService instance for the whole sequence.
- Books pane with no session: tapping Play starts the last book within 5 s (server progress honoured).
- Portrait screenshot of Music and Books panes: title and all four controls fully visible, art not overlapping any text.
- Both docs have a dated results table.

**Smoke:** `docs/books-smoke.sh` + D9 sequence + portrait screenshots (`adb shell settings put system user_rotation 0`, then 1 to restore).

---

## S1 — Immersive cockpit + status strip + rail cleanup (small) — DONE 2026-09-20, v0.10.0

**Goal:** no Android system bars; a thin status strip of our own; rail shows the thumbnail only.

- `MainActivity`: `WindowInsetsControllerCompat.hide(systemBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; re-hide on focus regain; keep `navigationBarsPadding` out of the layout now.
- `StatusStrip` composable, 28 dp, top of screen, both orientations: clock, Wi-Fi SSID or "no Wi-Fi", tailnet (Tailscale connected? read via `ConnectivityManager` for a VPN transport), GPS fix age, tablet battery %, charging. Material icons.
- Rail: remove play/next IconButtons under the thumbnail; thumbnail tap still opens the matching pane.

**Done when**
- Landscape and portrait screenshots show no system status bar and no navigation buttons; the strip is present at the top with all five fields populated (values match `dumpsys battery`, `dumpsys wifi`).
- A swipe from the bottom (`adb shell input swipe 400 790 400 600`) shows system bars, and they are gone again within 3 s (screenshot at +4 s).
- Rail region contains exactly one thumbnail and no transport icons (uiautomator dump has no "Play/Pause"/"Next" content-desc inside the rail bounds).
- Power strip fully visible above the bottom edge in landscape.

---

## S2 — Map centering and camera padding (small) — DONE 2026-09-20, v0.10.5

**Goal:** the map's "center" is the center of the visible map area, not the screen, in every layout state.

- Pass the side panel/strip dimensions into Ferrostar's navigation map as camera padding (`NavigationMapState` / `MapOptions` padding, or MapLibre `CameraPadding`), recompute when the panel opens/closes or orientation changes.
- Not-navigating recenter and navigating puck placement both respect it.

**Done when**
- Landscape, panel open: tap recenter; puck x is within ±5% of the map area's horizontal center (map area = between rail and panel), measured from a screenshot. Same with panel closed.
- Navigating (start the Denton route): the puck sits in the lower third of the *map area*, not under the panel, in both panel states.
- Portrait: same test vertically (map area above the panel).

---

## S3 — Map style switcher: light, dark, satellite, hybrid, terrain (medium) — DONE 2026-09-20, v0.12.2

**Goal:** off-road and satellite views without breaking offline.

- Styles as JSON on the tablet (served by `LocalAssetServer`):
  - `light`, `dark`: existing Protomaps (offline).
  - `satellite`: raster source Esri World Imagery `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` (online, attribution required).
  - `hybrid`: satellite + Protomaps roads/labels layers on top (roads/boundaries/places from the offline basemap, imagery online).
  - `terrain`: Protomaps + hillshade from AWS Terrarium DEM `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` as `raster-dem` → `hillshade` layer (online), plus contour-free; label "Terrain".
- Switcher: a Material `Layers` button on the map (top-left, big) → bottom sheet with five large tiles. Persist choice. Ferrostar gets the style via `BaseStyle.Uri`; switching must not drop an active route.
- Cache: MapLibre's ambient cache handles recently viewed satellite/terrain tiles; explicit region download is S5-adjacent and out of scope here.

**Done when**
- Five options visible in the sheet; selecting each changes the map within 3 s; screenshots per style show: imagery (satellite), imagery with white road lines and labels (hybrid), shaded relief (terrain — verify on a hilly spot, e.g. Wichita Mountains 34.73,−98.60), the two vector looks.
- Choice survives force-stop.
- With the self-restoring Wi-Fi cut: light/dark keep rendering; satellite/terrain show already-cached tiles and grey elsewhere, no crash, no dialog.
- Start a route on light, switch to hybrid mid-route: route line and turn card persist.

---

## S4 — YouTube: your history, no Live/Shorts (small, needs operator sign-in)

**Goal:** the pane opens on your own history/subscriptions; Live and Shorts never show; audio keeps playing while you use the map.

- Operator signs in once via the **You** tab (WebView with Chrome UA). If Google refuses, fall back to GrayJay as the YouTube rail target and record why.
- Dump the real DOM after sign-in (`adb shell` + `evaluateJavascript` logging `document.body.innerHTML` slices) and write selectors that actually match the pivot bar, chips, and shelves. Inject on every navigation (`WebViewClient.doUpdateVisitedHistory`) not only page finish (YouTube is an SPA).
- Default landing: `/feed/history`; toolbar: Home, Subscriptions, History, Back.

**Done when**
- After sign-in, History lists the operator's real watched videos (screenshot).
- uiautomator/DOM check: no visible element whose text is exactly "Live" or "Shorts" on Home, Subscriptions, History after 3 navigations.
- Start a video, switch to Map pane for 60 s: audio still audible (rail thumbnail shows the video; `dumpsys media_session` shows a WebView/Chrome session `PLAYING`).

---

## S5 — Books offline downloads (medium)

**Goal:** a downloaded book plays with no network at all.

- "Download" on the book's art view: fetch every `audioTracks[].contentUrl` to `filesDir/books/<id>/<index>.<ext>` with a progress bar; store the play-session JSON alongside. Player prefers local files when present (same timeline math). Progress sync still runs when online; queued syncs flush when back.
- "Delete download" and a storage line ("2.1 GB used, 12 GB free") in the Books browser.

**Done when**
- Download a 2-track book; `ls filesDir/books/<id>` shows the files with sizes matching `Content-Length`.
- Self-restoring Wi-Fi cut for 5 minutes mid-book (downloaded): playback never stalls, crosses a track boundary, position monotonic in `dumpsys media_session`.
- After Wi-Fi returns, server progress equals the pane position within 20 s (queued syncs flushed).
- Delete removes the files and the book falls back to streaming.

---

## S6 — On-device routing (large)

**Goal:** routes with no connectivity.

- Option A: Valhalla on-device via JNI (port the `:routing:engine-valhalla` module from noonr48/aus-roads; NDK build on atlas01). Tiles: reuse `/srv/valhalla/custom_files/valhalla_tiles` (2.6 GB) pushed to the tablet. Ferrostar: custom `RouteProvider` that calls the local engine and returns OSRM-format JSON.
- Option B (fallback if A won't build in a session): GraphHopper on-device with a prebuilt graph for the four states.
- Keep the server provider as primary when reachable; local as fallback; show which was used in the turn card footer.

**Done when**
- Self-restoring Wi-Fi cut, then search is unavailable (expected) but a long-press on the map → route computes in ≤ 10 s from home to a point 50+ mi away.
- Local route length within 5% of the server route for the same pair (log both).
- Two consecutive local routes without crash; memory from `dumpsys meminfo` under 600 MB for our process.

---

## S7 — Look and feel (small)

Implementation on `ui-s7` (2026-09-20), physical acceptance pending. The pre-edit [control contract](S7-CONTROLS.md) defines preserved behavior and V1–V6 acceptance; [workflow](SLICE-WORKFLOW.md) defines lifecycle gates. Coverage expands the original six-pane wording to all seven current panes, both orientations and font scales 1.0/1.3. See [implementation receipt](S7-IMPLEMENTATION.md). This is not a DONE marker.

**Goal:** it looks like one product.

- App icon: adaptive icon, Material `navigation` glyph on the rail's dark, no default robot.
- Theme: one dark palette (rail `#10141a`, cards `#1a2028`, accent `#1f5f8b`, text white/`#9aa4b2`) applied to every pane, tabs, buttons, progress bars; no Material default surfaces.
- Driving typography: min 16 sp body, 20 sp titles, 48 dp targets.

**Done when**
- Launcher chooser and Recents show the new icon.
- Screenshots of all six panes: no purple/lavender Material defaults; every tappable target ≥ 48 dp (spot-check with uiautomator bounds).

---

## S8 — Driving-friendly Settings pane (small, operator said "later")

**Goal:** the few settings you'd change in the truck, big and safe.

- Map style, default book speed, units (mi/km), Valhalla/Photon/ABS endpoints (read-only display + "test" button that shows reachability), "Open Android Settings".

**Done when**
- All six controls present; endpoint tests report 200/timeout correctly (unplug the server to prove the failure path); changes persist across force-stop.

---

## S9 — Test harness and runbook (small)

**Goal:** anyone (or any session) can prove the app works in ten minutes.

- `docs/cockpit-smoke.sh`: boot check, map render (tile requests ≥ 10), Denton route, End, power strip live, each pane opens, overlay over OsmAnd, crash gate — prints a pass/fail table.
- `docs/books-smoke.sh`: already exists; add D6/D8/D9.
- `docs/RUNBOOK.md`: install/upgrade routine, permissions grants (listener, usage stats, overlay), asset push, known-gaps list.

**Done when**
- Both scripts run end to end from a clean shell on atlas01 with no hands on the tablet and print only PASS lines on the current build (any FAIL is a real defect, not a harness bug).

---

## S10 — Infrastructure around the truck (small, mostly operator actions)

- Tailscale ACL: `tag:venus`, only the tablet may reach the Pi, Pi initiates nothing (desktop paste; JSON already written in this thread).
- Photon self-host on homebackup (optional; removes the komoot dependency).
- Kids' Kindle track (separate project; guide is live at `https://studio-pc.tail00ae77.ts.net:8482/`).

**Done when**
- `tailscale status` on the Pi shows only the tablet as a peer that can reach it (`tailscale ping` from atlas01 to the Pi fails, from the tablet succeeds).

---

## Sizing

| Slice | Size | Depends on |
|---|---|---|
| S0 audit fixes | small | — |
| S1 immersive + strip + rail | small | — |
| S2 map centering | small | S1 (layout changes) |
| S3 style switcher | medium | S2 |
| S4 YouTube history | small + operator sign-in | — |
| S5 books offline | medium | S0 |
| S6 on-device routing | large | S3 (style/asset plumbing) |
| S7 look and feel | small | S1 |
| S8 settings pane | small | S3, S7 |
| S9 harness + runbook | small | S0 |
| S10 infra | small / operator | — |

"Small" = one focused Opus session; "medium" = one session with a build-and-test loop; "large" = expect two.

---

# Bug slices (from `docs/BUGS.md`, 2026-09-20 drive)

## S11 — Navigation event log (B0, small) — DONE 2026-09-20, v0.14.0
**Goal:** a drive leaves evidence. Rolling `files/nav-YYYYMMDD.log` + logcat tag `NavLog`: route requests and Valhalla summary (distance, duration), every visual/spoken instruction (text, maneuver type/modifier, distance-to, step index, road, snapped location, bearing), trip progress every 10 s, deviation/reroute, start/stop with caller, mute toggles. 5 MB cap, 7 days.
**Done when** a 5-minute drive yields a log with all of the above, and B1–B4 can each be answered from it; `adb pull` documented in the runbook.

## S12 — End Navigation ends navigation (B1, small–medium) — RESOLVED 2026-09-20: root cause was OsmAnd navigating in the background (see BUGS.md); guard kept
**Goal:** after End, nothing reroutes. Prime suspect (from source, 2026-09-20): `AppModule`'s `RouteDeviationHandler` (deviation → `GetNewRoutes`) + `AlternativeRouteProcessor` (`replaceRoute(routes.first())` with no navigating check) — a reroute reply arriving after End restarts navigation to the old destination, and a stale route explains B2/B3 too. Fix: guard on `core.state.isNavigating`, cancel in-flight fetches on End; also audit notification 501, `selectedDestination`, the S2 orientation recenter.
**Done when** after End: `isNavigating()` false within 1 s, notification gone, zero `fetching route` for 5 min of driving, bar and turn card gone — proven from the NavLog and by `s2-camera.sh`'s end step + 60 s watch.

## S13 — Turn direction and ETA correctness (B2, B3, medium) — RESOLVED 2026-09-20: OsmAnd's voice, TruckNav's log was correct
**Goal:** what is said and shown matches the route. Use the NavLog to find which route/step produced "turn right on 377" (left in reality) and the 32 mi / 40 min vs 8.8 mi / 11 min split (two live routes? trip-remaining vs route summary?).
**Done when** for every maneuver on a test drive banner text == spoken text == polyline turn direction at the maneuver point; one active route; bar values within 2 % of that route's remaining distance/duration.

## S14 — Mute works (B4, small) — RESOLVED 2026-09-20: the unmuted voice was OsmAnd; re-verify TruckNav mute in S17
**Done when** tapping Mute logs `isMuted=true`, the next instruction produces no TTS (`TextToSpeechManagerPerUserService` shows no speak), the icon shows muted, and it survives rotation and pause/resume.

## S15 — Tile prefetch along the route (B5, medium)
**Goal:** satellite/hybrid never pops in behind the vehicle. Research first: MapLibre `prefetchZoomDelta`, raster `maxzoom` overzoom, 512-px tiles, ambient cache size (`OfflineManager.setMaximumAmbientCacheSize`, default 50 MB → ≥ 500 MB), and warming tiles along the route polyline ahead of the puck (offline region for the route bbox or an in-app prefetcher).
**Done when** on the Denton route in hybrid at simulated 60 mph, grey-pixel share of the map area stays < 1 % for 5 minutes (screenshot every 5 s); cache survives restart.

## S16 — Vehicle switches on every network (B6, small) — Vehicle pane shipped in v0.14.0; yaml flash + Pi verification pending
**Goal:** every relay controllable from TruckNav wherever the truck is, on a dedicated **Vehicle** pane (rail item, Material ToggleOn): grid of >= 76 dp tiles built from the board's own switch list (names come from `4runner.yaml`), icon + name + state, one tap, greyed with a reason when unreachable, hold-to-confirm only for relays that cut critical power. Starlink keeps its status cell in the power strip; Rear Lights (Relay 2) is the second first-class tile. Add `EverythingPhone` (priority 7) to `4runner.yaml` and OTA-flash from the ESPHome dashboard with the truck at home; then verify `/data/starlink/relay.sh` + the Node-RED `/starlink/<action>` flow with Pi and board on `Everylink`; then add Relay 2 (rear lights) etc. to the Power pane.
**Done when** with the dish off and the truck away from home, the Power strip's Starlink cell reads OFF (not `--`), a tap turns it ON within 2 s, `Everylink` appears in the Pi's scan within 5 min, and a second tap returns it to OFF with the cell still correct; `relay.sh state` answers in < 5 s cold / < 1 s warm.

## S17 — Navigation, finished (medium–large) — operator: "before any other slice"; full audit and order in `docs/NAV-AUDIT.md` §8
**Goal:** TruckNav is the only voice in the truck and gets you to the usual places in one tap.
- **No foreign navigator**: OsmAnd disabled on the tablet (`pm disable-user net.osmand.plus`; keep the APK for emergencies, re-enable by adb). App-level guard: on TruckNav start, if any other package holds a navigation foreground service / `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` focus, show a one-tap "Stop OsmAnd" banner.
- **Alert preferences** (Android Auto style): a Settings section listing the announcement classes Ferrostar/Valhalla can produce (turns, lane guidance, continue-for, arrival, deviation/reroute, speed limit change, and — once verified whether Valhalla emits them — railroad crossing / crosswalk / toll / ferry), each with a big toggle; muted classes never reach TTS or the banner. First step: log every instruction class for a week to see what actually fires.
- **Mute** re-verified against TruckNav's own TTS (log `isMuted=true` → no `speak` from our uid).
- **Favorites**: Home, Work + named places, stored in `files/favorites.json`; one-tap tiles at the top of the search results while not navigating ("Home 12 min"), long-press a result / the dropped pin → "Save as…". 76 dp tiles, Material icons.
- **Favorites API + MCP**: the app runs a small HTTP API on the tablet (loopback + LAN, token in `local.properties`): `GET/POST/DELETE /api/favorites`, `POST /api/navigate {lat,lng|favorite}`, `GET /api/state` (navigating, route summary, position). An MCP server on atlas01 wraps it (`trucknav-mcp`, tools: list_favorites, add_favorite, navigate_to, status) so favorites can be added by an agent when the tablet is on the tailnet/home LAN.
**Done when** (a) `dumpsys audio` never shows a non-TruckNav navigation-guidance focus during a 20-minute drive; (b) each alert class toggled off produces no TTS and no banner on a drive that passes at least one instance; (c) Home tile: tap → route starts within 3 s, ETA shown; (d) `curl -H "Authorization: Bearer …" http://<tablet>:8782/api/favorites` lists what the UI shows, and an MCP `add_favorite` appears on the tablet within 5 s.

## S18 — Voice (large, later)
Push-to-talk / wake word → on-device STT (Vosk/whisper.cpp small) with a home fallback (Spark over the tailnet) → intents: navigate to <favorite|place>, toggle <relay>, play <book|music>, style <x>, mute. Not before S17.
