#!/bin/bash
# proot-browser-verify.sh
# Verifies browser tools are installed correctly in PRoot
# Version: 1.0.0
# Created: 2026-07-28

set -euo pipefail

# Configuration
DISTRO="${DISTRO:-alpine}"
ERRORS=0
WARNINGS=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check functions
check_pass() {
    echo -e "${GREEN}✓${NC} $1"
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    ERRORS=$((ERRORS + 1))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    WARNINGS=$((WARNINGS + 1))
}

check_command() {
    local desc="$1"
    local cmd="$2"
    
    if proot-distro login "${DISTRO}" -- bash -c "$cmd" &>/dev/null; then
        check_pass "$desc"
        return 0
    else
        check_fail "$desc"
        return 1
    fi
}

check_optional() {
    local desc="$1"
    local cmd="$2"
    
    if proot-distro login "${DISTRO}" -- bash -c "$cmd" &>/dev/null; then
        check_pass "$desc"
        return 0
    else
        check_warn "$desc (optional)"
        return 1
    fi
}

# Version check functions
get_version() {
    local cmd="$1"
    proot-distro login "${DISTRO}" -- bash -c "$cmd" 2>&1 | head -1 || echo "unknown"
}

# Main verification
main() {
    echo "========================================="
    echo "  PRoot Browser Tool Verification"
    echo "========================================="
    echo "Distro: ${DISTRO} (Alpine Linux)"
    echo ""
    
    # Section 1: Core Prerequisites
    echo "Core Prerequisites:"
    check_command "  PRoot distro accessible" "exit 0"
    check_command "  apk package manager available" "which apk"
    check_command "  curl available" "which curl"
    echo ""
    
    # Section 2: Node.js Ecosystem
    echo "Node.js Ecosystem:"
    if check_command "  Node.js installed" "node --version"; then
        NODE_VERSION=$(get_version "node --version")
        echo "    Version: ${NODE_VERSION}"
    fi
    
    if check_command "  npm installed" "npm --version"; then
        NPM_VERSION=$(get_version "npm --version")
        echo "    Version: ${NPM_VERSION}"
    fi
    
    if check_command "  agent-browser installed" "agent-browser --version"; then
        AB_VERSION=$(get_version "agent-browser --version")
        echo "    Version: ${AB_VERSION}"
        
        # Test agent-browser execution
        if proot-distro login "${DISTRO}" -- agent-browser --help &>/dev/null; then
            check_pass "  agent-browser executable"
        else
            check_fail "  agent-browser executable"
        fi
    fi
    echo ""
    
    # Section 3: Browser Binaries
    echo "Browser Binaries:"
    if proot-distro login "${DISTRO}" -- bash -c "which chromium-browser || which chromium" &>/dev/null; then
        CHROME_BIN=$(proot-distro login "${DISTRO}" -- bash -c "which chromium-browser || which chromium" 2>/dev/null)
        check_pass "  Chromium installed"
        echo "    Path: ${CHROME_BIN}"
        
        # Get Chrome version
        CHROME_VERSION=$(proot-distro login "${DISTRO}" -- bash -c "($CHROME_BIN --version 2>&1 || echo 'unknown') | head -1")
        echo "    Version: ${CHROME_VERSION}"
        
        # Test headless mode
        if proot-distro login "${DISTRO}" -- bash -c "$CHROME_BIN --headless --no-sandbox --dump-dom https://example.com" &>/dev/null; then
            check_pass "  Chromium headless mode works"
        else
            check_warn "  Chromium headless mode (connectivity issue?)"
        fi
    else
        check_fail "  Chromium installed"
    fi
    echo ""
    
    # Section 4: Text Browsers
    echo "Text Browsers:"
    check_command "  lynx installed" "which lynx"
    check_command "  links installed" "which links"
    check_command "  w3m installed" "which w3m"
    
    # Test text browser execution
    if proot-distro login "${DISTRO}" -- lynx -dump https://example.com &>/dev/null; then
        check_pass "  lynx execution works"
    else
        check_warn "  lynx execution (connectivity issue?)"
    fi
    echo ""
    
    # Section 5: WebDriver (Optional)
    echo "WebDriver (Optional):"
    if check_optional "  ChromeDriver installed" "which chromedriver"; then
        DRIVER_VERSION=$(get_version "chromedriver --version")
        echo "    Version: ${DRIVER_VERSION}"
        
        # Check if chromedriver-start wrapper exists
        if check_command "  chromedriver-start wrapper" "[ -x /usr/local/bin/chromedriver-start ]"; then
            echo "    Lifecycle: on-demand (managed by Rust)"
        fi
        
        check_command "  chromedriver-stop wrapper" "[ -x /usr/local/bin/chromedriver-stop ]"
    fi
    echo ""
    
    # Section 6: Environment Scripts
    echo "Environment Scripts:"
    check_command "  zeroclaw-browser-env wrapper" "[ -x /usr/local/bin/zeroclaw-browser-env ]"
    
    # Test environment variables
    if proot-distro login "${DISTRO}" -- bash -c ". /usr/local/bin/zeroclaw-browser-env; [ -n \"\$CHROME_BIN\" ]" &>/dev/null; then
        check_pass "  Environment variables set"
    else
        check_warn "  Environment variables set"
    fi
    echo ""
    
    # Section 7: Network Connectivity
    echo "Network Connectivity:"
    if proot-distro login "${DISTRO}" -- bash -c "ip addr show | grep -q 127.0.0.1"; then
        check_pass "  Localhost (127.0.0.1) accessible"
    else
        check_fail "  Localhost (127.0.0.1) accessible"
    fi
    
    # Test external connectivity
    if proot-distro login "${DISTRO}" -- bash -c "curl -s --connect-timeout 5 https://example.com" &>/dev/null; then
        check_pass "  External HTTPS connectivity"
    else
        check_warn "  External HTTPS connectivity (network issue?)"
    fi
    
    # Test localhost port accessibility (for sidecar)
    if proot-distro login "${DISTRO}" -- bash -c "timeout 1 nc -z 127.0.0.1 8787" &>/dev/null; then
        check_pass "  Port 8787 (sidecar) reachable"
    else
        check_warn "  Port 8787 (sidecar) not running"
    fi
    
    # Test chromedriver port
    if proot-distro login "${DISTRO}" -- bash -c "timeout 1 nc -z 127.0.0.1 9515" &>/dev/null; then
        check_pass "  Port 9515 (ChromeDriver) reachable"
    else
        check_warn "  Port 9515 (ChromeDriver) not running"
    fi
    echo ""
    
    # Section 8: Disk Space
    echo "Resources:"
    DISK_USAGE=$(proot-distro login "${DISTRO}" -- df -h / | tail -1 | awk '{print $5}' | tr -d '%')
    echo "  Disk usage: ${DISK_USAGE}%"
    if [ "${DISK_USAGE}" -gt 90 ]; then
        check_warn "  Disk space low (${DISK_USAGE}% used)"
    else
        check_pass "  Disk space sufficient"
    fi
    
    # Memory check (if available)
    if proot-distro login "${DISTRO}" -- which free &>/dev/null; then
        MEM_INFO=$(proot-distro login "${DISTRO}" -- free -h | grep Mem | awk '{print $2}')
        echo "  Memory available: ${MEM_INFO}"
    fi
    echo ""
    
    # Summary
    echo "========================================="
    echo "  Verification Summary"
    echo "========================================="
    if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
        echo -e "${GREEN}All checks passed!${NC}"
        echo ""
        echo "Your PRoot environment is ready for browser automation."
        echo "Next step: bash scripts/test-browser-proot.sh"
        exit 0
    elif [ $ERRORS -eq 0 ]; then
        echo -e "${YELLOW}Passed with ${WARNINGS} warning(s)${NC}"
        echo ""
        echo "Your PRoot environment is functional but has optional components missing."
        echo "Browser tool will work with available backends."
        exit 0
    else
        echo -e "${RED}Failed: ${ERRORS} error(s), ${WARNINGS} warning(s)${NC}"
        echo ""
        echo "Please review the errors above and run setup again:"
        echo "  bash scripts/proot-browser-setup.sh"
        exit 1
    fi
}

# Run verification
main "$@"
