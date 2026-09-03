//! Board registry — maps USB VID/PID to known board names and architectures.

/// Information about a known board.
#[derive(Debug, Clone)]
pub struct BoardInfo {
    pub vid: u16,
    pub pid: u16,
    pub name: &'static str,
    pub architecture: Option<&'static str>,
}

/// Known USB VID/PID to board mappings.
const KNOWN_BOARDS: &[BoardInfo] = &[
    BoardInfo { vid: 0x0483, pid: 0x374b, name: "nucleo-f401re", architecture: Some("ARM Cortex-M4") },
    BoardInfo { vid: 0x0483, pid: 0x3748, name: "nucleo-f411re", architecture: Some("ARM Cortex-M4") },
    BoardInfo { vid: 0x2341, pid: 0x0043, name: "arduino-uno",   architecture: Some("AVR ATmega328P") },
    BoardInfo { vid: 0x2341, pid: 0x0078, name: "arduino-uno",   architecture: Some("Arduino Uno Q / ATmega328P") },
    BoardInfo { vid: 0x2341, pid: 0x0042, name: "arduino-mega",  architecture: Some("AVR ATmega2560") },
    BoardInfo { vid: 0x10c4, pid: 0xea60, name: "cp2102",        architecture: None },
    // Raspberry Pi Pico (RP2040) in normal mode
    BoardInfo { vid: 0x2e8a, pid: 0x0005, name: "rpi-pico",      architecture: Some("ARM Cortex-M0+") },
    // Raspberry Pi Pico in BOOTSEL / UF2 mass-storage mode
    BoardInfo { vid: 0x2e8a, pid: 0x0003, name: "rpi-pico-bootsel", architecture: Some("ARM Cortex-M0+") },
    // Total Phase Aardvark I2C/SPI Host Adapter
    BoardInfo { vid: 0x0403, pid: 0xaa01, name: "total-phase-aardvark", architecture: None },
];

/// Look up a board by its USB VID/PID.
pub fn lookup(vid: u16, pid: u16) -> Option<&'static BoardInfo> {
    KNOWN_BOARDS.iter().find(|b| b.vid == vid && b.pid == pid)
}

/// Determine whether a VID/PID corresponds to a Pico in BOOTSEL mode.
pub fn is_pico_bootsel(vid: u16, pid: u16) -> bool {
    vid == 0x2e8a && pid == 0x0003
}
