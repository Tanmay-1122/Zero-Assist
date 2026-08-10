<div align="center">

<!--
  Drop a wide banner/hero image here once you have one, e.g.:
  <img src="docs/screenshots/banner.png" alt="Zero-Assist" width="100%" />
-->

# 🦾 Zero-Assist

### An open-source, on-device AI agent for Android — powered by the **ZeroClaw** Rust engine

**No cloud lock-in. No subscriptions. Your phone, your agent, your data.**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![CI](https://github.com/Tanmay-1122/Zero-Assist/actions/workflows/ci.yml/badge.svg)](https://github.com/Tanmay-1122/Zero-Assist/actions/workflows/ci.yml)
[![GitHub stars](https://img.shields.io/github/stars/Tanmay-1122/Zero-Assist?style=flat&color=gold)](https://github.com/Tanmay-1122/Zero-Assist/stargazers)
[![GitHub last commit](https://img.shields.io/github/last-commit/Tanmay-1122/Zero-Assist)](https://github.com/Tanmay-1122/Zero-Assist/commits/main)
[![GitHub issues](https://img.shields.io/github/issues/Tanmay-1122/Zero-Assist)](https://github.com/Tanmay-1122/Zero-Assist/issues)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)](app/src/main/AndroidManifest.xml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7f52ff?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Rust](https://img.shields.io/badge/Rust-edition%202024-dea584?logo=rust&logoColor=white)](zeroclaw/Cargo.toml)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

**[Features](#-features) • [Screenshots](#-screenshots--demo) • [Quick Start](#-quick-start) • [Architecture](#-architecture) • [Integrations](#-supported-integrations) • [Contributing](#-contributing)**

</div>

---

## 📖 Overview

**Zero-Assist** is a self-hosted, personal AI assistant that runs directly on your phone. It wraps **ZeroClaw** — a fast, small, feature-gated Rust agent engine — behind a modern Material 3 Jetpack Compose UI, and extends it with deep Android-native capabilities that most "AI assistant" apps simply don't have:

- 🧠 **Real device control** — the agent can see your screen and drive the UI through Android's accessibility service, just like a human would.
- 🐧 **A real Linux sandbox on-device** — a PRoot-based rootfs so the agent can run genuine shell tooling, not a toy fake terminal.
- 🔌 **A terminal-native integration layer** — Termux bridging with per-command approval, audit trails, and streaming execution.
- 🎙️ **On-device speech & local models** — recognition, Piper TTS, LiteRT LM, ONNX Runtime, and ML Kit GenAI, so core functionality keeps working with zero cloud dependency.
- 🛰️ **30 messaging channel integrations** and **15 LLM providers** baked into the underlying engine, so the same agent can live in Telegram, Discord, WhatsApp, Slack, email, and more.
- 🖼️ **Rich chat rendering** — markdown, tables, code blocks, and inline images rendered natively in the terminal chat.

The app targets **Android 8.0+ (API 26) through API 35**, with split APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.

> This is a real, working codebase — not a wrapper around a hosted API. The entire agent runtime is vendored and compiled from Rust source directly into the app.

---

## 📸 Screenshots & Demo

<!--
  ADD YOUR SCREENSHOTS HERE. Suggested convention (create the files, the
  table below already points at these paths):

    docs/screenshots/dashboard.png
    docs/screenshots/terminal.png
    docs/screenshots/voice-assistant.png
    docs/screenshots/agent-group-chat.png
    docs/screenshots/device-control.png
    docs/screenshots/plugins-mcp.png
    docs/screenshots/settings-security.png
    docs/screenshots/sandbox-termux.png

  Once the images exist at those paths, this table will render automatically.
-->

| Dashboard | Terminal / Agent Chat | Voice Assistant |
|:---:|:---:|:---:|
| ![Dashboard](docs/screenshots/dashboard.png) | ![Terminal](docs/screenshots/terminal.png) | ![Voice Assistant](docs/screenshots/voice-assistant.png) |

| Agent Group Chat | Device Control | Plugins & MCP |
|:---:|:---:|:---:|
| ![Agent Group Chat](docs/screenshots/agent-group-chat.png) | ![Device Control](docs/screenshots/device-control.png) | ![Plugins & MCP](docs/screenshots/plugins-mcp.png) |

### 🎬 Demo

<!--
  For a screen recording, either:
  1. Drop a GIF at docs/demo.gif and reference it below, or
  2. Upload the video by dragging it into a GitHub issue/PR comment — GitHub
     will host it and give you a `user-images.githubusercontent.com` link
     you can embed directly, e.g.:

     https://github.com/Tanmay-1122/Zero-Assist/assets/<id>/<hash>

  Then replace this block with:
    ![Demo](docs/demo.gif)
  or an embedded video / linked asset.
-->

_Screen recording coming soon — see [`docs/screenshots/README.md`](docs/screenshots/README.md) for the media checklist._

---

## 📚 Table of Contents

- [Overview](#-overview)
- [Screenshots & Demo](#-screenshots--demo)
- [Features](#-features)
- [Supported Integrations](#-supported-integrations)
- [Architecture](#-architecture)
- [Repository Layout](#-repository-layout)
- [Tech Stack](#-tech-stack)
- [Quick Start](#-quick-start)
- [Building](#-building)
- [Testing](#-testing)
- [Configuration](#-configuration)
- [FAQ](#-faq)
- [Roadmap](#-roadmap)
- [Contributing](#-contributing)
- [License](#-license)

---

## ✨ Features

<details open>
<summary><b>💬 Chat, Agents & Terminal</b></summary>
<br>

- Interactive terminal REPL backed by the Rust engine, with a native markdown renderer (tables, code blocks, headings, **inline images**), streaming output, and live tool-call display.
- Multi-agent management — create, edit, and monitor agents with per-agent model/tool configuration.
- **Agent group chat** with @mentions and master-delegation gating between agents.
- Skills registry, granular skill permissions, and a skills marketplace client.
- A unified plugins and channels hub for connecting the engine to the outside world.
- Background process tracking with live status, token counts, cost, and latency per turn.

</details>

<details>
<summary><b>🎙️ Voice Assistant</b></summary>
<br>

- Hands-free assistant popup, launchable via `ACTION_ASSIST`, the launcher, or a floating trigger.
- Fully on-device speech recognition and synthesis (Piper ONNX TTS) — no cloud speech API required.
- Wake-word detection with a dedicated foreground service and per-device voice profiles/tiers.

</details>

<details>
<summary><b>🤖 Device Automation</b></summary>
<br>

- **Accessibility-based device control**: live screen observation, UI fingerprinting, and a model-backed planner that executes real actions — tap, type, swipe, launch apps.
- A full **UI agent loop**: parses a natural-language goal, plans, acts, verifies the result, and recovers from failures, with dedicated safety policies.
- Quick native intents for calls, SMS, media control, and a file-share controller.

</details>

<details>
<summary><b>🐧 Linux Sandbox & Termux</b></summary>
<br>

- **PRoot sandbox manager**: on-demand Linux rootfs download, persistent sandbox shells, and a local sidecar bridge server so the agent can orchestrate real Linux tooling.
- **Termux bridge**: capability probing, streaming execution, tiered command policies, per-command approval with audit trail, and automatic reconnection.

</details>

<details>
<summary><b>⚙️ Daemon & System Integration</b></summary>
<br>

- Persistent foreground daemon service with a quick-settings tile and boot-time auto-start.
- Battery-aware settings and background work scheduling via WorkManager.

</details>

<details>
<summary><b>🧠 On-Device AI</b></summary>
<br>

- LiteRT (LiteRT LM) model downloads and local inference, ONNX Runtime for other local models.
- Google ML Kit GenAI utilities: prompting, summarization, proofreading, rewriting, and image description — all on-device.
- Local embeddings with configurable embedding routes feeding an advanced, importance-scored memory system.

</details>

<details>
<summary><b>🔧 Hardware & I/O</b></summary>
<br>

- USB serial device support, CameraX capture with ML Kit barcode scanning, Bluetooth, contacts, calls, and SMS — all exposed to the agent through a curated, permission-gated tool catalog.
- GPIO pin control, sensor monitoring with configurable alerts, and actuator command execution for hobbyist/IoT hardware projects.

</details>

<details>
<summary><b>🔒 Security & Privacy</b></summary>
<br>

- SQLCipher-encrypted Room database, encrypted API-key storage, and secure preferences.
- Screen-recording protection (`FLAG_SECURE`) in release builds.
- Dedicated security/autonomy surfaces: identity, trust policy, WebAuthn routes, tiered approval policies, and an **emergency stop (estop)** that immediately halts agent actions.

</details>

<details>
<summary><b>🌐 Engine Integration</b></summary>
<br>

- Bundles the full ZeroClaw engine: **30 messaging channels**, **15 LLM providers**, and a **60+ tool catalog** (see [Supported Integrations](#-supported-integrations) below).
- Gateway with a React web dashboard, ACP server support, Prometheus/OTel observability, and pluggable memory backends.
- Hierarchical MCP (Model Context Protocol) registry — connect external MCP servers and the engine exposes each as a callable sub-agent tool automatically.

</details>

---

## 🔗 Supported Integrations

<details>
<summary><b>30 Messaging Channels</b> — click to expand</summary>
<br>

Telegram · Discord · Slack · Signal · Matrix · Mattermost · WhatsApp (Cloud & Web) · Gmail (push) · Email (generic) · IRC · iMessage · Line · Lark · DingTalk · WeCom · QQ · MoChat · Nextcloud Talk · Nostr · Notion · Reddit · Twitter/X · Bluesky · LINQ · WATI · ClawdTalk · Voice calls · Webhooks · CLI

</details>

<details>
<summary><b>15 LLM Providers</b> — click to expand</summary>
<br>

Anthropic · OpenAI · OpenAI Codex (OAuth) · Azure OpenAI · Amazon Bedrock · Google Gemini · Gemini CLI · GitHub Copilot · Ollama (local) · OpenRouter · GLM · KiloCLI · Telnyx · Claude Code · any OpenAI-compatible endpoint — with automatic reliability fallback and routing across providers.

</details>

<details>
<summary><b>60+ Native Tools</b> — click to expand</summary>
<br>

Memory (store/recall/forget/export/purge) · web search & fetch · sandbox execute & process management · Google Workspace (Drive, Gmail, Calendar, Sheets, Docs) · Microsoft 365 · Notion · Jira · LinkedIn · Git operations · file read/write/edit/glob search · browser control · image generation & description · PDF reading · calculator · weather · knowledge base · report templates · device control · hardware board/memory tools · sub-agent delegation & swarm coordination · MCP client (connect any external MCP server) · and more.

</details>

---

## 🏗️ Architecture

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

## 📁 Repository Layout

| Path | Description |
| --- | --- |
| `app/` | Native Android application (Compose, Material 3, Room, services, screens) |
| `lib/` | Android library module — JNA/UniFFI bridge to the Rust engine, published as `com.zeroclaw:zeroclaw-android` |
| `zeroclaw/` | Vendored ZeroClaw engine — Rust workspace (13 crates: runtime, channels, gateway, tools, memory, providers, hardware, config, plugins, mcp-gateway, infra, api, macros, tool-call-parser) + React web dashboard |
| `zeroclaw-android/zeroclaw-ffi/` | UniFFI-annotated Rust facade exporting engine capabilities to Android |
| `zeroclaw-config/` | Project-owned engine config overlay (`assets/overlay.toml`), patch/export/sync scripts |
| `patches/` | Local patch stack applied to the vendored engine during builds |
| `maestro/` | Maestro E2E flows for UI testing |
| `scripts/` | Build/test/verification helpers (PRoot setup, test runners, cleanup) |
| `docs/` | Project documentation, screenshots, and media |
| `config/detekt/` | Detekt configuration and baseline |
| `.github/workflows/` | CI, release, and upstream-sync pipelines |

---

## 🧰 Tech Stack

**Android app**
- Kotlin 2.0.20, AGP 8.8.1, Kotlin Compose compiler (strong skipping mode)
- Jetpack Compose + Material 3 (BOM 2024.12.01), Navigation Compose, adaptive window-size classes
- Room + SQLCipher, DataStore (Preferences + SecurePrefs), WorkManager, Lifecycle Process
- OkHttp, Coil 3, CameraX, ML Kit (barcode + GenAI), LiteRT LM, ONNX Runtime, USB Serial, JNA

**Rust engine**
- Edition 2024, rust-version 1.87, optimized-for-size release profile (`opt-level = "z"`, fat LTO)
- Tokio, axum (gateway), reqwest, rusqlite, UniFFI 0.29
- 13 workspace crates spanning runtime, channels, gateway, tools, memory, providers, hardware, config, plugins, MCP gateway, infra, api, and macros

---

## 🚀 Quick Start

### Option 1 — Download a build

Grab the latest APK from the [Releases](https://github.com/Tanmay-1122/Zero-Assist/releases) page, matching your device's ABI (`arm64-v8a` for most modern phones), and sideload it.

### Option 2 — Build from source

```bash
git clone --recursive https://github.com/Tanmay-1122/Zero-Assist.git
cd Zero-Assist
./gradlew :app:assembleDebug
```

See [Building](#-building) below for prerequisites and details.

---

## 🔨 Building

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

## ✅ Testing

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

## ⚙️ Configuration

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

## ❓ FAQ

<details>
<summary>Does Zero-Assist need an internet connection?</summary>
<br>

Not for core functionality. Speech recognition, TTS, and on-device model inference (LiteRT/ONNX/ML Kit GenAI) work fully offline. You'll need a connection for cloud LLM providers, messaging channels, and MCP servers that are themselves remote — or you can point it at a local model via Ollama for a fully offline agent.

</details>

<details>
<summary>Where is my data stored?</summary>
<br>

Locally, in an SQLCipher-encrypted Room database on your device. API keys live in encrypted preferences. Nothing is sent anywhere except the LLM provider and channels/tools you explicitly configure.

</details>

<details>
<summary>Can I add my own tools, channels, or LLM providers?</summary>
<br>

Yes. The engine's tool, channel, and provider traits are extensible at the Rust layer, and any external service that speaks MCP (Model Context Protocol) can be connected without touching engine code at all — it will automatically appear as a callable tool.

</details>

<details>
<summary>What Android versions are supported?</summary>
<br>

Android 8.0 (API 26) through Android 15 (API 35), with prebuilt split APKs for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.

</details>

<details>
<summary>Is this affiliated with any commercial AI product?</summary>
<br>

No. Zero-Assist is an independent, community-maintained open-source project built on top of the vendored ZeroClaw engine (see [NOTICE.md](NOTICE.md) for upstream attribution).

</details>

---

## 🗺️ Roadmap

Zero-Assist evolves through community proposals and issues rather than a fixed top-down plan. Recent milestones include the full accessibility-based device-control loop, the PRoot Linux sandbox, and native inline image rendering in the terminal chat. For what's currently planned or in progress, see the [Issues](https://github.com/Tanmay-1122/Zero-Assist/issues) and [Pull Requests](https://github.com/Tanmay-1122/Zero-Assist/pulls) tabs — and feel free to open a proposal of your own.

---

## 🤝 Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, checks, and PR guidelines. In short:

- Keep changes focused and include tests for behavior changes.
- Run `spotlessCheck`, `detekt`, unit tests, and `cargo test -p zeroclaw-ffi` before opening a PR.
- Do not change `applicationId` or the engine/FFI directory layout without an approved migration plan.
- Engine-local changes go through `zeroclaw-config/` patches where possible.

If you find Zero-Assist useful, consider ⭐ **starring the repo** — it genuinely helps others discover the project.

---

## 📄 License

[MIT](LICENSE) © 2026 ZeroClaw Community. See [NOTICE.md](NOTICE.md) for upstream engine attribution.

---

<div align="center">

### ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Tanmay-1122/Zero-Assist&type=Date)](https://star-history.com/#Tanmay-1122/Zero-Assist&Date)

</div>
