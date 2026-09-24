//! Files between the phone and the PC.
//!
//! - `mc put NAME`: a whole file from the phone lands in Downloads/AsenaScale.
//! - The file browser: `mc ls`, `mc read` (a byte range), and for chunked
//!   parallel uploads `mc prepare` / `mc write` (a byte range) / `mc done`.
//!   Paths travel base64url-encoded so any name survives the command line.

use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

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

/// A path in `dir` named like `name` that doesn't exist yet: "a.png", "a (1).png", ...
fn unique(dir: &Path, name: &str) -> PathBuf {
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    let mut path = dir.join(name);
    let mut n = 1;
    while path.exists() || part(&path).exists() {
        path = dir.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    path
}

/// Writes the file without overwriting an existing one; returns its full path.
pub fn save(name: &str, data: &[u8]) -> Result<String> {
    let dir = dir();
    fs::create_dir_all(&dir).with_context(|| format!("{}", dir.display()))?;
    let path = unique(&dir, name);
    fs::write(&path, data).with_context(|| format!("{}", path.display()))?;
    log::info!("received {} ({} bytes)", path.display(), data.len());
    Ok(path.display().to_string())
}

/// Largest byte range one `mc read` / `mc write` moves.
pub const MAX_CHUNK: usize = 16 * 1024 * 1024;

/// A base64url path argument.
pub fn decode_path(encoded: &str) -> Result<PathBuf> {
    let raw = decode_b64url(encoded).and_then(|b| String::from_utf8(b).ok()).context("bad path")?;
    Ok(PathBuf::from(raw))
}

fn arg<'a>(args: &'a [String], i: usize) -> Result<&'a str> {
    args.get(i).map(String::as_str).context("missing argument")
}

/// `mc ls PATH`. First line "path<TAB>dir", then one line per entry:
/// "d|f<TAB>size<TAB>mtime<TAB>name". An empty PATH lists starting places
/// (home, Desktop, Downloads, drives), with full paths as names.
pub fn list(args: &[String]) -> Result<Vec<u8>> {
    let enc = args.get(1).map(String::as_str).unwrap_or("");
    let mut out = String::new();
    if enc.is_empty() || enc == "-" {
        out.push_str("path\t\n");
        for p in places() {
            out.push_str(&format!("d\t0\t0\t{}\n", p.display()));
        }
        return Ok(out.into_bytes());
    }
    let dir = decode_path(enc)?;
    let dir = fs::canonicalize(&dir).with_context(|| format!("{}", dir.display()))?;
    out.push_str(&format!("path\t{}\n", display(&dir)));
    let mut entries: Vec<(bool, u64, u64, String)> = Vec::new();
    for e in fs::read_dir(&dir).with_context(|| format!("{}", dir.display()))?.flatten() {
        let name = e.file_name().to_string_lossy().replace(['\t', '\n'], " ");
        // Follows links, so a link to a folder opens like a folder.
        let Ok(meta) = fs::metadata(e.path()) else { continue };
        if hidden(&name, &meta) {
            continue;
        }
        let mtime = meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
        entries.push((meta.is_dir(), if meta.is_dir() { 0 } else { meta.len() }, mtime, name));
    }
    entries.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.3.to_lowercase().cmp(&b.3.to_lowercase())));
    for (is_dir, size, mtime, name) in entries {
        out.push_str(&format!("{}\t{size}\t{mtime}\t{name}\n", if is_dir { 'd' } else { 'f' }));
    }
    Ok(out.into_bytes())
}

/// Paths as the user knows them (no \\?\ prefix on Windows).
fn display(p: &Path) -> String {
    let s = p.display().to_string();
    s.strip_prefix(r"\\?\").map(str::to_string).unwrap_or(s)
}

fn hidden(name: &str, _meta: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const HIDDEN: u32 = 0x2;
        const SYSTEM: u32 = 0x4;
        if _meta.file_attributes() & (HIDDEN | SYSTEM) != 0 {
            return true;
        }
    }
    name.starts_with('.') || name.ends_with(PART_EXT)
}

fn places() -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = [dirs::home_dir(), dirs::desktop_dir(), dirs::download_dir(), dirs::document_dir(), dirs::picture_dir()]
        .into_iter()
        .flatten()
        .collect();
    let received = dir();
    if received.is_dir() {
        v.push(received);
    }
    #[cfg(windows)]
    for letter in b'A'..=b'Z' {
        let root = PathBuf::from(format!("{}:\\", letter as char));
        if root.is_dir() {
            v.push(root);
        }
    }
    #[cfg(not(windows))]
    v.push(PathBuf::from("/"));
    v.dedup();
    v
}

