//! The host's one WebSocket to the rendezvous Worker. Each relayed phone connection becomes a
//! virtual agent connection, served exactly like a direct `/v1` one.

use crate::agent::AgentHandle;
use crate::store::{b64url, Store};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::mpsc;
use tokio_rustls::rustls::{self, pki_types::ServerName};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::{self, Message};

const BACKOFF_S: [u64; 5] = [1, 2, 5, 10, 30];
const PING_EVERY: Duration = Duration::from_secs(60);
/// No pong (or anything else) for this long: the socket is dead.
const SILENT: Duration = Duration::from_secs(150);

/// What the Worker sends us.
#[derive(Debug, PartialEq)]
pub enum Incoming {
    Open(u32),
    Close(u32),
    Text(u32, String),
    Bin(u32, Vec<u8>),
}

pub fn parse(m: &Message) -> Option<Incoming> {
    #[derive(Deserialize)]
    struct Env {
        c: u32,
        o: Option<String>,
        m: Option<String>,
    }
    match m {
        Message::Binary(b) if b.len() >= 4 => Some(Incoming::Bin(u32::from_be_bytes(b[..4].try_into().ok()?), b[4..].to_vec())),
        Message::Text(t) => {
            let e: Env = serde_json::from_str(t).ok()?;
            match (e.o.as_deref(), e.m) {
                (Some("open"), _) => Some(Incoming::Open(e.c)),
                (Some("close"), _) => Some(Incoming::Close(e.c)),
                (None, Some(m)) => Some(Incoming::Text(e.c, m)),
                _ => None,
            }
        }
        _ => None,
    }
}

pub fn text_env(c: u32, m: &str) -> Message {
    Message::text(serde_json::json!({ "c": c, "m": m }).to_string())
}

pub fn bin_env(c: u32, b: &[u8]) -> Message {
    let mut v = Vec::with_capacity(4 + b.len());
    v.extend_from_slice(&c.to_be_bytes());
    v.extend_from_slice(b);
    Message::binary(v)
}

pub fn close_env(c: u32) -> Message {
    Message::text(serde_json::json!({ "c": c, "o": "close" }).to_string())
}

pub fn revoke_env(dev: &str) -> Message {
    Message::text(serde_json::json!({ "o": "revoke", "dev": dev }).to_string())
}

/// `<base>/v1/host?pc=..&ts=..&sig=..[&register=..]`
pub fn host_url(base: &str, store: &Store, ts: u64, register: bool) -> String {
    let (pc, key) = (store.pc_id(), store.relay_key());
    let mut u = format!("{base}/v1/host?pc={pc}&ts={ts}&sig={}", super::host_sig(&key, pc, ts));
    if register {
        u += &format!("&register={}", b64url(&key));
    }
    u
}

pub(crate) trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}
pub(crate) type Ws = tokio_tungstenite::WebSocketStream<Box<dyn Io>>;

pub(crate) async fn connect(url: &str) -> Result<Ws, tungstenite::Error> {
    let req = url.into_client_request()?;
    let uri = req.uri();
    let host = uri.host().unwrap_or_default().to_string();
    let tls = uri.scheme_str() == Some("wss");
    let port = uri.port_u16().unwrap_or(if tls { 443 } else { 80 });
    let tcp = tokio::net::TcpStream::connect((host.as_str(), port)).await?;
    let _ = tcp.set_nodelay(true);
    let io: Box<dyn Io> = if tls {
        use rustls_platform_verifier::BuilderVerifierExt;
        let cfg = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .and_then(|b| b.with_platform_verifier())
            .map_err(|e| tungstenite::Error::Io(std::io::Error::other(e)))?
            .with_no_client_auth();
        let name = ServerName::try_from(host).map_err(|e| tungstenite::Error::Io(std::io::Error::other(e)))?;
        Box::new(tokio_rustls::TlsConnector::from(Arc::new(cfg)).connect(name, tcp).await?)
    } else {
        Box::new(tcp)
    };
    let cfg = tungstenite::protocol::WebSocketConfig::default()
        .max_message_size(Some(crate::agent::MAX_FRAME + 64))
        .max_frame_size(Some(crate::agent::MAX_FRAME + 64));
    Ok(tokio_tungstenite::client_async_with_config(req, io, Some(cfg)).await?.0)
}

fn unix_now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// Connects, registering the relay key when the Worker doesn't know it yet.
async fn open(base: &str, store: &Mutex<Store>) -> Result<Ws, tungstenite::Error> {
    let registered = store.lock().unwrap().relay_registered();
    let url = host_url(base, &store.lock().unwrap(), unix_now(), !registered);
    let ws = match connect(&url).await {
        Err(tungstenite::Error::Http(r)) if registered && r.status() == 401 => {
            let url = host_url(base, &store.lock().unwrap(), unix_now(), true);
            connect(&url).await?
        }
        r => r?,
    };
    let _ = store.lock().unwrap().set_relay_registered();
    Ok(ws)
}

/// Keeps the Worker connection up for the life of the process.
pub async fn run(agent: AgentHandle, store: Arc<Mutex<Store>>, base: String) {
    let mut fails = 0;
    loop {
        match open(&base, &store).await {
            Ok(ws) => {
                fails = 0;
                crate::log::line("rendezvous: connected");
                serve(ws, &agent, &store).await;
                crate::log::line("rendezvous: disconnected");
            }
            Err(e) => crate::log::line(&format!("rendezvous: {e}")),
        }
        tokio::time::sleep(Duration::from_secs(BACKOFF_S[fails.min(BACKOFF_S.len() - 1)])).await;
        fails += 1;
    }
}

