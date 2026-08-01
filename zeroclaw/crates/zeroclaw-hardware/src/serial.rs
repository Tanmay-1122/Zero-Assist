//! Serial transport — lazy-open newline-delimited JSON over USB CDC.

#![cfg(feature = "hardware")]

use super::protocol::{ZcCommand, ZcResponse};
use super::transport::{Transport, TransportError, TransportKind};
use async_trait::async_trait;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::Mutex;
use tokio_serial::SerialStream;

const DEFAULT_BAUD_RATE: u32 = 115_200;
const DEFAULT_TIMEOUT_MS: u64 = 5_000;

/// Lazy-opening serial transport for USB CDC devices.
pub struct HardwareSerialTransport {
    port_path: String,
    baud_rate: u32,
    timeout_ms: u64,
    inner: Arc<Mutex<Option<SerialStream>>>,
}

impl HardwareSerialTransport {
    pub fn new(port_path: String) -> Self {
        Self {
            port_path,
            baud_rate: DEFAULT_BAUD_RATE,
            timeout_ms: DEFAULT_TIMEOUT_MS,
            inner: Arc::new(Mutex::new(None)),
        }
    }

    pub fn with_baud_rate(mut self, baud: u32) -> Self {
        self.baud_rate = baud;
        self
    }

    pub fn with_timeout_ms(mut self, ms: u64) -> Self {
        self.timeout_ms = ms;
        self
    }

    async fn open(&self) -> Result<SerialStream, TransportError> {
        let builder = tokio_serial::new(&self.port_path, self.baud_rate);
        SerialStream::open(&builder).map_err(|e| TransportError::Other(e.to_string()))
    }
}

#[async_trait]
impl Transport for HardwareSerialTransport {
    async fn send(&self, cmd: ZcCommand) -> Result<ZcResponse, TransportError> {
        let mut line = serde_json::to_string(&cmd)
            .map_err(|e| TransportError::Protocol(e.to_string()))?;
        line.push('\n');

        let timeout = Duration::from_millis(self.timeout_ms);

        tokio::time::timeout(timeout, async {
            let stream = self.open().await?;
            let (reader, mut writer) = tokio::io::split(stream);
            writer
                .write_all(line.as_bytes())
                .await
                .map_err(TransportError::Io)?;
            writer.flush().await.map_err(TransportError::Io)?;

            let mut buf_reader = BufReader::new(reader);
            let mut response_line = String::new();
            buf_reader
                .read_line(&mut response_line)
                .await
                .map_err(TransportError::Io)?;

            serde_json::from_str(&response_line)
                .map_err(|e| TransportError::Protocol(e.to_string()))
        })
        .await
        .map_err(|_| TransportError::Timeout(self.timeout_ms / 1_000))?
    }

    fn kind(&self) -> TransportKind {
        TransportKind::Serial
    }

    fn is_connected(&self) -> bool {
        std::path::Path::new(&self.port_path).exists()
    }
}
