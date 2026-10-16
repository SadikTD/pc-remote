//! RFC 8656 TURN client on the PRS stream socket (docs/remote-protocol.md, "TURN (both ends)").
//! The stream's receive loop owns the socket: it hands packets from the TURN server to
//! [`Turn::on_packet`], which completes pending requests or unwraps relayed data.

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::mpsc;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const COOKIE: u32 = 0x2112_A442;
const ALLOCATE: u16 = 0x0003;
const REFRESH: u16 = 0x0004;
const CREATE_PERMISSION: u16 = 0x0008;
const CHANNEL_BIND: u16 = 0x0009;
const SEND_INDICATION: u16 = 0x0016;
const DATA_INDICATION: u16 = 0x0017;
const USERNAME: u16 = 0x0006;
const MESSAGE_INTEGRITY: u16 = 0x0008;
const ERROR_CODE: u16 = 0x0009;
const CHANNEL_NUMBER: u16 = 0x000C;
const LIFETIME: u16 = 0x000D;
const XOR_PEER_ADDRESS: u16 = 0x0012;
const DATA: u16 = 0x0013;
const REALM: u16 = 0x0014;
const NONCE: u16 = 0x0015;
const XOR_RELAYED_ADDRESS: u16 = 0x0016;
const REQUESTED_TRANSPORT: u16 = 0x0019;

/// A decoded STUN message: type, transaction id, attributes in order.
#[derive(Debug, Clone, PartialEq)]
pub struct Msg {
    pub typ: u16,
    pub txid: [u8; 12],
    pub attrs: Vec<(u16, Vec<u8>)>,
}

impl Msg {
    pub fn new(typ: u16) -> Msg {
        Msg { typ, txid: crate::store::random(), attrs: vec![] }
    }

    pub fn add(mut self, t: u16, v: impl Into<Vec<u8>>) -> Msg {
        self.attrs.push((t, v.into()));
        self
    }

    pub fn get(&self, t: u16) -> Option<&[u8]> {
        self.attrs.iter().find(|a| a.0 == t).map(|a| a.1.as_slice())
    }

    fn is_success(&self) -> bool {
        self.typ & 0x0110 == 0x0100
    }

    fn error_code(&self) -> Option<u16> {
        self.get(ERROR_CODE).filter(|v| v.len() >= 4).map(|v| (v[2] & 7) as u16 * 100 + v[3] as u16)
    }

    fn header(&self, len: usize) -> Vec<u8> {
        let mut h = Vec::with_capacity(20);
        h.extend_from_slice(&self.typ.to_be_bytes());
        h.extend_from_slice(&(len as u16).to_be_bytes());
        h.extend_from_slice(&COOKIE.to_be_bytes());
        h.extend_from_slice(&self.txid);
        h
    }

    /// Encodes; with `key`, appends MESSAGE-INTEGRITY (HMAC-SHA1 with the length counting it).
    pub fn encode(&self, key: Option<&[u8]>) -> Vec<u8> {
        let mut body = Vec::new();
        for (t, v) in &self.attrs {
            body.extend(tlv(*t, v));
        }
        if let Some(k) = key {
            let mut signed = self.header(body.len() + 24);
            signed.extend_from_slice(&body);
            let tag = ring::hmac::sign(&ring::hmac::Key::new(ring::hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, k), &signed);
            body.extend(tlv(MESSAGE_INTEGRITY, tag.as_ref()));
        }
        let mut out = self.header(body.len());
        out.extend(body);
        out
    }

    pub fn decode(b: &[u8]) -> Option<Msg> {
        if b.len() < 20 || b[0] & 0xC0 != 0 || b[4..8] != COOKIE.to_be_bytes() {
            return None;
        }
        let len = u16::from_be_bytes([b[2], b[3]]) as usize;
        let end = 20 + len;
        if end > b.len() {
            return None;
        }
        let mut m = Msg { typ: u16::from_be_bytes([b[0], b[1]]), txid: b[8..20].try_into().ok()?, attrs: vec![] };
        let mut i = 20;
        while i + 4 <= end {
            let t = u16::from_be_bytes([b[i], b[i + 1]]);
            let l = u16::from_be_bytes([b[i + 2], b[i + 3]]) as usize;
            m.attrs.push((t, b.get(i + 4..i + 4 + l)?.to_vec()));
            i += 4 + l.div_ceil(4) * 4;
        }
        Some(m)
    }
}