async fn serve(ws: Ws, agent: &AgentHandle, store: &Mutex<Store>) {
    let (mut wtx, mut wrx) = ws.split();
    // Bounded: a relayed file download waits for the Worker socket.
    let (out_tx, mut out_rx) = mpsc::channel::<Message>(64);
    for dev in store.lock().unwrap().revoked() {
        let _ = out_tx.try_send(revoke_env(&dev));
    }
    agent.set_relay(Some(out_tx.clone()));
    let mut conns: HashMap<u32, mpsc::UnboundedSender<Message>> = HashMap::new();
    let mut ping = tokio::time::interval_at(tokio::time::Instant::now() + PING_EVERY, PING_EVERY);
    let mut last_rx = tokio::time::Instant::now();
    loop {
        tokio::select! {
            m = wrx.next() => {
                let Some(Ok(m)) = m else { break };
                last_rx = tokio::time::Instant::now();
                match parse(&m) {
                    Some(Incoming::Open(c)) => {
                        let (tx, rx) = mpsc::unbounded_channel();
                        conns.insert(c, tx);
                        tokio::spawn(relayed(agent.clone(), c, rx, out_tx.clone()));
                    }
                    Some(Incoming::Close(c)) => drop(conns.remove(&c)), // ends that session
                    Some(Incoming::Text(c, t)) => forward(&mut conns, c, Message::text(t)),
                    Some(Incoming::Bin(c, b)) => forward(&mut conns, c, Message::binary(b)),
                    None => {} // pong
                }
            }
            Some(m) = out_rx.recv() => if wtx.send(m).await.is_err() { break },
            _ = ping.tick() => {
                if last_rx.elapsed() > SILENT || wtx.send(Message::text("ping")).await.is_err() { break }
            }
        }
    }
    agent.set_relay(None);
}

fn forward(conns: &mut HashMap<u32, mpsc::UnboundedSender<Message>>, c: u32, m: Message) {
    if conns.get(&c).is_some_and(|tx| tx.send(m).is_err()) {
        conns.remove(&c);
    }
}

/// One phone through the Worker: the agent protocol over channels, then `close` to the Worker.
async fn relayed(agent: AgentHandle, c: u32, mut rx: mpsc::UnboundedReceiver<Message>, out: mpsc::Sender<Message>) {
    let incoming = futures_util::stream::poll_fn(move |cx| rx.poll_recv(cx));
    let outgoing = Box::pin(futures_util::sink::unfold(out.clone(), move |out, m: Message| async move {
        let env = match m {
            Message::Text(t) => text_env(c, &t),
            Message::Binary(b) => bin_env(c, &b),
            _ => return Ok(out), // Close: sent below, whatever ended the session
        };
        out.send(env).await.map(|_| out)
    }));
    agent.serve(incoming, outgoing).await;
    let _ = out.send(close_env(c)).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelopes() {
        assert_eq!(parse(&Message::text(r#"{"c":7,"o":"open"}"#)), Some(Incoming::Open(7)));
        assert_eq!(parse(&Message::text(r#"{"c":7,"o":"close"}"#)), Some(Incoming::Close(7)));
        assert_eq!(
            parse(&Message::text(r#"{"c":7,"m":"{\"t\":\"ping\"}"}"#)),
            Some(Incoming::Text(7, r#"{"t":"ping"}"#.into()))
        );
        assert_eq!(parse(&Message::binary(vec![0, 0, 1, 2, 9, 8])), Some(Incoming::Bin(258, vec![9, 8])));
        assert_eq!(parse(&Message::binary(vec![0, 0, 1])), None);
        assert_eq!(parse(&Message::text("pong")), None);
        assert_eq!(parse(&Message::text(r#"{"o":"open"}"#)), None, "no cid");
        assert_eq!(parse(&Message::text(r#"{"c":1,"o":"what"}"#)), None);

        let v: serde_json::Value = serde_json::from_str(text_env(3, r#"{"t":"pong"}"#).to_text().unwrap()).unwrap();
        assert_eq!(v, serde_json::json!({"c":3,"m":"{\"t\":\"pong\"}"}));
        assert_eq!(bin_env(0x01020304, &[5]).into_data().to_vec(), vec![1, 2, 3, 4, 5]);
        let json = |m: Message| serde_json::from_str::<serde_json::Value>(m.to_text().unwrap()).unwrap();
        assert_eq!(json(close_env(3)), serde_json::json!({"c":3,"o":"close"}));
        assert_eq!(json(revoke_env("ab")), serde_json::json!({"o":"revoke","dev":"ab"}));
    }

    #[test]
    fn host_url_signs_and_registers() {
        let dir = std::env::temp_dir().join(format!("pcr-rdv-url-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let s = Store::open(&dir).unwrap();
        let (pc, key) = (s.pc_id().to_string(), s.relay_key());
        let u = host_url("wss://w.example", &s, 1_700_000_000, false);
        assert_eq!(u, format!("wss://w.example/v1/host?pc={pc}&ts=1700000000&sig={}", super::super::host_sig(&key, &pc, 1_700_000_000)));
        assert!(host_url("wss://w.example", &s, 1, true).ends_with(&format!("&register={}", b64url(&key))));
    }
}
