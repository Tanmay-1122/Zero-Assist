//! UF2 firmware flashing — copy .uf2 images to Pico bootloader mass-storage.

#![cfg(feature = "hardware")]

use anyhow::{Context, Result};
use std::path::{Path, PathBuf};
use std::time::Duration;

const UF2_WAIT_SECS: u64 = 10;
const UF2_POLL_MS: u64 = 200;

/// Find the UF2 mount point (RPI-RP2 volume).
pub fn find_uf2_mount() -> Option<PathBuf> {
    #[cfg(target_os = "linux")]
    {
        for entry in std::fs::read_dir("/media").ok()?.flatten() {
            let path = entry.path();
            if path.join("INFO_UF2.TXT").exists() {
                return Some(path);
            }
        }
        // Also check /mnt
        for entry in std::fs::read_dir("/mnt").ok()?.flatten() {
            let path = entry.path();
            if path.join("INFO_UF2.TXT").exists() {
                return Some(path);
            }
        }
        None
    }
    #[cfg(target_os = "macos")]
    {
        for entry in std::fs::read_dir("/Volumes").ok()?.flatten() {
            let path = entry.path();
            if path.join("INFO_UF2.TXT").exists() {
                return Some(path);
            }
        }
        None
    }
    #[cfg(target_os = "windows")]
    {
        // Check all drive letters for RPI-RP2 volume
        for letter in b'D'..=b'Z' {
            let drive = format!("{}:\\", letter as char);
            let info = PathBuf::from(&drive).join("INFO_UF2.TXT");
            if info.exists() {
                return Some(PathBuf::from(drive));
            }
        }
        None
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        None
    }
}

/// Wait for a UF2 mount to appear, polling every `UF2_POLL_MS` ms.
pub async fn wait_for_uf2_mount() -> Option<PathBuf> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(UF2_WAIT_SECS);
    while tokio::time::Instant::now() < deadline {
        if let Some(mount) = find_uf2_mount() {
            return Some(mount);
        }
        tokio::time::sleep(Duration::from_millis(UF2_POLL_MS)).await;
    }
    None
}

/// Flash a `.uf2` file to a Pico in BOOTSEL mode.
///
/// Copies the file to the UF2 mass-storage volume. The Pico automatically
/// reboots and runs the new firmware after the copy completes.
pub async fn flash_uf2(uf2_path: &Path) -> Result<()> {
    anyhow::ensure!(
        uf2_path.exists(),
        "UF2 file not found: {}",
        uf2_path.display()
    );

    let mount = wait_for_uf2_mount()
        .await
        .context("Timed out waiting for UF2 mount — make sure the Pico is in BOOTSEL mode")?;

    let filename = uf2_path
        .file_name()
        .context("UF2 path has no filename")?;
    let dest = mount.join(filename);

    tracing::info!(
        "Flashing {} → {}",
        uf2_path.display(),
        dest.display()
    );

    tokio::fs::copy(uf2_path, &dest)
        .await
        .with_context(|| format!("Failed to copy UF2 to {}", dest.display()))?;

    tracing::info!("Flash complete — Pico is rebooting");
    Ok(())
}
