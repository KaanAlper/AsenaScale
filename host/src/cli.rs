//! `asenascale COMMAND`: the command line. Talks to the running tray app
//! (see control.rs); `log`, `start` and `help` work without it.

use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::TcpStream;
use std::time::Duration;

use crate::control::Endpoint;
use crate::store;

const HELP: &str = "\
AsenaScale: use this PC from the AsenaScale phone app over Tailscale.

Usage: asenascale [COMMAND]        (no command: start the tray app)

  status              Tailscale state, this PC's name and IPs, sessions, phones
  devices             devices on the tailnet: name, IPv4, OS, online/offline,
                      direct or relayed link, IPv6
  ip [-4|-6]          this PC's Tailscale IPs
  connect             bring this PC onto the tailnet (prints a sign-in link if needed)
  disconnect          take it off the tailnet (phones can't reach it until connect)
  login | logout      Tailscale account on this PC
  ping DEVICE         Tailscale round trip to a device (name or IP)
  exec DEVICE -- CMD  run CMD on another PC with AsenaScale (PowerShell on
                      Windows); prints its output, exits with its code.
                      CMD is passed as one string and re-parsed by the
                      other PC's shell; quote it
  windows DEVICE      that PC's screens and windows (IDs for shot)
  shot DEVICE [screen|active|output N|window ID] -o FILE.png
                      save a screenshot of that PC (default: all screens)
  claude [ARGS]       start Claude Code here, in this folder, as a shared
                      session: the phone can join it live (and vice versa)
  codex | gemini | grok [ARGS]   the same for those CLIs
  new [COMMAND]       the same with any command (default: a shell)
  attach [ID]         join a running session (one the phone started, too);
                      Ctrl+] leaves it running
  sessions            terminal sessions (IDs for attach)
  kill ID|all         end a terminal session
  forward [list]      local ports opened on this PC's tailnet address
  forward add PORT    let your own tailnet devices (a phone in phone-wide
                      mode) reach 127.0.0.1:PORT here; off by default
  forward remove PORT|all
  phones              phones allowed on this PC
  revoke N|NAME|all   forget an allowed phone (it will be asked again)
  forget IP|all       forget another PC's saved identity (after AsenaScale
                      was reinstalled there)
  log [-f] [-n N]     the app's log (-f: keep following)
  start               start the tray app in the background
  quit                stop the tray app
  update              install the latest release now (sessions are closed)
  version

Add --json to status, devices or sessions for machine-readable output.
";

