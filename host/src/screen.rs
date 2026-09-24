//! `mc screen [MONITOR]`: live screen sharing for the phone, light on data.
//!
//! The screen is captured, scaled to at most `max_w` pixels wide, cut into
//! 64 px tiles and compared with the previous frame; only changed tiles go
//! out, as JPEG (runs of neighbouring changed tiles share one image). A
//! static screen costs a capture every half second and zero bytes. The
//! phone sends mouse and keyboard input back as text lines on stdin.
//!
//! PC -> phone (big-endian):
//!   'H' monitor:u8 monitors:u8 width:u16 height:u16   frame size changed
//!   'T' x:u16 y:u16 w:u16 h:u16 len:u32 jpeg[len]      a changed region
//!   'F'                                                 end of one frame
//! phone -> PC, one command per line, coordinates in frame pixels:
//!   m X Y | c X Y BUTTON COUNT | d X Y | u X Y | w LINES | h COLUMNS
//!   t BASE64TEXT | k KEY[+KEY...] | o MONITOR | q QUALITY MAXWIDTH | r

use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

use anyhow::{anyhow, Result};
use image::codecs::jpeg::JpegEncoder;
use image::imageops::FilterType;
use image::{ExtendedColorType, RgbaImage};
use russh::server::Handle;
use russh::ChannelId;
use xcap::Monitor;

const TILE: u32 = 64;

struct Streamer {
    monitor: usize,
    quality: u8,
    max_w: u32,
    /// Last frame sent (scaled), to find what changed.
    prev: Option<RgbaImage>,
    /// Full-resolution size and desktop position of the monitor, for input.
    full: (u32, u32),
    origin: (i32, i32),
}

impl Streamer {
    fn capture(&mut self) -> Result<(RgbaImage, usize)> {
        let monitors = Monitor::all()?;
        if monitors.is_empty() {
            return Err(anyhow!("no monitor"));
        }
        self.monitor = self.monitor.min(monitors.len() - 1);
        let m = &monitors[self.monitor];
        let img = m.capture_image()?;
        self.full = (img.width(), img.height());
        self.origin = (m.x().unwrap_or(0), m.y().unwrap_or(0));
        let img = if img.width() > self.max_w {
            let h = (img.height() as u64 * self.max_w as u64 / img.width() as u64) as u32;
            image::imageops::resize(&img, self.max_w, h.max(1), FilterType::Triangle)
        } else {
            img
        };
        Ok((img, monitors.len()))
    }

    /// The bytes for one frame; empty when nothing changed.
    fn frame(&mut self) -> Result<Vec<u8>> {
        let (img, count) = self.capture()?;
        let (w, h) = img.dimensions();
        let mut out = Vec::new();
        let fresh = self.prev.as_ref().is_none_or(|p| p.dimensions() != (w, h));
        if fresh {
            out.push(b'H');
            out.push(self.monitor as u8);
            out.push(count as u8);
            out.extend_from_slice(&(w as u16).to_be_bytes());
            out.extend_from_slice(&(h as u16).to_be_bytes());
        }
        let cols = w.div_ceil(TILE);
        let rows = h.div_ceil(TILE);
        for ty in 0..rows {
            let y = ty * TILE;
            let th = TILE.min(h - y);
            let mut run: Option<u32> = None; // first changed tile column of the current run
            for tx in 0..=cols {
                let changed = tx < cols && (fresh || self.tile_changed(&img, tx * TILE, y, TILE.min(w - tx * TILE), th));
                match (changed, run) {
                    (true, None) => run = Some(tx),
                    (false, Some(start)) => {
                        let x = start * TILE;
                        let rw = (tx * TILE).min(w) - x;
                        self.push_tile(&mut out, &img, x, y, rw, th)?;
                        run = None;
                    }
                    _ => {}
                }
            }
        }
        self.prev = Some(img);
        if out.is_empty() {
            return Ok(out);
        }
        out.push(b'F');
        Ok(out)
    }

