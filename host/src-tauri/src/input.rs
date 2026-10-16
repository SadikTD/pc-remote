//! Mouse and keyboard injection with stuck-key protection.

use crate::proto::ClientMsg;
use std::collections::BTreeSet;
use windows::Win32::UI::Input::KeyboardAndMouse::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Btn {
    Left,
    Right,
    Middle,
    X1,
    X2,
}

impl Btn {
    fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "left" => Btn::Left,
            "right" => Btn::Right,
            "middle" => Btn::Middle,
            "x1" => Btn::X1,
            "x2" => Btn::X2,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Ev {
    Move { dx: i32, dy: i32 },
    /// Absolute position on the primary display, 0..=65535 on each axis.
    Abs { x: i32, y: i32 },
    Button { b: Btn, down: bool },
    Wheel(i32),
    HWheel(i32),
    Key { vk: u16, down: bool },
    Unicode { unit: u16, down: bool },
}

pub trait Emitter {
    fn emit(&mut self, evs: &[Ev]);
}

#[derive(Default)]
pub struct HeldSet {
    keys: BTreeSet<u16>,
    buttons: BTreeSet<Btn>,
}

impl HeldSet {
    pub fn press_key(&mut self, vk: u16) -> bool {
        self.keys.insert(vk)
    }
    pub fn release_key(&mut self, vk: u16) -> bool {
        self.keys.remove(&vk)
    }
    pub fn press_button(&mut self, b: Btn) -> bool {
        self.buttons.insert(b)
    }
    pub fn release_button(&mut self, b: Btn) -> bool {
        self.buttons.remove(&b)
    }
    pub fn is_empty(&self) -> bool {
        self.keys.is_empty() && self.buttons.is_empty()
    }
}

/// One session's input. Dropping it releases every key and button still held.
pub struct Input<E: Emitter> {
    e: E,
    held: HeldSet,
}

impl<E: Emitter> Input<E> {
    pub fn new(e: E) -> Self {
        Input { e, held: HeldSet::default() }
    }

    pub fn handle(&mut self, m: &ClientMsg) -> Result<(), &'static str> {
        match m {
            ClientMsg::MouseMove { dx, dy } => self.e.emit(&[Ev::Move { dx: *dx, dy: *dy }]),
            ClientMsg::MousePos { x, y } => self.e.emit(&[Ev::Abs { x: (x * 65535.0).round() as i32, y: (y * 65535.0).round() as i32 }]),
            ClientMsg::MouseScroll { dy, dx } => {
                let evs: Vec<Ev> = [(*dy != 0).then_some(Ev::Wheel(*dy)), (*dx != 0).then_some(Ev::HWheel(*dx))]
                    .into_iter()
                    .flatten()
                    .collect();
                self.e.emit(&evs)
            }
            ClientMsg::MouseButton { b, down } => {
                let b = Btn::parse(b).ok_or("bad_request")?;
                if *down { self.held.press_button(b) } else { self.held.release_button(b) };
                self.e.emit(&[Ev::Button { b, down: *down }])
            }
            ClientMsg::Key { vk, down } => {
                let vk = u16::try_from(*vk).ok().filter(|v| (1..=254).contains(v)).ok_or("bad_request")?;
                if *down { self.held.press_key(vk) } else { self.held.release_key(vk) };
                self.e.emit(&[Ev::Key { vk, down: *down }])
            }
            ClientMsg::Text { s } => {
                let mut evs = Vec::new();
                for ch in s.chars().filter(|c| *c != '\r') {
                    if ch == '\n' {
                        evs.extend([Ev::Key { vk: 0x0D, down: true }, Ev::Key { vk: 0x0D, down: false }]);
                    } else {
                        for unit in ch.encode_utf16(&mut [0; 2]) {
                            evs.extend([Ev::Unicode { unit: *unit, down: true }, Ev::Unicode { unit: *unit, down: false }]);
                        }
                    }
                }
                self.e.emit(&evs)
            }
            _ => return Err("unsupported"),
        }
        Ok(())
    }

