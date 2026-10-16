pub mod agent;
pub mod clip;
pub mod commands;
pub mod engine;
pub mod files;
pub mod host;
pub mod input;
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
pub mod store;
pub mod thumb;
pub mod tls;
pub mod win;

use agent::{AgentHandle, Event};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use store::Store;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter as _, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

/// Shared state for the Tauri commands.
pub struct App {
    pub store: Arc<Mutex<Store>>,
    pub agent: AgentHandle,
    pub fp: String,
    pub outgoing: files::Outgoing,
    pub net: Arc<Mutex<net::NetStatus>>,
    /// LAN discovery, withdrawn when the app quits.
    pub mdns: Mutex<Option<mdns::Advert>>,
}

pub fn data_dir() -> PathBuf {
    PathBuf::from(std::env::var("APPDATA").unwrap_or_else(|_| ".".into())).join("PC Remote")
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
        .inner_size(420.0, 640.0)
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

fn send_arg(argv: &[String]) -> Option<PathBuf> {
    let i = argv.iter().position(|a| a == "--send")?;
    argv.get(i + 1).map(PathBuf::from)
}

pub fn run() {
    run_with(|b| b)
}

/// Like [run], letting the binary add plugins the library (and its tests) shouldn't link.
pub fn run_with(extra: impl FnOnce(tauri::Builder<tauri::Wry>) -> tauri::Builder<tauri::Wry>) {
    let dir = data_dir();
    // reqwest (update check, TURN credentials) uses rustls with no built-in provider: install ring once
    let _ = tokio_rustls::rustls::crypto::ring::default_provider().install_default();
    let hidden = std::env::args().any(|a| a == "--hidden");
    extra(tauri::Builder::default())
        .plugin(tauri_plugin_single_instance::init(|app, argv, _| {
            // Explorer "Send to phone" starts a second instance with --send <path>.
            if argv.iter().any(|a| a == "--pair-url") {
                // `pc-remote-host --pair-url` writes a fresh pairing link (for pairing without a camera)
                let _ = std::fs::write(data_dir().join("pair-url.txt"), commands::pair_url(&app.state::<App>()));
                return;
            }
            match send_arg(&argv) {
                Some(path) => {
                    let _ = commands::offer_to(&app.state::<App>(), None, &path);
                }
                None => show_window(app),
            }
        }))
        .invoke_handler(tauri::generate_handler![
            commands::get_state,
            commands::new_qr,
            commands::revoke,
            commands::rename,
            commands::get_config,
            commands::set_config,
            commands::open_files_dir,
            commands::check_update,
            commands::open_release,
            commands::send_file,
            commands::pick_files_dir,
            commands::get_net,
        ])
        .setup(move |app| {
            let identity = tls::Identity::load_or_create(&dir)?;
            let store = Arc::new(Mutex::new(Store::open(&dir)?));
            let host = Arc::new(host::Host::new(store.clone()));
            let (outgoing, net) = (host.outgoing.clone(), host.net.clone());
            if store.lock().unwrap().config().anywhere {
                let net = net.clone();
                tauri::async_runtime::spawn(async move { *net.lock().unwrap() = net::apply_now(true).await });
            }
            let _ = win::sys::register_send_verb();
            let agent = tauri::async_runtime::block_on(agent::Agent::start(
                &identity,
                store.clone(),
                host,
                "0.0.0.0:47999".parse().unwrap(),
                Duration::from_secs(30),
            ))?;
            if let Some(url) = remote::url() {
                tauri::async_runtime::spawn(remote::rendezvous::run(agent.clone(), store.clone(), url.into()));
            }
            let mut events = agent.events();
            let advert = mdns::Advert::start(store.lock().unwrap().pc_id(), &win::sys::pc_name());
            let mdns = Mutex::new(advert);
            app.manage(App { store, agent: agent.clone(), fp: identity.fingerprint_hex.clone(), outgoing, net, mdns });

            let open = MenuItem::with_id(app, "open", "Open", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
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

            // Agent events → tray tooltip and the window (if open). No polling.
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                while let Ok(e) = events.recv().await {
                    let tip = match agent.connected().len() {
                        0 => "PC Remote".to_string(),
                        1 => "PC Remote · 1 phone connected".to_string(),
                        n => format!("PC Remote · {n} phones connected"),
                    };
                    let _ = tray.set_tooltip(Some(tip));
                    let _ = handle.emit(if matches!(e, Event::Paired { .. }) { "paired" } else { "devices" }, ());
                }
            });
            if !hidden {
                show_window(app.handle());
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("tauri app")
        .run(|app, e| match e {
            // Closing the window destroys the webview; the tray process lives on.
            RunEvent::ExitRequested { code: None, api, .. } => api.prevent_exit(),
            RunEvent::Exit => drop(app.state::<App>().mdns.lock().unwrap().take()),
            _ => {}
        });
}
