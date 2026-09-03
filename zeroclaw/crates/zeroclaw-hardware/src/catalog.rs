//! Canonical hardware tool-name and capability catalog.
//!
//! Single source of truth for the names the agent sees and the docs render.
//! `fn name()` impls and the mdBook hardware snippets both read these constants
//! so a rename lands in one place and the rendered tables follow on the next
//! docs build. Non-gated so xtask can walk it without the `hardware` feature.

/// Built-in hardware tools always present with the `hardware` feature.
pub const BASE_TOOLS: &[&str] = &[
    "gpio_read",
    "gpio_write",
    "pico_flash",
    "device_read_code",
    "device_write_code",
    "device_exec",
];

/// Tools loaded only when at least one Aardvark adapter is present at boot.
pub const AARDVARK_TOOLS: &[&str] = &[
    "i2c_scan",
    "i2c_read",
    "i2c_write",
    "spi_transfer",
    "gpio_aardvark",
    "datasheet",
];

/// All tool names shipped with the hardware crate (base + aardvark).
pub const ALL_TOOLS: &[&str] = &[
    "gpio_read",
    "gpio_write",
    "pico_flash",
    "device_read_code",
    "device_write_code",
    "device_exec",
    "i2c_scan",
    "i2c_read",
    "i2c_write",
    "spi_transfer",
    "gpio_aardvark",
    "datasheet",
];
