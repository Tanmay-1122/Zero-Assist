#!/bin/bash
# run-all-tests.sh
# Master test runner for PRoot Browser migration (Tasks 5-8)
# Executes all automated tests and generates comprehensive report
# Version: 1.0.0
# Created: 2026-07-28

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_FILE="${REPORT_FILE:-test-results-$(date +%Y%m%d-%H%M%S).md}"
DAEMON_URL="${DAEMON_URL:-http://localhost:42617}"
DISTRO="${DISTRO:-alpine}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Test results tracking
TASK5_RESULT=""
TASK6_RESULT=""
OVERALL_RESULT="UNKNOWN"

log_header() {
    echo -e "\n${CYAN}========================================${NC}"
    echo -e "${CYAN}$1${NC}"
    echo -e "${CYAN}========================================${NC}\n"
}

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_header "Checking Prerequisites"
    
    local all_ok=true
    
    # Check if daemon is running
    log_info "Checking daemon at ${DAEMON_URL}..."
    if curl -s "${DAEMON_URL}/health" &>/dev/null; then
        log_success "Daemon is running"
    else
        log_error "Daemon is not running at ${DAEMON_URL}"
        echo "  Please start the daemon: cd zeroclaw && cargo run"
        all_ok=false
    fi
    
    # Check if PRoot distro installed
    log_info "Checking PRoot distro '${DISTRO}'..."
    if proot-distro list --installed 2>/dev/null | grep -q "${DISTRO}"; then
        log_success "PRoot distro '${DISTRO}' is installed"
    else
        log_error "PRoot distro '${DISTRO}' is not installed"
        echo "  Please install: proot-distro install ${DISTRO}"
        all_ok=false
    fi
    
    # Check if test scripts exist
    log_info "Checking test scripts..."
    if [ -f "${SCRIPT_DIR}/test-security-proot.sh" ] && [ -f "${SCRIPT_DIR}/test-sidecar-proot.sh" ]; then
        log_success "Test scripts found"
    else
        log_error "Test scripts not found in ${SCRIPT_DIR}"
        all_ok=false
    fi
    
    # Check if PRoot browser tools installed
    log_info "Checking PRoot browser tools..."
    if proot-distro login "${DISTRO}" -- which agent-browser &>/dev/null || \
       proot-distro login "${DISTRO}" -- which lynx &>/dev/null; then
        log_success "Browser tools found in PRoot"
    else
        log_warning "Browser tools not found in PRoot"
        echo "  Please run setup: bash scripts/proot-browser-setup.sh"
        # Not fatal, tests may still work
    fi
    
    if [ "$all_ok" = false ]; then
        log_error "Prerequisites check failed. Please fix issues above."
        return 1
    fi
    
    log_success "All prerequisites met!"
    return 0
}

# Run Task 5: Security Tests
run_task5() {
    log_header "Task 5: Security Testing"
    
    log_info "Running security tests..."
    echo ""
    
    local output_file="/tmp/task5-output.txt"
    
    if bash "${SCRIPT_DIR}/test-security-proot.sh" 2>&1 | tee "$output_file"; then
        TASK5_RESULT="PASS"
        log_success "Task 5 completed successfully"
    else
        TASK5_RESULT="FAIL"
        log_error "Task 5 failed - security issues detected"
    fi
    
    # Extract summary
    local passed=$(grep "Passed:" "$output_file" | awk '{print $2}' || echo "0")
    local failed=$(grep "Failed:" "$output_file" | awk '{print $2}' || echo "0")
    local skipped=$(grep "Skipped:" "$output_file" | awk '{print $2}' || echo "0")
    
    echo ""
    echo "Task 5 Summary:"
    echo "  Passed:  ${passed}"
    echo "  Failed:  ${failed}"
    echo "  Skipped: ${skipped}"
}

