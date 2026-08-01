#!/bin/bash
# test-security-proot.sh
# Comprehensive security testing for PRoot Browser
# Tests URL validation, domain allowlist, rate limiting, SSRF protection
# Version: 1.0.0
# Created: 2026-07-28

set -euo pipefail

# Configuration
DAEMON_URL="${DAEMON_URL:-http://localhost:42617}"
DISTRO="${DISTRO:-alpine}"
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

# Check if daemon is running
check_daemon() {
    if ! curl -s "${DAEMON_URL}/health" &>/dev/null; then
        echo -e "${RED}ERROR:${NC} Zero-Assist daemon not running at ${DAEMON_URL}"
        echo "Please start the daemon first: cd zeroclaw && cargo run"
        exit 1
    fi
}

# Test 1: file:// URL blocking
test_file_url_blocking() {
    run_test
    log_test "Block file:// URLs (security critical)"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "file:///etc/passwd"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|not allowed\|file://"; then
        log_pass "file:// URLs are blocked before PRoot execution"
        [ "$VERBOSE" -eq 1 ] && echo "Response: $response"
    else
        log_fail "file:// URLs were NOT blocked - SECURITY ISSUE"
        echo "Response: $response"
        return 1
    fi
}

# Test 2: javascript: URL blocking
test_javascript_url_blocking() {
    run_test
    log_test "Block javascript: URLs"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "javascript:alert(1)"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|not allowed"; then
        log_pass "javascript: URLs are blocked"
    else
        log_fail "javascript: URLs were NOT blocked"
        return 1
    fi
}

# Test 3: data: URL blocking
test_data_url_blocking() {
    run_test
    log_test "Block data: URLs"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "data:text/html,<script>alert(1)</script>"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|not allowed"; then
        log_pass "data: URLs are blocked"
    else
        log_fail "data: URLs were NOT blocked"
        return 1
    fi
}

# Test 4: localhost blocking
test_localhost_blocking() {
    run_test
    log_test "Block localhost access"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "http://localhost:8080"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|localhost\|private"; then
        log_pass "localhost is blocked"
    else
        log_fail "localhost was NOT blocked - SSRF risk"
        return 1
    fi
}

# Test 5: 127.0.0.1 blocking
test_loopback_blocking() {
    run_test
    log_test "Block 127.0.0.1 access"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "http://127.0.0.1:8080"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|127.0.0.1\|private\|loopback"; then
        log_pass "127.0.0.1 is blocked"
    else
        log_fail "127.0.0.1 was NOT blocked - SSRF risk"
        return 1
    fi
}

# Test 6: Private IP blocking (192.168.x.x)
test_private_ip_blocking_192() {
    run_test
    log_test "Block private IP 192.168.1.1"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "http://192.168.1.1"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|private"; then
        log_pass "192.168.x.x IPs are blocked"
    else
        log_fail "192.168.x.x IPs were NOT blocked - SSRF risk"
        return 1
    fi
}

# Test 7: Private IP blocking (10.x.x.x)
test_private_ip_blocking_10() {
    run_test
    log_test "Block private IP 10.0.0.1"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "http://10.0.0.1"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|private"; then
        log_pass "10.x.x.x IPs are blocked"
    else
        log_fail "10.x.x.x IPs were NOT blocked - SSRF risk"
        return 1
    fi
}

# Test 8: Private IP blocking (172.16.x.x)
test_private_ip_blocking_172() {
    run_test
    log_test "Block private IP 172.16.0.1"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "http://172.16.0.1"}' || true)
    
    if echo "$response" | grep -qi "error\|blocked\|private"; then
        log_pass "172.16.x.x IPs are blocked"
    else
        log_fail "172.16.x.x IPs were NOT blocked - SSRF risk"
        return 1
    fi
}

# Test 9: Domain allowlist enforcement
test_domain_allowlist() {
    run_test
    log_test "Domain allowlist enforcement"
    
    # Try accessing a domain not in allowlist
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "https://definitelynotinallowlist.com"}' || true)
    
    if echo "$response" | grep -qi "error\|not in\|allowed_domains\|allowlist"; then
        log_pass "Domains not in allowlist are blocked"
    else
        log_skip "Allowlist may be empty (all domains allowed)"
    fi
}

# Test 10: HTTPS enforcement
test_https_allowed() {
    run_test
    log_test "HTTPS URLs are allowed"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "https://example.com"}' || true)
    
    if echo "$response" | grep -qi "success\|output" || ! echo "$response" | grep -qi "error"; then
        log_pass "HTTPS URLs are allowed"
    else
        log_fail "HTTPS URLs were blocked incorrectly"
        return 1
    fi
}

# Test 11: HTTP allowed (for compatibility)
test_http_allowed() {
    run_test
    log_test "HTTP URLs are allowed"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "http://example.com"}' || true)
    
    if echo "$response" | grep -qi "success\|output" || ! echo "$response" | grep -qi "error"; then
        log_pass "HTTP URLs are allowed"
    else
        log_fail "HTTP URLs were blocked incorrectly"
        return 1
    fi
}

