//! Host keys of other AsenaScale PCs, pinned the first time this PC connects
//! to each (`asenascale exec|windows|shot`): a later connection that shows a
//! different key is refused until the user runs `asenascale forget IP`.

use std::collections::BTreeMap;
use std::fs;
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};

use anyhow::{anyhow, Context, Result};
use russh::keys::PublicKey;

/// What `check` found for a peer.
#[derive(Debug, PartialEq, Eq)]
pub enum Seen {
    /// First contact; the key is now remembered.
    New,
    Same,
    Changed,
}

/// `known_peers.json` in a directory: peer identity (its tailnet IP) to
/// OpenSSH public key line.
#[derive(Clone)]
pub struct Known {
    dir: PathBuf,
}

impl Known {
    /// The user's list, next to the other AsenaScale state.
    pub fn open() -> Known {
        Known { dir: crate::store::dir() }
    }

    #[cfg(test)]
    pub fn in_dir(dir: &Path) -> Known {
        Known { dir: dir.to_path_buf() }
    }

    /// Compares `key` with the one remembered for `who`, remembering it if
    /// there is none yet.
    pub fn check(&self, who: &str, key: &PublicKey) -> Result<Seen> {
        let mut map = self.load()?;
        if let Some(line) = map.get(who) {
            let same = PublicKey::from_openssh(line).is_ok_and(|k| k.key_data() == key.key_data());
            return Ok(if same { Seen::Same } else { Seen::Changed });
        }
        map.insert(who.to_string(), key.to_openssh().context("encoding the other PC's key")?);
        self.save(&map)?;
        Ok(Seen::New)
    }

    /// Drops what is remembered for `who` (or everything for "all"); returns
    /// how many entries went.
    pub fn forget(&self, who: &str) -> Result<usize> {
        let mut map = self.load()?;
        let n = if who == "all" {
            std::mem::take(&mut map).len()
        } else {
            usize::from(map.remove(who).is_some())
        };
        if n > 0 {
            self.save(&map)?;
        }
        Ok(n)
    }

    fn path(&self) -> PathBuf {
        self.dir.join(FILE)
    }

    fn load(&self) -> Result<BTreeMap<String, String>> {
        let path = self.path();
        match fs::read(&path) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(|_| {
                anyhow!(
                    "{} is damaged; delete it (this PC then learns the other PCs' identities again)",
                    path.display()
                )
            }),
            Err(e) if e.kind() == ErrorKind::NotFound => Ok(BTreeMap::new()),
            Err(e) => Err(e).with_context(|| format!("reading {}", path.display())),
        }
    }

    /// Writes a temp file next to the list and renames it over, so a crash
    /// never leaves half a file.
    fn save(&self, map: &BTreeMap<String, String>) -> Result<()> {
        let path = self.path();
        let json = serde_json::to_vec_pretty(map)?;
        crate::store::create_private_dir(&self.dir).with_context(|| format!("creating {}", self.dir.display()))?;
        let tmp = self.dir.join(format!("{FILE}.{}.tmp", std::process::id()));
        let written = write_private(&tmp, &json).and_then(|()| fs::rename(&tmp, &path));
        if written.is_err() {
            let _ = fs::remove_file(&tmp);
        }
        written.with_context(|| format!("writing {}", path.display()))
    }
}

const FILE: &str = "known_peers.json";

/// Creates (or truncates) `path` readable by the user only (unix) and
/// writes `data` to disk.
fn write_private(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut file = opts.open(path)?;
    file.write_all(data)?;
    file.sync_all()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::TempDir;
    use russh::keys::{Algorithm, PrivateKey};

    fn key() -> PublicKey {
        PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap().public_key().clone()
    }

    #[test]
    fn first_key_is_remembered_and_then_matches() {
        let dir = TempDir::new("known-same");
        let k = key();
        assert_eq!(Known::in_dir(&dir.0).check("100.64.0.1", &k).unwrap(), Seen::New);
        assert_eq!(Known::in_dir(&dir.0).check("100.64.0.1", &k).unwrap(), Seen::Same);
    }

    #[test]
    fn a_different_key_is_reported_and_not_saved() {
        let dir = TempDir::new("known-changed");
        let known = Known::in_dir(&dir.0);
        let (a, b) = (key(), key());
        known.check("100.64.0.1", &a).unwrap();
        assert_eq!(known.check("100.64.0.1", &b).unwrap(), Seen::Changed);
        assert_eq!(known.check("100.64.0.1", &a).unwrap(), Seen::Same);
        // Another peer is independent.
        assert_eq!(known.check("100.64.0.2", &b).unwrap(), Seen::New);
    }

    #[test]
    fn forget_removes_one_or_all() {
        let dir = TempDir::new("known-forget");
        let known = Known::in_dir(&dir.0);
        let (a, b) = (key(), key());
        known.check("100.64.0.1", &a).unwrap();
        known.check("100.64.0.2", &a).unwrap();
        known.check("100.64.0.3", &a).unwrap();
        assert_eq!(known.forget("100.64.0.1").unwrap(), 1);
        assert_eq!(known.forget("100.64.0.1").unwrap(), 0);
        assert_eq!(known.check("100.64.0.1", &b).unwrap(), Seen::New);
        assert_eq!(known.forget("all").unwrap(), 3);
        assert_eq!(known.check("100.64.0.2", &b).unwrap(), Seen::New);
    }

    #[test]
    fn a_damaged_file_is_an_error() {
        let dir = TempDir::new("known-damaged");
        std::fs::create_dir_all(&dir.0).unwrap();
        std::fs::write(dir.0.join("known_peers.json"), "not json").unwrap();
        let err = Known::in_dir(&dir.0).check("100.64.0.1", &key()).unwrap_err();
        assert!(err.to_string().contains("known_peers.json"), "{err}");
    }

    #[cfg(unix)]
    #[test]
    fn the_file_is_private_and_no_temp_file_is_left() {
        use std::os::unix::fs::PermissionsExt;
        let dir = TempDir::new("known-mode");
        Known::in_dir(&dir.0).check("100.64.0.1", &key()).unwrap();
        let mode = std::fs::metadata(dir.0.join("known_peers.json")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
        let names: Vec<_> = std::fs::read_dir(&dir.0).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(names, ["known_peers.json"]);
    }
}