# Run Task 6: Sidecar Tests
run_task6() {
    log_header "Task 6: Sidecar Accessibility Testing"
    
    log_info "Running sidecar tests..."
    echo ""
    
    local output_file="/tmp/task6-output.txt"
    
    if bash "${SCRIPT_DIR}/test-sidecar-proot.sh" 2>&1 | tee "$output_file"; then
        TASK6_RESULT="PASS"
        log_success "Task 6 completed successfully"
    else
        TASK6_RESULT="FAIL"
        log_error "Task 6 failed - network connectivity issues"
    fi
    
    # Extract summary
    local passed=$(grep "Passed:" "$output_file" | awk '{print $2}' || echo "0")
    local failed=$(grep "Failed:" "$output_file" | awk '{print $2}' || echo "0")
    local skipped=$(grep "Skipped:" "$output_file" | awk '{print $2}' || echo "0")
    
    echo ""
    echo "Task 6 Summary:"
    echo "  Passed:  ${passed}"
    echo "  Failed:  ${failed}"
    echo "  Skipped: ${skipped}"
}

# Generate test report
generate_report() {
    log_header "Generating Test Report"
    
    log_info "Creating report: ${REPORT_FILE}"
    
    cat > "${REPORT_FILE}" <<EOF
# PRoot Browser Test Results

**Date:** $(date '+%Y-%m-%d %H:%M:%S')  
**Environment:** $(uname -s) $(uname -r)  
**PRoot Distro:** ${DISTRO}  
**Daemon:** ${DAEMON_URL}

---

## Executive Summary

EOF

    # Overall status
    if [ "$TASK5_RESULT" = "PASS" ] && [ "$TASK6_RESULT" = "PASS" ]; then
        OVERALL_RESULT="✅ PASS - Ready for Tasks 7-8"
        cat >> "${REPORT_FILE}" <<EOF
**Status:** ✅ **PASS**

All automated tests (Tasks 5-6) passed successfully. The system is ready for manual testing (Tasks 7-8).

EOF
    elif [ "$TASK5_RESULT" = "FAIL" ]; then
        OVERALL_RESULT="❌ FAIL - Security Issues"
        cat >> "${REPORT_FILE}" <<EOF
**Status:** ❌ **FAIL**

**CRITICAL:** Security tests (Task 5) failed. Do NOT proceed to production until issues are resolved.

EOF
    elif [ "$TASK6_RESULT" = "FAIL" ]; then
        OVERALL_RESULT="⚠️ PARTIAL - Network Issues"
        cat >> "${REPORT_FILE}" <<EOF
**Status:** ⚠️ **PARTIAL PASS**

Security tests passed, but network connectivity tests (Task 6) failed. Review network configuration.

EOF
    else
        OVERALL_RESULT="❓ UNKNOWN"
        cat >> "${REPORT_FILE}" <<EOF
**Status:** ❓ **UNKNOWN**

Test execution incomplete or inconclusive. Please review test outputs.

EOF
    fi

    # Task results
    cat >> "${REPORT_FILE}" <<EOF
## Test Results

### Task 5: Security Testing
**Result:** ${TASK5_RESULT}

EOF

    if [ -f "/tmp/task5-output.txt" ]; then
        cat >> "${REPORT_FILE}" <<EOF
\`\`\`
$(tail -30 /tmp/task5-output.txt)
\`\`\`

EOF
    fi

    cat >> "${REPORT_FILE}" <<EOF
### Task 6: Sidecar Accessibility
**Result:** ${TASK6_RESULT}

EOF

    if [ -f "/tmp/task6-output.txt" ]; then
        cat >> "${REPORT_FILE}" <<EOF
\`\`\`
$(tail -30 /tmp/task6-output.txt)
\`\`\`

EOF
    fi

    # Next steps
    cat >> "${REPORT_FILE}" <<EOF
---

## Next Steps

### Task 7: AI Integration Testing
**Status:** ⏳ Pending Manual Execution

Follow guide: \`docs/AI-INTEGRATION-TEST-GUIDE.md\`

**Test Scenarios:**
1. Basic browser navigation
2. Text browser usage
3. Multi-step workflow
4. Error handling - blocked URL
5. Error handling - network timeout
6. Backend: agent_browser
7. Backend: rust_native
8. Backend: computer_use
9. Performance check
10. Fallback to native

**Estimated Time:** 1 hour

---

### Task 8: Rollback Verification
**Status:** ⏳ Pending Manual Execution

Follow guide: \`docs/ROLLBACK-VERIFICATION-GUIDE.md\`

**Test Scenarios:**
1. Disable via UI
2. PRoot distro not installed
3. PRoot binary missing
4. Emergency TOML edit
5. Factory reset
6. Plugin uninstall
7. Partial setup
8. Configuration corruption

**Estimated Time:** 30 minutes

---

## Recommendations

EOF

    if [ "$TASK5_RESULT" = "PASS" ] && [ "$TASK6_RESULT" = "PASS" ]; then
        cat >> "${REPORT_FILE}" <<EOF
✅ **Automated tests passed!** Proceed with manual testing (Tasks 7-8).

**Action Items:**
1. Execute Task 7 AI integration tests
2. Execute Task 8 rollback verification
3. Document results for both tasks
4. Create deployment checklist
5. Deploy to production (if all tests pass)

EOF
    else
        cat >> "${REPORT_FILE}" <<EOF
⚠️ **Issues detected!** Fix before proceeding.

**Action Items:**
1. Review failed test output above
2. Fix identified issues
3. Re-run this test suite
4. Only proceed to Tasks 7-8 after all tests pass

EOF
    fi

    cat >> "${REPORT_FILE}" <<EOF
---

## Test Artifacts

- Task 5 Output: \`/tmp/task5-output.txt\`
- Task 6 Output: \`/tmp/task6-output.txt\`
- Full Report: \`${REPORT_FILE}\`

---

**Report Generated:** $(date '+%Y-%m-%d %H:%M:%S')
EOF

    log_success "Report generated: ${REPORT_FILE}"
}

# Display summary
display_summary() {
    log_header "Test Execution Summary"
    
    echo ""
    echo "Task 5 (Security):      ${TASK5_RESULT}"
    echo "Task 6 (Sidecar):       ${TASK6_RESULT}"
    echo ""
    echo "Overall Status:         ${OVERALL_RESULT}"
    echo ""
    echo "Full report:            ${REPORT_FILE}"
    echo ""
    
    if [ "$TASK5_RESULT" = "PASS" ] && [ "$TASK6_RESULT" = "PASS" ]; then
        log_success "All automated tests passed! ✅"
        echo ""
        echo "Next steps:"
        echo "  1. Review report: cat ${REPORT_FILE}"
        echo "  2. Execute Task 7: docs/AI-INTEGRATION-TEST-GUIDE.md"
        echo "  3. Execute Task 8: docs/ROLLBACK-VERIFICATION-GUIDE.md"
        return 0
    else
        log_error "Some tests failed! ❌"
        echo ""
        echo "Please review the report and fix issues before proceeding."
        return 1
    fi
}

# Main execution
main() {
    echo -e "${CYAN}"
    cat <<'EOF'
╔═══════════════════════════════════════════════╗
║  PRoot Browser Migration Test Suite          ║
║  Tasks 5-6: Automated Testing                 ║
╚═══════════════════════════════════════════════╝
EOF
    echo -e "${NC}"
    
    log_info "Starting test execution at $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
    
    # Check prerequisites
    if ! check_prerequisites; then
        log_error "Prerequisites check failed. Exiting."
        exit 1
    fi
    
    echo ""
    sleep 2
    
    # Run tests
    run_task5
    echo ""
    sleep 2
    
    run_task6
    echo ""
    sleep 1
    
    # Generate report
    generate_report
    echo ""
    
    # Display summary
    display_summary
    
    local exit_code=$?
    
    echo ""
    log_info "Test execution completed at $(date '+%Y-%m-%d %H:%M:%S')"
    
    exit $exit_code
}

# Parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --daemon-url)
                DAEMON_URL="$2"
                shift 2
                ;;
            --distro)
                DISTRO="$2"
                shift 2
                ;;
            --report-file)
                REPORT_FILE="$2"
                shift 2
                ;;
            --help|-h)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Run all PRoot Browser automated tests (Tasks 5-6)"
                echo ""
                echo "Options:"
                echo "  --daemon-url URL     Daemon URL (default: http://localhost:42617)"
                echo "  --distro NAME        PRoot distro (default: alpine)"
                echo "  --report-file FILE   Report output file (default: auto-generated)"
                echo "  --help, -h           Show this help message"
                echo ""
                echo "Example:"
                echo "  $0 --daemon-url http://localhost:42617 --distro alpine"
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

# Entry point
parse_args "$@"
main
