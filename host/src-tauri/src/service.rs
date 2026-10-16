//! `--service`: the "PC Remote" Windows service (SYSTEM, session 0, starts at boot).
//! It keeps one `--server` running as SYSTEM in the console session — at the sign-in screen too —
//! and installs signed updates.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use windows::core::{w, PWSTR};
use windows::Win32::System::Services::*;

const NAME: windows::core::PCWSTR = w!("PC Remote");
static STOP: AtomicBool = AtomicBool::new(false);
static mut STATUS: SERVICE_STATUS_HANDLE = SERVICE_STATUS_HANDLE(std::ptr::null_mut());

const UPDATE_EVERY: Duration = Duration::from_secs(6 * 3600);
const FIRST_UPDATE_AFTER: Duration = Duration::from_secs(120);

fn set(state: SERVICE_STATUS_CURRENT_STATE) {
    let s = SERVICE_STATUS {
        dwServiceType: SERVICE_WIN32_OWN_PROCESS,
        dwCurrentState: state,
        dwControlsAccepted: if state == SERVICE_RUNNING { SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN } else { 0 },
        dwWaitHint: 5000,
        ..Default::default()
    };
    // SAFETY: STATUS is written once in `main` before any call here.
    unsafe {
        let _ = SetServiceStatus(*std::ptr::addr_of!(STATUS), &s);
    }
}

unsafe extern "system" fn handler(ctl: u32, _: u32, _: *mut core::ffi::c_void, _: *mut core::ffi::c_void) -> u32 {
    if ctl == SERVICE_CONTROL_STOP || ctl == SERVICE_CONTROL_SHUTDOWN {
        STOP.store(true, Ordering::SeqCst);
        set(SERVICE_STOP_PENDING);
    }
    0
}

unsafe extern "system" fn main(_: u32, _: *mut PWSTR) {
    // SAFETY: registration with a 'static handler; STATUS is only written here.
    unsafe {
        match RegisterServiceCtrlHandlerExW(NAME, Some(handler), None) {
            Ok(h) => STATUS = h,
            Err(_) => return,
        }
    }
    set(SERVICE_RUNNING);
    supervise();
    set(SERVICE_STOPPED);
}

/// Runs as a service; when started by hand (not by the SCM) it supervises in the foreground.
pub fn run() {
    let mut name: Vec<u16> = "PC Remote".encode_utf16().chain([0]).collect();
    let table = [
        SERVICE_TABLE_ENTRYW { lpServiceName: PWSTR(name.as_mut_ptr()), lpServiceProc: Some(main) },
        SERVICE_TABLE_ENTRYW::default(),
    ];
    // SAFETY: the table and its name outlive the dispatcher, which returns when the service stops.
    if unsafe { StartServiceCtrlDispatcherW(table.as_ptr()) }.is_err() {
        supervise();
    }
}

fn supervise() {
    use crate::win::session;
    lock_down(&crate::data_dir());
    crate::log::line("service: started");
    let mut server: Option<(session::Child, u32)> = None;
    let mut next_update = Instant::now() + FIRST_UPDATE_AFTER;
    while !STOP.load(Ordering::SeqCst) {
        if let Some(s) = session::console() {
            let stale = server.as_ref().is_none_or(|(c, sess)| !c.alive() || *sess != s);
            if stale {
                if let Some((c, _)) = server.take() {
                    c.kill();
                }
                server = session::system_token_for(s).and_then(|t| session::spawn(*t, "--server", false)).map(|c| (c, s));
                crate::log::line(&format!("service: server in session {s}: {}", if server.is_some() { "started" } else { "FAILED" }));
            }
        }
        if Instant::now() >= next_update {
            next_update = Instant::now() + UPDATE_EVERY;
            std::thread::spawn(|| {
                let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("runtime");
                match rt.block_on(crate::update::check_and_install(&crate::data_dir())) {
                    Ok(Some(v)) => crate::log::line(&format!("update: installing {v}")),
                    Ok(None) => {}
                    Err(e) => crate::log::line(&format!("update: {e}")),
                }
            });
        }
        std::thread::sleep(Duration::from_secs(2));
    }
    if let Some((c, _)) = server {
        c.kill();
    }
    crate::log::line("service: stopped");
}

/// The data folder holds device tokens and keys: SYSTEM and Administrators only, nothing inherited.
fn lock_down(dir: &std::path::Path) {
    use std::os::windows::process::CommandExt;
    let _ = std::fs::create_dir_all(dir);
    let _ = std::process::Command::new("icacls.exe")
        .arg(dir)
        .args(["/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", "/T", "/Q"])
        .creation_flags(0x0800_0000) // no console window
        .status();
}
