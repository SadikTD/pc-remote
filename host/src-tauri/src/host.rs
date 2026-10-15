//! The real agent handlers: one `HostSession` per phone, composed from the feature modules.

use crate::agent::{BoxFut, Handlers, Outbox, Session};
use crate::proto::{ClientMsg, PcInfo, ServerMsg};
use crate::store::Store;
use crate::sunshine::{self, Sunshine};
use crate::{input, media, power, win};
use std::sync::{Arc, Mutex};

pub struct Host {
    pub store: Arc<Mutex<Store>>,
    pub sunshine: Arc<Sunshine>,
}

impl Host {
    pub fn new(store: Arc<Mutex<Store>>, sunshine: Arc<Sunshine>) -> Self {
        Host { store, sunshine }
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
        ["input", "power", "media"].map(String::from).to_vec()
    }

    fn session(&self, _device: &str, out: Outbox) -> Box<dyn Session> {
        Box::new(HostSession { input: input::Input::new(input::SendInputEmitter), out, media: None })
    }
}

pub struct HostSession {
    input: input::Input<input::SendInputEmitter>,
    out: Outbox,
    media: Option<tokio::task::JoinHandle<()>>,
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
            _ => err("unsupported", "not supported by this PC yet"),
        }
    }
}
