use super::*;
use crate::proto::PcInfo;
use crate::store::Store;
use crate::tls::Identity;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::Mutex as StdMutex;
use tokio::net::TcpStream;
use tokio_rustls::rustls::{
    self,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, ServerName, UnixTime},
    DigitallySignedStruct, SignatureScheme,
};
use tokio_tungstenite::{tungstenite::Message, WebSocketStream};

type Ws = WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>>;

/// Accepts exactly the certificate whose SHA-256 is `fp`, like the phone does.
#[derive(Debug)]
struct Pinned(String);

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if crate::tls::hex(&crate::tls::sha256(end)) == self.0 {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("fingerprint mismatch".into()))
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(m, c, d, &rustls::crypto::ring::default_provider().signature_verification_algorithms)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(m, c, d, &rustls::crypto::ring::default_provider().signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

async fn connect(addr: SocketAddr, fp: &str) -> Ws {
    let cfg = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned(fp.into())))
        .with_no_client_auth();
    let tcp = TcpStream::connect(addr).await.unwrap();
    let tls = tokio_rustls::TlsConnector::from(Arc::new(cfg))
        .connect(ServerName::try_from("pc-remote.local").unwrap(), tcp)
        .await
        .unwrap();
    let (ws, _) = tokio_tungstenite::client_async(format!("wss://{addr}/v1"), tls).await.unwrap();
    ws
}

async fn send(ws: &mut Ws, v: Value) {
    ws.send(Message::text(v.to_string())).await.unwrap();
}

/// Next JSON message, or None once the server has closed.
async fn recv(ws: &mut Ws) -> Option<Value> {
    loop {
        match tokio::time::timeout(Duration::from_secs(3), ws.next()).await.expect("recv timed out") {
            Some(Ok(Message::Text(t))) => return Some(serde_json::from_str(&t).unwrap()),
            Some(Ok(Message::Close(_))) | None | Some(Err(_)) => return None,
            Some(Ok(_)) => {}
        }
    }
}

async fn recv_bin(ws: &mut Ws) -> Vec<u8> {
    loop {
        match tokio::time::timeout(Duration::from_secs(3), ws.next()).await.expect("recv timed out") {
            Some(Ok(Message::Binary(b))) => return b.to_vec(),
            Some(Ok(Message::Text(_))) | Some(Ok(Message::Ping(_))) | Some(Ok(Message::Pong(_))) => {}
            other => panic!("expected binary, got {other:?}"),
        }
    }
}

#[derive(Default)]
struct Fake {
    pins: StdMutex<Vec<(String, String)>>,
    pin_ok: bool,
}

/// Echoes what a real host session would do, minimally: power, a pushed binary frame, binary echo.
struct FakeSession {
    out: Outbox,
}

impl Session for FakeSession {
    fn on_msg(&mut self, m: ClientMsg) -> Option<ServerMsg> {
        match m {
            ClientMsg::Power { a } if a == "lock" => Some(ServerMsg::Ok { of: "power".into() }),
            ClientMsg::Power { .. } => Some(ServerMsg::error("bad_request", "x", Some("power"))),
            ClientMsg::ThumbSub { on: true } => {
                let out = self.out.clone();
                tokio::spawn(async move { out.send_bin(vec![2, 0xFF, 0xD8]).await });
                None
            }
            m => Some(ServerMsg::error("unsupported", "x", Some(m.t()))),
        }
    }
    fn on_binary(&mut self, b: &[u8]) -> Option<ServerMsg> {
        Some(ServerMsg::Ok { of: format!("bin:{}", b.len()) })
    }
}

impl Handlers for Fake {
    fn pc_info(&self) -> BoxFut<PcInfo> {
        Box::pin(async {
            PcInfo { id: "pc".into(), name: "TEST-PC".into(), user: "u".into(), macs: vec![], os: "Windows".into(), sunshine: true }
        })
    }
    fn submit_pin(&self, pin: String, name: String) -> BoxFut<bool> {
        self.pins.lock().unwrap().push((pin, name));
        let ok = self.pin_ok;
        Box::pin(async move { ok })
    }
    fn caps(&self) -> Vec<String> {
        vec!["input".into(), "power".into(), "thumb".into()]
    }
    fn session(&self, _device: &str, out: Outbox) -> Box<dyn Session> {
        Box::new(FakeSession { out })
    }
}

struct Rig {
    handle: AgentHandle,
    store: Arc<StdMutex<Store>>,
    fake: Arc<Fake>,
    fp: String,
}

