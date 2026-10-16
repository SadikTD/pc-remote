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
pub const STATE_FILE: &str = r"C:\Program Files\Sunshine\config\sunshine_state.json";

/// The admin username Sunshine actually has (from its state file).
pub fn state_username(state_json: &str) -> Option<String> {
    serde_json::from_str::<Value>(state_json).ok()?.get("username")?.as_str().map(str::to_string)
}

/// True when our saved login isn't the one Sunshine has, e.g. `--creds` silently failed to write
/// (it exits 0 even then) or the user changed it in Sunshine's web page.
pub fn creds_stale(saved_user: &str, state_json: Option<&str>) -> bool {
    state_json.is_some_and(|s| state_username(s).as_deref() != Some(saved_user))
}

/// From `GET /api/pin`: None = no request pending yet; Some(Some(id)) = the newest pending
/// request (Sunshine 2026+ needs its id); Some(None) = older Sunshine without pairing ids.
pub fn pending_pairing(v: &Value) -> Option<Option<String>> {
    match v.get("pairings").and_then(Value::as_array) {
        Some(list) => list.last().and_then(|p| p["id"].as_str()).map(|id| Some(id.to_string())),
        None => Some(None),
    }
}

fn read_state() -> Option<String> {
    std::fs::read_to_string(STATE_FILE).ok()
}
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
    creds: std::sync::RwLock<Option<(String, String)>>,
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
        if !creds_stale(&c.0, read_state().as_deref()) {
            return Ok(c);
        }
    }
    let (user, pass) = ("pcremote".to_string(), b64url(&random::<18>()));
    let ok = run(SUNSHINE_EXE, &["--creds", &user, &pass])?;
    // `--creds` exits 0 even when it can't write the state file, so check what Sunshine has now
    if !ok || creds_stale(&user, Some(read_state().unwrap_or_default().as_str())) {
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
        Sunshine { base: base.trim_end_matches('/').into(), http, creds: creds.into(), retry: Duration::from_millis(500), pin_timeout: Duration::from_secs(30) }
    }

    /// Credentials can arrive after startup (the elevated first-run fallback).
    pub fn set_creds(&self, c: (String, String)) {
        *self.creds.write().unwrap() = Some(c);
    }

    fn req(&self, m: reqwest::Method, p: &str) -> reqwest::RequestBuilder {
        let r = self.http.request(m, format!("{}{p}", self.base));
        match &*self.creds.read().unwrap() {
            Some((u, pw)) => r.basic_auth(u, Some(pw)),
            None => r,
        }
    }

    /// POSTs the PIN until Sunshine has a pending Moonlight pair request that takes it.
    pub async fn submit_pin(&self, pin: &str, name: &str) -> Result<(), SunshineError> {
        let deadline = Instant::now() + self.pin_timeout;
        loop {
            // Newer Sunshine lists pending Moonlight requests and wants the PIN tied to one of them
            let pending = match self.req(reqwest::Method::GET, "/api/pin").send().await {
                Ok(r) if r.status().is_success() => pending_pairing(&r.json::<Value>().await.unwrap_or_default()),
                _ => Some(None), // older Sunshine (no GET): post the PIN on its own
            };
            let Some(pairing_id) = pending else {
                if Instant::now() >= deadline {
                    return Err(SunshineError::PinTimeout);
                }
                tokio::time::sleep(self.retry).await;
                continue;
            };
            let body = match &pairing_id {
                Some(id) => json!({"pairing_id": id, "pin": pin, "name": name}),
                None => json!({"pin": pin, "name": name}),
            };
            let r = self.req(reqwest::Method::POST, "/api/pin").json(&body).send().await;
            match r {
                Ok(r) => {
                    let code = r.status();
                    let v: Value = r.json().await.unwrap_or_default();
                    if code.is_success() && (v["status"] == true || v["status"] == "true") {
                        crate::log::line("sunshine: PIN accepted");
                        return Ok(());
                    }
                    crate::log::line(&format!("sunshine: PIN not taken yet ({code} {v})"));
                }
                Err(e) => crate::log::line(&format!("sunshine: PIN request failed: {e}")),
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
    /// Turns off Sunshine's own tray icon and notifications: PC Remote Host is the UI, and
    /// Sunshine's tray opens its PIN web page in the browser on every pair request.
    pub async fn hide_tray(&self) {
        let cur: Option<Value> = match self.req(reqwest::Method::GET, "/api/config").send().await {
            Ok(r) if r.status().is_success() => r.json().await.ok(),
            _ => None,
        };
        if cur.is_some_and(|c| c["system_tray"] != "disabled") {
            let _ = self.set_config("system_tray", "disabled").await;
        }
    }

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

    #[test]
    fn pending_pairing_selection() {
        assert_eq!(pending_pairing(&json!({"pairings": []})), None); // nothing pending yet
        assert_eq!(pending_pairing(&json!({"pairings": [{"id": "a"}, {"id": "b"}]})), Some(Some("b".into()))); // newest
        assert_eq!(pending_pairing(&json!({"status": true})), Some(None)); // older Sunshine: no ids
    }

    #[tokio::test]
    async fn submit_pin_names_the_pending_pairing() {
        let server = MockServer::start().await;
        let id = "0123456789abcdef0123456789abcdef";
        Mock::given(method("GET")).and(path("/api/pin"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"pairings": [{"id": id}]})))
            .mount(&server).await;
        Mock::given(method("POST")).and(path("/api/pin")).and(body_json(json!({"pairing_id": id, "pin": "4821", "name": "Pixel 8"})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status": true})))
            .expect(1).mount(&server).await;
        let s = Sunshine::new(&server.uri(), Some(("u".into(), "p".into())));
        assert_eq!(s.submit_pin("4821", "Pixel 8").await, Ok(()));
    }

    #[tokio::test]
    async fn hide_tray_turns_off_sunshines_tray_once() {
        let server = MockServer::start().await;
        Mock::given(method("GET")).and(path("/api/config"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"system_tray": "enabled", "sunshine_name": "PC"})))
            .mount(&server).await;
        Mock::given(method("POST")).and(path("/api/config")).and(body_json(json!({"system_tray": "disabled", "sunshine_name": "PC"})))
            .respond_with(ResponseTemplate::new(200)).expect(1).mount(&server).await;
        Mock::given(method("POST")).and(path("/api/restart")).respond_with(ResponseTemplate::new(200)).mount(&server).await;
        let s = Sunshine::new(&server.uri(), Some(("u".into(), "p".into())));
        s.hide_tray().await;
    }

    #[tokio::test]
    async fn hide_tray_leaves_an_already_hidden_tray_alone() {
        let server = MockServer::start().await;
        Mock::given(method("GET")).and(path("/api/config"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"system_tray": "disabled"})))
            .mount(&server).await;
        Mock::given(method("POST")).and(path("/api/config")).respond_with(ResponseTemplate::new(200)).expect(0).mount(&server).await;
        Sunshine::new(&server.uri(), Some(("u".into(), "p".into()))).hide_tray().await;
    }

    #[test]
    fn stale_creds_are_detected_from_sunshine_state() {
        let state = r#"{"username": "SadikTD", "salt": "x", "password": "y"}"#;
        assert_eq!(state_username(state).as_deref(), Some("SadikTD"));
        assert!(creds_stale("pcremote", Some(state)));
        assert!(!creds_stale("SadikTD", Some(state)));
        assert!(creds_stale("pcremote", Some("{}")));
        assert!(!creds_stale("pcremote", None)); // can't read the state file: trust what we have
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
        let posts = srv.received_requests().await.unwrap().into_iter().filter(|r| r.method.as_str() == "POST").count();
        assert_eq!(posts, 3); // older Sunshine (no pending list): PIN posted until taken
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

    #[tokio::test]
    async fn creds_set_after_start_are_used() {
        let srv = MockServer::start().await;
        Mock::given(method("POST")).and(path("/api/pin")).and(basic_auth("late", "pw"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"status":true})))
            .mount(&srv).await;
        let mut s = Sunshine::new(&srv.uri(), None);
        s.pin_timeout = Duration::from_millis(100);
        assert_eq!(s.submit_pin("1", "x").await, Err(SunshineError::PinTimeout));
        s.set_creds(("late".into(), "pw".into()));
        assert_eq!(s.submit_pin("1", "x").await, Ok(()));
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
