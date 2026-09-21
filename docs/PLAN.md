# TruckNav — the whole plan in slices (handoff edition)

Written 2026-09-20 18:40 from main `0.30.0` / versionCode 112 (on the tablet). One page per slice: what it is, what **done** looks like (falsifiable — a screenshot, a log line, a number), and the smoke test that proves it. Detail for the finished slices lives in `docs/BUILD-PLAN.md` (design notes) and `docs/SMOKE-TEST.md` (measured results); this file is the map.

Hand a slice to an agent by pointing them at its section here plus `docs/AGENTS.md` §1–§7. They must: claim the tree (`docs/claim.sh`), lease a device before touching it (`docs/tablet-lock.sh <serial> acquire … || exit 2`), work in their own worktree/branch, prove every done-line, append results to `docs/SMOKE-TEST.md`, and merge to main as `max(versionCode across worktrees)+2`. Emulator first; the tablet only for what needs real hardware (GPS, TTS, Wi-Fi, media); the truck only for the relay/Pi/drive items.

## 0. Rules that apply to every slice

| Rule | Why |
|---|---|
| Pass gate: `adb logcat -d -b crash \| grep -c "Process: com.morton.trucknav"` = 0 after the slice's smoke, and every done-line is true in a screenshot or a NavLog line. | "Works on my emulator" is not evidence. |
| Never `pm clear` the app (3 GB basemap dies). Never cut the tablet's Wi-Fi from outside (it is a Wi-Fi adb device unless the USB cable is in — check `adb devices` for `R9PT207J6ZN`). | Both have cost an afternoon. |
| Never commit in `~/trucknav` while `git status` shows unmerged paths; one merge at a time, claim the tree first. Both tracks took 0.29.0 today. | A committed conflict marker shipped once. |
| Fake drives on the emulator: start *near* the destination or the GPS gate (B10) rejects the teleport for ~25 s. `emu.sh drive <polyline> 3`. | Wasted a run. |
| Ferrostar core is not thread-safe (B11): every `startNavigation/replaceRoute/stopNavigation` goes through `NavLock.sync {}`. | Discarded routes come back otherwise. |
| Material icons, no emoji, targets ≥ 48 dp, text ≥ 24 dp / 4.5:1 for anything read while driving (AAOS rules). | Driving-first. |

Scripts: `docs/cockpit-smoke.sh` (13 rows, every merge), `docs/emu-favorites.sh`, `docs/emu-arrival.sh`, `docs/emu-addstop.sh`, `docs/emu-overview.sh`, `docs/s2-camera.sh`, `docs/s3-styles.sh`, `docs/search-smoke.sh`, `docs/s5-books-offline.sh`, `docs/s15-prefetch.sh`, `docs/s16-pi-relay.sh`, `docs/books-smoke.sh`, `docs/d9.sh`. Emulator profiles: `~/.config/emu/*.env`, `emu.sh` (skill `android-emulator-dev`). UI driver: `~/bin/ui.sh <serial> dump|tapx "<label>"`.

## 1. Status board

