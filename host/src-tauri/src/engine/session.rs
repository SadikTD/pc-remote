//! One PRS stream to one phone: the UDP socket, PROBE address learning, video/audio threads,
//! input, REPORT-driven bitrate and the 5 s timeout. Dropping it ends everything.

use super::bitrate::{notch_size, notches_below, Bitrate, Target};
use super::capture::{self, Capture, Dxgi};
use super::convert::{fit, Convert};
use super::encode::{codec_name, codecs_available, parse_codec, Encoder};
use super::input::Dedup;
use super::{audio, cursor, now_us, Com};
use crate::proto::{ClientMsg, ServerMsg};
use crate::prs::frame::{packetize, Codec, FrameHeader};
use crate::prs::{Crypt, Dir, Header, Type};
use base64::{engine::general_purpose::STANDARD, Engine};
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, Weak};
use std::time::{Duration, Instant};
use windows::Win32::Graphics::Direct3D11::ID3D11Texture2D;

pub const TIMEOUT: Duration = Duration::from_secs(5);
const IDR_EVERY: Duration = Duration::from_millis(250);
/// Hole punching (docs/remote-protocol.md): PROBE every phone candidate this often, for this long.
const PUNCH_EVERY: Duration = Duration::from_millis(100);
const PUNCH_FOR: Duration = Duration::from_secs(10);
const MAX_CANDS: usize = 16;

#[derive(Debug, Clone)]
pub struct Params {
    pub w: u32,
    pub h: u32,
    pub fps: u32,
    pub codecs: Vec<String>,
    pub max_kbps: u32,
    pub audio: bool,
    /// The phone's PRS addresses to punch towards.
    pub cands: Vec<SocketAddr>,
    /// The router's public IP when UPnP mapped the stream port.
    pub public: Option<IpAddr>,
}

/// The UDP port streams use when it's free (mapped on the router by `net`).
pub const STREAM_PORT: u16 = 47998;

/// Pushes an agent message to the phone (`cursor.shape`, a new `stream.ready`).
pub type Notify = Arc<dyn Fn(ServerMsg) + Send + Sync>;
/// Applies one deduplicated input event.
pub type InputSink = Box<dyn FnMut(ClientMsg) + Send>;

pub struct Shared {
    crypt: Crypt,
    sock: UdpSocket,
    seq: AtomicU32,
    peer: Mutex<Option<SocketAddr>>,
    stop: AtomicBool,
    idr: AtomicBool,
    target: Mutex<Target>,
    cands: Mutex<Vec<SocketAddr>>,
    punch_until: Mutex<Option<Instant>>,
    punch_every: Duration,
    punch_for: Duration,
}

fn hdr(typ: Type, frame_id: u32) -> Header {
    Header { typ, flags: 0, k: 0, seq: 0, frame_id, frag: 0, count: 0 }
}

impl Shared {
    pub fn new(sock: UdpSocket, key: &[u8; 32], session: u32, target: Target) -> Shared {
        Shared {
            crypt: Crypt::new(key, session),
            sock,
            seq: AtomicU32::new(0),
            peer: Mutex::new(None),
            stop: AtomicBool::new(false),
            idr: AtomicBool::new(false),
            target: Mutex::new(target),
            cands: Mutex::default(),
            punch_until: Mutex::default(),
            punch_every: PUNCH_EVERY,
            punch_for: PUNCH_FOR,
        }
    }

    /// Adds phone candidates and (re)starts the punching window.
    pub fn punch(&self, add: impl IntoIterator<Item = SocketAddr>) {
        let mut c = self.cands.lock().unwrap();
        for a in add {
            if c.len() < MAX_CANDS && !c.contains(&a) {
                c.push(a);
            }
        }
        if !c.is_empty() {
            *self.punch_until.lock().unwrap() = Some(Instant::now() + self.punch_for);
        }
    }

    /// Still looking for the phone: no authenticated PROBE yet and inside the window.
    fn punching(&self) -> bool {
        self.peer().is_none() && self.punch_until.lock().unwrap().is_some_and(|t| Instant::now() < t)
    }

    pub fn peer(&self) -> Option<SocketAddr> {
        *self.peer.lock().unwrap()
    }

