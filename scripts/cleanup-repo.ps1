#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Zero-Assist Repository Cleanup Script - Clean up unused folders and files
.DESCRIPTION
    Removes unused upstream crates, firmware, nix files, docker scripts,
    redundant documentation, and dead code. Creates a backup branch first.
.PARAMETER Confirm
    Request confirmation before executing each major step
.PARAMETER DryRun
    Show what would be deleted without actually deleting
#>

param(
    [switch]$Confirm = $true,
    [switch]$DryRun = $false
)

$ErrorActionPreference = "Stop"
$WarningPreference = "Continue"

# Colors for output
$InfoColor = "Cyan"
$SuccessColor = "Green"
$WarningColor = "Yellow"
$ErrorColor = "Red"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor $InfoColor
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor $SuccessColor
}

function Write-Warning-Custom {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor $WarningColor
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor $ErrorColor
}

function Confirm-Action {
    param([string]$Message)
    if (-not $Confirm) { return $true }
    
    $response = Read-Host "$Message (y/n)"
    return $response.Trim().ToLower() -eq 'y'
}

# ============================================================================
# SAFETY VERIFICATION & BACKUP
# ============================================================================

function Prepare-Backup {
    Write-Info "Preparing Git Backup..."
    
    # Check if git is available
    $gitFound = $null -ne (Get-Command git -ErrorAction SilentlyContinue)
    if (-not $gitFound) {
        Write-Warning-Custom "Git not found in PATH. Skipping automated Git backup branch creation."
        return
    }

    if ($DryRun) {
        Write-Info "DRY RUN: git branch backup/pre-cleanup-zeroclaw"
    } else {
        try {
            git branch backup/pre-cleanup-zeroclaw
            Write-Success "Backup branch created: backup/pre-cleanup-zeroclaw"
        } catch {
            Write-Warning-Custom "Failed to create backup branch. It may already exist."
        }
    }
}

# ============================================================================
# PHASE 1: REMOVE UNUSED CRATES AND CODE FROM SYSTEM
# ============================================================================

function Remove-Unused-Crates-And-Code {
    Write-Info "Removing Unused Upstream Crates and Code..."
    
    $itemsToRemove = @(
        # Unused Rust Crates
        "zeroclaw/crates/zeroclaw-tui",
        "zeroclaw/crates/zeroclaw-plugins",
        "zeroclaw/crates/zeroclaw-hardware",
        "zeroclaw/crates/robot-kit",
        "zeroclaw/crates/aardvark-sys",
        "zeroclaw/apps/tauri",
        "zeroclaw/tools/fill-translations",
        "zeroclaw/xtask",
        
        # Unused upstream workspace modules/folders
        "zeroclaw/tests",
        "zeroclaw/benches",
        "zeroclaw/fuzz",
        "zeroclaw/web",
        "zeroclaw/firmware",
        "zeroclaw/marketplace",
        "zeroclaw/dist",
        "zeroclaw/dev",
        "zeroclaw/scripts",
        "zeroclaw/.githooks",
        
        # Nix, Docker & Build setup configurations
        "zeroclaw/flake.nix",
        "zeroclaw/flake.lock",
        "zeroclaw/Dockerfile",
        "zeroclaw/Dockerfile.ci",
        "zeroclaw/Dockerfile.debian",
        "zeroclaw/Dockerfile.debian.ci",
        "zeroclaw/docker-compose.yml",
        "zeroclaw/.dockerignore",
        "zeroclaw/Justfile",
        "zeroclaw/install.sh",
        "zeroclaw/setup.bat",
        "zeroclaw/.env.example",
        "zeroclaw/.envrc",
        "zeroclaw/.actrc",
        "zeroclaw/.markdownlint-cli2.yaml",
        "zeroclaw/CNAME",
        "zeroclaw/locales.toml",
        "zeroclaw/release-plz.toml",
        
        # Redundant markdown/licenses
        "zeroclaw/README.md",
        "zeroclaw/CODE_OF_CONDUCT.md",
        "zeroclaw/CONTRIBUTING.md",
        "zeroclaw/SECURITY.md",
        "zeroclaw/TRANSLATIONS.md",
        "zeroclaw/LICENSE-APACHE",
        "zeroclaw/LICENSE-MIT",
        "zeroclaw/NOTICE",
        
        # Unreferenced files in zeroclaw-android
        "zeroclaw-android/agent.py"
    )

    $existingItems = @()
    $totalSavedBytes = 0

    foreach ($item in $itemsToRemove) {
        $fullPath = Join-Path (Get-Location).Path $item
        if (Test-Path $fullPath) {
            $existingItems += $item
            # Measure size
            $files = Get-ChildItem $fullPath -Recurse -File -ErrorAction SilentlyContinue
            $size = ($files | Measure-Object -Property Length -Sum).Sum
            $totalSavedBytes += $size
            Write-Info "  Found target: $item (~$([Math]::Round($size / 1MB, 2))MB)"
        }
    }

    if ($existingItems.Count -eq 0) {
        Write-Success "No unused crates or junk files found to clean."
        return
    }

    Write-Info "Total estimated space to free: ~$([Math]::Round($totalSavedBytes / 1MB, 2))MB"
    if (-not (Confirm-Action "Proceed to delete these $($existingItems.Count) items?")) {
        Write-Info "Skipping deletion."
        return
    }

    foreach ($item in $existingItems) {
        $fullPath = Join-Path (Get-Location).Path $item
        if ($DryRun) {
            Write-Info "DRY RUN: Remove-Item -Recurse -Force $item"
        } else {
            try {
                Remove-Item -Path $fullPath -Recurse -Force -ErrorAction Stop
                Write-Success "  Deleted: $item"
            } catch {
                Write-Warning-Custom "  Could not delete: $item. Error: $_"
            }
        }
    }
}

