//! The real agent handlers: one `HostSession` per phone, composed from the feature modules.

use crate::agent::{BoxFut, Handlers, Outbox, Session};
use crate::proto::{ClientMsg, PcInfo, ServerMsg};
use crate::store::Store;
use crate::sunshine::{self, Sunshine};
use crate::files::{self, Incoming, Outgoing};
use crate::pad::{self, Pads};
use crate::{clip, input, media, power, win};
use std::sync::{Arc, Mutex};

pub struct Host {
    pub store: Arc<Mutex<Store>>,
    pub sunshine: Arc<Sunshine>,
    pub outgoing: Outgoing,
    /// None when the ViGEmBus driver isn't installed.
    pads: Option<Arc<dyn pad::Bus>>,
}

impl Host {
    pub fn new(store: Arc<Mutex<Store>>, sunshine: Arc<Sunshine>) -> Self {
        let pads = pad::ViGEm::connect().map(|b| Arc::new(b) as Arc<dyn pad::Bus>);
        Host { store, sunshine, outgoing: Outgoing::default(), pads }
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
        let mut c: Vec<String> = ["input", "power", "media", "files", "clip"].map(String::from).to_vec();
        if self.pads.is_some() {
            c.push("pad".into());
        }
        c
    }

    fn session(&self, _device: &str, out: Outbox) -> Box<dyn Session> {
        let dir = self.store.lock().unwrap().config().files_dir.clone();
        let pads = self.pads.clone().map(|bus| {
            let out = out.clone();
            Pads::new(bus, Box::new(move |id, lo, hi| {
                out.send(ServerMsg::Rumble { id, lo, hi });
            }))
        });
        Box::new(HostSession {
            input: input::Input::new(input::SendInputEmitter),
            out,
            media: None,
            store: self.store.clone(),
            incoming: Incoming::new(dir.into()),
            outgoing: self.outgoing.clone(),
            clip: None,
            sid: u64::from_le_bytes(crate::store::random::<8>()),
            pads,
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
    clip: Option<tokio::task::JoinHandle<()>>,
    /// Tags this phone's clipboard writes so they are not echoed back to it.
    sid: u64,
    pads: Option<Pads>,
}

impl HostSession {
    fn allow_clip(&self) -> bool {
        self.store.lock().unwrap().config().allow_clip
    }
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
        toggle(&mut self.clip, false, || unreachable!());
        if let Some(p) = self.pads.as_mut() {
            p.linger(std::time::Duration::from_secs(5));
        }
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
            ClientMsg::Pad { id, btn, lt, rt, lx, ly, rx, ry } => {
                let Some(p) = self.pads.as_mut() else { return err("unsupported", "gamepad driver (ViGEmBus) is not installed") };
                p.pad(id, &pad::PadState { btn, lt, rt, lx, ly, rx, ry }).err().and_then(|c| err(c, "gamepad error"))
            }
            ClientMsg::PadOff { id } => {
                if let Some(p) = self.pads.as_mut() {
                    p.off(id);
                }
                None
            }
            ClientMsg::ClipSub { on } => {
                if on && !self.allow_clip() {
                    return err("unsupported", "clipboard sharing is off on this PC");
                }
                let (out, store, sid) = (self.out.clone(), self.store.clone(), self.sid);
                toggle(&mut self.clip, on, || {
                    let mut rx = clip::hub().tx.subscribe();
                    tokio::spawn(async move {
                        loop {
                            match rx.recv().await {
                                Ok((origin, m)) if origin != Some(sid) && store.lock().unwrap().config().allow_clip => {
                                    if !out.send(m) {
                                        break;
                                    }
                                }
                                Ok(_) | Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                                Err(_) => break,
                            }
                        }
                    })
                });
                None
            }
            ClientMsg::Clip { mime, data } => {
                if !self.allow_clip() {
                    return err("unsupported", "clipboard sharing is off on this PC");
                }
                match clip::Content::from_msg(&mime, &data).filter(|c| clip::within_limit(c.mime(), c.bytes().len())) {
                    Some(c) => {
                        let sid = self.sid;
                        tokio::task::spawn_blocking(move || clip::hub().write(&c, sid));
                        None
                    }
                    None => err("bad_request", "unsupported or oversized clipboard data"),
                }
            }
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
