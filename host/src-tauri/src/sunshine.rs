//! Sunshine's local REST API (https://localhost:47990) with hidden admin credentials.

use crate::store::{b64url, random};
use crate::win::dpapi;
use serde_json::{json, Map, Value};
use std::os::windows::process::CommandExt;
use std::path::Path;
use std::process::Command;
use std::time::{Duration, Instant};

pub const CREDS_FILE: &str = "sunshine.cred";
pub const SUNSHINE_EXE: &str = r"C:\Program Files\Sunshine\sunshine.exe";
const SERVICE: &str = "SunshineService";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, PartialEq)]
pub enum SunshineError {
    PinTimeout,
    Http(String),
}

#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct SunshineStatus {
    pub running: bool,
    /// ponytail: always false; Sunshine has no stream-state API. Track C reads it from the log/session events.
    pub streaming: bool,
}

pub struct Sunshine {
    base: String,
    http: reqwest::Client,
    creds: Option<(String, String)>,
    pub retry: Duration,
    pub pin_timeout: Duration,
}

pub fn load_creds(dir: &Path) -> Option<(String, String)> {
    let plain = dpapi::unprotect(&std::fs::read(dir.join(CREDS_FILE)).ok()?).ok()?;
    let s = String::from_utf8(plain).ok()?;
    let (u, p) = s.split_once('\n')?;
    Some((u.into(), p.into()))
}

pub fn save_creds(dir: &Path, user: &str, pass: &str) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let blob = dpapi::protect(format!("{user}\n{pass}").as_bytes()).map_err(std::io::Error::other)?;
    std::fs::write(dir.join(CREDS_FILE), blob)
}

fn run(exe: &str, args: &[&str]) -> std::io::Result<bool> {
    Ok(Command::new(exe).args(args).creation_flags(CREATE_NO_WINDOW).status()?.success())
}

/// Starts the Sunshine service. Needs admin, or the start right the installer grants to interactive users.
pub fn start_service() -> std::io::Result<bool> {
    run("net", &["start", SERVICE])
}

pub fn restart_service() -> std::io::Result<bool> {
    let _ = run("net", &["stop", SERVICE]);
    start_service()
}

/// First run: creates random Sunshine admin credentials the user never sees.
/// Needs admin (Sunshine's state lives under Program Files); the installer runs it
/// elevated via `--init-sunshine`.
pub fn ensure_credentials(dir: &Path) -> std::io::Result<(String, String)> {
    if let Some(c) = load_creds(dir) {
        return Ok(c);
    }
    let (user, pass) = ("pcremote".to_string(), b64url(&random::<18>()));
    if !run(SUNSHINE_EXE, &["--creds", &user, &pass])? {
        return Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "sunshine --creds failed"));
    }
    restart_service()?;
    save_creds(dir, &user, &pass)?;
    Ok((user, pass))
}

impl Sunshine {
    pub fn local(creds: Option<(String, String)>) -> Self {
        Self::new("https://localhost:47990", creds)
    }

    pub fn new(base: &str, creds: Option<(String, String)>) -> Self {
        let _ = tokio_rustls::rustls::crypto::ring::default_provider().install_default();
        let http = reqwest::Client::builder()
            // Sunshine serves a self-signed cert; this client only ever talks to localhost.
            .tls_danger_accept_invalid_certs(true)
            .timeout(Duration::from_secs(3))
            .build()
            .expect("http client");
        Sunshine { base: base.trim_end_matches('/').into(), http, creds, retry: Duration::from_millis(500), pin_timeout: Duration::from_secs(30) }
    }

    fn req(&self, m: reqwest::Method, p: &str) -> reqwest::RequestBuilder {
        let r = self.http.request(m, format!("{}{p}", self.base));
        match &self.creds {
            Some((u, pw)) => r.basic_auth(u, Some(pw)),
            None => r,
        }
    }

    /// POSTs the PIN until Sunshine has a pending Moonlight pair request that takes it.
    pub async fn submit_pin(&self, pin: &str, name: &str) -> Result<(), SunshineError> {
        let deadline = Instant::now() + self.pin_timeout;
        loop {
            let r = self.req(reqwest::Method::POST, "/api/pin").json(&json!({"pin": pin, "name": name})).send().await;
            if let Ok(r) = r {
                if r.status().is_success() {
                    let v: Value = r.json().await.unwrap_or_default();
                    if v["status"] == true || v["status"] == "true" {
                        return Ok(());
                    }
                }
            }
            if Instant::now() >= deadline {
                return Err(SunshineError::PinTimeout);
            }
            tokio::time::sleep(self.retry).await;
        }
    }

