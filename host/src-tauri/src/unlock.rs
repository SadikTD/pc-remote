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

#[cfg(test)]
mod tests {
    use super::*;

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