pub fn run(args: &[String]) -> i32 {
    attach_console();
    let cmd = args.first().map(String::as_str).unwrap_or("help");
    match cmd {
        "help" | "-h" | "--help" => {
            print!("{HELP}");
            0
        }
        "version" | "-V" | "--version" => {
            println!("asenascale {}", env!("AS_VERSION"));
            0
        }
        "log" | "logs" => log(&args[1..]),
        "update" | "upgrade" => {
            match crate::update::available() {
                Ok(None) => {
                    println!("up to date ({})", crate::update::current());
                    0
                }
                Ok(Some(v)) => {
                    println!("updating {} -> {v}...", crate::update::current());
                    match crate::update::apply(&v) {
                        Ok(()) => {
                            // Windows: the installer restarts the app. Linux: restart it here.
                            #[cfg(not(windows))]
                            {
                                let was_running = ask(&["quit".into()]).is_ok();
                                if was_running {
                                    std::thread::sleep(Duration::from_millis(800));
                                    start_app();
                                }
                            }
                            println!("done");
                            0
                        }
                        Err(e) => {
                            eprintln!("update failed: {e:#}");
                            1
                        }
                    }
                }
                Err(e) => {
                    eprintln!("can't check for updates: {e:#}");
                    1
                }
            }
        }
        "start" => {
            if ask(&["ping-app".into()]).is_ok() {
                println!("already running");
                return 0;
            }
            start_app();
            if wait_running() {
                println!("AsenaScale started (look for it in the system tray)");
                0
            } else {
                eprintln!("AsenaScale didn't start; see: asenascale log");
                1
            }
        }
        "attach" | "a" | "claude" | "codex" | "gemini" | "grok" | "new" => {
            if ask(&["ping-app".into()]).is_err() {
                start_app();
                if !wait_running() {
                    eprintln!("AsenaScale didn't start; see: asenascale log");
                    return 1;
                }
            }
            let (id, command) = match cmd {
                "claude" | "codex" | "gemini" | "grok" => (None, Some(std::iter::once(cmd.to_string()).chain(args[1..].iter().cloned()).collect::<Vec<_>>().join(" "))),
                "new" => (None, Some(args[1..].join(" "))),
                _ => match args.get(1) {
                    Some(id) => (Some(id.clone()), None),
                    None => match only_session() {
                        Ok(id) => (Some(id), None),
                        Err(msg) => {
                            eprint!("{msg}");
                            return 1;
                        }
                    },
                },
            };
            match term::attach(id, command) {
                Ok(msg) => {
                    eprintln!("{msg}");
                    0
                }
                Err(e) => {
                    eprintln!("{e}");
                    1
                }
            }
        }
        "exec" => {
            let Some(dev) = args.get(1) else {
                eprintln!("usage: asenascale exec DEVICE -- CMD");
                return 2;
            };
            let rest = &args[2..];
            let rest = if rest.first().map(String::as_str) == Some("--") { &rest[1..] } else { rest };
            if rest.is_empty() {
                eprintln!("usage: asenascale exec DEVICE -- CMD");
                return 2;
            }
            remote(dev, &rest.join(" "), Sink::Stdout)
        }
        "windows" => match args.get(1) {
            Some(dev) => remote(dev, "mc list", Sink::Stdout),
            None => {
                eprintln!("usage: asenascale windows DEVICE");
                2
            }
        },
        "shot" => match shot_args(&args[1..]) {
            Ok((dev, cmd, file)) => remote(&dev, &cmd, Sink::File(file)),
            Err(usage) => {
                eprintln!("{usage}");
                2
            }
        },
        "forget" => forget(&args[1..]),
        "up" => run(&["connect".into()]),
        "down" => run(&["disconnect".into()]),
        "list" | "ls" => run(&["devices".into()]),
        _ => {
            // Anything else needs the app; start it for commands that imply it.
            if ask(&["ping-app".into()]).is_err() {
                if cmd == "quit" {
                    println!("AsenaScale isn't running");
                    return 0;
                }
                if matches!(cmd, "forward" | "forwards") {
                    // Not running: edit the saved list; it applies on start.
                    let mut fw = crate::forward::Forwards::load();
                    return match crate::forward::command(&mut fw, args, None) {
                        Ok((text, changed)) => {
                            print!("{text}");
                            if changed {
                                println!("AsenaScale isn't running; this applies when it starts");
                            }
                            0
                        }
                        Err(e) => {
                            eprintln!("{e:#}");
                            1
                        }
                    };
                }
                if matches!(cmd, "connect" | "login" | "status") {
                    start_app();
                    if !wait_running() {
                        eprintln!("AsenaScale didn't start; see: asenascale log");
                        return 1;
                    }
                } else {
                    eprintln!("AsenaScale isn't running. Start it with: asenascale start");
                    return 1;
                }
            }
            match ask(args) {
                Ok((code, text)) => {
                    if code == 0 {
                        print!("{text}");
                    } else {
                        eprint!("{text}");
                    }
                    code
                }
                Err(e) => {
                    eprintln!("{e}");
                    1
                }
            }
        }
    }
}


pub fn is_running() -> bool {
    ask(&["ping-app".into()]).is_ok()
}

pub fn quit_running() {
    let _ = ask(&["quit".into()]);
}


/// The session to join when none was named: the only one, else a list.
fn only_session() -> Result<String, String> {
    let (_, json) = ask(&["sessions".into(), "--json".into()]).map_err(|e| format!("{e}\n"))?;
    let v: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap_or_default();
    match v.len() {
        0 => Err("no sessions yet; start one with: asenascale claude\n".into()),
        1 => Ok(v[0]["id"].as_str().unwrap_or_default().to_string()),
        _ => {
            let (_, table) = ask(&["sessions".into()]).map_err(|e| format!("{e}\n"))?;
            Err(format!("{table}\nwhich one? asenascale attach ID\n"))
        }
    }

}

