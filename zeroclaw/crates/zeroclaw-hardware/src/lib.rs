#![allow(clippy::to_string_in_format_args)]
//! Hardware discovery — USB device enumeration and introspection.
//!
//! See `docs/hardware-peripherals-design.md` for the full design.

pub mod catalog;
pub mod device;
pub mod gpio;
pub mod peripherals;
pub mod protocol;
pub mod registry;
pub mod transport;
pub mod tool_result;

#[cfg(all(
    feature = "hardware",
    any(target_os = "linux", target_os = "macos", target_os = "windows")
))]
pub mod discover;

#[cfg(all(
    feature = "hardware",
    any(target_os = "linux", target_os = "macos", target_os = "windows")
))]
pub mod introspect;

#[cfg(feature = "hardware")]
pub mod serial;

#[cfg(feature = "hardware")]
pub mod uf2;

#[cfg(feature = "hardware")]
pub mod pico_flash;

#[cfg(feature = "hardware")]
pub mod pico_code;

/// Aardvark USB adapter transport (I2C / SPI / GPIO via aardvark-sys).
#[cfg(feature = "hardware")]
pub mod aardvark;

/// Tools backed by the Aardvark transport (i2c_scan, i2c_read, i2c_write,
/// spi_transfer, gpio_aardvark).
#[cfg(feature = "hardware")]
pub mod aardvark_tools;

/// Datasheet management — search, download, and manage device datasheets.
/// Used by DatasheetTool when an Aardvark is connected.
#[cfg(feature = "hardware")]
pub mod datasheet;

/// Interactive hardware onboarding wizard UI.
#[cfg(feature = "hardware")]
pub mod wizard;

/// Raspberry Pi self-discovery and native GPIO tools.
/// Only compiled on Linux with the `peripheral-rpi` feature.
#[cfg(all(feature = "peripheral-rpi", target_os = "linux"))]
pub mod rpi;

pub mod util;

// ── Phase 4: ToolRegistry + plugin system ─────────────────────────────────────
pub mod loader;
pub mod manifest;
pub mod subprocess;
pub mod tool_registry;

#[cfg(feature = "hardware")]
#[allow(unused_imports)]
pub use aardvark::AardvarkTransport;

#[allow(unused_imports)]
pub use tool_registry::{ToolError, ToolRegistry};

// Re-export config types so wizard can use `hardware::HardwareConfig` etc.
pub use zeroclaw_config::schema::{HardwareConfig, HardwareTransport};

// ── Phase 5: boot() — hardware tool integration into agent loop ───────────────

/// Merge hardware tools from a [`HardwareBootResult`] into an existing tool
/// registry, deduplicating by name.
///
/// Returns a tuple of `(device_summary, added_tool_names)`.
pub fn merge_hardware_tools(
    tools: &mut Vec<Box<dyn zeroclaw_api::tool::Tool>>,
    hw_boot: HardwareBootResult,
) -> (String, Vec<String>) {
    let device_summary = hw_boot.device_summary.clone();
    let mut added_tool_names: Vec<String> = Vec::new();
    if !hw_boot.tools.is_empty() {
        let existing: std::collections::HashSet<String> =
            tools.iter().map(|t| t.name().to_string()).collect();
        let new_hw_tools: Vec<Box<dyn zeroclaw_api::tool::Tool>> = hw_boot
            .tools
            .into_iter()
            .filter(|t| !existing.contains(t.name()))
            .collect();
        if !new_hw_tools.is_empty() {
            added_tool_names = new_hw_tools.iter().map(|t| t.name().to_string()).collect();
            tracing::info!(count = new_hw_tools.len(), "Hardware registry tools added");
            tools.extend(new_hw_tools);
        }
    }
    (device_summary, added_tool_names)
}

/// Result of [`boot`]: tools to merge into the agent + device summary for the
/// system prompt.
pub struct HardwareBootResult {
    /// Tools to extend into the agent's `tools_registry`.
    pub tools: Vec<Box<dyn zeroclaw_api::tool::Tool>>,
    /// Human-readable device summary for the LLM system prompt.
    pub device_summary: String,
    /// Content of `~/.zeroclaw/hardware/` context files (HARDWARE.md, device
    /// profiles, and skills) for injection into the system prompt.
    pub context_files_prompt: String,
}

/// Load hardware context files from `~/.zeroclaw/hardware/` and return them
/// concatenated as a single markdown string ready for system-prompt injection.
///
/// Reads (if they exist):
/// 1. `~/.zeroclaw/hardware/HARDWARE.md`
/// 2. `~/.zeroclaw/hardware/devices/<alias>.md` for each discovered alias
/// 3. All `~/.zeroclaw/hardware/skills/*.md` files (sorted by name)
///
/// Missing files are silently skipped. Returns an empty string when no files
/// are found.
pub fn load_hardware_context_prompt(aliases: &[&str]) -> String {
    let home = match directories::BaseDirs::new().map(|d| d.home_dir().to_path_buf()) {
        Some(h) => h,
        None => return String::new(),
    };
    load_hardware_context_from_dir(&home.join(".zeroclaw").join("hardware"), aliases)
}

/// Inner helper that reads hardware context from an explicit base directory.
/// Separated from [`load_hardware_context_prompt`] to allow unit-testing with
/// a temporary directory.
pub fn load_hardware_context_from_dir(hw_dir: &std::path::Path, aliases: &[&str]) -> String {
    let mut sections: Vec<String> = Vec::new();

    // 1. Global HARDWARE.md
    let global = hw_dir.join("HARDWARE.md");
    if let Ok(content) = std::fs::read_to_string(&global)
        && !content.trim().is_empty()
    {
        sections.push(content.trim().to_string());
    }

    // 2. Per-device profile
    let devices_dir = hw_dir.join("devices");
    for alias in aliases {
        let path = devices_dir.join(format!("{alias}.md"));
        tracing::info!("loading device file: {:?}", path);
        if let Ok(content) = std::fs::read_to_string(&path)
            && !content.trim().is_empty()
        {
            sections.push(content.trim().to_string());
        }
    }

    // 3. Skills directory (*.md files, sorted)
    let skills_dir = hw_dir.join("skills");
    if let Ok(entries) = std::fs::read_dir(&skills_dir) {
        let mut skill_paths: Vec<std::path::PathBuf> = entries
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("md"))
            .collect();
        skill_paths.sort();
        for path in skill_paths {
            if let Ok(content) = std::fs::read_to_string(&path)
                && !content.trim().is_empty()
            {
                sections.push(content.trim().to_string());
            }
        }
    }

    if sections.is_empty() {
        return String::new();
    }
    sections.join("\n\n")
}
