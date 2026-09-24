//! The SSH server the phone app talks to.
//!
//! - Only tailnet addresses get in, and only with a public key the user
//!   approved on this PC (a dialog pops up the first time).
//! - `shell` gets a real pseudo-terminal (ConPTY on Windows).
//! - `exec "mc ..."` is answered in-process (screenshots); any other command
//!   runs in the platform shell.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use portable_pty::{native_pty_system, ChildKiller, CommandBuilder, MasterPty, PtySize};
use russh::keys::{HashAlg, PublicKey};
use russh::server::{Auth, ChannelOpenHandle, Handle, Msg, Session};
use russh::{Channel, ChannelId, Pty};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::process::ChildStdin;

use crate::approve;
use crate::store::Devices;

pub const SERVER_ID: &str = concat!("SSH-2.0-MobileClaudeHost_", env!("CARGO_PKG_VERSION"));

/// Shared between connections and the tray.
pub struct State {
    pub devices: Mutex<Devices>,
    /// Open terminal sessions, shown in the tray.
    pub sessions: AtomicUsize,
    /// Only one approval dialog at a time.
    prompt: tokio::sync::Mutex<()>,
    /// Called whenever `sessions` or `devices` change.
    pub on_change: Box<dyn Fn() + Send + Sync>,
}

