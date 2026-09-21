# TruckNav runbook

Everything an agent or a human needs to install, verify, and recover the 4Runner cockpit.
Coordination rules live in `AGENTS.md` and win over anything here.

## Hosts and addresses

| What | Where |
|---|---|
| Build host / adb server / evidence | `atlas01` (`ssh morton@100.127.143.78`) |
| Source | `atlas01:~/trucknav` = `main`, install tree. Feature worktrees `~/trucknav-<slice>` |
| Tablet | Samsung Tab A7 Lite `SM-T220`. USB serial `R9PT207J6ZN` (preferred), tailnet `100.95.16.47:5555`, home LAN `192.168.0.187:5555` |
| Emulator | `~/.config/emu/trucknav.env`, serial `emulator-5554`; `~/.claude/skills/android-emulator-dev/scripts/emu.sh` |
| Venus OS Pi | `venus-4runner` `100.112.123.30` (root by key from atlas01); MQTT `:1883`, portal id in `local.properties` |
| Relay board | ESPHome `4runner-relay`, DHCP on Everylink / EverythingPhone / Everything, own AP `4Runner Relay Remote`; web API `:80`, native API `:6053` |
| Routing / basemap | Valhalla `homebackup:8446`; Protomaps extract + styles on the tablet under `/sdcard/Android/data/com.morton.trucknav/files/` (mirror `atlas01:~/trucknav-assets`) |
| Audiobookshelf | URL and login in `local.properties`; `docs/abs.sh` wraps the API |
| Secrets | `local.properties`, `~/.calibre-shell-keystore.properties`, `~/esphome-builds/secrets.yaml`. Refer, never copy |

## Install / upgrade (from `main` only)

```bash
cd ~/trucknav && docs/claim.sh take <agent> "<why>" && docs/tablet-lock.sh R9PT207J6ZN acquire <agent> 30 "<why>"
# bump versionCode (+1, monotonic across every branch) and versionName in app/build.gradle
export ANDROID_HOME="$HOME/Android/Sdk"
ANDROID_SDK_ROOT="$ANDROID_HOME" ./gradlew -q assembleRelease --no-daemon
adb -s R9PT207J6ZN install -r app/build/outputs/apk/release/app-release.apk
adb -s R9PT207J6ZN shell cmd package set-home-activity com.morton.trucknav/.MainActivity   # every install resets HOME
adb -s R9PT207J6ZN shell am start -n com.morton.trucknav/.MainActivity                     # the old launcher stays on screen otherwise
cp app/build/outputs/apk/release/app-release.apk ~/apk-drop/trucknav-<version>.apk
git commit -am "<version>: <what>"; docs/tablet-lock.sh R9PT207J6ZN release <agent>; docs/claim.sh release <agent>
```

- A release build cannot be downgraded (`INSTALL_FAILED_VERSION_DOWNGRADE`). Numbers are handed out on `main`.
- Never `pm clear com.morton.trucknav`: it deletes the 3 GB basemap. Never `adb kill-server`.
- Emulator: build **debug** (x86_64 libs), `emu.sh install trucknav app/build/outputs/apk/debug/app-debug.apk`.

## One-time grants (survive upgrades, not `pm clear`)

```bash
S=R9PT207J6ZN
adb -s $S shell cmd notification allow_listener com.morton.trucknav/.overlay.MediaListener   # now-playing bar
adb -s $S shell appops set com.morton.trucknav GET_USAGE_STATS allow                         # foreground-app detection
adb -s $S shell appops set com.morton.trucknav SYSTEM_ALERT_WINDOW allow                     # overlay dock
adb -s $S shell pm grant com.morton.trucknav android.permission.ACCESS_FINE_LOCATION
adb -s $S shell pm grant com.morton.trucknav android.permission.POST_NOTIFICATIONS
```

## Assets (once, ~9 min for the basemap)

```bash
adb -s $S push ~/trucknav-assets/. /sdcard/Android/data/com.morton.trucknav/files/
adb -s $S shell ls /sdcard/Android/data/com.morton.trucknav/files/   # fonts navlog southcentral.pmtiles sprites style-*.json books
```

Restart the app after pushing styles (the loopback server serves them with `no-store`, MapLibre re-reads on restart).

## Harnesses (all on atlas01, all print a PASS/FAIL table, evidence under `~/evidence/`)

| Script | Proves | Needs |
|---|---|---|
| `docs/cockpit-smoke.sh <tag>` | boot as HOME, map tiles, Denton route + End, power strip, every pane, overlay, crash gate | tablet lease, or `SERIAL=emulator-5554` |
| `docs/books-smoke.sh` | books player D1-D8, D10, D11 | tablet lease; plays the test book only |
| `docs/d9.sh` | audio-focus swap with Finamp | tablet lease |
| `docs/s5-books-offline.sh <tag>` | offline download / playback / queued sync | **USB serial**, lease; cuts Wi-Fi 5 min |
| `docs/s3-styles.sh`, `docs/search-smoke.sh`, `docs/s2-camera.sh` | map styles, search contrast, camera | tablet lease |
| `docs/emu-overview.sh` | route overview fit | emulator |
| `docs/s16-pi-relay.sh <tag>` | Pi relay.sh cold/warm, Node-RED toggle, board sensors, B8 SOC topics, Pi tailscale | truck powered; no tablet |

