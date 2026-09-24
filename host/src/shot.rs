//! `mc list` / `mc shot KIND ID`: screenshots for the phone, in the same
//! line format as the app's shell helpers. This process runs inside the
//! user's desktop session, so it can capture directly.

use std::io::Cursor;

use anyhow::{anyhow, bail, Result};
use xcap::image::codecs::png::{CompressionType, FilterType, PngEncoder};
use xcap::image::{GenericImage, ImageEncoder, RgbaImage};
use xcap::{Monitor, Window};

fn os() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

fn clean(s: &str) -> String {
    s.replace(['\t', '\r', '\n'], " ")
}

/// Windows worth offering: titled, not minimized, and a sane size.
fn listable(w: &Window) -> bool {
    let title = w.title().unwrap_or_default();
    !title.trim().is_empty()
        && !w.is_minimized().unwrap_or(true)
        && w.width().unwrap_or(0) > 40
        && w.height().unwrap_or(0) > 40
}

pub fn list() -> Result<Vec<u8>> {
    let mut out = format!("de\t{0}\t{0}\nscreen\tall\t-\n", os());
    let monitors = Monitor::all()?;
    if monitors.len() > 1 {
        for (i, m) in monitors.iter().enumerate() {
            let name = m.friendly_name().or_else(|_| m.name()).unwrap_or_else(|_| format!("Ekran {}", i + 1));
            out += &format!(
                "output\t{i}\t{}  {}x{}\n",
                clean(&name),
                m.width().unwrap_or(0),
                m.height().unwrap_or(0)
            );
        }
    }
    out += "active\tactive\t-\n";
    for w in Window::all()?.iter().filter(|w| listable(w)) {
        let app = w.app_name().unwrap_or_default();
        out += &format!(
            "window\t{}\t{} - {}\n",
            w.id()?,
            clean(&app),
            clean(&w.title().unwrap_or_default())
        );
    }
    Ok(out.into_bytes())
}

pub fn shot(kind: &str, id: &str) -> Result<Vec<u8>> {
    let img = match kind {
        "screen" => all_screens()?,
        "output" => {
            let i: usize = id.parse()?;
            Monitor::all()?
                .get(i)
                .ok_or_else(|| anyhow!("E_NOWINDOW"))?
                .capture_image()?
        }
        "active" => Window::all()?
            .into_iter()
            .find(|w| w.is_focused().unwrap_or(false) && !w.is_minimized().unwrap_or(true))
            .ok_or_else(|| anyhow!("E_NOWINDOW"))?
            .capture_image()?,
        "window" => {
            let want: u32 = id.parse()?;
            Window::all()?
                .into_iter()
                .find(|w| w.id().map(|i| i == want).unwrap_or(false))
                .ok_or_else(|| anyhow!("E_NOWINDOW"))?
                .capture_image()?
        }
        _ => bail!("unknown kind {kind}"),
    };
    encode(&img)
}

/// Every monitor, laid out left to right in their desktop order.
fn all_screens() -> Result<RgbaImage> {
    let mut monitors = Monitor::all()?;
    if monitors.len() == 1 {
        return Ok(monitors.remove(0).capture_image()?);
    }
    monitors.sort_by_key(|m| (m.x().unwrap_or(0), m.y().unwrap_or(0)));
    let shots: Vec<RgbaImage> = monitors.iter().map(|m| m.capture_image()).collect::<Result<_, _>>()?;
    let width = shots.iter().map(|s| s.width()).sum();
    let height = shots.iter().map(|s| s.height()).max().unwrap_or(0);
    let mut canvas = RgbaImage::new(width, height);
    let mut x = 0;
    for s in &shots {
        canvas.copy_from(s, x, 0)?;
        x += s.width();
    }
    Ok(canvas)
}

/// Fast PNG: a 4K screen encodes in a fraction of the default time.
fn encode(img: &RgbaImage) -> Result<Vec<u8>> {
    let mut buf = Cursor::new(Vec::with_capacity(img.len() / 4));
    PngEncoder::new_with_quality(&mut buf, CompressionType::Fast, FilterType::Sub).write_image(
        img.as_raw(),
        img.width(),
        img.height(),
        xcap::image::ExtendedColorType::Rgba8,
    )?;
    Ok(buf.into_inner())
}
