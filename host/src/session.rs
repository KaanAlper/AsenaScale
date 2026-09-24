//! Terminal sessions that outlive connections.
//!
//! A session is a shell in a pseudo-terminal plus a copy of its screen (a
//! vt100 parser fed with everything the shell prints). Phones attach and
//! detach freely; on attach they get the current screen in one small burst
//! and then the live stream. The shell only ends when it exits or is
//! killed, so a phone that dropped off (network, Android killing the app)
//! comes back to exactly where it was, without tmux.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use portable_pty::{native_pty_system, ChildKiller, CommandBuilder, MasterPty, PtySize};
use russh::server::Handle;
use russh::ChannelId;

/// Rows of history kept for the snapshot's parser (the phone keeps its own
/// scrollback while attached).
const SCROLLBACK: usize = 0;

#[derive(Default)]
struct Title(String);

impl vt100::Callbacks for Title {
    fn set_window_title(&mut self, _: &mut vt100::Screen, title: &[u8]) {
        self.0 = String::from_utf8_lossy(title).into_owned();
    }
}

struct Client {
    handle: Handle,
    channel: ChannelId,
}

pub struct Session {
    pub id: String,
    pub command: String,
    pub created: u64,
    writer: Mutex<Box<dyn Write + Send>>,
    master: Mutex<Box<dyn MasterPty + Send>>,
    killer: Mutex<Box<dyn ChildKiller + Send + Sync>>,
    screen: Mutex<vt100::Parser<Title>>,
    clients: Mutex<HashMap<u64, Client>>,
}

static NEXT_CLIENT: AtomicU64 = AtomicU64::new(1);

impl Session {
    pub fn write(&self, data: &[u8]) {
        let mut w = self.writer.lock().unwrap();
        let _ = w.write_all(data);
        let _ = w.flush();
    }

    pub fn resize(&self, size: PtySize) {
        let _ = self.master.lock().unwrap().resize(size);
        self.screen.lock().unwrap().screen_mut().set_size(size.rows, size.cols);
    }

    pub fn kill(&self) {
        let _ = self.killer.lock().unwrap().kill();
    }

    pub fn attached(&self) -> usize {
        self.clients.lock().unwrap().len()
    }

    pub fn title(&self) -> String {
        self.screen.lock().unwrap().callbacks().0.clone()
    }

    /// Escape codes that paint the current screen on a fresh terminal:
    /// modes, contents, cursor, title.
    fn snapshot(&self) -> Vec<u8> {
        let parser = self.screen.lock().unwrap();
        let screen = parser.screen();
        let mut out = b"\x1b[0m\x1b[H\x1b[2J".to_vec();
        if screen.alternate_screen() {
            out.extend_from_slice(b"\x1b[?1049h\x1b[H\x1b[2J");
        }
        out.extend(screen.state_formatted());
        let title = &parser.callbacks().0;
        if !title.is_empty() {
            out.extend(format!("\x1b]0;{title}\x07").into_bytes());
        }
        out
    }

    /// Adds a phone. Returns its client id, for [`Session::detach`].
    pub async fn attach(&self, handle: Handle, channel: ChannelId, size: PtySize) -> u64 {
        self.resize(size);
        let snap = self.snapshot();
        let _ = handle.data(channel, snap).await;
        let id = NEXT_CLIENT.fetch_add(1, Ordering::Relaxed);
        self.clients.lock().unwrap().insert(id, Client { handle, channel });
        id
    }

    pub fn detach(&self, client: u64) {
        self.clients.lock().unwrap().remove(&client);
    }
}

/// All live sessions, by id (chosen by the phone, so it can find its way back).
#[derive(Default)]
pub struct Sessions {
    map: Mutex<HashMap<String, Arc<Session>>>,
}

impl Sessions {
    pub fn get(&self, id: &str) -> Option<Arc<Session>> {
        self.map.lock().unwrap().get(id).cloned()
    }

    pub fn list(&self) -> Vec<Arc<Session>> {
        let mut v: Vec<_> = self.map.lock().unwrap().values().cloned().collect();
        v.sort_by_key(|s| s.created);
        v
    }

    pub fn kill_all(&self) {
        for s in self.list() {
            s.kill();
        }
    }

    /// Starts `command` (or a plain shell) in a new session.
    pub fn create(
        self: &Arc<Self>,
        id: &str,
        command: &str,
        size: PtySize,
        on_change: Arc<dyn Fn() + Send + Sync>,
    ) -> anyhow::Result<Arc<Session>> {
        let pair = native_pty_system().openpty(size)?;
        let mut child = pair.slave.spawn_command(shell_command())?;
        drop(pair.slave);
        let killer = child.clone_killer();
        let mut reader = pair.master.try_clone_reader()?;
        let mut writer = pair.master.take_writer()?;
        if !command.trim().is_empty() {
            // Typed into the shell, so the shell stays when the tool exits.
            let _ = writer.write_all(format!("{}\r", command.trim()).as_bytes());
        }
        let session = Arc::new(Session {
            id: id.to_string(),
            command: command.to_string(),
            created: SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0),
            writer: Mutex::new(writer),
            master: Mutex::new(pair.master),
            killer: Mutex::new(killer),
            screen: Mutex::new(vt100::Parser::new_with_callbacks(size.rows, size.cols, SCROLLBACK, Title::default())),
            clients: Mutex::new(HashMap::new()),
        });
        self.map.lock().unwrap().insert(id.to_string(), session.clone());
        on_change();

