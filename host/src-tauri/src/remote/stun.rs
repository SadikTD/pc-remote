//! RFC 5389 Binding request from the PRS socket: this PC's public (server-reflexive) address.

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant};

pub const SERVER: &str = "stun.cloudflare.com:3478";
const COOKIE: [u8; 4] = [0x21, 0x12, 0xA4, 0x42];
/// Retransmits at 0, 250 and 750 ms; gives up at 1.5 s.
const SENDS_MS: [u64; 3] = [0, 250, 750];
const GIVE_UP: Duration = Duration::from_millis(1500);

/// STUN and TURN packets start with 0x00–0x03; PRS with 0x1?.
pub fn is_stun(b: &[u8]) -> bool {
    b.first().is_some_and(|&x| x < 4)
}

pub fn request(txid: &[u8; 12]) -> [u8; 20] {
    let mut r = [0u8; 20];
    r[1] = 0x01; // Binding request, no attributes
    r[4..8].copy_from_slice(&COOKIE);
    r[8..].copy_from_slice(txid);
    r
}

/// The mapped address from a Binding success response to `txid`: XOR-MAPPED-ADDRESS, else MAPPED-ADDRESS (IPv4).
pub fn parse(b: &[u8], txid: &[u8; 12]) -> Option<SocketAddrV4> {
    if b.len() < 20 || b[..2] != [0x01, 0x01] || b[4..8] != COOKIE || &b[8..20] != txid {
        return None;
    }
    let end = (20 + u16::from_be_bytes([b[2], b[3]]) as usize).min(b.len());
    let (mut i, mut mapped) = (20, None);
    while i + 4 <= end {
        let typ = u16::from_be_bytes([b[i], b[i + 1]]);
        let len = u16::from_be_bytes([b[i + 2], b[i + 3]]) as usize;
        let v = b.get(i + 4..i + 4 + len)?;
        if (typ == 0x0020 || typ == 0x0001) && len >= 8 && v[1] == 0x01 {
            let mut port = u16::from_be_bytes([v[2], v[3]]);
            let mut ip = [v[4], v[5], v[6], v[7]];
            if typ == 0x0020 {
                port ^= 0x2112;
                ip.iter_mut().zip(COOKIE).for_each(|(a, c)| *a ^= c);
                return Some(SocketAddrV4::new(Ipv4Addr::from(ip), port));
            }
            mapped = Some(SocketAddrV4::new(Ipv4Addr::from(ip), port));
        }
        i += 4 + len.div_ceil(4) * 4;
    }
    mapped
}

/// Asks `server` which address `sock` maps to. Blocks up to 1.5 s; changes the socket's read timeout.
pub fn reflexive(sock: &UdpSocket, server: SocketAddr) -> Option<SocketAddr> {
    let txid: [u8; 12] = crate::store::random();
    let req = request(&txid);
    let t0 = Instant::now();
    let (mut sent, mut buf) = (0, [0u8; 1500]);
    while t0.elapsed() < GIVE_UP {
        if sent < SENDS_MS.len() && t0.elapsed() >= Duration::from_millis(SENDS_MS[sent]) {
            let _ = sock.send_to(&req, server);
            sent += 1;
        }
        let until = SENDS_MS.get(sent).map_or(GIVE_UP, |&ms| Duration::from_millis(ms));
        let _ = sock.set_read_timeout(Some(until.saturating_sub(t0.elapsed()).max(Duration::from_millis(1))));
        if let Ok((n, from)) = sock.recv_from(&mut buf) {
            if let Some(a) = (from == server).then(|| parse(&buf[..n], &txid)).flatten() {
                return Some(a.into());
            }
        }
    }
    None
}

/// [reflexive] against the public server (IPv4).
pub fn public_addr(sock: &UdpSocket) -> Option<SocketAddr> {
    let server = SERVER.to_socket_addrs().ok()?.find(SocketAddr::is_ipv4)?;
    reflexive(sock, server)
}

#[cfg(test)]
mod tests {
    use super::*;

