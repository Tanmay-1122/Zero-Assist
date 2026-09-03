//! USB device discovery — enumerate devices and enrich with board registry.
//!
//! USB enumeration is only supported on Linux, macOS, and Windows.
//! On Android and other unsupported platforms this module is excluded.

#![cfg(any(target_os = "linux", target_os = "macos", target_os = "windows"))]

use super::registry;
use anyhow::Result;

/// Serial port with USB VID/PID for device registration.
#[derive(Debug, Clone)]
pub struct SerialDeviceInfo {
    pub port_path: String,
    pub vid: u16,
    pub pid: u16,
    pub board_name: Option<String>,
    pub architecture: Option<String>,
}

/// Enumerate all connected USB serial devices (stub when `hardware` feature is off).
#[cfg(not(feature = "hardware"))]
pub fn scan_serial_devices() -> Vec<SerialDeviceInfo> {
    Vec::new()
}

/// Enumerate connected USB devices (returns VID/PID list).
pub fn list_usb_devices() -> Result<Vec<SerialDeviceInfo>> {
    Ok(scan_serial_devices())
}

/// Enumerate serial ports that correspond to known USB devices.
#[cfg(feature = "hardware")]
pub fn scan_serial_devices() -> Vec<SerialDeviceInfo> {
    let mut result = Vec::new();

    // Use tokio_serial to enumerate available ports
    let ports = match tokio_serial::available_ports() {
        Ok(p) => p,
        Err(_) => return result,
    };

    for port in ports {
        let port_name = port.port_name.as_str();
        if !crate::util::is_serial_path_allowed(port_name) {
            continue;
        }

        let (vid, pid) = match &port.port_type {
            tokio_serial::SerialPortType::UsbPort(info) => (info.vid, info.pid),
            _ => continue,
        };

        let board = registry::lookup(vid, pid);
        result.push(SerialDeviceInfo {
            port_path: port_name.to_string(),
            vid,
            pid,
            board_name: board.map(|b| b.name.to_string()),
            architecture: board.and_then(|b| b.architecture).map(|a| a.to_string()),
        });
    }

    result
}
