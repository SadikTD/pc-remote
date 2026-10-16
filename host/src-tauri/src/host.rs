//! The real agent handlers: one `HostSession` per phone, composed from the feature modules.

use crate::agent::{BoxFut, Handlers, Outbox, Session};
use crate::proto::{ClientMsg, PcInfo, ServerMsg};
use crate::store::Store;
use crate::files::{self, Incoming, Outgoing};
use crate::pad::{self, Pads};
use crate::engine::session::{Params, StreamSession};
use crate::{clip, commands, input, media, net, power, thumb, win};
use std::sync::{Arc, Mutex};

pub struct Host {
    pub store: Arc<Mutex<Store>>,
    pub outgoing: Outgoing,
    /// None when the ViGEmBus driver isn't installed.
    pads: Option<Arc<dyn pad::Bus>>,
    lingering: pad::Lingering,
    pub net: Arc<Mutex<net::NetStatus>>,
}

impl Host {
    pub fn new(store: Arc<Mutex<Store>>) -> Self {
        let pads = pad::ViGEm::connect().map(|b| Arc::new(b) as Arc<dyn pad::Bus>);
        Host { store, outgoing: Outgoing::default(), pads, lingering: Default::default(), net: Arc::default() }
    }
}

impl Handlers for Host {
    fn pc_info(&self) -> BoxFut<PcInfo> {
        let id = self.store.lock().unwrap().pc_id().to_string();
        Box::pin(async move {
            PcInfo { id, name: win::sys::pc_name(), user: win::session::user_name(), macs: win::sys::adapters().1, os: win::sys::os_name() }
        })
    }

    fn caps(&self) -> Vec<String> {
        let mut c: Vec<String> = ["input", "power", "media", "files", "clip", "thumb", "net", "stream", "unlock", "fsbrowse"].map(String::from).to_vec();
        if self.pads.is_some() {
            c.push("pad".into());
        }
        c
    }

    fn locked(&self) -> bool {
        win::desk::is_locked()
    }

