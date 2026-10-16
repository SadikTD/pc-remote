//! AES-256-GCM per packet. Nonce = session (4) ‖ dir (1) ‖ 000000 ‖ seq (4); AAD = the header.

use super::Header;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dir {
    HostToPhone = 0,
    PhoneToHost = 1,
}

pub struct Crypt {
    key: LessSafeKey,
    session: u32,
}

impl Crypt {
    pub fn new(key: &[u8; 32], session: u32) -> Crypt {
        Crypt { key: LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).expect("32-byte key")), session }
    }

    fn nonce(&self, dir: Dir, seq: u32) -> Nonce {
        let mut n = [0u8; 12];
        n[..4].copy_from_slice(&self.session.to_be_bytes());
        n[4] = dir as u8;
        n[8..].copy_from_slice(&seq.to_be_bytes());
        Nonce::assume_unique_for_key(n)
    }

    /// Header ‖ ciphertext ‖ tag.
    pub fn seal(&self, dir: Dir, h: &Header, plain: &[u8]) -> Vec<u8> {
        let hb = h.encode();
        let mut body = plain.to_vec();
        self.key
            .seal_in_place_append_tag(self.nonce(dir, h.seq), Aad::from(hb), &mut body)
            .expect("seal");
        let mut out = Vec::with_capacity(16 + body.len());
        out.extend_from_slice(&hb);
        out.extend_from_slice(&body);
        out
    }

    /// None if the header is malformed or authentication fails.
    pub fn open(&self, dir: Dir, pkt: &[u8]) -> Option<(Header, Vec<u8>)> {
        let h = Header::decode(pkt)?;
        if pkt.len() < 32 {
            return None;
        }
        let mut body = pkt[16..].to_vec();
        let plain = self.key.open_in_place(self.nonce(dir, h.seq), Aad::from(&pkt[..16]), &mut body).ok()?;
        Some((h, plain.to_vec()))
    }
}
