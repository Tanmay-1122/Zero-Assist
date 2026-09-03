#!/bin/bash
# test-browser-proot.sh
# Tests browser tool invocation from PRoot
# Usage: bash scripts/test-browser-proot.sh [--distro ubuntu] [--test-all]
set -euo pipefail

# Default configuration
DISTRO="${DISTRO:-alpine}"
TEST_ALL="${TEST_ALL:-false}"
VERBOSE="${VERBOSE:-false}"
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[PASS]${NC} $1"; }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --distro)
            DISTRO="$2"
            shift 2
            ;;
        --test-all)
            TEST_ALL=true
            shift
            ;;
        --verbose)
            VERBOSE=true
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --distro NAME   PRoot distro name (default: ubuntu)"
            echo "  --test-all      Run all tests including optional ones"
            echo "  --verbose       Enable verbose output"
            echo "  --help          Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Run a test and record result
run_test() {
    local test_name="$1"
    local test_cmd="$2"
    local required="${3:-true}"
    
    TESTS_RUN=$((TESTS_RUN + 1))
    
    if $VERBOSE; then
        echo -n "  Running: $test_cmd ... "
    fi
    
    if eval "$test_cmd" &>/dev/null; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "$test_name"
        return 0
    else
        if [ "$required" = "true" ]; then
            TESTS_FAILED=$((TESTS_FAILED + 1))
            log_fail "$test_name"
        else
            log_warn "$test_name (optional)"
        fi
        return 1
    fi
}

# Test agent-browser backend
test_agent_browser_backend() {
    echo ""
    echo "=== Agent-Browser Backend Tests ==="
    
    # Test open
    run_test "Open URL" \
        "proot-distro login $DISTRO -- agent-browser open https://example.com --json"
    
    # Test snapshot
    run_test "Take snapshot" \
        "proot-distro login $DISTRO -- agent-browser snapshot --json"
    
    # Test click (with ref from snapshot)
    run_test "Click element" \
        "proot-distro login $DISTRO -- agent-browser click '@e1' --json" \
        false
    
    # Test screenshot
    run_test "Take screenshot" \
        "proot-distro login $DISTRO -- agent-browser screenshot --json"
    
    # Test get title
    run_test "Get page title" \
        "proot-distro login $DISTRO -- agent-browser get title --json"
    
    # Test get url
    run_test "Get current URL" \
        "proot-distro login $DISTRO -- agent-browser get url --json"
    
    # Test close
    run_test "Close browser" \
        "proot-distro login $DISTRO -- agent-browser close --json"
}

# Test text browsers
test_text_browser() {
    echo ""
    echo "=== Text Browser Tests ==="
    
    # Test lynx
    run_test "lynx -dump" \
        "proot-distro login $DISTRO -- lynx -dump https://example.com" \
        false
    
    # Test links
    run_test "links -dump" \
        "proot-distro login $DISTRO -- links -dump https://example.com" \
        false
    
    # Test w3m
    run_test "w3m -dump" \
        "proot-distro login $DISTRO -- w3m -dump https://example.com" \
        false
}

# Test rust native backend (with on-demand ChromeDriver)
test_rust_native_backend() {
    echo ""
    echo "=== Rust Native Backend Tests (On-Demand ChromeDriver) ==="
    
    # Check if chromedriver-start wrapper exists
    if ! proot-distro login $DISTRO -- test -x /usr/local/bin/chromedriver-start &>/dev/null; then
        log_warn "chromedriver-start wrapper not found - skipping rust native tests"
        return
    fi
    
    # Stop any existing instance
    proot-distro login $DISTRO -- chromedriver-stop &>/dev/null || true
    
    # Test on-demand startup
    run_test "ChromeDriver on-demand startup" \
        "proot-distro login $DISTRO -- chromedriver-start"
    
    # Give it a moment to initialize
    sleep 1
    
    # Test ChromeDriver status
    run_test "ChromeDriver status endpoint" \
        "proot-distro login $DISTRO -- curl -s http://127.0.0.1:9515/status"
    
    # Test ChromeDriver session creation
    run_test "ChromeDriver session creation" \
        "proot-distro login $DISTRO -- curl -s -X POST http://127.0.0.1:9515/session -H 'Content-Type: application/json' -d '{\"capabilities\": {\"alwaysMatch\": {\"goog:chromeOptions\": {\"args\": [\"--headless\", \"--no-sandbox\"]}}}}'" \
        false
    
    # Test cleanup
    run_test "ChromeDriver cleanup (stop)" \
        "proot-distro login $DISTRO -- chromedriver-stop"
}