/// One request to the running app: (exit code, text).
fn ask(args: &[String]) -> std::io::Result<(i32, String)> {
    let ep = Endpoint::load().ok_or_else(|| std::io::Error::other("AsenaScale isn't running"))?;
    let mut s = TcpStream::connect_timeout(&([127, 0, 0, 1], ep.port).into(), Duration::from_secs(2))?;
    s.set_read_timeout(Some(Duration::from_secs(30)))?;
    s.write_all(format!("{} {}\n", ep.token, args.join(" ")).as_bytes())?;
    let mut r = BufReader::new(s);
    let mut first = String::new();
    r.read_line(&mut first)?;
    let code = first.trim().parse().map_err(|_| std::io::Error::other("AsenaScale isn't running"))?;
    let mut text = String::new();
    r.read_to_string(&mut text)?;
    Ok((code, text))
}

fn start_app() {
    let exe = std::env::var_os("APPIMAGE").map(std::path::PathBuf::from).or_else(|| std::env::current_exe().ok());
    if let Some(exe) = exe {
        let mut c = std::process::Command::new(exe);
        c.stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            c.creation_flags(0x0000_0008 | 0x0000_0200); // DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        }
        let _ = c.spawn();
    }
}

fn wait_running() -> bool {
    for _ in 0..40 {
        std::thread::sleep(Duration::from_millis(250));
        if ask(&["ping-app".into()]).is_ok() {
            return true;
        }
    }
    false
}

fn log(args: &[String]) -> i32 {
    let follow = args.iter().any(|a| a == "-f" || a == "--follow");
    let n: usize = args
        .iter()
        .position(|a| a == "-n")
        .and_then(|i| args.get(i + 1))
        .and_then(|v| v.parse().ok())
        .unwrap_or(40);
    let path = store::dir().join("host.log");
    let Ok(mut f) = std::fs::File::open(&path) else {
        eprintln!("no log yet ({})", path.display());
        return 1;
    };
    let mut all = String::new();
    let _ = f.read_to_string(&mut all);
    let lines: Vec<&str> = all.lines().collect();
    for l in &lines[lines.len().saturating_sub(n)..] {
        println!("{l}");
    }
    if !follow {
        return 0;
    }
    let mut pos = all.len() as u64;
    loop {
        std::thread::sleep(Duration::from_millis(500));
        let len = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        if len < pos {
            pos = 0; // started over
            if let Ok(nf) = std::fs::File::open(&path) {
                f = nf;
            }
        }
        if len > pos && f.seek(SeekFrom::Start(pos)).is_ok() {
            let mut more = String::new();
            let _ = f.read_to_string(&mut more);
            pos += more.len() as u64;
            print!("{more}");
            let _ = std::io::stdout().flush();
        }
    }
}

/// The app is a GUI program on Windows; borrow the terminal it was started
/// from. A GUI process gets no standard handles from a console, so they
/// are pointed at the console here (unless redirected to a file or pipe).
#[cfg(windows)]
fn attach_console() {
    use windows_sys::Win32::System::Console::{
        AttachConsole, GetStdHandle, SetStdHandle, ATTACH_PARENT_PROCESS, STD_ERROR_HANDLE, STD_INPUT_HANDLE, STD_OUTPUT_HANDLE,
    };
    unsafe {
        if AttachConsole(ATTACH_PARENT_PROCESS) == 0 {
            return;
        }
        for (which, name) in [(STD_OUTPUT_HANDLE, "CONOUT$"), (STD_ERROR_HANDLE, "CONOUT$"), (STD_INPUT_HANDLE, "CONIN$")] {
            let h = GetStdHandle(which);
            if h.is_null() || h == windows_sys::Win32::Foundation::INVALID_HANDLE_VALUE {
                if let Some(c) = term::open_console(name) {
                    SetStdHandle(which, c);
                }
            }
        }
    }
}

#[cfg(not(windows))]
fn attach_console() {}

/// A terminal on this PC joined to a session: raw keys in, output out.
mod term {
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpStream;
    use std::time::Duration;

