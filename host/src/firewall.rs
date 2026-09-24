//! Windows Firewall: the Tailscale adapter usually counts as a *public*
//! network, and Windows' own "allow access?" prompt only ticks *private* by
//! default, which would leave the phone blocked. So the app adds one rule for
//! itself on every profile, with a single UAC prompt, the first time.

#[cfg(windows)]
mod imp {
    use std::os::windows::process::CommandExt;
    use std::process::Command;

    const RULE: &str = "AsenaScale";

    /// Whether our rule exists (querying needs no admin rights).
    pub fn has_rule() -> bool {
        Command::new("netsh")
            .args(["advfirewall", "firewall", "show", "rule", &format!("name={RULE}")])
            .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    /// Asks Windows (UAC) to add an inbound allow rule for this exe on all profiles.
    pub fn add_rule() {
        use windows_sys::Win32::UI::Shell::ShellExecuteW;
        use windows_sys::Win32::UI::WindowsAndMessaging::SW_HIDE;
        let Ok(exe) = std::env::current_exe() else { return };
        let args = format!(
            "advfirewall firewall add rule name=\"{RULE}\" dir=in action=allow enable=yes profile=any program=\"{}\"",
            exe.display()
        );
        let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let (verb, file, params) = (wide("runas"), wide("netsh"), wide(&args));
        unsafe {
            ShellExecuteW(std::ptr::null_mut(), verb.as_ptr(), file.as_ptr(), params.as_ptr(), std::ptr::null(), SW_HIDE);
        }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn has_rule() -> bool {
        true
    }
    pub fn add_rule() {}
}

pub use imp::{add_rule, has_rule};

/// Adds the rule if missing. Returns immediately; the UAC prompt runs on its own.
pub fn ensure() {
    if !has_rule() {
        add_rule();
    }
}
