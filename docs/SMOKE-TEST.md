# TruckNav — surfaces, smoke tests, and pass/fail criteria

Version this applies to: 0.6.0 (2026-09-19). Device: Galaxy Tab A7 Lite `100.95.16.47`, landscape in the 4Runner mount.

Every test below is run the same way: from atlas01, `adb -s 100.95.16.47:5555` drives the screen with `~/bin/ui.sh` (uiautomator taps by label) and `exec-out screencap`, then the screenshot is read. A test **passes only if the stated observation is true in the screenshot or the log**; nothing is inferred. Before each pass: `adb logcat -c` and `adb logcat -c -b crash`. After each pass: `adb logcat -d -b crash | grep -c "Process: com.morton.trucknav"` must be `0` — any nonzero is an automatic fail for the whole pass, whatever else looked right.

## 1. Surfaces (everything the app shows)

| # | Surface | What it is | Data source |
|---|---|---|---|
| S1 | Rail | Left column: Map, Music, Books, YouTube, Power, Apps; mini now-playing at the bottom (art, play/pause, next) | `SessionWatcher` (any media session on the device) |
| S2 | Map pane | Ferrostar/MapLibre map, search field, recenter button; navigation UI (turn card, route line, ETA bar, end button, mute) once a route is active | Offline PMTiles via loopback server; Valhalla on homebackup; Photon search |
| S3 | Power strip | One row under the map, always visible: SOC, battery V/A, Solar W, Alternator W, Load W, Net in/out W, Link (charger state / offline / stale) | Venus MQTT |
| S4 | Music pane | Big art, title, artist, progress, prev / play-pause / next, Library button | now-playing from `SessionWatcher`; library from Finamp's MediaBrowserService |
| S5 | Books pane | Same as S4 with back-30s / forward-30s instead of prev/next | Audiobookshelf's MediaBrowserService |
| S6 | Library browser | List with back arrow and title; folders drill in, tracks play and return to the art view | MediaBrowserService of the pane's source |
| S7 | Power pane | Full detail: SOC big, battery state, time-to-go, Battery / Sources / Loads groups | Venus MQTT |
| S8 | Apps pane | Grid of external apps (Music, Audiobooks, YouTube, OsmAnd, Venus, Settings); dimmed when not installed | PackageManager |
| S9 | YouTube | Rail item launches NewPipe full-screen; its audio shows in S1/S4 when you come back | NewPipe media session |
| S10 | Overlay dock | Vertical pill (home + app shortcuts) over foreign full-screen apps only; draggable; remembered position | `OverlayService` |
| S11 | Overlay now-playing bar | Art/title/controls over foreign apps while something plays, hidden over the player itself and over TruckNav | `OverlayService` + `SessionWatcher` |
| S12 | Boot | Tablet boots straight into TruckNav (HOME app); overlay service starts on boot | `BootReceiver`, default-home setting |

## 2. Smoke tests

Each row: **Steps** → **Pass criterion** (falsifiable) → **Fail looks like**.

### S12 Boot / home
1. `adb reboot`; wait 90 s; screenshot.
   - Pass: focused window is `com.morton.trucknav/.MainActivity`, rail visible, map tiles drawn (street names legible), power strip present.
   - Fail: Samsung launcher, "Select a Home app" dialog, grey map, missing strip.
2. Press HOME from any foreign app (`adb shell input keyevent KEYCODE_HOME`).
   - Pass: TruckNav in front within 2 s.

### S2 Map + navigation
3. Cold start with Wi-Fi and Tailscale up. Screenshot after 15 s.
   - Pass: map centred on GPS (or fallback homeLat/homeLng from local.properties if no fix), street labels rendered, blue dot visible. Log: `LocalAssetServer` shows ≥ 10 `pmtiles range=` requests.
   - Fail: beige/grey map without roads; zero tile range requests.
4. Type `Denton` in "Where to?", tap `Denton, Texas`.
   - Pass: within 10 s a route line is drawn, turn card shows a street name and distance, ETA bar shows time/duration/miles. No crash entry.
   - Fail: spinner forever (routing unreachable) → check `https://homebackup.tail00ae77.ts.net:8446/status` from the tablet; crash → `adb logcat -d -b crash`.
5. Tap End Navigation.
   - Pass: turn card and route gone, search field back, map still rendered.
6. Airplane-mode test: enable airplane mode, start step 4 again.
   - Pass (current design): search and routing fail *gracefully* (no crash), map still renders offline, an active route from before keeps guiding.
   - This is the known limitation until on-device routing lands.
7. Voice: start a route, wait for the first spoken instruction.
   - Pass: audible TTS within the first manoeuvre; Mute button toggles it.