async fn rig(name: &str, idle: Duration, pin_ok: bool) -> Rig {
    let dir = std::env::temp_dir().join(format!("pcr-agent-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let id = Identity::load_or_create(&dir).unwrap();
    let fp = id.fingerprint_hex.clone();
    let store = Arc::new(StdMutex::new(Store::open(&dir).unwrap()));
    let fake = Arc::new(Fake { pin_ok, ..Default::default() });
    let handle = Agent::start(&id, store.clone(), fake.clone(), "127.0.0.1:0".parse().unwrap(), idle).await.unwrap();
    Rig { handle, store, fake, fp }
}

async fn hello(r: &Rig, token: &str) -> Ws {
    let mut ws = connect(r.handle.addr, &r.fp).await;
    send(&mut ws, json!({"t":"hello","v":1,"token":token,"device":"Pixel 8"})).await;
    ws
}

#[tokio::test]
async fn hello_with_valid_token_gets_welcome() {
    let r = rig("hello", Duration::from_secs(30), true).await;
    let (_, token) = r.store.lock().unwrap().add_device("Pixel 8").unwrap();
    let mut ws = hello(&r, &token).await;
    let w = recv(&mut ws).await.unwrap();
    assert_eq!(w["t"], "welcome");
    assert_eq!(w["v"], 1);
    assert_eq!(w["pc"]["name"], "TEST-PC");
    assert!(w["caps"].as_array().unwrap().contains(&json!("input")));
    send(&mut ws, json!({"t":"ping"})).await;
    assert_eq!(recv(&mut ws).await.unwrap(), json!({"t":"pong"}));
    send(&mut ws, json!({"t":"power","a":"warp"})).await;
    let e = recv(&mut ws).await.unwrap();
    assert_eq!((e["code"].as_str(), e["of"].as_str()), (Some("bad_request"), Some("power")));
    send(&mut ws, json!({"t":"teleport"})).await; // unknown types are ignored
    send(&mut ws, json!({"t":"media.sub","on":true})).await;
    assert_eq!(recv(&mut ws).await.unwrap()["code"], "unsupported");
}

#[tokio::test]
async fn hello_with_unknown_token_is_unauthorized() {
    let r = rig("unknown", Duration::from_secs(30), true).await;
    let mut ws = hello(&r, "nope").await;
    assert_eq!(recv(&mut ws).await.unwrap()["code"], "unauthorized");
    assert_eq!(recv(&mut ws).await, None);
}

#[tokio::test]
async fn first_message_not_auth_closes_unauthorized() {
    let r = rig("first", Duration::from_secs(30), true).await;
    let mut ws = connect(r.handle.addr, &r.fp).await;
    send(&mut ws, json!({"t":"ping"})).await;
    assert_eq!(recv(&mut ws).await.unwrap()["code"], "unauthorized");
    assert_eq!(recv(&mut ws).await, None);
}

#[tokio::test]
async fn pair_with_otp_returns_token_and_otp_is_consumed() {
    let r = rig("pair", Duration::from_secs(30), true).await;
    let mut events = r.handle.events();
    let otp = r.store.lock().unwrap().new_otp();
    let mut ws = connect(r.handle.addr, &r.fp).await;
    send(&mut ws, json!({"t":"pair","otp":otp,"device":"Pixel 8","pin":"4821"})).await;
    let p = recv(&mut ws).await.unwrap();
    assert_eq!(p["t"], "paired");
    assert_eq!(p["pc"]["name"], "TEST-PC");
    let token = p["token"].as_str().unwrap();
    assert_eq!(token.len(), 43);
    let dev = r.store.lock().unwrap().auth(token).unwrap();
    assert!(matches!(events.recv().await.unwrap(), Event::Paired { device } if device == dev));
    // authenticated: ping works on the same socket
    send(&mut ws, json!({"t":"ping"})).await;
    assert_eq!(recv(&mut ws).await.unwrap()["t"], "pong");
    assert_eq!(*r.fake.pins.lock().unwrap(), vec![("4821".to_string(), "Pixel 8".to_string())]);

    let mut again = connect(r.handle.addr, &r.fp).await;
    send(&mut again, json!({"t":"pair","otp":otp,"device":"Thief","pin":"0000"})).await;
    assert_eq!(recv(&mut again).await.unwrap()["code"], "bad_otp");
    assert_eq!(recv(&mut again).await, None);
}

#[tokio::test]
async fn pair_reports_pin_timeout() {
    let r = rig("pinfail", Duration::from_secs(30), false).await;
    let otp = r.store.lock().unwrap().new_otp();
    let mut ws = connect(r.handle.addr, &r.fp).await;
    send(&mut ws, json!({"t":"pair","otp":otp,"device":"Pixel 8","pin":"4821"})).await;
    assert_eq!(recv(&mut ws).await.unwrap()["t"], "paired");
    assert_eq!(recv(&mut ws).await.unwrap()["code"], "pin_timeout");
}

#[tokio::test]
async fn revoke_closes_only_that_device() {
    let r = rig("revoke", Duration::from_secs(30), true).await;
    let (a_id, a_tok) = r.store.lock().unwrap().add_device("A").unwrap();
    let (_, b_tok) = r.store.lock().unwrap().add_device("B").unwrap();
    let mut a = hello(&r, &a_tok).await;
    let mut b = hello(&r, &b_tok).await;
    assert_eq!(recv(&mut a).await.unwrap()["t"], "welcome");
    assert_eq!(recv(&mut b).await.unwrap()["t"], "welcome");
    assert_eq!(r.handle.connected().len(), 2);

    r.handle.revoke(&a_id);
    assert_eq!(recv(&mut a).await.unwrap()["code"], "revoked");
    assert_eq!(recv(&mut a).await, None);

    send(&mut b, json!({"t":"ping"})).await;
    assert_eq!(recv(&mut b).await.unwrap()["t"], "pong");

    let mut a2 = hello(&r, &a_tok).await;
    assert_eq!(recv(&mut a2).await.unwrap()["code"], "revoked");
}

#[tokio::test]
async fn idle_timeout_closes() {
    let r = rig("idle", Duration::from_millis(300), true).await;
    let (_, tok) = r.store.lock().unwrap().add_device("A").unwrap();
    let mut ws = hello(&r, &tok).await;
    assert_eq!(recv(&mut ws).await.unwrap()["t"], "welcome");
    let t0 = std::time::Instant::now();
    assert_eq!(recv(&mut ws).await, None);
    assert!(t0.elapsed() >= Duration::from_millis(250));
}

#[tokio::test]
async fn wrong_path_is_rejected() {
    let r = rig("path", Duration::from_secs(30), true).await;
    let cfg = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned(r.fp.clone())))
        .with_no_client_auth();
    let tcp = TcpStream::connect(r.handle.addr).await.unwrap();
    let tls = tokio_rustls::TlsConnector::from(Arc::new(cfg))
        .connect(ServerName::try_from("pc-remote.local").unwrap(), tcp)
        .await
        .unwrap();
    assert!(tokio_tungstenite::client_async("wss://x/other", tls).await.is_err());
}

