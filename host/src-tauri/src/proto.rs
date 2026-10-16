//! Wire types for docs/protocol.md.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(tag = "t")]
pub enum ClientMsg {
    #[serde(rename = "pair")]
    Pair { otp: String, device: String, pin: String },
    #[serde(rename = "hello")]
    Hello { v: u32, token: String, device: String },
    #[serde(rename = "ping")]
    Ping,
    #[serde(rename = "mouse.move")]
    MouseMove { dx: i32, dy: i32 },
    #[serde(rename = "mouse.button")]
    MouseButton { b: String, down: bool },
    #[serde(rename = "mouse.scroll")]
    MouseScroll {
        #[serde(default, deserialize_with = "null_as_default")]
        dy: i32,
        #[serde(default, deserialize_with = "null_as_default")]
        dx: i32,
    },
    #[serde(rename = "key")]
    Key { vk: u32, down: bool },
    #[serde(rename = "text")]
    Text { s: String },
    #[serde(rename = "pad")]
    Pad { id: u8, btn: u16, lt: u8, rt: u8, lx: i16, ly: i16, rx: i16, ry: i16 },
    #[serde(rename = "pad.off")]
    PadOff { id: u8 },
    #[serde(rename = "media.sub")]
    MediaSub { on: bool },
    #[serde(rename = "media.cmd")]
    MediaCmd {
        c: String,
        #[serde(default)]
        v: Option<u8>,
    },
    #[serde(rename = "clip.sub")]
    ClipSub { on: bool },
    #[serde(rename = "clip")]
    Clip { mime: String, data: String },
    #[serde(rename = "file.begin")]
    FileBegin { id: String, name: String, size: u64 },
    #[serde(rename = "file.end")]
    FileEnd { id: String, sha256: String },
    #[serde(rename = "file.accept")]
    FileAccept { id: String, offset: u64 },
    #[serde(rename = "file.reject")]
    FileReject { id: String },
    #[serde(rename = "power")]
    Power { a: String },
    #[serde(rename = "thumb.sub")]
    ThumbSub { on: bool },
    #[serde(rename = "net")]
    Net,
}

/// The phone may send `null` for optional fields; treat it like an absent field.
fn null_as_default<'de, D: serde::Deserializer<'de>, T: Default + Deserialize<'de>>(d: D) -> Result<T, D::Error> {
    Ok(Option::<T>::deserialize(d)?.unwrap_or_default())
}

impl ClientMsg {
    /// Clamps values the protocol bounds: mouse moves to ±2000, text to 1000 chars.
    pub fn sanitize(self) -> Self {
        match self {
            ClientMsg::MouseMove { dx, dy } => ClientMsg::MouseMove { dx: dx.clamp(-2000, 2000), dy: dy.clamp(-2000, 2000) },
            ClientMsg::Text { s } => ClientMsg::Text { s: s.chars().take(1000).collect() },
            m => m,
        }
    }

