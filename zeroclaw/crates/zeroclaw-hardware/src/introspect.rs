//! Device introspection — correlate serial path with USB device info.

use super::discover;
use super::registry;
use anyhow::Result;

/// Result of introspecting a device by path.
#[derive(Debug, Clone)]
pub struct IntrospectResult {
    pub path: String,
    pub vid: Option<u16>,
    pub pid: Option<u16>,
    pub board_name: Option<String>,
    pub architecture: Option<String>,
    pub memory_map_note: String,
}

/// Introspect a device by its serial path (e.g. /dev/ttyACM0, /dev/tty.usbmodem*).
/// Attempts to correlate with USB devices from discovery.
#[cfg(feature = "hardware")]
pub fn introspect_device(path: &str) -> Result<IntrospectResult> {
    let devices = discover::list_usb_devices()?;

    let matched = if devices.len() == 1 {
        devices.first().cloned()
    } else if devices.is_empty() {
        None
    } else {
        devices
            .iter()
            .find(|d| d.board_name.is_some())
            .cloned()
            .or_else(|| devices.first().cloned())
    };

    let (vid, pid, board_name, architecture) = match matched {
        Some(d) => (Some(d.vid), Some(d.pid), d.board_name, d.architecture),
        None => (None, None, None, None),
    };

    let board_info = vid.and_then(|v| pid.and_then(|p| registry::lookup(v, p)));
    let memory_map_note = board_info
        .and_then(|b| b.architecture)
        .map(|arch| format!("Architecture: {arch}"))
        .unwrap_or_else(|| "Memory map unknown — use `capabilities` tool for device details".to_string());

    Ok(IntrospectResult {
        path: path.to_string(),
        vid,
        pid,
        board_name,
        architecture,
        memory_map_note,
    })
}
