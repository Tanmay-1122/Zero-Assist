/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Native readiness contract for local Piper phonemization.

/// Native Piper/eSpeak phonemizer availability exposed over UniFFI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PiperPhonemizerStatus {
    /// Whether this native build can phonemize Piper `phoneme_type = espeak` voices.
    pub available: bool,
    /// The native engine implementation name.
    pub engine: String,
    /// Human-readable readiness detail for callers and diagnostics.
    pub detail: String,
}

/// Returns the native Piper/eSpeak phonemizer readiness for this build.
pub(crate) fn get_piper_phonemizer_status_inner() -> PiperPhonemizerStatus {
    PiperPhonemizerStatus {
        available: false,
        engine: "none".to_string(),
        detail: "Native eSpeak/piper-phonemize is not bundled in this build.".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_fails_closed_until_native_phonemizer_is_bundled() {
        let status = get_piper_phonemizer_status_inner();

        assert!(!status.available);
        assert_eq!(status.engine, "none");
        assert!(status.detail.contains("not bundled"));
    }
}
