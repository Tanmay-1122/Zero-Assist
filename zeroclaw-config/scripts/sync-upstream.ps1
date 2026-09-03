param(
    [string]$ZeroClawPath = "zeroclaw",
    [string]$UpstreamRemote = "zeroclaw-upstream",
    [string]$UpstreamUrl = "https://github.com/zeroclaw-labs/zeroclaw.git",
    [string]$UpstreamRef = "master",
    [string]$PatchDirectory = "zeroclaw-config/patches",
    [switch]$SkipPatchApply,
    [switch]$AllowDirty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )
    & git @Args
    if ($LASTEXITCODE -ne 0) {
        throw "git command failed: git $($Args -join ' ')"
    }
}

function Assert-CleanState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathSpec
    )
    if ($AllowDirty) {
        return
    }
    $status = & git status --porcelain -- $PathSpec
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to inspect git status for '$PathSpec'"
    }
    if (-not [string]::IsNullOrWhiteSpace(($status -join "`n"))) {
        throw "Working tree is dirty under '$PathSpec'. Commit/stash changes or re-run with -AllowDirty."
    }
}

function Ensure-Remote {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RemoteName,
        [Parameter(Mandatory = $true)]
        [string]$RemoteUrl
    )
    $remotes = & git remote
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to list git remotes."
    }
    if (-not ($remotes -contains $RemoteName)) {
        Invoke-Git -Args @("remote", "add", $RemoteName, $RemoteUrl)
        return
    }
    Invoke-Git -Args @("remote", "set-url", $RemoteName, $RemoteUrl)
}

function Get-IsSubmodule {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathSpec
    )
    $lines = & git ls-files --stage -- $PathSpec
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    foreach ($line in $lines) {
        if ($line -match "^160000\s") {
            return $true
        }
    }
    return $false
}

function Assert-PathInsideRepo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CandidatePath,
        [Parameter(Mandatory = $true)]
        [string]$RepoRootPath
    )
    $resolvedCandidate = (Resolve-Path $CandidatePath).Path
    $resolvedRoot = (Resolve-Path $RepoRootPath).Path
    if (-not $resolvedCandidate.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing operation outside repository root: $resolvedCandidate"
    }
}

function Sync-VendoredDirectoryFromRemote {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRootPath,
        [Parameter(Mandatory = $true)]
        [string]$TargetPath,
        [Parameter(Mandatory = $true)]
        [string]$RemoteUrl,
        [Parameter(Mandatory = $true)]
        [string]$RefName
    )
    $tempDir = Join-Path $RepoRootPath ".tmp-zeroclaw-upstream-sync"
    if (Test-Path $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }

    try {
        Invoke-Git -Args @("clone", "--depth", "1", "--branch", $RefName, $RemoteUrl, $tempDir)

        $targetFull = Join-Path $RepoRootPath $TargetPath
        if (-not (Test-Path $targetFull)) {
            throw "Target path not found: $targetFull"
        }

        Assert-PathInsideRepo -CandidatePath $targetFull -RepoRootPath $RepoRootPath
        Assert-PathInsideRepo -CandidatePath $tempDir -RepoRootPath $RepoRootPath

        $robocopyLog = & robocopy $tempDir $targetFull /MIR /XD .git /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
        $robocopyExit = $LASTEXITCODE
        if ($robocopyExit -ge 8) {
            throw "robocopy mirror failed with exit code $robocopyExit.`n$($robocopyLog | Out-String)"
        }
    } finally {
        if (Test-Path $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force
        }
    }
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repoRoot

if (-not (Test-Path $ZeroClawPath)) {
    throw "Path not found: $ZeroClawPath"
}

Write-Host "Syncing '$ZeroClawPath' from $UpstreamUrl ($UpstreamRef)"

$isSubmodule = Get-IsSubmodule -PathSpec $ZeroClawPath
Assert-CleanState -PathSpec $ZeroClawPath

if ($isSubmodule) {
    Write-Host "Detected submodule layout."
    $submoduleRemotes = & git -C $ZeroClawPath remote
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to list remotes in submodule path '$ZeroClawPath'."
    }
    if (-not ($submoduleRemotes -contains $UpstreamRemote)) {
        Invoke-Git -Args @("-C", $ZeroClawPath, "remote", "add", $UpstreamRemote, $UpstreamUrl)
    } else {
        Invoke-Git -Args @("-C", $ZeroClawPath, "remote", "set-url", $UpstreamRemote, $UpstreamUrl)
    }
    Invoke-Git -Args @("-C", $ZeroClawPath, "fetch", $UpstreamRemote, $UpstreamRef)
    Invoke-Git -Args @("-C", $ZeroClawPath, "merge", "--ff-only", "FETCH_HEAD")
} else {
    Write-Host "Detected vendored/subtree layout."
    Ensure-Remote -RemoteName $UpstreamRemote -RemoteUrl $UpstreamUrl
    Invoke-Git -Args @("fetch", $UpstreamRemote, $UpstreamRef)
    Write-Host "Applying vendored sync strategy (clone + replace directory)."
    Sync-VendoredDirectoryFromRemote `
        -RepoRootPath $repoRoot `
        -TargetPath $ZeroClawPath `
        -RemoteUrl $UpstreamUrl `
        -RefName $UpstreamRef
}

if (-not $SkipPatchApply) {
    Write-Host "Re-applying local patch stack from '$PatchDirectory'"
    & (Join-Path $PSScriptRoot "apply-zeroclaw-patches.ps1") -PatchDirectory $PatchDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "Patch re-application failed."
    }
}

Write-Host "Upstream sync complete."