fn tlv(t: u16, v: &[u8]) -> Vec<u8> {
    let mut o = Vec::with_capacity(4 + v.len() + 3);
    o.extend_from_slice(&t.to_be_bytes());
    o.extend_from_slice(&(v.len() as u16).to_be_bytes());
    o.extend_from_slice(v);
    o.resize(o.len().div_ceil(4) * 4, 0);
    o
}

pub fn xor_addr(a: SocketAddrV4) -> Vec<u8> {
    let c = COOKIE.to_be_bytes();
    let mut v = vec![0, 1];
    v.extend_from_slice(&(a.port() ^ (COOKIE >> 16) as u16).to_be_bytes());
    v.extend(a.ip().octets().iter().zip(c).map(|(x, y)| x ^ y));
    v
}

pub fn read_xor_addr(v: &[u8]) -> Option<SocketAddrV4> {
    if v.len() < 8 || v[1] != 1 {
        return None;
    }
    let c = COOKIE.to_be_bytes();
    let port = u16::from_be_bytes([v[2], v[3]]) ^ (COOKIE >> 16) as u16;
    let ip: [u8; 4] = std::array::from_fn(|i| v[4 + i] ^ c[i]);
    Some(SocketAddrV4::new(Ipv4Addr::from(ip), port))
}

/// Relays refuse private, loopback, link-local and carrier-NAT (100.64/10) peers.
pub fn is_public(a: &SocketAddrV4) -> bool {
    let ip = a.ip();
    let o = ip.octets();
    !(ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() || ip.is_multicast() || (o[0] == 100 && o[1] & 0xC0 == 64))
}

pub fn channel_data(ch: u16, b: &[u8]) -> Vec<u8> {
    let mut o = Vec::with_capacity(4 + b.len() + 3);
    o.extend_from_slice(&ch.to_be_bytes());
    o.extend_from_slice(&(b.len() as u16).to_be_bytes());
    o.extend_from_slice(b);
    o.resize(o.len().div_ceil(4) * 4, 0);
    o
}

/// MD5 (RFC 1321), only for the TURN long-term credential key.
pub fn md5(input: &[u8]) -> [u8; 16] {
    const S: [u32; 64] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23,
        4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ];
    let k: [u32; 64] = std::array::from_fn(|i| ((i as f64 + 1.0).sin().abs() * 4_294_967_296.0) as u32);
    let mut msg = input.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&((input.len() as u64).wrapping_mul(8)).to_le_bytes());
    let mut h: [u32; 4] = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476];
    for chunk in msg.chunks(64) {
        let m: [u32; 16] = std::array::from_fn(|i| u32::from_le_bytes(chunk[i * 4..i * 4 + 4].try_into().unwrap()));
        let [mut a, mut b, mut c, mut d] = h;
        for i in 0..64 {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let t = d;
            d = c;
            c = b;
            b = b.wrapping_add(a.wrapping_add(f).wrapping_add(k[i]).wrapping_add(m[g]).rotate_left(S[i]));
            a = t;
        }
        h = [h[0].wrapping_add(a), h[1].wrapping_add(b), h[2].wrapping_add(c), h[3].wrapping_add(d)];
    }
    let mut out = [0u8; 16];
    for (i, w) in h.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&w.to_le_bytes());
    }
    out
}

struct Auth {
    realm: Vec<u8>,
    nonce: Vec<u8>,
    key: Vec<u8>,
}

/// One TURN allocation. Requests block the calling (non-receive) thread until the receive
/// loop delivers the answer through [`Turn::on_packet`].
pub struct Turn {
    pub server: SocketAddr,
    user: String,
    pass: String,
    auth: Mutex<Option<Auth>>,
    pending: Mutex<HashMap<[u8; 12], mpsc::Sender<Msg>>>,
    channels: Mutex<HashMap<SocketAddrV4, u16>>,
    next_channel: Mutex<u16>,
    pub relayed: Mutex<Option<SocketAddrV4>>,
}

impl Turn {
    pub fn new(server: SocketAddr, user: String, pass: String) -> Turn {
        Turn {
            server,
            user,
            pass,
            auth: Mutex::default(),
            pending: Mutex::default(),
            channels: Mutex::default(),
            next_channel: Mutex::new(0x4000),
            relayed: Mutex::default(),
        }
    }

    fn transact(&self, sock: &UdpSocket, m: &Msg, key: Option<&[u8]>) -> Option<Msg> {
        let (tx, rx) = mpsc::channel();
        self.pending.lock().unwrap().insert(m.txid, tx);
        let bytes = m.encode(key);
        let t0 = Instant::now();
        let mut answer = None;
        for at in [0u64, 250, 750] {
            if let Some(w) = (t0 + Duration::from_millis(at)).checked_duration_since(Instant::now()) {
                if let Ok(r) = rx.recv_timeout(w) {
                    answer = Some(r);
                    break;
                }
            }
            let _ = sock.send_to(&bytes, self.server);
        }
        if answer.is_none() {
            answer = rx.recv_timeout((t0 + Duration::from_millis(1500)).saturating_duration_since(Instant::now())).ok();
        }
        self.pending.lock().unwrap().remove(&m.txid);
        answer
    }

