//! The real agent handlers: one `HostSession` per phone, composed from the feature modules.

use crate::agent::{BoxFut, Handlers, Outbox, Session};
use crate::proto::{ClientMsg, PcInfo, ServerMsg};
use crate::store::Store;
use crate::sunshine::{self, Sunshine};
use crate::files::{self, Incoming, Outgoing};
use crate::{input, media, power, win};
use std::sync::{Arc, Mutex};

pub struct Host {
    pub store: Arc<Mutex<Store>>,
    pub sunshine: Arc<Sunshine>,
    pub outgoing: Outgoing,
}

impl Host {
    pub fn new(store: Arc<Mutex<Store>>, sunshine: Arc<Sunshine>) -> Self {
        Host { store, sunshine, outgoing: Outgoing::default() }
    }
}

impl Handlers for Host {
    fn pc_info(&self) -> BoxFut<PcInfo> {
        let (sun, id) = (self.sunshine.clone(), self.store.lock().unwrap().pc_id().to_string());
        Box::pin(async move {
            let running = sun.status().await.running;
            if !running {
                // Event-driven recovery: a phone connected and Sunshine is down, so start it.
                tokio::task::spawn_blocking(sunshine::start_service);
            }
            PcInfo {
                id,
                name: win::sys::pc_name(),
                user: win::sys::user_name(),
                macs: win::sys::adapters().1,
                os: win::sys::os_name(),
                sunshine: running,
            }
        })
    }

    fn submit_pin(&self, pin: String, name: String) -> BoxFut<bool> {
        let sun = self.sunshine.clone();
        Box::pin(async move { sun.submit_pin(&pin, &name).await.is_ok() })
    }

    fn caps(&self) -> Vec<String> {
        ["input", "power", "media", "files"].map(String::from).to_vec()
    }

    fn session(&self, _device: &str, out: Outbox) -> Box<dyn Session> {
        let dir = self.store.lock().unwrap().config().files_dir.clone();
        Box::new(HostSession {
            input: input::Input::new(input::SendInputEmitter),
            out,
            media: None,
            store: self.store.clone(),
            incoming: Incoming::new(dir.into()),
            outgoing: self.outgoing.clone(),
        })
    }
}

pub struct HostSession {
    input: input::Input<input::SendInputEmitter>,
    out: Outbox,
    media: Option<tokio::task::JoinHandle<()>>,
    store: Arc<Mutex<Store>>,
    incoming: Incoming,
    outgoing: Outgoing,
}

/// Starts or stops a per-session subscription task.
fn toggle(slot: &mut Option<tokio::task::JoinHandle<()>>, on: bool, start: impl FnOnce() -> tokio::task::JoinHandle<()>) {
    if let Some(t) = slot.take() {
        t.abort();
    }
    if on {
        *slot = Some(start());
    }
}

impl Drop for HostSession {
    fn drop(&mut self) {
        toggle(&mut self.media, false, || unreachable!());
    }
}

impl Session for HostSession {
    fn on_binary(&mut self, b: &[u8]) -> Option<ServerMsg> {
        self.incoming.chunk(b)
    }

    fn on_msg(&mut self, m: ClientMsg) -> Option<ServerMsg> {
        let of = m.t();
        let err = |code: &str, msg: &str| Some(ServerMsg::error(code, msg, Some(of)));
        match m {
            ClientMsg::MouseMove { .. }
            | ClientMsg::MouseButton { .. }
            | ClientMsg::MouseScroll { .. }
            | ClientMsg::Key { .. }
            | ClientMsg::Text { .. } => self.input.handle(&m).err().and_then(|c| err(c, "invalid input")),
            ClientMsg::Power { a } => match power::power(&a) {
                Ok(()) => Some(ServerMsg::Ok { of: of.into() }),
                Err(c) => err(c, "power action failed"),
            },
            ClientMsg::MediaSub { on } => {
                let out = self.out.clone();
                toggle(&mut self.media, on, || media::subscribe(out));
                None
            }
            ClientMsg::MediaCmd { c, v } => match media::Cmd::parse(&c, v) {
                Ok(cmd) => {
                    tokio::task::spawn_blocking(move || media::run(cmd));
                    None
                }
                Err(code) => err(code, "unknown media command"),
            },
            ClientMsg::FileBegin { id, name, size } => Some(if self.store.lock().unwrap().config().allow_files {
                self.incoming.begin(&id, &name, size)
            } else {
                ServerMsg::FileError { id, code: "denied".into() }
            }),
            ClientMsg::FileEnd { id, sha256 } => Some(self.incoming.end(&id, &sha256)),
            ClientMsg::FileAccept { id, offset } => {
                let Some(path) = self.outgoing.lock().unwrap().remove(&id) else {
                    return Some(ServerMsg::FileError { id, code: "denied".into() });
                };
                let out = self.out.clone();
                tokio::task::spawn_blocking(move || {
                    let Some(key) = files::parse_uuid(&id) else { return };
                    match files::stream(&path, &key, offset, &mut |f| out.send_bin_blocking(f)) {
                        Ok(sha256) => out.send(ServerMsg::FileEnd { id, sha256 }),
                        Err(_) => out.send(ServerMsg::FileError { id, code: "denied".into() }),
                    };
                });
                None
            }
            ClientMsg::FileReject { id } => {
                self.outgoing.lock().unwrap().remove(&id);
                None
            }
            _ => err("unsupported", "not supported by this PC yet"),
        }
    }
}
