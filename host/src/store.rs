//! On-disk state: the server's host key and the phones the user approved.

use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use russh::keys::ssh_key::LineEnding;
use russh::keys::{Algorithm, EcdsaCurve, PrivateKey, PublicKey};
use serde::{Deserialize, Serialize};

pub fn dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("AsenaScale")
}

/// ECDSA P-256: the one host key type Android's crypto provider verifies
/// without extra libraries (no Ed25519 there).
pub fn host_key() -> Result<PrivateKey> {
    let path = dir().join("host_ecdsa_key");
    if let Ok(pem) = fs::read_to_string(&path) {
        if let Ok(k) = PrivateKey::from_openssh(&pem) {
            return Ok(k);
        }
    }
    let key = PrivateKey::random(
        &mut rand::rng(),
        Algorithm::Ecdsa { curve: EcdsaCurve::NistP256 },
    )
    .context("generating host key")?;
    fs::create_dir_all(dir())?;
    fs::write(&path, key.to_openssh(LineEnding::LF)?.as_bytes())?;
    Ok(key)
}

/// This PC's own identity when it connects to another AsenaScale PC
/// (`asenascale exec|shot`). The other PC asks once before trusting it.
pub fn client_key() -> Result<PrivateKey> {
    client_key_in(&dir())
}

/// Reads the key in `dir`, or makes one if there is none. An existing file is
/// never replaced: a new key would make every other PC ask to approve this
/// one again, so a broken file is an error for the user to resolve.
fn client_key_in(dir: &std::path::Path) -> Result<PrivateKey> {
    use std::io::{ErrorKind, Write};

    let path = dir.join("client_ed25519_key");
    match read_client_key(&path) {
        Err(e) if e.kind() == ErrorKind::NotFound => {}
        other => return other.map_err(|e| key_error(&path, e)),
    }
    let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).context("generating client key")?;
    let pem = key.to_openssh(LineEnding::LF)?;
    create_private_dir(dir).with_context(|| format!("creating {}", dir.display()))?;
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut file = match opts.open(&path) {
        Ok(f) => f,
        // Another process made it first: use theirs.
        Err(e) if e.kind() == ErrorKind::AlreadyExists => {
            return read_client_key(&path).map_err(|e| key_error(&path, e));
        }
        Err(e) => return Err(e).with_context(|| format!("creating {}", path.display())),
    };
    file.write_all(pem.as_bytes()).with_context(|| format!("writing {}", path.display()))?;
    Ok(key)
}

/// The key at `path`; a file that is not a key reads as `InvalidData`.
fn read_client_key(path: &std::path::Path) -> std::io::Result<PrivateKey> {
    let pem = fs::read_to_string(path)?;
    PrivateKey::from_openssh(&pem).map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
}

fn key_error(path: &std::path::Path, e: std::io::Error) -> anyhow::Error {
    if e.kind() == std::io::ErrorKind::InvalidData {
        anyhow::anyhow!(
            "{} is not a valid key; delete it to make a new one (the other PC will ask to approve this PC again)",
            path.display()
        )
    } else {
        anyhow::anyhow!("reading {}: {e}", path.display())
    }
}

/// `create_dir_all`, with new directories private to the user on unix.
pub fn create_private_dir(dir: &std::path::Path) -> std::io::Result<()> {
    let mut b = fs::DirBuilder::new();
    b.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        b.mode(0o700);
    }
    b.create(dir)
}

#[derive(Serialize, Deserialize, Clone)]
pub struct Device {
    /// OpenSSH public key line of the phone app.
    pub key: String,
    /// What the user saw when approving it (Tailscale device name).
    pub name: String,
    pub added: u64,
}

#[derive(Default)]
pub struct Devices {
    list: Vec<Device>,
}

impl Devices {
    fn path() -> PathBuf {
        dir().join("devices.json")
    }

    pub fn load() -> Devices {
        let list = fs::read(Self::path())
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        Devices { list }
    }

    fn save(&self) {
        let _ = fs::create_dir_all(dir());
        if let Ok(json) = serde_json::to_vec_pretty(&self.list) {
            let _ = fs::write(Self::path(), json);
        }
    }

    /// Tests: these keys only, in memory (never saved over the user's list).
    #[cfg(test)]
    pub fn only(keys: &[PublicKey]) -> Devices {
        let list = keys.iter().map(|k| Device { key: k.to_openssh().unwrap(), name: "test".into(), added: 0 }).collect();
        Devices { list }
    }

    pub fn is_allowed(&self, key: &PublicKey) -> bool {
        self.list.iter().any(|d| {
            PublicKey::from_openssh(&d.key)
                .map(|k| k.key_data() == key.key_data())
                .unwrap_or(false)
        })
    }

    pub fn allow(&mut self, key: &PublicKey, name: &str) {
        if self.is_allowed(key) {
            return;
        }
        let added = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        self.list.push(Device {
            key: key.to_openssh().unwrap_or_default(),
            name: name.to_string(),
            added,
        });
        self.save();
    }

    pub fn clear(&mut self) {
        self.list.clear();
        self.save();
    }

    pub fn len(&self) -> usize {
        self.list.len()
    }

    pub fn list(&self) -> &[Device] {
        &self.list
    }

    /// Clears the list; returns how many there were.
    pub fn clear_count(&mut self) -> usize {
        let n = self.list.len();
        self.clear();
        n
    }

    /// Removes by 1-based number or by (part of) the name; returns how many.
    pub fn remove(&mut self, which: &str) -> usize {
        let before = self.list.len();
        if let Ok(n) = which.parse::<usize>() {
            if n >= 1 && n <= self.list.len() {
                self.list.remove(n - 1);
            }
        } else if !which.is_empty() {
            let w = which.to_lowercase();
            self.list.retain(|d| !d.name.to_lowercase().contains(&w));
        }
        let removed = before - self.list.len();
        if removed > 0 {
            self.save();
        }
        removed
    }
}

/// Tests: a fresh temp dir per test (unique `tag`), removed on drop (also
/// when an assert fails).
#[cfg(test)]
pub struct TempDir(pub PathBuf);

#[cfg(test)]
impl TempDir {
    pub fn new(tag: &str) -> Self {
        let d = std::env::temp_dir().join(format!("asenascale-ck-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        TempDir(d)
    }
}

#[cfg(test)]
impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod client_key_tests {
    use super::*;

    #[test]
    fn client_key_is_ed25519_and_stable() {
        let dir = TempDir::new("stable");
        let a = client_key_in(&dir.0).unwrap();
        let b = client_key_in(&dir.0).unwrap();
        assert_eq!(a.algorithm(), Algorithm::Ed25519);
        assert_eq!(a.public_key(), b.public_key());
    }

    #[cfg(unix)]
    #[test]
    fn key_file_is_private() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TempDir::new("mode");
        client_key_in(&dir.0).unwrap();
        let mode = fs::metadata(dir.0.join("client_ed25519_key")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
    }

    #[test]
    fn corrupt_key_file_is_an_error_not_replaced() {
        let dir = TempDir::new("corrupt");
        fs::create_dir_all(&dir.0).unwrap();
        let path = dir.0.join("client_ed25519_key");
        fs::write(&path, "garbage").unwrap();
        let err = client_key_in(&dir.0).unwrap_err();
        assert!(err.to_string().contains("client_ed25519_key"), "{err}");
        assert_eq!(fs::read_to_string(&path).unwrap(), "garbage");
    }
}
