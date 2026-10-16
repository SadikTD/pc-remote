use super::*;
use crate::prs::frame::assemble;
use std::collections::BTreeMap;
use std::sync::mpsc;

const KEY: [u8; 32] = [7; 32];

struct Phone {
    sock: UdpSocket,
    crypt: Crypt,
    seq: u32,
}

impl Phone {
    fn new(key: &[u8; 32], session: u32) -> Phone {
        let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
        sock.set_read_timeout(Some(Duration::from_millis(300))).unwrap();
        Phone { sock, crypt: Crypt::new(key, session), seq: 0 }
    }

    fn send(&mut self, to: SocketAddr, typ: Type, plain: &[u8]) {
        let h = Header { seq: self.seq, ..hdr(typ, 0) };
        self.seq += 1;
        self.sock.send_to(&self.crypt.seal(Dir::PhoneToHost, &h, plain), to).unwrap();
    }

    fn probe(&mut self, to: SocketAddr, t: u64) {
        let mut p = [0u8; 16];
        p[..8].copy_from_slice(&t.to_be_bytes());
        self.send(to, Type::Probe, &p);
    }

    fn recv(&self) -> Option<(Header, Vec<u8>)> {
        let mut b = [0u8; 2048];
        let (n, _) = self.sock.recv_from(&mut b).ok()?;
        self.crypt.open(Dir::HostToPhone, &b[..n])
    }
}

type Sink = mpsc::Receiver<ClientMsg>;

/// A host-side control loop on 127.0.0.1 with no media threads.
fn host(timeout: Duration) -> (Arc<Shared>, SocketAddr, Sink, std::thread::JoinHandle<()>) {
    let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = sock.local_addr().unwrap();
    let sh = Arc::new(Shared::new(sock, &KEY, 42, Target { kbps: 10000, fec: 15, notch: 0 }));
    let (tx, rx) = mpsc::channel();
    let s = sh.clone();
    let t = std::thread::spawn(move || {
        let mut br = Bitrate::new(10000, 3);
        control(&s, &mut |m| tx.send(m).unwrap(), &mut br, timeout)
    });
    (sh, addr, rx, t)
}

fn stop(sh: &Shared, t: std::thread::JoinHandle<()>) {
    sh.stop.store(true, Ordering::SeqCst);
    t.join().unwrap();
}

#[test]
fn follows_new_probe_address() {
    let (sh, host_addr, _, t) = host(TIMEOUT);
    let (mut a, mut b) = (Phone::new(&KEY, 42), Phone::new(&KEY, 42));
    a.probe(host_addr, 1234);
    let (h, p) = a.recv().expect("PROBE answered");
    assert_eq!(h.typ, Type::Probe);
    assert_eq!(&p[8..16], &1234u64.to_be_bytes(), "echoes the phone's t");
    assert_eq!(sh.peer(), Some(a.sock.local_addr().unwrap()));
    assert!(sh.idr.swap(false, Ordering::SeqCst), "first PROBE asks for a keyframe");

    // the phone moved networks: same session, new address
    b.seq = a.seq;
    b.probe(host_addr, 99);
    assert!(b.recv().is_some());
    assert_eq!(sh.peer(), Some(b.sock.local_addr().unwrap()));
    assert!(sh.send(hdr(Type::Cursor, 0), &[0; 5]));
    assert_eq!(b.recv().map(|(h, _)| h.typ), Some(Type::Cursor), "media follows the new address");
    assert!(a.recv().is_none(), "old address gets nothing");
    stop(&sh, t);
}

#[test]
fn ignores_unauthenticated_probe() {
    let (sh, host_addr, _, t) = host(TIMEOUT);
    let mut bad = Phone::new(&[9; 32], 42);
    bad.probe(host_addr, 1);
    assert!(bad.recv().is_none());
    assert_eq!(sh.peer(), None);

    // a replayed (old seq) PROBE from another address can't hijack the stream
    let mut a = Phone::new(&KEY, 42);
    a.seq = 10;
    a.probe(host_addr, 1);
    assert!(a.recv().is_some());
    let mut replay = Phone::new(&KEY, 42);
    replay.seq = 10;
    replay.probe(host_addr, 1);
    assert!(replay.recv().is_none());
    assert_eq!(sh.peer(), Some(a.sock.local_addr().unwrap()));
    stop(&sh, t);
}

