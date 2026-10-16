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

/// Next JSON message other than `remote`, or None once the server has closed.
async fn recv(ws: &mut Ws) -> Option<Value> {
    loop {
        match recv_any(ws).await {
            Some(v) if v["t"] == "remote" => {}
            v => return v,
        }
    }
}

async fn recv_any(ws: &mut Ws) -> Option<Value> {
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
    let remote = recv_any(&mut ws).await.unwrap();
    assert_eq!(remote["t"], "remote", "direct connections get the relay ticket too");
    assert_eq!(remote["ticket"], crate::store::b64url(&r.store.lock().unwrap().ticket(remote["dev"].as_str().unwrap())));
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

mod relay {
    use super::*;
    use crate::remote::rendezvous::{bin_env, close_env, text_env};
    use std::collections::HashMap;
    use tokio::net::TcpListener;
    use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
    use tokio_tungstenite::tungstenite::http::StatusCode;

    type Worker = WebSocketStream<TcpStream>;

    /// Accepts the host's next connection like the Worker: 401 when `refuse`, else the socket.
    async fn accept(l: &TcpListener, refuse: bool) -> (HashMap<String, String>, Option<Worker>) {
        let (tcp, _) = l.accept().await.unwrap();
        let mut q = HashMap::new();
        let cb = |req: &Request, resp: Response| -> Result<Response, ErrorResponse> {
            assert_eq!(req.uri().path(), "/v1/host");
            for kv in req.uri().query().unwrap_or_default().split('&') {
                let (k, v) = kv.split_once('=').unwrap();
                q.insert(k.to_string(), v.to_string());
            }
            if refuse {
                let mut e = ErrorResponse::new(None);
                *e.status_mut() = StatusCode::UNAUTHORIZED;
                Err(e)
            } else {
                Ok(resp)
            }
        };
        let ws = tokio_tungstenite::accept_hdr_async(tcp, cb).await.ok();
        (q, ws)
    }

    async fn env(w: &mut Worker) -> Value {
        loop {
            match tokio::time::timeout(Duration::from_secs(3), w.next()).await.expect("worker recv timed out") {
                Some(Ok(Message::Text(t))) => {
                    let mut v: Value = serde_json::from_str(&t).unwrap();
                    if let Some(m) = v["m"].as_str() {
                        v["m"] = serde_json::from_str(m).unwrap();
                    }
                    return v;
                }
                Some(Ok(Message::Binary(b))) => return json!({"bin": b.to_vec()}),
                other => panic!("worker got {other:?}"),
            }
        }
    }

    #[tokio::test]
    async fn relayed_phone_runs_the_agent_protocol() {
        let r = rig("relay", Duration::from_secs(30), true).await;
        let (dev, token) = r.store.lock().unwrap().add_device("Pixel 8").unwrap();
        let (gone, _) = r.store.lock().unwrap().add_device("old phone").unwrap();
        r.store.lock().unwrap().revoke(&gone).unwrap();
        r.store.lock().unwrap().set_relay_registered().unwrap(); // but the Worker has forgotten us
        let (pc, key) = {
            let s = r.store.lock().unwrap();
            (s.pc_id().to_string(), s.relay_key())
        };
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("ws://{}", l.local_addr().unwrap());
        tokio::spawn(crate::remote::rendezvous::run(r.handle.clone(), r.store.clone(), base));

        let (q, _) = accept(&l, true).await;
        assert!(!q.contains_key("register"), "registered keys aren't resent");
        let (q, w) = accept(&l, false).await;
        let mut w = w.unwrap();
        assert_eq!(q["pc"], pc);
        assert_eq!(q["sig"], crate::remote::host_sig(&key, &pc, q["ts"].parse().unwrap()), "after a 401, retried");
        assert_eq!(q["register"], crate::store::b64url(&key), "with the key");
        assert_eq!(env(&mut w).await, json!({"o":"revoke","dev":gone}), "revocations are repeated on connect");

        w.send(Message::text(r#"{"c":7,"o":"open"}"#)).await.unwrap();
        w.send(text_env(7, &json!({"t":"hello","v":1,"token":token,"device":"Pixel 8"}).to_string())).await.unwrap();
        let welcome = env(&mut w).await;
        assert_eq!((welcome["c"].as_u64(), welcome["m"]["t"].as_str()), (Some(7), Some("welcome")));
        let remote = env(&mut w).await;
        assert_eq!(remote["m"]["t"], "remote");
        assert_eq!(remote["m"]["dev"], json!(dev));
        assert_eq!(remote["m"]["url"], crate::remote::url().unwrap());
        assert_eq!(remote["m"]["ticket"], crate::store::b64url(&r.store.lock().unwrap().ticket(&dev)));

        w.send(text_env(7, r#"{"t":"power","a":"lock"}"#)).await.unwrap();
        assert_eq!(env(&mut w).await, json!({"c":7,"m":{"t":"ok","of":"power"}}));
        w.send(bin_env(7, &[1, 2, 3])).await.unwrap();
        assert_eq!(env(&mut w).await, json!({"c":7,"m":{"t":"ok","of":"bin:3"}}));
        w.send(text_env(7, r#"{"t":"thumb.sub","on":true}"#)).await.unwrap();
        assert_eq!(env(&mut w).await, json!({"bin":[0, 0, 0, 7, 2, 0xFF, 0xD8]}), "binary frames carry the cid");
        assert_eq!(r.handle.connected(), vec![dev.clone()]);

        // a second phone; the Worker closes it
        w.send(Message::text(r#"{"c":8,"o":"open"}"#)).await.unwrap();
        w.send(text_env(8, &json!({"t":"hello","v":1,"token":"nope","device":"x"}).to_string())).await.unwrap();
        assert_eq!(env(&mut w).await["m"]["code"], "unauthorized");
        assert_eq!(env(&mut w).await, json!({"c":8,"o":"close"}), "the host ends a failed session");

        // removing the phone on the PC: the Worker refuses it and the relayed session ends
        r.handle.revoke(&dev);
        let mut got = vec![env(&mut w).await, env(&mut w).await, env(&mut w).await];
        got.sort_by_key(|v| v.to_string());
        assert_eq!(got[0], json!({"c":7,"m":{"code":"revoked","msg":"this phone was removed on the PC","t":"error"}}));
        assert_eq!(got[1], json!({"c":7,"o":"close"}));
        assert_eq!(got[2], json!({"dev":dev,"o":"revoke"}));
        let _ = close_env; // (Worker → host close is covered by `envelopes`)
    }

    #[tokio::test]
    async fn worker_close_ends_the_session_and_reconnects() {
        let r = rig("relay-close", Duration::from_secs(30), true).await;
        let (dev, token) = r.store.lock().unwrap().add_device("Pixel 8").unwrap();
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        tokio::spawn(crate::remote::rendezvous::run(r.handle.clone(), r.store.clone(), format!("ws://{}", l.local_addr().unwrap())));
        let (q, w) = accept(&l, false).await;
        assert!(q.contains_key("register"), "first connection registers");
        let mut w = w.unwrap();
        w.send(Message::text(r#"{"c":1,"o":"open"}"#)).await.unwrap();
        w.send(text_env(1, &json!({"t":"hello","v":1,"token":token,"device":"Pixel 8"}).to_string())).await.unwrap();
        env(&mut w).await;
        env(&mut w).await;
        assert_eq!(r.handle.connected(), vec![dev]);
        w.send(close_env(1)).await.unwrap();
        assert_eq!(env(&mut w).await, json!({"c":1,"o":"close"}));
        assert!(r.handle.connected().is_empty());

        drop(w); // the Worker socket drops: the host comes back after 1 s, now without `register`
        let t0 = std::time::Instant::now();
        let (q, _) = accept(&l, false).await;
        assert!(!q.contains_key("register"));
        assert!(t0.elapsed() >= Duration::from_millis(900));
    }

    async fn next(p: &mut crate::remote::rendezvous::Ws) -> Value {
        match tokio::time::timeout(Duration::from_secs(5), p.next()).await.unwrap() {
            Some(Ok(Message::Text(t))) => serde_json::from_str(&t).unwrap(),
            other => panic!("phone got {other:?}"),
        }
    }

    /// Against the deployed Worker: a fresh PC registers, a phone reaches its agent through the
    /// relay, and a revoked phone is refused.
    #[tokio::test]
    #[ignore = "needs the internet and the deployed Worker"]
    async fn live_worker_relay() {
        let r = rig("relay-live", Duration::from_secs(30), true).await;
        let (dev, token) = r.store.lock().unwrap().add_device("Pixel 8").unwrap();
        let base = crate::remote::url().unwrap().to_string();
        tokio::spawn(crate::remote::rendezvous::run(r.handle.clone(), r.store.clone(), base.clone()));
        let (pc, ticket) = {
            let s = r.store.lock().unwrap();
            (s.pc_id().to_string(), s.ticket(&dev))
        };
        let phone_url = |ts: u64| {
            let sig = crate::tls::hex(&crate::store::hmac(&ticket, &format!("phone:{pc}:{dev}:{ts}")));
            format!("{base}/v1/phone?pc={pc}&dev={dev}&ts={ts}&sig={sig}")
        };
        let now = || std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
        for _ in 0..50 {
            if r.store.lock().unwrap().relay_registered() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(r.store.lock().unwrap().relay_registered(), "host registered with the Worker");

        let t0 = std::time::Instant::now();
        let mut p = crate::remote::rendezvous::connect(&phone_url(now())).await.unwrap();
        p.send(Message::text(json!({"t":"hello","v":1,"token":token,"device":"Pixel 8"}).to_string())).await.unwrap();
        assert_eq!(next(&mut p).await["t"], "welcome");
        eprintln!("hello → welcome through the Worker in {:?}", t0.elapsed());
        let remote = next(&mut p).await;
        assert_eq!(remote["ticket"], crate::store::b64url(&ticket));
        let t1 = std::time::Instant::now();
        p.send(Message::text(r#"{"t":"ping"}"#)).await.unwrap();
        assert_eq!(next(&mut p).await["t"], "pong");
        eprintln!("relayed ping RTT {:?}", t1.elapsed());

        r.handle.revoke(&dev);
        // whichever is first: the agent's `revoked` error, or the Worker closing with 4403
        match tokio::time::timeout(Duration::from_secs(5), p.next()).await.unwrap() {
            Some(Ok(Message::Text(t))) => assert!(t.contains("revoked"), "{t}"),
            Some(Ok(Message::Close(Some(c)))) => assert_eq!(u16::from(c.code), 4403),
            other => panic!("phone got {other:?}"),
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
        match crate::remote::rendezvous::connect(&phone_url(now())).await {
            Err(tokio_tungstenite::tungstenite::Error::Http(resp)) => assert_eq!(resp.status(), 403),
            other => panic!("revoked phone: {:?}", other.map(|_| ())),
        }
    }
}