    pub fn stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }

    /// Seals and sends to the phone's learned address; false before the first PROBE.
    pub fn send(&self, h: Header, plain: &[u8]) -> bool {
        self.peer().is_some_and(|to| self.send_to(to, h, plain))
    }

    fn send_to(&self, to: SocketAddr, mut h: Header, plain: &[u8]) -> bool {
        h.seq = self.seq.fetch_add(1, Ordering::Relaxed);
        self.sock.send_to(&self.crypt.seal(Dir::HostToPhone, &h, plain), to).is_ok()
    }

    /// One frame's packets, spread over at most ~4 ms so a burst doesn't overflow Wi-Fi queues.
    fn send_paced(&self, pkts: &[(Header, Vec<u8>)]) {
        let groups = if pkts.len() <= 8 { 1 } else { 4 };
        let t0 = Instant::now();
        for (g, chunk) in pkts.chunks(pkts.len().div_ceil(groups).max(1)).enumerate() {
            if let Some(wait) = (t0 + Duration::from_millis(g as u64)).checked_duration_since(Instant::now()) {
                std::thread::sleep(wait);
            }
            for (h, p) in chunk {
                self.send(*h, p);
            }
        }
    }
}

/// REPORT plaintext: (lossPermille, rttMs).
fn parse_report(p: &[u8]) -> Option<(u16, u16)> {
    (p.len() >= 16).then(|| (u16::from_be_bytes([p[4], p[5]]), u16::from_be_bytes([p[10], p[11]])))
}

fn probe(echo: u64) -> [u8; 16] {
    let mut p = [0u8; 16];
    p[..8].copy_from_slice(&now_us().to_be_bytes());
    p[8..].copy_from_slice(&echo.to_be_bytes());
    p
}

/// The receive loop: runs until BYE, `timeout` of silence (punching extends it), or `stop`.
pub fn control(sh: &Shared, input: &mut dyn FnMut(ClientMsg), br: &mut Bitrate, timeout: Duration) {
    let start = Instant::now();
    let (mut wait, mut next_punch) = (Duration::ZERO, start);
    let (mut last_rx, mut last_idr, mut probe_seq) = (Instant::now(), None::<Instant>, None::<u32>);
    let mut dedup = Dedup::default();
    let (mut input_pkts, mut input_log) = (0u64, Instant::now());
    let mut buf = [0u8; 2048];
    loop {
        let punching = sh.punching();
        if sh.stopped() || (last_rx.elapsed() >= timeout && !punching) {
            break;
        }
        let now = Instant::now();
        if punching && now >= next_punch {
            for &to in sh.cands.lock().unwrap().iter() {
                sh.send_to(to, hdr(Type::Probe, 0), &probe(0));
            }
            next_punch = now + sh.punch_every;
        }
        let w = if punching { next_punch.saturating_duration_since(now).max(Duration::from_millis(1)) } else { Duration::from_millis(100) };
        if w != wait {
            wait = w;
            let _ = sh.sock.set_read_timeout(Some(w));
        }
        // Errors include Windows' WSAECONNRESET after an ICMP "port unreachable": keep going.
        let Ok((n, from)) = sh.sock.recv_from(&mut buf) else { continue };
        if crate::remote::stun::is_stun(&buf[..n]) {
            continue; // a late STUN answer
        }
        let Some((h, p)) = sh.crypt.open(Dir::PhoneToHost, &buf[..n]) else { continue };
        last_rx = Instant::now();
        match h.typ {
            Type::Probe if p.len() >= 16 && probe_seq.is_none_or(|s| h.seq > s) => {
                probe_seq = Some(h.seq);
                let first = sh.peer.lock().unwrap().replace(from).is_none();
                if first {
                    sh.idr.store(true, Ordering::Relaxed);
                }
                sh.send(hdr(Type::Probe, 0), &probe(u64::from_be_bytes(p[..8].try_into().unwrap())));
            }
            Type::Input => {
                let evs = dedup.take(&p);
                if input_log.elapsed() >= Duration::from_secs(5) || input_pkts == 0 {
                    crate::log::line(&format!("stream: input packet {} bytes, {} new events (packets so far {input_pkts})", p.len(), evs.len()));
                    input_log = Instant::now();
                }
                input_pkts += 1;
                evs.into_iter().for_each(&mut *input)
            }
            Type::Report => {
                if let Some(t) = parse_report(&p).and_then(|(loss, rtt)| br.on_report(start.elapsed(), loss, rtt)) {
                    *sh.target.lock().unwrap() = t;
                }
            }
            Type::Idr if last_idr.is_none_or(|t| t.elapsed() >= IDR_EVERY) => {
                last_idr = Some(Instant::now());
                sh.idr.store(true, Ordering::Relaxed);
            }
            Type::Bye => break,
            _ => {}
        }
    }
    sh.stop.store(true, Ordering::Relaxed);
}

