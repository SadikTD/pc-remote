//! The server ⇄ UI pipe: JSON lines over `\\.\pipe\pc-remote`.
//! - UI → server: `{"id":1,"cmd":"get_state","args":{…}}`
//! - server → UI: `{"id":1,"ok":…}` or `{"id":1,"err":"…"}`, and pushed events `{"ev":"devices",…}`.
//!
//! Only interactive users may open the pipe, and only from the server's own session.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::windows::named_pipe::{ClientOptions, NamedPipeServer, ServerOptions};
use tokio::sync::{broadcast, mpsc, oneshot};

pub const PIPE: &str = r"\\.\pipe\pc-remote";

pub type Handler = Arc<dyn Fn(String, Value) -> crate::agent::BoxFut<Result<Value, String>> + Send + Sync>;

/// SYSTEM and Administrators: full; interactive users: read/write.
const SDDL: &str = "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)";

fn create(name: &str, first: bool) -> std::io::Result<NamedPipeServer> {
    use windows::Win32::Security::Authorization::{ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1};
    use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
    let mut opts = ServerOptions::new();
    opts.first_pipe_instance(first).reject_remote_clients(true);
    let mut sd = PSECURITY_DESCRIPTOR::default();
    // SAFETY: the descriptor is allocated by Windows and stays alive (leaked once per instance, tiny)
    // for the pipe's lifetime; the attributes struct outlives the create call.
    unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(&windows::core::HSTRING::from(SDDL), SDDL_REVISION_1, &mut sd, None)
            .map_err(std::io::Error::other)?;
        let mut sa = SECURITY_ATTRIBUTES { nLength: size_of::<SECURITY_ATTRIBUTES>() as u32, lpSecurityDescriptor: sd.0, bInheritHandle: false.into() };
        opts.create_with_security_attributes_raw(name, &mut sa as *mut _ as _)
    }
}

fn same_session(p: &NamedPipeServer) -> bool {
    use std::os::windows::io::AsRawHandle;
    let mut s = u32::MAX;
    // SAFETY: a connected pipe handle and an out-param.
    let ok = unsafe { windows::Win32::System::Pipes::GetNamedPipeClientSessionId(windows::Win32::Foundation::HANDLE(p.as_raw_handle()), &mut s).is_ok() };
    ok && s == crate::win::session::current()
}

/// Serves UIs forever; `events` are pushed to every connected UI.
pub async fn serve(name: &str, handler: Handler, events: broadcast::Sender<Value>) -> std::io::Result<()> {
    let mut server = create(name, true)?;
    loop {
        server.connect().await?;
        let client = std::mem::replace(&mut server, create(name, false)?);
        if !same_session(&client) && !cfg!(test) {
            continue;
        }
        tokio::spawn(client_loop(client, handler.clone(), events.subscribe()));
    }
}

async fn client_loop(pipe: NamedPipeServer, handler: Handler, mut events: broadcast::Receiver<Value>) {
    let (rd, mut wr) = tokio::io::split(pipe);
    let mut lines = BufReader::new(rd).lines();
    let (tx, mut rx) = mpsc::unbounded_channel::<Value>();
    loop {
        tokio::select! {
            l = lines.next_line() => {
                let Ok(Some(l)) = l else { break };
                let Ok(req) = serde_json::from_str::<Value>(&l) else { continue };
                let (id, cmd, args) = (req["id"].clone(), req["cmd"].as_str().unwrap_or_default().to_string(), req["args"].clone());
                let (h, tx) = (handler.clone(), tx.clone());
                tokio::spawn(async move {
                    let r = h(cmd, args).await;
                    let _ = tx.send(match r { Ok(v) => json!({"id": id, "ok": v}), Err(e) => json!({"id": id, "err": e}) });
                });
            }
            Some(v) = rx.recv() => if write(&mut wr, &v).await.is_err() { break },
            e = events.recv() => match e {
                Ok(v) => if write(&mut wr, &v).await.is_err() { break },
                Err(broadcast::error::RecvError::Lagged(_)) => {}
                Err(_) => break,
            },
        }
    }
}

async fn write(w: &mut (impl AsyncWriteExt + Unpin), v: &Value) -> std::io::Result<()> {
    let mut s = v.to_string();
    s.push('\n');
    w.write_all(s.as_bytes()).await
}

