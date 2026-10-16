//! Unlock from the phone: wake the screen, lift the lock curtain, type the PIN or password.
//! The secret comes from the phone's fingerprint-protected vault over the encrypted agent channel
//! and is never stored or logged here.

use crate::input::{Emitter, Ev, SendInputEmitter};
use std::time::Duration;

const VK_CONTROL: u16 = 0x11;
const VK_RETURN: u16 = 0x0D;

/// Key events that type `secret`: digits as real number keys (the Windows Hello PIN box only
/// takes those), anything else as Unicode characters.
pub fn keys_for(secret: &str) -> Vec<Ev> {
    let mut evs = Vec::new();
    for ch in secret.chars() {
        if ch.is_ascii_digit() {
            let vk = ch as u16; // '0'..'9' are VK 0x30..0x39
            evs.extend([Ev::Key { vk, down: true }, Ev::Key { vk, down: false }]);
        } else {
            for unit in ch.encode_utf16(&mut [0; 2]) {
                evs.extend([Ev::Unicode { unit: *unit, down: true }, Ev::Unicode { unit: *unit, down: false }]);
            }
        }
    }
    evs
}

/// Blocking (a few seconds): true when the PC is unlocked afterwards.
pub fn unlock(secret: &str) -> bool {
    if !crate::win::desk::is_locked() {
        return true;
    }
    let mut e = SendInputEmitter;
    crate::win::desk::wake_display();
    std::thread::sleep(Duration::from_millis(400));
    // Ctrl lifts the curtain without typing anything into the PIN box
    e.emit(&[Ev::Key { vk: VK_CONTROL, down: true }, Ev::Key { vk: VK_CONTROL, down: false }]);
    std::thread::sleep(Duration::from_millis(900));
    // With a fingerprint reader the sign-in screen asks for a finger: switch it to the PIN box
    if !logon::show_box() {
        crate::log::line("unlock: no PIN or password box found on the sign-in screen; typing anyway");
    }
    if secret.is_empty() {
        return false; // a dry run (diagnostics): bring up the box, type nothing
    }
    e.emit(&keys_for(secret));
    // a Windows Hello PIN signs in by itself; a password needs Enter
    std::thread::sleep(Duration::from_millis(700));
    if crate::win::desk::is_locked() {
        e.emit(&[Ev::Key { vk: VK_RETURN, down: true }, Ev::Key { vk: VK_RETURN, down: false }]);
    }
    for _ in 0..30 {
        std::thread::sleep(Duration::from_millis(200));
        if !crate::win::desk::is_locked() {
            return true;
        }
    }
    false
}

/// What to do next on the sign-in screen, from its visible elements (name, is a text box).
#[derive(Debug, PartialEq)]
pub enum Step {
    /// The PIN or password box is there: focus it and type.
    Focus(usize),
    /// Open a provider tile (PIN / Password) or "Sign-in options".
    Click(usize),
}

/// The PIN (Windows Hello) comes first; a password box or tile is the fallback.
pub fn next_step(items: &[(String, bool)]) -> Option<Step> {
    let has = |i: &(String, bool), w: &str| i.0.to_lowercase().contains(w);
    let tile = |i: &(String, bool), w: &str| !i.1 && has(i, w) && !has(i, "sign-in options") && !has(i, "forgot") && !has(i, "reset");
    let pos = |f: &dyn Fn(&(String, bool)) -> bool| items.iter().position(f);
    pos(&|i| i.1 && has(i, "pin"))
        .or_else(|| pos(&|i| i.1 && has(i, "password")))
        .map(Step::Focus)
        .or_else(|| pos(&|i| tile(i, "pin")).map(Step::Click))
        .or_else(|| pos(&|i| !i.1 && has(i, "sign-in options")).map(Step::Click))
        .or_else(|| pos(&|i| tile(i, "password")).map(Step::Click))
}

