//! Interactive hardware onboarding wizard — guides users through device setup.

#![cfg(feature = "hardware")]

use crate::device::{DeviceEntry, DeviceKind, DeviceRegistry};
use crate::discover;
use anyhow::Result;

/// Configuration collected during wizard flow.
#[derive(Debug, Clone)]
pub struct WizardResult {
    pub devices: Vec<WizardDeviceConfig>,
}

/// Configuration for a single device discovered by the wizard.
#[derive(Debug, Clone)]
pub struct WizardDeviceConfig {
    pub alias: String,
    pub port: String,
    pub kind: DeviceKind,
    pub board_name: Option<String>,
    pub architecture: Option<String>,
}

/// Run the non-interactive hardware discovery wizard.
///
/// In interactive mode this would ask the user to confirm each detected device.
/// In the current build we auto-discover and assign aliases deterministically.
pub async fn run_wizard() -> Result<WizardResult> {
    tracing::info!("Running hardware discovery wizard...");

    #[cfg(any(target_os = "linux", target_os = "macos", target_os = "windows"))]
    let devices = discover::scan_serial_devices();
    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    let devices: Vec<discover::SerialDeviceInfo> = Vec::new();

    if devices.is_empty() {
        tracing::info!("No serial devices detected.");
        return Ok(WizardResult { devices: Vec::new() });
    }

    let mut kind_counters: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
    let mut wizard_devices = Vec::new();

    for dev in &devices {
        let kind = infer_device_kind(dev);
        let kind_key = format!("{kind:?}").to_lowercase();
        let count = kind_counters.entry(kind_key.clone()).or_insert(0);
        let alias = format!("{kind_key}{count}");
        *count += 1;

        tracing::info!(
            "Detected device: {} @ {} (alias: {alias})",
            dev.board_name.as_deref().unwrap_or("unknown"),
            dev.port_path,
        );

        wizard_devices.push(WizardDeviceConfig {
            alias,
            port: dev.port_path.clone(),
            kind,
            board_name: dev.board_name.clone(),
            architecture: dev.architecture.clone(),
        });
    }

    Ok(WizardResult { devices: wizard_devices })
}

/// Convert wizard results into DeviceRegistry entries.
pub fn wizard_to_registry(result: WizardResult) -> DeviceRegistry {
    let mut registry = DeviceRegistry::new();
    for cfg in result.devices {
        let entry = DeviceEntry::new(
            cfg.alias,
            cfg.port,
            cfg.kind,
            cfg.board_name,
            cfg.architecture,
        );
        registry.register(entry);
    }
    registry
}

fn infer_device_kind(dev: &discover::SerialDeviceInfo) -> DeviceKind {
    match (dev.vid, dev.pid) {
        (0x2e8a, _) => DeviceKind::Pico,
        (0x2341, _) => DeviceKind::Arduino,
        (0x0483, _) => DeviceKind::Nucleo,
        (0x0403, 0xaa01) => DeviceKind::Aardvark,
        _ => {
            if let Some(ref name) = dev.board_name {
                let lower = name.to_lowercase();
                if lower.contains("pico") {
                    return DeviceKind::Pico;
                }
                if lower.contains("arduino") {
                    return DeviceKind::Arduino;
                }
                if lower.contains("esp") {
                    return DeviceKind::Esp32;
                }
            }
            DeviceKind::Generic
        }
    }
}