# ============================================================================
# PHASE 2: REMOVE DYNAMIC BUILD ARTIFACTS AND LOCAL CACHES
# ============================================================================

function Remove-Build-Artifacts-And-Caches {
    Write-Info "Cleaning Build Artifacts and Local Caches..."
    
    $cacheItems = @(
        "app/build",
        "lib/build",
        "zeroclaw-android/target",
        ".gradle",
        ".kotlin",
        ".android-user-home",
        ".idea",
        ".tmp-tools"
    )

    $existingCaches = @()
    foreach ($cache in $cacheItems) {
        $fullPath = Join-Path (Get-Location).Path $cache
        if (Test-Path $fullPath) {
            $existingCaches += $cache
            Write-Info "  Found cache: $cache"
        }
    }

    if ($existingCaches.Count -eq 0) {
        Write-Success "All build and cache directories are clean."
        return
    }

    if (-not (Confirm-Action "Proceed to delete these $($existingCaches.Count) local cache/build directories?")) {
        Write-Info "Skipping cache cleanup."
        return
    }

    foreach ($cache in $existingCaches) {
        $fullPath = Join-Path (Get-Location).Path $cache
        if ($DryRun) {
            Write-Info "DRY RUN: Remove-Item -Recurse -Force $cache"
        } else {
            try {
                Remove-Item -Path $fullPath -Recurse -Force -ErrorAction Stop
                Write-Success "  Cleaned: $cache"
            } catch {
                Write-Warning-Custom "  Could not clean cache: $cache (it may be in use by Gradle daemon)"
            }
        }
    }
}

# ============================================================================
# PHASE 3: FINAL CLEANUP & GIT REFRESH
# ============================================================================

function Run-Git-Refresh {
    Write-Info "Refreshing Git Repository status..."
    
    # Check if git is available
    $gitFound = $null -ne (Get-Command git -ErrorAction SilentlyContinue)
    if (-not $gitFound) { return }

    if ($DryRun) {
        Write-Info "DRY RUN: git rm -r --cached (deleted files)"
        Write-Info "DRY RUN: git gc --aggressive --prune=now"
    } else {
        # Check if we also want to remove CLEANUP_SUMMARY.txt
        if (Test-Path "CLEANUP_SUMMARY.txt") {
            if (Confirm-Action "Remove deprecated CLEANUP_SUMMARY.txt?") {
                Remove-Item "CLEANUP_SUMMARY.txt" -Force
                Write-Success "Removed CLEANUP_SUMMARY.txt"
            }
        }
        
        Write-Info "  Removing tracked references from Git cache..."
        git status --porcelain | ForEach-Object {
            if ($_ -match '^ D (.*)') {
                $file = $Matches[1].Trim()
                git rm --cached $file 2>$null | Out-Null
            }
        }
        
        Write-Info "  Running aggressive garbage collection (this may take a minute)..."
        git gc --aggressive --prune=now | Out-Null
        Write-Success "Git garbage collection complete."
    }
}

# ============================================================================
# MAIN EXECUTION
# ============================================================================

function Main {
    Write-Host ""
    Write-Host "+------------------------------------------------------------+"
    Write-Host "|  Zero-Assist Repository Deep Cleanup Script                |"
    Write-Host "+------------------------------------------------------------+"
    Write-Host ""
    
    # Verify script is run from root of Zero-Assist-main repo
    if (-not (Test-Path "zeroclaw") -or -not (Test-Path "app")) {
        Write-Error-Custom "Error: This script must be run from the root of Zero-Assist-main repository."
        exit 1
    }

    if ($DryRun) {
        Write-Warning-Custom "Running in DRY-RUN mode - no files will be deleted."
    }

    Prepare-Backup
    Remove-Unused-Crates-And-Code
    Remove-Build-Artifacts-And-Caches
    Run-Git-Refresh

    Write-Host ""
    Write-Success "Deep Cleanup Script Execution Completed!"
    Write-Host ""
}

Main
