#!/bin/bash
set -euo pipefail

# Zero-Assist Repository Deep Cleanup Script (Bash version)
# Usage: bash scripts/cleanup-repo.sh [--dry-run] [--no-confirm]

DRY_RUN=false
CONFIRM=true

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --no-confirm)
            CONFIRM=false
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# Colors
INFO='\033[0;36m'
SUCCESS='\033[0;32m'
WARNING='\033[1;33m'
ERROR='\033[0;31m'
NC='\033[0m' # No Color

echo_info() { echo -e "${INFO}ℹ️  $1${NC}"; }
echo_success() { echo -e "${SUCCESS}✓ $1${NC}"; }
echo_warning() { echo -e "${WARNING}⚠️  $1${NC}"; }
echo_error() { echo -e "${ERROR}❌ $1${NC}"; }

confirm_action() {
    if [ "$CONFIRM" = false ]; then
        return 0
    fi
    read -p "$1 (y/n) " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]]
}

# ============================================================================
# SAFETY VERIFICATION & BACKUP
# ============================================================================

prepare_backup() {
    echo_info "\n[1/4] Preparing Git Backup..."
    
    if ! command -v git &> /dev/null; then
        echo_warning "Git not found in PATH. Skipping automated Git backup branch creation."
        return 0
    fi

    if [ "$DRY_RUN" = true ]; then
        echo_info "DRY RUN: git branch backup/pre-cleanup-zeroclaw"
    else
        git branch backup/pre-cleanup-zeroclaw 2>/dev/null || echo_warning "Backup branch already exists."
        echo_success "Backup branch created: backup/pre-cleanup-zeroclaw"
    fi
}

# ============================================================================
# PHASE 1: REMOVE UNUSED CRATES AND CODE
# ============================================================================

