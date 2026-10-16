//! Video frames ↔ shards: a 16-byte frame header, then the encoded bitstream.

use super::{fec, Header, Type, SHARD};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Codec {
    H264 = 1,
    Hevc = 2,
    Av1 = 3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameHeader {
    pub len: u32,
    pub capture_us: u64,
    pub keyframe: bool,
    pub codec: Codec,
}

impl FrameHeader {
    pub fn encode(&self) -> [u8; 16] {
        let mut b = [0u8; 16];
        b[..4].copy_from_slice(&self.len.to_be_bytes());
        b[4..12].copy_from_slice(&self.capture_us.to_be_bytes());
        b[12] = self.keyframe as u8;
        b[13] = self.codec as u8;
        b
    }

    pub fn decode(b: &[u8]) -> Option<FrameHeader> {
        if b.len() < 16 {
            return None;
        }
        Some(FrameHeader {
            len: u32::from_be_bytes(b[..4].try_into().ok()?),
            capture_us: u64::from_be_bytes(b[4..12].try_into().ok()?),
            keyframe: b[12] & 1 == 1,
            codec: match b[13] {
                1 => Codec::H264,
                2 => Codec::Hevc,
                3 => Codec::Av1,
                _ => return None,
            },
        })
    }
}

/// Packets (headers with seq 0, filled in by the sender) and shard plaintexts for one frame.
pub fn packetize(frame_id: u32, fh: &FrameHeader, data: &[u8], fec_pct: usize) -> Vec<(Header, Vec<u8>)> {
    let p = fec_pct.clamp(5, 50);
    let mut payload = Vec::with_capacity(16 + data.len());
    payload.extend_from_slice(&fh.encode());
    payload.extend_from_slice(data);
    let (k, shards) = fec::shards(&payload, p);
    let blocks = fec::layout(k, p);
    let n = shards.len() as u16;
    let mut out = Vec::with_capacity(shards.len());
    let mut it = shards.into_iter();
    for (kb, mb) in blocks {
        for j in 0..kb + mb {
            let typ = if j < kb { Type::Video } else { Type::Fec };
            let h = Header { typ, flags: p as u8, k: k as u16, seq: 0, frame_id, frag: out.len() as u16, count: n };
            out.push((h, it.next().expect("layout").to_vec()));
        }
    }
    out
}

/// Rebuilds a frame from its shards in layout order (`k`, `p` from any of its headers).
pub fn assemble(k: usize, p: usize, shards: Vec<Option<Vec<u8>>>) -> Option<(FrameHeader, Vec<u8>)> {
    let all = fec::rebuild(k, p, shards)?;
    let fh = FrameHeader::decode(&all)?;
    let end = 16 + fh.len as usize;
    if end > all.len() || end <= (k - 1) * SHARD && k > 1 {
        return None;
    }
    Some((fh, all[16..end].to_vec()))
}