    use crate::control::Endpoint;

    const DETACH: u8 = 0x1d; // Ctrl+]

    pub fn attach(id: Option<String>, command: Option<String>) -> Result<String, String> {
        let ep = Endpoint::load().ok_or("AsenaScale isn't running")?;
        let mut s = TcpStream::connect(("127.0.0.1", ep.port)).map_err(|e| e.to_string())?;
        s.set_nodelay(true).ok();
        let (cols, rows) = size();
        let b64 = |v: &str| {
            use std::fmt::Write as _;
            let a = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
            let mut out = String::new();
            for c in v.as_bytes().chunks(3) {
                let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
                for i in 0..=c.len() {
                    let _ = out.write_char(a[(n >> (18 - 6 * i) & 63) as usize] as char);
                }
            }
            out
        };
        let cwd = std::env::current_dir().map(|d| d.display().to_string()).unwrap_or_default();
        let line = match (&id, &command) {
            (Some(id), _) => format!("{} attach {id} {cols} {rows} - -\n", ep.token),
            (None, cmd) => {
                let cmd = cmd.clone().unwrap_or_default();
                let cmd = if cmd.trim().is_empty() { "-".to_string() } else { b64(&cmd) };
                format!("{} attach - {cols} {rows} {} {cmd}\n", ep.token, b64(&cwd))
            }
        };
        s.write_all(line.as_bytes()).map_err(|e| e.to_string())?;
        let mut reader = BufReader::new(s.try_clone().map_err(|e| e.to_string())?);
        let mut first = String::new();
        reader.read_line(&mut first).map_err(|e| e.to_string())?;
        let first = first.trim().to_string();
        if !first.starts_with("0 ") {
            let mut msg = String::new();
            let _ = reader.read_to_string(&mut msg);
            return Err(msg.trim().to_string());
        }
        let id = first[2..].to_string();

        let raw = Raw::enter().map_err(|e| format!("not a terminal: {e}"))?;
        // Keys -> session.
        let mut keys = s.try_clone().map_err(|e| e.to_string())?;
        std::thread::spawn(move || {
            let mut buf = [0u8; 4096];
            loop {
                let n = match read_input(&mut buf) {
                    Some(n) if n > 0 => n,
                    _ => break,
                };
                let data = &buf[..n];
                if let Some(i) = data.iter().position(|&b| b == DETACH) {
                    let _ = send(&mut keys, &data[..i]);
                    break;
                }
                if send(&mut keys, data).is_err() {
                    break;
                }
            }
            let _ = keys.shutdown(std::net::Shutdown::Both);
        });
        // Window size -> session (Windows has no resize signal, so poll).
        let mut sizes = s.try_clone().map_err(|e| e.to_string())?;
        std::thread::spawn(move || {
            let mut last = size();
            loop {
                std::thread::sleep(Duration::from_millis(400));
                let now = size();
                if now != last {
                    last = now;
                    let mut f = vec![b'r'];
                    f.extend_from_slice(&now.0.to_be_bytes());
                    f.extend_from_slice(&now.1.to_be_bytes());
                    if sizes.write_all(&f).is_err() {
                        break;
                    }
                }
            }
        });
        // Session -> screen.
        let mut buf = [0u8; 16 * 1024];
        loop {
            match reader.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => write_output(&buf[..n]),
            }
        }
        drop(raw);
        let alive = {
            let ep = Endpoint::load();
            ep.and_then(|ep| {
                let mut c = TcpStream::connect(("127.0.0.1", ep.port)).ok()?;
                c.write_all(format!("{} sessions --json\n", ep.token).as_bytes()).ok()?;
                let mut t = String::new();
                c.read_to_string(&mut t).ok()?;
                Some(t.contains(&format!("\"{id}\"")))
            })
            .unwrap_or(false)
        };
        Ok(if alive {
            format!("\r\n[left session {id}; it keeps running: asenascale attach {id}]")
        } else {
            format!("\r\n[session {id} ended]")
        })
    }

    fn send(s: &mut TcpStream, data: &[u8]) -> std::io::Result<()> {
        if data.is_empty() {
            return Ok(());
        }
        let mut f = Vec::with_capacity(data.len() + 5);
        f.push(b'd');
        f.extend_from_slice(&(data.len() as u32).to_be_bytes());
        f.extend_from_slice(data);
        s.write_all(&f)
    }

    // --- Linux / macOS ---------------------------------------------------------

    #[cfg(not(windows))]
    pub struct Raw(libc::termios);

    #[cfg(not(windows))]
    impl Raw {
        pub fn enter() -> std::io::Result<Raw> {
            unsafe {
                let mut t: libc::termios = std::mem::zeroed();
                if libc::tcgetattr(0, &mut t) != 0 {
                    return Err(std::io::Error::last_os_error());
                }
                let old = t;
                libc::cfmakeraw(&mut t);
                libc::tcsetattr(0, libc::TCSANOW, &t);
                Ok(Raw(old))
            }
        }
    }

    #[cfg(not(windows))]
    impl Drop for Raw {
        fn drop(&mut self) {
            unsafe {
                libc::tcsetattr(0, libc::TCSANOW, &self.0);
            }
        }
    }

    #[cfg(not(windows))]
    fn size() -> (u16, u16) {
        unsafe {
            let mut w: libc::winsize = std::mem::zeroed();
            if libc::ioctl(1, libc::TIOCGWINSZ, &mut w) == 0 && w.ws_col > 0 {
                return (w.ws_col, w.ws_row);
            }
        }
        (80, 24)
    }

    #[cfg(not(windows))]
    fn read_input(buf: &mut [u8]) -> Option<usize> {
        std::io::stdin().lock().read(buf).ok()
    }

    #[cfg(not(windows))]
    fn write_output(data: &[u8]) {
        let mut o = std::io::stdout().lock();
        let _ = o.write_all(data);
        let _ = o.flush();
    }

    // --- Windows ---------------------------------------------------------------

    #[cfg(windows)]
    use windows_sys::Win32::Foundation::HANDLE;

    #[cfg(windows)]
    pub fn open_console(name: &str) -> Option<HANDLE> {
        use windows_sys::Win32::Foundation::{GENERIC_READ, GENERIC_WRITE, INVALID_HANDLE_VALUE};
        use windows_sys::Win32::Storage::FileSystem::{CreateFileW, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING};
        let w: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
        let h = unsafe {
            CreateFileW(w.as_ptr(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, std::ptr::null(), OPEN_EXISTING, 0, std::ptr::null_mut())
        };
        (h != INVALID_HANDLE_VALUE && !h.is_null()).then_some(h)
    }

    #[cfg(windows)]
    fn handles() -> (HANDLE, HANDLE) {
        use windows_sys::Win32::System::Console::{GetStdHandle, STD_INPUT_HANDLE, STD_OUTPUT_HANDLE};
        unsafe { (GetStdHandle(STD_INPUT_HANDLE), GetStdHandle(STD_OUTPUT_HANDLE)) }
    }

    #[cfg(windows)]
    pub struct Raw {
        in_mode: u32,
        out_mode: u32,
        in_cp: u32,
        out_cp: u32,
    }

    #[cfg(windows)]
    impl Raw {
        pub fn enter() -> std::io::Result<Raw> {
            use windows_sys::Win32::System::Console::*;
            let (i, o) = handles();
            unsafe {
                let (mut in_mode, mut out_mode) = (0u32, 0u32);
                if GetConsoleMode(i, &mut in_mode) == 0 || GetConsoleMode(o, &mut out_mode) == 0 {
                    return Err(std::io::Error::last_os_error());
                }
                let raw = Raw { in_mode, out_mode, in_cp: GetConsoleCP(), out_cp: GetConsoleOutputCP() };
                // Keys arrive as VT sequences (arrows, etc.), nothing cooked.
                SetConsoleMode(
                    i,
                    (in_mode & !(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_PROCESSED_INPUT | ENABLE_QUICK_EDIT_MODE))
                        | ENABLE_VIRTUAL_TERMINAL_INPUT
                        | ENABLE_EXTENDED_FLAGS,
                );
                SetConsoleMode(o, out_mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING | ENABLE_PROCESSED_OUTPUT | DISABLE_NEWLINE_AUTO_RETURN);
                SetConsoleCP(65001);
                SetConsoleOutputCP(65001);
                Ok(raw)
            }
        }
    }

    #[cfg(windows)]
    impl Drop for Raw {
        fn drop(&mut self) {
            use windows_sys::Win32::System::Console::*;
            let (i, o) = handles();
            unsafe {
                SetConsoleMode(i, self.in_mode);
                SetConsoleMode(o, self.out_mode);
                SetConsoleCP(self.in_cp);
                SetConsoleOutputCP(self.out_cp);
            }
        }
    }

    #[cfg(windows)]
    fn size() -> (u16, u16) {
        use windows_sys::Win32::System::Console::{GetConsoleScreenBufferInfo, CONSOLE_SCREEN_BUFFER_INFO};
        unsafe {
            let mut info: CONSOLE_SCREEN_BUFFER_INFO = std::mem::zeroed();
            if GetConsoleScreenBufferInfo(handles().1, &mut info) != 0 {
                let w = info.srWindow;
                return ((w.Right - w.Left + 1).max(10) as u16, (w.Bottom - w.Top + 1).max(4) as u16);
            }
        }
        (80, 24)
    }

    #[cfg(windows)]
    fn read_input(buf: &mut [u8]) -> Option<usize> {
        use windows_sys::Win32::Storage::FileSystem::ReadFile;
        let mut n = 0u32;
        let ok = unsafe { ReadFile(handles().0, buf.as_mut_ptr(), buf.len() as u32, &mut n, std::ptr::null_mut()) };
        (ok != 0).then_some(n as usize)
    }

    #[cfg(windows)]
    fn write_output(data: &[u8]) {
        use windows_sys::Win32::Storage::FileSystem::WriteFile;
        let mut off = 0;
        while off < data.len() {
            let mut n = 0u32;
            let ok = unsafe {
                WriteFile(handles().1, data[off..].as_ptr(), (data.len() - off) as u32, &mut n, std::ptr::null_mut())
            };
            if ok == 0 || n == 0 {
                break;
            }
            off += n as usize;
        }
    }
}




