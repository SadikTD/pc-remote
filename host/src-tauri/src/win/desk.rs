//! The input desktop: the normal desktop, the lock/sign-in screen or a UAC prompt (both "Winlogon").
//! A SYSTEM thread can capture and inject into whichever one is showing, once it switches to it.

use std::cell::RefCell;
use windows::core::PWSTR;
use windows::Win32::Foundation::{GENERIC_ALL, HANDLE};
use windows::Win32::System::Power::{SetThreadExecutionState, ES_DISPLAY_REQUIRED, ES_SYSTEM_REQUIRED};
use windows::Win32::System::RemoteDesktop::{
    WTSFreeMemory, WTSQuerySessionInformationW, WTSSessionInfoEx, WTSINFOEXW, WTS_SESSIONSTATE_LOCK,
};
use windows::Win32::System::StationsAndDesktops::{
    CloseDesktop, GetUserObjectInformationW, OpenInputDesktop, SetThreadDesktop, DESKTOP_ACCESS_FLAGS,
    DF_ALLOWOTHERACCOUNTHOOK, HDESK, UOI_NAME,
};

thread_local! {
    /// The desktop this thread was switched to by [follow].
    static CURRENT: RefCell<Option<(HDESK, String)>> = const { RefCell::new(None) };
}

fn name(d: HDESK) -> String {
    let mut buf = [0u16; 64];
    // SAFETY: buf is writable for its byte length.
    let _ = unsafe { GetUserObjectInformationW(HANDLE(d.0), UOI_NAME, Some(buf.as_mut_ptr().cast()), (buf.len() * 2) as u32, None) };
    let n = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..n])
}

/// The input desktop's name ("Default", "Winlogon", "Screen-saver"), if we may open it.
pub fn input_name() -> Option<String> {
    // SAFETY: the handle is closed before returning.
    unsafe {
        let d = OpenInputDesktop(DF_ALLOWOTHERACCOUNTHOOK, false, DESKTOP_ACCESS_FLAGS(GENERIC_ALL.0)).ok()?;
        let n = name(d);
        let _ = CloseDesktop(d);
        Some(n)
    }
}

/// Moves the calling thread to the input desktop if it changed; true when it switched.
/// Fails harmlessly (false) for threads that own windows, or without SYSTEM rights.
pub fn follow() -> bool {
    // SAFETY: desktop handles are closed once replaced; the thread's current one stays open.
    unsafe {
        let Ok(d) = OpenInputDesktop(DF_ALLOWOTHERACCOUNTHOOK, false, DESKTOP_ACCESS_FLAGS(GENERIC_ALL.0)) else { return false };
        let n = name(d);
        let same = CURRENT.with(|c| c.borrow().as_ref().is_some_and(|(_, cur)| *cur == n));
        if same || SetThreadDesktop(d).is_err() {
            let _ = CloseDesktop(d);
            return false;
        }
        if let Some((old, _)) = CURRENT.with(|c| c.borrow_mut().replace((d, n))) {
            let _ = CloseDesktop(old);
        }
        true
    }
}

/// The console session is locked, or at the sign-in screen with nobody logged on.
pub fn is_locked() -> bool {
    let Some(s) = super::session::console() else { return false };
    let mut buf = PWSTR::null();
    let mut len = 0;
    // SAFETY: WTS allocates `buf` (a WTSINFOEXW); we read it then free it.
    unsafe {
        if WTSQuerySessionInformationW(None, s, WTSSessionInfoEx, &mut buf, &mut len).is_err() {
            return false;
        }
        let info = &*(buf.0 as *const WTSINFOEXW);
        let l1 = &info.Data.WTSInfoExLevel1;
        let locked = l1.SessionFlags as u32 == WTS_SESSIONSTATE_LOCK || l1.UserName[0] == 0;
        WTSFreeMemory(buf.0 as _);
        locked
    }
}

/// Turns the display on (it may be asleep from inactivity) and resets the idle timers.
pub fn wake_display() {
    use windows::Win32::UI::Input::KeyboardAndMouse::{SendInput, INPUT, INPUT_0, INPUT_MOUSE, MOUSEEVENTF_MOVE, MOUSEINPUT};
    // SAFETY: flag-only call, and SendInput with two valid structs.
    unsafe {
        SetThreadExecutionState(ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED);
        follow();
        let mv = |dx| INPUT { r#type: INPUT_MOUSE, Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy: 0, dwFlags: MOUSEEVENTF_MOVE, ..Default::default() } } };
        SendInput(&[mv(1), mv(-1)], size_of::<INPUT>() as i32);
    }
}

/// Resets the display idle timer while someone is using the PC remotely.
pub fn keep_awake() {
    // SAFETY: flag-only call.
    unsafe { SetThreadExecutionState(ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED) };
}
