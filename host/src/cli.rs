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
  sessions            terminal sessions kept for the phone
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

/// The app is a GUI program on Windows; borrow the terminal it was started from.
#[cfg(windows)]
fn attach_console() {
    use windows_sys::Win32::System::Console::{AttachConsole, ATTACH_PARENT_PROCESS};
    unsafe {
        AttachConsole(ATTACH_PARENT_PROCESS);
    }
}

#[cfg(not(windows))]
fn attach_console() {}
