# TruckNav

An Android head unit for a truck, without Google. One app owns the screen and draws
everything itself: offline map and turn-by-turn navigation, music, audiobooks, YouTube,
the vehicle's power system, and the vehicle's relays. It runs as the HOME app on a cheap
tablet (Samsung Galaxy Tab A7 Lite) mounted in a 4Runner, and it keeps working with no
signal.

Status: **alpha, one vehicle, built in the open.** Expect rough edges. See
[`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md) for what is done and what is next, and
[`docs/BUGS.md`](docs/BUGS.md) for what is known to be wrong.

## What it does today

- **Map + navigation** — [Ferrostar](https://github.com/stadiamaps/ferrostar) on
  [MapLibre](https://maplibre.org/), Protomaps PMTiles basemap stored on the tablet
  (3 GB for four states), routing from a self-hosted [Valhalla](https://github.com/valhalla/valhalla),
  geocoding from [Photon](https://photon.komoot.io/). Five map styles (light, dark, satellite,
  hybrid, terrain). Your own vehicle is the location marker. Camera padding that respects
  the side panel and orientation. Search pill built to in-car contrast/size rules.
  Lettered results with distance/ETA, route preview with alternates, favorites (Home/Work/places),
  add-a-stop, arrival card, speed-limit sign, per-class voice toggles, auto day/night, a GPS
  plausibility gate, and a guard that stops any other navigator so only one voice speaks.
- **HTTP API + MCP** — `:8782` (bearer token): state, favorites, navigate, add_stop, stop;
  `mcp/trucknav_mcp.py` wraps it so an agent can add places or start a route.
- **Music** — controls any app's media session; browses [Finamp](https://github.com/jmshrv/finamp) (Jellyfin) through MediaBrowser.
- **Audiobooks** — its own media3 player streaming from [Audiobookshelf](https://www.audiobookshelf.org/) with
  progress sync, ±30 s, and a 1.0–2.5× speed ladder.
- **YouTube** — a WebView pane (history-first, Live/Shorts filtering in progress).
- **Power** — live Victron data over MQTT from a Venus OS Raspberry Pi (battery, solar, loads),
  always visible in a strip under the map.
- **Vehicle** — a switch panel for an ESPHome relay board (Starlink power, rear lights, …),
  discovered on the local network.
- **Cockpit chrome** — immersive full screen, its own status strip (clock, Wi-Fi, tailnet,
  GPS age, battery), a rail of panes, overlay dock over foreign apps, navigation event log.

Everything is Material icons and driving-sized targets. No emoji, no Play Services.

## Architecture in one paragraph

`CockpitScreen.kt` is the head unit: a rail, the map column (Ferrostar
`DynamicallyOrientingNavigationView` + power strip), and a side pane. Panes are plain
composables (`media/`, `power/`, `books/`, `YouTubePane`). Map tiles, fonts, sprites and style
JSON are served to MapLibre by an in-app loopback HTTP server (`LocalAssetServer`) from the
app's external files directory, because MapLibre's `pmtiles://` reader needs an HTTP origin.
Routing, geocoding, audiobooks and the Venus MQTT broker are network services configured in
`local.properties`. `nav/NavLog.kt` writes a rolling log of every route, instruction and
progress tick so field bugs can be diagnosed after the drive.

## Building

Requirements: JDK 21, Android SDK 36, Gradle wrapper included. Kotlin/Compose/AGP versions are
in `gradle/libs.versions.toml`.

1. Copy `local.properties.example` to `local.properties` and fill it in. Nothing in the
   repo works without it; nothing in it belongs in a commit.
2. `./gradlew assembleRelease` (R8 + arm64-only, ~26 MB). Debug builds also work.
3. Push the map assets to the tablet once (they are not in the repo — see
   `docs/mkstyles.py` and `docs/BUILD-PLAN.md` → "Offline basemap"):
   `/sdcard/Android/data/com.morton.trucknav/files/{southcentral.pmtiles,fonts/,sprites/,style-*.json}`.
4. Install, then make it the home app and grant the listeners it relies on:
   ```
   adb install -r app/build/outputs/apk/release/app-release.apk
   adb shell cmd package set-home-activity com.morton.trucknav/.MainActivity
   adb shell cmd notification allow_listener com.morton.trucknav/.overlay.MediaListener
   adb shell appops set com.morton.trucknav SYSTEM_ALERT_WINDOW allow
   adb shell appops set com.morton.trucknav GET_USAGE_STATS allow
   ```
   Never `pm clear` the app: that deletes the basemap.

## Testing

Tests run against the real tablet over adb and measure screenshots instead of trusting eyes:
`docs/*.sh` drive the UI with `uiautomator`, `docs/puck.py`, `contrast.py`, `mapstat.py` read the
pixels, and results are appended to `docs/SMOKE-TEST.md` with the version and date. Every slice
in `docs/BUILD-PLAN.md` has done-criteria a script can falsify.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md). Short version: pick a slice or a bug from the docs,
keep the done-criteria falsifiable, put no secrets or home coordinates in the tree, Material
icons only, and prove it on a device before calling it done.

## License

MIT — see [`LICENSE`](LICENSE).