/// What `stream.ready` repeats on every resolution change.
#[derive(Clone)]
struct ReadyInfo {
    codec: Codec,
    fps: u32,
    port: u16,
    session: u32,
    key: String,
    cands: Vec<String>,
}

impl ReadyInfo {
    fn msg(&self, (w, h): (u32, u32)) -> ServerMsg {
        ServerMsg::StreamReady {
            codec: codec_name(self.codec).into(),
            w,
            h,
            fps: self.fps,
            port: self.port,
            session: self.session,
            key: self.key.clone(),
            cands: self.cands.clone(),
        }
    }
}

/// Capture → convert → encode → packetize, on its own thread (D3D/MF objects stay on it).
struct Video {
    dev: windows::Win32::Graphics::Direct3D11::ID3D11Device,
    ctx: windows::Win32::Graphics::Direct3D11::ID3D11DeviceContext,
    luid: i64,
    cap: Capture<Dxgi>,
    enc: Encoder,
    /// Stream size at notch 0.
    base: (u32, u32),
    fps: u32,
}

impl Video {
    #[allow(clippy::result_large_err)] // once per stream start
    fn open(p: &Params, kbps: u32) -> Result<Video, ServerMsg> {
        let err = |code: &str, msg: &str| ServerMsg::StreamError { code: code.into(), msg: msg.into() };
        let (dev, ctx, out, luid) = capture::device().ok_or_else(|| err("capture_failed", "no display to capture"))?;
        let first = Dxgi::open(&dev, &ctx, &out).ok_or_else(|| err("capture_failed", "the desktop can't be captured right now (locked?)"))?;
        let avail = codecs_available(luid);
        let codec = p
            .codecs
            .iter()
            .filter_map(|c| parse_codec(c))
            .find(|c| avail.contains(c))
            .ok_or_else(|| err("no_encoder", "this PC's GPU can't encode any codec the phone decodes"))?;
        use capture::Duplicator as _;
        let base = fit(first.size(), (p.w, p.h));
        let fps = p.fps.clamp(1, 240);
        let enc = Encoder::new(&dev, luid, codec, base, fps, kbps).map_err(|e| err("no_encoder", &e.message()))?;
        let (d, c) = (dev.clone(), ctx.clone());
        let cap = Capture::new(Some(first), move || Dxgi::open(&d, &c, &out));
        Ok(Video { dev, ctx, luid, cap, enc, base, fps })
    }

