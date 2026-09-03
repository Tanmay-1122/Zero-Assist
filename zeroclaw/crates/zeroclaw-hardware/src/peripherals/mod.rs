//! Peripherals module — hardware peripheral tools and implementations.

pub mod traits;
pub mod capabilities_tool;
pub mod arduino_flash;
pub mod arduino_upload;
pub mod nucleo_flash;
pub mod smartroom;
pub mod uno_q_bridge;
pub mod uno_q_setup;

#[cfg(feature = "hardware")]
pub mod serial;

#[cfg(all(feature = "peripheral-rpi", target_os = "linux"))]
pub mod rpi;

pub use traits::Peripheral;

