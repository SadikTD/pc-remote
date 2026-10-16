#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::Manager;
use tauri_plugin_notification::NotificationExt;

fn main() {
    pc_remote_host::run_with(|b| {
        b.plugin(tauri_plugin_notification::init()).setup(|app| {
            let handle = app.app_handle().clone();
            pc_remote_host::notify::set_hook(Box::new(move |title, body| {
                let _ = handle.notification().builder().title(title).body(body).show();
            }));
            Ok(())
        })
    })
}
