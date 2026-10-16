//! Reed-Solomon FEC over GF(2^8), compatible with Backblaze JavaReedSolomon.
//! Data shards are grouped into blocks of at most `BLOCK` shards, each with its own parity.

use super::SHARD;
use reed_solomon_erasure::galois_8::ReedSolomon;

pub const BLOCK: usize = 64;

/// `(k_b, m_b)` for each block of a frame with `k` data shards at FEC percentage `p`.
pub fn layout(k: usize, p: usize) -> Vec<(usize, usize)> {
    (0..k.max(1))
        .step_by(BLOCK)
        .map(|start| {
            let kb = (k.max(1) - start).min(BLOCK);
            (kb, (kb * p).div_ceil(100).max(1))
        })
        .collect()
}

/// Splits `payload` into `k` zero-padded data shards plus per-block parity, in layout order.
pub fn shards(payload: &[u8], p: usize) -> (usize, Vec<[u8; SHARD]>) {
    let k = payload.len().div_ceil(SHARD).max(1);
    let mut chunks = payload.chunks(SHARD);
    let mut out = Vec::new();
    for (kb, mb) in layout(k, p) {
        let mut block = vec![[0u8; SHARD]; kb + mb];
        for s in block.iter_mut().take(kb) {
            if let Some(c) = chunks.next() {
                s[..c.len()].copy_from_slice(c);
            }
        }
        ReedSolomon::new(kb, mb).expect("kb ≤ 64, mb ≤ 64").encode(&mut block).expect("encode");
        out.extend(block);
    }
    (k, out)
}

/// Rebuilds the `k` data shards (concatenated, still padded) from shards in layout order.
pub fn rebuild(k: usize, p: usize, mut shards: Vec<Option<Vec<u8>>>) -> Option<Vec<u8>> {
    let blocks = layout(k, p);
    if shards.len() != blocks.iter().map(|(kb, mb)| kb + mb).sum::<usize>()
        || shards.iter().flatten().any(|s| s.len() != SHARD)
    {
        return None;
    }
    let mut out = Vec::with_capacity(k * SHARD);
    let mut at = 0;
    for (kb, mb) in blocks {
        let block = &mut shards[at..at + kb + mb];
        if block.iter().take(kb).any(Option::is_none) {
            if block.iter().flatten().count() < kb {
                return None;
            }
            ReedSolomon::new(kb, mb).ok()?.reconstruct_data(block).ok()?;
        }
        for s in block.iter().take(kb) {
            out.extend_from_slice(s.as_ref()?);
        }
        at += kb + mb;
    }
    Some(out)
}