    fn tile_changed(&self, img: &RgbaImage, x: u32, y: u32, w: u32, h: u32) -> bool {
        let Some(prev) = &self.prev else { return true };
        let stride = img.width() as usize * 4;
        let (a, b) = (img.as_raw(), prev.as_raw());
        (y..y + h).any(|row| {
            let start = row as usize * stride + x as usize * 4;
            let end = start + w as usize * 4;
            a[start..end] != b[start..end]
        })
    }

    fn push_tile(&self, out: &mut Vec<u8>, img: &RgbaImage, x: u32, y: u32, w: u32, h: u32) -> Result<()> {
        let mut rgb = Vec::with_capacity((w * h * 3) as usize);
        for row in y..y + h {
            for col in x..x + w {
                let p = img.get_pixel(col, row).0;
                rgb.extend_from_slice(&p[..3]);
            }
        }
        let mut jpeg = Vec::new();
        JpegEncoder::new_with_quality(&mut jpeg, self.quality).encode(&rgb, w, h, ExtendedColorType::Rgb8)?;
        out.push(b'T');
        for v in [x, y, w, h] {
            out.extend_from_slice(&(v as u16).to_be_bytes());
        }
        out.extend_from_slice(&(jpeg.len() as u32).to_be_bytes());
        out.extend_from_slice(&jpeg);
        Ok(())
    }

    /// Frame pixel -> desktop pixel.
    fn to_desktop(&self, x: f64, y: f64) -> (i32, i32) {
        let (fw, fh) = self.prev.as_ref().map(|p| p.dimensions()).unwrap_or(self.full);
        let sx = self.full.0 as f64 / fw.max(1) as f64;
        let sy = self.full.1 as f64 / fh.max(1) as f64;
        (self.origin.0 + (x * sx).round() as i32, self.origin.1 + (y * sy).round() as i32)
    }
}

