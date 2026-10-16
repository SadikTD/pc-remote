//! Toasts. In the server, the hook forwards them to the tray over the pipe; in the tray (main.rs)
//! it shows them with the notification plugin, which only the app binary links.

use std::path::Path;
use std::sync::OnceLock;

type Hook = Box<dyn Fn(&str, &str) + Send + Sync>;
static HOOK: OnceLock<Hook> = OnceLock::new();

pub fn set_hook(h: Hook) {
    let _ = HOOK.set(h);
}

/// Shows (or forwards) a toast with (title, body).
pub fn show(title: &str, body: &str) {
    if let Some(h) = HOOK.get() {
        h(title, body);
    }
}

pub fn file_received(path: &Path) {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let folder = path.parent().map(|d| d.to_string_lossy().into_owned()).unwrap_or_default();
    show("File received", &format!("{name}\nSaved to {folder}"));
}
