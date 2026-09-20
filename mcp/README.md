# trucknav MCP

Wraps the tablet HTTP API (port 8782, bearer token = apiToken in local.properties). Install on atlas01:

    python3 -m venv ~/trucknav-mcp/venv && ~/trucknav-mcp/venv/bin/pip install "mcp<2"
    cp mcp/trucknav_mcp.py mcp/run.sh ~/trucknav-mcp/

Register in Claude Code (stdio over ssh):

    claude mcp add trucknav -- ssh morton@atlas01 ~/trucknav-mcp/run.sh

Tools: status, list_favorites, list_recent, add_favorite, remove_favorite, navigate_to, stop_navigation. Env: TRUCKNAV_URL, TRUCKNAV_TOKEN.
