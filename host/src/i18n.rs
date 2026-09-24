//! Tray and dialog texts in the user's language (English otherwise).
//! The table is generated from i18n/desktop/*.json by i18n/gen.py.

use std::sync::OnceLock;

#[path = "i18n_table.rs"]
mod table;

fn lang() -> usize {
    static LANG: OnceLock<usize> = OnceLock::new();
    *LANG.get_or_init(|| {
        let find = |code: &str| table::LANGS.iter().position(|(l, _)| *l == code);
        system_languages().iter().find_map(|tag| find(&normalize(tag))).unwrap_or(0)
    })
}

/// "zh_TW.UTF-8", "zh-Hant-HK", "pt-BR", "tr-TR" -> "zh-TW", "zh-TW", "pt", "tr".
fn normalize(tag: &str) -> String {
    let tag = tag.split(['.', '@']).next().unwrap_or("").replace('_', "-");
    let mut parts = tag.split('-');
    let lang = parts.next().unwrap_or("").to_ascii_lowercase();
    let rest: Vec<String> = parts.map(|p| p.to_ascii_lowercase()).collect();
    match lang.as_str() {
        "zh" if rest.iter().any(|p| p == "hant" || p == "tw" || p == "hk" || p == "mo") => "zh-TW".into(),
        "zh" => "zh-CN".into(),
        "in" => "id".into(),
        _ => lang,
    }
}

#[cfg(windows)]
fn system_languages() -> Vec<String> {
    use windows_sys::Win32::Globalization::{GetUserDefaultUILanguage, LCIDToLocaleName};
    let mut buf = [0u16; 85];
    let n = unsafe { LCIDToLocaleName(GetUserDefaultUILanguage() as u32, buf.as_mut_ptr(), buf.len() as i32, 0) };
    if n > 1 {
        vec![String::from_utf16_lossy(&buf[..n as usize - 1])]
    } else {
        Vec::new()
    }
}

#[cfg(not(windows))]
fn system_languages() -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(list) = std::env::var("LANGUAGE") {
        out.extend(list.split(':').filter(|s| !s.is_empty()).map(String::from));
    }
    for var in ["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Ok(v) = std::env::var(var) {
            if !v.is_empty() && v != "C" && v != "POSIX" && !v.starts_with("C.") {
                out.push(v);
            }
        }
    }
    out
}

/// The text for `key`.
pub fn t(key: &str) -> &'static str {
    let i = table::KEYS.iter().position(|k| *k == key).unwrap_or_else(|| panic!("no text {key}"));
    let text = table::LANGS[lang()].1[i];
    if text.is_empty() { table::LANGS[0].1[i] } else { text }
}

/// The text for `key` with `{name}` placeholders filled in.
pub fn tf(key: &str, args: &[(&str, &dyn std::fmt::Display)]) -> String {
    let mut s = t(key).to_string();
    for (name, value) in args {
        s = s.replace(&format!("{{{name}}}"), &value.to_string());
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tags() {
        assert_eq!(normalize("tr_TR.UTF-8"), "tr");
        assert_eq!(normalize("zh-Hant-HK"), "zh-TW");
        assert_eq!(normalize("zh_CN"), "zh-CN");
        assert_eq!(normalize("pt-BR"), "pt");
    }

    #[test]
    fn complete() {
        for (lang, texts) in table::LANGS {
            assert_eq!(texts.len(), table::KEYS.len(), "{lang}");
        }
        assert!(tf("sessions_phones", &[("n", &2), ("a", &1)]).contains('2'));
    }
}
