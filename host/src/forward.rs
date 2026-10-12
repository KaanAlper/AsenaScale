//! `asenascale forward`: local TCP ports this PC opens on its tailnet address,
//! each piped to the same port on 127.0.0.1. A phone in phone-wide mode can
//! then reach a server running on the PC (e.g. port 8765) even while the PC's
//! own Tailscale is off. Opt-in: the list starts empty. Only devices of the
//! same Tailscale user get through (enforced by tsbridge), and every
//! connection is written to the log.

use std::path::PathBuf;

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};

/// More than enough for dev servers; keeps a typo loop from opening hundreds.
pub const MAX: usize = 32;

const USAGE: &str = "usage: asenascale forward [list] | add PORT... | remove PORT...|all";

#[derive(Serialize, Deserialize, Default)]
struct OnDisk {
    #[serde(default)]
    ports: Vec<u16>,
}

/// The allowlist, kept sorted and saved to forwards.json in the settings folder.
pub struct Forwards {
    ports: Vec<u16>,
    path: PathBuf,
}

impl Forwards {
    pub fn load() -> Forwards {
        Self::load_from(crate::store::dir().join("forwards.json"))
    }

    pub fn load_from(path: PathBuf) -> Forwards {
        let mut ports = std::fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice::<OnDisk>(&b).ok())
            .map(|d| d.ports)
            .unwrap_or_default();
        // A hand-edited file can't sneak in the reserved port or duplicates.
        ports.retain(|p| check(*p).is_ok());
        ports.sort_unstable();
        ports.dedup();
        ports.truncate(MAX);
        Forwards { ports, path }
    }

    pub fn ports(&self) -> &[u16] {
        &self.ports
    }

    /// "8765,3000": the form tsbridge's SetForwards takes.
    pub fn csv(&self) -> String {
        self.ports.iter().map(u16::to_string).collect::<Vec<_>>().join(",")
    }

    fn save(&self) -> Result<()> {
        if let Some(dir) = self.path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let json = serde_json::to_vec_pretty(&OnDisk { ports: self.ports.clone() })?;
        std::fs::write(&self.path, json).with_context(|| format!("saving {}", self.path.display()))
    }

    /// Adds a port; false if it was already there.
    pub fn add(&mut self, port: u16) -> Result<bool> {
        check(port)?;
        if self.ports.contains(&port) {
            return Ok(false);
        }
        if self.ports.len() >= MAX {
            bail!("at most {MAX} forwarded ports");
        }
        let mut next = self.ports.clone();
        next.push(port);
        next.sort_unstable();
        let old = std::mem::replace(&mut self.ports, next);
        self.save().inspect_err(|_| self.ports = old)?;
        Ok(true)
    }

    /// Removes a port; false if it wasn't there.
    pub fn remove(&mut self, port: u16) -> Result<bool> {
        if !self.ports.contains(&port) {
            return Ok(false);
        }
        let next: Vec<u16> = self.ports.iter().copied().filter(|p| *p != port).collect();
        let old = std::mem::replace(&mut self.ports, next);
        self.save().inspect_err(|_| self.ports = old)?;
        Ok(true)
    }

    /// Removes all; returns how many there were.
    pub fn clear(&mut self) -> Result<usize> {
        let old = std::mem::take(&mut self.ports);
        let n = old.len();
        self.save().inspect_err(|_| self.ports = old)?;
        Ok(n)
    }
}

fn check(port: u16) -> Result<u16> {
    if port == 0 {
        bail!("port must be 1-65535");
    }
    if port == crate::PORT {
        bail!("port {port} is AsenaScale's own port; it is always reachable");
    }
    Ok(port)
}

pub fn parse_port(s: &str) -> Result<u16> {
    let port: u16 = s.trim().parse().map_err(|_| anyhow!("not a port: {s:?} (1-65535)"))?;
    check(port)
}

/// Runs `forward ARGS` (args[0] is "forward") against `fw`. Returns the text
/// for the user and whether the list changed (so the caller re-applies it).
/// `ip` is this PC's tailnet IPv4, shown in the listing when known.
pub fn command(fw: &mut Forwards, args: &[String], ip: Option<&str>) -> Result<(String, bool)> {
    let sub = args.get(1).map(String::as_str).unwrap_or("list");
    let rest: Vec<&str> = args.iter().skip(2).map(String::as_str).filter(|a| !a.starts_with("--")).collect();
    match sub {
        "list" | "ls" => Ok((list(fw, ip), false)),
        "add" => {
            if rest.is_empty() {
                bail!("{USAGE}");
            }
            // Validate everything first, so a typo adds nothing.
            let ports = rest.iter().map(|a| parse_port(a)).collect::<Result<Vec<_>>>()?;
            let mut out = String::new();
            let mut changed = false;
            for p in ports {
                if fw.add(p)? {
                    changed = true;
                    out += &format!("forwarding tailnet port {p} -> 127.0.0.1:{p}\n");
                } else {
                    out += &format!("port {p} is already forwarded\n");
                }
            }
            Ok((out, changed))
        }
        "remove" | "rm" | "del" | "delete" => {
            if rest.is_empty() {
                bail!("{USAGE}");
            }
            if rest == ["all"] {
                let n = fw.clear()?;
                return Ok((format!("removed {n} forwarded port(s)\n"), n > 0));
            }
            let ports = rest.iter().map(|a| a.trim().parse::<u16>().map_err(|_| anyhow!("not a port: {a:?}"))).collect::<Result<Vec<_>>>()?;
            let mut out = String::new();
            let mut changed = false;
            for p in ports {
                if fw.remove(p)? {
                    changed = true;
                    out += &format!("stopped forwarding port {p}\n");
                } else {
                    out += &format!("port {p} wasn't forwarded\n");
                }
            }
            Ok((out, changed))
        }
        _ => bail!("{USAGE}"),
    }
}