/// The UI's side: reconnects on its own; [call] fails fast while the server is away.
#[derive(Clone)]
pub struct Client {
    out: Arc<Mutex<Option<mpsc::UnboundedSender<String>>>>,
    waiting: Arc<Mutex<HashMap<u64, oneshot::Sender<Result<Value, String>>>>>,
    next: Arc<AtomicU64>,
}

impl Client {
    /// Connects in the background; `on_event` gets every pushed event, plus `{"ev":"server","up":bool}`.
    pub fn start(name: &'static str, on_event: impl Fn(Value) + Send + Sync + 'static) -> Client {
        let c = Client { out: Arc::default(), waiting: Arc::default(), next: Arc::new(AtomicU64::new(1)) };
        let me = c.clone();
        tauri_free_spawn(async move {
            loop {
                if let Ok(pipe) = ClientOptions::new().open(name) {
                    let (rd, mut wr) = tokio::io::split(pipe);
                    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
                    *me.out.lock().unwrap() = Some(tx);
                    on_event(json!({"ev": "server", "up": true}));
                    let mut lines = BufReader::new(rd).lines();
                    loop {
                        tokio::select! {
                            l = lines.next_line() => {
                                let Ok(Some(l)) = l else { break };
                                let Ok(v) = serde_json::from_str::<Value>(&l) else { continue };
                                if let Some(id) = v["id"].as_u64() {
                                    if let Some(w) = me.waiting.lock().unwrap().remove(&id) {
                                        let _ = w.send(match v.get("ok") { Some(ok) => Ok(ok.clone()), None => Err(v["err"].as_str().unwrap_or("error").into()) });
                                    }
                                } else {
                                    on_event(v);
                                }
                            }
                            Some(s) = rx.recv() => if wr.write_all(s.as_bytes()).await.is_err() { break },
                        }
                    }
                    *me.out.lock().unwrap() = None;
                    me.waiting.lock().unwrap().clear(); // callers see "server unavailable"
                    on_event(json!({"ev": "server", "up": false}));
                }
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            }
        });
        c
    }

    pub async fn call(&self, cmd: &str, args: Value) -> Result<Value, String> {
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        self.waiting.lock().unwrap().insert(id, tx);
        let line = json!({"id": id, "cmd": cmd, "args": args}).to_string() + "\n";
        let sent = self.out.lock().unwrap().as_ref().is_some_and(|o| o.send(line).is_ok());
        if !sent {
            self.waiting.lock().unwrap().remove(&id);
            return Err("PC Remote service is not running".into());
        }
        match tokio::time::timeout(std::time::Duration::from_secs(30), rx).await {
            Ok(Ok(r)) => r,
            _ => {
                self.waiting.lock().unwrap().remove(&id);
                Err("PC Remote service is not responding".into())
            }
        }
    }
}

/// The client runs on whatever tokio runtime is current (the UI's Tauri runtime).
fn tauri_free_spawn(f: impl std::future::Future<Output = ()> + Send + 'static) {
    match tokio::runtime::Handle::try_current() {
        Ok(h) => drop(h.spawn(f)),
        Err(_) => drop(std::thread::spawn(move || tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(f))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn request_reply_and_events() {
        let name: &'static str = Box::leak(format!(r"\\.\pipe\pc-remote-test-{}", std::process::id()).into_boxed_str());
        let ev = broadcast::channel(8).0;
        let h: Handler = Arc::new(|cmd, args| Box::pin(async move {
            match cmd.as_str() {
                "echo" => Ok(args),
                _ => Err(format!("unknown {cmd}")),
            }
        }));
        tokio::spawn(serve(name, h, ev.clone()));
        let (etx, mut erx) = mpsc::unbounded_channel();
        let c = Client::start(name, move |v| { let _ = etx.send(v); });
        assert_eq!(erx.recv().await.unwrap()["up"], true);
        assert_eq!(c.call("echo", json!({"a": 1})).await, Ok(json!({"a": 1})));
        assert_eq!(c.call("nope", Value::Null).await, Err("unknown nope".into()));
        ev.send(json!({"ev": "devices"})).unwrap();
        assert_eq!(erx.recv().await.unwrap()["ev"], "devices");
    }
}
