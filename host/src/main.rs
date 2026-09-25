//! AsenaScale: a tray app that lets the AsenaScale phone app
//! into this PC over Tailscale. No setup commands, no admin rights: the
//! first time a phone connects, a dialog asks whether to allow it.

#![cfg_attr(windows, windows_subsystem = "windows")]

mod approve;
mod autostart;
mod cli;
mod control;
mod firewall;
mod files;
mod i18n;
mod screen;
mod server;
mod session;
mod shot;
mod store;
mod tailnet;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use tao::event::{Event, StartCause};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tray_icon::menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu};
use tray_icon::{Icon, TrayIconBuilder};

use i18n::{t, tf};

/// The phone app looks for this port on the PC.
pub const PORT: u16 = 2222;

enum UserEvent {
    Menu(MenuEvent),
    Changed,
    Quit,
}

fn main() {
    // Any argument: the command line (asenascale status, devices, ...).
    let args: Vec<String> = std::env::args().skip(1).collect();
    if !args.is_empty() {
        std::process::exit(cli::run(&args));
    }
    init_log();

    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let changed = proxy.clone();
    let state = server::State::new(Arc::new(move || {
        let _ = changed.send_event(UserEvent::Changed);
    }));

    let rt = match start_server(state.clone()) {
        Ok(rt) => rt,
        Err(e) => {
            approve::info("AsenaScale", &tf("start_failed", &[("e", &format!("{e:#}"))]));
            return;
        }
    };

    if autostart::first_run() {
        autostart::set(true);
        // Once: allow direct (peer-to-peer) Tailscale traffic through the
        // firewall; the tray can repair it later.
        std::thread::spawn(firewall::ensure);
    }

    // The embedded Tailscale node, and a slow watcher for the tray.
    let ts = Arc::new(Mutex::new(tailnet::Status::default()));
    let net = control::Net::new();
    {
        let (ts, net, changed) = (ts.clone(), net.clone(), proxy.clone());
        std::thread::spawn(move || watch_tailnet(ts, net, move || {
            let _ = changed.send_event(UserEvent::Changed);
        }));
    }

    // The command line talks to this process.
    {
        let quit = proxy.clone();
        let ctx = control::Ctx {
            state: state.clone(),
            ts: ts.clone(),
            net: net.clone(),
            rt,
            quit: Box::new(move || {
                let _ = quit.send_event(UserEvent::Quit);
            }),
        };
        if let Err(e) = control::start(ctx) {
            log::warn!("command line unavailable: {e:#}");
        }
    }

    let menu_proxy = proxy.clone();
    MenuEvent::set_event_handler(Some(move |e| {
        let _ = menu_proxy.send_event(UserEvent::Menu(e));
    }));

    let status = MenuItem::new(t("starting"), false, None);
    let address = MenuItem::new(t("ts_starting"), false, None);
    let login = MenuItem::new(t("ts_login"), false, None);
    let devices = MenuItem::new("", false, None);
    let peers_menu = Submenu::new(t("devices"), true);
    let reset = MenuItem::new(t("reset"), true, None);
    let close_all = MenuItem::new(t("close_all"), true, None);
    let fix_firewall = MenuItem::new(t("fix_firewall"), cfg!(windows), None);
    let autorun = CheckMenuItem::new(t("autorun"), true, autostart::is_enabled(), None);
    let logout = MenuItem::new(t("ts_logout"), true, None);
    let toggle = MenuItem::new(if net.want_up() { t("ts_disconnect") } else { t("ts_connect") }, true, None);
    let quit = MenuItem::new(t("quit"), true, None);
    let menu = Menu::new();
    let _ = menu.append_items(&[
        &status,
        &address,
        &login,
        &peers_menu,
        &devices,
        &PredefinedMenuItem::separator(),
        &close_all,
        &reset,
        &fix_firewall,
        &autorun,
        &toggle,
        &logout,
        &PredefinedMenuItem::separator(),
        &quit,
    ]);

    let refresh = {
        let (status, address, login, devices, state, ts, peers_menu, toggle, net) = (
            status.clone(),
            address.clone(),
            login.clone(),
            devices.clone(),
            state.clone(),
            ts.clone(),
            peers_menu.clone(),
            toggle.clone(),
            net.clone(),
        );
        move |tray: Option<&tray_icon::TrayIcon>| {
            let live = state.sessions.list();
            let attached: usize = live.iter().map(|s| s.attached()).sum();
            let text = match (live.len(), attached) {
                (0, _) => t("ready").to_string(),
                (n, 0) => tf("sessions_no_phone", &[("n", &n)]),
                (n, a) => tf("sessions_phones", &[("n", &n), ("a", &a)]),
            };
            status.set_text(&text);
            let st = ts.lock().unwrap().clone();
            toggle.set_text(if net.want_up() { t("ts_disconnect") } else { t("ts_connect") });
            let line = if !net.want_up() {
                t("ts_off").to_string()
            } else if st.running() {
                let name = st.me.as_ref().map(|m| m.dns_name.split('.').next().unwrap_or("").to_string()).unwrap_or_default();
                format!("Tailscale: {}  ·  {name}", st.ipv4().unwrap_or_default())
            } else if st.needs_login() {
                t("ts_needs_login").to_string()
            } else {
                t("ts_connecting").to_string()
            };
            address.set_text(line);
            login.set_enabled(st.needs_login());

            // Tailnet devices with their IPv4; phones connected now are marked.
            while peers_menu.remove_at(0).is_some() {}
            let connected = state.connected.lock().unwrap().clone();
            let mut peers = st.peers.clone().unwrap_or_default();
            peers.sort_by_key(|p| (!p.online, p.short_name()));
            if peers.is_empty() {
                let _ = peers_menu.append(&MenuItem::new(t("no_devices"), false, None));
            }
            for p in peers.iter().take(30) {
                let ip = p.ipv4();
                let mark = if connected.contains(&ip) {
                    format!("  ·  {}", t("mark_connected"))
                } else if p.online {
                    String::new()
                } else {
                    format!("  ·  {}", t("mark_offline"))
                };
                let text = format!("{}   {}   {}{}", p.short_name(), ip, p.os, mark);
                let _ = peers_menu.append(&MenuItem::new(text, false, None));
            }
            let d = state.devices.lock().unwrap().len();
            devices.set_text(tf("allowed_phones", &[("d", &d)]));
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
            Event::UserEvent(UserEvent::Quit) => {
                let _ = std::fs::remove_file(control::Endpoint::path());
                tray = None;
                *control_flow = ControlFlow::Exit;
            }
            Event::UserEvent(UserEvent::Menu(e)) => {
                if e.id == quit.id() {
                    let _ = std::fs::remove_file(control::Endpoint::path());
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
                } else if e.id == toggle.id() {
                    net.set(!net.want_up());
                    refresh(tray.as_ref());
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
fn start_server(state: Arc<server::State>) -> anyhow::Result<tokio::runtime::Handle> {
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
    let handle = rt.handle().clone();
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
    Ok(handle)
}

/// Starts the embedded node, forwards its port to the local server, and
/// keeps the tray's copy of its status fresh: every 2 s while logging in,
/// every 30 s once connected (it costs next to nothing).
fn watch_tailnet(ts: Arc<Mutex<tailnet::Status>>, net: Arc<control::Net>, changed: impl Fn()) {
    let dir = store::dir().to_string_lossy().into_owned();
    let mut up = false;
    let mut opened_login = false;
    loop {
        // Follow the on/off switch (tray or `asenascale connect/disconnect`).
        let want = net.want_up();
        if want && !up {
            match tailnet::start(&dir, &tailnet::hostname()) {
                Ok(()) => {
                    if let Err(e) = tailnet::serve(PORT, PORT) {
                        log::error!("tailscale serve: {e:#}");
                    }
                    log::info!("tailscale on");
                    up = true;
                }
                Err(e) => log::error!("tailscale start: {e:#}"),
            }
        } else if !want && up {
            tailnet::stop();
            log::info!("tailscale off");
            up = false;
        }
        let st = if up { tailnet::status() } else { tailnet::Status { state: "Stopped".into(), ..Default::default() } };
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
        net.wait(Duration::from_secs(if running || !want { 30 } else { 2 }));
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
    // Keep the log small: start over once it passes 1 MB.
    let path = store::dir().join("host.log");
    if std::fs::metadata(&path).map(|m| m.len() > 1 << 20).unwrap_or(false) {
        let _ = std::fs::remove_file(&path);
    }
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
