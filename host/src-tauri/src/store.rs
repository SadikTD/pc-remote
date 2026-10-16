//! Paired devices, the one-time pairing token and settings, in `store.json`.
//! Device tokens are kept only as SHA-256 hashes.

use crate::tls::{hex, sha256};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub type DeviceId = String;
const OTP_TTL: Duration = Duration::from_secs(120);

pub fn random<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    SystemRandom::new().fill(&mut b).expect("system RNG");
    b
}

pub fn b64url(b: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(b)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub autostart: bool,
    pub display: String,
    pub max_quality: String,
    pub allow_files: bool,
    pub allow_clip: bool,
    pub anywhere: bool,
    pub files_dir: String,
}

impl Default for Config {
    fn default() -> Self {
        let home = std::env::var("USERPROFILE").unwrap_or_else(|_| ".".into());
        Config {
            autostart: true,
            display: String::new(),
            max_quality: "auto".into(),
            allow_files: true,
            allow_clip: true,
            anywhere: false,
            files_dir: Path::new(&home).join("Downloads").join("PC Remote").to_string_lossy().into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: DeviceId,
    pub name: String,
    token_sha256: String,
    #[serde(default)]
    revoked: bool,
}

#[derive(Serialize, Deserialize)]
struct Data {
    pc_id: String,
    #[serde(default)]
    devices: Vec<Device>,
    #[serde(default)]
    config: Config,
    /// docs/remote-protocol.md `relayKey`, base64url; created once.
    #[serde(default)]
    relay_key: String,
    /// The Worker accepted the key (later connections leave out `register`).
    #[serde(default)]
    relay_registered: bool,
}

pub struct Store {
    path: PathBuf,
    data: Data,
    otp: Option<(String, Instant)>,
}

fn uuid4() -> String {
    let mut b = random::<16>();
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h = hex(&b);
    format!("{}-{}-{}-{}-{}", &h[..8], &h[8..12], &h[12..16], &h[16..20], &h[20..])
}

pub fn hmac(key: &[u8], msg: &str) -> [u8; 32] {
    let tag = ring::hmac::sign(&ring::hmac::Key::new(ring::hmac::HMAC_SHA256, key), msg.as_bytes());
    tag.as_ref().try_into().expect("32-byte tag")
}

fn token_hash(token: &str) -> String {
    hex(&sha256(token.as_bytes()))
}

impl Store {
    pub fn open(dir: &Path) -> std::io::Result<Self> {
        let path = dir.join("store.json");
        let data = match std::fs::read(&path) {
            Ok(b) => serde_json::from_slice(&b).map_err(std::io::Error::other)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                Data { pc_id: uuid4(), devices: vec![], config: Config::default(), relay_key: String::new(), relay_registered: false }
            }
            Err(e) => return Err(e),
        };
        let mut s = Store { path, data, otp: None };
        if s.data.relay_key.is_empty() {
            s.data.relay_key = b64url(&random::<32>());
        }
        s.save()?;
        Ok(s)
    }

    fn save(&self) -> std::io::Result<()> {
        std::fs::create_dir_all(self.path.parent().expect("dir"))?;
        let tmp = self.path.with_extension("tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(&self.data)?)?;
        std::fs::rename(tmp, &self.path)
    }

    pub fn pc_id(&self) -> &str {
        &self.data.pc_id
    }

    pub fn new_otp(&mut self) -> String {
        self.new_otp_at(Instant::now())
    }

    /// Issues a fresh 128-bit pairing token, replacing any earlier one.
    pub fn new_otp_at(&mut self, now: Instant) -> String {
        let otp = b64url(&random::<16>());
        self.otp = Some((otp.clone(), now));
        otp
    }

    pub fn take_otp(&mut self, otp: &str) -> bool {
        self.take_otp_at(otp, Instant::now())
    }

    pub fn take_otp_at(&mut self, otp: &str, now: Instant) -> bool {
        match &self.otp {
            Some((cur, at)) if cur == otp && now.duration_since(*at) < OTP_TTL => {
                self.otp = None;
                true
            }
            _ => false,
        }
    }

    pub fn add_device(&mut self, name: &str) -> std::io::Result<(DeviceId, String)> {
        let token = b64url(&random::<32>());
        let id = hex(&random::<8>());
        self.data.devices.push(Device { id: id.clone(), name: name.into(), token_sha256: token_hash(&token), revoked: false });
        self.save()?;
        Ok((id, token))
    }

    /// Ok(device) for a live token, else the protocol error code.
    pub fn auth(&self, token: &str) -> Result<DeviceId, &'static str> {
        let h = token_hash(token);
        match self.data.devices.iter().find(|d| d.token_sha256 == h) {
            Some(d) if d.revoked => Err("revoked"),
            Some(d) => Ok(d.id.clone()),
            None => Err("unauthorized"),
        }
    }

    pub fn revoke(&mut self, id: &str) -> std::io::Result<()> {
        self.data.devices.iter_mut().filter(|d| d.id == id).for_each(|d| d.revoked = true);
        self.save()
    }

    pub fn rename(&mut self, id: &str, name: &str) -> std::io::Result<()> {
        self.data.devices.iter_mut().filter(|d| d.id == id).for_each(|d| d.name = name.into());
        self.save()
    }

    pub fn devices(&self) -> Vec<Device> {
        self.data.devices.iter().filter(|d| !d.revoked).cloned().collect()
    }

    pub fn device_name(&self, id: &str) -> Option<String> {
        self.data.devices.iter().find(|d| d.id == id).map(|d| d.name.clone())
    }

    pub fn relay_key(&self) -> Vec<u8> {
        URL_SAFE_NO_PAD.decode(&self.data.relay_key).unwrap_or_default()
    }

    pub fn relay_registered(&self) -> bool {
        self.data.relay_registered
    }

    pub fn set_relay_registered(&mut self) -> std::io::Result<()> {
        self.data.relay_registered = true;
        self.save()
    }

    /// `ticket(dev) = HMAC-SHA256(relayKey, "ticket:" + pcId + ":" + deviceId)`.
    pub fn ticket(&self, dev: &str) -> [u8; 32] {
        hmac(&self.relay_key(), &format!("ticket:{}:{dev}", self.data.pc_id))
    }

    /// Ids of removed devices (the Worker is told again on every connection).
    pub fn revoked(&self) -> Vec<DeviceId> {
        self.data.devices.iter().filter(|d| d.revoked).map(|d| d.id.clone()).collect()
    }

    pub fn config(&self) -> &Config {
        &self.data.config
    }

    pub fn set_config(&mut self, c: Config) -> std::io::Result<()> {
        self.data.config = c;
        self.save()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    fn tmp(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("pcr-store-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn otp_single_use_and_expiry() {
        let mut s = Store::open(&tmp("otp")).unwrap();
        let t0 = Instant::now();
        let otp = s.new_otp_at(t0);
        assert_eq!(otp.len(), 22);
        assert!(otp.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'));
        assert!(!s.take_otp_at("wrong", t0));
        assert!(s.take_otp_at(&otp, t0 + Duration::from_secs(119)));
        assert!(!s.take_otp_at(&otp, t0 + Duration::from_secs(119)), "single use");

        let otp2 = s.new_otp_at(t0);
        assert_ne!(otp, otp2);
        assert!(!s.take_otp_at(&otp2, t0 + Duration::from_secs(121)), "expired");

        let a = s.new_otp_at(t0);
        let b = s.new_otp_at(t0);
        assert!(!s.take_otp_at(&a, t0), "replaced by a newer one");
        assert!(s.take_otp_at(&b, t0));
    }

    #[test]
    fn auth_after_add_and_fails_after_revoke() {
        let mut s = Store::open(&tmp("auth")).unwrap();
        let (id, token) = s.add_device("Pixel 8").unwrap();
        assert_eq!(token.len(), 43);
        assert_eq!(s.auth(&token), Ok(id.clone()));
        assert_eq!(s.auth("nope"), Err("unauthorized"));
        assert_eq!(s.devices().len(), 1);
        assert_eq!(s.devices()[0].name, "Pixel 8");
        s.revoke(&id).unwrap();
        assert_eq!(s.auth(&token), Err("revoked"));
        assert!(s.devices().is_empty());
    }

    #[test]
    fn rename_device() {
        let mut s = Store::open(&tmp("rename")).unwrap();
        let (id, _) = s.add_device("a").unwrap();
        s.rename(&id, "Living room").unwrap();
        assert_eq!(s.devices()[0].name, "Living room");
    }

    #[test]
    fn persists_across_reopen_without_raw_token() {
        let d = tmp("persist");
        let (id, token, pc_id) = {
            let mut s = Store::open(&d).unwrap();
            let (id, token) = s.add_device("Pixel 8").unwrap();
            let mut cfg = s.config().clone();
            cfg.allow_clip = false;
            cfg.display = "\\\\.\\DISPLAY2".into();
            s.set_config(cfg).unwrap();
            (id, token, s.pc_id().to_string())
        };
        let s = Store::open(&d).unwrap();
        assert_eq!(s.auth(&token), Ok(id));
        assert!(!s.config().allow_clip);
        assert_eq!(s.config().display, "\\\\.\\DISPLAY2");
        assert_eq!(s.pc_id(), pc_id);
        assert_eq!(pc_id.len(), 36);
        let bytes = std::fs::read(d.join("store.json")).unwrap();
        let text = String::from_utf8(bytes).unwrap();
        assert!(!text.contains(&token));
    }

    #[test]
    fn relay_key_persists_and_ticket_matches_hmac() {
        let d = tmp("relay");
        let (key, pc) = {
            let mut s = Store::open(&d).unwrap();
            assert!(!s.relay_registered());
            s.set_relay_registered().unwrap();
            (s.relay_key(), s.pc_id().to_string())
        };
        let s = Store::open(&d).unwrap();
        assert_eq!(key.len(), 32);
        assert_eq!(s.relay_key(), key, "created once");
        assert!(s.relay_registered());
        assert_eq!(s.ticket("dev1"), hmac(&key, &format!("ticket:{pc}:dev1")));
        // RFC 4231 test case 2
        assert_eq!(
            hex(&hmac(b"Jefe", "what do ya want for nothing?")),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
    }

    #[test]
    fn default_config() {
        let s = Store::open(&tmp("defaults")).unwrap();
        let c = s.config();
        assert!(c.autostart && c.allow_files && c.allow_clip && !c.anywhere);
        assert!(c.files_dir.ends_with("PC Remote"));
    }
}