        let (sessions, s, rt) = (self.clone(), session.clone(), tokio::runtime::Handle::current());
        std::thread::Builder::new().name(format!("pty-{id}")).spawn(move || {
            let mut buf = [0u8; 16 * 1024];
            let mut dsr = CursorQueries::default();
            loop {
                let n = match reader.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                // Cursor position queries are answered here, from the screen
                // copy: Windows' ConPTY asks one at startup and shows nothing
                // until it gets an answer, even with no phone attached.
                let mut owned = Vec::with_capacity(n);
                {
                    let mut screen = s.screen.lock().unwrap();
                    for piece in dsr.split(&buf[..n]) {
                        match piece {
                            Piece::Output(bytes) => {
                                screen.process(&bytes);
                                owned.extend_from_slice(&bytes);
                            }
                            Piece::Query => {
                                let (row, col) = screen.screen().cursor_position();
                                s.write(format!("\x1b[{};{}R", row + 1, col + 1).as_bytes());
                            }
                        }
                    }
                }
                if owned.is_empty() {
                    continue;
                }
                let bytes = &owned[..];
                // Fan out to every attached phone; drop the ones that are gone.
                let clients: Vec<(u64, Handle, ChannelId)> =
                    s.clients.lock().unwrap().iter().map(|(k, c)| (*k, c.handle.clone(), c.channel)).collect();
                for (k, h, ch) in clients {
                    if rt.block_on(h.data(ch, bytes.to_vec())).is_err() {
                        s.detach(k);
                    }
                }
            }
            let code = child.wait().map(|st| st.exit_code()).unwrap_or(1);
            sessions.map.lock().unwrap().remove(&s.id);
            let clients: Vec<Client> = s.clients.lock().unwrap().drain().map(|(_, c)| c).collect();
            rt.block_on(async {
                for c in clients {
                    let _ = c.handle.exit_status_request(c.channel, code).await;
                    let _ = c.handle.eof(c.channel).await;
                    let _ = c.handle.close(c.channel).await;
                }
            });
            on_change();
        })?;
        Ok(session)
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

enum Piece {
    Output(Vec<u8>),
    /// ESC[6n: "where is the cursor?"
    Query,
}

/// Finds ESC[6n (cursor position query) in terminal output, also when split
/// across reads, so it can be answered here and kept from the phones.
#[derive(Default)]
struct CursorQueries {
    carry: Vec<u8>,
}

impl CursorQueries {
    const QUERY: &'static [u8] = b"\x1b[6n";

    fn split(&mut self, input: &[u8]) -> Vec<Piece> {
        let mut data = std::mem::take(&mut self.carry);
        data.extend_from_slice(input);
        let mut pieces = Vec::new();
        let mut start = 0;
        let mut i = 0;
        while i < data.len() {
            if data[i..].starts_with(Self::QUERY) {
                if i > start {
                    pieces.push(Piece::Output(data[start..i].to_vec()));
                }
                pieces.push(Piece::Query);
                i += Self::QUERY.len();
                start = i;
            } else if data[i] == 0x1b && Self::QUERY.starts_with(&data[i..]) {
                break; // a query cut at the end of this read: hold it back
            } else {
                i += 1;
            }
        }
        if i > start {
            pieces.push(Piece::Output(data[start..i].to_vec()));
        }
        self.carry = data[i..].to_vec();
        pieces
    }
}

/// Ids come from phones: keep them short and plain.
pub fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(q: &mut CursorQueries, input: &[u8]) -> (Vec<u8>, usize) {
        let mut out = Vec::new();
        let mut n = 0;
        for p in q.split(input) {
            match p {
                Piece::Output(b) => out.extend(b),
                Piece::Query => n += 1,
            }
        }
        (out, n)
    }

    #[test]
    fn cursor_queries() {
        let mut q = CursorQueries::default();
        assert_eq!(run(&mut q, b"hi\x1b[6nthere\x1b["), (b"hithere".to_vec(), 1));
        assert_eq!(run(&mut q, b"6nok\x1b[31m"), (b"ok\x1b[31m".to_vec(), 1));
        // An ESC that turns out not to be a query is passed on.
        assert_eq!(run(&mut q, b"x\x1b"), (b"x".to_vec(), 0));
        assert_eq!(run(&mut q, b"[Ay"), (b"\x1b[Ay".to_vec(), 0));
    }
}