| # | Slice | Size | Status | Proof |
|---|---|---|---|---|
| S0 | Audit fixes (books session survives focus loss, portrait layout) | S | DONE 0.9.5 | `books-smoke.sh`, D9 |
| S1 | Immersive cockpit + status strip + rail | S | DONE 0.10.0 | SMOKE-TEST §5 |
| S2 | Map centering / camera padding from map size | S | DONE 0.10.5 | `s2-camera.sh`, `puck.py` |
| S3 | Five map styles + readable search pill | M | DONE 0.12.2 | `s3-styles.sh`, `contrast.py` 18.5:1 |
| S4 | YouTube: history, no Live/Shorts | S + sign-in | OPEN — needs operator's Google sign-in on the tablet | — |
| S5 | Books offline downloads | M | DONE 0.17.1 | `s5-books-offline.sh` run6 |
| S6 | On-device routing (Valhalla in-process, server-first) | L | DONE 0.22.x (Astra) + tablet validation 0.22.1 | SMOKE-TEST "S6" |
| S7 | Look and feel (icon, palette, type) | S | source done on `ui-s7` (Astra), physical run blocked; not merged | BUILD-PLAN S7 receipt |
| S8 | Settings pane | S | folded into S20 | — |
| S9 | Harness + runbook | S | DONE 0.21.0 | `cockpit-smoke.sh` 13/13 |
| S10 | Pi ACL, Photon self-host, Kindles | operator | OPEN | — |
| S11 | Nav event log | S | DONE 0.14.0 | `files/navlog/` |
| S12–S14 | End/turns/ETA/mute bugs | — | RESOLVED (OsmAnd was the ghost navigator) | BUGS B1–B4 |
| S15 | Tile prefetch along route | M | DONE 0.25.1 (emulator); truck drive to confirm B5 | `s15-prefetch.sh` |
| S16 | Relay board on every network + Pi flow | S | app side DONE 0.21.0; truck side STAGED (ESP32 OTA when battery healthy, operator present) | `s16-pi-relay.sh` |
| S17 | Navigation, finished (11 parts) | M–L | DONE 0.28.0; + preview-first favorites and A/B/C badges 0.30.0 | SMOKE-TEST "S17.1–S17.11" |
| S18 | Voice commands | L | OPEN, after S19/S20 | — |
| S19 | Trip bar + stops (Google/Tesla) | M | assigned to astra-2 2026-09-20 20:10 (box in HANDOFF.md) | — |
| S20 | Usable by others (settings, no hardcoded places, vehicle upload, first run) | M | NEXT (hand off) | — |
| S21 | Search along route | M | READY along-s21, 0.34.1/code131 — a–f proven on build01 emulator; claude-nav merges/installs | SMOKE-TEST S21; ~/evidence/s21-along-final131/receipt.md |
| S22 | Traffic (TomTom only) | M + key | foundation merged 0.33.0; live pixels / ETA line / Test-key UI OPEN (need a TomTom key + S20 pane) | SMOKE-TEST "S22" |
| S23 | Map control placement + full-map destination mode | S | (e) full-map DONE 0.32.0; (a)–(d) buttons OPEN | `docs/smoke/s23-fullmap.sh` run4 7/7 |
| — | Favorites/recents redesign (Tesla tiles + panel) | S | DONE 0.29.0 (vehicle track) | `fav-smoke` |

Suggested split: **nav track** S19 → S21 → S23; **vehicle track** S20 (settings + API + vehicle upload) → S16 truck side when the truck is home; **Astra** S22 traffic research + S7 merge; **operator** S4 sign-in, S10, ESP32 OTA.

## 2. Finished slices — what was proven (so nobody re-proves it)

