//! The window's commands, served by the server over the pipe, plus their pure helpers.

use crate::server::App;
use crate::store::Config;
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use serde::Serialize;
use serde_json::Value;
use std::net::Ipv4Addr;
use std::sync::Arc;

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
    devices: Vec<DeviceView>,
    locked: bool,
}

fn state(app: &App) -> StateView {
    let connected = app.agent.connected();
    let devices = app
        .store
        .lock()
        .unwrap()
        .devices()
        .into_iter()
        .map(|d| DeviceView { connected: connected.contains(&d.id), id: d.id, name: d.name })
        .collect();
    StateView { name: crate::win::sys::pc_name(), devices, locked: crate::win::desk::is_locked() }
}

/// A fresh pairing code: the QR (SVG) and the same one-time link as text, for "Copy pairing link".
#[derive(Serialize)]
pub struct PairCode {
    svg: String,
    link: String,
}

fn new_qr(app: &App) -> PairCode {
    let link = pair_url(app);
    let svg = qrcode::QrCode::with_error_correction_level(&link, qrcode::EcLevel::M)
        .map(|q| {
            q.render::<qrcode::render::svg::Color>()
                .quiet_zone(false)
                .dark_color(qrcode::render::svg::Color("#07080C"))
                .light_color(qrcode::render::svg::Color("#FFFFFF"))
                .build()
        })
        .unwrap_or_default();
    PairCode { svg, link }
}

/// A fresh one-time pairing link (what the QR code encodes).
pub fn pair_url(app: &App) -> String {
    let otp = app.store.lock().unwrap().new_otp();
    qr_payload(&crate::win::sys::pc_name(), &rank_ips(crate::win::sys::adapters().0), PORT, &app.fp, &otp)
}

#[derive(Serialize)]
pub struct ConfigView {
    config: Config,
    displays: Vec<crate::engine::capture::Display>,
}

fn get_config(app: &App) -> ConfigView {
    let store = app.store.lock().unwrap();
    let mut config = store.config().clone();
    config.files_dir = store.files_dir().to_string_lossy().into_owned();
    ConfigView { config, displays: crate::engine::capture::displays() }
}

async fn set_config(app: &App, config: Config) -> Result<(), String> {
    let old = app.store.lock().unwrap().config().clone();
    app.store.lock().unwrap().set_config(config.clone()).map_err(|e| e.to_string())?;
    if old.anywhere != config.anywhere {
        let st = crate::net::apply_now(config.anywhere).await;
        *app.net.lock().unwrap() = st;
    }
    Ok(())
}

/// Offers `path` to `device`, or to every connected phone; returns how many got the offer.
pub fn offer_to(app: &App, device: Option<&str>, path: &std::path::Path) -> Result<usize, String> {
    let mut devices = device.map(|d| vec![d.to_string()]).unwrap_or_else(|| app.agent.connected());
    devices.sort();
    devices.dedup(); // one offer per phone, even with two sessions open
    let mut sent = 0;
    for d in devices {
        for m in crate::files::offer_tree(&app.outgoing, path).map_err(|e| e.to_string())? {
            sent += app.agent.send(&d, m).min(1);
        }
    }
    Ok(sent)
}

#[derive(Serialize)]
pub struct NetView {
    #[serde(flatten)]
    status: crate::net::NetStatus,
    lan: Vec<String>,
}

#[derive(Serialize)]
pub struct UpdateView {
    current: String,
    latest: String,
    newer: bool,
    url: String,
}

async fn check_update() -> Result<UpdateView, String> {
    let current = env!("CARGO_PKG_VERSION").to_string();
    let v = crate::update::latest_release().await?;
    let latest = v["tag_name"].as_str().unwrap_or_default().to_string();
    let url = v["html_url"].as_str().unwrap_or("https://github.com/SadikTD/pc-remote/releases").to_string();
    Ok(UpdateView { newer: is_newer(&latest, &current), current, latest, url })
}

fn arg<T: serde::de::DeserializeOwned>(args: &Value, k: &str) -> Result<T, String> {
    serde_json::from_value(args[k].clone()).map_err(|e| format!("{k}: {e}"))
}

fn to_value(v: impl Serialize) -> Result<Value, String> {
    serde_json::to_value(v).map_err(|e| e.to_string())
}

/// The UI's requests over the pipe (crate::ipc), by command name.
pub async fn dispatch(app: Arc<App>, cmd: String, args: Value) -> Result<Value, String> {
    match cmd.as_str() {
        "get_state" => to_value(state(&app)),
        "new_qr" => to_value(new_qr(&app)),
        "revoke" => {
            app.agent.revoke(&arg::<String>(&args, "id")?);
            Ok(Value::Null)
        }
        "rename" => {
            let name: String = arg::<String>(&args, "name")?.trim().chars().take(40).collect();
            if name.is_empty() {
                return Err("empty name".into());
            }
            app.store.lock().unwrap().rename(&arg::<String>(&args, "id")?, &name).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "get_config" => to_value(get_config(&app)),
        "set_config" => to_value(set_config(&app, arg(&args, "config")?).await?),
        "set_files_dir" => {
            let dir: String = arg(&args, "dir")?;
            let mut store = app.store.lock().unwrap();
            let mut cfg = store.config().clone();
            cfg.files_dir = dir;
            store.set_config(cfg).map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        "files_dir" => {
            let dir = app.store.lock().unwrap().files_dir();
            let _ = std::fs::create_dir_all(&dir);
            to_value(dir)
        }
        "offer" => {
            let device: Option<String> = arg(&args, "device").ok().flatten();
            let path: String = arg(&args, "path")?;
            to_value(offer_to(&app, device.as_deref(), std::path::Path::new(&path))?)
        }
        "get_net" => to_value(NetView { status: app.net.lock().unwrap().clone(), lan: rank_ips(crate::win::sys::adapters().0) }),
        "check_update" => to_value(check_update().await?),
        _ => Err(format!("unknown command {cmd}")),
    }
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
    fn newer_version_comparison() {
        assert!(is_newer("v1.0.1", "1.0.0"));
        assert!(is_newer("1.1.0", "1.0.9"));
        assert!(is_newer("v2.0", "1.9.9"));
        assert!(!is_newer("v1.0.0", "1.0.0"));
        assert!(!is_newer("0.9.0", "1.0.0"));
        assert!(!is_newer("garbage", "1.0.0"));
    }
}
