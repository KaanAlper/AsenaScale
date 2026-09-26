//! Updates from the GitHub releases, without a browser: the latest
//! release's tag is compared with this build; Windows runs the new
//! installer silently (it restarts the app), the Linux AppImage replaces
//! itself. Uses curl, which Windows 10+ and every Linux desktop have.

use std::process::Command;

use anyhow::{anyhow, bail, Context, Result};

const REPO: &str = "KaanAlper/AsenaScale";

pub fn current() -> &'static str {
    env!("AS_VERSION")
}

fn curl() -> Command {
    let mut c = Command::new("curl");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    c.args(["-fsSL", "--max-time", "600", "-H", "User-Agent: AsenaScale"]);
    c
}

/// The newest published version ("0.5.2"), from the latest release.
pub fn latest() -> Result<String> {
    let out = curl()
        .arg(format!("https://api.github.com/repos/{REPO}/releases/latest"))
        .output()
        .context("curl not found")?;
    if !out.status.success() {
        bail!("{}", String::from_utf8_lossy(&out.stderr).trim());
    }
    let v: serde_json::Value = serde_json::from_slice(&out.stdout)?;
    let tag = v["tag_name"].as_str().ok_or_else(|| anyhow!("no release"))?;
    Ok(tag.trim_start_matches('v').to_string())
}

/// "0.5.10" > "0.5.9"; a pre-release ("0.5.2-nightly.3") is older than "0.5.2".
pub fn newer(candidate: &str, than: &str) -> bool {
    fn parts(v: &str) -> (Vec<u64>, bool) {
        let (num, pre) = match v.split_once('-') {
            Some((n, _)) => (n, true),
            None => (v, false),
        };
        (num.split('.').map(|p| p.parse().unwrap_or(0)).collect(), pre)
    }
    let (a, a_pre) = parts(candidate);
    let (b, b_pre) = parts(than);
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    b_pre && !a_pre
}

/// Some(version) when a newer release is out.
pub fn available() -> Result<Option<String>> {
    let v = latest()?;
    Ok(newer(&v, current()).then_some(v))
}

/// Downloads and installs the update. Windows: the silent installer closes
/// this process and starts the new one. Linux: the AppImage file is
/// replaced; call [`restart`] (or restart the running app) afterwards.
pub fn apply(version: &str) -> Result<()> {
    let base = format!("https://github.com/{REPO}/releases/download/v{version}");
    #[cfg(windows)]
    {
        let setup = std::env::temp_dir().join(format!("AsenaScale-Setup-{version}.exe"));
        let ok = curl().arg("-o").arg(&setup).arg(format!("{base}/AsenaScale-Setup.exe")).status()?.success();
        if !ok {
            bail!("download failed");
        }
        log::info!("updating to {version}");
        // Silent install: closes this app, replaces it, starts the new one.
        Command::new(&setup).arg("/S").spawn().context("running the installer")?;
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let Some(appimage) = std::env::var_os("APPIMAGE").map(std::path::PathBuf::from) else {
            bail!("not running from the AppImage; update with install.sh");
        };
        let tmp = appimage.with_extension("new");
        let ok = curl().arg("-o").arg(&tmp).arg(format!("{base}/AsenaScale-x86_64.AppImage")).status()?.success();
        if !ok {
            let _ = std::fs::remove_file(&tmp);
            bail!("download failed");
        }
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o755))?;
        std::fs::rename(&tmp, &appimage)?;
        log::info!("updated to {version}");
        Ok(())
    }
}

/// Linux: starts the (new) AppImage once this process has let go of the
/// port, and exits.
#[cfg(not(windows))]
pub fn restart() -> ! {
    if let Some(appimage) = std::env::var_os("APPIMAGE") {
        let _ = Command::new("sh")
            .arg("-c")
            .arg("sleep 2; exec \"$0\"")
            .arg(appimage)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn();
    }
    let _ = std::fs::remove_file(crate::control::Endpoint::path());
    std::process::exit(0);
}

/// Builds from a branch or a local checkout don't replace themselves.
pub fn auto_allowed() -> bool {
    !current().contains('-')
}

#[cfg(test)]
mod tests {
    use super::newer;

    #[test]
    fn versions() {
        assert!(newer("0.5.2", "0.5.1"));
        assert!(newer("0.5.10", "0.5.9"));
        assert!(newer("1.0.0", "0.9.9"));
        assert!(!newer("0.5.1", "0.5.1"));
        assert!(!newer("0.5.0", "0.5.1"));
        assert!(newer("0.5.1", "0.5.1-nightly.4"));
        assert!(!newer("0.5.1-nightly.4", "0.5.1"));
        assert!(newer("0.5.1", "0.3.0-nightly.30"));
    }
}
