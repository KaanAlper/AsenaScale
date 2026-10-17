//! The embedded Tailscale node (Go tsnet, linked in by build.rs). This PC
//! shows up on the tailnet as its own device; no Tailscale install needed.

use std::ffi::{c_char, c_int, CStr, CString};

use anyhow::{anyhow, Result};
use serde::Deserialize;

extern "C" {
    fn AsStart(data_dir: *const c_char, hostname: *const c_char) -> *mut c_char;
    fn AsServe(tailnet_port: c_int, local_port: c_int) -> *mut c_char;
    fn AsStatus() -> *mut c_char;
    fn AsPeer(src_port: c_int) -> *mut c_char;
    fn AsLogin() -> *mut c_char;
    fn AsLogout() -> *mut c_char;
    fn AsPing(ip: *const c_char) -> c_int;
    fn AsSetForwards(ports: *const c_char) -> *mut c_char;
    fn AsForward(target: *const c_char, port: *mut c_int) -> *mut c_char;
    fn AsCloseForward(port: c_int);
    fn AsSetLogger(f: extern "C" fn(*const c_char));
    fn AsStop();
    fn AsFree(p: *mut c_char);
}

/// Takes ownership of a Go-allocated C string.
fn take(p: *mut c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    let s = unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned();
    unsafe { AsFree(p) };
    Some(s)
}

fn check(p: *mut c_char) -> Result<()> {
    match take(p) {
        Some(e) => Err(anyhow!(e)),
        None => Ok(()),
    }
}

pub fn start(data_dir: &str, hostname: &str) -> Result<()> {
    let (d, h) = (CString::new(data_dir)?, CString::new(hostname)?);
    check(unsafe { AsStart(d.as_ptr(), h.as_ptr()) })
}

/// Forwards connections on this node's `tailnet_port` to 127.0.0.1:`local_port`.
pub fn serve(tailnet_port: u16, local_port: u16) -> Result<()> {
    check(unsafe { AsServe(tailnet_port as c_int, local_port as c_int) })
}

pub fn login() -> Result<()> {
    check(unsafe { AsLogin() })
}

pub fn logout() -> Result<()> {
    check(unsafe { AsLogout() })
}

/// Opens exactly these ports on this node, each piped to the same port on
/// 127.0.0.1 (see forward.rs). Needs a started node; `stop` closes them.
pub fn set_forwards(csv: &str) -> Result<()> {
    let c = CString::new(csv)?;
    check(unsafe { AsSetForwards(c.as_ptr()) })
}

/// A 127.0.0.1 port that pipes to `target` ("IP:PORT") over the tailnet.
/// Close it with `close_forward` when done.
pub fn forward(target: &str) -> Result<u16> {
    let c = CString::new(target)?;
    let mut port: c_int = 0;
    check(unsafe { AsForward(c.as_ptr(), &mut port) })?;
    u16::try_from(port).map_err(|_| anyhow!("bad forward port {port}"))
}

pub fn close_forward(port: u16) {
    unsafe { AsCloseForward(c_int::from(port)) }
}

extern "C" fn go_log(line: *const c_char) {
    if line.is_null() {
        return;
    }
    let s = unsafe { CStr::from_ptr(line) }.to_string_lossy();
    log::info!(target: "tailscale", "{s}");
}

/// Sends the embedded node's log lines (forwarded connections among them)
/// to this app's log instead of a stderr nobody sees.
pub fn route_logs() {
    unsafe { AsSetLogger(go_log) }
}

/// Shuts the node down (off the tailnet); `start` + `serve` bring it back.
pub fn stop() {
    unsafe { AsStop() }
}

/// Tailscale-level round trip to a peer in ms, or None if it doesn't answer.
pub fn ping(ip: &str) -> Option<u32> {
    let c = CString::new(ip).ok()?;
    let ms = unsafe { AsPing(c.as_ptr()) };
    (ms >= 0).then_some(ms as u32)
}