    /// Signed request; refreshes realm/nonce on 401/438 and retries once.
    fn request(&self, sock: &UdpSocket, build: impl Fn() -> Msg) -> Option<Msg> {
        for _ in 0..2 {
            let mut m = build();
            let key = self.auth.lock().unwrap().as_ref().map(|a| {
                m.attrs.push((USERNAME, self.user.as_bytes().to_vec()));
                m.attrs.push((REALM, a.realm.clone()));
                m.attrs.push((NONCE, a.nonce.clone()));
                a.key.clone()
            });
            let r = self.transact(sock, &m, key.as_deref())?;
            if r.is_success() {
                return Some(r);
            }
            match (r.error_code(), r.get(NONCE)) {
                (Some(401 | 438), Some(nonce)) => {
                    let realm = r.get(REALM).map(<[u8]>::to_vec).or_else(|| self.auth.lock().unwrap().as_ref().map(|a| a.realm.clone())).unwrap_or_default();
                    let mut id = self.user.as_bytes().to_vec();
                    id.push(b':');
                    id.extend_from_slice(&realm);
                    id.push(b':');
                    id.extend_from_slice(self.pass.as_bytes());
                    *self.auth.lock().unwrap() = Some(Auth { realm, nonce: nonce.to_vec(), key: md5(&id).to_vec() });
                }
                (code, _) => {
                    crate::log::line(&format!("turn: request {:04x} failed ({code:?})", m.typ));
                    return None;
                }
            }
        }
        None
    }

    pub fn allocate(&self, sock: &UdpSocket) -> Option<SocketAddrV4> {
        let r = self.request(sock, || Msg::new(ALLOCATE).add(REQUESTED_TRANSPORT, [17u8, 0, 0, 0]))?;
        let a = r.get(XOR_RELAYED_ADDRESS).and_then(read_xor_addr)?;
        *self.relayed.lock().unwrap() = Some(a);
        Some(a)
    }

    pub fn refresh(&self, sock: &UdpSocket, lifetime: u32) {
        self.request(sock, || Msg::new(REFRESH).add(LIFETIME, lifetime.to_be_bytes()));
    }

    /// CreatePermission for the public peers, then a ChannelBind each.
    pub fn permit(&self, sock: &UdpSocket, peers: &[SocketAddrV4]) {
        let peers: Vec<SocketAddrV4> = peers.iter().copied().filter(is_public).collect();
        if peers.is_empty() {
            return;
        }
        self.request(sock, || peers.iter().fold(Msg::new(CREATE_PERMISSION), |m, p| m.add(XOR_PEER_ADDRESS, xor_addr(*p))));
        for p in peers {
            let ch = *self.channels.lock().unwrap().entry(p).or_insert_with(|| {
                let mut n = self.next_channel.lock().unwrap();
                *n += 1;
                *n - 1
            });
            let mut num = ch.to_be_bytes().to_vec();
            num.extend([0, 0]);
            self.request(sock, || Msg::new(CHANNEL_BIND).add(CHANNEL_NUMBER, num.clone()).add(XOR_PEER_ADDRESS, xor_addr(p)));
        }
    }

    /// The datagram to send to [`Turn::server`] so `data` reaches `to` through the relay.
    pub fn wrap(&self, to: SocketAddrV4, data: &[u8]) -> Vec<u8> {
        match self.channels.lock().unwrap().get(&to) {
            Some(ch) => channel_data(*ch, data),
            None => Msg::new(SEND_INDICATION).add(XOR_PEER_ADDRESS, xor_addr(to)).add(DATA, data).encode(None),
        }
    }