    pub fn release_all(&mut self) {
        let held = std::mem::take(&mut self.held);
        let evs: Vec<Ev> = held
            .keys
            .into_iter()
            .map(|vk| Ev::Key { vk, down: false })
            .chain(held.buttons.into_iter().map(|b| Ev::Button { b, down: false }))
            .collect();
        if !evs.is_empty() {
            self.e.emit(&evs);
        }
    }
}

impl<E: Emitter> Drop for Input<E> {
    fn drop(&mut self) {
        self.release_all();
    }
}

/// Keys that need KEYEVENTF_EXTENDEDKEY to be told apart from their numpad/left twins.
pub fn is_extended(vk: u16) -> bool {
    matches!(vk, 0x03 | 0x21..=0x28 | 0x2C..=0x2E | 0x5B..=0x5D | 0x6F | 0x90 | 0xA3 | 0xA5 | 0xA6..=0xB7)
}

/// The real emitter: `SendInput`.
pub struct SendInputEmitter;

impl Emitter for SendInputEmitter {
    fn emit(&mut self, evs: &[Ev]) {
        let inputs: Vec<INPUT> = evs.iter().map(to_input).collect();
        // SAFETY: `inputs` is a valid slice of initialised INPUT structs.
        let sent = unsafe { SendInput(&inputs, std::mem::size_of::<INPUT>() as i32) };
        if sent as usize != inputs.len() {
            static WARNED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
            if !WARNED.swap(true, std::sync::atomic::Ordering::Relaxed) {
                crate::log::line(&format!("input: SendInput injected {sent}/{} ({:?})", inputs.len(), std::io::Error::last_os_error()));
            }
        }
    }
}

fn mouse(dx: i32, dy: i32, data: i32, flags: MOUSE_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    }
}

fn key(vk: u16, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: VIRTUAL_KEY(vk), wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 } },
    }
}

