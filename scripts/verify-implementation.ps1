# verify-implementation.ps1
# Windows PowerShell script to verify PRoot Browser implementation
# Checks code, configuration, and documentation without running tests
# Version: 1.0.0
# Created: 2026-07-28

$ErrorActionPreference = "Continue"

# Colors
function Write-Header {
    param([string]$Message)
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host $Message -ForegroundColor Cyan
    Write-Host "========================================`n" -ForegroundColor Cyan
}

function Write-CheckSuccess {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-CheckFail {
    param([string]$Message)
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Write-CheckInfo {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Blue
}

function Write-CheckWarning {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

# Counters
$script:totalChecks = 0
$script:passedChecks = 0
$script:failedChecks = 0

function Test-Item {
    param(
        [string]$Name,
        [scriptblock]$Test
    )
    
    $script:totalChecks++
    try {
        $result = & $Test
        if ($result) {
            Write-CheckSuccess $Name
            $script:passedChecks++
            return $true
        } else {
            Write-CheckFail $Name
            $script:failedChecks++
            return $false
        }
    } catch {
        Write-CheckFail "$Name - Error: $_"
        $script:failedChecks++
        return $false
    }
}

# Get project root
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path "$projectRoot\app\build.gradle.kts")) {
    $projectRoot = "$PSScriptRoot\.."
}

Write-Host @"
╔═══════════════════════════════════════════════╗
║  PRoot Browser Implementation Verification    ║
║  Code & Documentation Check (Windows)         ║
╚═══════════════════════════════════════════════╝
"@ -ForegroundColor Cyan

Write-CheckInfo "Project root: $projectRoot"
Write-CheckInfo "Starting verification...`n"

# ==========================================
# Phase 1: File Existence Checks
# ==========================================
Write-Header "Phase 1: File Existence Checks"

Test-Item "Rust proot_executor.rs module exists" {
    Test-Path "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\proot_executor.rs"
}

Test-Item "Modified browser.rs with PRoot support" {
    $content = Get-Content "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\browser.rs" -Raw
    $content -match "proot_config" -and $content -match "with_proot_config"
}

Test-Item "Modified text_browser.rs with PRoot support" {
    $content = Get-Content "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\text_browser.rs" -Raw
    $content -match "proot_config"
}

Test-Item "Modified lib.rs exports proot_executor" {
    $content = Get-Content "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\lib.rs" -Raw
    $content -match "proot_executor"
}

Test-Item "AppSettings.kt has PRoot fields" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\model\AppSettings.kt" -Raw
    $content -match "prootBrowserEnabled" -and $content -match "prootBrowserDistro"
}

Test-Item "ConfigTomlBuilder.kt has appendProotBrowserSection" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\service\ConfigTomlBuilder.kt" -Raw
    $content -match "appendProotBrowserSection"
}

Test-Item "OfficialPlugins.kt has PROOT_BROWSER" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\model\OfficialPlugins.kt" -Raw
    $content -match "PROOT_BROWSER"
}

Test-Item "SeedData.kt has PROOT_BROWSER plugin" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\data\local\SeedData.kt" -Raw
    $content -match "PROOT_BROWSER"
}

# ==========================================
# Phase 2: Script Files
# ==========================================
Write-Header "Phase 2: Script Files"

Test-Item "proot-browser-setup.sh exists" {
    Test-Path "$projectRoot\scripts\proot-browser-setup.sh"
}

Test-Item "proot-browser-verify.sh exists" {
    Test-Path "$projectRoot\scripts\proot-browser-verify.sh"
}

Test-Item "test-security-proot.sh exists" {
    Test-Path "$projectRoot\scripts\test-security-proot.sh"
}

Test-Item "test-sidecar-proot.sh exists" {
    Test-Path "$projectRoot\scripts\test-sidecar-proot.sh"
}

Test-Item "run-all-tests.sh exists" {
    Test-Path "$projectRoot\scripts\run-all-tests.sh"
}

Test-Item "Setup script uses Alpine (apk)" {
    $content = Get-Content "$projectRoot\scripts\proot-browser-setup.sh" -Raw
    $content -match "apk add" -and $content -match "alpine"
}

# ==========================================
# Phase 3: Documentation
# ==========================================
Write-Header "Phase 3: Documentation"

Test-Item "AI-INTEGRATION-TEST-GUIDE.md exists" {
    Test-Path "$projectRoot\docs\AI-INTEGRATION-TEST-GUIDE.md"
}

Test-Item "ROLLBACK-VERIFICATION-GUIDE.md exists" {
    Test-Path "$projectRoot\docs\ROLLBACK-VERIFICATION-GUIDE.md"
}