    fn run(mut self, sh: &Shared, ready: &ReadyInfo, notify: &Notify) {
        let interval = Duration::from_secs(1) / self.fps;
        let (mut last_enc, mut pending) = (Instant::now(), false);
        // Token bucket: at most `fps` frames per second on average, bursts of 2. A fixed frame
        // clock would beat against the display's own refresh and hold frames up to a frame.
        let (mut tokens, mut refill) = (2.0f64, Instant::now());
        let mut img: Option<ID3D11Texture2D> = None;
        let mut conv: Option<Convert> = None;
        let (mut frame_id, mut cap_us) = (0u32, 0u64);
        let mut applied = *sh.target.lock().unwrap();
        let mut last_cursor = None;
        while !sh.stopped() {
            let t = *sh.target.lock().unwrap();
            if t.notch != applied.notch {
                let size = notch_size(self.base.0, self.base.1, t.notch);
                match Encoder::new(&self.dev, self.luid, self.enc.codec(), size, self.fps, t.kbps) {
                    Ok(e) => {
                        self.enc = e;
                        conv = None;
                        notify(ready.msg(size));
                    }
                    Err(_) => break,
                }
            } else if t.kbps != applied.kbps {
                let _ = self.enc.set_bitrate(t.kbps);
            }
            applied = t;

            let now = Instant::now();
            tokens = (tokens + now.duration_since(refill).as_secs_f64() * self.fps as f64).min(2.0);
            refill = now;
            let due = if tokens >= 1.0 { now } else { now + interval.mul_f64(1.0 - tokens) };
            if !(pending && now >= due) {
                // DXGI's own timeouts run on the coarse system tick: sleep precisely, poll with 0.
                let got = if pending {
                    std::thread::sleep(due - now);
                    self.cap.next(Duration::ZERO) // the newest image at the deadline
                } else {
                    // nothing new for two frame times: send the last image again
                    poll(&mut self.cap, (last_enc + interval * 2).max(due), sh)
                };
                if std::mem::take(&mut self.cap.reinit) {
                    conv = None;
                    sh.idr.store(true, Ordering::Relaxed);
                }
                if let Some(f) = &got {
                    let desk = self.cap.size().unwrap_or(self.base);
                    if let Some(p) = &f.pointer {
                        let (sw, sh_) = self.enc.size;
                        let c = (p.x * sw as i32 / desk.0.max(1) as i32, p.y * sh_ as i32 / desk.1.max(1) as i32, p.visible);
                        if last_cursor != Some(c) {
                            last_cursor = Some(c);
                            sh.send(hdr(Type::Cursor, 0), &cursor::packet(c.0, c.1, c.2));
                        }
                    }
                    if let Some(png) = f.shape.as_ref().and_then(|s| cursor::png(s).map(|p| (p, s.hx, s.hy))) {
                        notify(ServerMsg::CursorShape { png: STANDARD.encode(&png.0), hx: png.1, hy: png.2 });
                    }
                    if f.tex.is_some() {
                        img = f.tex.clone();
                        cap_us = f.capture_us;
                        pending = true;
                    }
                }
                if Instant::now() < due || (got.is_some() && !pending) {
                    continue; // early, or only the pointer moved
                }
            }
            // Due: the newest image, or the last one again (keeps the phone's decoder flowing).
            let repeat = !pending;
            pending = false;
            last_enc = Instant::now();
            tokens -= 1.0;
            let Some(src) = img.as_ref().filter(|_| sh.peer().is_some()) else { continue };
            let Some(size) = self.cap.size() else { continue };
            if conv.is_none() {
                conv = Convert::new(&self.dev, &self.ctx, size, self.enc.size).ok();
            }
            let Some(nv12) = conv.as_mut().and_then(|c| c.convert(src).ok()) else { continue };
            let force = sh.idr.swap(false, Ordering::Relaxed);
            let Ok(outs) = self.enc.encode(&nv12, if repeat { now_us() } else { cap_us }, force) else { break };
            for e in outs {
                let fh = FrameHeader { len: e.data.len() as u32, capture_us: e.capture_us, keyframe: e.key, codec: self.enc.codec() };
                sh.send_paced(&packetize(frame_id, &fh, &e.data, t.fec as usize));
                frame_id = frame_id.wrapping_add(1);
            }
        }
        sh.stop.store(true, Ordering::Relaxed);
    }
}

/// The next capture event, or None at `until`.
fn poll(cap: &mut Capture<Dxgi>, until: Instant, sh: &Shared) -> Option<capture::GpuFrame> {
    loop {
        let f = cap.next(Duration::ZERO);
        if f.is_some() || Instant::now() >= until || sh.stopped() {
            return f;
        }
        std::thread::sleep(Duration::from_millis(1));
    }
}

/// `stream.ready` candidates: LAN addresses, the UPnP-mapped public one, the STUN-reflexive one.
fn host_cands(lan: &[String], port: u16, public: Option<IpAddr>, reflexive: Option<SocketAddr>) -> Vec<String> {
    let mut v: Vec<String> = lan.iter().map(|ip| format!("{ip}:{port}")).collect();
    // UPnP maps only the fixed port
    v.extend(public.filter(|_| port == STREAM_PORT).map(|ip| SocketAddr::new(ip, port).to_string()));
    v.extend(reflexive.map(|a| a.to_string()));
    let mut seen = std::collections::HashSet::new();
    v.retain(|c| seen.insert(c.clone()));
    v
}

static ACTIVE: Mutex<Weak<Shared>> = Mutex::new(Weak::new());

pub struct StreamSession {
    sh: Arc<Shared>,
    threads: Vec<std::thread::JoinHandle<()>>,
}

