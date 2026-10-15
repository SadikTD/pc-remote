//! Tauri commands for the window, plus their pure helpers.

use crate::store::Config;
use crate::App;
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use serde::Serialize;
use std::net::Ipv4Addr;
use tauri::State;

const NAME_SET: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'.').remove(b'_').remove(b'~');
pub const PORT: u16 = 47999;

pub fn qr_payload(name: &str, ips: &[String], port: u16, fp: &str, otp: &str) -> String {
    format!(
        "pcremote://pair?n={}&h={}&p={port}&fp={fp}&t={otp}",
        utf8_percent_encode(name, NAME_SET),
        ips.join(",")
    )
}

/// LAN IPv4s, best first: private ranges, then adapters with a gateway. Loopback and link-local dropped.
pub fn rank_ips(mut ips: Vec<(Ipv4Addr, bool)>) -> Vec<String> {
    ips.retain(|(ip, _)| !ip.is_loopback() && !ip.is_link_local() && !ip.is_unspecified());
    ips.sort_by_key(|(ip, gw)| (!ip.is_private(), !gw));
    ips.into_iter().map(|(ip, _)| ip.to_string()).collect()
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Display {
    pub id: String,
    pub name: String,
    pub primary: bool,
}

/// Sunshine wants its own display device ids for `output_name`; it only publishes them
/// in its startup log, as a JSON array after "Currently available display devices:".
pub fn parse_displays(log: &str) -> Vec<Display> {
    let Some(i) = log.find("Currently available display devices:") else { return vec![] };
    let rest = &log[i..];
    let Some(start) = rest.find('[') else { return vec![] };
    let mut de = serde_json::Deserializer::from_str(&rest[start..]).into_iter::<Vec<serde_json::Value>>();
    let Some(Ok(arr)) = de.next() else { return vec![] };
    arr.iter()
        .filter_map(|d| {
            Some(Display {
                id: d["device_id"].as_str()?.into(),
                name: d["friendly_name"].as_str().or(d["display_name"].as_str())?.into(),
                primary: d["info"]["primary"].as_bool().unwrap_or(false),
            })
        })
        .collect()
}

fn version(v: &str) -> Option<Vec<u64>> {
    v.trim_start_matches('v').split('.').map(|p| p.parse().ok()).collect()
}

pub fn is_newer(latest: &str, current: &str) -> bool {
    matches!((version(latest), version(current)), (Some(l), Some(c)) if l > c)
}

#[derive(Serialize)]
pub struct DeviceView {
    id: String,
    name: String,
    connected: bool,
}

#[derive(Serialize)]
pub struct StateView {
    name: String,
    sunshine: bool,
    devices: Vec<DeviceView>,
}

#[tauri::command]
pub async fn get_state(app: State<'_, App>) -> Result<StateView, String> {
    let sunshine = app.sunshine.status().await.running;
    let connected = app.agent.connected();
    let devices = app
        .store
        .lock()
        .unwrap()
        .devices()
        .into_iter()
        .map(|d| DeviceView { connected: connected.contains(&d.id), id: d.id, name: d.name })
        .collect();
    Ok(StateView { name: crate::win::sys::pc_name(), sunshine, devices })
}

#[tauri::command]
pub fn new_qr(app: State<'_, App>) -> String {
    let otp = app.store.lock().unwrap().new_otp();
    let uri = qr_payload(&crate::win::sys::pc_name(), &rank_ips(crate::win::sys::adapters().0), PORT, &app.fp, &otp);
    qrcode::QrCode::with_error_correction_level(uri, qrcode::EcLevel::M)
        .map(|q| {
            q.render::<qrcode::render::svg::Color>()
                .quiet_zone(false)
                .dark_color(qrcode::render::svg::Color("#0B0D12"))
                .light_color(qrcode::render::svg::Color("#FFFFFF"))
                .build()
        })
        .unwrap_or_default()
}

#[tauri::command]
pub fn revoke(app: State<'_, App>, id: String) {
    app.agent.revoke(&id);
}

#[tauri::command]
pub fn rename(app: State<'_, App>, id: String, name: String) -> Result<(), String> {
    let name: String = name.trim().chars().take(40).collect();
    if name.is_empty() {
        return Err("empty name".into());
    }
    app.store.lock().unwrap().rename(&id, &name).map_err(|e| e.to_string())
}

#[derive(Serialize)]
pub struct ConfigView {
    config: Config,
    displays: Vec<Display>,
}

#[tauri::command]
pub fn get_config(app: State<'_, App>) -> ConfigView {
    let log = std::fs::read_to_string(r"C:\Program Files\Sunshine\config\sunshine.log").unwrap_or_default();
    ConfigView { config: app.store.lock().unwrap().config().clone(), displays: parse_displays(&log) }
}

#[tauri::command]
pub async fn set_config(app: State<'_, App>, config: Config) -> Result<(), String> {
    let old = app.store.lock().unwrap().config().clone();
    app.store.lock().unwrap().set_config(config.clone()).map_err(|e| e.to_string())?;
    if old.autostart != config.autostart {
        crate::win::sys::set_autostart(config.autostart).map_err(|e| e.to_string())?;
    }
    if old.display != config.display {
        app.sunshine.set_config("output_name", &config.display).await.map_err(|e| format!("{e:?}"))?;
    }
    if old.anywhere != config.anywhere {
        let st = crate::net::apply_now(config.anywhere).await;
        *app.net.lock().unwrap() = st;
        let v = if config.anywhere { "enabled" } else { "disabled" };
        app.sunshine.set_config("upnp", v).await.map_err(|e| format!("{e:?}"))?;
    }
    Ok(())
}

#[tauri::command]
pub fn open_files_dir(app: State<'_, App>) -> Result<(), String> {
    let dir = app.store.lock().unwrap().config().files_dir.clone();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::process::Command::new("explorer.exe").arg(&dir).spawn().map(|_| ()).map_err(|e| e.to_string())
}

/// Offers `path` to `device`, or to every connected phone; returns how many got the offer.
pub fn offer_to(app: &App, device: Option<&str>, path: &std::path::Path) -> Result<usize, String> {
    let mut devices = device.map(|d| vec![d.to_string()]).unwrap_or_else(|| app.agent.connected());
    devices.dedup();
    let mut sent = 0;
    for d in devices {
        let m = crate::files::offer(&app.outgoing, path).map_err(|e| e.to_string())?;
        sent += app.agent.send(&d, m).min(1);
    }
    Ok(sent)
}

/// "Send file" on a connected phone: system file picker, then `file.offer`.
#[tauri::command]
pub fn send_file(app: State<'_, App>, window: tauri::WebviewWindow, device: String) -> Result<bool, String> {
    let Some(path) = crate::win::sys::pick(window.hwnd().ok(), false) else { return Ok(false) };
    offer_to(&app, Some(&device), &path).map(|n| n > 0)
}

/// Settings → Received files → Change.
#[tauri::command]
pub fn pick_files_dir(app: State<'_, App>, window: tauri::WebviewWindow) -> Result<Option<String>, String> {
    let Some(dir) = crate::win::sys::pick(window.hwnd().ok(), true) else { return Ok(None) };
    let dir = dir.to_string_lossy().into_owned();
    let mut store = app.store.lock().unwrap();
    let mut cfg = store.config().clone();
    cfg.files_dir = dir.clone();
    store.set_config(cfg).map_err(|e| e.to_string())?;
    Ok(Some(dir))
}

#[derive(Serialize)]
pub struct NetView {
    #[serde(flatten)]
    status: crate::net::NetStatus,
    lan: Vec<String>,
}

/// Access-from-anywhere status for Settings.
#[tauri::command]
pub fn get_net(app: State<'_, App>) -> NetView {
    NetView { status: app.net.lock().unwrap().clone(), lan: rank_ips(crate::win::sys::adapters().0) }
}

/// Opens the project's GitHub releases page in the default browser.
#[tauri::command]
pub fn open_release(url: String) -> Result<(), String> {
    if !url.starts_with("https://github.com/SadikTD/") {
        return Err("refused".into());
    }
    std::process::Command::new("explorer.exe").arg(url).spawn().map(|_| ()).map_err(|e| e.to_string())
}

#[derive(Serialize)]
pub struct UpdateView {
    current: String,
    latest: String,
    newer: bool,
    url: String,
}

#[tauri::command]
pub async fn check_update() -> Result<UpdateView, String> {
    let current = env!("CARGO_PKG_VERSION").to_string();
    let v: serde_json::Value = reqwest::Client::new()
        .get("https://api.github.com/repos/SadikTD/pc-remote/releases/latest")
        .header("User-Agent", "pc-remote-host")
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?
        .json()
        .await
        .map_err(|e| e.to_string())?;
    let latest = v["tag_name"].as_str().unwrap_or_default().to_string();
    let url = v["html_url"].as_str().unwrap_or("https://github.com/SadikTD/pc-remote/releases").to_string();
    Ok(UpdateView { newer: is_newer(&latest, &current), current, latest, url })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    #[test]
    fn qr_payload_matches_protocol() {
        let fp = "ab".repeat(32);
        let uri = qr_payload("DESKTOP-ABC", &["192.168.0.126".into(), "10.0.0.5".into()], 47999, &fp, "otp_-x");
        assert_eq!(uri, format!("pcremote://pair?n=DESKTOP-ABC&h=192.168.0.126,10.0.0.5&p=47999&fp={fp}&t=otp_-x"));
    }

    #[test]
    fn qr_payload_url_encodes_name() {
        let uri = qr_payload("Sadik's PC & Co é", &[], 47999, "f", "t");
        assert!(uri.starts_with("pcremote://pair?n=Sadik%27s%20PC%20%26%20Co%20%C3%A9&h=&p=47999"), "{uri}");
    }

    #[test]
    fn lan_ips_private_and_gateway_first() {
        let ips = vec![
            (Ipv4Addr::new(100, 101, 1, 2), true),
            (Ipv4Addr::new(169, 254, 1, 1), true),
            (Ipv4Addr::new(192, 168, 56, 1), false),
            (Ipv4Addr::new(127, 0, 0, 1), true),
            (Ipv4Addr::new(192, 168, 0, 126), true),
            (Ipv4Addr::new(10, 0, 0, 5), true),
            (Ipv4Addr::new(172, 20, 0, 1), false),
            (Ipv4Addr::new(172, 32, 0, 1), true),
        ];
        assert_eq!(
            rank_ips(ips),
            vec!["192.168.0.126", "10.0.0.5", "192.168.56.1", "172.20.0.1", "100.101.1.2", "172.32.0.1"]
        );
    }

    #[test]
    fn displays_parsed_from_sunshine_log() {
        let log = r#"[2026-10-15 21:24:24.328]: Info: something
[2026-10-15 21:24:24.333]: Info: Currently available display devices:
[
  {
    "device_id": "{0613302e-c22e-560f-a2a6-789bf9656cfa}",
    "display_name": "\\\\.\\DISPLAY1",
    "friendly_name": "M27QA ICE",
    "info": { "primary": false }
  },
  {
    "device_id": "{14b6e91b-9400-5efb-a888-60b537faaafa}",
    "display_name": "\\\\.\\DISPLAY2",
    "friendly_name": "M27Q2",
    "info": { "primary": true }
  }
]
[2026-10-15 21:24:24.400]: Info: next line"#;
        let d = parse_displays(log);
        assert_eq!(d.len(), 2);
        assert_eq!(d[0], Display { id: "{0613302e-c22e-560f-a2a6-789bf9656cfa}".into(), name: "M27QA ICE".into(), primary: false });
        assert!(d[1].primary);
        assert!(parse_displays("no displays here").is_empty());
    }

    #[test]
    fn newer_version_comparison() {
        assert!(is_newer("v1.0.1", "1.0.0"));
        assert!(is_newer("1.1.0", "1.0.9"));
        assert!(is_newer("v2.0", "1.9.9"));
        assert!(!is_newer("v1.0.0", "1.0.0"));
        assert!(!is_newer("0.9.0", "1.0.0"));
        assert!(!is_newer("garbage", "1.0.0"));
    }
}
