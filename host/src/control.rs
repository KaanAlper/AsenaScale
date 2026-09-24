//! The running tray app answers the `asenascale` command line here.
//!
//! A TCP listener on 127.0.0.1 (random port) that takes one line,
//! "TOKEN COMMAND ARGS...", and answers with an exit code line plus text.
//! Port and token live in control.json in the settings folder, which only
//! this user can read, so other users on the PC can't drive the app.

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::Result;
use serde::{Deserialize, Serialize};

use crate::server::State;
use crate::{store, tailnet};

#[derive(Serialize, Deserialize)]
pub struct Endpoint {
    pub port: u16,
    pub token: String,
}

impl Endpoint {
    pub fn path() -> std::path::PathBuf {
        store::dir().join("control.json")
    }

    pub fn load() -> Option<Endpoint> {
        serde_json::from_slice(&std::fs::read(Self::path()).ok()?).ok()
    }
}

/// Whether the Tailscale node should be up (the user can switch it off).
/// Survives restarts: an "offline" marker file means off.
pub struct Net {
    want_up: Mutex<bool>,
    wake: Condvar,
}

impl Net {
    pub fn new() -> Arc<Net> {
        let up = !store::dir().join("offline").exists();
        Arc::new(Net { want_up: Mutex::new(up), wake: Condvar::new() })
    }

    pub fn want_up(&self) -> bool {
        *self.want_up.lock().unwrap()
    }

    pub fn set(&self, up: bool) {
        *self.want_up.lock().unwrap() = up;
        let marker = store::dir().join("offline");
        if up {
            let _ = std::fs::remove_file(marker);
        } else {
            let _ = std::fs::write(marker, b"");
        }
        self.wake.notify_all();
    }

    /// Sleeps up to `d`, or until the wanted state changes.
    pub fn wait(&self, d: Duration) {
        let g = self.want_up.lock().unwrap();
        let _ = self.wake.wait_timeout(g, d);
    }
}

pub struct Ctx {
    pub state: Arc<State>,
    pub ts: Arc<Mutex<tailnet::Status>>,
    pub net: Arc<Net>,
    pub quit: Box<dyn Fn() + Send + Sync>,
}

pub fn start(ctx: Ctx) -> Result<()> {
    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    let token: String = (0..32).map(|_| format!("{:x}", rand::random::<u8>() % 16)).collect();
    let ep = Endpoint { port: listener.local_addr()?.port(), token: token.clone() };
    std::fs::create_dir_all(store::dir())?;
    write_private(&Endpoint::path(), &serde_json::to_vec(&ep)?)?;
    let ctx = Arc::new(ctx);
    std::thread::Builder::new().name("control".into()).spawn(move || {
        for conn in listener.incoming().flatten() {
            let (ctx, token) = (ctx.clone(), token.clone());
            std::thread::spawn(move || {
                let _ = handle(conn, &ctx, &token);
            });
        }
    })?;
    Ok(())
}

#[cfg(unix)]
fn write_private(path: &std::path::Path, data: &[u8]) -> Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?;
    f.write_all(data)?;
    Ok(())
}

#[cfg(not(unix))]
fn write_private(path: &std::path::Path, data: &[u8]) -> Result<()> {
    // %APPDATA% is already private to the user.
    std::fs::write(path, data)?;
    Ok(())
}

fn handle(conn: TcpStream, ctx: &Ctx, token: &str) -> Result<()> {
    conn.set_read_timeout(Some(Duration::from_secs(5)))?;
    let mut line = String::new();
    BufReader::new(&conn).take(64 * 1024).read_line(&mut line)?;
    let mut words = line.split_whitespace();
    if words.next() != Some(token) {
        return Ok(());
    }
    let args: Vec<String> = words.map(str::to_string).collect();
    let (code, text) = match command(ctx, &args) {
        Ok(text) => (0, text),
        Err(e) => (1, format!("{e:#}\n")),
    };
    let mut w = &conn;
    w.write_all(format!("{code}\n{text}").as_bytes())?;
    Ok(())
}

use std::io::Read as _;

