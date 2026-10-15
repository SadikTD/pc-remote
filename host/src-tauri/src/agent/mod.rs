//! TLS WebSocket agent on 47999 (docs/protocol.md).

mod session;
#[cfg(test)]
mod tests;

use crate::proto::{ClientMsg, PcInfo};
use crate::store::{DeviceId, Store};
use crate::tls::Identity;
use std::collections::HashMap;
use std::future::Future;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{broadcast, mpsc};
use tokio_rustls::rustls::{self, pki_types::PrivatePkcs8KeyDer};

pub type BoxFut<T> = Pin<Box<dyn Future<Output = T> + Send>>;

/// Per-session input state; dropping it releases anything still held.
pub trait InputSink: Send {
    fn handle(&mut self, m: &ClientMsg) -> Result<(), &'static str>;
}

/// What the agent needs from the rest of the host. Error values are protocol codes.
pub trait Handlers: Send + Sync + 'static {
    fn pc_info(&self) -> BoxFut<PcInfo>;
    /// Submits a Moonlight PIN to Sunshine; false once it gave up (`pin_timeout`).
    fn submit_pin(&self, pin: String, name: String) -> BoxFut<bool>;
    fn input(&self) -> Box<dyn InputSink>;
    fn power(&self, action: &str) -> Result<(), &'static str>;
}

/// Largest WebSocket message accepted: an 8 MB clipboard image as base64, plus slack.
pub const MAX_FRAME: usize = 12 << 20;

/// Capabilities this build implements; the rest answer `unsupported`.
pub const CAPS: &[&str] = &["input", "power"];

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Paired { device: DeviceId },
    Connected { device: DeviceId },
    Disconnected { device: DeviceId },
}

pub(crate) enum Out {
    Msg(crate::proto::ServerMsg),
    Close(&'static str),
}

pub(crate) struct Ctx {
    pub store: Arc<Mutex<Store>>,
    pub handlers: Arc<dyn Handlers>,
    pub idle: Duration,
    pub sessions: Mutex<HashMap<u64, (DeviceId, mpsc::UnboundedSender<Out>)>>,
    pub events: broadcast::Sender<Event>,
    next_id: AtomicU64,
}

impl Ctx {
    pub fn register(&self, dev: &DeviceId, tx: mpsc::UnboundedSender<Out>) -> u64 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.sessions.lock().unwrap().insert(id, (dev.clone(), tx));
        let _ = self.events.send(Event::Connected { device: dev.clone() });
        id
    }

    pub fn unregister(&self, id: u64) {
        if let Some((dev, _)) = self.sessions.lock().unwrap().remove(&id) {
            let _ = self.events.send(Event::Disconnected { device: dev });
        }
    }
}

#[derive(Clone)]
pub struct AgentHandle {
    pub addr: SocketAddr,
    ctx: Arc<Ctx>,
}

impl AgentHandle {
    /// Revokes the device and closes only its sessions with `revoked`.
    pub fn revoke(&self, device: &str) {
        let _ = self.ctx.store.lock().unwrap().revoke(device);
        for (dev, tx) in self.ctx.sessions.lock().unwrap().values() {
            if dev == device {
                let _ = tx.send(Out::Close("revoked"));
            }
        }
    }

    pub fn events(&self) -> broadcast::Receiver<Event> {
        self.ctx.events.subscribe()
    }

    /// Device ids with an open session (one entry per session).
    pub fn connected(&self) -> Vec<DeviceId> {
        self.ctx.sessions.lock().unwrap().values().map(|(d, _)| d.clone()).collect()
    }
}

pub struct Agent;

impl Agent {
    pub async fn start(
        identity: &Identity,
        store: Arc<Mutex<Store>>,
        handlers: Arc<dyn Handlers>,
        bind: SocketAddr,
        idle: Duration,
    ) -> std::io::Result<AgentHandle> {
        let tls = rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .map_err(std::io::Error::other)?
            .with_no_client_auth()
            .with_single_cert(
                vec![identity.cert_der.clone().into()],
                PrivatePkcs8KeyDer::from(identity.key_der.clone()).into(),
            )
            .map_err(std::io::Error::other)?;
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tls));
        let listener = tokio::net::TcpListener::bind(bind).await?;
        let addr = listener.local_addr()?;
        let ctx = Arc::new(Ctx {
            store,
            handlers,
            idle,
            sessions: Mutex::default(),
            events: broadcast::channel(32).0,
            next_id: AtomicU64::new(0),
        });
        let c = ctx.clone();
        tokio::spawn(async move {
            loop {
                let Ok((tcp, _)) = listener.accept().await else { continue };
                let _ = tcp.set_nodelay(true);
                tokio::spawn(session::run(tcp, acceptor.clone(), c.clone()));
            }
        });
        Ok(AgentHandle { addr, ctx })
    }
}