#[derive(Deserialize, Default, Debug, Clone)]
pub struct Node {
    #[serde(default, rename = "dnsName")]
    pub dns_name: String,
    #[serde(default)]
    pub ips: Option<Vec<String>>,
}

#[derive(Deserialize, Default, Debug, Clone)]
pub struct PeerStatus {
    #[serde(default)]
    pub name: String,
    #[serde(default, rename = "dnsName")]
    pub dns_name: String,
    #[serde(default)]
    pub os: String,
    #[serde(default)]
    pub ips: Option<Vec<String>>,
    #[serde(default)]
    pub online: bool,
    /// RFC 3339, only while offline.
    #[serde(default, rename = "lastSeen")]
    pub last_seen: String,
    /// ip:port when the link is direct (peer to peer).
    #[serde(default)]
    pub direct: String,
    /// DERP relay region when relayed.
    #[serde(default)]
    pub relay: String,
    #[serde(default)]
    pub rx: u64,
    #[serde(default)]
    pub tx: u64,
}

impl PeerStatus {
    pub fn ipv4(&self) -> String {
        self.ips.as_ref().and_then(|v| v.iter().find(|ip| ip.contains('.')).cloned()).unwrap_or_default()
    }
    pub fn ipv6(&self) -> String {
        self.ips.as_ref().and_then(|v| v.iter().find(|ip| ip.contains(':')).cloned()).unwrap_or_default()
    }
    pub fn short_name(&self) -> String {
        self.dns_name.split('.').next().filter(|s| !s.is_empty()).unwrap_or(&self.name).to_string()
    }
}

#[derive(Deserialize, Default, Debug, Clone)]
pub struct Status {
    #[serde(default)]
    pub state: String,
    #[serde(default, rename = "authURL")]
    pub auth_url: String,
    #[serde(default)]
    pub tailnet: String,
    #[serde(default)]
    pub error: String,
    #[serde(rename = "self")]
    pub me: Option<Node>,
    #[serde(default)]
    pub peers: Option<Vec<PeerStatus>>,
}

impl Status {
    pub fn running(&self) -> bool {
        self.state == "Running"
    }
    pub fn needs_login(&self) -> bool {
        self.state == "NeedsLogin" || self.state == "NeedsMachineAuth"
    }
    pub fn ipv4(&self) -> Option<String> {
        self.me.as_ref()?.ips.as_ref()?.iter().find(|ip| ip.contains('.')).cloned()
    }
    pub fn ipv6(&self) -> Option<String> {
        self.me.as_ref()?.ips.as_ref()?.iter().find(|ip| ip.contains(':')).cloned()
    }
}

pub fn status() -> Status {
    take(unsafe { AsStatus() })
        .and_then(|j| serde_json::from_str(&j).ok())
        .unwrap_or_default()
}

#[derive(Deserialize, Debug, Clone)]
pub struct Peer {
    pub ip: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub user: String,
}

impl Peer {
    pub fn label(&self) -> String {
        match (self.name.is_empty(), self.user.is_empty()) {
            (false, false) => format!("{} ({})", self.name, self.user),
            (false, true) => self.name.clone(),
            _ => self.ip.clone(),
        }
    }
}

/// Who is behind a connection that arrived through `serve`, identified by
/// its loopback source port. None for connections from local processes.
pub fn peer(src_port: u16) -> Option<Peer> {
    take(unsafe { AsPeer(src_port as c_int) })
        .filter(|s| !s.is_empty())
        .and_then(|j| serde_json::from_str(&j).ok())
}

/// "asenascale-<computer name>", in the form Tailscale likes for hostnames.
pub fn hostname() -> String {
    let raw = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok())
        .unwrap_or_default();
    let clean: String = raw
        .trim()
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    let clean = clean.trim_matches('-');
    if clean.is_empty() { "asenascale-pc".into() } else { format!("asenascale-{clean}") }
}
