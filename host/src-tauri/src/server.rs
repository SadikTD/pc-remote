//! `--server`: everything phones talk to, running as SYSTEM in the console session (started by the
//! service). It serves the window over the pipe (crate::ipc) and starts the tray at sign-in.

use crate::agent::{AgentHandle, Event};
use crate::{files, ipc, mdns, net, remote, store::Store, tls, win};
use serde_json::{json, Value};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::broadcast;

/// Shared state for the window's commands (crate::commands).
pub struct App {
    pub store: Arc<Mutex<Store>>,
    pub agent: AgentHandle,
    pub fp: String,
    pub outgoing: files::Outgoing,
    pub net: Arc<Mutex<net::NetStatus>>,
}

pub fn run() {
    let _ = tokio_rustls::rustls::crypto::ring::default_provider().install_default();
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("runtime");
    if let Err(e) = rt.block_on(serve()) {
        crate::log::line(&format!("server: stopped: {e}"));
    }
}

async fn serve() -> Result<(), Box<dyn std::error::Error>> {
    let dir = crate::data_dir();
    std::fs::create_dir_all(&dir)?;
    migrate(&dir);
    let identity = tls::Identity::load_or_create(&dir)?;
    let store = Arc::new(Mutex::new(Store::open(&dir)?));
    let host = Arc::new(crate::host::Host::new(store.clone()));
    let (outgoing, net) = (host.outgoing.clone(), host.net.clone());
    if store.lock().unwrap().config().anywhere {
        let net = net.clone();
        tokio::spawn(async move { *net.lock().unwrap() = net::apply_now(true).await });
    }
    let agent = crate::agent::Agent::start(&identity, store.clone(), host, "0.0.0.0:47999".parse()?, Duration::from_secs(30)).await?;
    if let Some(url) = remote::url() {
        tokio::spawn(remote::rendezvous::run(agent.clone(), store.clone(), url.into()));
    }
    let _mdns = mdns::Advert::start(store.lock().unwrap().pc_id(), &win::sys::pc_name());
    crate::log::line(&format!("server: up in session {}", win::session::current()));

    let ui = broadcast::channel::<Value>(32).0;
    let tx = ui.clone();
    crate::notify::set_hook(Box::new(move |title, body| {
        let _ = tx.send(json!({"ev": "toast", "title": title, "body": body}));
    }));
    let (mut events, tx) = (agent.events(), ui.clone());
    tokio::spawn(async move {
        while let Ok(e) = events.recv().await {
            let _ = tx.send(json!({"ev": if matches!(e, Event::Paired { .. }) { "paired" } else { "devices" }}));
        }
    });
    tokio::spawn(watch_lock(agent.clone(), ui.clone()));
    tokio::spawn(start_tray(store.clone()));

    let app = Arc::new(App { store, agent, fp: identity.fingerprint_hex, outgoing, net });
    let handler: ipc::Handler = Arc::new(move |cmd, args| Box::pin(crate::commands::dispatch(app.clone(), cmd, args)));
    ipc::serve(ipc::PIPE, handler, ui).await?;
    Ok(())
}

/// Tells phones (and the window) when the PC locks or unlocks.
async fn watch_lock(agent: AgentHandle, ui: broadcast::Sender<Value>) {
    let mut last = None;
    loop {
        let locked = tokio::task::spawn_blocking(win::desk::is_locked).await.unwrap_or(false);
        if last != Some(locked) {
            if last.is_some() {
                agent.broadcast(crate::proto::ServerMsg::Lock { locked });
                let _ = ui.send(json!({"ev": "devices"}));
            }
            last = Some(locked);
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

/// Starts the tray as the signed-in user once per sign-in (when "show in tray" is on).
async fn start_tray(store: Arc<Mutex<Store>>) {
    let mut started = false;
    loop {
        match win::session::user_token() {
            None => started = false,
            Some(t) if !started && store.lock().unwrap().config().autostart => {
                started = win::session::spawn(*t, "--hidden", true).is_some();
            }
            Some(_) => {}
        }
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
}

/// Upgrading from 2.x: the data lived in the user's `%APPDATA%\PC Remote` with a user-scope DPAPI key.
/// Copy it here once and re-wrap the key, so paired phones keep working with the same fingerprint.
fn migrate(dir: &Path) {
    if dir.join("store.json").exists() {
        return;
    }
    let Some(token) = win::session::user_token() else { return };
    let Some(old) = win::session::profile_dir(*token).map(|p| p.join("AppData").join("Roaming").join("PC Remote")) else { return };
    let key = std::fs::read(old.join("tls.key")).ok().and_then(|blob| {
        use windows::Win32::Security::{ImpersonateLoggedOnUser, RevertToSelf};
        // SAFETY: impersonation is reverted before returning; the token outlives it.
        unsafe {
            ImpersonateLoggedOnUser(*token).ok()?;
            let k = win::dpapi::unprotect(&blob).ok();
            let _ = RevertToSelf();
            k
        }
    });
    match copy_data(&old, dir, key.as_deref()) {
        Ok(true) => crate::log::line(&format!("server: migrated data from {}", old.display())),
        Ok(false) => {}
        Err(e) => crate::log::line(&format!("server: migration failed: {e}")),
    }
}

/// Copies the store and TLS identity from `old` (2.x) into `dir`; `key` is the decrypted TLS key.
/// Without the key the identity is left behind (a new one means re-pairing, but nothing breaks).
pub fn copy_data(old: &Path, dir: &Path, key: Option<&[u8]>) -> std::io::Result<bool> {
    if !old.join("store.json").exists() {
        return Ok(false);
    }
    std::fs::create_dir_all(dir)?;
    if let (Some(k), Ok(cert)) = (key, std::fs::read(old.join("tls.cer"))) {
        std::fs::write(dir.join("tls.key"), win::dpapi::protect(k).map_err(std::io::Error::other)?)?;
        std::fs::write(dir.join("tls.cer"), cert)?;
    }
    std::fs::copy(old.join("store.json"), dir.join("store.json"))?; // last: its presence marks "migrated"
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migration_keeps_identity_and_store() {
        let base = std::env::temp_dir().join(format!("pcr-migrate-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        let (old, new) = (base.join("old"), base.join("new"));
        let id = tls::Identity::load_or_create(&old).unwrap();
        Store::open(&old).unwrap();
        assert!(!copy_data(&base.join("none"), &new, None).unwrap());
        assert!(copy_data(&old, &new, Some(&id.key_der)).unwrap());
        let moved = tls::Identity::load_or_create(&new).unwrap();
        assert_eq!(moved.fingerprint_hex, id.fingerprint_hex, "phones pin this fingerprint");
        assert_eq!(Store::open(&new).unwrap().pc_id(), Store::open(&old).unwrap().pc_id());
    }
}
