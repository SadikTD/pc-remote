pub mod sunshine;
pub mod agent;
pub mod store;
pub mod tls;
pub mod win;
pub mod proto;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

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

pub fn run() {
    let hidden = std::env::args().any(|a| a == "--hidden");
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| show_window(app)))
        .setup(move |app| {
            let open = MenuItem::with_id(app, "open", "Open", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            TrayIconBuilder::with_id("tray")
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
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = e
                    {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;
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
                api.prevent_exit();
            }
        });
}