remove_unused_crates_and_code() {
    echo_info "\n[2/4] Removing Unused Upstream Crates and Code..."
    
    local items_to_remove=(
        # Unused Rust Crates
        "zeroclaw/crates/zeroclaw-tui"
        "zeroclaw/crates/zeroclaw-plugins"
        "zeroclaw/crates/zeroclaw-hardware"
        "zeroclaw/crates/robot-kit"
        "zeroclaw/crates/aardvark-sys"
        "zeroclaw/apps/tauri"
        "zeroclaw/tools/fill-translations"
        "zeroclaw/xtask"
        
        # Unused upstream workspace modules/folders
        "zeroclaw/tests"
        "zeroclaw/benches"
        "zeroclaw/fuzz"
        "zeroclaw/web"
        "zeroclaw/firmware"
        "zeroclaw/marketplace"
        "zeroclaw/dist"
        "zeroclaw/dev"
        "zeroclaw/scripts"
        "zeroclaw/.githooks"
        
        # Nix, Docker & Build setup configurations
        "zeroclaw/flake.nix"
        "zeroclaw/flake.lock"
        "zeroclaw/Dockerfile"
        "zeroclaw/Dockerfile.ci"
        "zeroclaw/Dockerfile.debian"
        "zeroclaw/Dockerfile.debian.ci"
        "zeroclaw/docker-compose.yml"
        "zeroclaw/.dockerignore"
        "zeroclaw/Justfile"
        "zeroclaw/install.sh"
        "zeroclaw/setup.bat"
        "zeroclaw/.env.example"
        "zeroclaw/.envrc"
        "zeroclaw/.actrc"
        "zeroclaw/.markdownlint-cli2.yaml"
        "zeroclaw/CNAME"
        "zeroclaw/locales.toml"
        "zeroclaw/release-plz.toml"
        
        # Redundant markdown/licenses
        "zeroclaw/README.md"
        "zeroclaw/CODE_OF_CONDUCT.md"
        "zeroclaw/CONTRIBUTING.md"
        "zeroclaw/SECURITY.md"
        "zeroclaw/TRANSLATIONS.md"
        "zeroclaw/LICENSE-APACHE"
        "zeroclaw/LICENSE-MIT"
        "zeroclaw/NOTICE"
        
        # Unreferenced files in zeroclaw-android
        "zeroclaw-android/agent.py"
    )

    local existing_items=()
    local total_saved_kb=0

    for item in "${items_to_remove[@]}"; do
        if [ -e "$item" ]; then
            existing_items+=("$item")
            # Measure size
            local size=$(du -sk "$item" 2>/dev/null | cut -f1 || echo 0)
            total_saved_kb=$((total_saved_kb + size))
            echo_info "  Found target: $item (~$((size / 1024))MB)"
        fi
    done

    if [ ${#existing_items[@]} -eq 0 ]; then
        echo_success "No unused crates or junk files found to clean."
        return 0
    fi

    echo_info "\nTotal estimated space to free: ~$((total_saved_kb / 1024))MB"
    if ! confirm_action "Proceed to delete these ${#existing_items[@]} items?"; then
        echo_info "Skipping deletion."
        return 0
    fi

    for item in "${existing_items[@]}"; do
        if [ "$DRY_RUN" = true ]; then
            echo_info "DRY RUN: rm -rf $item"
        else
            rm -rf "$item"
            echo_success "  ✓ Deleted: $item"
        fi
    done
}

# ============================================================================
# PHASE 2: REMOVE DYNAMIC BUILD ARTIFACTS AND LOCAL CACHES
# ============================================================================

remove_build_artifacts_and_caches() {
    echo_info "\n[3/4] Cleaning Build Artifacts and Local Caches..."
    
    local cache_items=(
        "app/build"
        "lib/build"
        "zeroclaw-android/target"
        ".gradle"
        ".kotlin"
        ".android-user-home"
        ".idea"
        ".tmp-tools"
    )

    local existing_caches=()
    for cache in "${cache_items[@]}"; do
        if [ -e "$cache" ]; then
            existing_caches+=("$cache")
            echo_info "  Found cache: $cache"
        fi
    done

    if [ ${#existing_caches[@]} -eq 0 ]; then
        echo_success "All build and cache directories are clean."
        return 0
    fi

    if ! confirm_action "Proceed to delete these ${#existing_caches[@]} local cache/build directories?"; then
        echo_info "Skipping cache cleanup."
        return 0
    fi

    for cache in "${existing_caches[@]}"; do
        if [ "$DRY_RUN" = true ]; then
            echo_info "DRY RUN: rm -rf $cache"
        else
            rm -rf "$cache"
            echo_success "  ✓ Cleaned: $cache"
        fi
    done
}

# ============================================================================
# PHASE 3: FINAL CLEANUP & GIT REFRESH
# ============================================================================

run_git_refresh() {
    echo_info "\n[4/4] Refreshing Git Repository status..."
    
    if ! command -v git &> /dev/null; then return 0; fi

    if [ "$DRY_RUN" = true ]; then
        echo_info "DRY RUN: git rm -r --cached <deleted files>"
        echo_info "DRY RUN: git gc --aggressive --prune=now"
    else
        # Check if we also want to remove CLEANUP_SUMMARY.txt
        if [ -f "CLEANUP_SUMMARY.txt" ]; then
            if confirm_action "Remove deprecated CLEANUP_SUMMARY.txt?"; then
                rm -f "CLEANUP_SUMMARY.txt"
                echo_success "Removed CLEANUP_SUMMARY.txt"
            fi
        fi
        
        echo_info "  Removing tracked references from Git cache..."
        git status --porcelain | while read -r line; do
            if [[ "$line" =~ ^\ D\ (.*) ]]; then
                local file="${BASH_REMATCH[1]}"
                git rm --cached "$file" &>/dev/null || true
            fi
        done
        
        echo_info "  Running aggressive garbage collection (this may take a minute)..."
        git gc --aggressive --prune=now &>/dev/null
        echo_success "Git garbage collection complete."
    fi
}

# ============================================================================
# MAIN
# ============================================================================

main() {
    echo ""
    echo -e "${INFO}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${INFO}║  Zero-Assist Repository Deep Cleanup Script (Bash)         ║${NC}"
    echo -e "${INFO}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""

    if [ ! -d "zeroclaw" ] || [ ! -d "app" ]; then
        echo_error "Error: This script must be run from the root of Zero-Assist-main repository."
        exit 1
    fi

    if [ "$DRY_RUN" = true ]; then
        echo_warning "Running in DRY-RUN mode - no actual changes will be made"
    fi

    prepare_backup
    remove_unused_crates_and_code
    remove_build_artifacts_and_caches
    run_git_refresh

    echo ""
    echo_success "Deep Cleanup Script Execution Completed!"
    echo ""
}

main "$@"