impl StreamSession {
    /// Opens capture and the encoder, binds the UDP socket on `bind` and starts streaming as
    /// soon as the phone's first PROBE arrives. Err is the `stream.error` to send.
    #[allow(clippy::result_large_err)] // once per stream start
    pub fn start(p: Params, bind: IpAddr, notify: Notify, mut input: InputSink) -> Result<(ServerMsg, StreamSession), ServerMsg> {
        let err = |code: &str, msg: &str| ServerMsg::StreamError { code: code.into(), msg: msg.into() };
        let mut active = ACTIVE.lock().unwrap();
        if active.upgrade().is_some_and(|s| !s.stopped()) {
            return Err(err("busy", "another phone is streaming from this PC"));
        }
        // The fixed port is the one UPnP maps for access from anywhere; any port works on the LAN.
        let sock = UdpSocket::bind((bind, STREAM_PORT))
            .or_else(|_| UdpSocket::bind((bind, 0)))
            .map_err(|e| err("capture_failed", &e.to_string()))?;
        let port = sock.local_addr().map_err(|e| err("capture_failed", &e.to_string()))?.port();
        let key: [u8; 32] = crate::store::random();
        let session = u32::from_be_bytes(crate::store::random());
        let sh = Arc::new(Shared::new(sock, &key, session, Bitrate::new(p.max_kbps, 0).target()));

        // The video thread opens its pipeline, reports the codec and size it got, then waits for
        // the full `stream.ready` (it repeats it on resolution changes).
        let (tx, rx) = std::sync::mpsc::sync_channel(1);
        let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel::<ReadyInfo>(1);
        let (vsh, vp, vn) = (sh.clone(), p.clone(), notify.clone());
        let video = std::thread::spawn(move || {
            let _com = Com::init();
            let kbps = vsh.target.lock().unwrap().kbps;
            match Video::open(&vp, kbps) {
                Ok(v) => {
                    let _ = tx.send(Ok((v.enc.codec(), v.fps, v.base)));
                    if let Ok(ready) = ready_rx.recv() {
                        v.run(&vsh, &ready, &vn);
                    }
                }
                Err(e) => {
                    let _ = tx.send(Err(e));
                }
            }
        });
        // While the encoder opens: this socket's public address, for the phone to punch towards.
        let reflexive = (!bind.is_loopback()).then(|| crate::remote::stun::public_addr(&sh.sock)).flatten();
        let (codec, fps, base) = match rx.recv() {
            Ok(Ok(r)) => r,
            Ok(Err(e)) => return Err(e),
            Err(_) => return Err(err("capture_failed", "video pipeline crashed")),
        };
        let lan = crate::commands::rank_ips(crate::win::sys::adapters().0);
        let cands = host_cands(&lan, port, p.public, reflexive);
        let ready = ReadyInfo { codec, fps, port, session, key: STANDARD.encode(key), cands };
        let _ = ready_tx.send(ready.clone());
        sh.punch(p.cands.iter().copied());
        // step-down notches follow the size actually streamed
        let mut br = Bitrate::new(p.max_kbps, notches_below(base.1).len() as u8);
        let mut threads = vec![video];
        let csh = sh.clone();
        threads.push(std::thread::spawn(move || control(&csh, &mut input, &mut br, TIMEOUT)));
        if p.audio {
            let ash = sh.clone();
            threads.push(std::thread::spawn(move || {
                let _com = Com::init();
                let mut n = 0u32;
                audio::run(audio::Wasapi::open, &ash.stop, |opus, us| {
                    let mut plain = Vec::with_capacity(8 + opus.len());
                    plain.extend_from_slice(&us.to_be_bytes());
                    plain.extend_from_slice(opus);
                    if ash.send(hdr(Type::Audio, n), &plain) {
                        n = n.wrapping_add(1);
                    }
                });
            }));
        }
        *active = Arc::downgrade(&sh);
        Ok((ready.msg(base), StreamSession { sh, threads }))
    }

    /// A phone candidate from `stream.cand`: punch towards it too, restarting the window.
    pub fn add_cand(&self, a: SocketAddr) {
        self.sh.punch([a]);
    }

    pub fn alive(&self) -> bool {
        !self.sh.stopped()
    }
}

impl Drop for StreamSession {
    fn drop(&mut self) {
        self.sh.stop.store(true, Ordering::Relaxed);
        self.sh.send(hdr(Type::Bye, 0), &[]);
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
    }
}

#[cfg(test)]
mod tests;
