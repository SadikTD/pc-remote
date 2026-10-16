//! Tests for all of PRS, plus the shared cross-language vectors in docs/prs-vectors.json.

use super::*;
use serde_json::{json, Value};

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

#[test]
fn header_round_trip_and_layout() {
    let h = Header { typ: Type::Video, flags: 15, k: 0x0102, seq: 0x01020304, frame_id: 0x0A0B0C0D, frag: 7, count: 12 };
    let b = h.encode();
    assert_eq!(hex(&b), "110f010201020304" .to_string() + "0a0b0c0d0007000c");
    assert_eq!(Header::decode(&b), Some(h));
    assert_eq!(Header::decode(&b[..15]), None);
    let mut bad = b;
    bad[0] = 0x21; // version 2
    assert_eq!(Header::decode(&bad), None);
}

#[test]
fn seal_open_and_tamper() {
    let c = Crypt::new(&[7u8; 32], 0xDEADBEEF);
    let h = Header { typ: Type::Probe, flags: 0, k: 0, seq: 5, frame_id: 0, frag: 0, count: 0 };
    let pkt = c.seal(Dir::HostToPhone, &h, b"hello");
    assert_eq!(pkt.len(), 16 + 5 + 16);
    let (h2, p) = c.open(Dir::HostToPhone, &pkt).unwrap();
    assert_eq!((h2, p.as_slice()), (h, &b"hello"[..]));
    assert!(c.open(Dir::PhoneToHost, &pkt).is_none(), "direction is part of the nonce");
    for i in [0usize, 4, 16, pkt.len() - 1] {
        let mut t = pkt.clone();
        t[i] ^= 1;
        assert!(c.open(Dir::HostToPhone, &t).is_none(), "byte {i}");
    }
}

#[test]
fn layout_blocks() {
    assert_eq!(fec::layout(1, 15), vec![(1, 1)]);
    assert_eq!(fec::layout(64, 15), vec![(64, 10)]);
    assert_eq!(fec::layout(65, 15), vec![(64, 10), (1, 1)]);
    assert_eq!(fec::layout(261, 50), vec![(64, 32), (64, 32), (64, 32), (64, 32), (5, 3)]);
}

#[test]
fn recovers_any_k_of_n_per_block() {
    // 70 data shards → blocks (64, 16) and (6, 2) at 25%
    let payload: Vec<u8> = (0..70 * SHARD as u32 - 100).map(|i| (i * 31 % 251) as u8).collect();
    let (k, shards) = fec::shards(&payload, 25);
    assert_eq!((k, shards.len()), (70, 64 + 16 + 6 + 2));
    let full = || shards.iter().map(|s| Some(s.to_vec())).collect::<Vec<Option<Vec<u8>>>>();
    // per pattern: erased indexes (block 0 spans 0..80, block 1 spans 80..88)
    let patterns: Vec<Vec<usize>> = vec![
        (0..16).chain(80..82).collect(),            // leading data of each block
        (64..80).chain(86..88).collect(),           // all parity
        (0..80).step_by(5).chain([81, 85]).collect(), // scattered
    ];
    for erase in patterns {
        let mut got = full();
        for i in erase {
            got[i] = None;
        }
        assert_eq!(&fec::rebuild(k, 25, got).unwrap()[..payload.len()], &payload[..]);
    }
    let mut too_few = full();
    for i in 80..83 {
        too_few[i] = None; // 3 lost in a block with 2 parity
    }
    assert!(fec::rebuild(k, 25, too_few).is_none());
    assert!(fec::rebuild(k, 25, full()[1..].to_vec()).is_none(), "wrong shard count");
}

