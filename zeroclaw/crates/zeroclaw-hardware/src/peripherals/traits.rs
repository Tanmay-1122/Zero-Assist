//! Peripheral device traits.

use async_trait::async_trait;

/// Common trait for all peripheral device implementations.
#[async_trait]
pub trait Peripheral: Send + Sync {
    /// Human-readable name of the peripheral.
    fn name(&self) -> &str;
    /// Initialize or reset the peripheral.
    async fn init(&mut self) -> anyhow::Result<()>;
}