fn command(ctx: &Ctx, args: &[String]) -> Result<String> {
    let arg = |i: usize| args.get(i).map(String::as_str).unwrap_or("");
    let json = args.iter().any(|a| a == "--json");
    match arg(0) {
        "status" => Ok(status(ctx, json)),
        "devices" => Ok(devices(ctx, json)),
        "ip" => {
            let st = ctx.ts.lock().unwrap().clone();
            let v4 = st.ipv4().unwrap_or_default();
            let v6 = st.ipv6().unwrap_or_default();
            if v4.is_empty() && v6.is_empty() {
                anyhow::bail!("Tailscale isn't connected (asenascale status)");
            }
            Ok(match arg(1) {
                "-4" => format!("{v4}\n"),
                "-6" => format!("{v6}\n"),
                _ => [v4, v6].into_iter().filter(|s| !s.is_empty()).map(|s| s + "\n").collect(),
            })
        }
        "connect" => {
            ctx.net.set(true);
            // Give the node a moment, then report where it stands.
            for _ in 0..40 {
                std::thread::sleep(Duration::from_millis(250));
                let st = tailnet::status();
                if st.running() {
                    return Ok(format!("connected: {}\n", st.ipv4().unwrap_or_default()));
                }
                if st.needs_login() && !st.auth_url.is_empty() {
                    return Ok(format!("sign in to Tailscale: {}\n", st.auth_url));
                }
            }
            Ok("connecting...\n".into())
        }
        "disconnect" => {
            ctx.net.set(false);
            Ok("disconnected from Tailscale (phones can't reach this PC until `asenascale connect`)\n".into())
        }
        "login" => {
            let st = tailnet::status();
            if st.running() {
                return Ok("already signed in\n".into());
            }
            let _ = tailnet::login();
            for _ in 0..40 {
                let st = tailnet::status();
                if !st.auth_url.is_empty() {
                    crate::approve::open_url(&st.auth_url);
                    return Ok(format!("{}\n", st.auth_url));
                }
                std::thread::sleep(Duration::from_millis(250));
            }
            anyhow::bail!("no sign-in link yet; is the node connected? (asenascale connect)")
        }
        "logout" => {
            tailnet::logout()?;
            Ok("signed out of Tailscale\n".into())
        }
        "ping" => {
            let who = arg(1);
            if who.is_empty() {
                anyhow::bail!("usage: asenascale ping DEVICE");
            }
            let st = tailnet::status();
            let peer = st.peers.unwrap_or_default().into_iter().find(|p| {
                p.short_name().eq_ignore_ascii_case(who) || p.ips.as_ref().is_some_and(|v| v.iter().any(|ip| ip == who))
            });
            let ip = peer.as_ref().map(|p| p.ipv4()).unwrap_or_else(|| who.to_string());
            let mut out = String::new();
            for _ in 0..4 {
                match tailnet::ping(&ip) {
                    Some(ms) => out += &format!("{ip}: {ms} ms\n"),
                    None => out += &format!("{ip}: no answer\n"),
                }
            }
            Ok(out)
        }
        "sessions" => {
            let list = ctx.state.sessions.list();
            if json {
                let v: Vec<_> = list
                    .iter()
                    .map(|s| serde_json::json!({"id": s.id, "command": s.command, "title": s.title(), "attached": s.attached(), "created": s.created}))
                    .collect();
                return Ok(serde_json::to_string_pretty(&v)? + "\n");
            }
            if list.is_empty() {
                return Ok("no terminal sessions\n".into());
            }
            let mut rows = vec![vec!["ID".into(), "PHONES".into(), "AGE".into(), "COMMAND".into(), "TITLE".into()]];
            for s in &list {
                rows.push(vec![s.id.clone(), s.attached().to_string(), ago(s.created), s.command.clone(), s.title()]);
            }
            Ok(table(&rows))
        }
        "kill" => {
            let id = arg(1);
            if id == "all" {
                ctx.state.sessions.kill_all();
                return Ok("all sessions closed\n".into());
            }
            ctx.state.sessions.get(id).ok_or_else(|| anyhow::anyhow!("no session {id}"))?.kill();
            Ok(format!("closed {id}\n"))
        }
        "phones" => {
            let d = ctx.state.devices.lock().unwrap();
            if d.list().is_empty() {
                return Ok("no approved phones\n".into());
            }
            let mut rows = vec![vec!["#".into(), "PHONE".into(), "APPROVED".into()]];
            for (i, p) in d.list().iter().enumerate() {
                rows.push(vec![(i + 1).to_string(), p.name.clone(), ago(p.added)]);
            }
            Ok(table(&rows))
        }
        "revoke" => {
            let which = arg(1);
            let mut d = ctx.state.devices.lock().unwrap();
            let n = if which == "all" { d.clear_count() } else { d.remove(which) };
            drop(d);
            (ctx.state.on_change)();
            if n == 0 {
                anyhow::bail!("no approved phone matches {which:?} (see: asenascale phones)");
            }
            Ok(format!("removed {n}; it will be asked for permission again\n"))
        }
        "quit" => {
            (ctx.quit)();
            Ok("AsenaScale stopped\n".into())
        }
        "ping-app" => Ok("pong\n".into()),
        other => anyhow::bail!("unknown command {other:?} (asenascale help)"),
    }
}

