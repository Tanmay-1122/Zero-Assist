//! Transport trait — decouples hardware tools from wire protocol.
//!
//! Implementations:
//! - `serial::HardwareSerialTransport` — lazy-open newline-delimited JSON over USB CDC
//! - `SWDTransport` — memory read/write via probe-rs
//! - `UF2Transport` — firmware flashing via UF2 mass storage
//! - `NativeTransport` — direct Linux GPIO/I2C/SPI via rppal/sysfs

use super::protocol::{ZcCommand, ZcResponse};
use async_trait::async_trait;
use thiserror::Error;

/// Transport layer error.
#[derive(Debug, Error)]
pub enum TransportError {
    /// Operation timed out.
    #[error("transport timeout after {0}s")]
    Timeout(u64),

    /// Transport is disconnected or device was removed.
    #[error("transport disconnected")]
    Disconnected,

    /// Protocol-level error (malformed JSON, id mismatch, etc.).
    #[error("protocol error: {0}")]
    Protocol(String),

    /// Underlying I/O error.
    #[error("transport I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Catch-all for transport-specific errors.
    #[error("{0}")]
    Other(String),
}

/// Transport kind discriminator.
///
/// Used for capability matching — some tools require a specific transport
/// (e.g. `pico_flash` requires UF2, `memory_read` prefers SWD).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TransportKind {
    /// Newline-delimited JSON over USB CDC serial.
    Serial,
    /// UF2 mass-storage bootloader (Pico, etc.).
    Uf2,
    /// SWD/JTAG debug probe (probe-rs).
    Swd,
    /// Native Linux GPIO/I2C/SPI (rppal, sysfs).
    Native,
    /// Aardvark I2C/SPI/GPIO USB adapter.
    Aardvark,
}

/// Abstract hardware transport.
#[async_trait]
pub trait Transport: Send + Sync {
    /// Send a command and receive a response.
    async fn send(&self, cmd: ZcCommand) -> Result<ZcResponse, TransportError>;

    /// The kind of this transport.
    fn kind(&self) -> TransportKind;

    /// Whether the transport is currently connected.
    fn is_connected(&self) -> bool;
}
