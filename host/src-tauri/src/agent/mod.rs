//! TLS WebSocket agent on 47999 (docs/protocol.md).

mod session;
#[cfg(test)]
mod tests;

use crate::proto::{ClientMsg, PcInfo, ServerMsg};
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

/// Largest WebSocket message accepted: an 8 MB clipboard image as base64, plus slack.
pub const MAX_FRAME: usize = 12 << 20;

/// One authenticated phone connection's state on the host side. Dropping it ends
/// everything the session started (held keys, subscriptions, pads, transfers).
pub trait Session: Send {
    /// An authenticated, sanitised message other than ping/pair/hello.
    fn on_msg(&mut self, m: ClientMsg) -> Option<ServerMsg>;
    /// A binary frame (file chunk).
    fn on_binary(&mut self, _b: &[u8]) -> Option<ServerMsg> {
        None
    }
}

/// Lets a session push to its phone at any time. Binary frames go through a small
/// bounded queue, so a fast producer (a file upload) waits for the socket.
#[derive(Clone)]
pub struct Outbox {
    ctl: mpsc::UnboundedSender<Out>,
    bin: mpsc::Sender<Vec<u8>>,
}

impl Outbox {
    /// False once the session is gone.
    pub fn send(&self, m: ServerMsg) -> bool {
        self.ctl.send(Out::Msg(m)).is_ok()
    }
    pub async fn send_bin(&self, b: Vec<u8>) -> bool {
        self.bin.send(b).await.is_ok()
    }
    pub fn is_closed(&self) -> bool {
        self.ctl.is_closed()
    }
}

/// What the agent needs from the rest of the host.
pub trait Handlers: Send + Sync + 'static {
    fn pc_info(&self) -> BoxFut<PcInfo>;
    /// Submits a Moonlight PIN to Sunshine; false once it gave up (`pin_timeout`).
    fn submit_pin(&self, pin: String, name: String) -> BoxFut<bool>;
    /// Capabilities for `welcome`.
    fn caps(&self) -> Vec<String>;
    fn session(&self, device: &str, out: Outbox) -> Box<dyn Session>;
}

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    Paired { device: DeviceId },
    Connected { device: DeviceId },
    Disconnected { device: DeviceId },
}

pub(crate) enum Out {
    Msg(ServerMsg),
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

    /// Sends to every open session of `device`; returns how many got it.
    pub fn send(&self, device: &str, m: ServerMsg) -> usize {
        let sessions = self.ctx.sessions.lock().unwrap();
        sessions.values().filter(|(d, tx)| d == device && tx.send(Out::Msg(m.clone())).is_ok()).count()
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