/// Streams until the phone goes away. Runs on its own thread.
pub fn run(handle: Handle, channel: ChannelId, rt: tokio::runtime::Handle, monitor: usize, input: Receiver<Vec<u8>>) {
    let mut s = Streamer { monitor, quality: 55, max_w: 1280, prev: None, full: (0, 0), origin: (0, 0) };
    let mut control = Control::new();
    let mut line = Vec::new();
    let mut wait = Duration::ZERO;
    let mut quiet = 0u32;
    loop {
        // Input first (it wakes us early): a click wants a fresh frame soon.
        let mut poked = false;
        match input.recv_timeout(wait) {
            Ok(bytes) => {
                for b in bytes {
                    if b == b'\n' {
                        control.apply(&mut s, &String::from_utf8_lossy(&line));
                        line.clear();
                        poked = true;
                    } else if line.len() < 64 * 1024 {
                        line.push(b);
                    }
                }
                while let Ok(more) = input.try_recv() {
                    for b in more {
                        if b == b'\n' {
                            control.apply(&mut s, &String::from_utf8_lossy(&line));
                            line.clear();
                        } else if line.len() < 64 * 1024 {
                            line.push(b);
                        }
                    }
                }
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => return,
        }
        if poked {
            // Let the PC react to the click before looking.
            std::thread::sleep(Duration::from_millis(60));
        }
        let t0 = Instant::now();
        match s.frame() {
            Ok(bytes) if bytes.is_empty() => quiet = quiet.saturating_add(1),
            Ok(bytes) => {
                quiet = 0;
                if rt.block_on(handle.data(channel, bytes)).is_err() {
                    return;
                }
            }
            Err(e) => {
                log::warn!("screen capture: {e:#}");
                quiet = quiet.saturating_add(4);
            }
        }
        // ~8 fps while things move, easing off to 2 fps when still.
        let spent = t0.elapsed();
        let target = Duration::from_millis(match quiet {
            0..=2 => 120,
            3..=10 => 250,
            _ => 500,
        });
        wait = target.saturating_sub(spent);
    }
}

struct Control {
    enigo: Option<enigo::Enigo>,
    tried: bool,
}

impl Control {
    fn new() -> Self {
        Control { enigo: None, tried: false }
    }

    fn enigo(&mut self) -> Option<&mut enigo::Enigo> {
        if !self.tried {
            self.tried = true;
            match enigo::Enigo::new(&enigo::Settings::default()) {
                Ok(e) => self.enigo = Some(e),
                Err(e) => log::warn!("input unavailable: {e}"),
            }
        }
        self.enigo.as_mut()
    }

    fn apply(&mut self, s: &mut Streamer, line: &str) {
        use enigo::{Axis, Button, Coordinate, Direction, Keyboard, Mouse};
        let parts: Vec<&str> = line.split_whitespace().collect();
        let num = |i: usize| parts.get(i).and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
        match parts.first().copied() {
            Some("o") => {
                s.monitor = num(1) as usize;
                s.prev = None;
            }
            Some("q") => {
                s.quality = (num(1) as u8).clamp(20, 90);
                s.max_w = (num(2) as u32).clamp(480, 3840);
                s.prev = None;
            }
            Some("r") => s.prev = None,
            Some(cmd @ ("m" | "c" | "d" | "u")) => {
                let (x, y) = s.to_desktop(num(1), num(2));
                let button = match parts.get(3).copied() {
                    Some("r") => Button::Right,
                    Some("m") => Button::Middle,
                    _ => Button::Left,
                };
                let count = (num(4) as u32).clamp(1, 3);
                let Some(e) = self.enigo() else { return };
                let _ = e.move_mouse(x, y, Coordinate::Abs);
                match cmd {
                    "c" => {
                        for _ in 0..count {
                            let _ = e.button(button, Direction::Click);
                        }
                    }
                    "d" => {
                        let _ = e.button(Button::Left, Direction::Press);
                    }
                    "u" => {
                        let _ = e.button(Button::Left, Direction::Release);
                    }
                    _ => {}
                }
            }
            Some("w") => {
                if let Some(e) = self.enigo() {
                    let _ = e.scroll(num(1) as i32, Axis::Vertical);
                }
            }
            Some("h") => {
                if let Some(e) = self.enigo() {
                    let _ = e.scroll(num(1) as i32, Axis::Horizontal);
                }
            }
            Some("t") => {
                let text = parts.get(1).and_then(|b| crate::files::decode_path(b).ok());
                if let (Some(text), Some(e)) = (text, self.enigo()) {
                    let _ = e.text(&text.to_string_lossy());
                }
            }
            Some("k") => {
                let Some(combo) = parts.get(1) else { return };
                let keys: Vec<enigo::Key> = combo.split('+').filter_map(key).collect();
                let Some(e) = self.enigo() else { return };
                if let Some((last, mods)) = keys.split_last() {
                    for m in mods {
                        let _ = e.key(*m, Direction::Press);
                    }
                    let _ = e.key(*last, Direction::Click);
                    for m in mods.iter().rev() {
                        let _ = e.key(*m, Direction::Release);
                    }
                }
            }
            _ => {}
        }
    }
}

fn key(name: &str) -> Option<enigo::Key> {
    use enigo::Key as K;
    Some(match name {
        "enter" => K::Return,
        "backspace" => K::Backspace,
        "tab" => K::Tab,
        "esc" => K::Escape,
        "space" => K::Space,
        "up" => K::UpArrow,
        "down" => K::DownArrow,
        "left" => K::LeftArrow,
        "right" => K::RightArrow,
        "home" => K::Home,
        "end" => K::End,
        "pgup" => K::PageUp,
        "pgdn" => K::PageDown,
        "del" => K::Delete,
        "ctrl" => K::Control,
        "shift" => K::Shift,
        "alt" => K::Alt,
        "win" | "meta" => K::Meta,
        "f5" => K::F5,
        s if s.chars().count() == 1 => K::Unicode(s.chars().next()?.to_ascii_lowercase()),
        _ => return None,
    })
}
