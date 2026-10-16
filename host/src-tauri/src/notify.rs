//! "File received" toasts. The notification plugin is linked only into the app binary (main.rs),
//! not the library its tests build, so this module just holds the hook main.rs installs.

use std::path::Path;
use std::sync::OnceLock;

type Hook = Box<dyn Fn(&str, &str) + Send + Sync>;
static HOOK: OnceLock<Hook> = OnceLock::new();

/// Installed by main.rs: shows a toast with (title, body).
pub fn set_hook(h: Hook) {
    let _ = HOOK.set(h);
}

pub fn file_received(path: &Path) {
    let Some(h) = HOOK.get() else { return };
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let folder = path.parent().map(|d| d.to_string_lossy().into_owned()).unwrap_or_default();
    h("File received", &format!("{name}
Saved to {folder}"));
}
