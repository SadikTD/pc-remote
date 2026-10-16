//! The host runs elevated so its injected input reaches admin windows (Task Manager, installers…):
//! Windows drops input sent from a normal process to a higher-integrity window.
//!
//! The installer registers an on-demand "PC Remote" scheduled task that starts the host with the
//! highest privileges and no UAC prompt. A normal launch (Start menu, HKCU Run autostart, Explorer
//! "Send to phone") starts that task if needed and hands its arguments to the elevated copy the way
//! tauri-plugin-single-instance does, then exits. Without the task the host runs as before.

use std::os::windows::process::CommandExt;
use std::time::{Duration, Instant};
use windows::core::HSTRING;
use windows::Win32::Foundation::{CloseHandle, HANDLE, LPARAM, WPARAM};
use windows::Win32::Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY};
use windows::Win32::System::DataExchange::COPYDATASTRUCT;
use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};
use windows::Win32::UI::WindowsAndMessaging::{
    AllowSetForegroundWindow, ChangeWindowMessageFilter, FindWindowW, GetWindowThreadProcessId, SendMessageTimeoutW,
    MSGFLT_ADD, SMTO_NORMAL, WM_COPYDATA,
};

pub const TASK: &str = "PC Remote";
// tauri-plugin-single-instance's window (identifier + "-sic"/"-siw") and its NUL-separated payload id
const SI_CLASS: &str = "com.pcremote.host-sic";
const SI_WINDOW: &str = "com.pcremote.host-siw";
const SI_DATA_NUL: usize = 1543;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub fn is_elevated() -> bool {
    // SAFETY: token query on our own process; the handle is closed.
    unsafe {
        let mut tok = HANDLE::default();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut tok).is_err() {
            return false;
        }
        let mut e = TOKEN_ELEVATION::default();
        let mut len = 0;
        let ok = GetTokenInformation(tok, TokenElevation, Some(&mut e as *mut _ as _), size_of::<TOKEN_ELEVATION>() as u32, &mut len).is_ok();
        let _ = CloseHandle(tok);
        ok && e.TokenIsElevated != 0
    }
}

/// In the elevated copy: let normal-integrity launches deliver their arguments (WM_COPYDATA).
pub fn accept_handoff() {
    // SAFETY: plain process-wide message filter change.
    let _ = unsafe { ChangeWindowMessageFilter(WM_COPYDATA, MSGFLT_ADD) };
}

/// True if this (normal) launch was handed to the elevated copy and should exit now.
pub fn hand_off() -> bool {
    if is_elevated() {
        return false;
    }
    if let Some(w) = instance() {
        return forward(w);
    }
    // not running yet: start it through the task (fails fast when the task doesn't exist)
    let started = std::process::Command::new("schtasks.exe")
        .args(["/Run", "/TN", TASK])
        .creation_flags(CREATE_NO_WINDOW)
        .status()
        .is_ok_and(|s| s.success());
    if !started {
        return false;
    }
    let t = Instant::now();
    while t.elapsed() < Duration::from_secs(15) {
        std::thread::sleep(Duration::from_millis(100));
        if let Some(w) = instance() {
            return forward(w);
        }
    }
    false
}

fn instance() -> Option<windows::Win32::Foundation::HWND> {
    // SAFETY: FindWindowW with NUL-terminated strings that outlive the call.
    unsafe { FindWindowW(&HSTRING::from(SI_CLASS), &HSTRING::from(SI_WINDOW)).ok() }
}

/// Sends cwd and argv like a second instance would; the elevated copy's single-instance callback runs.
fn forward(w: windows::Win32::Foundation::HWND) -> bool {
    let mut data = std::env::current_dir().unwrap_or_default().to_string_lossy().into_owned();
    for a in std::env::args_os() {
        data.push('\0');
        data.push_str(&a.to_string_lossy());
    }
    let cds = COPYDATASTRUCT { dwData: SI_DATA_NUL, cbData: data.len() as u32, lpData: data.as_ptr() as _ };
    // SAFETY: `cds` and `data` outlive the synchronous send.
    unsafe {
        let mut pid = 0;
        GetWindowThreadProcessId(w, Some(&mut pid));
        if pid != 0 {
            let _ = AllowSetForegroundWindow(pid);
        }
        SendMessageTimeoutW(w, WM_COPYDATA, WPARAM(0), LPARAM(&cds as *const _ as isize), SMTO_NORMAL, 10_000, None).0 != 0
    }
}
