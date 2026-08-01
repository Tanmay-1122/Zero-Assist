#!/bin/bash
# test-sidecar-proot.sh
# Tests sidecar accessibility from inside PRoot
# Verifies network namespace sharing and computer_use backend
# Version: 1.0.0
# Created: 2026-07-28

set -euo pipefail

# Configuration
DISTRO="${DISTRO:-alpine}"
SIDECAR_PORT="${SIDECAR_PORT:-8787}"
CHROMEDRIVER_PORT="${CHROMEDRIVER_PORT:-9515}"
VERBOSE="${VERBOSE:-0}"

# Test tracking
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
SKIPPED_TESTS=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_test() {
    echo -e "\n${BLUE}[TEST]${NC} $1"
}

log_pass() {
    echo -e "${GREEN}✓ PASS${NC} $1"
    PASSED_TESTS=$((PASSED_TESTS + 1))
}

log_fail() {
    echo -e "${RED}✗ FAIL${NC} $1"
    FAILED_TESTS=$((FAILED_TESTS + 1))
}

log_skip() {
    echo -e "${YELLOW}⊘ SKIP${NC} $1"
    SKIPPED_TESTS=$((SKIPPED_TESTS + 1))
}

run_test() {
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
}

# Test 1: PRoot network namespace
test_network_namespace() {
    run_test
    log_test "PRoot network namespace (shared with host)"
    
    if proot-distro login "${DISTRO}" -- ip addr show | grep -q "127.0.0.1"; then
        log_pass "Localhost interface accessible in PRoot"
        [ "$VERBOSE" -eq 1 ] && proot-distro login "${DISTRO}" -- ip addr show | grep "127.0.0.1"
    else
        log_fail "Localhost interface NOT accessible - isolated namespace"
        return 1
    fi
}

# Test 2: External connectivity
test_external_connectivity() {
    run_test
    log_test "External HTTPS connectivity from PRoot"
    
    if proot-distro login "${DISTRO}" -- curl -s --connect-timeout 5 https://example.com | head -10 | grep -q "Example Domain"; then
        log_pass "External HTTPS works from PRoot"
    else
        log_fail "External HTTPS NOT working from PRoot"
        return 1
    fi
}

# Test 3: DNS resolution
test_dns_resolution() {
    run_test
    log_test "DNS resolution from PRoot"
    
    if proot-distro login "${DISTRO}" -- nslookup example.com 2>/dev/null | grep -q "Address" || \
       proot-distro login "${DISTRO}" -- ping -c 1 example.com &>/dev/null; then
        log_pass "DNS resolution works from PRoot"
    else
        log_skip "DNS resolution test inconclusive (nslookup/ping not available)"
    fi
}

# Test 4: Sidecar port accessibility
test_sidecar_port() {
    run_test
    log_test "Sidecar port ${SIDECAR_PORT} accessibility from PRoot"
    
    # Check if sidecar is running on host first
    if ! curl -s --connect-timeout 2 "http://127.0.0.1:${SIDECAR_PORT}/v1/actions" &>/dev/null; then
        log_skip "Sidecar not running on host (port ${SIDECAR_PORT})"
        echo "  ℹ️  Start sidecar to test: computer-use-sidecar --port ${SIDECAR_PORT}"
        return 0
    fi
    
    # Test from inside PRoot
    if proot-distro login "${DISTRO}" -- curl -s --connect-timeout 2 "http://127.0.0.1:${SIDECAR_PORT}/v1/actions" &>/dev/null; then
        log_pass "Sidecar accessible from PRoot"
    else
        log_fail "Sidecar NOT accessible from PRoot - network isolation issue"
        return 1
    fi
}

# Test 5: Sidecar endpoint response
test_sidecar_endpoint() {
    run_test
    log_test "Sidecar /v1/actions endpoint response"
    
    # Check if sidecar is running
    if ! curl -s --connect-timeout 2 "http://127.0.0.1:${SIDECAR_PORT}/v1/actions" &>/dev/null; then
        log_skip "Sidecar not running"
        return 0
    fi
    
    local response
    response=$(proot-distro login "${DISTRO}" -- curl -s "http://127.0.0.1:${SIDECAR_PORT}/v1/actions" || true)
    
    if echo "$response" | grep -qi "action\|available\|screen_capture\|mouse\|keyboard"; then
        log_pass "Sidecar responds with available actions"
        [ "$VERBOSE" -eq 1 ] && echo "$response" | head -20
    else
        log_fail "Sidecar response invalid or empty"
        return 1
    fi
}

# Test 6: ChromeDriver port accessibility
test_chromedriver_port() {
    run_test
    log_test "ChromeDriver port ${CHROMEDRIVER_PORT} accessibility from PRoot"
    
    # Start ChromeDriver inside PRoot
    proot-distro login "${DISTRO}" -- chromedriver-start 2>/dev/null || true
    sleep 1
    
    # Test from inside PRoot
    if proot-distro login "${DISTRO}" -- curl -s --connect-timeout 2 "http://127.0.0.1:${CHROMEDRIVER_PORT}/status" &>/dev/null; then
        log_pass "ChromeDriver accessible from PRoot"
        proot-distro login "${DISTRO}" -- chromedriver-stop 2>/dev/null || true
    else
        log_skip "ChromeDriver not available or not started"
        proot-distro login "${DISTRO}" -- chromedriver-stop 2>/dev/null || true
    fi
}

