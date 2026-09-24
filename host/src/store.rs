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
}
