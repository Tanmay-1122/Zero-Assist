//! Aardvark USB adapter transport stub (I2C / SPI / GPIO).
//! Full implementation requires `aardvark-sys` (not available in this workspace build).
//! This module exposes the type signature so gateway can compile.

#![cfg(feature = "hardware")]

use super::protocol::{ZcCommand, ZcResponse};
use super::transport::{Transport, TransportError, TransportKind};
use async_trait::async_trait;

/// Aardvark I2C/SPI/GPIO transport.
/// When `aardvark-sys` is unavailable this is a stub that returns an error.
pub struct AardvarkTransport {
    port: u16,
}

impl AardvarkTransport {
    /// Open the first available Aardvark adapter (port 0 = auto-detect).
    pub fn open(port: u16) -> anyhow::Result<Self> {
        // Without aardvark-sys, we cannot open hardware.
        let _ = port;
        anyhow::bail!(
            "Aardvark support requires the `aardvark-sys` crate, \
             which is not included in this workspace build. \
             Connect a Total Phase Aardvark adapter and rebuild with the \
             upstream `hardware` feature for full support."
        )
    }
}

#[async_trait]
impl Transport for AardvarkTransport {
    async fn send(&self, _cmd: ZcCommand) -> Result<ZcResponse, TransportError> {
        Err(TransportError::Other(
            "Aardvark transport not available in this build".to_string(),
        ))
    }

    fn kind(&self) -> TransportKind {
        TransportKind::Aardvark
    }

    fn is_connected(&self) -> bool {
        false
    }
}