    /// The protocol `t` of this message, for `error.of` / `ok.of`.
    pub fn t(&self) -> &'static str {
        match self {
            ClientMsg::Pair { .. } => "pair",
            ClientMsg::Hello { .. } => "hello",
            ClientMsg::Ping => "ping",
            ClientMsg::MouseMove { .. } => "mouse.move",
            ClientMsg::MouseButton { .. } => "mouse.button",
            ClientMsg::MouseScroll { .. } => "mouse.scroll",
            ClientMsg::Key { .. } => "key",
            ClientMsg::Text { .. } => "text",
            ClientMsg::Pad { .. } => "pad",
            ClientMsg::PadOff { .. } => "pad.off",
            ClientMsg::MediaSub { .. } => "media.sub",
            ClientMsg::MediaCmd { .. } => "media.cmd",
            ClientMsg::ClipSub { .. } => "clip.sub",
            ClientMsg::Clip { .. } => "clip",
            ClientMsg::FileBegin { .. } => "file.begin",
            ClientMsg::FileEnd { .. } => "file.end",
            ClientMsg::FileAccept { .. } => "file.accept",
            ClientMsg::FileReject { .. } => "file.reject",
            ClientMsg::Power { .. } => "power",
            ClientMsg::ThumbSub { .. } => "thumb.sub",
            ClientMsg::Net => "net",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct PcInfo {
    pub id: String,
    pub name: String,
    pub user: String,
    pub macs: Vec<String>,
    pub os: String,
    pub sunshine: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "t")]
pub enum ServerMsg {
    #[serde(rename = "paired")]
    Paired { token: String, pc: PcInfo },
    #[serde(rename = "welcome")]
    Welcome { v: u32, pc: PcInfo, caps: Vec<String> },
    #[serde(rename = "pong")]
    Pong,
    #[serde(rename = "error")]
    Error {
        code: String,
        msg: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        of: Option<String>,
    },
    #[serde(rename = "rumble")]
    Rumble { id: u8, lo: u8, hi: u8 },
    #[serde(rename = "media")]
    Media { title: String, artist: String, app: String, playing: bool, vol: u8, muted: bool },
    #[serde(rename = "clip")]
    Clip { mime: String, data: String },
    #[serde(rename = "file.ready")]
    FileReady { id: String, offset: u64 },
    #[serde(rename = "file.done")]
    FileDone { id: String, path: String },
    #[serde(rename = "file.error")]
    FileError { id: String, code: String },
    #[serde(rename = "file.offer")]
    FileOffer { id: String, name: String, size: u64 },
    #[serde(rename = "file.end")]
    FileEnd { id: String, sha256: String },
    #[serde(rename = "ok")]
    Ok { of: String },
    #[serde(rename = "status")]
    Status { streaming: bool, to: String },
    #[serde(rename = "net")]
    Net { lan: Vec<String>, public: Option<String>, anywhere: bool, upnp: bool },
}

impl ServerMsg {
    pub fn error(code: &str, msg: &str, of: Option<&str>) -> Self {
        ServerMsg::Error { code: code.into(), msg: msg.into(), of: of.map(Into::into) }
    }

