#!/usr/bin/env python3
"""trucknav MCP — favorites and routing on the 4Runner tablet, over its HTTP API.

Env: TRUCKNAV_URL (default http://100.95.16.47:8782), TRUCKNAV_TOKEN (default: apiToken from ~/trucknav/local.properties).
Run over stdio:  python3 trucknav_mcp.py
"""
import json, os, re, urllib.request
from mcp.server.fastmcp import FastMCP

URL = os.environ.get("TRUCKNAV_URL", "http://100.95.16.47:8782").rstrip("/")
def _token():
    t = os.environ.get("TRUCKNAV_TOKEN")
    if t: return t
    p = os.path.expanduser("~/trucknav/local.properties")
    m = re.search(r"^apiToken=(.+)$", open(p).read(), re.M) if os.path.exists(p) else None
    return m.group(1).strip() if m else ""

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(URL + path, data=data, method=method, headers={"Authorization": "Bearer " + _token(), "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.loads(r.read().decode() or "null")

mcp = FastMCP("trucknav")

@mcp.tool()
def status() -> dict:
    """Tablet navigation state: navigating, position, remaining miles/ETA, current instruction."""
    return call("GET", "/api/state")

@mcp.tool()
def list_favorites() -> list:
    """Saved places (kind home|work|place) with ids."""
    return call("GET", "/api/favorites")

@mcp.tool()
def list_recent() -> list:
    """Recent destinations, newest first."""
    return call("GET", "/api/recent")

@mcp.tool()
def add_favorite(name: str, lat: float, lng: float, kind: str = "place") -> dict:
    """Save a place. kind: 'home' or 'work' replace the existing one; 'place' adds. Appears on the tablet within seconds."""
    return call("POST", "/api/favorites", {"name": name, "lat": lat, "lng": lng, "kind": kind})

@mcp.tool()
def remove_favorite(favorite_id: str) -> dict:
    """Delete a favorite by id."""
    return call("DELETE", f"/api/favorites/{favorite_id}")

@mcp.tool()
def navigate_to(favorite_id: str = "", lat: float | None = None, lng: float | None = None, name: str = "") -> dict:
    """Start turn-by-turn navigation on the tablet, to a favorite id or to lat/lng."""
    body = {"favorite": favorite_id} if favorite_id else {"lat": lat, "lng": lng, "name": name}
    return call("POST", "/api/navigate", body)

@mcp.tool()
def add_stop(favorite_id: str = "", lat: float | None = None, lng: float | None = None, name: str = "") -> dict:
    """Add a stop to the route in progress (goes there next, then on to the destination). 409 if not navigating."""
    body = {"favorite": favorite_id} if favorite_id else {"lat": lat, "lng": lng, "name": name}
    return call("POST", "/api/add_stop", body)

@mcp.tool()
def stop_navigation() -> dict:
    """End the current route."""
    return call("POST", "/api/stop")

if __name__ == "__main__":
    mcp.run()