Rules for every harness: check the lease, abort if anything is playing, never cut Wi-Fi off USB, restore state, leave the player paused.

## Truck infrastructure

- **Relay board firmware**: `~/esphome-builds/4runner.yaml` (mirror of HA `/config/esphome/4runner.yaml`). Validate `~/.local/bin/esphome config 4runner.yaml`; compile `... compile`; flash `... upload 4runner.yaml --device <ip>` with the board on the same LAN as atlas01 (Everything at home) or via HA's ESPHome Builder. Flash only with the truck battery healthy: the board reboots and Relay 1 (Starlink) restores ON.
- **Pi**: `/data/starlink/relay.sh on|off|toggle|state|find`, Node-RED `GET http://127.0.0.1:1880/starlink/<action>`, Wi-Fi watchdog in `/data/rc.local`. Tailscale binary in `/data/tailscale/`. No RTC: after a long power-off the clock is 1970 until NTP.
- **SOC**: Venus only has a battery SOC when a battery monitor is on VE.Direct. The SmartShunt talks BLE to VictronConnect but is not cabled to the Pi; until it is, SOC is `--` by design (B8). The app also listens on `battery/+/Soc` for a monitor the system service has not adopted.
- **Networks**: Everylink (Starlink, dish on) > EverythingPhone (phone hotspot; client isolation, so tablet<->Pi only over the tailnet) > Everything (home). The app finds the Pi tailnet-first, then cached LAN, then a /24 sweep on 1883; the relay board by its cached IP, then the AP address, then a /24 sweep on `/switch/<id>`.

## Known gaps

- S4 YouTube needs the operator's Google sign-in on the tablet.
- S6 on-device routing requires routing/valhalla_tiles.tar for your region; blank Valhalla URL selects the on-device path.
- S15 along-route prefetch is implemented; physical-drive coverage remains a separate acceptance item.
- S16: firmware compiled but not flashed; `relay.sh` / Node-RED / B8 unverified end-to-end until the truck is powered (`docs/s16-pi-relay.sh`).
- 30-minute soak never recorded.

## S20 — fresh installation for another truck

Build with an empty local.properties and ANDROID_HOME pointing at Android SDK 36.
No provider key or personal location belongs in build configuration.

1. Install the debug APK on a fresh Android 15 emulator (or your signed release on
   the tablet under its lease). Restore HOME and grants using the commands above.
2. Launch TruckNav. First-run setup creates a private API token. The token/QR is
   available on that screen and in Settings → API; do not put screenshots of an
   exposed token in public evidence.
3. Continue to the map. Without an offline pack, the online MapLibre demonstration
   style is available. First-run/About shows the exact offline asset destination.
4. For offline maps, copy the region's PMTiles extract, fonts/, sprites/ and
   style-*.json to Android/data/com.morton.trucknav/files/. The style JSON must
   reference the extract filename and the loopback server at 127.0.0.1:8781.
   Restart after installing assets. Initial position is last accepted GPS fix,
   otherwise the PMTiles v3 header centre. A fresh profile without a pack or GPS
   starts at the world origin until a fix arrives.
5. For offline routes add routing/valhalla_tiles.tar. Alternatively set your
   Valhalla URL in Settings → Servers. A blank URL is visibly labeled
   “Routing: On-device only”.
6. Set Home/Work in Settings → Places, or PUT /api/favorites/home|work. Choose a
   vehicle image in Settings → Vehicle or upload it through the API/MCP.
7. Configure servers, units, voice and auto night. Enter traffic keys only in
   Settings or the API/MCP. Configure TRUCKNAV_URL/TRUCKNAV_TOKEN in your MCP
   client's private environment. See API.md for the complete contract.

The emulator asset helper needs an explicit source directory:

    ~/bin/emu.sh assets trucknav ~/trucknav-assets

Never omit that source argument: the current helper expands an empty source to
the host root.

Acceptance (assigned emulator, lease required):

    SERIAL=emulator-5554 docs/emu-settings.sh <unique-tag>
    SERIAL=emulator-5554 docs/cockpit-smoke.sh <unique-tag> claude-vehicle

The Settings harness restores its temporary Home/Work, image and settings fixtures.
A pass requires measured visible puck replacement within 2 seconds, persistence
after force-stop, default restoration, real MCP requests, and zero crashes.
Empty-build/fresh-profile proof is recorded separately; an existing token does
not count as newly generated.