    pub async fn status(&self) -> SunshineStatus {
        let r = self.req(reqwest::Method::GET, "/api/config").timeout(Duration::from_secs(1)).send().await;
        SunshineStatus { running: r.is_ok(), streaming: false }
    }

    /// Sets one key (`output_name`, `upnp`, …). Sunshine's POST replaces the whole
    /// file, so this merges into the current config, then applies it with a restart.
    pub async fn set_config(&self, key: &str, value: &str) -> Result<(), SunshineError> {
        let e = |e: reqwest::Error| SunshineError::Http(e.to_string());
        let cur: Map<String, Value> = self.req(reqwest::Method::GET, "/api/config").send().await.map_err(e)?
            .error_for_status().map_err(e)?.json().await.map_err(e)?;
        let mut cfg: Map<String, Value> = cur
            .into_iter()
            .filter(|(k, v)| v.is_string() && k != "platform" && k != "version")
            .collect();
        cfg.insert(key.into(), value.into());
        self.req(reqwest::Method::POST, "/api/config").json(&cfg).send().await.map_err(e)?
            .error_for_status().map_err(e)?;
        let _ = self.req(reqwest::Method::POST, "/api/restart").send().await;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::time::Duration;
    use wiremock::matchers::{basic_auth, body_json, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn client(base: &str) -> Sunshine {
        let mut s = Sunshine::new(base, Some(("u".into(), "p".into())));
        s.retry = Duration::from_millis(20);
        s.pin_timeout = Duration::from_millis(400);
        s
    }

    #[tokio::test]
    async fn submit_pin_retries_until_accepted() {
        let srv = MockServer::start().await;
        let body = json!({"pin":"4821","name":"Pixel 8"});
        Mock::given(method("POST")).and(path("/api/pin")).and(body_json(&body))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status":false})))
            .up_to_n_times(2).mount(&srv).await;
        Mock::given(method("POST")).and(path("/api/pin")).and(basic_auth("u", "p"))
            .and(header("content-type", "application/json")).and(body_json(&body))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status":true})))
            .expect(1).mount(&srv).await;
        assert_eq!(client(&srv.uri()).submit_pin("4821", "Pixel 8").await, Ok(()));
        assert_eq!(srv.received_requests().await.unwrap().len(), 3);
    }

    #[tokio::test]
    async fn submit_pin_times_out() {
        let srv = MockServer::start().await;
        Mock::given(method("POST")).and(path("/api/pin"))
            .respond_with(ResponseTemplate::new(400).set_body_json(json!({"status":false})))
            .mount(&srv).await;
        let t0 = std::time::Instant::now();
        assert_eq!(client(&srv.uri()).submit_pin("1", "x").await, Err(SunshineError::PinTimeout));
        assert!(t0.elapsed() >= Duration::from_millis(400));
        assert!(srv.received_requests().await.unwrap().len() >= 3);
    }

    #[tokio::test]
    async fn status_running_when_reachable() {
        let srv = MockServer::start().await;
        Mock::given(method("GET")).and(path("/api/config"))
            .respond_with(ResponseTemplate::new(401)).mount(&srv).await;
        assert!(client(&srv.uri()).status().await.running);
    }

    #[tokio::test]
    async fn status_false_when_unreachable() {
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let st = client(&format!("http://127.0.0.1:{port}")).status().await;
        assert_eq!(st, SunshineStatus { running: false, streaming: false });
    }

    #[tokio::test]
    async fn set_config_posts_merged_config() {
        let srv = MockServer::start().await;
        Mock::given(method("GET")).and(path("/api/config")).and(basic_auth("u", "p"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "status": true, "platform": "windows", "version": "v1", "vdisplayStatus": 0,
                "upnp": "disabled", "min_log_level": "2"
            })))
            .mount(&srv).await;
        Mock::given(method("POST")).and(path("/api/config")).and(basic_auth("u", "p"))
            .and(body_json(json!({"upnp":"enabled","min_log_level":"2"})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status":true})))
            .expect(1).mount(&srv).await;
        Mock::given(method("POST")).and(path("/api/restart"))
            .respond_with(ResponseTemplate::new(200)).expect(1).mount(&srv).await;
        assert_eq!(client(&srv.uri()).set_config("upnp", "enabled").await, Ok(()));
    }

    #[test]
    fn creds_round_trip_through_dpapi_file() {
        let dir = std::env::temp_dir().join(format!("pcr-sun-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        assert_eq!(load_creds(&dir), None);
        save_creds(&dir, "pcremote", "s3cret").unwrap();
        assert_eq!(load_creds(&dir), Some(("pcremote".into(), "s3cret".into())));
        assert!(!std::fs::read(dir.join(CREDS_FILE)).unwrap().windows(6).any(|w| w == b"s3cret"));
    }
}
