//! Lock, sleep, restart, shut down and sign out.

use windows::core::w;
use windows::Win32::Foundation::{CloseHandle, HANDLE, LUID};
use windows::Win32::Security::{
    AdjustTokenPrivileges, LookupPrivilegeValueW, SE_PRIVILEGE_ENABLED, TOKEN_ADJUST_PRIVILEGES, TOKEN_PRIVILEGES,
    TOKEN_QUERY,
};
use windows::Win32::System::Power::SetSuspendState;
use windows::Win32::System::Shutdown::{
    ExitWindowsEx, LockWorkStation, EWX_LOGOFF, EWX_POWEROFF, EWX_REBOOT, SHTDN_REASON_FLAG_PLANNED,
};
use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Action {
    Lock,
    Sleep,
    Restart,
    Shutdown,
    SignOut,
}

impl Action {
    pub fn parse(s: &str) -> Result<Self, &'static str> {
        Ok(match s {
            "lock" => Action::Lock,
            "sleep" => Action::Sleep,
            "restart" => Action::Restart,
            "shutdown" => Action::Shutdown,
            "signout" => Action::SignOut,
            _ => return Err("bad_request"),
        })
    }
}

fn enable_shutdown_privilege() -> windows::core::Result<()> {
    // SAFETY: plain Win32 calls on our own process token, closed before return.
    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, &mut token)?;
        let mut luid = LUID::default();
        let r = LookupPrivilegeValueW(None, w!("SeShutdownPrivilege"), &mut luid).and_then(|_| {
            let mut tp = TOKEN_PRIVILEGES { PrivilegeCount: 1, ..Default::default() };
            tp.Privileges[0].Luid = luid;
            tp.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED;
            AdjustTokenPrivileges(token, false, Some(&tp), 0, None, None)
        });
        let _ = CloseHandle(token);
        r
    }
}

/// Runs a protocol power action; errors are protocol codes.
pub fn power(action: &str) -> Result<(), &'static str> {
    let a = Action::parse(action)?;
    // SAFETY: argument-free / flag-only Win32 calls.
    let ok = unsafe {
        match a {
            Action::Lock => LockWorkStation().is_ok(),
            Action::Sleep => SetSuspendState(false, false, false),
            Action::SignOut => ExitWindowsEx(EWX_LOGOFF, SHTDN_REASON_FLAG_PLANNED).is_ok(),
            Action::Restart | Action::Shutdown => {
                let flags = if a == Action::Restart { EWX_REBOOT } else { EWX_POWEROFF };
                enable_shutdown_privilege().is_ok() && ExitWindowsEx(flags, SHTDN_REASON_FLAG_PLANNED).is_ok()
            }
        }
    };
    if ok { Ok(()) } else { Err("internal") }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_actions() {
        assert_eq!(Action::parse("lock"), Ok(Action::Lock));
        assert_eq!(Action::parse("sleep"), Ok(Action::Sleep));
        assert_eq!(Action::parse("restart"), Ok(Action::Restart));
        assert_eq!(Action::parse("shutdown"), Ok(Action::Shutdown));
        assert_eq!(Action::parse("signout"), Ok(Action::SignOut));
        assert_eq!(Action::parse("LOCK"), Err("bad_request"));
        assert_eq!(Action::parse("hibernate"), Err("bad_request"));
        assert_eq!(Action::parse(""), Err("bad_request"));
    }
}
