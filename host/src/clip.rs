use arboard::{Clipboard, ImageData};
use std::borrow::Cow;

pub fn read() -> anyhow::Result<Vec<u8>> {
    let mut cb = Clipboard::new()?;
    if let Ok(text) = cb.get_text() {
        let mut res = b"text\n".to_vec();
        res.extend_from_slice(text.as_bytes());
        return Ok(res);
    }
    
    if let Ok(image) = cb.get_image() {
        let mut buf = Vec::new();
        let img = image::RgbaImage::from_raw(
            image.width as u32,
            image.height as u32,
            image.bytes.into_owned(),
        )
        .ok_or_else(|| anyhow::anyhow!("invalid image data"))?;
        
        let mut cursor = std::io::Cursor::new(&mut buf);
        img.write_to(&mut cursor, image::ImageFormat::Png)?;
        
        let mut res = b"image\n".to_vec();
        res.extend_from_slice(&buf);
        return Ok(res);
    }
    
    Ok(Vec::new())
}

pub fn write_text(text: &str) -> anyhow::Result<()> {
    let mut cb = Clipboard::new()?;
    cb.set_text(text)?;
    Ok(())
}

pub fn write_image(png_bytes: &[u8]) -> anyhow::Result<()> {
    let img = image::load_from_memory(png_bytes)?.into_rgba8();
    let img_data = ImageData {
        width: img.width() as usize,
        height: img.height() as usize,
        bytes: Cow::Owned(img.into_raw()),
    };
    let mut cb = Clipboard::new()?;
    cb.set_image(img_data)?;
    Ok(())
}
