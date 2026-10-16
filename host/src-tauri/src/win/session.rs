//! The console session: who is logged on, and starting processes there (as SYSTEM or as the user).
//! Only the service and the server (both SYSTEM) call the token functions.

use std::path::PathBuf;
use windows::core::{Owned, PWSTR};
use windows::Win32::Foundation::{HANDLE, WAIT_TIMEOUT};
use windows::Win32::Security::{
    DuplicateTokenEx, SecurityImpersonation, SetTokenInformation, TokenPrimary, TokenSessionId, TOKEN_ALL_ACCESS,
    TOKEN_DUPLICATE, TOKEN_QUERY,
};
use windows::Win32::System::Environment::{CreateEnvironmentBlock, DestroyEnvironmentBlock};
use windows::Win32::System::RemoteDesktop::{
    ProcessIdToSessionId, WTSFreeMemory, WTSGetActiveConsoleSessionId, WTSQuerySessionInformationW, WTSQueryUserToken, WTSUserName,
};
use windows::Win32::System::Threading::{
    CreateProcessAsUserW, GetCurrentProcess, OpenProcessToken, TerminateProcess,
    WaitForSingleObject, CREATE_NO_WINDOW, CREATE_UNICODE_ENVIRONMENT, PROCESS_INFORMATION, STARTUPINFOW,
};
use windows::Win32::UI::Shell::GetUserProfileDirectoryW;

/// The physical console's session, or None while it is switching.
pub fn console() -> Option<u32> {
    // SAFETY: no arguments.
    let s = unsafe { WTSGetActiveConsoleSessionId() };
    (s != u32::MAX).then_some(s)
}

/// This process's session.
pub fn current() -> u32 {
    let mut s = 0;
    // SAFETY: out-param on the stack.
    let _ = unsafe { ProcessIdToSessionId(std::process::id(), &mut s) };
    s
}

/// The logged-on user's primary token in the console session (SYSTEM only). None at the sign-in screen.
pub fn user_token() -> Option<Owned<HANDLE>> {
    let mut t = HANDLE::default();
    // SAFETY: out-param; the handle is owned by the returned value.
    unsafe { WTSQueryUserToken(console()?, &mut t).ok().map(|_| Owned::new(t)) }
}

/// The console user's name ("" when nobody is logged on).
pub fn user_name() -> String {
    let Some(s) = console() else { return String::new() };
    let mut buf = PWSTR::null();
    let mut len = 0;
    // SAFETY: WTS allocates `buf`; we copy it then free it.
    unsafe {
        if WTSQuerySessionInformationW(None, s, WTSUserName, &mut buf, &mut len).is_err() {
            return String::new();
        }
        let name = buf.to_string().unwrap_or_default();
        WTSFreeMemory(buf.0 as _);
        name
    }
}

/// `C:\Users\<name>` of the user `token` belongs to.
pub fn profile_dir(token: HANDLE) -> Option<PathBuf> {
    let mut buf = [0u16; 260];
    let mut len = buf.len() as u32;
    // SAFETY: buf/len describe a writable buffer of `len` UTF-16 units.
    unsafe { GetUserProfileDirectoryW(token, Some(PWSTR(buf.as_mut_ptr())), &mut len).ok()? };
    let n = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
    Some(PathBuf::from(String::from_utf16_lossy(&buf[..n])))
}

/// The console user's profile, falling back to Public when nobody is logged on.
pub fn user_profile() -> PathBuf {
    user_token()
        .and_then(|t| profile_dir(*t))
        .or_else(|| std::env::var("PUBLIC").ok().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from(r"C:\Users\Public"))
}

/// Our own (SYSTEM) token, as a primary token for `session`.
pub fn system_token_for(session: u32) -> Option<Owned<HANDLE>> {
    // SAFETY: token handles are owned and closed by `Owned`.
    unsafe {
        let mut own = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY, &mut own).ok()?;
        let own = Owned::new(own);
        let mut dup = HANDLE::default();
        DuplicateTokenEx(*own, TOKEN_ALL_ACCESS, None, SecurityImpersonation, TokenPrimary, &mut dup).ok()?;
        let dup = Owned::new(dup);
        SetTokenInformation(*dup, TokenSessionId, &session as *const u32 as _, 4).ok()?;
        Some(dup)
    }
}

/// A process started by [spawn].
pub struct Child(Owned<HANDLE>);

impl Child {
    pub fn alive(&self) -> bool {
        // SAFETY: a live process handle.
        unsafe { WaitForSingleObject(*self.0, 0) == WAIT_TIMEOUT }
    }

    pub fn kill(&self) {
        // SAFETY: a live process handle.
        let _ = unsafe { TerminateProcess(*self.0, 0) };
    }
}

/// Starts `args` (after this exe) with `token`, on the interactive desktop of the token's session.
/// `user_env`: build the environment from the token (the user's), not inherit ours.
pub fn spawn(token: HANDLE, args: &str, user_env: bool) -> Option<Child> {
    let exe = std::env::current_exe().ok()?;
    let mut cmd: Vec<u16> = format!("\"{}\" {args}", exe.display()).encode_utf16().chain([0]).collect();
    let mut desk: Vec<u16> = "winsta0\\default".encode_utf16().chain([0]).collect();
    let si = STARTUPINFOW { cb: size_of::<STARTUPINFOW>() as u32, lpDesktop: PWSTR(desk.as_mut_ptr()), ..Default::default() };
    let mut pi = PROCESS_INFORMATION::default();
    // SAFETY: all buffers outlive the call; the environment block is destroyed after it; the
    // thread handle is closed and the process handle is owned by `Child`.
    unsafe {
        let mut env = std::ptr::null_mut();
        if user_env {
            let _ = CreateEnvironmentBlock(&mut env, Some(token), false);
        }
        let r = CreateProcessAsUserW(
            Some(token),
            None,
            Some(PWSTR(cmd.as_mut_ptr())),
            None,
            None,
            false,
            CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT,
            (!env.is_null()).then_some(env as *const _),
            None,
            &si,
            &mut pi,
        );
        if !env.is_null() {
            let _ = DestroyEnvironmentBlock(env);
        }
        r.ok()?;
        drop(Owned::new(pi.hThread));
        Some(Child(Owned::new(pi.hProcess)))
    }
}
