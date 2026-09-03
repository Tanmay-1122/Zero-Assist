# Aster Local Plugin

This Codex plugin connects to the Aster Local MCP server shown in the Android app.

## Setup

1. Open the app's `Local MCP` screen.
2. Tap `Start MCP`.
3. Keep the phone and this computer on the same network.
4. If the phone IP changes, update `plugins/aster-local/.mcp.json` with the current `Local Network` endpoint.

The configured endpoint is:

```json
{
  "mcpServers": {
    "aster-local": {
      "type": "http",
      "url": "http://10.197.43.57:8080/mcp"
    }
  }
}
```

Use the `127.0.0.1` endpoint only from the same device that is running the Android app. From this Windows workspace, use the LAN or Tailscale IP.
