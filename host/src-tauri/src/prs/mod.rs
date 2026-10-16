//! PRS: PC Remote Stream protocol (docs/stream-protocol.md): header, crypto, FEC, framing.

pub mod crypt;
pub mod fec;
pub mod frame;
pub mod header;

pub use crypt::{Crypt, Dir};
pub use header::{Header, Type};

/// Bytes of plaintext in one data or parity shard.
pub const SHARD: usize = 1152;
/// Largest plaintext in one packet (1200 − 16 header − 16 tag).
pub const MAX_PLAIN: usize = 1168;

#[cfg(test)]
mod vectors;