Test-Item "ALPINE-PROOT-NOTES.md exists" {
    Test-Path "$projectRoot\docs\ALPINE-PROOT-NOTES.md"
}

Test-Item "HOW-TO-TEST.md exists" {
    Test-Path "$projectRoot\docs\HOW-TO-TEST.md"
}

Test-Item "TASKS-5-8-READY.md exists" {
    Test-Path "$projectRoot\TASKS-5-8-READY.md"
}

# ==========================================
# Phase 4: Content Verification
# ==========================================
Write-Header "Phase 4: Content Verification"

Test-Item "proot_executor.rs has ProotConfig struct" {
    $content = Get-Content "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\proot_executor.rs" -Raw
    $content -match "pub struct ProotConfig"
}

Test-Item "proot_executor.rs has ChromeDriverManager" {
    $content = Get-Content "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\proot_executor.rs" -Raw
    $content -match "pub struct ChromeDriverManager"
}

Test-Item "proot_executor.rs has execute_command function" {
    $content = Get-Content "$projectRoot\zeroclaw\crates\zeroclaw-tools\src\proot_executor.rs" -Raw
    $content -match "execute_command"
}

Test-Item "AppSettings defaults to Alpine" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\model\AppSettings.kt" -Raw
    $content -match 'prootBrowserDistro.*=.*"alpine"'
}

Test-Item "Security test checks file:// blocking" {
    $content = Get-Content "$projectRoot\scripts\test-security-proot.sh" -Raw
    $content -match "file://" -and $content -match "block"
}

Test-Item "Security test checks localhost blocking" {
    $content = Get-Content "$projectRoot\scripts\test-security-proot.sh" -Raw
    $content -match "localhost" -and $content -match "block"
}

Test-Item "Sidecar test checks network namespace" {
    $content = Get-Content "$projectRoot\scripts\test-sidecar-proot.sh" -Raw
    $content -match "network.*namespace"
}

Test-Item "AI Integration guide has 10 scenarios" {
    $content = Get-Content "$projectRoot\docs\AI-INTEGRATION-TEST-GUIDE.md" -Raw
    ($content -split "Scenario \d+:").Count -ge 10
}

Test-Item "Rollback guide has 8 scenarios" {
    $content = Get-Content "$projectRoot\docs\ROLLBACK-VERIFICATION-GUIDE.md" -Raw
    ($content -split "Scenario \d+:").Count -ge 8
}

# ==========================================
# Phase 5: Configuration Checks
# ==========================================
Write-Header "Phase 5: Configuration Checks"

Test-Item "AppSettings has all 7 PRoot fields" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\model\AppSettings.kt" -Raw
    $fields = @(
        "prootBrowserEnabled",
        "prootBrowserDistro",
        "prootBrowserBackend",
        "prootBrowserSessionName",
        "prootBrowserChromeDriverPort",
        "prootBrowserAllowedDomains",
        "prootBrowserMaxActionsPerHour"
    )
    $allFound = $true
    foreach ($field in $fields) {
        if ($content -notmatch $field) {
            Write-CheckWarning "Missing field: $field"
            $allFound = $false
        }
    }
    $allFound
}

Test-Item "ConfigTomlBuilder generates [browser.proot] section" {
    $content = Get-Content "$projectRoot\app\src\main\java\com\zeroclaw\android\service\ConfigTomlBuilder.kt" -Raw
    $content -match '\[browser\.proot\]' -or $content -match 'browser\.proot'
}

# ==========================================
# Summary
# ==========================================
Write-Header "Verification Summary"

Write-Host "Total checks:  $totalChecks"
Write-Host "Passed:        " -NoNewline
Write-Host $passedChecks -ForegroundColor Green
Write-Host "Failed:        " -NoNewline
Write-Host $failedChecks -ForegroundColor Red

$percentage = [math]::Round(($passedChecks / $totalChecks) * 100, 1)
Write-Host "`nSuccess rate:  $percentage%"

Write-Host "`n========================================`n"

if ($failedChecks -eq 0) {
    Write-CheckSuccess "All implementation checks passed!"
    Write-Host "`nImplementation is complete and ready for testing."
    Write-Host "`nNext steps:"
    Write-Host "  1. Build and install the app on Android device"
    Write-Host "  2. Follow testing guide: docs\HOW-TO-TEST.md"
    Write-Host "  3. Execute automated tests (Tasks 5-6)"
    Write-Host "  4. Execute manual tests (Tasks 7-8)"
    exit 0
} else {
    Write-CheckFail "Some implementation checks failed!"
    Write-Host "`nPlease review the failures above and fix them."
    Write-Host "`nRerun this script to verify fixes:"
    Write-Host "  .\scripts\verify-implementation.ps1"
    exit 1
}
