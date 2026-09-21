# TruckNav

An Android head unit for a truck, without Google Play Services. One app owns the
screen: offline maps and navigation, music, audiobooks, YouTube, vehicle power and
relays. It runs as the HOME app on an inexpensive Android tablet.

Status: **alpha**. See [the plan](docs/PLAN.md), [measured smoke results](docs/SMOKE-TEST.md)
and [known bugs](docs/BUGS.md).

## What it does

- MapLibre maps with Protomaps PMTiles extracts, five map styles, Ferrostar
  navigation and Valhalla routing with an on-device fallback.
- Favorites, Home/Work, recent destinations, route previews, stops, arrival,
  voice classes, automatic night maps and a configurable vehicle puck.
- On-device Settings, generated API tokens, authenticated HTTP API and MCP tools.
- Music controls for existing media apps, Audiobookshelf playback and downloads,
  and a YouTube WebView.
- Victron/Venus MQTT telemetry and an ESPHome relay panel.

## Set up for your own truck

1. Build with JDK 21, Android SDK 36 and the included Gradle wrapper. Set
   `ANDROID_HOME` to the installed SDK. **An empty `local.properties` is supported.**
   Run `./gradlew assembleDebug` for an emulator or `./gradlew assembleRelease`
   with your release signing configuration for a physical tablet.
2. Install the APK and follow the first-run setup. TruckNav generates a private
   API token automatically. View its QR code in **Settings → API**. Regenerate
   there whenever clients should lose access.
3. In **Settings → Places**, set Home and Work from the current GPS fix, a search
   result or a long press on the map. No personal home coordinate is compiled in.
   Before GPS arrives, the initial position uses the last accepted fix, then the
   PMTiles extract centre.
4. In **Settings → Servers**, configure your Photon search, optional Valhalla
   routing endpoint, map style, Audiobookshelf credentials, Venus host/portal ID
   and relay host. Blank Valhalla means on-device routing; install its routing pack.
   Blank relay host enables discovery. Enter personal traffic-provider keys on
   the device or through the API/MCP—never in source or `local.properties`.
5. In **Settings → Vehicle**, choose **Replace vehicle**. Use a top-down,
   nose-up PNG/JPEG no larger than 2 MiB. A transparent 512 × 512 PNG works best.
   TruckNav saves a proportional 256 × 256 PNG and updates the map puck live.
   **Restore default vehicle** removes it.
6. Configure units, voice classes and automatic night maps in Settings. **About**
   shows the version and installed packs and exports navigation logs.
7. Install offline map and routing assets using [the runbook](docs/RUNBOOK.md).
   Without a local map pack the online demonstration map remains available;
   offline navigation needs both map and routing data for your region.

Existing installations can migrate their previous build configuration on first
launch; subsequent configuration lives in the app's private `files/settings.json`.
Never clear app data to upgrade: it deletes the offline basemap.

## API and MCP

The API listens on port 8782 with bearer authentication. Settings GET masks
secrets; PUT changes settings atomically. Home/Work and vehicle uploads can be
managed without touching the screen. See [docs/API.md](docs/API.md) for endpoints,
request limits, token setup and MCP tools.

`mcp/trucknav_mcp.py` provides `get_settings`, `set_setting`, `set_home`, `set_work`,
`upload_vehicle`, favorites and navigation tools. Supply `TRUCKNAV_URL` and
`TRUCKNAV_TOKEN` through your MCP client's private environment.

## Architecture

`CockpitScreen.kt` hosts the rail, navigation map, power strip and secondary panes.
The local asset server serves offline styles, fonts, sprites and PMTiles over
loopback HTTP. `settings/Settings.kt` persists configuration atomically and exposes
per-key StateFlows. `VehicleImage` persists and publishes the custom puck.
Navigation logs provide field evidence for routes, instructions and progress.

## Build and install

The Gradle wrapper and `gradle/libs.versions.toml` define the toolchain. Release
builds use arm64 and shrinking; debug builds include emulator architectures.
Release signing reads the existing private keystore properties file.

After replacing an APK, restore the HOME activity:

```sh
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell cmd package set-home-activity com.morton.trucknav/.MainActivity
adb -s "$SERIAL" shell am start -n com.morton.trucknav/.MainActivity
```

The runbook documents one-time location, notification, media-listener and overlay
grants. Physical tablet changes require the project lease. Do not clear app data,
kill the shared adb server, or cut the tablet's Wi-Fi.

## Verification and contributing

Read [docs/AGENTS.md](docs/AGENTS.md) before sharing a tree or device and
[CONTRIBUTING.md](CONTRIBUTING.md) before changing code.

`docs/cockpit-smoke.sh` checks 13 cockpit behaviors. `docs/emu-settings.sh` measures
S20 API/MCP behavior, vehicle pixels and persistence, and the hardcoded-coordinate
check. Use a unique evidence tag; results and screenshots belong under
`~/evidence/`, with measured receipts appended to `docs/SMOKE-TEST.md`.

## License

MIT — see [LICENSE](LICENSE).