#[tokio::test]
async fn pair_caps_device_name() {
    let r = rig("longname", Duration::from_secs(30), true).await;
    let otp = r.store.lock().unwrap().new_otp();
    let mut ws = connect(r.handle.addr, &r.fp).await;
    send(&mut ws, json!({"t":"pair","otp":otp,"device":"é".repeat(500),"pin":"1"})).await;
    let token = recv(&mut ws).await.unwrap()["token"].as_str().unwrap().to_string();
    let id = r.store.lock().unwrap().auth(&token).unwrap();
    assert_eq!(r.store.lock().unwrap().device_name(&id).unwrap().chars().count(), 40);
}

#[tokio::test]
async fn oversized_frame_before_auth_closes() {
    let r = rig("big", Duration::from_secs(30), true).await;
    let mut ws = connect(r.handle.addr, &r.fp).await;
    let _ = ws.send(Message::text("x".repeat(MAX_FRAME + 1))).await;
    assert_eq!(recv(&mut ws).await, None);
}

#[tokio::test]
async fn session_pushes_binary_and_receives_binary() {
    let r = rig("bin", Duration::from_secs(30), true).await;
    let (_, tok) = r.store.lock().unwrap().add_device("A").unwrap();
    let mut ws = hello(&r, &tok).await;
    let w = recv(&mut ws).await.unwrap();
    assert_eq!(w["caps"], json!(["input", "power", "thumb"]));
    send(&mut ws, json!({"t":"thumb.sub","on":true})).await;
    assert_eq!(recv_bin(&mut ws).await, vec![2, 0xFF, 0xD8]);
    ws.send(Message::binary(vec![1u8; 30])).await.unwrap();
    assert_eq!(recv(&mut ws).await.unwrap()["of"], "bin:30");
}

#[tokio::test]
async fn handle_sends_to_one_device() {
    let r = rig("sendto", Duration::from_secs(30), true).await;
    let (a_id, a_tok) = r.store.lock().unwrap().add_device("A").unwrap();
    let (_, b_tok) = r.store.lock().unwrap().add_device("B").unwrap();
    let mut a = hello(&r, &a_tok).await;
    let mut b = hello(&r, &b_tok).await;
    recv(&mut a).await;
    recv(&mut b).await;
    assert_eq!(r.handle.send(&a_id, ServerMsg::Ok { of: "hi".into() }), 1);
    assert_eq!(recv(&mut a).await.unwrap()["of"], "hi");
    send(&mut b, json!({"t":"ping"})).await;
    assert_eq!(recv(&mut b).await.unwrap()["t"], "pong", "B got nothing extra");
}
