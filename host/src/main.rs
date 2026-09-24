//! AsenaScale: a tray app that lets the AsenaScale phone app
//! into this PC over Tailscale. No setup commands, no admin rights: the
//! first time a phone connects, a dialog asks whether to allow it.

#![cfg_attr(windows, windows_subsystem = "windows")]

mod approve;
mod autostart;
mod firewall;
mod files;
mod server;
mod session;
mod shot;
mod store;
mod tailnet;

use std::sync::{Arc, Mutex};
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
    let state = server::State::new(Arc::new(move || {
        let _ = changed.send_event(UserEvent::Changed);
    }));

    if let Err(e) = start_server(state.clone()) {
        approve::info(
            "AsenaScale",
            &format!("Başlatılamadı: {e:#}\n\nUygulama zaten çalışıyor olabilir (sistem tepsisine bak)."),
        );
        return;
    }

    if autostart::first_run() {
        autostart::set(true);
    }

    // The embedded Tailscale node, and a slow watcher for the tray.
    let ts = Arc::new(Mutex::new(tailnet::Status::default()));
    {
        let (ts, changed) = (ts.clone(), proxy.clone());
        std::thread::spawn(move || watch_tailnet(ts, move || {
            let _ = changed.send_event(UserEvent::Changed);
        }));
    }
    // Off the UI thread: netsh can take a moment.
    std::thread::spawn(firewall::ensure);

    let menu_proxy = proxy.clone();
    MenuEvent::set_event_handler(Some(move |e| {
        let _ = menu_proxy.send_event(UserEvent::Menu(e));
    }));

    let status = MenuItem::new("Başlatılıyor…", false, None);
    let address = MenuItem::new("Tailscale başlatılıyor…", false, None);
    let login = MenuItem::new("Tailscale'e giriş yap", false, None);
    let devices = MenuItem::new("", false, None);
    let reset = MenuItem::new("İzinli telefonları sıfırla", true, None);
    let close_all = MenuItem::new("Tüm oturumları kapat", true, None);
    let fix_firewall = MenuItem::new("Güvenlik duvarı iznini onar", cfg!(windows), None);
    let autorun = CheckMenuItem::new("Oturum açınca başlat", true, autostart::is_enabled(), None);
    let logout = MenuItem::new("Tailscale hesabından çık", true, None);
    let quit = MenuItem::new("Çıkış", true, None);
    let menu = Menu::new();
    let _ = menu.append_items(&[
        &status,
        &address,
        &login,
        &devices,
        &PredefinedMenuItem::separator(),
        &close_all,
        &reset,
        &fix_firewall,
        &autorun,
        &logout,
        &PredefinedMenuItem::separator(),
        &quit,
    ]);

    let refresh = {
        let (status, address, login, devices, state, ts) =
            (status.clone(), address.clone(), login.clone(), devices.clone(), state.clone(), ts.clone());
        move |tray: Option<&tray_icon::TrayIcon>| {
            let live = state.sessions.list();
            let attached: usize = live.iter().map(|s| s.attached()).sum();
            let text = match (live.len(), attached) {
                (0, _) => "Hazır, telefon bekleniyor".to_string(),
                (n, 0) => format!("{n} oturum açık (telefon bağlı değil)"),
                (n, a) => format!("{n} oturum açık, {a} telefon bağlı"),
            };
            status.set_text(&text);
            let st = ts.lock().unwrap().clone();
            let line = if st.running() {
                let name = st.me.as_ref().map(|m| m.dns_name.split('.').next().unwrap_or("").to_string()).unwrap_or_default();
                format!("Tailscale: {}  ·  {name}", st.ipv4().unwrap_or_default())
            } else if st.needs_login() {
                "Tailscale: giriş gerekli".to_string()
            } else {
                "Tailscale: bağlanıyor…".to_string()
            };
            address.set_text(line);
            login.set_enabled(st.needs_login());
            let d = state.devices.lock().unwrap().len();
            devices.set_text(format!("İzinli telefon: {d}"));
            if let Some(t) = tray {
                let _ = t.set_tooltip(Some(format!("AsenaScale — {text}")));
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
                    .with_tooltip("AsenaScale")
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
                } else if e.id == close_all.id() {
                    state.sessions.kill_all();
                } else if e.id == login.id() {
                    let url = ts.lock().unwrap().auth_url.clone();
                    if url.is_empty() {
                        std::thread::spawn(|| { let _ = tailnet::login(); });
                    } else {
                        approve::open_url(&url);
                    }
                } else if e.id == logout.id() {
                    std::thread::spawn(|| { let _ = tailnet::logout(); });
                } else if e.id == fix_firewall.id() {
                    std::thread::spawn(firewall::add_rule);
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
    // Loopback only: the tailnet reaches it through the embedded node.
    let listener = rt.block_on(tokio::net::TcpListener::bind(("127.0.0.1", PORT)))?;
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
    log::info!("listening on 127.0.0.1:{PORT}");
    Ok(())
}

/// Starts the embedded node, forwards its port to the local server, and
/// keeps the tray's copy of its status fresh: every 2 s while logging in,
/// every 30 s once connected (it costs next to nothing).
fn watch_tailnet(ts: Arc<Mutex<tailnet::Status>>, changed: impl Fn()) {
    let dir = store::dir().to_string_lossy().into_owned();
    if let Err(e) = tailnet::start(&dir, &tailnet::hostname()) {
        log::error!("tailscale start: {e:#}");
        return;
    }
    if let Err(e) = tailnet::serve(PORT, PORT) {
        log::error!("tailscale serve: {e:#}");
    }
    let mut opened_login = false;
    loop {
        let st = tailnet::status();
        // First time only: take the user straight to the login page.
        if st.needs_login() && !st.auth_url.is_empty() && !opened_login {
            opened_login = true;
            approve::open_url(&st.auth_url);
        }
        let running = st.running();
        let differs = {
            let mut cur = ts.lock().unwrap();
            let d = cur.state != st.state || cur.auth_url != st.auth_url || cur.ipv4() != st.ipv4();
            *cur = st;
            d
        };
        if differs {
            changed();
        }
        std::thread::sleep(Duration::from_secs(if running { 30 } else { 2 }));
    }
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

/// The AsenaScale mark, 32 px.
fn icon() -> Icon {
    let img = xcap::image::load_from_memory(include_bytes!("../tray.png")).expect("tray icon").into_rgba8();
    let (w, h) = img.dimensions();
    Icon::from_rgba(img.into_raw(), w, h).expect("valid icon")
}
