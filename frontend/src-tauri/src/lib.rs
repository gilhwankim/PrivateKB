mod document_import;
mod upload_transport;
mod document_source;
mod runtime;
mod log_management;

use document_import::{
    discard_document_selection, import_document_selection, select_document_files,
    select_document_folder, DocumentSelectionStore,
};
use runtime::{RuntimeStatusView, RuntimeSupervisor};
use tauri::{Manager, State};

#[cfg(desktop)]
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
};

#[cfg(desktop)]
const TRAY_OPEN_MENU_ID: &str = "privatekb-tray-open";
#[cfg(desktop)]
const TRAY_EXIT_MENU_ID: &str = "privatekb-tray-exit";
#[cfg(desktop)]
const MAINTENANCE_SHUTDOWN_ARG: &str = "--privatekb-maintenance-shutdown";

#[cfg(desktop)]
fn maintenance_shutdown_requested(args: &[String]) -> bool {
    args.iter().any(|arg| arg == MAINTENANCE_SHUTDOWN_ARG)
}

#[cfg(desktop)]
fn shutdown_and_exit(app: &tauri::AppHandle) {
    if let Some(runtime) = app.try_state::<RuntimeSupervisor>() {
        runtime.shutdown();
    }
    app.exit(0);
}

#[cfg(desktop)]
fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

#[cfg(desktop)]
fn install_tray(app: &mut tauri::App) -> tauri::Result<()> {
    let open_item = MenuItem::with_id(
        app,
        TRAY_OPEN_MENU_ID,
        "PrivateKB 열기",
        true,
        None::<&str>,
    )?;
    let exit_item =
        MenuItem::with_id(app, TRAY_EXIT_MENU_ID, "종료", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_item, &exit_item])?;

    let mut tray = TrayIconBuilder::new()
        .tooltip("PrivateKB")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            TRAY_OPEN_MENU_ID => show_main_window(app),
            TRAY_EXIT_MENU_ID => shutdown_and_exit(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if matches!(
                event,
                TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                }
            ) {
                show_main_window(tray.app_handle());
            }
        });

    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }

    tray.build(app)?;
    Ok(())
}

#[tauri::command]
fn get_runtime_status(runtime: State<'_, RuntimeSupervisor>) -> RuntimeStatusView {
    runtime.status()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let mut builder = tauri::Builder::default();

    #[cfg(desktop)]
    let maintenance_shutdown = maintenance_shutdown_requested(
        &std::env::args().collect::<Vec<_>>(),
    );

    #[cfg(desktop)]
    {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if maintenance_shutdown_requested(&args) {
                shutdown_and_exit(app);
            } else {
                show_main_window(app);
            }
        }));
    }

    let app = builder
        .invoke_handler(tauri::generate_handler![
            select_document_files,
            select_document_folder,
            discard_document_selection,
            import_document_selection,
            document_import::get_document_selection_exclusions,
            document_source::reveal_document_source,
            get_runtime_status
        ])
        .setup(move |app| {
            let runtime = RuntimeSupervisor::new();

            #[cfg(desktop)]
            if maintenance_shutdown {
                app.manage(runtime);
                app.manage(DocumentSelectionStore::default());
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.hide();
                }
                app.handle().exit(0);
                return Ok(());
            }

            runtime.start(app.handle());
            app.manage(runtime);
            app.manage(DocumentSelectionStore::default());

            #[cfg(desktop)]
            install_tray(app)?;

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("PrivateKB 데스크톱 애플리케이션을 구성할 수 없습니다.");

    app.run(|app_handle, event| match event {
        #[cfg(desktop)]
        tauri::RunEvent::WindowEvent {
            label,
            event: tauri::WindowEvent::CloseRequested { api, .. },
            ..
        } if label == "main" => {
            api.prevent_close();
            if let Some(window) = app_handle.get_webview_window("main") {
                let _ = window.hide();
            }
        }
        tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit => {
            if let Some(runtime) = app_handle.try_state::<RuntimeSupervisor>() {
                runtime.shutdown();
            }
        }
        _ => {}
    });
}

#[cfg(all(test, desktop))]
mod tests {
    use super::{maintenance_shutdown_requested, MAINTENANCE_SHUTDOWN_ARG};

    #[test]
    fn recognizes_only_exact_maintenance_shutdown_argument() {
        assert!(maintenance_shutdown_requested(&[
            "PrivateKB.exe".to_owned(),
            MAINTENANCE_SHUTDOWN_ARG.to_owned(),
        ]));
        assert!(!maintenance_shutdown_requested(&[
            "PrivateKB.exe".to_owned(),
            format!("{MAINTENANCE_SHUTDOWN_ARG}=true"),
        ]));
    }
}