fn to_input(ev: &Ev) -> INPUT {
    match *ev {
        Ev::Move { dx, dy } => mouse(dx, dy, 0, MOUSEEVENTF_MOVE),
        Ev::Abs { x, y } => mouse(x, y, 0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE),
        Ev::Wheel(v) => mouse(0, 0, v, MOUSEEVENTF_WHEEL),
        Ev::HWheel(v) => mouse(0, 0, v, MOUSEEVENTF_HWHEEL),
        Ev::Button { b, down } => {
            let (flags, data) = match (b, down) {
                (Btn::Left, true) => (MOUSEEVENTF_LEFTDOWN, 0),
                (Btn::Left, false) => (MOUSEEVENTF_LEFTUP, 0),
                (Btn::Right, true) => (MOUSEEVENTF_RIGHTDOWN, 0),
                (Btn::Right, false) => (MOUSEEVENTF_RIGHTUP, 0),
                (Btn::Middle, true) => (MOUSEEVENTF_MIDDLEDOWN, 0),
                (Btn::Middle, false) => (MOUSEEVENTF_MIDDLEUP, 0),
                (Btn::X1, true) => (MOUSEEVENTF_XDOWN, 1),
                (Btn::X1, false) => (MOUSEEVENTF_XUP, 1),
                (Btn::X2, true) => (MOUSEEVENTF_XDOWN, 2),
                (Btn::X2, false) => (MOUSEEVENTF_XUP, 2),
            };
            mouse(0, 0, data, flags)
        }
        Ev::Key { vk, down } => {
            // SAFETY: pure lookup in the active keyboard layout.
            let scan = unsafe { MapVirtualKeyW(vk as u32, MAPVK_VK_TO_VSC) } as u16;
            let mut f = if down { KEYBD_EVENT_FLAGS(0) } else { KEYEVENTF_KEYUP };
            if is_extended(vk) {
                f |= KEYEVENTF_EXTENDEDKEY;
            }
            key(vk, scan, f)
        }
        Ev::Unicode { unit, down } => key(0, unit, if down { KEYEVENTF_UNICODE } else { KEYEVENTF_UNICODE | KEYEVENTF_KEYUP }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::ClientMsg;
    use std::sync::{Arc, Mutex};

    #[derive(Clone, Default)]
    struct Rec(Arc<Mutex<Vec<Ev>>>);
    impl Emitter for Rec {
        fn emit(&mut self, evs: &[Ev]) {
            self.0.lock().unwrap().extend_from_slice(evs);
        }
    }

    #[test]
    fn held_set_bookkeeping() {
        let mut h = HeldSet::default();
        assert!(h.press_key(16));
        assert!(!h.press_key(16), "repeat is not a new press");
        assert!(h.release_key(16));
        assert!(!h.release_key(16));
        assert!(h.press_button(Btn::Left));
        assert!(h.release_button(Btn::Left));
        assert!(h.is_empty());
    }

    #[test]
    fn releases_held_on_drop() {
        let rec = Rec::default();
        {
            let mut i = Input::new(rec.clone());
            i.handle(&ClientMsg::Key { vk: 16, down: true }).unwrap();
            i.handle(&ClientMsg::MouseButton { b: "left".into(), down: true }).unwrap();
            i.handle(&ClientMsg::Key { vk: 65, down: true }).unwrap();
            i.handle(&ClientMsg::Key { vk: 65, down: false }).unwrap();
            rec.0.lock().unwrap().clear();
        }
        assert_eq!(
            *rec.0.lock().unwrap(),
            vec![Ev::Key { vk: 16, down: false }, Ev::Button { b: Btn::Left, down: false }]
        );
    }

    #[test]
    fn maps_messages_to_events() {
        let rec = Rec::default();
        let mut i = Input::new(rec.clone());
        i.handle(&ClientMsg::MouseMove { dx: -3, dy: 12 }).unwrap();
        i.handle(&ClientMsg::MousePos { x: 0.5, y: 1.0 }).unwrap();
        i.handle(&ClientMsg::MouseScroll { dy: 120, dx: -120 }).unwrap();
        i.handle(&ClientMsg::MouseButton { b: "x2".into(), down: true }).unwrap();
        i.handle(&ClientMsg::MouseButton { b: "x2".into(), down: false }).unwrap();
        i.handle(&ClientMsg::Text { s: "a\n😀".into() }).unwrap();
        assert_eq!(
            *rec.0.lock().unwrap(),
            vec![
                Ev::Move { dx: -3, dy: 12 },
                Ev::Abs { x: 32768, y: 65535 },
                Ev::Wheel(120),
                Ev::HWheel(-120),
                Ev::Button { b: Btn::X2, down: true },
                Ev::Button { b: Btn::X2, down: false },
                Ev::Unicode { unit: 'a' as u16, down: true },
                Ev::Unicode { unit: 'a' as u16, down: false },
                Ev::Key { vk: 0x0D, down: true },
                Ev::Key { vk: 0x0D, down: false },
                Ev::Unicode { unit: 0xD83D, down: true },
                Ev::Unicode { unit: 0xD83D, down: false },
                Ev::Unicode { unit: 0xDE00, down: true },
                Ev::Unicode { unit: 0xDE00, down: false },
            ]
        );
    }

    #[test]
    fn rejects_bad_button_and_key() {
        let mut i = Input::new(Rec::default());
        assert_eq!(i.handle(&ClientMsg::MouseButton { b: "fourth".into(), down: true }), Err("bad_request"));
        assert_eq!(i.handle(&ClientMsg::Key { vk: 0, down: true }), Err("bad_request"));
        assert_eq!(i.handle(&ClientMsg::Key { vk: 256, down: true }), Err("bad_request"));
    }

    #[test]
    fn extended_keys() {
        assert!(is_extended(0x25)); // left arrow
        assert!(is_extended(0x5B)); // left win
        assert!(is_extended(0xA3)); // right ctrl
        assert!(!is_extended(0x41));
        assert!(!is_extended(0xA2)); // left ctrl
    }
}
