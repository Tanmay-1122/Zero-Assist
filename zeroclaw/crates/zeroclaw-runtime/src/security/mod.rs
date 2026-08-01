pub mod audit;
pub mod detect;
pub mod domain_matcher;
pub mod estop;
pub mod iam_policy;
pub mod leak_detector;
pub mod nevis;
pub mod otp;
pub mod pairing;
pub mod playbook;
pub mod policy;
pub mod prompt_guard;
pub mod secrets;
pub mod vulnerability;
#[cfg(feature = "webauthn")]
pub mod webauthn;
pub mod workspace_boundary;

#[allow(unused_imports)]
pub use audit::{AuditEvent, AuditEventType, AuditLogger};
#[allow(unused_imports)]
pub use detect::linux_memcg_available;
pub use domain_matcher::DomainMatcher;
#[allow(unused_imports)]
pub use estop::{EstopLevel, EstopManager, EstopState, ResumeSelector};
#[allow(unused_imports)]
pub use iam_policy::{IamPolicy, PolicyDecision};
#[allow(unused_imports)]
pub use nevis::{NevisAuthProvider, NevisIdentity};
#[allow(unused_imports)]
pub use otp::OtpValidator;
#[allow(unused_imports)]
pub use pairing::PairingGuard;
pub use policy::{AutonomyLevel, SecurityPolicy};
#[allow(unused_imports)]
pub use secrets::SecretStore;
#[allow(unused_imports)]
pub use leak_detector::{LeakDetector, LeakResult};
#[allow(unused_imports)]
pub use prompt_guard::{GuardAction, GuardResult, PromptGuard};
#[allow(unused_imports)]
pub use workspace_boundary::{BoundaryVerdict, WorkspaceBoundary};

pub fn redact(value: &str) -> String {
    let char_count = value.chars().count();
    if char_count <= 4 {
        "***".to_string()
    } else {
        let prefix: String = value.chars().take(4).collect();
        format!("{prefix}***")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reexported_policy_and_pairing_types_are_usable() {
        let policy = SecurityPolicy::default();
        assert_eq!(policy.autonomy, AutonomyLevel::Supervised);

        let guard = PairingGuard::new(false, &[]);
        assert!(!guard.require_pairing());
    }

    #[test]
    fn reexported_secret_store_encrypt_decrypt_roundtrip() {
        let temp = tempfile::tempdir().unwrap();
        let store = SecretStore::new(temp.path(), false);

        let encrypted = store.encrypt("top-secret").unwrap();
        let decrypted = store.decrypt(&encrypted).unwrap();

        assert_eq!(decrypted, "top-secret");
    }

    #[test]
    fn redact_hides_most_of_value() {
        assert_eq!(redact("abcdefgh"), "abcd***");
        assert_eq!(redact("ab"), "***");
        assert_eq!(redact(""), "***");
        assert_eq!(redact("12345"), "1234***");
    }

    #[test]
    fn redact_handles_multibyte_utf8_without_panic() {
        let result = redact("密码是很长的秘密");
        assert!(result.ends_with("***"));
        assert!(result.is_char_boundary(result.len()));
    }
}
