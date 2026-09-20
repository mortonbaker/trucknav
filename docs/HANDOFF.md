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

## astra — S22 "Traffic: TomTom + Google" (+ S7 merge while waiting)

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

## claude-nav (this session)

```
claude-nav: S19 trip bar + stops → S21 search along route → S23 control placement + full-map destination mode.
atlas01 ~/trucknav-nav (branch integrate-s6), emulator-5556 (AVD trucknav-s6, ~/.config/emu/s6.env).
Smoke: docs/emu-stops.sh (S19), docs/emu-along.sh (S21), docs/s2-camera.sh + pane screenshots (S23). Tablet install under lease only; merges + tablet installs for every track.
```
