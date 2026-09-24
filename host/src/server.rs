//! The SSH server the phone app talks to.
//!
//! - Only tailnet addresses get in, and only with a public key the user
//!   approved on this PC (a dialog pops up the first time).
//! - `shell` gets a real pseudo-terminal (ConPTY on Windows).
//! - `exec "mc ..."` is answered in-process (screenshots); any other command
//!   runs in the platform shell.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use portable_pty::PtySize;
use russh::keys::{HashAlg, PublicKey};
use russh::server::{Auth, ChannelOpenHandle, Handle, Msg, Session as RusshSession};
use russh::{Channel, ChannelId, Pty};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::ChildStdin;

use crate::approve;
use crate::session::{Session, Sessions};
use crate::store::Devices;

pub const SERVER_ID: &str = concat!("SSH-2.0-AsenaScale_", env!("CARGO_PKG_VERSION"));

/// Shared between connections and the tray.
pub struct State {
    pub devices: Mutex<Devices>,
    /// Terminal sessions; they outlive connections.
    pub sessions: Arc<Sessions>,
    /// Only one approval dialog at a time.
    prompt: tokio::sync::Mutex<()>,
    /// Called whenever sessions or devices change.
    pub on_change: Arc<dyn Fn() + Send + Sync>,
    /// Tailnet IPs of the phones connected right now (one entry per connection).
    pub connected: Mutex<Vec<String>>,
}

impl State {
    pub fn new(on_change: Arc<dyn Fn() + Send + Sync>) -> Arc<State> {
        Arc::new(State {
            devices: Mutex::new(Devices::load()),
            sessions: Arc::new(Sessions::default()),
            prompt: tokio::sync::Mutex::new(()),
            on_change,
            connected: Mutex::new(Vec::new()),
        })
    }
}

#[derive(Clone)]
pub struct Server {
    pub state: Arc<State>,
}

impl russh::server::Server for Server {
    type Handler = Conn;

    fn new_client(&mut self, peer: Option<SocketAddr>) -> Conn {
        Conn { state: self.state.clone(), peer, channels: HashMap::new(), tailnet_ip: None }
    }

    fn handle_session_error(&mut self, error: anyhow::Error) {
        log::info!("session ended: {error:#}");
    }
}

enum Chan {
    /// Opened; waiting for pty/env/shell/exec requests.
    New { pty: Option<PtySize>, env: HashMap<String, String> },
    /// Attached to a persistent terminal session.
    Shell { session: Arc<Session>, client: u64 },
    Exec { stdin: Option<ChildStdin> },
    /// `mc put NAME`: file bytes arrive as channel data until EOF.
    Upload { name: String, data: Vec<u8>, handle: Handle },
    Busy,
}

pub struct Conn {
    state: Arc<State>,
    peer: Option<SocketAddr>,
    channels: HashMap<ChannelId, Chan>,
    /// Tailnet IP, once authenticated (listed in the tray while connected).
    tailnet_ip: Option<String>,
}

impl Conn {
    fn mark_connected(&mut self, ip: &str) {
        if self.tailnet_ip.is_none() {
            self.tailnet_ip = Some(ip.to_string());
            self.state.connected.lock().unwrap().push(ip.to_string());
            (self.state.on_change)();
        }
    }

    /// The tailnet device on the other end. Connections reach the server on
    /// 127.0.0.1 through the embedded Tailscale node; anything else (a local
    /// process) has no tailnet identity and is refused.
    fn tailnet_peer(&self) -> Option<crate::tailnet::Peer> {
        let port = self.peer?.port();
        if let Some(p) = crate::tailnet::peer(port) {
            return Some(p);
        }
        // Local end-to-end tests only (debug builds).
        if cfg!(debug_assertions) && std::env::var_os("AS_TEST_LOCAL").is_some() {
            return Some(crate::tailnet::Peer { ip: "127.0.0.1".into(), name: "test".into(), user: String::new() });
        }
        None
    }
}

impl Drop for Conn {
    fn drop(&mut self) {
        if let Some(ip) = self.tailnet_ip.take() {
            let mut c = self.state.connected.lock().unwrap();
            if let Some(i) = c.iter().position(|x| *x == ip) {
                c.remove(i);
            }
            drop(c);
            (self.state.on_change)();
        }
        // Detach only: the sessions keep running for the next connection.
        for (_, ch) in self.channels.drain() {
            if let Chan::Shell { session, client } = ch {
                session.detach(client);
                (self.state.on_change)();
            }
        }
    }
}

