//! Persistent self-signed TLS identity (ECDSA P-256, 10 years), pinned by the phone.

use crate::win::dpapi;
use std::path::Path;

#[derive(Clone)]
pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    pub fingerprint_hex: String,
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn sha256(b: &[u8]) -> Vec<u8> {
    ring::digest::digest(&ring::digest::SHA256, b).as_ref().to_vec()
}

impl Identity {
    /// Loads `tls.cer` + DPAPI-wrapped `tls.key` from `dir`, creating them on first run.
    pub fn load_or_create(dir: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        let (cert_p, key_p) = (dir.join("tls.cer"), dir.join("tls.key"));
        let (cert_der, key_der) = match (std::fs::read(&cert_p), std::fs::read(&key_p)) {
            (Ok(c), Ok(k)) => (c, dpapi::unprotect(&k)?),
            _ => {
                let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256)?;
                let mut p = rcgen::CertificateParams::new(vec!["pc-remote.local".to_string()])?;
                let year = 1970 + (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs() / 31_556_952) as i32;
                p.not_before = rcgen::date_time_ymd(year - 1, 1, 1);
                p.not_after = rcgen::date_time_ymd(year + 10, 1, 1);
                p.distinguished_name.push(rcgen::DnType::CommonName, "PC Remote Host");
                let cert = p.self_signed(&key)?;
                let (c, k) = (cert.der().to_vec(), key.serialize_der());
                std::fs::create_dir_all(dir)?;
                std::fs::write(&key_p, dpapi::protect(&k)?)?;
                std::fs::write(&cert_p, &c)?;
                (c, k)
            }
        };
        let fingerprint_hex = hex(&sha256(&cert_der));
        Ok(Identity { cert_der, key_der, fingerprint_hex })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("pcr-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn create_then_load_same_fingerprint() {
        let d = tmp("tls");
        let a = Identity::load_or_create(&d).unwrap();
        let b = Identity::load_or_create(&d).unwrap();
        assert_eq!(a.fingerprint_hex, b.fingerprint_hex);
        assert_eq!(a.cert_der, b.cert_der);
        assert_eq!(a.key_der, b.key_der);
        // the private key is not on disk in the clear
        for e in std::fs::read_dir(&d).unwrap() {
            let bytes = std::fs::read(e.unwrap().path()).unwrap();
            assert!(!bytes.windows(a.key_der.len()).any(|w| w == &a.key_der[..]));
        }
    }

    #[test]
    fn fingerprint_is_sha256_lowercase_hex() {
        let d = tmp("fp");
        let id = Identity::load_or_create(&d).unwrap();
        assert_eq!(id.fingerprint_hex.len(), 64);
        assert!(id.fingerprint_hex.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)));
        let digest = ring::digest::digest(&ring::digest::SHA256, &id.cert_der);
        assert_eq!(id.fingerprint_hex, hex(digest.as_ref()));
    }
}
