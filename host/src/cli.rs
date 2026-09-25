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
  claude [ARGS]       start Claude Code here, in this folder, as a shared
                      session: the phone can join it live (and vice versa)
  new [COMMAND]       the same with any command (default: a shell)
  attach [ID]         join a running session (one the phone started, too);
                      Ctrl+] leaves it running
  sessions            terminal sessions (IDs for attach)
  kill ID|all         end a terminal session
  phones              phones allowed on this PC
  revoke N|NAME|all   forget an allowed phone (it will be asked again)
  log [-f] [-n N]     the app's log (-f: keep following)
  start               start the tray app in the background
  quit                stop the tray app
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
            println!("asenascale {}", env!("CARGO_PKG_VERSION"));
            0
        }
        "log" | "logs" => log(&args[1..]),
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
        "attach" | "a" | "claude" | "new" => {
            if ask(&["ping-app".into()]).is_err() {
                start_app();
                if !wait_running() {
                    eprintln!("AsenaScale didn't start; see: asenascale log");
                    return 1;
                }
            }
            let (id, command) = match cmd {
                "claude" => (None, Some(std::iter::once("claude".to_string()).chain(args[1..].iter().cloned()).collect::<Vec<_>>().join(" "))),
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
