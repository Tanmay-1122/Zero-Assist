#!/bin/bash
# proot-browser-setup.sh
# Sets up browser automation tools inside PRoot distro for Zero-Assist
# Version: 1.0.0
# Created: 2026-07-28

set -euo pipefail

# Configuration
PROOT_DIR="${PROOT_DIR:-/data/data/com.termux/files/home}"
DISTRO="${DISTRO:-alpine}"
NODE_VERSION="${NODE_VERSION:-20}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "\n${GREEN}[$1]${NC} $2"
}

# Check prerequisites
check_prerequisites() {
    log_step "0/7" "Checking prerequisites..."
    
    if ! command -v proot &>/dev/null; then
        log_error "proot not found. Install Termux + proot first:"
        echo "  pkg install proot"
        exit 1
    fi
    
    if ! command -v proot-distro &>/dev/null; then
        log_error "proot-distro not found. Install it first:"
        echo "  pkg install proot-distro"
        exit 1
    fi
    
    # Check if distro is installed
    if ! proot-distro list --installed 2>/dev/null | grep -q "^${DISTRO}$"; then
        log_warn "Distro '${DISTRO}' not installed. Installing now..."
        proot-distro install "${DISTRO}" || {
            log_error "Failed to install ${DISTRO}"
            exit 1
        }
    fi
    
    log_info "Prerequisites check passed"
}

# Verify PRoot network namespace
verify_network() {
    log_step "1/7" "Verifying network configuration..."
    
    if proot-distro login "${DISTRO}" -- bash -c 'ip addr show | grep -q 127.0.0.1'; then
        log_info "Network namespace: shared (localhost accessible) ✓"
    else
        log_warn "Network namespace: isolated (may affect connectivity)"
    fi
}

# Install Node.js
install_nodejs() {
    log_step "2/7" "Installing Node.js ${NODE_VERSION}..."
    
    proot-distro login "${DISTRO}" -- bash -c "
        set -e
        
        # Update package list (Alpine uses apk, not apt-get)
        apk update
        
        # Install curl if not present
        if ! command -v curl &>/dev/null; then
            apk add curl
        fi
        
        # Add NodeSource repository for Node.js
        # Note: Alpine uses different package manager, so we'll install from official repos
        apk add nodejs npm
        
        # Verify installation
        node --version
        npm --version
    " || {
        log_error "Failed to install Node.js"
        exit 1
    }
    
    local node_version
    node_version=$(proot-distro login "${DISTRO}" -- node --version)
    log_info "Node.js installed: ${node_version}"
}

# Install agent-browser CLI
install_agent_browser() {
    log_step "3/7" "Installing agent-browser CLI..."
    
    proot-distro login "${DISTRO}" -- bash -c "
        set -e
        
        # Install agent-browser globally
        npm install -g agent-browser
        
        # Verify installation
        which agent-browser
        agent-browser --version
    " || {
        log_error "Failed to install agent-browser"
        exit 1
    }
    
    local ab_version
    ab_version=$(proot-distro login "${DISTRO}" -- agent-browser --version 2>&1 | head -1)
    log_info "agent-browser installed: ${ab_version}"
}

# Install Chromium browser
install_chromium() {
    log_step "4/7" "Installing Chromium browser..."
    
    proot-distro login "${DISTRO}" -- bash -c "
        set -e
        
        # Try chromium first (Alpine package name)
        apk add chromium chromium-chromedriver || {
            echo 'Chromium installation failed, trying alternative...'
            apk add chromium
        }
        
        # Verify installation
        which chromium || which chromium-browser
    " || {
        log_error "Failed to install Chromium"
        exit 1
    }
    
    # Detect chrome binary path
    local chrome_bin
    chrome_bin=$(proot-distro login "${DISTRO}" -- bash -c 'which chromium-browser || which chromium')
    log_info "Chromium installed: ${chrome_bin}"
}

# Install text browsers
install_text_browsers() {
    log_step "5/7" "Installing text browsers (lynx, links, w3m)..."
    
    proot-distro login "${DISTRO}" -- bash -c "
        set -e
        
        # Install all three text browsers (Alpine)
        apk add lynx links w3m
        
        # Verify installations
        which lynx
        which links
        which w3m
    " || {
        log_error "Failed to install text browsers"
        exit 1
    }
    
    log_info "Text browsers installed: lynx, links, w3m"
}