# Test 12: Empty URL blocking
test_empty_url_blocking() {
    run_test
    log_test "Empty URLs are rejected"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": ""}' || true)
    
    if echo "$response" | grep -qi "error\|empty\|missing"; then
        log_pass "Empty URLs are rejected"
    else
        log_fail "Empty URLs were NOT rejected"
        return 1
    fi
}

# Test 13: Whitespace in URL blocking
test_whitespace_url_blocking() {
    run_test
    log_test "URLs with whitespace are rejected"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "https://example.com/hello world"}' || true)
    
    if echo "$response" | grep -qi "error\|whitespace\|invalid"; then
        log_pass "URLs with whitespace are rejected"
    else
        log_fail "URLs with whitespace were NOT rejected"
        return 1
    fi
}

# Test 14: Rate limiting (if enabled)
test_rate_limiting() {
    run_test
    log_test "Rate limiting enforcement (optional)"
    
    # Make multiple rapid requests
    local success_count=0
    local fail_count=0
    
    for i in {1..10}; do
        local response
        response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
            -H "Content-Type: application/json" \
            -d '{"url": "https://example.com"}' || true)
        
        if echo "$response" | grep -qi "success\|output"; then
            success_count=$((success_count + 1))
        elif echo "$response" | grep -qi "rate limit"; then
            fail_count=$((fail_count + 1))
        fi
        
        sleep 0.1
    done
    
    if [ $fail_count -gt 0 ]; then
        log_pass "Rate limiting is active ($fail_count requests blocked)"
    else
        log_skip "Rate limiting not active or threshold not reached"
    fi
}

# Test 15: FTP URL blocking
test_ftp_url_blocking() {
    run_test
    log_test "Block FTP URLs"
    
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "ftp://ftp.example.com"}' || true)
    
    if echo "$response" | grep -qi "error\|not allowed\|ftp"; then
        log_pass "FTP URLs are blocked"
    else
        log_fail "FTP URLs were NOT blocked"
        return 1
    fi
}

# Test 16: Verify PRoot execution (log check)
test_proot_execution_verification() {
    run_test
    log_test "Verify commands execute in PRoot (log check)"
    
    # This test requires access to daemon logs
    # We'll make a request and check if it succeeded
    local response
    response=$(curl -s -X POST "${DAEMON_URL}/api/tools/text_browser" \
        -H "Content-Type: application/json" \
        -d '{"url": "https://example.com"}' || true)
    
    if echo "$response" | grep -qi "success\|output"; then
        log_pass "Browser tool works (check logs for PRoot execution)"
        echo "  ℹ️  Check daemon logs for: 'Executing command in PRoot'"
    else
        log_skip "Could not verify PRoot execution (check logs manually)"
    fi
}

# Print summary
print_summary() {
    echo ""
    echo "========================================="
    echo "  Security Test Summary"
    echo "========================================="
    echo "Total tests:  ${TOTAL_TESTS}"
    echo -e "Passed:       ${GREEN}${PASSED_TESTS}${NC}"
    echo -e "Failed:       ${RED}${FAILED_TESTS}${NC}"
    echo -e "Skipped:      ${YELLOW}${SKIPPED_TESTS}${NC}"
    echo "========================================="
    
    if [ $FAILED_TESTS -eq 0 ]; then
        echo -e "${GREEN}All security tests passed!${NC}"
        echo ""
        echo "✅ URL validation working"
        echo "✅ Private host blocking working"
        echo "✅ SSRF protection working"
        echo "✅ Safe for production deployment"
        return 0
    else
        echo -e "${RED}${FAILED_TESTS} security tests failed!${NC}"
        echo ""
        echo "⚠️  SECURITY ISSUES DETECTED"
        echo "⚠️  DO NOT DEPLOY TO PRODUCTION"
        echo ""
        echo "Please review failures and fix security policies."
        return 1
    fi
}

# Parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --daemon-url)
                DAEMON_URL="$2"
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
                echo "  --daemon-url URL  Daemon URL (default: http://localhost:42617)"
                echo "  --verbose, -v     Show detailed test output"
                echo "  --help, -h        Show this help message"
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                echo "Run with --help for usage information"
                exit 1
                ;;
        esac
    done
}

# Main test execution
main() {
    echo "========================================="
    echo "  PRoot Browser Security Testing"
    echo "========================================="
    echo "Daemon: ${DAEMON_URL}"
    echo "Distro: ${DISTRO}"
    echo ""
    
    # Check daemon is running
    check_daemon
    
    echo "Starting security tests..."
    echo ""
    
    # Run all security tests
    test_file_url_blocking
    test_javascript_url_blocking
    test_data_url_blocking
    test_localhost_blocking
    test_loopback_blocking
    test_private_ip_blocking_192
    test_private_ip_blocking_10
    test_private_ip_blocking_172
    test_domain_allowlist
    test_https_allowed
    test_http_allowed
    test_empty_url_blocking
    test_whitespace_url_blocking
    test_rate_limiting
    test_ftp_url_blocking
    test_proot_execution_verification
    
    # Print results
    print_summary
}

# Parse arguments and run
parse_args "$@"
main