# Test computer use sidecar
test_computer_use_sidecar() {
    echo ""
    echo "=== Computer Use Sidecar Tests ==="
    
    # Check if sidecar is running
    if ! curl -s http://127.0.0.1:8787/v1/actions &>/dev/null; then
        log_warn "Sidecar not running - skipping sidecar tests"
        return
    fi
    
    # Test sidecar reachability
    run_test "Sidecar reachable" \
        "curl -s http://127.0.0.1:8787/v1/actions"
    
    # Test sidecar POST request
    run_test "Sidecar POST" \
        "curl -s -X POST http://127.0.0.1:8787/v1/actions -H 'Content-Type: application/json' -d '{\"action\": \"screen_capture\", \"params\": {}}'" \
        false
}

# Test security features
test_security_features() {
    echo ""
    echo "=== Security Feature Tests ==="
    
    # Test URL validation - blocked domain
    run_test "Blocked domain rejected" \
        "proot-distro login $DISTRO -- agent-browser open https://evil.com --json 2>&1 | grep -q error"
    
    # Test URL validation - file:// blocked
    run_test "file:// URL blocked" \
        "proot-distro login $DISTRO -- agent-browser open file:///etc/passwd --json 2>&1 | grep -q error"
    
    # Test URL validation - localhost blocked
    run_test "localhost blocked" \
        "proot-distro login $DISTRO -- agent-browser open https://localhost --json 2>&1 | grep -q error"
    
    # Test URL validation - private IP blocked
    run_test "Private IP blocked" \
        "proot-distro login $DISTRO -- agent-browser open https://192.168.1.1 --json 2>&1 | grep -q error"
}

# Test performance
test_performance() {
    echo ""
    echo "=== Performance Tests ==="
    
    # Measure open time
    local start_time=$(date +%s%N)
    proot-distro login $DISTRO -- agent-browser open https://example.com --json &>/dev/null
    local end_time=$(date +%s%N)
    local duration=$(( (end_time - start_time) / 1000000 ))
    
    if [ $duration -lt 5000 ]; then
        log_success "Open time: ${duration}ms (< 5000ms)"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        log_fail "Open time: ${duration}ms (>= 5000ms)"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
    TESTS_RUN=$((TESTS_RUN + 1))
    
    # Clean up
    proot-distro login $DISTRO -- agent-browser close --json &>/dev/null || true
}

# Main test execution
main() {
    echo "=== Browser Tool PRoot Testing ==="
    echo "Distro: $DISTRO (Alpine Linux)"
    echo "Test All: $TEST_ALL"
    echo ""
    
    # Run tests
    test_agent_browser_backend
    test_text_browser
    
    if $TEST_ALL; then
        test_rust_native_backend
        test_computer_use_sidecar
        test_security_features
        test_performance
    fi
    
    # Summary
    echo ""
    echo "=== Test Summary ==="
    echo -e "Tests Run: ${BLUE}$TESTS_RUN${NC}"
    echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
    echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
    
    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "\n${GREEN}All tests passed!${NC}"
        echo ""
        echo "Browser tools are working correctly in PRoot."
        echo ""
        echo "Next steps:"
        echo "1. Integrate with Zero-Assist agent"
        echo "2. Test AI tool invocation"
        echo "3. Deploy to production"
        exit 0
    else
        echo -e "\n${RED}$TESTS_FAILED tests failed!${NC}"
        echo ""
        echo "Please fix the issues above before proceeding."
        exit 1
    fi
}

# Run main function
main
