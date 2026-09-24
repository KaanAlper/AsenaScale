//! Who is knocking, and does the user want to let them in?

use std::net::IpAddr;
use std::process::Command;

/// Only the tailnet may connect: Tailscale's CGNAT range and ULA prefix,
/// plus loopback for local testing.
pub fn is_tailnet(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            v4.is_loopback() || (o[0] == 100 && (o[1] & 0xC0) == 64) // 100.64.0.0/10
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            v6.is_loopback()
                || (s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0) // fd7a:115c:a1e0::/48
                || v6.to_ipv4_mapped().map(|v4| is_tailnet(IpAddr::V4(v4))).unwrap_or(false)
        }
    }
}

fn tailscale_cli() -> Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let installed = r"C:\Program Files\Tailscale\tailscale.exe";
        let exe = if std::path::Path::new(installed).exists() { installed } else { "tailscale" };
        let mut c = Command::new(exe);
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW: no console flash
        c
    }
    #[cfg(not(windows))]
    {
        Command::new("tailscale")
    }
}

/// Human name of the tailnet device at `ip`, e.g. "mobile-claude-pixel-8
/// (Kaan)", via the local Tailscale client. Falls back to the IP.
pub fn device_name(ip: IpAddr) -> String {
    let out = tailscale_cli().args(["whois", "--json", &ip.to_string()]).output();
    let parsed = out
        .ok()
        .filter(|o| o.status.success())
        .and_then(|o| serde_json::from_slice::<serde_json::Value>(&o.stdout).ok());
    let Some(v) = parsed else { return ip.to_string() };
    let node = v["Node"]["ComputedName"]
        .as_str()
        .or_else(|| v["Node"]["Hostinfo"]["Hostname"].as_str())
        .unwrap_or("");
    let user = v["UserProfile"]["DisplayName"]
        .as_str()
        .or_else(|| v["UserProfile"]["LoginName"].as_str())
        .unwrap_or("");
    match (node.is_empty(), user.is_empty()) {
        (false, false) => format!("{node} ({user})"),
        (false, true) => node.to_string(),
        _ => ip.to_string(),
    }
}

/// This PC's Tailscale IPv4, for the tray menu.
pub fn own_ip() -> Option<String> {
    let out = tailscale_cli().args(["ip", "-4"]).output().ok()?;
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!s.is_empty()).then_some(s)
}

/// Blocking yes/no prompt shown on the PC's desktop.
pub fn ask(device: &str, fingerprint: &str) -> bool {
    // Test hook, compiled into debug builds only.
    if cfg!(debug_assertions) && std::env::var_os("MCH_AUTO_APPROVE").is_some() {
        return true;
    }
    let text = format!(
        "{device}\nMobile Claude ile bu bilgisayara bağlanmak istiyor.\n\n\
         İzin verirsen bu telefon terminali kullanabilir ve ekran görüntüsü alabilir. \
         Bir daha sorulmaz.\n\nAnahtar: {fingerprint}\n\nİzin veriyor musun?"
    );
    show_yes_no("Mobile Claude — bağlantı isteği", &text)
}

#[cfg(windows)]
fn show_yes_no(title: &str, text: &str) -> bool {
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        MessageBoxW, IDYES, MB_DEFBUTTON2, MB_ICONQUESTION, MB_SETFOREGROUND, MB_TOPMOST, MB_YESNO,
    };
    let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    let (t, m) = (wide(title), wide(text));
    // Topmost + foreground: the request comes from the background, and the
    // dialog must not hide behind other windows. "No" is the default button.
    let r = unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            m.as_ptr(),
            t.as_ptr(),
            MB_YESNO | MB_ICONQUESTION | MB_TOPMOST | MB_SETFOREGROUND | MB_DEFBUTTON2,
        )
    };
    r == IDYES
}

#[cfg(not(windows))]
fn show_yes_no(title: &str, text: &str) -> bool {
    rfd::MessageDialog::new()
        .set_title(title)
        .set_description(text)
        .set_level(rfd::MessageLevel::Warning)
        .set_buttons(rfd::MessageButtons::YesNo)
        .show()
        == rfd::MessageDialogResult::Yes
}

/// Plain info box (e.g. "already running").
pub fn info(title: &str, text: &str) {
    #[cfg(windows)]
    {
        use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONINFORMATION, MB_OK, MB_TOPMOST};
        let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
        let (t, m) = (wide(title), wide(text));
        unsafe { MessageBoxW(std::ptr::null_mut(), m.as_ptr(), t.as_ptr(), MB_OK | MB_ICONINFORMATION | MB_TOPMOST) };
    }
    #[cfg(not(windows))]
    {
        rfd::MessageDialog::new().set_title(title).set_description(text).show();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tailnet_ranges() {
        assert!(is_tailnet("100.101.102.103".parse().unwrap()));
        assert!(is_tailnet("100.64.0.1".parse().unwrap()));
        assert!(is_tailnet("100.127.255.254".parse().unwrap()));
        assert!(!is_tailnet("100.128.0.1".parse().unwrap()));
        assert!(!is_tailnet("100.63.255.255".parse().unwrap()));
        assert!(!is_tailnet("192.168.1.10".parse().unwrap()));
        assert!(is_tailnet("fd7a:115c:a1e0::1".parse().unwrap()));
        assert!(!is_tailnet("fd7a:115c:a1e1::1".parse().unwrap()));
        assert!(is_tailnet("::ffff:100.100.1.1".parse().unwrap()));
        assert!(is_tailnet("127.0.0.1".parse().unwrap()));
    }
}
