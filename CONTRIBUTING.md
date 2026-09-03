# Contributing

Thanks for helping improve Zero-Assist.

## Development Setup

1. Install JDK 17, Android SDK API 35, Android NDK `25.2.9519653`, Rust stable, and `cargo-ndk`.
2. Clone with submodules:

```bash
git clone --recursive https://github.com/Tanmay-1122/Zero-Assist.git
cd Zero-Assist
```

3. Build the app:

```bash
./gradlew :app:assembleDebug
```

## Checks

Run the focused checks before opening a pull request:

```bash
./gradlew spotlessCheck detekt
./gradlew :app:testDebugUnitTest :lib:testDebugUnitTest
cd zeroclaw-android && cargo test -p zeroclaw-ffi
```

## Pull Requests

- Keep changes focused.
- Do not change `applicationId = "com.zeroclaw.android"` unless a migration plan is approved.
- Do not rename the `zeroclaw/` submodule or `zeroclaw-android/` FFI workspace without a dedicated migration.
- Include tests for behavior changes when practical.
- Update README or release notes for user-visible changes.

## Code Style

- Kotlin uses the project Gradle, Detekt, and Spotless configuration.
- Rust uses the workspace `rustfmt`, Clippy, and Cargo configuration.
- Prefer small, explicit changes over broad rewrites.