fn list(fw: &Forwards, ip: Option<&str>) -> String {
    if fw.ports().is_empty() {
        return "no forwarded ports (add one: asenascale forward add PORT)\n".into();
    }
    let mut out = String::new();
    for p in fw.ports() {
        match ip {
            Some(ip) if !ip.is_empty() => out += &format!("{ip}:{p} -> 127.0.0.1:{p}\n"),
            _ => out += &format!("tailnet port {p} -> 127.0.0.1:{p}\n"),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("asenascale-fwd-{}-{}", std::process::id(), rand::random::<u32>()));
        dir.join("forwards.json")
    }

    fn args(s: &str) -> Vec<String> {
        s.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn starts_empty_and_persists() {
        let path = temp();
        let mut fw = Forwards::load_from(path.clone());
        assert!(fw.ports().is_empty());
        assert!(fw.add(8765).unwrap());
        assert!(!fw.add(8765).unwrap());
        assert!(fw.add(3000).unwrap());
        assert_eq!(fw.csv(), "3000,8765");
        let again = Forwards::load_from(path.clone());
        assert_eq!(again.ports(), &[3000, 8765]);
        assert!(fw.remove(3000).unwrap());
        assert!(!fw.remove(3000).unwrap());
        assert_eq!(Forwards::load_from(path.clone()).ports(), &[8765]);
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn rejects_bad_and_reserved_ports() {
        assert!(parse_port("0").is_err());
        assert!(parse_port("65536").is_err());
        assert!(parse_port("http").is_err());
        assert!(parse_port(&crate::PORT.to_string()).is_err());
        assert_eq!(parse_port(" 8765 ").unwrap(), 8765);
        let mut fw = Forwards::load_from(temp());
        assert!(fw.add(0).is_err());
        assert!(fw.add(crate::PORT).is_err());
        assert!(fw.ports().is_empty());
    }

    #[test]
    fn caps_the_list() {
        let path = temp();
        let mut fw = Forwards::load_from(path.clone());
        for p in 0..MAX as u16 {
            fw.add(10000 + p).unwrap();
        }
        assert!(fw.add(20000).is_err());
        assert_eq!(fw.ports().len(), MAX);
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn cleans_a_hand_edited_file() {
        let path = temp();
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, format!(r#"{{"ports":[8765,0,{},8765,80]}}"#, crate::PORT)).unwrap();
        assert_eq!(Forwards::load_from(path.clone()).ports(), &[80, 8765]);
        std::fs::write(&path, b"not json").unwrap();
        assert!(Forwards::load_from(path.clone()).ports().is_empty());
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn command_line() {
        let path = temp();
        let mut fw = Forwards::load_from(path.clone());
        let (text, changed) = command(&mut fw, &args("forward"), None).unwrap();
        assert!(!changed && text.contains("no forwarded ports"));
        let (text, changed) = command(&mut fw, &args("forward add 8765 3000"), None).unwrap();
        assert!(changed && text.contains("8765 -> 127.0.0.1:8765"));
        // One bad port: nothing is added.
        assert!(command(&mut fw, &args("forward add 4000 nope"), None).is_err());
        assert_eq!(fw.ports(), &[3000, 8765]);
        let (text, changed) = command(&mut fw, &args("forward add 8765"), None).unwrap();
        assert!(!changed && text.contains("already"));
        let (text, _) = command(&mut fw, &args("forward list"), Some("100.64.0.5")).unwrap();
        assert_eq!(text, "100.64.0.5:3000 -> 127.0.0.1:3000\n100.64.0.5:8765 -> 127.0.0.1:8765\n");
        let (_, changed) = command(&mut fw, &args("forward remove 3000"), None).unwrap();
        assert!(changed);
        let (text, changed) = command(&mut fw, &args("forward rm all"), None).unwrap();
        assert!(changed && text.contains("removed 1"));
        assert!(command(&mut fw, &args("forward add"), None).is_err());
        assert!(command(&mut fw, &args("forward frobnicate"), None).is_err());
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }
}