    pub fn json(&self) -> String {
        serde_json::to_string(self).expect("ServerMsg serializes")
    }
}

#[derive(Debug, PartialEq)]
pub enum Binary {
    FileChunk { id: [u8; 16], offset: u64, data: Vec<u8> },
    Thumb(Vec<u8>),
}

/// `[0x01][16-byte uuid][u64 BE offset][data]` or `[0x02][JPEG]`.
pub fn parse_binary(b: &[u8]) -> Option<Binary> {
    match b.first()? {
        1 if b.len() >= 25 => Some(Binary::FileChunk {
            id: b[1..17].try_into().ok()?,
            offset: u64::from_be_bytes(b[17..25].try_into().ok()?),
            data: b[25..].to_vec(),
        }),
        2 => Some(Binary::Thumb(b[1..].to_vec())),
        _ => None,
    }
}

pub fn chunk_frame(id: &[u8; 16], offset: u64, data: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(25 + data.len());
    f.push(1);
    f.extend_from_slice(id);
    f.extend_from_slice(&offset.to_be_bytes());
    f.extend_from_slice(data);
    f
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(s: &str) -> ClientMsg {
        serde_json::from_str(s).unwrap_or_else(|e| panic!("{s}: {e}"))
    }

    #[test]
    fn client_examples_parse() {
        assert_eq!(
            c(r#"{"t":"pair","otp":"abc","device":"Pixel 8","pin":"4821"}"#),
            ClientMsg::Pair { otp: "abc".into(), device: "Pixel 8".into(), pin: "4821".into() }
        );
        assert_eq!(
            c(r#"{"t":"hello","v":1,"token":"tok","device":"Pixel 8"}"#),
            ClientMsg::Hello { v: 1, token: "tok".into(), device: "Pixel 8".into() }
        );
        assert_eq!(c(r#"{"t":"ping"}"#), ClientMsg::Ping);
        assert_eq!(c(r#"{"t":"mouse.move","dx":-3,"dy":12}"#), ClientMsg::MouseMove { dx: -3, dy: 12 });
        assert_eq!(
            c(r#"{"t":"mouse.button","b":"left","down":true}"#),
            ClientMsg::MouseButton { b: "left".into(), down: true }
        );
        assert_eq!(c(r#"{"t":"mouse.scroll","dy":120,"dx":0}"#), ClientMsg::MouseScroll { dy: 120, dx: 0 });
        assert_eq!(c(r#"{"t":"key","vk":91,"down":true}"#), ClientMsg::Key { vk: 91, down: true });
        assert_eq!(c(r#"{"t":"text","s":"héllo"}"#), ClientMsg::Text { s: "héllo".into() });
        assert_eq!(
            c(r#"{"t":"pad","id":0,"btn":4096,"lt":0,"rt":255,"lx":0,"ly":-32768,"rx":1200,"ry":0}"#),
            ClientMsg::Pad { id: 0, btn: 4096, lt: 0, rt: 255, lx: 0, ly: -32768, rx: 1200, ry: 0 }
        );
        assert_eq!(c(r#"{"t":"pad.off","id":0}"#), ClientMsg::PadOff { id: 0 });
        assert_eq!(c(r#"{"t":"media.sub","on":true}"#), ClientMsg::MediaSub { on: true });
        assert_eq!(c(r#"{"t":"media.cmd","c":"play_pause"}"#), ClientMsg::MediaCmd { c: "play_pause".into(), v: None });
        assert_eq!(c(r#"{"t":"media.cmd","c":"vol_set","v":40}"#), ClientMsg::MediaCmd { c: "vol_set".into(), v: Some(40) });
        assert_eq!(c(r#"{"t":"clip.sub","on":true}"#), ClientMsg::ClipSub { on: true });
        assert_eq!(
            c(r#"{"t":"clip","mime":"text/plain","data":"x"}"#),
            ClientMsg::Clip { mime: "text/plain".into(), data: "x".into() }
        );
        assert_eq!(
            c(r#"{"t":"file.begin","id":"u","name":"photo.jpg","size":123456}"#),
            ClientMsg::FileBegin { id: "u".into(), name: "photo.jpg".into(), size: 123456 }
        );
        assert_eq!(
            c(r#"{"t":"file.end","id":"u","sha256":"ab"}"#),
            ClientMsg::FileEnd { id: "u".into(), sha256: "ab".into() }
        );
        assert_eq!(c(r#"{"t":"file.accept","id":"u","offset":5}"#), ClientMsg::FileAccept { id: "u".into(), offset: 5 });
        assert_eq!(c(r#"{"t":"file.reject","id":"u"}"#), ClientMsg::FileReject { id: "u".into() });
        assert_eq!(c(r#"{"t":"power","a":"lock"}"#), ClientMsg::Power { a: "lock".into() });
        assert_eq!(c(r#"{"t":"thumb.sub","on":true}"#), ClientMsg::ThumbSub { on: true });
        assert_eq!(c(r#"{"t":"net"}"#), ClientMsg::Net);
    }

    fn s(m: &ServerMsg) -> serde_json::Value {
        serde_json::to_value(m).unwrap()
    }

    #[test]
    fn server_examples_serialize() {
        use serde_json::json;
        let pc = PcInfo {
            id: "id".into(),
            name: "DESKTOP-ABC".into(),
            user: "Sadik".into(),
            macs: vec!["AA:BB:CC:DD:EE:FF".into()],
            os: "Windows 11 Pro 26300".into(),
            sunshine: true,
        };
        assert_eq!(
            s(&ServerMsg::Paired { token: "t".into(), pc: pc.clone() }),
            json!({"t":"paired","token":"t","pc":{"id":"id","name":"DESKTOP-ABC","user":"Sadik","macs":["AA:BB:CC:DD:EE:FF"],"os":"Windows 11 Pro 26300","sunshine":true}})
        );
        assert_eq!(
            s(&ServerMsg::Welcome { v: 1, pc, caps: vec!["input".into()] })["caps"],
            json!(["input"])
        );
        assert_eq!(s(&ServerMsg::Pong), json!({"t":"pong"}));
        assert_eq!(
            s(&ServerMsg::error("bad_otp", "x", None)),
            json!({"t":"error","code":"bad_otp","msg":"x"})
        );
        assert_eq!(
            s(&ServerMsg::error("bad_request", "x", Some("power"))),
            json!({"t":"error","code":"bad_request","msg":"x","of":"power"})
        );
        assert_eq!(s(&ServerMsg::Rumble { id: 0, lo: 1, hi: 2 }), json!({"t":"rumble","id":0,"lo":1,"hi":2}));
        assert_eq!(
            s(&ServerMsg::Media { title: "a".into(), artist: "".into(), app: "Spotify".into(), playing: true, vol: 45, muted: false }),
            json!({"t":"media","title":"a","artist":"","app":"Spotify","playing":true,"vol":45,"muted":false})
        );
        assert_eq!(s(&ServerMsg::Clip { mime: "text/plain".into(), data: "x".into() })["t"], "clip");
        assert_eq!(s(&ServerMsg::FileReady { id: "u".into(), offset: 0 }), json!({"t":"file.ready","id":"u","offset":0}));
        assert_eq!(s(&ServerMsg::FileDone { id: "u".into(), path: "p".into() }), json!({"t":"file.done","id":"u","path":"p"}));
        assert_eq!(s(&ServerMsg::FileError { id: "u".into(), code: "denied".into() }), json!({"t":"file.error","id":"u","code":"denied"}));
        assert_eq!(
            s(&ServerMsg::FileOffer { id: "u".into(), name: "n".into(), size: 1 }),
            json!({"t":"file.offer","id":"u","name":"n","size":1})
        );
        assert_eq!(s(&ServerMsg::FileEnd { id: "u".into(), sha256: "ab".into() }), json!({"t":"file.end","id":"u","sha256":"ab"}));
        assert_eq!(s(&ServerMsg::Ok { of: "power".into() }), json!({"t":"ok","of":"power"}));
        assert_eq!(
            s(&ServerMsg::Status { streaming: true, to: "Pixel 8".into() }),
            json!({"t":"status","streaming":true,"to":"Pixel 8"})
        );
        assert_eq!(
            s(&ServerMsg::Net { lan: vec!["192.168.0.126".into()], public: None, anywhere: true, upnp: true }),
            json!({"t":"net","lan":["192.168.0.126"],"public":null,"anywhere":true,"upnp":true})
        );
    }

    #[test]
    fn unknown_type_is_error_and_unknown_fields_ignored() {
        assert!(serde_json::from_str::<ClientMsg>(r#"{"t":"teleport"}"#).is_err());
        assert!(serde_json::from_str::<ClientMsg>(r#"{"x":1}"#).is_err());
        assert_eq!(c(r#"{"t":"ping","extra":[1,2]}"#), ClientMsg::Ping);
        assert_eq!(c(r#"{"t":"key","vk":13,"down":false,"rep":3}"#), ClientMsg::Key { vk: 13, down: false });
    }

    #[test]
    fn null_optional_fields_are_absent() {
        assert_eq!(c(r#"{"t":"mouse.scroll","dy":120,"dx":null}"#), ClientMsg::MouseScroll { dy: 120, dx: 0 });
        assert_eq!(c(r#"{"t":"mouse.scroll","dx":-5}"#), ClientMsg::MouseScroll { dy: 0, dx: -5 });
        assert_eq!(c(r#"{"t":"media.cmd","c":"mute","v":null}"#), ClientMsg::MediaCmd { c: "mute".into(), v: None });
    }

    #[test]
    fn binary_chunk_round_trip() {
        let id = [7u8; 16];
        let frame = chunk_frame(&id, 0x0102_0304_0506_0708, b"hello");
        assert_eq!(frame[0], 1);
        assert_eq!(frame.len(), 25 + 5);
        assert_eq!(
            parse_binary(&frame),
            Some(Binary::FileChunk { id, offset: 0x0102_0304_0506_0708, data: b"hello".to_vec() })
        );
        assert_eq!(parse_binary(&[2, 0xFF, 0xD8]), Some(Binary::Thumb(vec![0xFF, 0xD8])));
    }

    #[test]
    fn short_or_unknown_binary_is_none() {
        assert_eq!(parse_binary(&[1; 24]), None);
        assert_eq!(parse_binary(&[]), None);
        assert_eq!(parse_binary(&[9, 1, 2]), None);
        assert_eq!(parse_binary(&[1; 25]), Some(Binary::FileChunk { id: [1; 16], offset: 0x0101_0101_0101_0101, data: vec![] }));
    }

    #[test]
    fn sanitize_clamps_mouse_and_text() {
        assert_eq!(ClientMsg::MouseMove { dx: 5000, dy: -9000 }.sanitize(), ClientMsg::MouseMove { dx: 2000, dy: -2000 });
        assert_eq!(ClientMsg::MouseMove { dx: -3, dy: 12 }.sanitize(), ClientMsg::MouseMove { dx: -3, dy: 12 });
        let long: String = "é".repeat(1500);
        match (ClientMsg::Text { s: long }).sanitize() {
            ClientMsg::Text { s } => assert_eq!(s.chars().count(), 1000),
            m => panic!("{m:?}"),
        }
    }
}