impl russh::server::Handler for Conn {
    type Error = anyhow::Error;

    async fn auth_none(&mut self, _user: &str) -> Result<Auth, Self::Error> {
        Ok(Auth::reject())
    }

    async fn auth_password(&mut self, _user: &str, _password: &str) -> Result<Auth, Self::Error> {
        Ok(Auth::reject())
    }

    async fn auth_publickey_offered(&mut self, _user: &str, _key: &PublicKey) -> Result<Auth, Self::Error> {
        Ok(if self.tailnet_peer().is_some() { Auth::Accept } else { Auth::reject() })
    }

    /// Called once the client proved it holds the key.
    async fn auth_publickey(&mut self, _user: &str, key: &PublicKey) -> Result<Auth, Self::Error> {
        let Some(peer) = self.tailnet_peer() else {
            return Ok(Auth::reject());
        };
        if self.state.devices.lock().unwrap().is_allowed(key) {
            self.mark_connected(&peer.ip);
            return Ok(Auth::Accept);
        }
        // Unknown phone: ask the person at the PC.
        let state = self.state.clone();
        let _one_at_a_time = state.prompt.lock().await;
        if self.state.devices.lock().unwrap().is_allowed(key) {
            self.mark_connected(&peer.ip);
            return Ok(Auth::Accept); // approved while we waited
        }
        let fp = key.fingerprint(HashAlg::Sha256).to_string();
        let ip = peer.ip.clone();
        let yes = tokio::task::spawn_blocking(move || {
            let name = peer.label();
            let ok = approve::ask(&name, &fp);
            (ok, name)
        })
        .await?;
        if yes.0 {
            self.state.devices.lock().unwrap().allow(key, &yes.1);
            (self.state.on_change)();
            log::info!("approved {}", yes.1);
            self.mark_connected(&ip);
            Ok(Auth::Accept)
        } else {
            log::info!("denied {}", yes.1);
            Ok(Auth::reject())
        }
    }