const SHOT_USAGE: &str = "usage: asenascale shot DEVICE [screen|active|output N|window ID] -o FILE.png";

/// (device, remote command, output file) for `shot`.
fn shot_args(args: &[String]) -> Result<(String, String, std::path::PathBuf), &'static str> {
    let dev = args.first().ok_or(SHOT_USAGE)?.clone();
    let mut kind = "screen".to_string();
    let mut id = String::new();
    let mut file = None;
    let mut i = 1;
    while let Some(a) = args.get(i) {
        match a.as_str() {
            "-o" | "--out" => {
                file = Some(std::path::PathBuf::from(args.get(i + 1).ok_or(SHOT_USAGE)?));
                i += 1;
            }
            "screen" | "active" => kind = a.clone(),
            "output" | "window" => {
                let v = args.get(i + 1).filter(|v| v.parse::<u32>().is_ok()).ok_or(SHOT_USAGE)?;
                kind = a.clone();
                id = v.clone();
                i += 1;
            }
            _ => return Err(SHOT_USAGE),
        }
        i += 1;
    }
    let file = file.ok_or(SHOT_USAGE)?;
    let cmd = if id.is_empty() { format!("mc shot {kind}") } else { format!("mc shot {kind} {id}") };
    Ok((dev, cmd, file))
}