    /// A packet from the TURN server: relayed data → (peer, payload); a response → delivered, None.
    pub fn on_packet(&self, b: &[u8]) -> Option<(SocketAddrV4, Vec<u8>)> {
        match b.first()? {
            0x40..=0x7F if b.len() >= 4 => {
                let ch = u16::from_be_bytes([b[0], b[1]]);
                let len = u16::from_be_bytes([b[2], b[3]]) as usize;
                let peer = self.channels.lock().unwrap().iter().find(|(_, c)| **c == ch).map(|(p, _)| *p)?;
                Some((peer, b.get(4..4 + len)?.to_vec()))
            }
            0..=3 => {
                let m = Msg::decode(b)?;
                if m.typ == DATA_INDICATION {
                    return Some((m.get(XOR_PEER_ADDRESS).and_then(read_xor_addr)?, m.get(DATA)?.to_vec()));
                }
                if let Some(tx) = self.pending.lock().unwrap().remove(&m.txid) {
                    let _ = tx.send(m);
                }
                None
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn md5_vectors() {
        let hex = |b: [u8; 16]| crate::tls::hex(&b);
        assert_eq!(hex(md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex(md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(hex(md5(b"user:realm:pass")), "8493fbc53ba582fb4c044c456bdc40eb");
        assert_eq!(hex(md5(&[b'a'; 1000])), "cabe45dcc9ae5b66ba86600cca6b8ba8");
    }

    /// RFC 5769 §2.1: MESSAGE-INTEGRITY over the request, using the short-term password as key.
    #[test]
    fn message_integrity_matches_rfc5769() {
        let m = Msg {
            typ: 0x0001,
            txid: [0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae],
            attrs: vec![
                (0x8022, b"STUN test client".to_vec()),
                (0x0024, vec![0x6e, 0x00, 0x01, 0xff]),
                (0x8029, vec![0x93, 0x2f, 0xf9, 0xb1, 0x51, 0x26, 0x3b, 0x36]),
                (USERNAME, b"evtj:h6vY".to_vec()),
            ],
        };
        // RFC pads USERNAME with spaces; patch our zero padding to match before signing
        let mut enc = m.encode(None);
        let n = enc.len();
        enc[n - 3..].copy_from_slice(b"   ");
        let mut signed = enc.clone();
        signed[2..4].copy_from_slice(&((n - 20 + 24) as u16).to_be_bytes());
        let tag = ring::hmac::sign(&ring::hmac::Key::new(ring::hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, b"VOkJxbRl1RmTxUk/WvJxBt"), &signed);
        assert_eq!(crate::tls::hex(tag.as_ref()), "9aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2");
        // and encode() signs the same way over its own bytes
        let e = Msg::new(ALLOCATE).add(USERNAME, b"u".to_vec()).encode(Some(b"k"));
        let mi = Msg::decode(&e).unwrap().get(MESSAGE_INTEGRITY).unwrap().to_vec();
        let mut s = e[..e.len() - 24].to_vec();
        s[2..4].copy_from_slice(&((e.len() - 20) as u16).to_be_bytes());
        assert_eq!(mi, ring::hmac::sign(&ring::hmac::Key::new(ring::hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, b"k"), &s).as_ref());
    }

    #[test]
    fn addresses_channels_and_indications() {
        let a = SocketAddrV4::new(Ipv4Addr::new(104, 30, 145, 128), 47888);
        assert_eq!(read_xor_addr(&xor_addr(a)), Some(a));
        assert!(is_public(&a));
        assert!(!is_public(&SocketAddrV4::new(Ipv4Addr::new(192, 168, 0, 5), 1)));
        assert!(!is_public(&SocketAddrV4::new(Ipv4Addr::new(100, 64, 1, 1), 1)));
        assert_eq!(channel_data(0x4000, &[1, 2, 3, 4, 5]), vec![0x40, 0, 0, 5, 1, 2, 3, 4, 5, 0, 0, 0]);

        let t = Turn::new("127.0.0.1:3478".parse().unwrap(), "u".into(), "p".into());
        // no channel yet: Send indication carrying the peer and data
        let w = t.wrap(a, b"hi");
        let m = Msg::decode(&w).unwrap();
        assert_eq!((m.typ, m.get(XOR_PEER_ADDRESS).and_then(read_xor_addr), m.get(DATA)), (SEND_INDICATION, Some(a), Some(&b"hi"[..])));
        // a Data indication from the relay unwraps to (peer, payload)
        let di = Msg::new(DATA_INDICATION).add(XOR_PEER_ADDRESS, xor_addr(a)).add(DATA, b"yo".to_vec()).encode(None);
        assert_eq!(t.on_packet(&di), Some((a, b"yo".to_vec())));
        // with a channel, ChannelData both ways
        t.channels.lock().unwrap().insert(a, 0x4001);
        assert_eq!(t.wrap(a, b"x"), channel_data(0x4001, b"x"));
        assert_eq!(t.on_packet(&channel_data(0x4001, b"zz")), Some((a, b"zz".to_vec())));
        assert_eq!(t.on_packet(&channel_data(0x4002, b"zz")), None, "unknown channel");
    }
}
