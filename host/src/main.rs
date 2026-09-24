//! Mobile Claude Host: a tray app that lets the Mobile Claude phone app
//! into this PC over Tailscale. No setup commands, no admin rights: the
//! first time a phone connects, a dialog asks whether to allow it.

#![cfg_attr(windows, windows_subsystem = "windows")]

mod approve;
mod autostart;
mod files;
mod server;
mod shot;
mod store;

use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use tao::event::{Event, StartCause};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tray_icon::menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, TrayIconBuilder};

/// The phone app looks for this port on the PC.
pub const PORT: u16 = 2222;

enum UserEvent {
    Menu(MenuEvent),
    Changed,
}

fn main() {
    init_log();

    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let changed = proxy.clone();
    let state = server::State::new(Box::new(move || {
        let _ = changed.send_event(UserEvent::Changed);
    }));

    if let Err(e) = start_server(state.clone()) {
        approve::info(
            "Mobile Claude",
            &format!("Başlatılamadı: {e:#}\n\nUygulama zaten çalışıyor olabilir (sistem tepsisine bak)."),
        );
        return;
    }

    if autostart::first_run() {
        autostart::set(true);
    }

    let menu_proxy = proxy.clone();
    MenuEvent::set_event_handler(Some(move |e| {
        let _ = menu_proxy.send_event(UserEvent::Menu(e));
    }));

    let status = MenuItem::new("Başlatılıyor…", false, None);
    let address = MenuItem::new("", false, None);
    let devices = MenuItem::new("", false, None);
    let reset = MenuItem::new("İzinli telefonları sıfırla", true, None);
    let autorun = CheckMenuItem::new("Oturum açınca başlat", true, autostart::is_enabled(), None);
    let quit = MenuItem::new("Çıkış", true, None);
    let menu = Menu::new();
    let _ = menu.append_items(&[
        &status,
        &address,
        &devices,
        &PredefinedMenuItem::separator(),
        &reset,
        &autorun,
        &PredefinedMenuItem::separator(),
        &quit,
    ]);

    let refresh = {
        let (status, address, devices, state) = (status.clone(), address.clone(), devices.clone(), state.clone());
        move |tray: Option<&tray_icon::TrayIcon>| {
            let n = state.sessions.load(Ordering::SeqCst);
            let text = if n == 0 { "Hazır — telefon bekleniyor".to_string() } else { format!("{n} terminal bağlı") };
            status.set_text(&text);
            let ip = approve::own_ip().unwrap_or_else(|| "Tailscale kapalı mı?".into());
            address.set_text(format!("Tailscale: {ip}  ·  port {PORT}"));
            let d = state.devices.lock().unwrap().len();
            devices.set_text(format!("İzinli telefon: {d}"));
            if let Some(t) = tray {
                let _ = t.set_tooltip(Some(format!("Mobile Claude — {text}")));
            }
        }
    };

    let mut tray = None;
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            // Create the icon once the loop runs (required on macOS).
            Event::NewEvents(StartCause::Init) => {
                tray = TrayIconBuilder::new()
                    .with_menu(Box::new(menu.clone()))
                    .with_tooltip("Mobile Claude")
                    .with_icon(icon())
                    .build()
                    .ok();
                refresh(tray.as_ref());
            }
            Event::UserEvent(UserEvent::Changed) => refresh(tray.as_ref()),
            Event::UserEvent(UserEvent::Menu(e)) => {
                if e.id == quit.id() {
                    tray = None;
                    *control_flow = ControlFlow::Exit;
                } else if e.id == reset.id() {
                    state.devices.lock().unwrap().clear();
                    refresh(tray.as_ref());
                } else if e.id == autorun.id() {
                    autostart::set(autorun.is_checked());
                }
            }
            _ => {}
        }
    });
}

/// Runs the SSH server on its own Tokio runtime thread. Fails fast if the
/// port is taken (usually: already running).
fn start_server(state: Arc<server::State>) -> anyhow::Result<()> {
    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build()?;
    let listener = rt.block_on(tokio::net::TcpListener::bind(("0.0.0.0", PORT)))?;
    let config = Arc::new(russh::server::Config {
        server_id: russh::SshId::Standard(server::SERVER_ID.into()),
        keys: vec![store::host_key()?],
        preferred: preferred(),
        auth_rejection_time: Duration::from_millis(300),
        auth_rejection_time_initial: Some(Duration::ZERO),
        inactivity_timeout: None,
        keepalive_interval: Some(Duration::from_secs(20)),
        keepalive_max: 6,
        nodelay: true,
        ..Default::default()
    });
    std::thread::spawn(move || {
        rt.block_on(async move {
            use russh::server::Server as _;
            let mut srv = server::Server { state };
            if let Err(e) = srv.run_on_socket(config, &listener).await {
                log::error!("server stopped: {e:#}");
            }
        });
    });
    log::info!("listening on port {PORT}");
    Ok(())
}

/// Adds ECDH P-256 in front: Android's crypto has no X25519, and ECDH is
/// much faster there than the classic Diffie-Hellman groups.
fn preferred() -> russh::Preferred {
    let mut kex = vec![russh::kex::ECDH_SHA2_NISTP256];
    kex.extend(russh::Preferred::DEFAULT.kex.iter().cloned());
    russh::Preferred { kex: kex.into(), ..russh::Preferred::DEFAULT }
}

fn init_log() {
    let _ = std::fs::create_dir_all(store::dir());
    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(store::dir().join("host.log"));
    let mut b = env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"));
    if let Ok(f) = file {
        b.target(env_logger::Target::Pipe(Box::new(f)));
    }
    let _ = b.try_init();
}

/// 32x32 ">_" in the app's colors, drawn in code so there's no asset file.
fn icon() -> Icon {
    const N: usize = 32;
    let mut px = vec![0u8; N * N * 4];
    let mut put = |x: usize, y: usize, c: [u8; 4]| {
        if x < N && y < N {
            px[(y * N + x) * 4..(y * N + x) * 4 + 4].copy_from_slice(&c);
        }
    };
    let bg = [0x1e, 0x1e, 0x2e, 0xff];
    let mauve = [0xcb, 0xa6, 0xf7, 0xff];
    let peach = [0xfa, 0xb3, 0x87, 0xff];
    // Rounded dark tile.
    for y in 0..N {
        for x in 0..N {
            let (dx, dy) = ((x as i32 - 15).abs().max(9) - 9, (y as i32 - 15).abs().max(9) - 9);
            if dx * dx + dy * dy <= 49 {
                put(x, y, bg);
            }
        }
    }
    // ">" chevron, 3px thick.
    for i in 0..7 {
        for t in 0..3 {
            put(8 + i + t, 9 + i, mauve);
            put(8 + i + t, 22 - i, mauve);
        }
    }
    // "_" underscore.
    for x in 17..25 {
        for y in 20..23 {
            put(x, y, peach);
        }
    }
    Icon::from_rgba(px, N as u32, N as u32).expect("valid icon")
}
