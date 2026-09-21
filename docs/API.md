# TruckNav API and MCP

TruckNav listens on TCP port **8782**. Every request requires
`Authorization: Bearer <token>`. On a fresh installation the app creates a random
256-bit token in its private `files/settings.json`. View the token or its QR code
in **Settings → API**. Regenerate replaces it immediately; reconnect clients with
the new token. The QR contains the token, not a public link.

Use the API on a trusted LAN or tailnet. HTTP transport is unencrypted outside
Tailscale. Do not expose the port to the public Internet.

## Settings

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/settings` | None | JSON object of configured settings |
| PUT | `/api/settings` | JSON object mapping keys to strings or `null` | Updated settings with secrets masked |

PUT merges the supplied keys atomically; omitted keys are unchanged and `null`
deletes a key. Values must be strings, including booleans. Runtime provider keys
are entered here or on the Settings screen, never in source or `local.properties`.
Do not submit the masked GET response as a replacement for actual credentials.

Secret names ending in `Key`, `Token`, `Pass`, `Password` or `Secret` are masked.
Only the final four characters of longer secrets are returned. Short secrets are
fully masked. Credentials and query strings in URL settings are omitted from the
read representation. Request bodies are not recorded in NavLog.

| Key | Meaning |
|---|---|
| `tomtomKey` | Personal TomTom key; optional |
| `googleMapsKey` | Personal Google Maps key; optional |
| `trafficProvider` | `off`, `tomtom` or `google`; Google provides ETA only |
| `valhallaUrl` | Routing endpoint; blank selects on-device routing |
| `photonUrl` | Photon search endpoint |
| `styleUrl` | Map style JSON URL |
| `absUrl`, `absUser`, `absPass` | Audiobookshelf server and credentials |
| `venusHost`, `venusPortalId` | Venus MQTT host and portal identifier |
| `relayHost` | ESPHome host; blank enables discovery |
| `units` | `imperial` or `metric` |
| `voiceDisabled` | Comma-separated announcement classes to suppress |
| `autoNight` | `true` or `false` |
| `apiToken` | Current bearer token; changing it invalidates existing clients |
| `place.home`, `place.work` | Saved place summaries; use the favorites endpoints to change places |

Server changes apply on the next connection or request. Settings persist before
subscribers are notified. Kotlin consumers use
`com.morton.trucknav.settings.Settings.get(key)`, `set(key, value)` and
`flow(key): StateFlow<String?>` after application initialization.

## Vehicle image

| Method | Path | Behavior |
|---|---|---|
| PUT | `/api/vehicle` | Raw PNG/JPEG body, at most 2 MiB |
| GET | `/api/vehicle` | Uploaded PNG; 404 when the built-in vehicle is active |
| DELETE | `/api/vehicle` | Remove the upload and restore the built-in vehicle |

Use a top-down vehicle with its **nose pointing up**. A square transparent PNG
at 512 × 512 pixels is recommended. JPEG is accepted but keeps its background.
The image is fitted proportionally into a transparent **256 × 256** canvas and
stored atomically as private `files/vehicle.png`. The map puck subscribes to image
changes; a restart is unnecessary. Uploads persist across app restarts.

Supply `Content-Length`; chunked request bodies are not supported. An invalid
image returns 400; a body larger than 2 MiB returns 413. Failed uploads leave the
previous image intact. Images above 16,384 pixels on either axis are rejected.

## Places and navigation

| Method | Path | Request or result |
|---|---|---|
| GET | `/api/state` | Navigation, location, progress, instruction and version |
| GET | `/api/favorites` | Saved places with `id`, `name`, `lat`, `lng`, `kind` |
| GET | `/api/recent` | Recent destinations |
| PUT | `/api/favorites/home` | `{ "name": "Home", "lat": 40.0, "lng": -105.0 }` |
| PUT | `/api/favorites/work` | Same schema; replaces the Work singleton |
| POST | `/api/favorites` | `{ "name": "Depot", "lat": 40.0, "lng": -105.0, "kind": "place" }` |
| DELETE | `/api/favorites/<id>` | Remove one saved place |
| POST | `/api/navigate` | `{ "favorite": "<id>" }` or `{ "lat": 40.0, "lng": -105.0, "name": "Depot" }` |
| POST | `/api/add_stop` | Same destination schema; 409 when idle |
| POST | `/api/stop` | End navigation |

Home and Work updates immediately publish to their map tiles and survive restart.
Their coordinates must be finite, latitude −90…90 and longitude −180…180.

Other common statuses: 401 unauthorized, 400 invalid request, 404 unknown endpoint,
413 oversized request. JSON bodies are limited to 64 KiB. Malformed settings batches
do not partially apply.

## MCP

Run `mcp/trucknav_mcp.py` with a Python environment containing `mcp`. It speaks
stdio. Configure `TRUCKNAV_URL` and `TRUCKNAV_TOKEN` in the MCP client's private
environment. Legacy installations can still read `apiToken` from
`~/trucknav/local.properties`; fresh installations use the generated token.

| Tool | Purpose |
|---|---|
| `get_settings()` | Read masked settings |
| `set_setting(key, value)` | Set a string or delete with `null` |
| `set_home(lat, lng, name="Home")` | Replace Home |
| `set_work(lat, lng, name="Work")` | Replace Work |
| `upload_vehicle(path)` | Read a PNG/JPEG from the MCP server's filesystem and upload it |
| `status()`, `list_favorites()`, `list_recent()` | Inspect state and destinations |
| `add_favorite(...)`, `remove_favorite(id)` | Manage saved places |
| `navigate_to(...)`, `add_stop(...)`, `stop_navigation()` | Control navigation |

The upload tool's docstring includes format, size, orientation, transparency,
recommended input dimensions and server-side resizing requirements. Its path is
on the machine running MCP, not on the tablet.
