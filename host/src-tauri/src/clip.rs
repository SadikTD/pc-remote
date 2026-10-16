//! Two-way clipboard: Windows clipboard changes → subscribed phones; phone `clip` → Windows clipboard.

use crate::proto::ServerMsg;
use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;
use std::sync::{Mutex, Once, OnceLock};
use tokio::sync::broadcast;
use windows::core::w;
use windows::Win32::Foundation::{GlobalFree, HANDLE, HGLOBAL, HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::DataExchange::*;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock, GMEM_MOVEABLE};
use windows::Win32::System::Ole::CF_UNICODETEXT;
use windows::Win32::UI::WindowsAndMessaging::*;

const TEXT_MAX: usize = 1 << 20;
const IMAGE_MAX: usize = 8 << 20;

pub fn within_limit(mime: &str, len: usize) -> bool {
    match mime {
        "text/plain" => len <= TEXT_MAX,
        "image/png" => len <= IMAGE_MAX,
        _ => false,
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Content {
    Text(String),
    Png(Vec<u8>),
}

impl Content {
    pub fn mime(&self) -> &'static str {
        match self {
            Content::Text(_) => "text/plain",
            Content::Png(_) => "image/png",
        }
    }

    pub fn bytes(&self) -> &[u8] {
        match self {
            Content::Text(t) => t.as_bytes(),
            Content::Png(b) => b,
        }
    }

    pub fn message(&self) -> ServerMsg {
        let data = match self {
            Content::Text(t) => t.clone(),
            Content::Png(b) => STANDARD.encode(b),
        };
        ServerMsg::Clip { mime: self.mime().into(), data }
    }

    pub fn from_msg(mime: &str, data: &str) -> Option<Self> {
        match mime {
            "text/plain" => Some(Content::Text(data.into())),
            "image/png" => STANDARD.decode(data).or_else(|_| STANDARD_NO_PAD.decode(data)).ok().map(Content::Png),
            _ => None,
        }
    }
}

/// Remembers what a phone just wrote, so the change event it causes is not sent back to it.
#[derive(Default)]
pub struct EchoGuard(Option<(Vec<u8>, u64)>);

impl EchoGuard {
    pub fn wrote(&mut self, content: &[u8], origin: u64) {
        self.0 = Some((crate::tls::sha256(content), origin));
    }

    /// The phone whose write caused this change, if any (consumed).
    pub fn origin_of(&mut self, content: &[u8]) -> Option<u64> {
        match self.0.take() {
            Some((h, o)) if h == crate::tls::sha256(content) => Some(o),
            other => {
                self.0 = other;
                None
            }
        }
    }
}

fn png_format() -> u32 {
    // SAFETY: registering a named format is idempotent.
    unsafe { RegisterClipboardFormatW(w!("PNG")) }
}

/// Opens the clipboard, retrying briefly while another app holds it.
fn open() -> bool {
    for _ in 0..10 {
        // SAFETY: paired with CloseClipboard by the caller.
        if unsafe { OpenClipboard(None) }.is_ok() {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    false
}

/// Current clipboard text, else PNG.
pub fn read() -> Option<Content> {
    if !open() {
        return None;
    }
    let get = |fmt: u32| -> Option<Vec<u8>> {
        // SAFETY: the clipboard is open; the handle is only read while locked.
        unsafe {
            let h = HGLOBAL(GetClipboardData(fmt).ok()?.0);
            let p = GlobalLock(h) as *const u8;
            if p.is_null() {
                return None;
            }
            let v = std::slice::from_raw_parts(p, GlobalSize(h)).to_vec();
            let _ = GlobalUnlock(h);
            Some(v)
        }
    };
    // SAFETY: plain availability checks on the open clipboard.
    let c = if unsafe { IsClipboardFormatAvailable(CF_UNICODETEXT.0 as u32) }.is_ok() {
        get(CF_UNICODETEXT.0 as u32).map(|b| {
            let units: Vec<u16> =
                b.as_chunks::<2>().0.iter().map(|c| u16::from_le_bytes(*c)).take_while(|u| *u != 0).collect();
            Content::Text(String::from_utf16_lossy(&units))
        })
    } else if unsafe { IsClipboardFormatAvailable(png_format()) }.is_ok() {
        get(png_format()).map(Content::Png)
    } else {
        None
    };
    // SAFETY: opened above.
    let _ = unsafe { CloseClipboard() };
    c
}

pub fn write(c: &Content) -> windows::core::Result<()> {
    let (fmt, bytes): (u32, Vec<u8>) = match c {
        Content::Text(t) => (CF_UNICODETEXT.0 as u32, t.encode_utf16().chain([0]).flat_map(u16::to_le_bytes).collect()),
        Content::Png(b) => (png_format(), b.clone()),
    };
    if !open() {
        return Err(windows::core::Error::from_thread());
    }
    // SAFETY: the clipboard is open; on success the system owns the HGLOBAL.
    let r = unsafe {
        EmptyClipboard().and_then(|_| {
            let h = GlobalAlloc(GMEM_MOVEABLE, bytes.len())?;
            let p = GlobalLock(h) as *mut u8;
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), p, bytes.len());
            let _ = GlobalUnlock(h);
            SetClipboardData(fmt, Some(HANDLE(h.0))).map(|_| ()).inspect_err(|_| {
                let _ = GlobalFree(Some(h));
            })
        })
    };
    // SAFETY: opened above.
    let _ = unsafe { CloseClipboard() };
    r
}

/// Clipboard changes, tagged with the phone that caused them (if any).
pub struct Hub {
    pub tx: broadcast::Sender<(Option<u64>, ServerMsg)>,
    guard: Mutex<EchoGuard>,
}

static HUB: OnceLock<Hub> = OnceLock::new();
static WATCH: Once = Once::new();

impl Hub {
    /// Writes a phone's clipboard; the change this causes is not echoed to `origin`.
    pub fn write(&self, c: &Content, origin: u64) {
        self.guard.lock().unwrap().wrote(c.bytes(), origin);
        let _ = write(c);
    }

    fn changed(&self) {
        let Some(c) = read() else { return };
        let origin = self.guard.lock().unwrap().origin_of(c.bytes());
        if within_limit(c.mime(), c.bytes().len()) {
            let _ = self.tx.send((origin, c.message()));
        }
    }
}

/// The hub, starting the clipboard listener on first use.
pub fn hub() -> &'static Hub {
    let h = HUB.get_or_init(|| Hub { tx: broadcast::channel(8).0, guard: Mutex::default() });
    WATCH.call_once(|| {
        std::thread::spawn(listen);
    });
    h
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    if msg == WM_CLIPBOARDUPDATE {
        if let Some(h) = HUB.get() {
            h.changed();
        }
        return LRESULT(0);
    }
    // SAFETY: default handling for everything else.
    unsafe { DefWindowProcW(hwnd, msg, w, l) }
}