enum Sink {
    Stdout,
    File(std::path::PathBuf),
}

/// The most `shot` holds in memory before giving up on a screenshot.
const MAX_SHOT_BYTES: usize = 256 * 1024 * 1024;

/// Appends `data` to `buf` unless that would make it longer than `max`.
fn push_capped(buf: &mut Vec<u8>, data: &[u8], max: usize) -> anyhow::Result<()> {
    if buf.len() + data.len() > max {
        anyhow::bail!("screenshot larger than 256 MB");
    }
    buf.extend_from_slice(data);
    Ok(())
}

/// Writes and flushes, so output shows up as the other PC sends it.
fn write_now(mut w: impl Write, data: &[u8]) -> anyhow::Result<()> {
    w.write_all(data)?;
    w.flush()?;
    Ok(())
}

/// The tray's answer to `dial DEVICE`: "PORT IP" (the local port to connect
/// to, and the device's tailnet IP, which names it in known_peers.json).
fn parse_dial(text: &str) -> Result<(u16, std::net::IpAddr), String> {
    let mut words = text.split_whitespace();
    let port = words.next().and_then(|w| w.parse::<u16>().ok());
    let ip = words.next().map(|w| w.parse::<std::net::IpAddr>());
    match (port, ip, words.next()) {
        (Some(port), Some(Ok(ip)), None) => Ok((port, ip)),
        (Some(_), None, None) => Err("the running AsenaScale is older than this command; restart it (asenascale quit, then asenascale start)".into()),
        _ => Err(format!("unexpected answer from AsenaScale: {}", text.trim())),
    }
}