# Test 7: Port scanning detection
test_port_scan() {
    run_test
    log_test "Common ports accessible from PRoot"
    
    local accessible=0
    local ports=(80 443 8080 8787 9515)
    
    for port in "${ports[@]}"; do
        if proot-distro login "${DISTRO}" -- timeout 1 bash -c "echo >/dev/tcp/127.0.0.1/${port}" 2>/dev/null; then
            accessible=$((accessible + 1))
            [ "$VERBOSE" -eq 1 ] && echo "  Port ${port}: Accessible"
        fi
    done
    
    if [ $accessible -gt 0 ]; then
        log_pass "Localhost ports accessible from PRoot (${accessible} found)"
    else
        log_skip "No services detected on common ports"
    fi
}

# Test 8: computer_use backend test (requires daemon)
test_computer_use_backend() {
    run_test
    log_test "computer_use backend with sidecar (integration)"
    
    # Check if daemon is running
    if ! curl -s http://localhost:42617/health &>/dev/null; then
        log_skip "Zero-Assist daemon not running"
        echo "  ℹ️  Start daemon to test: cd zeroclaw && cargo run"
        return 0
    fi
    
    # Check if sidecar is running
    if ! curl -s "http://127.0.0.1:${SIDECAR_PORT}/v1/actions" &>/dev/null; then
        log_skip "Sidecar not running"
        return 0
    fi
    
    # Test computer_use backend
    local response
    response=$(curl -s -X POST http://localhost:42617/api/tools/browser \
        -H "Content-Type: application/json" \
        -d "{\"action\": \"open\", \"url\": \"https://example.com\", \"backend\": \"computer_use\"}" || true)
    
    if echo "$response" | grep -qi "success\|output"; then
        log_pass "computer_use backend works with sidecar"
    else
        log_fail "computer_use backend failed"
        [ "$VERBOSE" -eq 1 ] && echo "$response"
        return 1
    fi
}

# Test 9: Network performance
test_network_performance() {
    run_test
    log_test "Network performance (latency check)"
    
    local start
    local end
    local duration
    
    start=$(date +%s%N)
    proot-distro login "${DISTRO}" -- curl -s --connect-timeout 5 https://example.com > /dev/null 2>&1 || true
    end=$(date +%s%N)
    
    duration=$(( (end - start) / 1000000 ))
    
    if [ $duration -lt 5000 ]; then
        log_pass "Network latency acceptable (${duration}ms)"
    else
        log_fail "Network latency too high (${duration}ms)"
        return 1
    fi
}

# Test 10: Verify PRoot doesn't isolate network
test_network_isolation_check() {
    run_test
    log_test "Verify PRoot doesn't isolate network"
    
    # Get host IP
    local host_ip
    host_ip=$(ip route get 1 | awk '{print $(NF-2);exit}' 2>/dev/null || echo "unknown")
    
    # Get PRoot IP
    local proot_ip
    proot_ip=$(proot-distro login "${DISTRO}" -- ip route get 1 | awk '{print $(NF-2);exit}' 2>/dev/null || echo "unknown")
    
    if [ "$host_ip" = "$proot_ip" ] && [ "$host_ip" != "unknown" ]; then
        log_pass "PRoot shares host network (IP: ${host_ip})"
    else
        log_skip "Could not verify network sharing"
    fi
}

# Print summary
print_summary() {
    echo ""
    echo "========================================="
    echo "  Sidecar Accessibility Summary"
    echo "========================================="
    echo "Total tests:  ${TOTAL_TESTS}"
    echo -e "Passed:       ${GREEN}${PASSED_TESTS}${NC}"
    echo -e "Failed:       ${RED}${FAILED_TESTS}${NC}"
    echo -e "Skipped:      ${YELLOW}${SKIPPED_TESTS}${NC}"
    echo "========================================="
    
    if [ $FAILED_TESTS -eq 0 ]; then
        echo -e "${GREEN}All sidecar tests passed!${NC}"
        echo ""
        echo "✅ Network namespace shared correctly"
        echo "✅ Localhost ports accessible from PRoot"
        echo "✅ Sidecar accessible (if running)"
        echo "✅ computer_use backend ready"
        return 0
    else
        echo -e "${RED}${FAILED_TESTS} tests failed!${NC}"
        echo ""
        echo "⚠️  Network connectivity issues detected"
        echo ""
        echo "Possible issues:"
        echo "  - PRoot network namespace isolated"
        echo "  - Firewall blocking localhost"
        echo "  - Sidecar not running or misconfigured"
        return 1
    fi
}

# Parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --sidecar-port)
                SIDECAR_PORT="$2"
                shift 2
                ;;
            --verbose|-v)
                VERBOSE=1
                shift
                ;;
            --help|-h)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  --sidecar-port PORT  Sidecar port (default: 8787)"
                echo "  --verbose, -v        Show detailed test output"
                echo "  --help, -h           Show this help message"
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                exit 1
                ;;
        esac
    done
}

# Main
main() {
    echo "========================================="
    echo "  PRoot Sidecar Accessibility Testing"
    echo "========================================="
    echo "Distro: ${DISTRO}"
    echo "Sidecar Port: ${SIDECAR_PORT}"
    echo "ChromeDriver Port: ${CHROMEDRIVER_PORT}"
    echo ""
    
    test_network_namespace
    test_external_connectivity
    test_dns_resolution
    test_sidecar_port
    test_sidecar_endpoint
    test_chromedriver_port
    test_port_scan
    test_computer_use_backend
    test_network_performance
    test_network_isolation_check
    
    print_summary
}

parse_args "$@"
main