/// UI Automation on LogonUI (we run as SYSTEM on the Winlogon desktop, like Narrator does).
mod logon {
    use super::{next_step, Step};
    use std::time::Duration;
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED};
    use windows::Win32::UI::Accessibility::*;

    /// Brings up the PIN (or password) box and focuses it. True when it is focused.
    pub fn show_box() -> bool {
        crate::win::desk::follow();
        // SAFETY: COM calls on this thread; every interface is released when dropped.
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            let Ok(uia) = CoCreateInstance::<_, IUIAutomation>(&CUIAutomation, None, CLSCTX_INPROC_SERVER) else { return false };
            let mut seen = Vec::new();
            for _ in 0..6 {
                let Ok(cond) = uia.CreateTrueCondition() else { return false };
                let Ok(all) = uia.GetRootElement().and_then(|r| r.FindAll(TreeScope_Descendants, &cond)) else { return false };
                let mut els = Vec::new();
                for i in 0..all.Length().unwrap_or(0) {
                    let Ok(el) = all.GetElement(i) else { continue };
                    if el.CurrentIsOffscreen().is_ok_and(|o| o.as_bool()) {
                        continue;
                    }
                    let name = el.CurrentName().map(|b| b.to_string()).unwrap_or_default();
                    if name.is_empty() {
                        continue;
                    }
                    let edit = el.CurrentControlType().is_ok_and(|t| t == UIA_EditControlTypeId);
                    els.push((el, (name, edit)));
                }
                let items: Vec<(String, bool)> = els.iter().map(|(_, i)| i.clone()).collect();
                seen = items.iter().map(|i| i.0.clone()).collect();
                match next_step(&items) {
                    Some(Step::Focus(i)) => return els[i].0.SetFocus().is_ok(),
                    Some(Step::Click(i)) => {
                        let el = &els[i].0;
                        let invoked = el.GetCurrentPatternAs::<IUIAutomationInvokePattern>(UIA_InvokePatternId).and_then(|p| p.Invoke()).is_ok();
                        if !invoked {
                            let _ = el.SetFocus();
                            let mut e = crate::input::SendInputEmitter;
                            use crate::input::Emitter;
                            e.emit(&[crate::input::Ev::Key { vk: 0x20, down: true }, crate::input::Ev::Key { vk: 0x20, down: false }]);
                        }
                    }
                    None => {}
                }
                std::thread::sleep(Duration::from_millis(700));
            }
            // names only (never the secret), to fix this for other sign-in screens
            crate::log::line(&format!("unlock: sign-in screen shows {seen:?}"));
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn items(v: &[(&str, bool)]) -> Vec<(String, bool)> {
        v.iter().map(|(n, e)| (n.to_string(), *e)).collect()
    }

    #[test]
    fn walks_from_fingerprint_to_the_pin_box() {
        // the fingerprint prompt: open sign-in options
        let fp = items(&[("Sadik", false), ("Scan your finger on the fingerprint reader", false), ("Sign-in options", false)]);
        assert_eq!(next_step(&fp), Some(Step::Click(2)));
        // options open: pick the PIN tile, not the options link again
        let opts = items(&[("Sign-in options", false), ("Fingerprint", false), ("PIN (Windows Hello)", false), ("Password", false)]);
        assert_eq!(next_step(&opts), Some(Step::Click(2)));
        // no PIN set up: the password tile, once the options are open
        assert_eq!(next_step(&items(&[("Sign-in options", false), ("Password", false)])), Some(Step::Click(0)));
        assert_eq!(next_step(&items(&[("Fingerprint", false), ("Password", false)])), Some(Step::Click(1)));
        // the PIN box is up
        let pin = items(&[("I forgot my PIN", false), ("PIN", true), ("Sign-in options", false)]);
        assert_eq!(next_step(&pin), Some(Step::Focus(1)));
        assert_eq!(next_step(&items(&[("Password", true)])), Some(Step::Focus(0)));
        assert_eq!(next_step(&items(&[("Sadik", false)])), None);
    }

    #[test]
    fn digits_are_number_keys_and_the_rest_unicode() {
        let evs = keys_for("1a");
        assert_eq!(
            evs,
            vec![
                Ev::Key { vk: 0x31, down: true },
                Ev::Key { vk: 0x31, down: false },
                Ev::Unicode { unit: 'a' as u16, down: true },
                Ev::Unicode { unit: 'a' as u16, down: false },
            ]
        );
        assert!(keys_for("").is_empty());
    }
}
