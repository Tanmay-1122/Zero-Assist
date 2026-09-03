//! Channel announcement delivery for daemon components (heartbeat alerts).
//!
//! The binary crate injects a delivery function at startup via
//! [`register_delivery_fn`]; daemon workers call [`deliver_announcement`],
//! which routes through the injected handler when one is registered.

use anyhow::Result;
use zeroclaw_config::schema::Config;

/// Delivery function type — takes owned values so the returned future is 'static.
pub type DeliveryFn = Box<
    dyn Fn(
            Config,
            String,
            String,
            String,
        ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<()>> + Send>>
        + Send
        + Sync,
>;

/// Global delivery function, injected by the binary crate at startup.
static DELIVERY_FN: std::sync::OnceLock<DeliveryFn> = std::sync::OnceLock::new();

/// Register the channel delivery function. Called once at startup by the binary.
pub fn register_delivery_fn(f: DeliveryFn) {
    let _ = DELIVERY_FN.set(f);
}

/// Deliver an announcement to a configured channel via the injected handler.
pub async fn deliver_announcement(
    config: &Config,
    channel: &str,
    target: &str,
    output: &str,
) -> Result<()> {
    if let Some(f) = DELIVERY_FN.get() {
        f(
            config.clone(),
            channel.to_string(),
            target.to_string(),
            output.to_string(),
        )
        .await
    } else {
        tracing::warn!(
            channel = %channel,
            target = %target,
            "Delivery skipped: no delivery handler registered \
             (register_delivery_fn was not called by the binary)"
        );
        Ok(())
    }
}
