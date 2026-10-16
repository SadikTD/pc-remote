//! The 16-byte PRS packet header.

pub const VERSION: u8 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Type {
    Video = 1,
    Fec = 2,
    Audio = 3,
    Input = 4,
    Report = 5,
    Idr = 6,
    Cursor = 7,
    Probe = 8,
    Bye = 9,
}

impl Type {
    fn from_u8(v: u8) -> Option<Type> {
        Some(match v {
            1 => Type::Video,
            2 => Type::Fec,
            3 => Type::Audio,
            4 => Type::Input,
            5 => Type::Report,
            6 => Type::Idr,
            7 => Type::Cursor,
            8 => Type::Probe,
            9 => Type::Bye,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Header {
    pub typ: Type,
    /// FEC percentage `p` for VIDEO/FEC; 0 otherwise.
    pub flags: u8,
    /// Data shards in the whole frame for VIDEO/FEC; 0 otherwise.
    pub k: u16,
    pub seq: u32,
    pub frame_id: u32,
    pub frag: u16,
    pub count: u16,
}

impl Header {
    pub fn encode(&self) -> [u8; 16] {
        let mut b = [0u8; 16];
        b[0] = (VERSION << 4) | self.typ as u8;
        b[1] = self.flags;
        b[2..4].copy_from_slice(&self.k.to_be_bytes());
        b[4..8].copy_from_slice(&self.seq.to_be_bytes());
        b[8..12].copy_from_slice(&self.frame_id.to_be_bytes());
        b[12..14].copy_from_slice(&self.frag.to_be_bytes());
        b[14..16].copy_from_slice(&self.count.to_be_bytes());
        b
    }

    pub fn decode(b: &[u8]) -> Option<Header> {
        if b.len() < 16 || b[0] >> 4 != VERSION {
            return None;
        }
        Some(Header {
            typ: Type::from_u8(b[0] & 0x0F)?,
            flags: b[1],
            k: u16::from_be_bytes(b[2..4].try_into().ok()?),
            seq: u32::from_be_bytes(b[4..8].try_into().ok()?),
            frame_id: u32::from_be_bytes(b[8..12].try_into().ok()?),
            frag: u16::from_be_bytes(b[12..14].try_into().ok()?),
            count: u16::from_be_bytes(b[14..16].try_into().ok()?),
        })
    }
}