/// `mc read PATH OFFSET LEN`: that byte range of the file (shorter at the end).
pub fn read(args: &[String]) -> Result<Vec<u8>> {
    let path = decode_path(arg(args, 1)?)?;
    let offset: u64 = arg(args, 2)?.parse()?;
    let len: usize = arg(args, 3)?.parse::<usize>()?.min(MAX_CHUNK);
    let mut f = fs::File::open(&path).with_context(|| format!("{}", path.display()))?;
    f.seek(SeekFrom::Start(offset))?;
    let mut buf = Vec::with_capacity(len);
    f.take(len as u64).read_to_end(&mut buf)?;
    Ok(buf)
}

const PART_EXT: &str = ".aspart";

fn part(path: &Path) -> PathBuf {
    let mut s = path.as_os_str().to_owned();
    s.push(PART_EXT);
    PathBuf::from(s)
}

/// `mc prepare DIR NAME SIZE`: reserves a free name in DIR (empty = the
/// received-files folder) and a SIZE-byte partial file; prints the path.
pub fn prepare(args: &[String]) -> Result<Vec<u8>> {
    let enc_dir = arg(args, 1)?;
    let dir = if enc_dir == "-" { dir() } else { decode_path(enc_dir)? };
    let name = decode_name(arg(args, 2)?);
    let size: u64 = arg(args, 3)?.parse()?;
    fs::create_dir_all(&dir).with_context(|| format!("{}", dir.display()))?;
    let path = unique(&dir, &name);
    let f = fs::File::create(part(&path)).with_context(|| format!("{}", path.display()))?;
    f.set_len(size)?;
    Ok(format!("{}\n", display(&path)).into_bytes())
}

/// `mc write PATH OFFSET` (bytes on stdin): fills that range of the partial file.
pub fn write_at(path: &Path, offset: u64, data: &[u8]) -> Result<()> {
    let mut f = fs::OpenOptions::new().write(true).open(part(path)).with_context(|| format!("{}", path.display()))?;
    f.seek(SeekFrom::Start(offset))?;
    f.write_all(data)?;
    Ok(())
}

/// `mc done PATH`: the upload is complete; the partial file takes its name.
pub fn done(args: &[String]) -> Result<Vec<u8>> {
    let path = decode_path(arg(args, 1)?)?;
    fs::rename(part(&path), &path).with_context(|| format!("{}", path.display()))?;
    log::info!("received {}", path.display());
    Ok(format!("{}\n", display(&path)).into_bytes())
}

/// `mc abort PATH`: drops a partial upload.
pub fn abort(args: &[String]) -> Result<Vec<u8>> {
    let path = decode_path(arg(args, 1)?)?;
    let _ = fs::remove_file(part(&path));
    Ok(Vec::new())
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

    fn b64(s: &str) -> String {
        const A: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let b = s.as_bytes();
        let mut out = String::new();
        for c in b.chunks(3) {
            let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
            for i in 0..=c.len() {
                out.push(A[(n >> (18 - 6 * i) & 63) as usize] as char);
            }
        }
        out
    }

    #[test]
    fn chunked_upload_and_ranges() {
        let dir = std::env::temp_dir().join(format!("as-files-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let args = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        let d = b64(dir.to_str().unwrap());
        let out = prepare(&args(&["prepare", &d, &b64("çok parça.bin"), "10"])).unwrap();
        let path = PathBuf::from(String::from_utf8(out).unwrap().trim());
        assert!(path.ends_with("çok parça.bin"));
        // Out of order, like parallel workers.
        write_at(&path, 5, b"56789").unwrap();
        write_at(&path, 0, b"01234").unwrap();
        let listing = String::from_utf8(list(&args(&["ls", &d])).unwrap()).unwrap();
        assert!(!listing.contains("çok parça"), "partial files are hidden");
        done(&args(&["done", &b64(path.to_str().unwrap())])).unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"0123456789");
        let p = b64(path.to_str().unwrap());
        assert_eq!(read(&args(&["read", &p, "3", "4"])).unwrap(), b"3456");
        assert_eq!(read(&args(&["read", &p, "8", "100"])).unwrap(), b"89");
        let listing = String::from_utf8(list(&args(&["ls", &d])).unwrap()).unwrap();
        assert!(listing.contains("f\t10\t"), "{listing}");
        // Second upload with the same name gets its own.
        let out = prepare(&args(&["prepare", &d, &b64("çok parça.bin"), "1"])).unwrap();
        assert!(String::from_utf8(out).unwrap().contains("(1)"));
        fs::remove_dir_all(&dir).unwrap();
    }
}
