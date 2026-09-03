# Zero-Assist Engine Config

This folder stores **project-owned Zero-Assist engine extensions** that should stay outside the upstream `zeroclaw/` source tree.

## What belongs here

- `assets/overlay.toml`: additive TOML blocks injected at runtime by Android startup.
- `patches/`: optional patch files for local upstream customizations.
- `scripts/`: helper scripts for exporting/applying `zeroclaw/` patch stacks.
- `notes/` (optional): migration notes for upstream version bumps.

## Runtime connection

The app now wires this folder automatically:

1. `app/build.gradle.kts` includes `zeroclaw-config/assets` as app assets.
2. On app start, `ExternalZeroClawConfig.installBundledOverlay(...)` copies `overlay.toml` into app storage:
   - `<filesDir>/zeroclaw-config/overlay.toml`
3. Before daemon start, TOML is composed with the overlay via `ExternalZeroClawConfig.applyOverlay(...)`.

## Upstream update workflow

1. Keep app-specific TOML additions in `assets/overlay.toml` (avoid editing upstream config defaults when possible).
2. If code-level local changes are still needed, export patches with:
   - `powershell -File zeroclaw-config/scripts/export-zeroclaw-patch.ps1`
3. Run one-command sync (pull upstream + re-apply patches):
   - `powershell -File zeroclaw-config/scripts/sync-upstream.ps1`
4. If you want only sync (no patch replay), use:
   - `powershell -File zeroclaw-config/scripts/sync-upstream.ps1 -SkipPatchApply`
5. Rebuild and run targeted tests before merging.

Keeping custom behavior in this folder reduces merge conflicts when the upstream engine changes.