#[test]
fn dedup_by_event_id() {
    let (sh, host_addr, rx, t) = host(TIMEOUT);
    let mut p = Phone::new(&KEY, 42);
    let ev = |i: u32| format!(r#"{{"i":{i},"t":"mouse.move","dx":{i},"dy":0}}"#);
    // each event goes out three times (newest plus the previous two), one packet lost, one reordered
    let packets = [vec![1], vec![2, 1], vec![4, 3, 2], vec![3, 2, 1], vec![5, 4, 3]];
    for ids in packets {
        let body = format!("[{}]", ids.iter().map(|&i| ev(i)).collect::<Vec<_>>().join(","));
        p.send(host_addr, Type::Input, body.as_bytes());
    }
    let mut got = Vec::new();
    while let Ok(m) = rx.recv_timeout(Duration::from_millis(500)) {
        got.push(m);
    }
    assert_eq!(got, (1..=5).map(|dx| ClientMsg::MouseMove { dx, dy: 0 }).collect::<Vec<_>>());
    stop(&sh, t);
}

#[test]
fn times_out_after_5s() {
    assert_eq!(TIMEOUT, Duration::from_secs(5));
    let (sh, host_addr, _, t) = host(Duration::from_millis(300));
    let mut p = Phone::new(&KEY, 42);
    let start = Instant::now();
    for _ in 0..4 {
        p.probe(host_addr, 1); // activity keeps it alive
        std::thread::sleep(Duration::from_millis(150));
    }
    assert!(!sh.stopped());
    t.join().unwrap();
    assert!(sh.stopped());
    let took = start.elapsed();
    assert!(took >= Duration::from_millis(700) && took < Duration::from_secs(2), "{took:?}");

    // BYE ends it at once
    let (sh, host_addr, _, t) = host(TIMEOUT);
    p.seq = 0;
    p.send(host_addr, Type::Bye, &[]);
    t.join().unwrap();
    assert!(sh.stopped());
}

#[test]
fn report_drives_bitrate_and_idr_is_rate_limited() {
    let (sh, host_addr, _, t) = host(TIMEOUT);
    let mut p = Phone::new(&KEY, 42);
    let mut report = [0u8; 16];
    report[4..6].copy_from_slice(&30u16.to_be_bytes()); // 3% loss
    report[10..12].copy_from_slice(&10u16.to_be_bytes());
    for _ in 0..3 {
        p.send(host_addr, Type::Report, &report);
    }
    p.send(host_addr, Type::Idr, &[]);
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(*sh.target.lock().unwrap(), Target { kbps: 7500, fec: 25, notch: 0 });
    assert!(sh.idr.swap(false, Ordering::SeqCst));
    p.send(host_addr, Type::Idr, &[]);
    std::thread::sleep(Duration::from_millis(100));
    assert!(!sh.idr.load(Ordering::SeqCst), "at most one keyframe per 250 ms");
    std::thread::sleep(Duration::from_millis(200));
    p.send(host_addr, Type::Idr, &[]);
    std::thread::sleep(Duration::from_millis(100));
    assert!(sh.idr.load(Ordering::SeqCst));
    stop(&sh, t);
}

/// Shards of one frame as they arrive.
struct Partial {
    k: usize,
    p: usize,
    shards: Vec<Option<Vec<u8>>>,
    first_us: u64,
}

/// H6: real capture → hardware encode → PRS on 127.0.0.1 → a receiver built on `prs` that
/// drops 10% of media packets, rebuilds frames through FEC and asks for keyframes like the phone.
#[test]
fn loopback_streams_real_frames() {
    let p = Params { w: 1280, h: 720, fps: 60, codecs: vec!["h264".into()], max_kbps: 10000, audio: true };
    let notify: Notify = Arc::new(|_| {});
    let (ready, session) = match StreamSession::start(p, IpAddr::from([127, 0, 0, 1]), notify, Box::new(|_| {})) {
        Ok(r) => r,
        Err(e) => return eprintln!("no stream on this PC: {e:?}"),
    };
    let ServerMsg::StreamReady { codec, w, h, port, session: sid, key, .. } = ready else { panic!("{ready:?}") };
    assert_eq!((codec.as_str(), w % 2, h % 2), ("h264", 0, 0));
    let key: [u8; 32] = STANDARD.decode(key).unwrap().try_into().unwrap();
    let host_addr: SocketAddr = ([127, 0, 0, 1], port).into();
    let mut ph = Phone::new(&key, sid);
    ph.sock.set_read_timeout(Some(Duration::from_millis(20))).unwrap();

    let mut rng = 0x2545F4914F6CDD1Du64;
    let mut drop10 = move || {
        rng ^= rng << 13;
        rng ^= rng >> 7;
        rng ^= rng << 17;
        rng.is_multiple_of(10)
    };
    let (mut partial, mut done) = (BTreeMap::<u32, Partial>::new(), BTreeMap::<u32, (bool, u64, u64)>::new());
    let (mut got_video, mut last_probe, mut last_report) = (false, Instant::now() - Duration::from_secs(1), Instant::now());
    let (mut media, mut dropped, mut idrs, mut audio) = (0u32, 0u32, 0u32, 0u32);
    let (mut decodable, mut need_key, mut next_id) = (Vec::new(), true, 0u32);
    let start = Instant::now();
    let mut first_frame_at = None;
    let mut last_idr = Instant::now();
    let (mut max_seq, mut received) = (0u32, 0u32);
    while decodable.len() < 120 && start.elapsed() < Duration::from_secs(15) {
        if last_probe.elapsed() >= Duration::from_millis(if got_video { 1000 } else { 200 }) {
            ph.probe(host_addr, now_us());
            last_probe = Instant::now();
        }
        if last_report.elapsed() >= Duration::from_millis(100) {
            let mut r = [0u8; 16];
            r[4..6].copy_from_slice(&((dropped * 1000 / media.max(1)) as u16).to_be_bytes());
            r[10..12].copy_from_slice(&1u16.to_be_bytes());
            ph.send(host_addr, Type::Report, &r);
            (media, dropped, last_report) = (0, 0, Instant::now());
        }
        let Some((hd, plain)) = ph.recv() else { continue };
        (max_seq, received) = (max_seq.max(hd.seq), received + 1);
        match hd.typ {
            Type::Audio => {
                assert!(plain.len() > 8);
                audio += 1;
            }
            Type::Video | Type::Fec => {
                got_video = true;
                media += 1;
                if drop10() {
                    dropped += 1;
                    continue;
                }
                if done.contains_key(&hd.frame_id) {
                    continue;
                }
                let f = partial.entry(hd.frame_id).or_insert_with(|| Partial {
                    k: hd.k as usize,
                    p: hd.flags as usize,
                    shards: vec![None; hd.count as usize],
                    first_us: now_us(),
                });
                f.shards[hd.frag as usize] = Some(plain);
                if f.shards.iter().flatten().count() >= f.k {
                    if let Some((fh, data)) = assemble(f.k, f.p, f.shards.clone()) {
                        assert_eq!(data.len(), fh.len as usize);
                        assert!(data.starts_with(&[0, 0, 0, 1]) || data.starts_with(&[0, 0, 1]), "Annex B");
                        done.insert(hd.frame_id, (fh.keyframe, fh.capture_us, f.first_us));
                        partial.remove(&hd.frame_id);
                    }
                }
            }
            _ => {}
        }
        // In-order delivery like the phone: a frame missing for 50 ms (or overtaken) is dropped,
        // and decoding resumes at the next keyframe, which is requested at most every 250 ms.
        while let Some(&(key, cap, at)) = done.get(&next_id) {
            first_frame_at.get_or_insert_with(Instant::now);
            if need_key && !key {
                // waiting for a keyframe: skip
            } else {
                need_key = false;
                decodable.push((next_id, key, at.saturating_sub(cap)));
            }
            next_id += 1;
        }
        let stale = partial.get(&next_id).is_some_and(|f| now_us() - f.first_us > 50_000) || done.keys().next_back().is_some_and(|&l| l > next_id + 2);
        if stale && !done.contains_key(&next_id) {
            partial.remove(&next_id);
            next_id += 1;
            need_key = true;
        }
        if need_key && got_video && last_idr.elapsed() >= Duration::from_millis(250) {
            ph.send(host_addr, Type::Idr, &[]);
            last_idr = Instant::now();
            idrs += 1;
        }
    }
    eprintln!("packets lost on the way (before the simulated drop): {}", (max_seq + 1).saturating_sub(received));
    eprintln!("assembled {} of {} frames through FEC; keyframes at {:?}", done.len(), done.keys().next_back().map_or(0, |l| l + 1), done.iter().filter(|d| d.1 .0).map(|d| *d.0).collect::<Vec<_>>());
    let secs = first_frame_at.map(|t| t.elapsed().as_secs_f64()).unwrap_or(1.0);
    let mut lat: Vec<u64> = decodable.iter().map(|d| d.2).collect();
    lat.sort();
    let p95 = lat.get(lat.len() * 95 / 100).copied().unwrap_or(0);
    eprintln!(
        "loopback: {} decodable frames in {secs:.2}s ({:.1} fps), {idrs} IDR requests, {audio} audio packets, capture→receive p50 {} µs p95 {p95} µs, {w}x{h}",
        decodable.len(),
        decodable.len() as f64 / secs,
        lat.get(lat.len() / 2).copied().unwrap_or(0)
    );
    assert!(decodable.len() >= 120, "only {} frames", decodable.len());
    assert!(decodable[0].1, "decoding starts at a keyframe");
    assert!(done.len() as f64 / secs > 40.0, "frame rate through FEC: {:.1} fps", done.len() as f64 / secs);
    assert!(p95 < 50_000, "capture→receive p95 {p95} µs");
    drop(session);
}
