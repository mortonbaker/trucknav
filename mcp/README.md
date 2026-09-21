# TruckNav MCP

The stdio server wraps TruckNav's authenticated HTTP API on port 8782. It uses `mcp<2` (the project's installed runtime is 1.30.0).

## Install

Run from the repository on the MCP host:

```bash
python3 -m venv ~/trucknav-mcp/venv
~/trucknav-mcp/venv/bin/pip install "mcp<2"
cp mcp/trucknav_mcp.py mcp/run.sh ~/trucknav-mcp/
```

Register the existing atlas01 deployment with Claude Code:

```bash
claude mcp add trucknav -- ssh morton@atlas01 ~/trucknav-mcp/run.sh
```

## Configuration

`TRUCKNAV_URL` selects the tablet's base URL; the existing truck defaults to `http://100.95.16.47:8782`. `TRUCKNAV_TOKEN` supplies the bearer token. Fresh installations generate a token in private `files/settings.json`; Settings -> API shows its QR code and offers Regenerate. Keep the token in the host's private environment configuration. Regeneration immediately invalidates the old token, so update the client's private configuration before reconnecting.

For existing installations only, the wrapper falls back to `apiToken` in `~/trucknav/local.properties` if `TRUCKNAV_TOKEN` is unset. New installs do not need a source-tree token. Never put TomTom or Google Maps credentials in source or `local.properties`; set them through the authenticated Settings API/MCP.

## Tools

- Settings: `get_settings()`, `set_setting(key, value)`. Secret values are returned masked to their last four characters; short secrets are fully masked. `None` removes a setting. The runtime keys include `tomtomKey`, `googleMapsKey`, and `trafficProvider` (`off`, `tomtom`, or `google`).
- Places: `list_favorites()`, `list_recent()`, `add_favorite(...)`, `remove_favorite(...)`, `set_home(lat, lng, name)`, `set_work(lat, lng, name)`. Home and Work replace their singleton saved places and update the map tiles.
- Vehicle: `upload_vehicle(path)` reads a file on the MCP host. Supply a PNG or JPEG no larger than 2 MiB, top view with the nose up. Transparent PNG is recommended; JPEG has no alpha. The app fits the image into a transparent 256 x 256 PNG and hot-reloads the puck. The tool's docstring includes the same specification.
- Navigation: `status()`, `navigate_to(...)`, `add_stop(...)`, `stop_navigation()`.

See [API reference](../docs/API.md) and [Set up for your own truck](../README.md) for endpoint details and runtime server configuration.