#[test]
fn frame_size_boundaries() {
    for len in [0usize, SHARD - 16, SHARD - 15, 64 * SHARD - 16, 64 * SHARD - 15, 300_000, 2_000_000] {
        let data: Vec<u8> = (0..len).map(|i| (i % 253) as u8).collect();
        let fh = frame::FrameHeader { len: len as u32, capture_us: 42, keyframe: true, codec: frame::Codec::Hevc };
        let pkts = frame::packetize(9, &fh, &data, 15);
        let k = (16 + len).div_ceil(SHARD).max(1);
        let n: usize = fec::layout(k, 15).iter().map(|(a, b)| a + b).sum();
        assert_eq!(pkts.len(), n, "len {len}");
        for (i, (h, p)) in pkts.iter().enumerate() {
            assert!(h.frame_id == 9 && p.len() == SHARD && h.count as usize == n && h.flags == 15 && h.k as usize == k);
            assert_eq!(h.frag as usize, i);
        }
        let mut shards: Vec<Option<Vec<u8>>> = pkts.iter().map(|(_, p)| Some(p.clone())).collect();
        shards[0] = None; // lose the frame header shard; parity brings it back
        let (fh2, body) = frame::assemble(k, 15, shards).unwrap();
        assert_eq!(fh2, fh);
        assert_eq!(body, data);
    }
}

#[test]
fn rejects_lying_frame_header() {
    let fh = frame::FrameHeader { len: 10_000_000, capture_us: 0, keyframe: false, codec: frame::Codec::H264 };
    let pkts = frame::packetize(1, &fh, &[1, 2, 3], 15);
    let shards = pkts.iter().map(|(_, p)| Some(p.clone())).collect();
    assert!(frame::assemble(1, 15, shards).is_none());
}

/// Writes docs/prs-vectors.json for the phone's tests: `cargo test prs::vectors::write_vectors -- --ignored`.
#[test]
#[ignore]
fn write_vectors() {
    let key = [0x42u8; 32];
    let session = 0xDEADBEEFu32;
    let c = Crypt::new(&key, session);
    let h = Header { typ: Type::Audio, flags: 0, k: 0, seq: 77, frame_id: 3, frag: 0, count: 0 };
    let sealed = c.seal(Dir::HostToPhone, &h, b"opus-bytes");
    let h_in = Header { typ: Type::Input, flags: 0, k: 0, seq: 9, frame_id: 0, frag: 0, count: 0 };
    let sealed_in = c.seal(Dir::PhoneToHost, &h_in, br#"[{"i":1,"t":"key","vk":65,"down":true}]"#);
    // 66 data shards: two FEC blocks, (64, 20) and (2, 1)
    let payload: Vec<u8> = (0..75_000u32).map(|i| (i * 7 % 256) as u8).collect();
    let fh = frame::FrameHeader { len: payload.len() as u32, capture_us: 123_456_789, keyframe: true, codec: frame::Codec::H264 };
    let pkts = frame::packetize(5, &fh, &payload, 30);
    let v = json!({
        "key": hex(&key), "session": session,
        "header": { "typ": 1, "flags": 15, "k": 258, "seq": 16909060, "frameId": 168496141, "frag": 7, "count": 12,
                    "bytes": hex(&Header { typ: Type::Video, flags: 15, k: 0x0102, seq: 0x01020304, frame_id: 0x0A0B0C0D, frag: 7, count: 12 }.encode()) },
        "sealed": { "dir": 0, "plain": hex(b"opus-bytes"), "packet": hex(&sealed) },
        "sealedIn": { "dir": 1, "plain": hex(br#"[{"i":1,"t":"key","vk":65,"down":true}]"#), "packet": hex(&sealed_in) },
        "frame": { "frameId": 5, "fecPct": 30, "payload": hex(&payload), "captureUs": 123456789u64, "keyframe": true, "codec": 1,
                   "shards": pkts.iter().map(|(h, p)| json!({"header": hex(&h.encode()), "data": hex(p)})).collect::<Vec<Value>>() },
    });
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../docs/prs-vectors.json");
    std::fs::write(&path, serde_json::to_string_pretty(&v).unwrap()).unwrap();
    // read back to prove it parses
    let back: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
    assert_eq!(back["session"], session);
}
