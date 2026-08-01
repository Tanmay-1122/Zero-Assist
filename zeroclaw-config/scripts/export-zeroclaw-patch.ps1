param(
    [string]$OutputFile = "zeroclaw-config/patches/0001-local-zeroclaw.patch"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

if (-not (Test-Path "zeroclaw")) {
    throw "Expected 'zeroclaw' directory in repo root."
}

$patchDir = Split-Path -Parent $OutputFile
if (-not [string]::IsNullOrWhiteSpace($patchDir) -and -not (Test-Path $patchDir)) {
    New-Item -ItemType Directory -Path $patchDir | Out-Null
}

& git diff --binary --output=$OutputFile -- zeroclaw
if ($LASTEXITCODE -ne 0) {
    throw "Failed to export patch stack from 'zeroclaw'."
}

Write-Host "Exported patch to $OutputFile"