/// `asenascale forget IP|all`: works without the tray app.
fn forget(args: &[String]) -> i32 {
    let which = match args.first().map(String::as_str) {
        Some("all") => "all".to_string(),
        Some(w) => match w.parse::<std::net::IpAddr>() {
            Ok(ip) => ip.to_string(),
            Err(_) => {
                eprintln!("forget takes the other PC's IP; use the IP from `asenascale devices`");
                return 2;
            }
        },
        None => {
            eprintln!("usage: asenascale forget IP|all");
            return 2;
        }
    };
    match crate::known::Known::open().forget(&which) {
        Ok(0) => {
            eprintln!("nothing saved for {which}");
            1
        }
        Ok(n) => {
            println!("forgot {n}; the next connection learns it again");
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

/// The remote exit code as ours. Windows NTSTATUS codes (0xC0000005 ...)
/// come through as negative i32, as Windows itself reports them.
fn exit_code(code: u32) -> i32 {
    code as i32
}

/// Saves a screenshot: written to FILE.part next to it, then renamed, so a
/// failure never leaves a truncated FILE. Refuses anything not a PNG.
fn save_png(path: &std::path::Path, data: &[u8]) -> anyhow::Result<()> {
    use anyhow::Context;
    if !data.starts_with(b"\x89PNG\r\n\x1a\n") {
        anyhow::bail!("the other PC sent no image");
    }
    let mut part = path.as_os_str().to_owned();
    part.push(".part");
    let part = std::path::PathBuf::from(part);
    let saved = std::fs::write(&part, data).and_then(|()| std::fs::rename(&part, path));
    if saved.is_err() {
        let _ = std::fs::remove_file(&part);
    }
    saved.with_context(|| format!("can't write {}", path.display()))
}

/// Dials DEVICE through the tray, runs `cmd` there, delivers the output.
fn remote(dev: &str, cmd: &str, sink: Sink) -> i32 {
    let (port, ip) = match ask(&["dial".into(), dev.into()]) {
        Ok((0, text)) => match parse_dial(&text) {
            Ok(answer) => answer,
            Err(msg) => {
                eprintln!("{msg}");
                return 1;
            }
        },
        Ok((_, text)) => {
            eprint!("{text}");
            return 1;
        }
        Err(e) => {
            eprintln!("{e}");
            return 1;
        }
    };
    let to_file = matches!(sink, Sink::File(_));
    let mut image = Vec::new();
    let result = crate::store::client_key().and_then(|key| {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
        let (who, known) = (ip.to_string(), crate::known::Known::open());
        rt.block_on(crate::peer::exec(([127, 0, 0, 1], port).into(), &who, &known, key, cmd, |stream, data| match stream {
            crate::peer::Stream::Out if to_file => push_capped(&mut image, data, MAX_SHOT_BYTES),
            crate::peer::Stream::Out => write_now(std::io::stdout().lock(), data),
            crate::peer::Stream::Err => write_now(std::io::stderr().lock(), data),
        }))
    });
    let _ = ask(&["undial".into(), port.to_string()]);
    match result {
        Ok(code) => {
            if let Sink::File(path) = sink {
                if code == 0 {
                    if let Err(e) = save_png(&path, &image) {
                        eprintln!("{e:#}");
                        return 1;
                    }
                    println!("{} ({} bytes)", path.display(), image.len());
                }
            }
            exit_code(code)
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

#[cfg(test)]
mod shot_args_tests {
    use super::shot_args;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn default_is_all_screens() {
        let (dev, cmd, file) = shot_args(&s(&["win", "-o", "a.png"])).unwrap();
        assert_eq!((dev.as_str(), cmd.as_str(), file.to_str().unwrap()), ("win", "mc shot screen", "a.png"));
    }

    #[test]
    fn window_takes_an_id() {
        assert_eq!(shot_args(&s(&["win", "window", "4242", "-o", "a.png"])).unwrap().1, "mc shot window 4242");
    }

    #[test]
    fn output_needs_a_number_and_file_is_required() {
        assert!(shot_args(&s(&["win", "output", "-o", "a.png"])).is_err());
        assert!(shot_args(&s(&["win"])).is_err());
    }
}

#[cfg(test)]
mod shot_buffer_tests {
    use super::push_capped;

    #[test]
    fn grows_up_to_the_cap_and_refuses_past_it() {
        let mut buf = Vec::new();
        push_capped(&mut buf, b"abc", 4).unwrap();
        push_capped(&mut buf, b"d", 4).unwrap();
        let err = push_capped(&mut buf, b"e", 4).unwrap_err();
        assert_eq!(err.to_string(), "screenshot larger than 256 MB");
        assert_eq!(buf, b"abcd");
    }
}

#[cfg(test)]
mod dial_answer_tests {
    use super::parse_dial;

    #[test]
    fn port_and_ip() {
        assert_eq!(parse_dial("40123 100.64.0.9\n").unwrap(), (40123, "100.64.0.9".parse().unwrap()));
    }

    #[test]
    fn a_bare_port_means_an_older_tray_app() {
        let err = parse_dial("40123\n").unwrap_err();
        assert!(err.contains("older"), "{err}");
    }

    #[test]
    fn garbage_is_reported_as_such() {
        assert!(parse_dial("hello world\n").unwrap_err().contains("unexpected answer"));
        assert!(parse_dial("40123 nope\n").unwrap_err().contains("unexpected answer"));
    }
}

#[cfg(test)]
mod save_png_tests {
    use super::{exit_code, save_png};
    use crate::store::TempDir;

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\nrest";

    fn files(dir: &std::path::Path) -> Vec<String> {
        let mut v: Vec<_> = std::fs::read_dir(dir).unwrap().map(|e| e.unwrap().file_name().into_string().unwrap()).collect();
        v.sort();
        v
    }

    #[test]
    fn writes_the_image_and_leaves_no_part_file() {
        let dir = TempDir::new("png-ok");
        std::fs::create_dir_all(&dir.0).unwrap();
        let path = dir.0.join("a.png");
        save_png(&path, PNG).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), PNG);
        assert_eq!(files(&dir.0), ["a.png"]);
    }

    #[test]
    fn refuses_what_is_not_a_png() {
        let dir = TempDir::new("png-bad");
        std::fs::create_dir_all(&dir.0).unwrap();
        let path = dir.0.join("a.png");
        for data in [&b""[..], b"usage: mc shot"] {
            let err = save_png(&path, data).unwrap_err();
            assert_eq!(err.to_string(), "the other PC sent no image");
        }
        assert!(files(&dir.0).is_empty());
    }

    #[test]
    fn a_failed_write_removes_the_part_file() {
        let dir = TempDir::new("png-fail");
        // The target is a non-empty directory: the rename fails.
        let path = dir.0.join("a.png");
        std::fs::create_dir_all(path.join("x")).unwrap();
        assert!(save_png(&path, PNG).is_err());
        assert_eq!(files(&dir.0), ["a.png"]);
    }

    #[test]
    fn exit_codes_keep_their_bits() {
        assert_eq!(exit_code(3), 3);
        // An NTSTATUS such as STATUS_ACCESS_VIOLATION.
        assert_eq!(exit_code(0xC000_0005), -1_073_741_819);
    }
}