impl State {
    pub fn new(on_change: Box<dyn Fn() + Send + Sync>) -> Arc<State> {
        Arc::new(State {
            devices: Mutex::new(Devices::load()),
            sessions: AtomicUsize::new(0),
            prompt: tokio::sync::Mutex::new(()),
            on_change,
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
        Conn { state: self.state.clone(), peer, channels: HashMap::new() }
    }

    fn handle_session_error(&mut self, error: anyhow::Error) {
        log::info!("session ended: {error:#}");
    }
}

enum Chan {
    /// Opened; waiting for pty/shell/exec requests.
    New { pty: Option<PtySize> },
    Shell { writer: Box<dyn Write + Send>, master: Box<dyn MasterPty + Send>, killer: Box<dyn ChildKiller + Send + Sync> },
    Exec { stdin: Option<ChildStdin> },
    /// `mc put NAME`: file bytes arrive as channel data until EOF.
    Upload { name: String, data: Vec<u8>, handle: Handle },
    Busy,
}

pub struct Conn {
    state: Arc<State>,
    peer: Option<SocketAddr>,
    channels: HashMap<ChannelId, Chan>,
}

impl Conn {
    fn peer_ok(&self) -> bool {
        self.peer.map(|p| approve::is_tailnet(p.ip())).unwrap_or(false)
    }
}

impl Drop for Conn {
    fn drop(&mut self) {
        for (_, ch) in self.channels.drain() {
            if let Chan::Shell { mut killer, .. } = ch {
                let _ = killer.kill();
                self.state.sessions.fetch_sub(1, Ordering::SeqCst);
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
        Ok(if self.peer_ok() { Auth::Accept } else { Auth::reject() })
    }

    /// Called once the client proved it holds the key.
    async fn auth_publickey(&mut self, _user: &str, key: &PublicKey) -> Result<Auth, Self::Error> {
        if !self.peer_ok() {
            return Ok(Auth::reject());
        }
        if self.state.devices.lock().unwrap().is_allowed(key) {
            return Ok(Auth::Accept);
        }
        // Unknown phone: ask the person at the PC.
        let _one_at_a_time = self.state.prompt.lock().await;
        if self.state.devices.lock().unwrap().is_allowed(key) {
            return Ok(Auth::Accept); // approved while we waited
        }
        let ip = self.peer.unwrap().ip();
        let fp = key.fingerprint(HashAlg::Sha256).to_string();
        let yes = tokio::task::spawn_blocking(move || {
            let name = approve::device_name(ip);
            let ok = approve::ask(&name, &fp);
            (ok, name)
        })
        .await?;
        if yes.0 {
            self.state.devices.lock().unwrap().allow(key, &yes.1);
            (self.state.on_change)();
            log::info!("approved {}", yes.1);
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
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.channels.insert(channel.id(), Chan::New { pty: None });
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
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        if let Some(Chan::New { pty }) = self.channels.get_mut(&channel) {
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
        _name: &str,
        _value: &str,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        session.channel_success(channel)?;
        Ok(())
    }

    async fn shell_request(&mut self, channel: ChannelId, session: &mut Session) -> Result<(), Self::Error> {
        let pty = match self.channels.get(&channel) {
            Some(Chan::New { pty }) => pty.unwrap_or_else(|| size(80, 24, 0, 0)),
            _ => {
                session.channel_failure(channel)?;
                return Ok(());
            }
        };
        match spawn_shell(pty, channel, session.handle()) {
            Ok(ch) => {
                self.channels.insert(channel, ch);
                self.state.sessions.fetch_add(1, Ordering::SeqCst);
                (self.state.on_change)();
                session.channel_success(channel)?;
            }
            Err(e) => {
                log::error!("shell: {e:#}");
                session.channel_failure(channel)?;
            }
        }
        Ok(())
    }

    async fn exec_request(&mut self, channel: ChannelId, data: &[u8], session: &mut Session) -> Result<(), Self::Error> {
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
            tokio::spawn(async move {
                let result = tokio::task::spawn_blocking(move || builtin(&args)).await;
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

    async fn data(&mut self, channel: ChannelId, data: &[u8], _session: &mut Session) -> Result<(), Self::Error> {
        match self.channels.get_mut(&channel) {
            Some(Chan::Shell { writer, .. }) => {
                writer.write_all(data)?;
                writer.flush()?;
            }
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

    async fn channel_eof(&mut self, channel: ChannelId, _session: &mut Session) -> Result<(), Self::Error> {
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
        _session: &mut Session,
    ) -> Result<(), Self::Error> {
        if let Some(Chan::Shell { master, .. }) = self.channels.get(&channel) {
            let _ = master.resize(size(cols, rows, pix_w, pix_h));
        }
        Ok(())
    }

    async fn channel_close(&mut self, channel: ChannelId, _session: &mut Session) -> Result<(), Self::Error> {
        if let Some(Chan::Shell { mut killer, .. }) = self.channels.remove(&channel) {
            let _ = killer.kill();
            self.state.sessions.fetch_sub(1, Ordering::SeqCst);
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

fn builtin(args: &[String]) -> anyhow::Result<Vec<u8>> {
    match args.first().map(String::as_str) {
        Some("list") => crate::shot::list(),
        Some("shot") => {
            let kind = args.get(1).map(String::as_str).unwrap_or("screen");
            let id = args.get(2).map(String::as_str).unwrap_or("");
            crate::shot::shot(kind, id)
        }
        Some("version") => Ok(format!("{SERVER_ID}\n").into_bytes()),
        _ => anyhow::bail!("bilinmeyen komut"),
    }
}

/// The interactive shell: PowerShell 7 if installed, else Windows
/// PowerShell; the login shell elsewhere.
fn shell_command() -> CommandBuilder {
    #[cfg(windows)]
    let mut cmd = {
        let pwsh = which("pwsh.exe");
        let mut c = CommandBuilder::new(pwsh.unwrap_or_else(|| "powershell.exe".into()));
        c.arg("-NoLogo");
        c
    };
    #[cfg(not(windows))]
    let mut cmd = {
        let sh = std::env::var("SHELL").unwrap_or_else(|_| "/bin/sh".into());
        let mut c = CommandBuilder::new(sh);
        c.arg("-l");
        c
    };
    cmd.env("TERM", "xterm-256color");
    cmd.env("COLORTERM", "truecolor");
    if let Some(home) = dirs::home_dir() {
        cmd.cwd(home);
    }
    cmd
}

#[cfg(windows)]
fn which(exe: &str) -> Option<String> {
    std::env::var_os("PATH").and_then(|paths| {
        std::env::split_paths(&paths)
            .map(|p| p.join(exe))
            .find(|p| p.is_file())
            .map(|p| p.to_string_lossy().into_owned())
    })
}

fn spawn_shell(size: PtySize, channel: ChannelId, handle: Handle) -> anyhow::Result<Chan> {
    let pair = native_pty_system().openpty(size)?;
    let mut child = pair.slave.spawn_command(shell_command())?;
    drop(pair.slave);
    let killer = child.clone_killer();
    let mut reader = pair.master.try_clone_reader()?;
    let writer = pair.master.take_writer()?;
    let rt = tokio::runtime::Handle::current();

    // Terminal output -> phone. Blocking reads, so a plain thread.
    std::thread::spawn(move || {
        let mut buf = [0u8; 16 * 1024];
        loop {
            match reader.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if rt.block_on(handle.data(channel, buf[..n].to_vec())).is_err() {
                        break;
                    }
                }
            }
        }
        let code = child.wait().map(|s| s.exit_code()).unwrap_or(1);
        rt.block_on(async {
            let _ = handle.exit_status_request(channel, code).await;
            let _ = handle.eof(channel).await;
            let _ = handle.close(channel).await;
        });
    });
    Ok(Chan::Shell { writer, master: pair.master, killer })
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
