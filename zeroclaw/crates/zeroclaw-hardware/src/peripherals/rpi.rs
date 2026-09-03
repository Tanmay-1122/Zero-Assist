//! Raspberry Pi peripheral tools (Linux only, peripheral-rpi feature).

#![cfg(all(feature = "peripheral-rpi", target_os = "linux"))]

pub use crate::rpi::*;