    fn session(&self, device: &str, out: Outbox) -> Box<dyn Session> {
        let dir = self.store.lock().unwrap().files_dir();
        let pads = self.pads.clone().map(|bus| {
            let out = out.clone();
            Pads::new(bus, Box::new(move |id, lo, hi| {
                out.send(ServerMsg::Rumble { id, lo, hi });
            }))
            .for_device(device, self.lingering.clone())
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
            pads: Arc::new(Mutex::new(pads)),
            thumb: None,
            net: self.net.clone(),
            stream: None,
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
    /// Shared with the stream's input thread (PRS INPUT carries `pad` too).
    pads: Arc<Mutex<Option<Pads>>>,
    thumb: Option<tokio::task::JoinHandle<()>>,
    net: Arc<Mutex<net::NetStatus>>,
    stream: Option<StreamSession>,
}

impl HostSession {
    fn allow_clip(&self) -> bool {
        self.store.lock().unwrap().config().allow_clip
    }

    /// Input arriving over PRS: the same handling as the agent's, on the stream's thread.
    fn stream_input(&self) -> crate::engine::session::InputSink {
        let pads = self.pads.clone();
        let mut inp = input::Input::new(input::SendInputEmitter);
        Box::new(move |m| match m {
            ClientMsg::Pad { id, btn, lt, rt, lx, ly, rx, ry } => {
                if let Some(p) = pads.lock().unwrap().as_mut() {
                    let _ = p.pad(id, &pad::PadState { btn, lt, rt, lx, ly, rx, ry });
                }
            }
            ClientMsg::PadOff { id } => {
                if let Some(p) = pads.lock().unwrap().as_mut() {
                    p.off(id);
                }
            }
            m => {
                let _ = inp.handle(&m);
            }
        })
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
        toggle(&mut self.thumb, false, || unreachable!());
        if let Some(p) = self.pads.lock().unwrap().as_mut() {
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
            | ClientMsg::MousePos { .. }
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
            ClientMsg::Net => {
                let st = self.net.lock().unwrap().clone();
                Some(ServerMsg::Net {
                    lan: commands::rank_ips(win::sys::adapters().0),
                    public: st.public,
                    anywhere: self.store.lock().unwrap().config().anywhere,
                    upnp: st.upnp,
                })
            }
            ClientMsg::ThumbSub { on } => {
                let out = self.out.clone();
                toggle(&mut self.thumb, on, || thumb::subscribe(out));
                None
            }
            ClientMsg::Pad { id, btn, lt, rt, lx, ly, rx, ry } => {
                let mut pads = self.pads.lock().unwrap();
                let Some(p) = pads.as_mut() else { return err("unsupported", "gamepad driver (ViGEmBus) is not installed") };
                p.pad(id, &pad::PadState { btn, lt, rt, lx, ly, rx, ry }).err().and_then(|c| err(c, "gamepad error"))
            }
            ClientMsg::PadOff { id } => {
                if let Some(p) = self.pads.lock().unwrap().as_mut() {
                    p.off(id);
                }
                None
            }
            ClientMsg::StreamStart { w, h, fps, codecs, max_kbps, audio, cands, display } => {
                self.stream = None; // a restart from this phone replaces its stream
                let cands = cands.iter().filter_map(|c| c.parse().ok()).collect();
                let st = self.net.lock().unwrap().clone();
                let public = st.public.filter(|_| st.upnp).and_then(|ip| ip.parse().ok());
                let (fps, max_kbps) = (if fps == 0 { 60 } else { fps }, if max_kbps == 0 { 20000 } else { max_kbps });
                let display = if display.is_empty() { self.store.lock().unwrap().config().display.clone() } else { display };
                let p = Params { w, h, fps, codecs, max_kbps, audio, cands, public, display };
                let out = self.out.clone();
                let notify: crate::engine::session::Notify = Arc::new(move |m| {
                    out.send(m);
                });
                let bind = std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED);
                match StreamSession::start(p, bind, notify, self.stream_input()) {
                    Ok((ready, s)) => {
                        self.stream = Some(s);
                        Some(ready)
                    }
                    Err(e) => Some(e),
                }
            }
            ClientMsg::StreamStop => {
                self.stream = None;
                None
            }
            ClientMsg::StreamDisplay { id } => {
                if let Some(s) = &self.stream {
                    s.switch_display(id);
                }
                None
            }
            ClientMsg::StreamCand { c } => {
                if let (Some(s), Ok(a)) = (&self.stream, c.parse::<std::net::SocketAddr>()) {
                    crate::log::line(&format!("stream: phone candidate {a}"));
                    s.add_cand(a);
                    // the phone relays: relay too, so both ends talk relay-to-relay (carrier NATs)
                    let (sh, store, out) = (s.shared(), self.store.clone(), self.out.clone());
                    std::thread::spawn(move || {
                        let Some((server, user, pass)) = crate::remote::turn_server(&store) else { return };
                        if let Some(r) = sh.start_turn(server, user, pass) {
                            out.send(ServerMsg::StreamCand { c: r.to_string() });
                        }
                    });
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
            ClientMsg::FileCancel { id } => {
                self.incoming.cancel(&id);
                self.outgoing.lock().unwrap().remove(&id);
                None
            }
            ClientMsg::FsList { path } => {
                if !self.store.lock().unwrap().config().allow_files {
                    return err("unsupported", "file transfer is off on this PC");
                }
                match files::list(&path, &win::session::user_profile()) {
                    Ok(entries) => Some(ServerMsg::FsList { path, entries }),
                    Err(e) => err("denied", &e.to_string()),
                }
            }
            ClientMsg::FsGet { path } => {
                if !self.store.lock().unwrap().config().allow_files {
                    return err("unsupported", "file transfer is off on this PC");
                }
                let (outgoing, out) = (self.outgoing.clone(), self.out.clone());
                tokio::task::spawn_blocking(move || match files::offer_tree(&outgoing, std::path::Path::new(&path)) {
                    Ok(offers) => offers.into_iter().for_each(|m| {
                        out.send(m);
                    }),
                    Err(e) => {
                        out.send(ServerMsg::error("denied", &e.to_string(), Some("fs.get")));
                    }
                });
                None
            }
            ClientMsg::Unlock { secret } => {
                let out = self.out.clone();
                tokio::task::spawn_blocking(move || {
                    let ok = crate::unlock::unlock(&secret);
                    crate::log::line(&format!("unlock: {}", if ok { "unlocked" } else { "still locked" }));
                    out.send(ServerMsg::UnlockResult { ok });
                });
                None
            }
            _ => err("unsupported", "not supported by this PC yet"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::toggle;
    use std::time::Duration;

    #[tokio::test]
    async fn subscription_starts_restarts_and_stops() {
        let mut slot = None;
        toggle(&mut slot, true, || tokio::spawn(tokio::time::sleep(Duration::from_secs(60))));
        let first = slot.as_ref().unwrap().id();
        toggle(&mut slot, true, || tokio::spawn(tokio::time::sleep(Duration::from_secs(60))));
        assert_ne!(slot.as_ref().unwrap().id(), first, "re-subscribing replaces the timer, never doubles it");
        let h = slot.take().unwrap();
        let abort = h.abort_handle();
        slot = Some(h);
        toggle(&mut slot, false, || unreachable!());
        assert!(slot.is_none());
        tokio::task::yield_now().await;
        assert!(abort.is_finished(), "unsubscribing stops the timer");
    }
}