    // RFC 5769 §2.2: sample IPv4 response, transaction id b7e7a701 bc34d686 fa87dfae.
    const TXID: [u8; 12] = [0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae];
    const RESPONSE: [u8; 80] = [
        0x01, 0x01, 0x00, 0x3c, 0x21, 0x12, 0xa4, 0x42, 0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae,
        0x80, 0x22, 0x00, 0x0b, 0x74, 0x65, 0x73, 0x74, 0x20, 0x76, 0x65, 0x63, 0x74, 0x6f, 0x72, 0x20, 0x00, 0x20, 0x00, 0x08,
        0x00, 0x01, 0xa1, 0x47, 0xe1, 0x12, 0xa6, 0x43, 0x00, 0x08, 0x00, 0x14, 0x2b, 0x91, 0xf5, 0x99, 0xfd, 0x9e, 0x90, 0xc3,
        0x8c, 0x74, 0x89, 0xf9, 0x2a, 0xf9, 0xba, 0x53, 0xf0, 0x6b, 0xe7, 0xd7, 0x80, 0x28, 0x00, 0x04, 0xc0, 0x7d, 0x4c, 0x96,
    ];

    #[test]
    fn rfc5769_ipv4_response() {
        assert_eq!(parse(&RESPONSE, &TXID), Some("192.0.2.1:32853".parse().unwrap()));
        assert_eq!(parse(&RESPONSE, &[0; 12]), None, "other transaction");
        assert_eq!(parse(&RESPONSE[..19], &TXID), None);
        assert!(is_stun(&RESPONSE) && !is_stun(&[0x10, 0]) && !is_stun(&[]));
    }

    #[test]
    fn request_header_matches_rfc5769_layout() {
        // §2.1 sample request starts 00 01 <len> 21 12 a4 42 <txid>; ours carries no attributes.
        let r = request(&TXID);
        assert_eq!(&r[..8], &[0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xa4, 0x42]);
        assert_eq!(&r[8..], &TXID);
        assert_eq!(parse(&r, &TXID), None, "a request is not a response");
    }

    #[test]
    fn falls_back_to_mapped_address() {
        let mut b = RESPONSE[..20].to_vec();
        b[3] = 12;
        b.extend_from_slice(&[0x00, 0x01, 0x00, 0x08, 0x00, 0x01, 0x1f, 0x90, 203, 0, 113, 5]);
        assert_eq!(parse(&b, &TXID), Some("203.0.113.5:8080".parse().unwrap()));
    }

    #[test]
    fn retries_until_answered() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = server.local_addr().unwrap();
        let t = std::thread::spawn(move || {
            let mut b = [0u8; 100];
            let t0 = Instant::now();
            let (_, _) = server.recv_from(&mut b).unwrap(); // dropped: the first try is lost
            let (n, from) = server.recv_from(&mut b).unwrap();
            let gap = t0.elapsed();
            assert_eq!(n, 20);
            let mut resp = RESPONSE;
            resp[8..20].copy_from_slice(&b[8..20]);
            server.send_to(&resp, from).unwrap();
            gap
        });
        let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
        assert_eq!(reflexive(&sock, addr), Some("192.0.2.1:32853".parse().unwrap()));
        let gap = t.join().unwrap();
        assert!(gap >= Duration::from_millis(200) && gap < Duration::from_millis(450), "second try at ~250 ms, got {gap:?}");
    }

    #[test]
    fn gives_up_after_1_5_s() {
        let silent = UdpSocket::bind("127.0.0.1:0").unwrap();
        let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
        let t0 = Instant::now();
        assert_eq!(reflexive(&sock, silent.local_addr().unwrap()), None);
        assert!(t0.elapsed() >= GIVE_UP && t0.elapsed() < Duration::from_millis(1800));
    }

    #[test]
    #[ignore = "needs the internet"]
    fn live_cloudflare_stun() {
        let sock = UdpSocket::bind("0.0.0.0:0").unwrap();
        let a = public_addr(&sock);
        // Same socket, another server: a different port means a symmetric NAT (punching unlikely).
        let google = "stun.l.google.com:19302".to_socket_addrs().unwrap().find(SocketAddr::is_ipv4).unwrap();
        eprintln!("reflexive address: {a:?}, via another server: {:?}", reflexive(&sock, google));
        assert!(a.is_some());
    }
}
