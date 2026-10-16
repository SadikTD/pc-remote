//! v2 streaming engine: capture → NV12 → hardware encode → PRS over UDP (docs/stream-protocol.md).
//! Nothing here runs until a phone sends `stream.start`.

pub mod audio;
pub mod bitrate;
pub mod capture;
pub mod convert;
pub mod cursor;
pub mod encode;
pub mod input;
pub mod session;

use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};

/// The host clock for `captureUs` and PROBE `t`: QPC in microseconds (what DXGI and WASAPI stamp).
pub fn now_us() -> u64 {
    let mut c = 0i64;
    // SAFETY: plain out-param call.
    unsafe {
        let _ = QueryPerformanceCounter(&mut c);
    }
    qpc_to_us(c)
}

pub fn qpc_to_us(ticks: i64) -> u64 {
    static FREQ: std::sync::OnceLock<i64> = std::sync::OnceLock::new();
    let f = *FREQ.get_or_init(|| {
        let mut f = 0i64;
        // SAFETY: plain out-param call.
        unsafe {
            let _ = QueryPerformanceFrequency(&mut f);
        }
        f.max(1)
    });
    (ticks as i128 * 1_000_000 / f as i128) as u64
}

/// COM (multithreaded) for the calling engine thread, released when dropped.
pub struct Com;

impl Com {
    pub fn init() -> Com {
        // SAFETY: balanced by CoUninitialize in Drop on the same thread.
        unsafe {
            let _ = windows::Win32::System::Com::CoInitializeEx(None, windows::Win32::System::Com::COINIT_MULTITHREADED);
        }
        Com
    }
}

impl Drop for Com {
    fn drop(&mut self) {
        // SAFETY: paired with init on this thread.
        unsafe { windows::Win32::System::Com::CoUninitialize() }
    }
}
