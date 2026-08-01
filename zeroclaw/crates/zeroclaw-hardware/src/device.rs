//! Device types and registry — stable aliases for discovered hardware.
//!
//! The LLM always refers to devices by alias (`"pico0"`, `"arduino0"`), never
//! by raw `/dev/` paths. The `DeviceRegistry` assigns these aliases at startup
//! and provides lookup + context building for tool execution.

use super::transport::Transport;
use std::collections::HashMap;
use std::sync::Arc;

// ── DeviceRuntime ─────────────────────────────────────────────────────────────

/// The software runtime / execution environment of a device.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeviceRuntime {
    MicroPython,
    CircuitPython,
    Arduino,
    Nucleus,
    Linux,
    Aardvark,
}

impl DeviceRuntime {
    pub fn from_kind(kind: &DeviceKind) -> Self {
        match kind {
            DeviceKind::Pico | DeviceKind::Esp32 | DeviceKind::Generic => Self::MicroPython,
            DeviceKind::Arduino => Self::Arduino,
            DeviceKind::Nucleo => Self::Nucleus,
            DeviceKind::Aardvark => Self::Aardvark,
        }
    }
}

impl std::fmt::Display for DeviceRuntime {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::MicroPython => write!(f, "MicroPython"),
            Self::CircuitPython => write!(f, "CircuitPython"),
            Self::Arduino => write!(f, "Arduino"),
            Self::Nucleus => write!(f, "Nucleus/probe-rs"),
            Self::Linux => write!(f, "Linux/SSH"),
            Self::Aardvark => write!(f, "Total Phase Aardvark"),
        }
    }
}

// ── DeviceKind ────────────────────────────────────────────────────────────────

/// The physical kind of the connected device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceKind {
    Pico,
    Esp32,
    Arduino,
    Nucleo,
    Aardvark,
    Generic,
}

impl std::fmt::Display for DeviceKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Pico => write!(f, "Raspberry Pi Pico"),
            Self::Esp32 => write!(f, "ESP32"),
            Self::Arduino => write!(f, "Arduino"),
            Self::Nucleo => write!(f, "STM32 Nucleo"),
            Self::Aardvark => write!(f, "Total Phase Aardvark"),
            Self::Generic => write!(f, "Generic"),
        }
    }
}

// ── DeviceEntry ───────────────────────────────────────────────────────────────

/// A single registered hardware device.
pub struct DeviceEntry {
    /// Stable alias (e.g. `"pico0"`).
    pub alias: String,
    /// Serial port path or adapter identifier.
    pub port: String,
    /// Physical device kind.
    pub kind: DeviceKind,
    /// Execution runtime.
    pub runtime: DeviceRuntime,
    /// Active transport (if opened).
    pub transport: Option<Arc<dyn Transport>>,
    /// Optional board name from USB registry.
    pub board_name: Option<String>,
    /// Optional CPU architecture string.
    pub architecture: Option<String>,
}

impl DeviceEntry {
    pub fn new(
        alias: String,
        port: String,
        kind: DeviceKind,
        board_name: Option<String>,
        architecture: Option<String>,
    ) -> Self {
        let runtime = DeviceRuntime::from_kind(&kind);
        Self {
            alias,
            port,
            kind,
            runtime,
            transport: None,
            board_name,
            architecture,
        }
    }

    pub fn summary(&self) -> String {
        let kind_string = self.kind.to_string();
        let board = self
            .board_name
            .as_deref()
            .unwrap_or(&kind_string);
        let arch = self
            .architecture
            .as_deref()
            .map(|a| format!(" [{a}]"))
            .unwrap_or_default();
        format!("{} @ {} ({}{})", self.alias, self.port, board, arch)
    }
}

// ── DeviceRegistry ────────────────────────────────────────────────────────────

/// Registry of all connected hardware devices, keyed by alias.
#[derive(Default)]
pub struct DeviceRegistry {
    devices: HashMap<String, DeviceEntry>,
}

impl DeviceRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register(&mut self, entry: DeviceEntry) {
        self.devices.insert(entry.alias.clone(), entry);
    }

    pub fn get(&self, alias: &str) -> Option<&DeviceEntry> {
        self.devices.get(alias)
    }

    pub fn get_mut(&mut self, alias: &str) -> Option<&mut DeviceEntry> {
        self.devices.get_mut(alias)
    }

    pub fn all(&self) -> impl Iterator<Item = &DeviceEntry> {
        self.devices.values()
    }

    pub fn is_empty(&self) -> bool {
        self.devices.is_empty()
    }

    pub fn len(&self) -> usize {
        self.devices.len()
    }

    /// Build a human-readable summary for the LLM system prompt.
    pub fn build_summary(&self) -> String {
        if self.devices.is_empty() {
            return String::new();
        }
        let lines: Vec<String> = self.devices.values().map(|d| d.summary()).collect();
        format!("Connected hardware devices:\n{}", lines.join("\n"))
    }

    /// Return a list of all device aliases.
    pub fn aliases(&self) -> Vec<&str> {
        self.devices.keys().map(|s| s.as_str()).collect()
    }
}
