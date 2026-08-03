# Zero-Assist

An open-source, on-device AI assistant for Android, powered by the **ZeroClaw** Rust engine. Zero-Assist combines a native Kotlin/Jetpack Compose app with a 100% Rust agent runtime — no cloud dependencies required for core functionality, with on-device speech, vision, models, and device automation built in.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android)](app/src/main/AndroidManifest.xml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7f52ff?logo=kotlin)](gradle/libs.versions.toml)
[![Rust](https://img.shields.io/badge/Rust-edition%202024-dea584?logo=rust)](zeroclaw/Cargo.toml)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Repository Layout](#repository-layout)
- [Tech Stack](#tech-stack)
- [Building](#building)
- [Testing](#testing)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

Zero-Assist is a self-hosted personal AI assistant that runs directly on your phone. It wraps the ZeroClaw engine (a fast, small, feature-gated Rust agent) behind a modern Material 3 Compose UI and a UniFFI/JNA bridge, and extends it with Android-native capabilities:

- **Device control** through the Android accessibility service — the agent can observe the screen and drive the UI.
- **A PRoot-based Linux sandbox** so the agent can run real Linux tooling on-device.
- **Termux integration** with streaming execution, command policies, and per-command approval.
- **On-device AI** — speech recognition, Piper TTS, LiteRT/ONNX models, and ML Kit GenAI utilities.
- **A persistent daemon** with a quick-settings tile, and boot-time startup.

The app targets Android 8.0+ (API 26) through API 35, with split APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.

---

## Features

### Chat, Agents & Terminal
- Interactive terminal REPL backed by the Rust engine, with a canvas-style markdown renderer, streaming output, and tool-call display.
- Multi-agent management: create, edit, and monitor agents with per-agent model/tool configuration.
- Agent **group chat** with mentions and master-delegation gating.
- Skills registry, skill permissions, and a skills marketplace client.
- Plugins and channels hub for connecting the engine to the outside world.

### Voice Assistant
- Hands-free assistant popup launched via `ACTION_ASSIST`, the launcher, or a floating trigger.
- On-device speech recognition and synthesis (Piper ONNX TTS), with local voice storage.
- Wake-word readiness foreground service and per-app voice device profiles.

### Device Automation
- **Accessibility-based device control**: screen observation, fingerprinting, and a model-backed planner that executes real UI actions (tap, type, swipe, launch apps).
- **UI agent loop** that parses goals, executes actions, verifies results, and recovers from failures — with safety policies.
- Quick intents (calls, SMS, media control) and a file share controller.

### Linux Sandbox & Termux
- **PRoot sandbox manager**: Linux rootfs download, persistent sandbox shells, and a sidecar bridge server for agent-orchestrated Linux tooling.
- **Termux bridge**: capability probing, streaming executor, command policy tiers, approval notifications with audit trail, and auto-connection.

### Daemon
- Foreground daemon service, quick-settings tile, boot receiver, and battery-aware settings.

### On-Device AI
- LiteRT (LiteRT LM) model downloads and inference, ONNX Runtime for local models, and Google ML Kit GenAI utilities (prompting, summarization, proofreading, rewriting, image description).
- Local embeddings with configurable embedding routes and advanced memory.

### Hardware & I/O
- USB serial devices, CameraX capture with ML Kit barcode scanning, Bluetooth, contacts, calls, and SMS — exposed to the agent through a curated tool catalog.

### Security & Privacy
- SQLCipher-encrypted Room database, encrypted API-key storage, and secure-prefs.
- Screen-recording protection (`FLAG_SECURE`) in release builds.
- Security/autonomy settings surfaces: identity, trust, webauthn routes, approval policies, and an emergency stop (estop).

### Engine Integration
- Bundles the full ZeroClaw engine with 25+ messaging channels (Telegram, Discord, Slack, Signal, Mattermost, WhatsApp Cloud, email, IRC, DingTalk, QQ, and more).
- Gateway with a React web dashboard, ACP server support, observability (Prometheus/OTel), and memory backends.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Android App (Kotlin)                 │
│  Compose UI · ViewModels · Room/SQLCipher · WorkManager     │
│  Voice · Terminal · Device Control · Sandbox · Scheduler    │
└──────────────────────────┬──────────────────────────────────┘
                           │ UniFFI bindings (generated)
┌──────────────────────────▼──────────────────────────────────┐
│          :lib — Android library (JNA + UniFFI)              │
└──────────────────────────┬──────────────────────────────────┘
                           │ cdylib (libzeroclaw.so)
┌──────────────────────────▼──────────────────────────────────┐
│          zeroclaw-android/zeroclaw-ffi (Rust crate)         │
│  REPL · sessions · agents · skills · workspace ·             │
│  device-control dispatch · sandbox/termux bridges · vision  │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│             zeroclaw — Rust engine (vendored upstream)      │
│  runtime · channels · gateway · memory · tools · hardware   │
│  config · plugins · observability · web dashboard           │
└─────────────────────────────────────────────────────────────┘
```

The app (`:app`) depends on a small Android library (`:lib`) that builds the Rust crate `zeroclaw-ffi` with [Gobley](https://github.com/google/gobley) + [UniFFI](https://mozilla.github.io/uniffi-rs/) and exposes the engine through generated JNA bindings. All engine config is composed at runtime from the app UI plus the project-owned TOML overlay in `zeroclaw-config/`.

The Rust engine is vendored under `zeroclaw/` and kept in sync with upstream via the `upstream-sync` GitHub workflow, with project-local patches under `patches/` applied during CI builds.

---

## Repository Layout

| Path | Description |
| --- | --- |
| `app/` | Native Android application (Compose, Material 3, Room, services, screens) |
| `lib/` | Android library module — JNA/UniFFI bridge to the Rust engine, published as `com.zeroclaw:zeroclaw-android` |
| `zeroclaw/` | Vendored ZeroClaw engine — Rust workspace (runtime, channels, gateway, tools, memory, …) + React web dashboard |
| `zeroclaw-android/zeroclaw-ffi/` | UniFFI-annotated Rust facade exporting engine capabilities to Android |
| `zeroclaw-config/` | Project-owned engine config overlay (`assets/overlay.toml`), patch/export/sync scripts |
| `patches/` | Local patch stack applied to the vendored engine during builds |
| `maestro/` | Maestro E2E flows for UI testing |
| `scripts/` | Build/test/verification helpers (PRoot setup, test runners, cleanup) |
| `config/detekt/` | Detekt configuration and baseline |
| `.github/workflows/` | CI, release, and upstream-sync pipelines |

---

## Tech Stack

**Android app**
- Kotlin 2.0.20, AGP 8.8.1, Kotlin Multiplatform Compose compiler (strong skipping)
- Jetpack Compose + Material 3 (BOM 2024.12.01), Navigation Compose, adaptive window-size classes
- Room + SQLCipher, DataStore (Preferences + SecurePrefs), WorkManager, Lifecycle Process
- OkHttp, Coil 3, CameraX, ML Kit (barcode + GenAI), LiteRT LM, ONNX Runtime, USB Serial, JNA

**Rust engine**
- Edition 2024, rust-version 1.87, optimized-for-size release profile (`opt-level = "z"`, fat LTO)
- Tokio, axum (gateway), reqwest, rusqlite, UniFFI 0.29
- 14 workspace crates: runtime, channels, gateway, tools, memory, hardware, config, plugins, macros, api, infra, providers, tool-call-parser, channels

---

## Building

### Prerequisites

- JDK 17
- Android SDK (API 35) and NDK `25.2.9519653`
- Rust stable toolchain with `cargo-ndk` and Android targets:
  `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android`

### Clone & build

```bash
git clone --recursive https://github.com/Tanmay-1122/Zero-Assist.git
cd Zero-Assist

# Build the debug APKs (split per ABI)
./gradlew :app:assembleDebug
```

Output APKs land in `app/build/outputs/apk/debug/` (e.g. `app-arm64-v8a-debug.apk`).

Notes:

- The Gradle build automatically downloads the PRoot native libraries (`libproot.so`, `libproot-loader.so`, `libtalloc.so`, …) from the Termux repository into `app/src/main/jniLibs/<abi>/` when missing. They can also be produced offline with `./build-proot.sh`.
- The `:lib` module compiles the Rust engine for Android via `cargo-ndk`; the first build downloads the full Rust dependency tree.
- By default, debug builds compile all four ABIs. For faster local builds targeting real phones only, set `zeroAssist.phoneOnlyAbi=true` in `gradle.properties` (already the default in this repo).
- Install directly to a connected device with `./gradlew installSamsungDebug` (add `-PzeroAssist.adbSerial=<serial>` if multiple devices are attached).

### Release builds

Configure signing in `local.properties`, then:

```bash
./gradlew :app:assembleRelease
```

```properties
RELEASE_STORE_FILE=/path/to/keystore.jks
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=…
RELEASE_KEY_PASSWORD=…
```

---

## Testing

```bash
# Kotlin unit tests
./gradlew :app:testDebugUnitTest :lib:testDebugUnitTest

# Rust FFI tests
cd zeroclaw-android && cargo test -p zeroclaw-ffi

# Lint & formatting
./gradlew spotlessCheck detekt
cd zeroclaw-android && cargo fmt --check && cargo clippy -p zeroclaw-ffi --all-targets

# Compose screen tests on a managed device (Gradle Managed Devices)
./gradlew pixel7Api35DebugAndroidTest

# Maestro E2E flows (emulator required)
maestro test maestro/flows/ --exclude-tags real-daemon
```

CI runs all of the above on every push/PR, plus `cargo-deny` license/advisory checks and a version-sync check that keeps `zeroclaw-ffi` and `:lib` publication versions aligned. Dokka API docs are generated and deployed to GitHub Pages on `main`.

---

## Configuration

Runtime engine configuration is built by the app UI (model routes, channels, gateway, memory, etc.) and composed with the additive overlay in [`zeroclaw-config/assets/overlay.toml`](zeroclaw-config/assets/overlay.toml), which is bundled as an app asset and applied before the daemon starts.

Upstream sync workflow (keeps project-local changes isolated from upstream):

```bash
# Export project-local patches
powershell -File zeroclaw-config/scripts/export-zeroclaw-patch.ps1

# Pull upstream + re-apply patches
powershell -File zeroclaw-config/scripts/sync-upstream.ps1
# or skip patch replay:
powershell -File zeroclaw-config/scripts/sync-upstream.ps1 -SkipPatchApply
```

See [zeroclaw-config/README.md](zeroclaw-config/README.md) for details.

---

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, checks, and PR guidelines. In short:

- Keep changes focused and include tests for behavior changes.
- Run `spotlessCheck`, `detekt`, unit tests, and `cargo test -p zeroclaw-ffi` before opening a PR.
- Do not change `applicationId` or the engine/FFI directory layout without an approved migration plan.
- Engine-local changes go through `zeroclaw-config/` patches where possible.

---

## License

[MIT](LICENSE) © 2026 ZeroClaw Community. See [NOTICE.md](NOTICE.md) for upstream engine attribution.
