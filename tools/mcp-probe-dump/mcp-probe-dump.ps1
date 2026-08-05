# mcp-probe-dump.ps1
#
# Standalone diagnostic tool for the Zero-Assist -> Autodesk Fusion 360 MCP
# connectivity investigation.
#
# Replays EXACTLY the HTTP request that Zero-Assist's MCP validator
# (app/src/main/java/com/zeroclaw/android/data/remote/McpServerProbe.kt)
# sends on "Validate" and dumps the RAW request and RAW response
# (status line, every header, body) for inspection.
#
# No production code is touched or required.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File mcp-probe-dump.ps1
#   powershell -ExecutionPolicy Bypass -File mcp-probe-dump.ps1 -Url http://127.0.0.1:27182/mcp
#   powershell -ExecutionPolicy Bypass -File mcp-probe-dump.ps1 -HostOverride "192.168.1.50:27182"
#   powershell -ExecutionPolicy Bypass -File mcp-probe-dump.ps1 -AddSessionId "b7c8d9e0-f1a2-4b3c-8d4e-5f6a7b8c9d0e"
#
# -HostOverride  simulates a client connecting via the PC's LAN IP through the
#                Windows portproxy (the Android-phone scenario). The Host
#                header is sent verbatim, exactly as the phone's OkHttp client
#                would send it.
# -AddSessionId  adds the Mcp-Session-Id header (to demonstrate Fusion's
#                session lookup path).
# -RawSocket     optional: sends the request over a raw TCP socket so that
#                even the transport bytes are visible.

param(
    [string]$Url = "http://127.0.0.1:27182/mcp",
    [string]$HostOverride = "",
    [string]$AddSessionId = "",
    [switch]$RawSocket
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# The exact initialize body and headers used by McpServerProbe.kt
# (buildInitializeBody / buildRequest, lines 279-302).
# ---------------------------------------------------------------------------
$PROTOCOL_VERSION = "2024-11-05"
$Body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"' + $PROTOCOL_VERSION + '","capabilities":{},"clientInfo":{"name":"zeroclaw-android","version":"1.0"}}}'

$headers = [ordered]@{
    "User-Agent"           = "ZeroClaw-MCP-Validator/1.0"
    "Cache-Control"        = "no-cache"
    "Accept"               = "application/json, text/event-stream"
    "MCP-Protocol-Version" = $PROTOCOL_VERSION
    "Content-Type"         = "application/json"
}
if ($AddSessionId) {
    $headers["Mcp-Session-Id"] = $AddSessionId
}

function Write-Section([string]$title) {
    Write-Output ("`n" + ("=" * 72))
    Write-Output "  $title"
    Write-Output ("=" * 72)
}

Write-Section "RAW REQUEST"
Write-Output ("URL:    $Url")
Write-Output ("Method: POST")
$uri = [System.Uri]$Url
$effectiveHost = if ($HostOverride) { $HostOverride } else { $uri.Host + ":" + $uri.Port }
Write-Output ("Host:   $effectiveHost  (note: phone via portproxy sends the LAN IP here)")
Write-Output ("")
Write-Output "Request headers as Zero-Assist sends them:"
foreach ($k in $headers.Keys) {
    Write-Output ("  {0}: {1}" -f $k, $headers[$k])
}
Write-Output "Request body:"
Write-Output ("  " + $Body)

# ---------------------------------------------------------------------------
# Send
# ---------------------------------------------------------------------------
if ($RawSocket) {
    Write-Section "RAW TCP EXCHANGE (raw socket)"
    $hostPart, $portPart = $effectiveHost.Split(":")
    if (-not $portPart) { $portPart = "80" }
    $client = [System.Net.Sockets.TcpClient]::new($hostPart, [int]$portPart)
    try {
        $stream = $client.GetStream()
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.Append("POST $($uri.PathAndQuery) HTTP/1.1`r`n")
        [void]$sb.Append("Host: $effectiveHost`r`n")
        foreach ($k in $headers.Keys) {
            [void]$sb.Append("$k`: $($headers[$k])`r`n")
        }
        [void]$sb.Append("Content-Length: $($Body.Length)`r`n")
        [void]$sb.Append("Connection: close`r`n`r`n")
        [void]$sb.Append($Body)
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($sb.ToString())
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
        $responseText = $reader.ReadToEnd()
        Write-Output $responseText
    } finally {
        $client.Close()
    }
} else {
    # HttpWebRequest gives full header control (including Host).
    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.Method = "POST"
    $req.ContentType = $headers["Content-Type"]
    $req.Accept = $headers["Accept"]
    $req.UserAgent = $headers["User-Agent"]
    if ($HostOverride) { $req.Host = $HostOverride }
    foreach ($k in $headers.Keys) {
        if ($k -in @("Content-Type", "Accept", "User-Agent")) { continue }
        try { $req.Headers.Add($k, $headers[$k]) } catch { Write-Output ("  (skipped header $k : $_)") }
    }
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $req.ContentLength = $bodyBytes.Length
    $reqStream = $req.GetRequestStream()
    $reqStream.Write($bodyBytes, 0, $bodyBytes.Length)
    $reqStream.Close()

    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
    }

    Write-Section "RAW RESPONSE"
    Write-Output ("Status: HTTP {0} {1}" -f [int]$resp.StatusCode, $resp.StatusDescription)
    Write-Output "Response headers:"
    foreach ($hk in $resp.Headers.AllKeys) {
        Write-Output ("  {0}: {1}" -f $hk, $resp.Headers[$hk])
    }
    Write-Output "Response body:"
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
    $respBody = $reader.ReadToEnd()
    Write-Output ("  " + $respBody)
    $reader.Close()
    $resp.Close()
}

Write-Output ("`n" + ("-" * 72))
Write-Output "Interpretation hint:"
Write-Output "  * 403 + body `"Invalid Host header`" = Fusion's host allowlist rejected the"
Write-Output "    Host header (phone scenario). NOT an authentication problem."
Write-Output "  * 400 + body `"Missing MCP-Session-Id header`" = Fusion requires a session"
Write-Output "    id on every request, even initialize (non-standard behavior)."
Write-Output "  * 404 + body `"Session not found`" = session id not known to Fusion."
