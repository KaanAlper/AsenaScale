//! "Start when I sign in", per user, no admin rights needed.

use std::path::PathBuf;

use crate::store;

/// True exactly once: the first time the app ever runs on this account.
pub fn first_run() -> bool {
    let marker = store::dir().join(".installed");
    if marker.exists() {
        return false;
    }
    let _ = std::fs::create_dir_all(store::dir());
    let _ = std::fs::write(marker, b"1");
    true
}

/// What to launch at sign-in. Inside an AppImage the running binary lives
/// on a temporary mount, so use the AppImage file itself.
fn exe() -> Option<PathBuf> {
    std::env::var_os("APPIMAGE").map(PathBuf::from).or_else(|| std::env::current_exe().ok())
}

#[cfg(windows)]
mod imp {
    use std::os::windows::process::CommandExt;
    use std::process::Command;

    const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    const NAME: &str = "AsenaScale";

    fn reg(args: &[&str]) -> bool {
        Command::new("reg.exe")
            .args(args)
            .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    pub fn is_enabled() -> bool {
        reg(&["query", KEY, "/v", NAME])
    }

    pub fn set(on: bool) {
        if on {
            if let Some(exe) = super::exe() {
                let value = format!("\"{}\"", exe.display());
                reg(&["add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", &value, "/f"]);
            }
        } else {
            reg(&["delete", KEY, "/v", NAME, "/f"]);
        }
    }
}

#[cfg(not(windows))]
mod imp {
    use std::path::PathBuf;

    fn file() -> Option<PathBuf> {
        dirs::config_dir().map(|d| d.join("autostart").join("asenascale.desktop"))
    }

    pub fn is_enabled() -> bool {
        file().map(|f| f.exists()).unwrap_or(false)
    }

    pub fn set(on: bool) {
        let Some(f) = file() else { return };
        if on {
            let Some(exe) = super::exe() else { return };
            let _ = std::fs::create_dir_all(f.parent().unwrap());
            let _ = std::fs::write(
                &f,
                format!(
                    "[Desktop Entry]\nType=Application\nName=AsenaScale\nExec=\"{}\"\nX-GNOME-Autostart-enabled=true\n",
                    exe.display()
                ),
            );
        } else {
            let _ = std::fs::remove_file(f);
        }
    }
}

pub use imp::{is_enabled, set};