### S3 Power strip
8. With the Pi reachable (`ping 100.112.123.30`): screenshot.
   - Pass: `Batt` shows a voltage between 10.0 and 15.0 V (matches the Pi's GUI within 0.1 V); `Link` is a charger state word (Off/Bulk/Absorption/Float) not "offline".
9. Unplug/disable the Pi's network for 3 min.
   - Pass: `Link` reads `stale` then `offline`; values stay as last seen or `--`; no crash. Reconnect → returns to live within 60 s.
10. Numbers vs truth: compare SOC/V/A with the Venus remote console at the same moment.
   - Pass: identical to the displayed precision. `--` only where the Pi itself has no value (currently SOC, time-to-go, alternator, loads — no shunt/inverter wired).

### S4 / S5 Now playing
11. From the Music pane, tap Library → Albums → any album → any track.
   - Pass: browser closes, art fills the pane, title/artist match Finamp's own now-playing, progress bar advances, rail mini strip shows the same art.
12. Tap play/pause twice, next once.
   - Pass: Finamp's playback state flips each time (verify with `adb shell dumpsys media_session | grep -A1 finamp | grep state=`); track title changes on next.
13. Start an audiobook in Audiobookshelf's own UI, return to the cockpit, open Books.
   - Pass: the book's cover/title shows; the two buttons are ±30 s and they move the progress bar by ~30 s.
14. Two sources: music playing, then start a book.
   - Pass: within 2 s the rail strip and both panes follow the *book* (the playing one). Pause the book, play music → follows music.

### S6 Library browser
15. Music → Library: root shows Albums / Artists / Playlists / Genres / Tracks; drill Albums → shows Noah Kahan albums with subtitles; back arrow returns one level; back at root returns to the art view.
16. Books → Library: root shows Audiobookshelf's tree (Continue / Library / …); drill one level; a playable item starts playback and returns to art view.
   - Fail: "Connecting to Books…" forever → ABS not running / service not exported; check `adb shell dumpsys package com.audiobookshelf.app | grep MediaBrowserService`.

### S7 Power pane
17. Rail → Power.
   - Pass: SOC big number (or `--`), state text (Idle/Charging/Discharging), Battery/Sources/Loads sections all present, values consistent with S3.

### S8 Apps
18. Rail → Apps: six tiles; OsmAnd tile opens OsmAnd full-screen; the overlay dock (S10) appears within 3 s; tapping its house icon returns to TruckNav.
   - Pass: both transitions happen; the dock is *not* visible while TruckNav is in front.

### S9 YouTube
19. Rail → YouTube: NewPipe opens. Play any video, switch to background playback (NewPipe: tap the headphone icon), press HOME.
   - Pass: rail mini strip shows the video's title/thumbnail; play/pause works from the rail; Music pane shows it as now playing.

### S10 / S11 Overlays
20. In OsmAnd with music playing: dock pill and now-playing bar both visible; drag each; open Finamp → bar hides (player is in front), dock stays; return to TruckNav → both hidden.
   - Pass: exactly that visibility matrix. Positions survive `adb reboot`.

### Stability
21. 30-minute soak: route active, music playing, Pi connected. Every 5 min: screenshot + crash count.
   - Pass: 0 crash entries, strip still `live`, progress bar still moving, map still following.

## 3. Known gaps (so a failure is classified correctly)

- Routing and search need connectivity (tailnet → homebackup). Not a bug until on-device Valhalla ships.
- SOC / time-to-go / alternator / load are `--` because nothing on the Victron bus reports them yet. Not a bug.
- (fixed 0.10.0) system bars are hidden; the power strip owns the bottom edge.
- App icon is the default Android robot.
- Theme: the right pane uses Material defaults, not the rail's palette.

## 4. YouTube: history without live

NewPipe has no Google sign-in, so it cannot show *your* YouTube history. Two routes:
- **GrayJay (FUTO)** — FOSS, signs in to your YouTube account through its plugin, syncs your subscriptions and history, no ads, and Live can simply be ignored. Would replace NewPipe on the YouTube rail item. Recommended if "my history" is the requirement.
- **NewPipe + Google Takeout** — import subscriptions once, history stays local to the tablet. No account on the device. Fine if "my subscriptions" is enough.

## 5. Results log

### 2026-09-20 — v0.9.5 (S0)

| Surface | Test | Result |
|---|---|---|
| S1 boot / home | app is default HOME, survives `am force-stop` + HOME | PASS |
| S2 map | basemap renders at home, search box present, locate button | PASS (viewport centering + styles deferred to S2/S3) |
| S3 power strip | Batt 14.16 V / −0.3 A, Solar 0 W, Net out 4 W, Link Off when the Pi is unplugged | PASS (SOC/Alt/Load `--` by design) |
| S4 Music pane | Finamp track, big art, prev/play/next, Library, progress | PASS, landscape + portrait |
| S5 Books pane | own player, ±30, speed ladder, Library, play-last from cold | PASS, landscape + portrait (books-smoke D1–D11) |
| S6 library browser | ABS Continue/Library grid, tap → plays | PASS |
| S7 power pane | full detail page | PASS |
| S8 apps | cockpit panes + Settings only | PASS |
| S9 YouTube | history feed loads; Live/Shorts still visible (CSS selectors pending sign-in) | PARTIAL → S4 |
| S10/S11 overlays | dock pill + now-playing over OsmAnd; hidden over self; Settings hides overlays (Android) | PASS |
| Stability | 0 crash entries across all S0 runs; 30-min soak not yet run | PASS / soak pending (S9) |

Scripts: `docs/books-smoke.sh`, `docs/d9.sh`. Screenshots: `~/s0-*.png` on atlas01.

### 2026-09-20 — v0.10.0 (S1 immersive + status strip)

| Check | Result | Evidence |
|---|---|---|
| No system status bar / nav buttons | PASS | `s1-land.png`, `s1-port.png`: first row is our strip, last row is the rail/power strip |
| Strip fields populated | PASS | 8:36 / `Everything` (dumpsys wifi SSID "Everything") / `tailnet` (connectivity shows tun0) / `GPS` (fix < 10 s) / `100%` charging (dumpsys battery level 100, AC powered) |
| Swipe reveals bars transiently | PASS | `input swipe 400 795 400 600`: +0.7 s shows Android status + nav bars over the app, +4 s screenshot identical to before the swipe |
| Rail has no transport icons | PASS | uiautomator: Play/Pause `[1019,685]`, Previous `[916,691]`, Next `[1135,691]` — all in the pane (x ≥ 916); rail is x < 116 and holds only the six destinations + thumbnail |
| Power strip fully visible in landscape | PASS | strip bottom edge at y=800 with all seven labels and values readable |

Implementation: `MainActivity.hideSystemBars()` (`WindowInsetsControllerCompat`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, re-applied in `onResume` and `onWindowFocusChanged`); `StatusStrip.kt` (28 dp; Wi-Fi via `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)`, tailnet via a `TRANSPORT_VPN` callback, GPS age via a 5 s `LocationManager` listener, battery via the sticky `ACTION_BATTERY_CHANGED`); `navigationBarsPadding` removed from the cockpit. Added `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`.

### 2026-09-20 — v0.10.5 (S2 map centering + camera padding)

Harness: `docs/s2-camera.sh <tag>` (drives search → Denton → Start navigation, rotates, toggles the panel) + `docs/puck.py <png> <map bounds>` (finds the puck as the compact blue component ringed by white; the route line shares the puck's blue). Map bounds come from the MapLibre view's uiautomator node.

| State | Puck position in map area | Result |
|---|---|---|
| Browsing, landscape, panel open (734×688) | x 49.9 % / y 49.9 % | PASS |
| Browsing, landscape, panel closed (1223×688) | x 50.0 % / y 49.9 % | PASS |
| Browsing, portrait, panel closed (800×1111) | x 50.0 % / y 50.0 % | PASS |
| Browsing, portrait, panel open (800×555) | x 49.9 % / y 49.9 % | PASS |
| Navigating, landscape, panel open | x 74.9 % / y 74.7 % (was 95.6 % on 0.10.1 — under the panel edge) | PASS |
| Navigating, landscape, panel closed | x 75.0 % / y 74.8 % | PASS |
| Navigating, portrait, panel closed | x 50.0 % / y 78.9 % | PASS (lower third) |
| Navigating, portrait, panel open (555 px map) | x 50.0 % / y 57.8 %, fully visible above the road pill + arrival bar | PASS (bottom 175 dp is Ferrostar chrome; lower third of the *visible* map) |
| Search → pick result | keyboard `mInputShown=false`, sheet + Start button visible | PASS (was: keyboard covered the sheet) |
| Rotation mid-route | camera back in `FOLLOW_USER_WITH_BEARING` (log line), pane selection kept | PASS |
| Crashes | 0 | PASS |

Root causes fixed:
- Ferrostar's `navigationCameraOptions()` derives padding from `LocalConfiguration` *screen* size (landscape: start = 50 % of screen width). With the map at 60 % of the screen that shoved the puck to the panel edge. `DemoNavigationScene` now measures the map with `onSizeChanged` and builds `NavigationCameraOptions` from the map's own size (landscape: start = w/2, top = h/2; portrait: top = h − 2·175 dp so the target clears the pill and arrival bar).
- Rotation recreates the activity and Ferrostar's map state comes back in `FOLLOW_USER`; a `LaunchedEffect(landscape)` recenters with `isNavigating = true` when a route is active.
- `pane` is `rememberSaveable` (rotation used to reset to Music).
- `PhotonSearch` clears focus + hides the IME on result pick / IME Search.

### 2026-09-20 — v0.11.1 (vehicle puck)

- `VehiclePuck.kt`: the 4Runner top-down PNG (`res/drawable-nodpi/vehicle_top.png`, 320 px, transparent) as a SymbolLayer, 84 dp, pitch + rotation aligned to the map, rotated by course-over-ground; position/heading tween over 1 s; route-snapped location while navigating. Ferrostar default puck is off (`showDefaultPuck = false`).
- Verified: browsing (top-down, on the home street) and navigating (tilted, on the route line, nose along the route) screenshots `~/puck-browse.png`, `~/puck-nav.png`; 0 crashes.
- Tablet GPS: SM-T220 has `android.hardware.location.gps`; `dumpsys location` shows a live gps fix indoors (13 satellites, hAcc 2.9 m). No external receiver or phone-shared location needed.
- Note: `docs/puck.py` (blue-dot finder) no longer matches the vehicle icon; it documents the S2 measurements on 0.10.x.

### 2026-09-20 — v0.12.2 (S3 map styles + search surface)

**Styles.** `docs/mkstyles.py` derives `style-satellite/hybrid/terrain.json` from the Protomaps light style (Esri World Imagery raster; hybrid = imagery + the offline roads/boundaries/labels recoloured white-on-black; terrain = light + AWS Terrarium `raster-dem` → `hillshade` inserted above every landuse fill, under water/roads — under the fills it was invisible inside the Wichita Mountains park polygon). Files live on the tablet next to the basemap and are served by `LocalAssetServer`; `MapStyle.kt` holds the enum, the persisted choice (`prefs map/style`) and the bottom sheet; Layers button at centre-start of the map in every state.

Harness: `docs/s3-styles.sh <tag>` + `docs/mapstat.py` (map-region colour statistics prove which style is on screen: light mean ≈ (217,218,212), dark ≈ (34,35,36), satellite/hybrid ≈ (113,114,89) with stddev > 55). Release builds refuse `run-as`, so persistence is proven by pixels, not by reading the prefs file.

| Check | Result |
|---|---|
| Sheet shows five tiles (uiautomator content-desc Light/Dark/Satellite/Hybrid/Terrain) | PASS (5) |
| Each selection changes the map within 3 s | PASS — screenshot at +3 s already classified as the new style for all five |
| Satellite = imagery; Hybrid = imagery + white roads + labels; Dark/Light vector | PASS (`s3-a-*.png`) |
| Terrain shows relief on a hilly spot | PASS after the layer-order fix — Mount Scott preview (`s3-terrain-scott.png`) shows shaded ridges; 69 terrarium tile requests in logcat |
| Choice survives force-stop | PASS — restart screenshot stats identical to hybrid (112,114,90 / 67,62,62) |
| Self-restoring Wi-Fi cut (45 s) on satellite | PASS — cached imagery still rendered, no dialog, 0 crashes; switching to Light during the outage renders the offline vector map |
| Route started on Light, switch to Hybrid mid-route | PASS — route line, turn card and End Navigation all present (`s3-a-route-hybrid.png`) |
| System bars stay hidden while the sheet is open | PASS (0.12.1: the sheet's own window gets the same insets controller) |
| Crashes across all runs | 0 |

**Search surface (operator report: "text is gray, can't read it over the map, can't see the box on satellite").** Verified on 0.12.0: the field was an outlined, transparent Material text field — placeholder and input rendered light grey over the pale basemap and the outline vanished over imagery.

Design rules applied (sources: [M3 search guidelines](https://m3.material.io/components/search/guidelines) — container/text ≥ 3:1, use a distinct surface container; [Design for Driving visual principles](https://developers.google.com/cars/design/design-foundations/visual-principles) — text/icons ≥ 4.5:1, primary text ≥ 24 dp, touch targets ≥ 76 dp with 23 dp gaps, negative polarity (light on dark) at night; [AAOS typography](https://developers.google.com/cars/design/automotive-os/design-system/typography) — nothing below 24 dp is glanceable; [AOSP driver-distraction guidelines](https://source.android.com/docs/automotive/driver_distraction/guidelines)): an **opaque, elevated dark pill** (#10141a, 8 dp shadow) so it reads identically over vector, dark and imagery basemaps; leading search glyph; **24 sp white input text**, placeholder #aab4c0 (7.7:1); trailing Clear (×) when there is text; result rows 72 dp, 22 sp white, opaque card. The whole pill is the tap target and focuses the field. Width: full map width minus margins (Google/Apple Maps convention) since it only exists while not navigating.

Done-criteria and measurements (`docs/search-smoke.sh` + `docs/contrast.py`, run on Light, Satellite and Dark):

| Criterion | Measured |
|---|---|
| Container colour identical on every basemap (proves opacity) | (16,20,26) on Light, Satellite, Dark — 91 % of field pixels |
| Input text contrast ≥ 4.5:1 | 18.5:1 (white on #10141a) on all three |
| Typed text actually visible | 2 191–2 648 bright text pixels inside the field on each basemap (was 0 on 0.12.0 Light) |
| Field height ≥ 64 dp | 85 px = 64.8 dp; width 676 px |
| Result row ≥ 72 dp | row card 90 px ≈ 69 dp visual (padding + 72 dp min-height clickable) |
| Clear (×) empties field, drops keyboard, clears results | keyboard `mInputShown=false`, results 0, on all three |
| Pick a result → keyboard down, field cleared, destination sheet with Start | PASS |
| Crashes | 0 |

Harness note: `ui.sh tap` substring-matched the wrong node more than once ("Map" hit the map view, "Mount Scott" hit the text field). `ui.sh tapx` (exact text/content-desc) added and used in the S3 scripts.

### 2026-09-20 — v0.14.0 (S11 nav log, S12 reroute guard, Vehicle pane)

- `nav/NavLog.kt`: rolling `files/navlog/nav-YYYYMMDD.log` + logcat tag `NavLog` — start/stop with caller, route summaries, every visual/spoken instruction with maneuver type/modifier and position, progress every 10 s, deviations, reroute decisions, mute. Verified: init line written on launch. Pull with `adb pull /sdcard/Android/data/com.morton.trucknav/files/navlog`.
- S12 guard: `AlternativeRouteProcessor` ignores reroute answers unless the core's trip state is `Navigating`; `startNavigation` drops a fetched route if navigation was stopped/restarted while fetching (generation counter). Needs a real drive to prove (criterion: zero `fetching route`/`replace` after `stop`).
- Vehicle pane (rail item): tiles built from the board's switch list; unreachable state renders "relay board not on this network" with the join hint (verified on the phone hotspot with the board in AP mode). Power strip Starlink cell now reads through the same client.
- Observed: on the phone hotspot the tablet cannot reach the Venus Pi either over the tailnet (100.112.123.30, 100 % loss) or the hotspot LAN (10.61.176.141) — hotspot client isolation suspected; power strip shows `Link offline`. Tracked under S16.
- R8/arm64 build: 26 MB, 75 s push over the hotspot.

### 2026-09-20 — v0.16.0–0.17.5 → merged as 0.18.0 (S17.1–S17.4, emulator-first)

Emulator: AVD `trucknav-tab` on atlas01 (Android 15 x86_64, 1340×800 @ 210 dpi, debug build, basemap pushed + chowned, `-dns-server 100.100.100.100`). Tablet: only for the OsmAnd test (USB, lease held).

| Item | Criterion | Measured | Result |
|---|---|---|---|
| S17.1 single navigator (tablet) | foreign navigator detected; manual Stop works; TruckNav start stops it | OsmAnd's paused route (Home → Hulen St, 31.4 mi / 37 min = the "32 mi / 40 min" ghost) resumed → banner "OsmAnd is navigating · Stop OsmAnd" → tap → `NavigationService` count 4 → 0, banner gone (`guard stopping OsmAnd: driver tapped Stop`, `foreign navigator gone`). Auto path: OsmAnd resumed, TruckNav route started → OsmAnd service count 0. OsmAnd then `pm disable-user`. | PASS |
| S17.2 search results | letters match badges; rows show distance + drive time; all rows visible; sorted by distance | "Whole Foods": rows A–E, `7.0 mi · 15 min`, `14 mi · 24 min`, `18 mi`, `18 mi · 26 min`, `22 mi · 33 min` (one ETA missing = Valhalla matrix null for that target); row bounds 174–269, 270–365, 366–461 (was 95/10/0 px before the grid fix); badges A–F on the map; landscape list-left, camera fits all badges + puck | PASS |
| S17.3 route overview | whole route inside the padded viewport | Ferrostar/MapLibre fit: route bbox x 754–1339 (clipped) and, with zero insets, x 130–1339 (still clipped, zoom ~2 levels too deep). Hand-computed Web-Mercator fit: route bbox x 802–1276, y 288–460 inside viewport x 749–1298, y 69–683 → inside = True, 86 % width fill (`docs/emu-overview.sh`) | PASS |
| S17.4 mute | muted → no TTS **and** no audio-focus request | before: muted leg = 3 instructions, 0 synthesis, **3 focus requests** (music ducked). after VoiceGate: unmuted leg 5/5/5; muted leg 3 instructions, 3 dropped, 0 synthesis, 0 focus | PASS |
| crashes | 0 | 0 on emulator and tablet | PASS |

Fake drive: `~/route-wholefoods.txt` (Valhalla polyline6 → 322 fixes) replayed with `emu.sh drive`; instructions fired at real geometry ("Turn left onto Oak Knoll Road", "… US Highway 377"…).

Infra fixes on the way: atlas01 `/etc/resolv.conf` was immutable with the home router first → tailnet names never resolved (the "MagicDNS flaky" mystery); now `100.100.100.100` first. Emulator FUSE makes shell-pushed dirs unreadable to the app → `emu.sh assets` chowns after push.

Open from S17: alert-class toggles UI (gate exists: `VoiceGate.disabledClasses`), favorites/Home/Work, route preview with ETA/alternates, favorites API + MCP, auto night mode, speed limits, add-a-stop, arrival flow.

## 2026-09-20 — S7 source implementation, physical run BLOCKED

See [S7 implementation receipt](S7-IMPLEMENTATION.md) and [pre-edit controls](S7-CONTROLS.md). Callback extraction: 40 bodies/references unchanged across 36 Kotlin files against main a09515f. Physical baseline, rendered contrast, all-pane interaction, launcher/Recents icon and restoration gates NOT RUN: main/source and tablet held by claude-studio for S5. No S7 APK installed; no DONE claim. Lint reports eight existing errors in unchanged files; raw report is `evidence/s7-source/lint.xml`.

## 2026-09-20 — S6 on-device routing, feature branch emulator acceptance

Branch `routing-s6`, base `2eaa770`, debug base version 0.18.0/code 52. AVD `trucknav-s6`, serial `emulator-5556`. No physical install and no main version bump. Controls documented before edits in [S6-ROUTING.md](S6-ROUTING.md).

| Criterion | Measured | Result |
|---|---|---|
| Server preferred when responsive | source=Server, 563 ms | PASS |
| Local route >50 mi, <=10 s | 287852.625 m (178.86 mi), 1168 ms cold actor, 407 ms second | PASS on emulator |
| Local/server distance within 5% | both 287852.625 m, delta=0 | PASS |
| Real HTTP server stalls: fallback <=10 s | 3478 ms including 3 s timeout | PASS |
| Two local routes, process <600 MiB | 257681 / 263401 KiB PSS | PASS on emulator |
| Navigation UI process <600 MiB | 275118 KiB PSS after controls/camera transitions | PASS on emulator |
| External network unavailable, map and route visible | app-UID-only emulator firewall, loopback allowed, screenshot retained, restoration verified | PASS |
| Existing controls preserved | Start, Mute/Unmute, Overview, Recenter, End; late immediate-cancel answer never starts guidance | PASS |
| Failure handling | Missing pack and outside coverage errors; Route unavailable dialog + Dismiss; no crash | PASS |
| Unit policy tests | 4 passed, including cancellation and malformed-response fallback policy | PASS |
| Build | x86_64 debug and ARM64 R8 release, vital lint included | PASS |
| Real tablet performance / GPS reroute / ARM64 runtime | Not run; main deployment and USB outage window required | OPEN |

Raw receipts and screenshot: `docs/evidence/s6/`. Native source evidence: `~/evidence/s6-native-20260920-152558/`; UI: `~/evidence/s6-ui-20260920-152655/`. Re-run with `bash docs/s6-smoke.sh native` / `ui` under the dedicated emulator lease.

Setup fixes: shell-pushed asset ownership repaired on emulator backing storage; Android's first-run full-screen tutorial dismissed. Earlier UI run had a gray PMTiles basemap and parser errors; later rendered-screen run had no such errors. Root cause is not proven and this observation remains an integration follow-up, not a claimed fix. No changes to LocalAssetServer or shared services.

### 2026-09-20 — v0.22.1 (S6 on-device routing integrated; tablet validation)

| Check | Result |
|---|---|
| Server-first route (tablet, LAN) | `routing source=Server elapsed_ms=450`, NAVIGATING |
| Server blocked (`docker stop valhalla` on homebackup, self-restoring after 100 s; tablet Wi-Fi untouched) → route to Ted Polk via the API | `routing source=On-device elapsed_ms=2070`, 49.3 mi / 18 steps, NAVIGATING, PSS 278 MB, 0 crashes |
| Offline pack | `routing/valhalla_tiles.tar` 2.69 GB pushed over USB at 46.7 MB/s, SHA-256 verified on device |
| Favorites via API after the merge | 8 favorites; tiles intact |

Caveat found (B10): both routes started from a garbage GPS fix (4 satellites indoors: 33.1275,-96.2930, alt 32 896 m, 33 m/s), so absolute distances (60 mi / 49 mi) reflect that origin, not home (server says home→Costco 14.1 mi, home→Ted Polk 33.7 mi).

### 2026-09-20 — v0.23–0.28.0 (S17.5–S17.11, emulator `trucknav-s6` / emulator-5556, debug x86_64)

Scripts: `docs/emu-favorites.sh` (`E=emulator-5556`), `docs/emu-arrival.sh`, `docs/emu-addstop.sh`; the rest are one-off `ui.sh` + NavLog reads quoted below.

| Criterion | Measured | Result |
|---|---|---|
| S17.5 favorites: Home saved from a result, tile with ETA, tap → route < 3 s, files exist | sheet "Home" → `Saved`; tile `Go: Home · 14 min`; tap → `state NAVIGATING` after 2 969 ms including ~1.5 s of uiautomator; `favorites.json` + `recent.json` written | PASS |
| S17.6 GPS gate (B10): implausible fix rejected and logged, no route from it; a consistent run re-anchors | teleport home → Whole Foods: `gps rejected: jump 10983 m in 5 s (6852 km/h)` ×N, puck and route unchanged, strip "GPS weak"; `gps re-anchored after 5 consistent fixes` 26 s later. Sub-second fix bursts no longer count (`dt ≥ 1 s`) | PASS |
| S17.7 route preview: ≥2 candidates with time/distance/via, Start uses the chosen one without a refetch | `preview 3 candidates: 14 min · 11.0 mi via East FM 407 \| 17 min · 11.1 mi via Cross Timbers Road \| 18 min · 10.3 mi via Justin Road`; Start → `start gen=1 preview route 11.0mi`, no second `routing` line | PASS |
| S17.8 voice classes: class off → no TTS, logged | Layers sheet `Voice continue on` → `off`; route start → `voice class continue off, dropped: "Drive south on Smoky Oak Trail…"`, `GoogleTTSServiceImpl: Synthesis request` count 0; toggled back → `on` | PASS |
| S17.8 auto night: sun elevation drives Light↔Dark | `night sun 30.8° -> day; style Light -> Light` every 60 s; dark below −6° (civil dusk). Real-sunset switch on the tablet not yet observed | PASS (math) / OPEN (field) |
| S17.9 speed limit sign | Valhalla `shape_attributes.speed_limit/speed/length/time` on both adapters, lenient decoder: `annotated=9/10`; MUTCD sign 60 mph on US 377 on the tablet (0.26.1) | PASS |
| S17.10 arrival: arrival spoken once, card with name + Done, no instruction after it, navigation ends by itself, End button gone, 0 crashes | run a2: `arrival name=Whole Foods Market… ending in 10s` → `voice arrival not yet spoken; saying "You have arrived at Whole Foods Market."` (TTS synthesis + nav focus request, focus abandoned 3 s later) → `state IDLE trip=Complete` → `stop gen=2 caller=…dismissArrival < onArrived` 10.0 s later. Card `Arrived`=1 `Done`=1 `End Navigation`=0; after: card gone, search back. spoken/visual after arrival = 0, crashes 0 | PASS |
| B11 (found by the first arrival run): reroute at arrival brought the discarded 11 mi route back | before: reroute → 2-step 5 m route → ARRIVE spoken → 350 ms later `visual "Oak Knoll Road" stepIdx=1 … remaining=10.94mi steps=10` forever, End still up. after NavLock: run a2 had a reroute 6 s before arrival (`reroute alternates=1 → replace`) and the trip completed on the new route | PASS |
| S17.11 add a stop: button while navigating, search opens under the card, pick → route now→stop→destination replaces the trip, camera back to following, second stop keeps the first, API 409 when idle | `Add stop`=1; field=1; pick Kroger Flower Mound → `route stop-add … distance=13.7mi steps=16` and `progress remaining=13.67mi eta=20min steps=16` (was 11.02 mi / 10 steps); recenter buttons after pick = 0; `POST /api/add_stop` QuikTrip → `distance=19.1mi steps=24` (Kroger kept: waypoints matched by distance, Valhalla snaps them); idle → 409; crashes 0 | PASS |

Screens: `~/arrival-a2-card.png` (card over the map, puck at Whole Foods), `~/addstop-s3-results.png` (search + lettered results over the navigating layout), `~/addstop-s3-after.png` (route via Kroger, 20 m / 14 mi, following camera).

Open from S17: night-mode field switch; `docs/emu-favorites.sh` still hard-codes the Whole Foods result label; the "Add stop" search card covers the Layers/Add-stop buttons while open (cosmetic).

## S20 Settings contract — 2026-09-20, 0.31.1 / code 119

Separate merge 05f6151 (contract 4ad1978). Main debug build passed. Emulator-5554 cockpit smoke **13/13**, 44 local tile requests, search 1 s, navigation first attempt, 0 crashes, playback stopped. Evidence: ~/evidence/cockpit-s20-contract-main. Settings API read/write, masking, provider validation, 401 authentication and request log redaction passed. No tablet install for this prerequisite.
### 2026-09-20 — 0.32.0 S23e full-map destination mode (emulator-5556, `docs/smoke/s23-fullmap.sh` run4, evidence `~/evidence/s23-fullmap-run4/`)

| Item | Criterion | Measured | Result | Evidence |
|---|---|---|---|---|
| FM0 | baseline: rail column and Books pane visible | rail=10141a pane=252f3b | PASS | fm0-books.png |
| FM1 | search focus → rail hidden ≤ 2000 ms | 1181 ms | PASS | fm1-full.png |
| FM2 | power strip + pane gone: (1100,780) is map, not chrome (#0b0e12/#10141a) | px=f2eff7 | PASS | fm1-full.png |
| FM3 | result → preview sheet, rail still hidden | sheet after 0s rail=1f5f8b | PASS | fm3-preview.png |
| FM4 | Close → rail back ≤ 2000 ms and Books pane back | 483 ms, Speed nodes=1 | PASS | fm4-back.png |
| FM5 | tile → full map; Start → NAVIGATING with rail back | tile rail=1f5f8b nav after 4s rail=10141a | PASS | fm5-navigating.png |
| crash | 0 crashes for com.morton.trucknav in the run window | 0 | PASS | crash.txt |

Run1 had two harness false-fails (probe hit the Recents panel; "Speed" text is "Speed  1.0×") and one environmental fail (emulator Wi-Fi was off at 18:43 — someone else toggled it; the harness now requires photon reachable before it starts).

## S22 — Traffic foundation, 2026-09-20 (Astra; build01 emulator-5554)

**IN PROGRESS, not DONE, not merged.** Branch `traffic-s22`, base `166e423`,
version 0.31.0/code 115 inherited from main (no slice version bump).
Build `:app:assembleDebug :app:assembleDebugAndroidTest` passed. Local development
Settings stub only; replace it with the S20 contract before integration. No tablet use.

| Criterion | Measured | Result |
|---|---|---|
| Provider contract fixtures | 12/12 instrumentation tests in 3.717 s: TomTom supporting geometry, Google TRAFFIC_AWARE/via ordering/ETA-only, both HTTP403, null answers, 3s timeout, strict 10min expiry, request cap/restart, incident parsing/corridor, BootReceiver restriction | PASS (fixtures) |
| e: literal credentials | 0 source/local.properties matches; both providers read Settings only | PASS |
| b: actual emulator network loss, invalid generated key | Wi-Fi plus LTE fallback disabled; overlay hidden; 0 new traffic requests over 8s after settling | PASS (offline gate) |
| b: reconnection | 5,878 ms to online state, 0 crashes | PARTIAL (valid flow pixels still pending) |
| f: cockpit, final build | 13/13, 0 crashes | PASS |
| S7 status | ui-s7 already merged via d7ef03f, ancestor of current main; no duplicate merge | VERIFIED |
| a: live TomTom colored pixels ≤5s + 30min fake drive ≤2000 requests | No operator key available. Persistent sliding cap tested; live soak not run | BLOCKED |
| c: actual A/B/C cards and TripBar | Provider fixtures pass; nav owner consumer hooks awaiting integration | BLOCKED |
| d: actual Settings Test key UI | Component implemented; HTTP403 fixture passes; S20 pane hook awaiting integration | BLOCKED |
| Google ETA on existing OSM map | Google service-specific terms §19.2 conflict; operator decision pending | HOLD |

Evidence on build01:
- `~/evidence/traffic-s22-fixtures-bootfix/` (test output, build, credential audit)
- `~/evidence/traffic-s22-offline-bootfix/` (network states, zero-request window, screenshot, crash buffer)
- `~/evidence/cockpit-s22-final/` (13 criterion rows, pane screenshots)
- `~/evidence/traffic-s22-ui/layers.png` and `layers.xml` (actual Traffic control)

Findings/fixes:
1. Initial cockpit was 12/13: warm MapLibre cache produced 7 local requests against
   the harness's 10-request cold-render criterion. Moved only the regenerable
   `files/mbgl-offline.db` aside, preserving it as `.s22-preserved`; cold check
   produced 39 requests and 13/13. No pm clear; basemap untouched; harness unchanged.
2. Wi-Fi disable alone did NOT create offline conditions: AVD had a validated LTE
   fallback. Harness now temporarily disables data too and restores prior state;
   device-side 20s restore plus shell trap. Both radios restored to enabled.
3. Observed a real baseline crash from BootReceiver when Android 15 rejected a
   foreground service start. Narrow exception guard defers overlay startup to the
   existing foreground CockpitScreen path. Regression test + subsequent offline
   run and cockpit have zero crashes. Original failing evidence retained.
4. No raw upstream URLs or response bodies are logged. Loopback raster URLs carry
   no keys; four workers/bounded queue, bounded response bytes, 2.8s network limit,
   3s ETA deadline, daily attempt counter and persistent rolling tile budget.

`docs/emu-traffic.sh` has fixtures/offline/30-minute-soak modes. It records
BLOCKED rather than promoting fixture results to live acceptance. The final
live screenshot ≤5s, both actual card states, and Test key UI criteria still need
their integrated UI harness extensions and real credentials. See
`docs/S22-INTEGRATION.md` for control contracts and the exact nav/Settings hooks.

### 2026-09-20 — 0.33.0 / 123 merge of traffic-s22 (claude-nav, build01 emulator-5554)

| Check | Measured | Result |
|---|---|---|
| `cockpit-smoke.sh merge-s22c` (cold render: `mbgl-offline.db` set aside, restored after) | 13/13, 39 asset requests, route in 2 s, 0 crashes | PASS |
| `docs/smoke/s23-fullmap.sh merge-s22` | FM0–FM4 PASS (rail hidden 1043 ms, back 943 ms); FM5 no Home favorite on this emulator | PASS / env |
| Layers sheet | `Traffic` row present, content-desc `Traffic off` (keyless → silent) | PASS |
| First build of the merge worktree | black map, 0 loopback requests — no `local.properties` in the worktree (demotiles style URL, API off) | harness caught it |

## S20 continuation — 2026-09-20, settings-s20 (acceptance pending)

| Check | Measured | Status |
|---|---|---|
| Empty local.properties build | assembleDebug passed; generated apiToken and valhallaUrl empty | PASS (build only) |
| Whole-app hardcoded-home scan | grep -r homeLat app/: exit 1, 0 bytes | PASS |
| App and S20 instrumentation compilation | :app:assembleDebug and :app:assembleDebugAndroidTest passed | PASS (compilation only) |
| Empty-build first-run token and fresh profile | APK archived at ~/evidence/s20-empty-build/app-empty.apk; runtime not yet exercised | NOT RUN |
| Vehicle <=2 s / persistence / MCP Home | docs/emu-settings.sh prepared; 5554 held for another session's S9b soak | BLOCKED (device lease) |
| Settings UI/QR and storage tests | S20SettingsTest compiled; not yet executed | NOT RUN |
| Final main integration and tablet install | No tablet changes by this S20 session | NOT RUN |

The broad assembleDebugAndroidTest target hit an existing :routing test AAR
desugaring requirement; app-specific instrumentation compiles. This is not an S20
runtime pass. The separate contract's earlier 13/13 receipt does not cover the
remainder. Nav-owned preview/trip unit consumers and runtime preview adapter
refresh are explicitly pending coordination.


## S20 remainder — fresh empty-build evidence (2026-09-20, 20:30 CDT)

Source: settings-s20 at 948b028 plus acceptance-tool fixes; app APK unchanged from
that source. This is feature-branch evidence, not a final production merge/tablet
receipt. The earlier contract-only merge remains separate (05f6151).

| Gate | Measurement | Result |
|---|---|---|
| Empty local.properties runtime | New AVD s20-fresh-empty-2007; token 64 hex characters, blank Valhalla and ABS password before runtime provisioning | PASS |
| First run | Setup dialog and RUNBOOK pack steps; Android fullscreen tutorial dismissed; token masked by API; runtime configuration accepted | PASS |
| Settings API | 10/10 pane configuration keys readable; token rotation invalidates old token immediately; invalid provider/type return 400 | PASS |
| Secret masking/logs | Disposable secret returned only as last four; plaintext absent from logcat | PASS |
| Vehicle upload | 512px PNG becomes 256x256 PNG; 4,194 magenta puck pixels visible at 0.492 s | PASS |
| Vehicle restart/delete | 4,194 pixels after force-stop/restart; DELETE returns default at 0.492 s (0 magenta pixels) | PASS |
| Vehicle rejection | Invalid image 400; >2 MiB 413 | PASS |
| MCP | Real set_home updates visible Go: Home tile; set_work leaves one Work; upload_vehicle roundtrip returns 2,864-byte image | PASS |
| Hardcoded home | grep -r homeLat app/: exit 1, no matches | PASS |
| App crashes / ADB retries | 0 / 0 during fresh-profile acceptance | PASS |
| RUNBOOK setup | Correct assets command with explicit ~/trucknav-assets; fresh profile renders Denton roads/buildings/POIs after GPS fixture | PASS |
| Cockpit smoke | 13/13; 51 local asset/tile requests; search 1 s, preview 0 s, navigation first tap/0 s; 8 strip cells; 0 crashes | PASS |
| Instrumentation controls | Five storage/image/position/token tests pass; Settings UI automation remains under diagnosis | PENDING |
| Production merge / final tablet install | GitHub publication approval pending; tablet untouched | NOT RUN |

Evidence on atlas01: ~/evidence/s20-fresh-empty-2007 (first-run, assets,
instrumentation and map-denton.png), ~/evidence/s20-fresh-empty-2007-resume
(S20 acceptance results), ~/evidence/cockpit-s20-fresh-empty-2007 (13 rows).
Existing-profile build01 validation also passed API/MCP/puck gates: hot reload
0.915 s, delete 0.457 s, 0 app crashes; one explicitly recorded ADB log read retry.
Its emulator lease was released for Astra at 20:18. Do not reuse the earlier
colliding nav-driver run as evidence for either slice.

Nav-owned unit formatting consumers and route-preview runtime adapter refresh
remain pending in protected files. S20 does not edit those files. Astra's
uncommitted TomTom/off-only traffic removal on build01 must be preserved during
future integration; do not reintroduce Google-provider UI or validation then.


### S20 UI gate resolved — 2026-09-20 20:38 CDT

`S20SettingsTest`: **6/6 PASS in 14.818 s** on the fresh empty-build AVD.
Units switched to metric and persisted; Auto night changed and restored; the
on-screen QR decoded to the current bearer token (compared in memory, never
written into screenshots/logs); About exposed log export. The five core tests
cover atomic Settings updates/flows/UTF-8/null, short-secret masking, PNG fit and
invalid-image preservation, last-fix -> PMTiles centre, and token entropy/unit
formatting. Evidence: ~/evidence/s20-fresh-empty-2007/instrumentation-final.txt.

The UI failure was resolved in the test harness: UiAutomation's cached node
bounds were stale after scroll, off-screen controls needed scrolling, and the
fully visible API chip is 85 px (the test's former 90 px minimum was wrong).
The app APK was unchanged throughout these harness fixes. Final feature evidence:
**cockpit 13/13; instrumentation 6/6; vehicle upload/delete 0.492/0.492 s;
0 app crashes; fresh runtime/configuration/MCP/no-homeLat gates PASS.**

The separately merged Settings MCP contract is now installed at
~/trucknav-mcp/trucknav_mcp.py after exact comparison with its pre-contract
source; rollback copy: trucknav_mcp.pre-settings-contract.py. No tablet request
or installation occurred. Public GitHub publication and production merge/install
remain blocked pending explicit approval; nav-owned Units/preview consumer hooks
remain pending. Do not mark S20 fully delivered or bump the production version yet.


S20 cleanup, 20:41 CDT: original atlas01 `trucknav-tab` AVD restored; disposable
profile stopped; no playback left running. The original emulator's expired S20
lease was free and subsequently acquired by astra-3 for S23; no further device
changes by S20. `docs/emu-settings.sh` now invokes the same passing six-test UI/core
suite after its API/MCP checks, so the repeatable acceptance entrypoint includes
the on-screen controls and QR proof. Its component commands were run above; the
combined wrapper was syntax-checked after wiring them together.
