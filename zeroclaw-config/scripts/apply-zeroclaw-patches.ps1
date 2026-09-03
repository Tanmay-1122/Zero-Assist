param(
    [string]$PatchDirectory = "zeroclaw-config/patches"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Invoke-GitApply {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args,
        [switch]$SuppressStderr
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($SuppressStderr) {
            & git @Args 2>$null
        } else {
            & git @Args
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Convert-PatchToUtf8IfNeeded {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PatchPath
    )
    $bytes = [System.IO.File]::ReadAllBytes($PatchPath)
    if ($bytes.Length -lt 2) {
        return $PatchPath
    }

    $isUtf16LeBom = ($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE)
    $isUtf16BeBom = ($bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF)
    if (-not $isUtf16LeBom -and -not $isUtf16BeBom) {
        return $PatchPath
    }

    $raw = Get-Content -LiteralPath $PatchPath -Raw
    $tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName() + ".patch")
    [System.IO.File]::WriteAllText($tempPath, $raw, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Converted UTF-16 patch to UTF-8: $PatchPath"
    return $tempPath
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

if (-not (Test-Path "zeroclaw")) {
    throw "Expected 'zeroclaw' directory in repo root."
}

if (-not (Test-Path $PatchDirectory)) {
    throw "Patch directory not found: $PatchDirectory"
}

$patches = @(
    Get-ChildItem -Path $PatchDirectory -Filter *.patch -File |
        Sort-Object Name
)

if ($patches.Count -eq 0) {
    Write-Host "No patch files found in $PatchDirectory"
    exit 0
}

foreach ($patch in $patches) {
    Write-Host "Applying $($patch.FullName)"
    $patchPathToApply = Convert-PatchToUtf8IfNeeded -PatchPath $patch.FullName
    try {
        $threeWayExit = Invoke-GitApply -Args @("apply", "--3way", "--whitespace=nowarn", "--", $patchPathToApply) -SuppressStderr
        if ($threeWayExit -ne 0) {
            Write-Host "3-way apply failed for $($patch.Name); retrying without index merge."
            $plainExit = Invoke-GitApply -Args @("apply", "--whitespace=nowarn", "--", $patchPathToApply)
            if ($plainExit -ne 0) {
                throw "Failed to apply patch: $($patch.Name)"
            }
        }
    } finally {
        if ($patchPathToApply -ne $patch.FullName -and (Test-Path $patchPathToApply)) {
            Remove-Item -LiteralPath $patchPathToApply -Force
        }
    }
}

Write-Host "Applied $($patches.Count) patch(es)."
