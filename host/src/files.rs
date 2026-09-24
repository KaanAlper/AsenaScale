//! `mc put`: files sent from the phone land in Downloads/AsenaScale.

use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};

/// Big enough for photos and screen recordings, small enough to hold in RAM.
pub const MAX_BYTES: usize = 512 * 1024 * 1024;

pub fn dir() -> PathBuf {
    dirs::download_dir()
        .or_else(|| dirs::home_dir().map(|h| h.join("Downloads")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("AsenaScale")
}

/// base64url (no padding) of a UTF-8 file name; anything odd becomes "dosya".
pub fn decode_name(encoded: &str) -> String {
    let raw = decode_b64url(encoded).and_then(|b| String::from_utf8(b).ok()).unwrap_or_default();
    sanitize(&raw)
}

/// Keeps a plain file name: no directories, no characters Windows rejects.
fn sanitize(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = base
        .chars()
        .map(|c| if c.is_control() || "<>:\"|?*".contains(c) { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_matches('.').to_string();
    if cleaned.is_empty() { "dosya".to_string() } else { cleaned }
}

/// Writes the file without overwriting an existing one; returns its full path.
pub fn save(name: &str, data: &[u8]) -> Result<String> {
    let dir = dir();
    fs::create_dir_all(&dir).with_context(|| format!("{}", dir.display()))?;
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    let mut path = dir.join(name);
    let mut n = 1;
    while path.exists() {
        path = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    fs::write(&path, data).with_context(|| format!("{}", path.display()))?;
    log::info!("received {} ({} bytes)", path.display(), data.len());
    Ok(path.display().to_string())
}

fn decode_b64url(s: &str) -> Option<Vec<u8>> {
    let val = |c: u8| -> Option<u32> {
        Some(match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'-' | b'+' => 62,
            b'_' | b'/' => 63,
            _ => return None,
        } as u32)
    };
    let bytes: Vec<u8> = s.bytes().filter(|&c| c != b'=').collect();
    let mut out = Vec::with_capacity(bytes.len() * 3 / 4);
    for chunk in bytes.chunks(4) {
        let mut acc = 0u32;
        for (i, &c) in chunk.iter().enumerate() {
            acc |= val(c)? << (18 - 6 * i);
        }
        let n = chunk.len();
        if n < 2 {
            return None;
        }
        out.push((acc >> 16) as u8);
        if n > 2 {
            out.push((acc >> 8) as u8);
        }
        if n > 3 {
            out.push(acc as u8);
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names() {
        // "ekran görüntüsü.png" base64url-encoded, no padding.
        assert_eq!(decode_name("ZWtyYW4gZ8O2csO8bnTDvHPDvC5wbmc"), "ekran görüntüsü.png");
        assert_eq!(sanitize("../../etc/passwd"), "passwd");
        assert_eq!(sanitize("C:\\Windows\\a:b?.txt"), "a_b_.txt");
        assert_eq!(sanitize("..."), "dosya");
        assert_eq!(decode_name("!!!"), "dosya");
    }
}