    async fn channel_open_session(
        &mut self,
        channel: Channel<Msg>,
        reply: ChannelOpenHandle,
        _session: &mut RusshSession,
    ) -> Result<(), Self::Error> {
        self.channels.insert(channel.id(), Chan::New { pty: None, env: HashMap::new() });
        reply.accept().await;
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn pty_request(
        &mut self,
        channel: ChannelId,
        _term: &str,
        cols: u32,
        rows: u32,
        pix_w: u32,
        pix_h: u32,
        _modes: &[(Pty, u32)],
        session: &mut RusshSession,
    ) -> Result<(), Self::Error> {
        if let Some(Chan::New { pty, .. }) = self.channels.get_mut(&channel) {
            *pty = Some(size(cols, rows, pix_w, pix_h));
            session.channel_success(channel)?;
        } else {
            session.channel_failure(channel)?;
        }
        Ok(())
    }

    async fn env_request(
        &mut self,
        channel: ChannelId,
        name: &str,
        value: &str,
        session: &mut RusshSession,
    ) -> Result<(), Self::Error> {
        // AS_SESSION: which terminal to attach to; AS_CMD: what a new one runs.
        if let Some(Chan::New { env, .. }) = self.channels.get_mut(&channel) {
            if name.starts_with("AS_") && env.len() < 16 && value.len() < 4096 {
                env.insert(name.to_string(), value.to_string());
            }
        }
        session.channel_success(channel)?;
        Ok(())
    }

    async fn shell_request(&mut self, channel: ChannelId, session: &mut RusshSession) -> Result<(), Self::Error> {
        let (pty, env) = match self.channels.get(&channel) {
            Some(Chan::New { pty, env }) => (pty.unwrap_or_else(|| size(80, 24, 0, 0)), env.clone()),
            _ => {
                session.channel_failure(channel)?;
                return Ok(());
            }
        };
        // Reattach to the phone's session if it's still running, else start it.
        let id = env
            .get("AS_SESSION")
            .filter(|id| crate::session::valid_id(id))
            .cloned()
            .unwrap_or_else(|| format!("s{}", rand::random::<u32>()));
        let term = match self.state.sessions.get(&id) {
            Some(t) => Ok(t),
            None => {
                let cmd = env.get("AS_CMD").cloned().unwrap_or_default();
                self.state.sessions.create(&id, &cmd, pty, self.state.on_change.clone())
            }
        };
        match term {
            Ok(term) => {
                session.channel_success(channel)?;
                let client = term.attach(session.handle(), channel, pty).await;
                self.channels.insert(channel, Chan::Shell { session: term, client });
                (self.state.on_change)();
            }
            Err(e) => {
                log::error!("shell: {e:#}");
                session.channel_failure(channel)?;
            }
        }
        Ok(())
    }

    async fn exec_request(&mut self, channel: ChannelId, data: &[u8], session: &mut RusshSession) -> Result<(), Self::Error> {
        let cmd = String::from_utf8_lossy(data).trim().to_string();
        let handle = session.handle();
        session.channel_success(channel)?;

        if let Some(encoded) = cmd.strip_prefix("mc put ") {
            // The name is base64url so spaces and non-ASCII survive any quoting.
            let name = crate::files::decode_name(encoded.trim());
            self.channels.insert(channel, Chan::Upload { name, data: Vec::new(), handle });
            return Ok(());
        }

        if let Some(rest) = cmd.strip_prefix("mc ") {
            // Built-in commands: answered here, no shell involved.
            self.channels.insert(channel, Chan::Busy);
            let args: Vec<String> = rest.split_whitespace().map(str::to_string).collect();
            let sessions = self.state.sessions.clone();
            tokio::spawn(async move {
                let result = tokio::task::spawn_blocking(move || builtin(&args, &sessions)).await;
                let (out, err, code) = match result {
                    Ok(Ok(bytes)) => (bytes, String::new(), 0),
                    Ok(Err(e)) => (Vec::new(), format!("{e:#}"), 2),
                    Err(e) => (Vec::new(), e.to_string(), 2),
                };
                finish(&handle, channel, out, err, code).await;
            });
            return Ok(());
        }

        match spawn_exec(&cmd, channel, handle) {
            Ok(stdin) => {
                self.channels.insert(channel, Chan::Exec { stdin: Some(stdin) });
            }
            Err(e) => {
                let h = session.handle();
                tokio::spawn(async move { finish(&h, channel, Vec::new(), format!("{e:#}"), 127).await });
            }
        }
        Ok(())
    }

    async fn data(&mut self, channel: ChannelId, data: &[u8], _session: &mut RusshSession) -> Result<(), Self::Error> {
        match self.channels.get_mut(&channel) {
            Some(Chan::Shell { session, .. }) => session.write(data),
            Some(Chan::Exec { stdin: Some(stdin) }) => {
                stdin.write_all(data).await?;
            }
            Some(Chan::Upload { data: buf, .. }) => {
                if buf.len() + data.len() > crate::files::MAX_BYTES {
                    anyhow::bail!("upload too large");
                }
                buf.extend_from_slice(data);
            }
            _ => {}
        }
        Ok(())
    }

    async fn channel_eof(&mut self, channel: ChannelId, _session: &mut RusshSession) -> Result<(), Self::Error> {
        match self.channels.get_mut(&channel) {
            Some(Chan::Exec { stdin }) => *stdin = None, // closes the child's stdin
            Some(Chan::Upload { .. }) => {
                if let Some(Chan::Upload { name, data, handle }) = self.channels.insert(channel, Chan::Busy) {
                    tokio::spawn(async move {
                        let saved = tokio::task::spawn_blocking(move || crate::files::save(&name, &data)).await;
                        match saved {
                            Ok(Ok(path)) => finish(&handle, channel, path.into_bytes(), String::new(), 0).await,
                            Ok(Err(e)) => finish(&handle, channel, Vec::new(), format!("{e:#}"), 2).await,
                            Err(e) => finish(&handle, channel, Vec::new(), e.to_string(), 2).await,
                        }
                    });
                }
            }
            _ => {}
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn window_change_request(
        &mut self,
        channel: ChannelId,
        cols: u32,
        rows: u32,
        pix_w: u32,
        pix_h: u32,
        _session: &mut RusshSession,
    ) -> Result<(), Self::Error> {
        if let Some(Chan::Shell { session, .. }) = self.channels.get(&channel) {
            session.resize(size(cols, rows, pix_w, pix_h));
        }
        Ok(())
    }

    async fn channel_close(&mut self, channel: ChannelId, _session: &mut RusshSession) -> Result<(), Self::Error> {
        if let Some(Chan::Shell { session, client }) = self.channels.remove(&channel) {
            session.detach(client);
            (self.state.on_change)();
        }
        Ok(())
    }
}

fn size(cols: u32, rows: u32, pix_w: u32, pix_h: u32) -> PtySize {
    PtySize {
        cols: cols.clamp(10, 1000) as u16,
        rows: rows.clamp(4, 500) as u16,
        pixel_width: pix_w.min(u16::MAX as u32) as u16,
        pixel_height: pix_h.min(u16::MAX as u32) as u16,
    }
}

/// Sends the result of a command and closes the channel.
async fn finish(handle: &Handle, channel: ChannelId, out: Vec<u8>, err: String, code: u32) {
    for chunk in out.chunks(32 * 1024) {
        if handle.data(channel, chunk.to_vec()).await.is_err() {
            return;
        }
    }
    if !err.is_empty() {
        let _ = handle.extended_data(channel, 1, err.into_bytes()).await;
    }
    let _ = handle.exit_status_request(channel, code).await;
    let _ = handle.eof(channel).await;
    let _ = handle.close(channel).await;
}

fn builtin(args: &[String], sessions: &Sessions) -> anyhow::Result<Vec<u8>> {
    match args.first().map(String::as_str) {
        // "session<TAB>id<TAB>attached<TAB>created<TAB>command<TAB>title"
        Some("sessions") => Ok(sessions
            .list()
            .iter()
            .map(|s| {
                format!(
                    "session\t{}\t{}\t{}\t{}\t{}\n",
                    s.id,
                    s.attached(),
                    s.created,
                    s.command.replace(['\t', '\n'], " "),
                    s.title().replace(['\t', '\n'], " ")
                )
            })
            .collect::<String>()
            .into_bytes()),
        Some("kill") => {
            let id = args.get(1).map(String::as_str).unwrap_or("");
            sessions.get(id).ok_or_else(|| anyhow::anyhow!("no such session"))?.kill();
            Ok(Vec::new())
        }
        Some("list") => crate::shot::list(),
        Some("shot") => {
            let kind = args.get(1).map(String::as_str).unwrap_or("screen");
            let id = args.get(2).map(String::as_str).unwrap_or("");
            crate::shot::shot(kind, id)
        }
        Some("version") => Ok(format!("{SERVER_ID}\n").into_bytes()),
        _ => anyhow::bail!("unknown command"),
    }
}

/// Non-interactive command with piped stdio, like `ssh host cmd`.
fn spawn_exec(cmd: &str, channel: ChannelId, handle: Handle) -> anyhow::Result<ChildStdin> {
    #[cfg(windows)]
    let mut command = {
        let mut c = tokio::process::Command::new("powershell.exe");
        c.args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", cmd]);
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        c
    };
    #[cfg(not(windows))]
    let mut command = {
        let mut c = tokio::process::Command::new(std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".into()));
        c.args(["-c", cmd]);
        c
    };
    if let Some(home) = dirs::home_dir() {
        command.current_dir(home);
    }
    let mut child = command
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true)
        .spawn()?;
    let stdin = child.stdin.take().expect("piped");
    let mut stdout = child.stdout.take().expect("piped");
    let mut stderr = child.stderr.take().expect("piped");

    let h_err = handle.clone();
    let err_task = tokio::spawn(async move {
        let mut buf = vec![0u8; 16 * 1024];
        while let Ok(n) = stderr.read(&mut buf).await {
            if n == 0 || h_err.extended_data(channel, 1, buf[..n].to_vec()).await.is_err() {
                break;
            }
        }
    });
    tokio::spawn(async move {
        let mut buf = vec![0u8; 32 * 1024];
        while let Ok(n) = stdout.read(&mut buf).await {
            if n == 0 || handle.data(channel, buf[..n].to_vec()).await.is_err() {
                break;
            }
        }
        let _ = err_task.await;
        let code = child.wait().await.ok().and_then(|s| s.code()).unwrap_or(1) as u32;
        let _ = handle.exit_status_request(channel, code).await;
        let _ = handle.eof(channel).await;
        let _ = handle.close(channel).await;
    });
    Ok(stdin)
}