/// A hidden message-only window receiving WM_CLIPBOARDUPDATE; blocks in GetMessage (no polling).
fn listen() {
    // SAFETY: window-class registration and a message loop on this dedicated thread.
    unsafe {
        let Ok(inst) = GetModuleHandleW(None) else { return };
        let class = WNDCLASSW { lpfnWndProc: Some(wndproc), hInstance: inst.into(), lpszClassName: w!("PCRemoteClip"), ..Default::default() };
        RegisterClassW(&class);
        let Ok(hwnd) = CreateWindowExW(
            Default::default(),
            w!("PCRemoteClip"),
            w!(""),
            Default::default(),
            0,
            0,
            0,
            0,
            Some(HWND_MESSAGE),
            None,
            Some(inst.into()),
            None,
        ) else {
            return;
        };
        if AddClipboardFormatListener(hwnd).is_err() {
            return;
        }
        let mut m = MSG::default();
        while GetMessageW(&mut m, None, 0, 0).as_bool() {
            DispatchMessageW(&m);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ignores_own_write() {
        let mut g = EchoGuard::default();
        g.wrote(b"from phone", 7);
        assert_eq!(g.origin_of(b"from phone"), Some(7), "the change our write caused is tagged with its phone");
        assert_eq!(g.origin_of(b"from phone"), None, "only once: a later real copy of the same text is sent");
        assert_eq!(g.origin_of(b"typed on PC"), None);
    }

    #[test]
    fn size_limits() {
        assert!(within_limit("text/plain", 1 << 20));
        assert!(!within_limit("text/plain", (1 << 20) + 1));
        assert!(within_limit("image/png", 8 << 20));
        assert!(!within_limit("image/png", (8 << 20) + 1));
        assert!(!within_limit("application/zip", 10));
    }

    #[test]
    fn content_round_trips_to_messages() {
        let c = Content::Text("héllo".into());
        assert_eq!(c.message(), ServerMsg::Clip { mime: "text/plain".into(), data: "héllo".into() });
        let png = Content::Png(vec![0x89, b'P', b'N', b'G']);
        let m = png.message();
        assert_eq!(m, ServerMsg::Clip { mime: "image/png".into(), data: "iVBORw==".into() });
        assert_eq!(Content::from_msg("image/png", "iVBORw=="), Some(png.clone()));
        assert_eq!(Content::from_msg("image/png", "iVBORw"), Some(png));
        assert_eq!(Content::from_msg("text/plain", "x"), Some(Content::Text("x".into())));
        assert_eq!(Content::from_msg("image/png", "!!!"), None);
        assert_eq!(Content::from_msg("text/html", "x"), None);
    }

    #[test]
    #[ignore = "needs an unlocked interactive desktop (the clipboard is unavailable on the lock screen)"]
    fn windows_clipboard_round_trip() {
        let marker = format!("pc-remote test {}", std::process::id());
        write(&Content::Text(marker.clone())).unwrap();
        assert_eq!(read(), Some(Content::Text(marker)));
    }
}