# Install ChromeDriver (on-demand management)
install_chromedriver() {
    log_step "6/7" "Installing ChromeDriver (on-demand lifecycle)..."
    
    proot-distro login "${DISTRO}" -- bash -c '
        set -e
        
        # Get Chrome version
        CHROME_BIN=$(which chromium-browser || which chromium)
        CHROME_VERSION=$($CHROME_BIN --version | grep -oP "\d+\.\d+\.\d+\.\d+" | head -1)
        CHROME_MAJOR_VERSION=$(echo $CHROME_VERSION | cut -d. -f1)
        
        echo "Chrome version: $CHROME_VERSION (major: $CHROME_MAJOR_VERSION)"
        
        # Get matching ChromeDriver version
        CHROMEDRIVER_VERSION=$(curl -s "https://chromedriver.storage.googleapis.com/LATEST_RELEASE_${CHROME_MAJOR_VERSION}")
        echo "ChromeDriver version: $CHROMEDRIVER_VERSION"
        
        # Download and install ChromeDriver
        curl -Lo /tmp/chromedriver.zip "https://chromedriver.storage.googleapis.com/${CHROMEDRIVER_VERSION}/chromedriver_linux64.zip"
        unzip -o /tmp/chromedriver.zip -d /tmp/
        mv /tmp/chromedriver /usr/local/bin/chromedriver
        chmod +x /usr/local/bin/chromedriver
        rm -f /tmp/chromedriver.zip
        
        # Verify installation
        chromedriver --version
    ' || {
        log_warn "ChromeDriver installation failed (optional component)"
        log_info "Browser tool will work with agent_browser and computer_use backends"
        return 0
    }
    
    log_info "ChromeDriver installed (managed on-demand by Rust code)"
}

# Create wrapper scripts
create_wrappers() {
    log_step "7/7" "Creating PRoot wrapper scripts..."
    
    # Create chromedriver-start script (for on-demand startup)
    proot-distro login "${DISTRO}" -- bash -c '
        cat > /usr/local/bin/chromedriver-start << "CHROMEDRIVER_START"
#!/bin/bash
# Start ChromeDriver on-demand
set -euo pipefail

PORT="${CHROMEDRIVER_PORT:-9515}"
LOG_FILE="${CHROMEDRIVER_LOG:-/tmp/chromedriver.log}"
PID_FILE="/tmp/chromedriver.pid"

# Check if already running
if [ -f "$PID_FILE" ]; then
    if ps -p $(cat "$PID_FILE") > /dev/null 2>&1; then
        echo "ChromeDriver already running (PID: $(cat $PID_FILE))"
        exit 0
    fi
fi

# Start ChromeDriver in background
chromedriver --port=$PORT > "$LOG_FILE" 2>&1 &
DRIVER_PID=$!

# Save PID
echo "$DRIVER_PID" > "$PID_FILE"

# Wait for startup
for i in {1..30}; do
    if curl -s http://127.0.0.1:$PORT/status > /dev/null 2>&1; then
        echo "ChromeDriver started (PID: $DRIVER_PID, Port: $PORT)"
        exit 0
    fi
    sleep 0.1
done

echo "ChromeDriver failed to start (timeout)"
exit 1
CHROMEDRIVER_START
        chmod +x /usr/local/bin/chromedriver-start
    '
    
    # Create chromedriver-stop script
    proot-distro login "${DISTRO}" -- bash -c '
        cat > /usr/local/bin/chromedriver-stop << "CHROMEDRIVER_STOP"
#!/bin/bash
# Stop ChromeDriver
set -euo pipefail

PID_FILE="/tmp/chromedriver.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "ChromeDriver not running (no PID file)"
    exit 0
fi

PID=$(cat "$PID_FILE")

if ps -p "$PID" > /dev/null 2>&1; then
    kill "$PID"
    rm -f "$PID_FILE"
    echo "ChromeDriver stopped (PID: $PID)"
else
    rm -f "$PID_FILE"
    echo "ChromeDriver not running (stale PID file removed)"
fi
CHROMEDRIVER_STOP
        chmod +x /usr/local/bin/chromedriver-stop
    '
    
    # Create environment setup script
    proot-distro login "${DISTRO}" -- bash -c '
        cat > /usr/local/bin/zeroclaw-browser-env << "ZEROCLAW_ENV"
#!/bin/bash
# Zero-Assist browser environment setup
export HOME=/root
export DISPLAY=:0
export CHROME_BIN=$(which chromium-browser || which chromium)
export CHROMIUM_FLAGS="--headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --disable-extensions --disable-software-rasterizer"

# Export for subprocesses
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"

# Run command
exec "$@"
ZEROCLAW_ENV
        chmod +x /usr/local/bin/zeroclaw-browser-env
    '
    
    log_info "Wrapper scripts created:"
    log_info "  - /usr/local/bin/chromedriver-start (on-demand startup)"
    log_info "  - /usr/local/bin/chromedriver-stop (cleanup)"
    log_info "  - /usr/local/bin/zeroclaw-browser-env (environment setup)"
}

# Main installation flow
main() {
    echo "========================================="
    echo "  Zero-Assist PRoot Browser Setup"
    echo "========================================="
    echo "Target: PRoot distro '${DISTRO}' (Alpine Linux)"
    echo ""
    
    check_prerequisites
    verify_network
    install_nodejs
    install_agent_browser
    install_chromium
    install_text_browsers
    install_chromedriver
    create_wrappers
    
    echo ""
    echo "========================================="
    log_info "Setup complete!"
    echo "========================================="
    echo ""
    echo "Next steps:"
    echo "  1. Run verification: bash scripts/proot-browser-verify.sh"
    echo "  2. Run tests: bash scripts/test-browser-proot.sh"
    echo "  3. Check docs: docs/browser-proot-quick-reference.md"
    echo ""
}

# Execute main
main "$@"
