pub mod agent;
pub mod clip;
pub mod commands;
pub mod engine;
pub mod files;
pub mod host;
pub mod input;
pub mod ipc;
pub mod log;
pub mod mdns;
pub mod media;
pub mod net;
pub mod notify;
pub mod pad;
pub mod power;
pub mod proto;
pub mod prs;
pub mod remote;
pub mod server;
pub mod service;
pub mod store;
pub mod thumb;
pub mod tls;
pub mod unlock;
pub mod update;
pub mod win;

use serde_json::{json, Value};
use std::path::PathBuf;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter as _, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

/// `C:\ProgramData\PC Remote`: the server's store, TLS identity and log (SYSTEM + Administrators only).
pub fn data_dir() -> PathBuf {
    PathBuf::from(std::env::var("PROGRAMDATA").unwrap_or_else(|_| r"C:\ProgramData".into())).join("PC Remote")
}

/// The tray process's link to the server.
struct Ui {
    pipe: ipc::Client,
}

/// Shows the main window, creating its webview if it was destroyed on close.
pub fn show_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
        return;
    }
    let built = WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
        .title("PC Remote")
        .inner_size(440.0, 680.0)
        .resizable(false)
        .maximizable(false)
        .decorations(false)
        .transparent(true)
        .center()
        .build();
    if let Ok(w) = built {
        let _ = window_vibrancy::apply_mica(&w, Some(true));
    }
}

fn send_arg(argv: &[String]) -> Option<String> {
    let i = argv.iter().position(|a| a == "--send")?;
    argv.get(i + 1).cloned()
}

fn pipe(app: &AppHandle) -> ipc::Client {
    app.state::<Ui>().pipe.clone()
}

/// Every window command the server answers (crate::commands::dispatch).
#[tauri::command]
async fn call(app: AppHandle, cmd: String, args: Option<Value>) -> Result<Value, String> {
    pipe(&app).call(&cmd, args.unwrap_or(Value::Null)).await
}

/// "Send file" on a connected phone: the file picker runs here, as the user; the server sends it.
#[tauri::command]
fn send_file(app: AppHandle, window: tauri::WebviewWindow, device: String, folder: Option<bool>) -> Result<bool, String> {
    let Some(path) = win::sys::pick(window.hwnd().ok(), folder.unwrap_or(false)) else { return Ok(false) };
    let n = tauri::async_runtime::block_on(pipe(&app).call("offer", json!({"device": device, "path": path})))?;
    Ok(n.as_u64().unwrap_or(0) > 0)
}

/// Settings → Received files → Change.
#[tauri::command]
fn pick_files_dir(app: AppHandle, window: tauri::WebviewWindow) -> Result<Option<String>, String> {
    let Some(dir) = win::sys::pick(window.hwnd().ok(), true) else { return Ok(None) };
    let dir = dir.to_string_lossy().into_owned();
    tauri::async_runtime::block_on(pipe(&app).call("set_files_dir", json!({"dir": dir})))?;
    Ok(Some(dir))
}

#[tauri::command]
async fn open_files_dir(app: AppHandle) -> Result<(), String> {
    let dir = pipe(&app).call("files_dir", Value::Null).await?;
    let dir = dir.as_str().ok_or("no folder")?;
    std::process::Command::new("explorer.exe").arg(dir).spawn().map(|_| ()).map_err(|e| e.to_string())
}

/// Opens the project's GitHub releases page in the default browser.
#[tauri::command]
fn open_release(url: String) -> Result<(), String> {
    if !url.starts_with("https://github.com/SadikTD/") {
        return Err("refused".into());
    }
    std::process::Command::new("explorer.exe").arg(url).spawn().map(|_| ()).map_err(|e| e.to_string())
}

/// The tray and window, as the signed-in user. Without the service running, the window says so.
pub fn run() {
    run_with(|b| b)
}

/// Like [run], letting the binary add plugins the library (and its tests) shouldn't link.
pub fn run_with(extra: impl FnOnce(tauri::Builder<tauri::Wry>) -> tauri::Builder<tauri::Wry>) {
    let hidden = std::env::args().any(|a| a == "--hidden");
    extra(tauri::Builder::default())
        .plugin(tauri_plugin_single_instance::init(move |app, argv, _| {
            if argv.iter().any(|a| a == "--hidden") {
                return; // the sign-in start found the tray already running
            }
            match send_arg(&argv) {
                // Explorer "Send to phone" starts a second instance with --send <path>
                Some(path) => {
                    let p = pipe(app);
                    tauri::async_runtime::spawn(async move { p.call("offer", json!({"path": path})).await });
                }
                None => show_window(app),
            }
        }))
        .invoke_handler(tauri::generate_handler![call, send_file, pick_files_dir, open_files_dir, open_release])
        .setup(move |app| {
            let _ = win::sys::register_send_verb();
            let open = MenuItem::with_id(app, "open", "Open PC Remote", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Hide from tray", true, None::<&str>)?;
            let tray = TrayIconBuilder::with_id("tray")
                .icon(app.default_window_icon().cloned().expect("icon"))
                .tooltip("PC Remote")
                .menu(&Menu::with_items(app, &[&open, &quit])?)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, e| match e.id.as_ref() {
                    "open" => show_window(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, e| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = e {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;

            // Server events → tray tooltip, toasts and the window (if open). No polling.
            let handle = app.handle().clone();
            let (etx, mut erx) = tokio::sync::mpsc::unbounded_channel::<Value>();
            let client = ipc::Client::start(ipc::PIPE, move |v| {
                let _ = etx.send(v);
            });
            app.manage(Ui { pipe: client.clone() });
            tauri::async_runtime::spawn(async move {
                while let Some(v) = erx.recv().await {
                    let ev = v["ev"].as_str().unwrap_or_default().to_string();
                    if ev == "toast" {
                        notify::show(v["title"].as_str().unwrap_or_default(), v["body"].as_str().unwrap_or_default());
                        continue;
                    }
                    if let Ok(s) = client.call("get_state", Value::Null).await {
                        let n = s["devices"].as_array().map_or(0, |d| d.iter().filter(|d| d["connected"] == true).count());
                        let tip = match n {
                            0 => "PC Remote".to_string(),
                            1 => "PC Remote · 1 phone connected".to_string(),
                            n => format!("PC Remote · {n} phones connected"),
                        };
                        let _ = tray.set_tooltip(Some(tip));
                    }
                    let _ = handle.emit(&ev, v.clone());
                }
            });
            if !hidden {
                show_window(app.handle());
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("tauri app")
        .run(|_, e| {
            // Closing the window destroys the webview; the tray process lives on.
            if let RunEvent::ExitRequested { code: None, api, .. } = e {
                api.prevent_exit()
            }
        });
}