- **S0** D9: Finamp playing → tap book → book plays, Finamp paused; Music Play → music plays, book paused; one `BooksPlayerService` for the sequence. Portrait: art never overlaps controls.
- **S1** no system bars in either orientation; swipe brings them back and they hide within 3 s; strip = clock, SSID, tailnet, GPS age, battery matching `dumpsys`.
- **S2** recenter puts the puck within ±5 % of the *map area* centre with the panel open and closed; navigating puck in the lower third of the map, never under the panel.
- **S3** five styles switch within 3 s, choice survives force-stop, style change mid-route keeps the route line; search pill 18.5:1 contrast, 64 dp, 24 sp.
- **S5** downloads resume from `.part`, offline play, queued progress PATCHed when a network appears (default-network callback never fires on the tablet — watch any INTERNET network).
- **S6** server route 563 ms; server stopped → on-device 2.07 s on the tablet, 178 mi route on the emulator 1.2 s, distances equal; process < 600 MB.
- **S9** `cockpit-smoke.sh` 13/13 on emulator and tablet with no hands on the device.
- **S11** NavLog: visual/spoken/deviation/progress/start/stop with callers; `nav-YYYYMMDD.log` rolling.
- **S15** offline sharpness +19 % mean / +32 % last minute with the warm-up; 1 GB ambient cache.
- **S16 app** Vehicle pane shows all 8 relays from the board; Starlink tap = on, hold = off; Venus reached via tailnet → cached LAN → /24 sweep.
- **S17** single navigator (OsmAnd stopped by NavGuard, `pm disable-user`'d), lettered results with distance/ETA, overview fits 86 % width inside the padded viewport, mute = 0 TTS + 0 focus requests, favorites tile → NAVIGATING in 2.9 s, GPS gate rejects a 6852 km/h jump and re-anchors after 5 consistent fixes, preview with 3 candidates and Start reuses the chosen route, voice class off → `dropped` + 0 synthesis, night from solar elevation, MUTCD 60 mph on 377, arrival card + spoken once + auto-end 10 s, add-a-stop 11.0 → 13.7 → 19.1 mi keeping earlier stops, API 409 when idle, A/B/C badges with minutes on every route, tap a route to choose it, favorites open the preview first.

## 3. Open slices — goal, done-when, smoke

### S4 — YouTube history, no Live/Shorts (small + operator sign-in)
Goal: the YouTube pane opens on the operator's history; Live and Shorts rows hidden.
Done when: (a) after sign-in the History list shows the operator's real watched videos (screenshot); (b) no visible element whose text is exactly "Live" or "Shorts" on Home/Subscriptions/History after 3 navigations (uiautomator); (c) play a video, switch to Map for 60 s: audio continues, rail thumbnail shows it, `dumpsys media_session` PLAYING.
Smoke: `cockpit-smoke.sh` YouTube row + the three checks above on the tablet under a lease. Needs the operator to sign in once (Rule 2 exception).

### S7 — Look and feel (small) — source exists on `ui-s7`
Done when: launcher and Recents show the new icon; screenshots of all seven panes have no Material purple defaults; every tappable ≥ 48 dp (uiautomator bounds spot-check).
Smoke: `cockpit-smoke.sh` + pane screenshots on the emulator; merge as max+2.

### S10 — Infrastructure (operator)
Done when: `tailscale ping` Pi from atlas01 fails and from the tablet succeeds (ACL); Photon self-hosted on homebackup answers `?q=whole foods&lat&lon` in < 1 s; Kindles per `kids-kindles`.

### S16 — truck side (small, truck must be home, operator present)
Done when: dish off + truck away from home → Starlink cell reads OFF (not `--`); tap → ON within 2 s; `Everylink` in the Pi's scan within 5 min; second tap → OFF; `relay.sh state` < 5 s cold / < 1 s warm. ESP32 OTA only with battery > 12.6 V and the operator watching ("don't brick").
Smoke: `docs/s16-pi-relay.sh <tag>`.

### S18 — Voice (large, after S19/S20)
Push-to-talk → on-device STT (Vosk/whisper.cpp small) with a Spark fallback over the tailnet → intents: navigate to <favorite|place>, add stop <place>, toggle <relay>, play <book|music>, style <x>, mute.
Done when: 20 scripted utterances at 60 mph road noise (recorded) → ≥ 18 correct intents, each acted on within 2 s of end-of-speech, offline; wrong intents never start a route without a confirm.

### S19 — Trip bar + stops (medium) — NEXT, nav track
What Google/Tesla do: bar counts to the **next stop**; a list shows every stop with its own ETA; arrival at a stop is announced and the trip **auto-continues**; stops removable mid-trip (Tesla can't — we will). Details + sources: BUILD-PLAN S19, NAV-AUDIT §9.
Build: `nav/TripBar.kt` replaces Ferrostar's `TripProgressView` (`→ <next stop>` / ETA · min · mi to it / small final ETA; tap → stop list with ✕); numbered pins + checkered flag; leg boundaries from the route's waypoint indices; `remainingWaypoints` drop → card + voice + continue; remove = rebuild waypoints + `replaceRoute` under `NavLock`.
Done when (emulator, `docs/emu-stops.sh`): (a) two stops → bar shows `→ <stop1>` with ETA/min/mi to stop 1, final ETA small, list of 3 rows with ascending ETAs; (b) pins 1, 2 + flag on the map; (c) fake-driving past stop 1 logs `stop-passed`, speaks the arrival once, bar flips to `→ <stop2>` within 2 s, no reroute; (d) ✕ on stop 2 → `route stop-remove … steps=`, bar and pins update, trip still NAVIGATING; (e) next-stop numbers within 5 % of Valhalla's leg summary; (f) 0 crashes.

### S20 — Usable by others (medium) — hand off
Goal: build once, configure on the tablet; an agent can discover and set everything through the API/MCP.
Build: Settings pane (Places: Home/Work from current location / search / map; Vehicle: image + Replace; Servers: Valhalla, Photon, style, ABS, Venus, relay; Units; Voice classes + Auto night moved here; API: token as QR + Regenerate; About: version, log export, pack status) in `files/settings.json`; delete `homeLat/homeLng` (initial camera = last fix → extract centre); `PUT/GET/DELETE /api/vehicle` (PNG/JPEG ≤ 2 MB, transparent, nose-up → 256 px `files/vehicle.png`, puck hot-reloads); `GET/PUT /api/settings`, `PUT /api/favorites/home|work`; MCP `get_settings`, `set_setting`, `set_home`, `set_work`, `upload_vehicle(path)` with the image spec in its docstring; first run: no token → generate + show, no basemap → the RUNBOOK steps on screen, no Valhalla → on-device only, said in the strip; README "Set up for your own truck"; `docs/API.md`.
Done when: (a) empty `local.properties` → boots to a usable map, token generated, every pane setting settable on screen and readable via `GET /api/settings`; (b) `PUT /api/vehicle` 512 px PNG → puck changes within 2 s, survives restart, `DELETE` restores; (c) MCP `set_home` moves the Home tile; (d) `grep -r homeLat app/` empty; (e) a fresh emulator profile set up from RUNBOOK alone works; (f) `cockpit-smoke.sh` 13/13.

### S21 — Search along route (medium) — NEW
What Google does: while navigating, "Search along route" with category chips (gas, food, coffee, groceries); results sit on/near the route and each shows the **detour time** ("+3 min"); tap → added as the next stop.
Build: chips in the add-stop box while navigating; Photon has no corridor search, so sample the *remaining* route every ~10 km, query Photon with `lat/lon` bias (+ `osm_tag` for the category) per sample, dedupe, keep hits ≤ 2 mi from the route, rank by detour = matrix(now→hit) + matrix(hit→next waypoint) − matrix(now→next waypoint) via Valhalla `sources_to_targets`; letters A–F, "+N min" on each; pick → `addStop`. Free text works the same way (bias along the corridor instead of at the puck).
Done when (build01 emulator-5554, `docs/smoke/s21-along.sh`): (a) on the home → Whole Foods route, chip "Gas" returns ≥ 3 hits all within 2 mi of the route (distance-to-polyline logged) in < 4 s; (b) each row shows "+N min" and N equals the matrix detour ±1 min; (c) picking B → `route stop-add` with B as the next stop, trip continues; (d) free text "Kroger" while navigating returns hits sorted by detour, not by distance from the puck; (e) offline (server down) → chips disabled with a reason in the strip, no crash.

### S22 — Traffic: TomTom only (medium + key) — Astra; foundation merged 0.33.0
Operator decision 2026-09-20 19:50: **TomTom only. Google dropped** (Google Maps Platform terms forbid using its data on a non-Google map; Astra flagged it, operator agreed). The S22 cleanup removes the Google provider, its fixtures and the Settings option; upgrades delete its retired credential and switch its selection to off. Keys are BYOK: keys are entered in the Settings screen, or through the API/MCP (`PUT /api/settings {"tomtomKey": "…"}`, `set_setting("tomtomKey", …)`), never in source or `local.properties`.
Facts (checked 2026-09-20): self-hosted Valhalla has no live traffic. TomTom: Traffic Flow raster/vector tiles + Routing API, free allowance covers one truck (50 k tile requests/day per [pricing](https://docs.tomtom.com/pricing); [flow tiles](https://developer.tomtom.com/traffic-api/documentation/traffic-flow/raster-flow-tiles)). Google Routes `TRAFFIC_AWARE` is a Pro SKU: 5,000 free/month then $15/1000 ([billing](https://developers.google.com/maps/documentation/routes/usage-and-billing)).
Build (all online-only, silent when offline or keyless):
1. `traffic/TrafficProvider` interface: `flowTileUrl(z,x,y)`, `etaWithTraffic(polyline|origin,dest): Duration?`, `incidents(bbox)`; implementation `TomTomTraffic`; the active setting is `trafficProvider = tomtom|off`.
2. Overlay: a raster layer above roads from TomTom flow tiles. Toggle in Layers; auto-hidden offline; tile requests counted in NavLog (`traffic tiles=…`) per day.
3. ETA: each A/B/C candidate gets a second number `· 19 min w/ traffic` from the provider (TomTom: routing with the same via points) when the answer arrives within 3 s; the trip bar's ETA switches to the traffic one while it is fresher than 10 min. Valhalla remains the router.
4. Keys: read from `Settings` (S20 contract below); Settings pane shows a per-provider "Test key" button that calls one cheap request and reports OK / the HTTP error.
5. Incidents (TomTom only, later in the slice): pins with a one-line label on the route ahead.
Done when (emulator `build01:emulator-5554`, key set via `PUT /api/settings`): (a) Layers → Traffic on → coloured flow segments over I-35W in a screenshot within 5 s; NavLog `traffic tiles=N` and N ≤ 2,000 for a 30-minute fake drive; (b) `svc wifi disable` on the *emulator* → overlay hidden, 0 requests, no crash; back online → returns within 10 s; (c) preview cards show `14 min · 19 min w/ traffic` when the provider answers within 3 s, plain when not, for TomTom; (d) bad key → Settings "Test key" reports the HTTP status, nothing else breaks; (e) `grep -rn "AIza\|tomtom.*key" app/src` finds no literal keys; (f) `cockpit-smoke.sh` 13/13.

**S20 contract Astra codes against** (vehicle track ships it first, small commit, same day): `com.morton.trucknav.settings.Settings` object — `get(key: String): String?`, `set(key: String, value: String?)`, `flow(key): StateFlow<String?>`, backed by `files/settings.json`; `GET /api/settings` (values for secret keys masked to last 4), `PUT /api/settings {k: v, …}`; MCP `get_settings`, `set_setting(key, value)`. Keys used: `tomtomKey`, `trafficProvider`.

### S23 — Map control placement (small) — NEW
Best practice (Android Auto `NavigationTemplate`, Google Maps, Waze, Tesla agree): **information on the left, actions on the right, actions in corners or a right-edge stack, nothing floating mid-edge.** Top-left = turn card / search; bottom-left = trip bar; top-right = mode toggles (overview, mute, layers, compass); bottom-right = camera controls (recenter, zoom ±); the puck's third of the screen stays clear. Today: Layers and Add-stop float at centre-left, recenter at centre-right (Ferrostar's grid defaults).
Build: move Layers + Add-stop into the top-right stack under overview/mute; recenter into the bottom-right stack with zoom; keep 16 dp corner margins, 56 dp buttons, 12 dp gaps; portrait mirrors the same corners. Destination mode: `CockpitScreen` collapses the rail and `PowerStrip` while `sceneState.selectedDestination != null || searching`; `mapSize` drives the camera padding as today.
Done when: (a) idle and navigating screenshots in both orientations show every button inside 16 dp of a corner or in the right-edge stack; (b) no button overlaps the turn card, the trip bar, the search box, the results card or the tiles at any state; (c) uiautomator bounds: every button ≥ 56 dp, gaps ≥ 12 dp; (d) `s2-camera.sh` puck criteria still pass (the stacks must not push the padding). (e) **Full-map destination mode** (operator 2026-09-20): tapping the search box, a favorites tile, a result, or long-pressing the map hides the rail and the power strip so the map, search and preview fill the screen; Start or Close brings them back; screenshot in each state, and the map's measured size in that mode equals the screen minus the status strip; the S2 puck criteria still hold when the rail returns.

## 4. Smoke-test plan (what runs when)

| When | Run | Where |
|---|---|---|
| Every merge to main | `docs/cockpit-smoke.sh <tag>` (13 rows) | emulator, then tablet under lease |
| Any change under `nav/` or the scene | `emu-favorites.sh`, `emu-arrival.sh`, `emu-addstop.sh`, `emu-overview.sh`, `search-smoke.sh` | emulator |
| Any change to styles/assets | `s3-styles.sh` + `contrast.py` | emulator |
| Any change to Books/Music | `books-smoke.sh`, `d9.sh`; `s5-books-offline.sh` **only on USB** | tablet |
| Before the next real drive | 0 crashes on the tablet, `navlog` rolling, NavGuard banner absent, GPS gate not rejecting outdoors | tablet, outside |
| Truck at home | `s16-pi-relay.sh`, S15 B5 drive check (grey pixels < 1 %) | truck |
| New slice | its own `docs/emu-<slice>.sh` written *with* the code, results appended to `docs/SMOKE-TEST.md` with numbers | emulator first |

Evidence goes in `docs/SMOKE-TEST.md` (dated table, measured values, PASS/FAIL) and screenshots under `~/` on atlas01 or `docs/evidence/<slice>/`.

## 5. Handoff — who does what, where (2026-09-20 18:50)

| Agent | Slice(s) | Machine / tree | Device for smoke | Tablet? |
|---|---|---|---|---|
| claude-nav (this session) | merger only: merges + installs + docs | atlas01 `~/trucknav-nav` branch `integrate-s6` | `emulator-5556` (AVD `trucknav-s6`) on atlas01 | install only, under lease |
| claude-vehicle | S20 usable by others (Settings pane, `Settings` store + API/MCP **first**, no hardcoded places, vehicle upload, first run) | atlas01 `~/trucknav-fav` or a new `~/trucknav-s20` worktree, branch `settings-s20` | `emulator-5554` (AVD `trucknav-tab`) on atlas01 | final install + `cockpit-smoke.sh`, under lease |
| Astra | S22 traffic (TomTom only) → S21 search along route; astra-2: S19; astra-3: S23 a–d | build01 `~/trucknav` (clone of GitHub main), branch `traffic-s22` | `emulator-5554` on **build01** (`~/bin/emu.sh`, profile `trucknav`) | never (no GPS/TTS needed); nav installs the merged build |

Merge order: S20's `Settings` commit → S7 → S19 → S22 → S20 rest → S21 → S23. Every merge: `max(versionCode)+2`, `cockpit-smoke.sh` on an emulator, then the tablet under a lease.

### S21 implementation contract — Astra, before code

Research: pinned Ferrostar core 0.56.0 exposes remainingSteps, currentStepGeometryIndex and remainingWaypoints. Cut the current step at its geometry index; append subsequent steps, never search completed steps. Photon configured public server rejects category-only /api (HTTP400); /reverse with osm_tag=amenity:fuel, radius=8, limit=20 returned six stations in 0.56s. Use reverse per sample for categories and forward /api for text. See https://github.com/komoot/photon/blob/master/docs/api-v1.md and https://valhalla.github.io/valhalla/api/matrix/ . Matrix is directional: one-to-many now→hits+next and many-to-one hits→next, unreachable values excluded.

| ID | Preconditions and automated action | Required observation / failure | Evidence |
|---|---|---|---|
| a | leased build01 5554, home→Whole Foods via POST /api/navigate; Add stop → Gas | ≥3 hits, each distance-to-segment ≤3218.688m, first rendered results <4000ms | gas.png, ui.xml, per-hit NavLog and paint time |
| b | inspect Gas rows, recompute directional matrices | every row +N min, absolute difference from matrix detour ≤60s | independent matrix JSON and comparison |
| c | pick letter B | route stop-add with B as next waypoint, NAVIGATING and camera following | before/after logs, screenshot, UI |
| d | reset home→Whole Foods, free text Kroger | detour ascending; log detour order and puck-distance order | kroger.png, rows/logs |
| e | route active, self-restoring docker stop valhalla on homebackup | all four chips disabled, one-line unavailable reason; no app crash; service restored | outage UI/logs and server state |
| f | entire run | crash buffer successfully collected, 0 com.morton.trucknav crashes | crash.txt, post-logcat.txt |

Controls: Gas/Food/Coffee/Groceries chips ≥48dp, query field/clear, A–F row selection, loading/empty/unavailable strip, Back closes add-stop. Never operate the tablet. One agent per emulator, build pressure gate + flock. No protected S19 files changed. Every measurement remains FAIL/BLOCKED until observed; never infer pass from a build.

S21 transport finding: identical reverse request on build01 returned503 with User-Agent okhttp/5.3.2,200 with an explicit TruckNav identifier (5.3.0 also returned200 during diagnosis). Requests now identify the actual app/version and repository; no browser impersonation. Runtime stage diagnostics located the response at Photon /reverse.