fn status(ctx: &Ctx, json: bool) -> String {
    let st = ctx.ts.lock().unwrap().clone();
    let sessions = ctx.state.sessions.list();
    let connected = ctx.state.connected.lock().unwrap().clone();
    let phones = ctx.state.devices.lock().unwrap().list().len();
    let state = if !ctx.net.want_up() { "Off".to_string() } else { st.state.clone() };
    if json {
        return serde_json::json!({
            "tailscale": state,
            "name": st.me.as_ref().map(|m| m.dns_name.trim_end_matches('.').to_string()),
            "ipv4": st.ipv4(),
            "ipv6": st.ipv6(),
            "tailnet": st.tailnet,
            "authURL": st.auth_url,
            "sessions": sessions.len(),
            "phonesConnected": connected,
            "phonesApproved": phones,
            "version": env!("CARGO_PKG_VERSION"),
        })
        .to_string()
            + "\n";
    }
    let mut out = String::new();
    let mut line = |k: &str, v: String| {
        if !v.is_empty() {
            out += &format!("{k:<14}{v}\n");
        }
    };
    line("AsenaScale", env!("CARGO_PKG_VERSION").into());
    line("Tailscale", state);
    line("Name", st.me.as_ref().map(|m| m.dns_name.trim_end_matches('.').to_string()).unwrap_or_default());
    line("IPv4", st.ipv4().unwrap_or_default());
    line("IPv6", st.ipv6().unwrap_or_default());
    line("Tailnet", st.tailnet.clone());
    if st.needs_login() {
        line("Sign in", st.auth_url.clone());
    }
    line("Error", st.error.clone());
    line("Sessions", sessions.len().to_string());
    line("Phones now", if connected.is_empty() { "none".into() } else { dedup(&connected).join(", ") });
    line("Approved", phones.to_string());
    out
}

fn devices(ctx: &Ctx, json: bool) -> String {
    let st = ctx.ts.lock().unwrap().clone();
    let connected = ctx.state.connected.lock().unwrap().clone();
    let mut peers = st.peers.clone().unwrap_or_default();
    peers.sort_by_key(|p| (!p.online, p.short_name()));
    if json {
        let v: Vec<_> = peers
            .iter()
            .map(|p| {
                serde_json::json!({
                    "name": p.short_name(), "dnsName": p.dns_name.trim_end_matches('.'), "os": p.os,
                    "ipv4": p.ipv4(), "ipv6": p.ipv6(), "online": p.online, "lastSeen": p.last_seen,
                    "direct": p.direct, "relay": p.relay, "rx": p.rx, "tx": p.tx,
                    "phoneConnected": connected.contains(&p.ipv4()),
                })
            })
            .collect();
        return serde_json::to_string_pretty(&v).unwrap_or_default() + "\n";
    }
    if peers.is_empty() {
        return if st.running() { "no other devices on this tailnet\n".into() } else { "Tailscale isn't connected (asenascale status)\n".into() };
    }
    let mut rows = vec![vec!["NAME".into(), "IPV4".into(), "OS".into(), "STATUS".into(), "LINK".into(), "IPV6".into()]];
    for p in &peers {
        let status = if connected.contains(&p.ipv4()) {
            "connected (app)".to_string()
        } else if p.online {
            "online".into()
        } else if !p.last_seen.is_empty() {
            format!("offline, {}", since_rfc3339(&p.last_seen))
        } else {
            "offline".into()
        };
        let link = if !p.direct.is_empty() {
            format!("direct {}", p.direct)
        } else if !p.relay.is_empty() {
            format!("relay {}", p.relay)
        } else {
            "-".into()
        };
        rows.push(vec![p.short_name(), p.ipv4(), p.os.clone(), status, link, p.ipv6()]);
    }
    table(&rows)
}

fn dedup(v: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for s in v {
        if !out.contains(s) {
            out.push(s.clone());
        }
    }
    out
}

/// Left-aligned columns, two spaces apart.
fn table(rows: &[Vec<String>]) -> String {
    let cols = rows.iter().map(Vec::len).max().unwrap_or(0);
    let widths: Vec<usize> = (0..cols)
        .map(|c| rows.iter().map(|r| r.get(c).map(|s| s.chars().count()).unwrap_or(0)).max().unwrap_or(0))
        .collect();
    let mut out = String::new();
    for r in rows {
        let mut line = String::new();
        for (c, cell) in r.iter().enumerate() {
            if c + 1 == r.len() {
                line += cell;
            } else {
                line += &format!("{cell:<w$}  ", w = widths[c]);
            }
        }
        out += line.trim_end();
        out.push('\n');
    }
    out
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn ago(secs: u64) -> String {
    let d = now().saturating_sub(secs);
    match d {
        0..=59 => "just now".into(),
        60..=3599 => format!("{} min ago", d / 60),
        3600..=86399 => format!("{} h ago", d / 3600),
        _ => format!("{} d ago", d / 86400),
    }
}

/// "2026-09-24T17:00:00Z" -> "3 h ago" (UTC only, which is what Go sends).
fn since_rfc3339(s: &str) -> String {
    let p = |a: usize, b: usize| s.get(a..b).and_then(|v| v.parse::<i64>().ok());
    let (Some(y), Some(mo), Some(d), Some(h), Some(mi), Some(se)) = (p(0, 4), p(5, 7), p(8, 10), p(11, 13), p(14, 16), p(17, 19)) else {
        return s.to_string();
    };
    // Days from civil (Howard Hinnant's algorithm).
    let y2 = if mo <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * (if mo > 2 { mo - 3 } else { mo + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    ago((days * 86400 + h * 3600 + mi * 60 + se).max(0) as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dates_and_tables() {
        assert_eq!(since_rfc3339("1970-01-01T00:00:00Z"), ago(0));
        let t = table(&[vec!["A".into(), "BB".into()], vec!["CCC".into(), "D".into()]]);
        assert_eq!(t, "A    BB\nCCC  D\n");
    }
}
