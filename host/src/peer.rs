//! The client side of `asenascale exec|shot`: SSH to another PC's
//! AsenaScale server (through a tray-made tailnet forward) and run one command.

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{anyhow, bail, Result};
use russh::client;
use russh::keys::{PrivateKey, PrivateKeyWithHashAlg, PublicKey, PublicKeyOrCertificate};
use russh::{ChannelMsg, Sig};

use crate::known::{Known, Seen};

/// Connecting and the SSH handshake (not the approval wait) must finish in this.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);

/// Which of the remote command's streams a chunk of output came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stream {
    Out,
    Err,
}

/// Accepts the other PC's host key if it is the one this PC saw there before
/// (or the first one); anything else is left in `problem` for `exec`.
struct Pin {
    who: String,
    known: Known,
    problem: Arc<Mutex<Option<anyhow::Error>>>,
}

impl client::Handler for Pin {
    type Error = russh::Error;

    // The tailnet already proved which device answers (WireGuard keys); the
    // pinned host key also catches a different AsenaScale behind its IP.
    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        let key = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(cert) => PublicKey::new(cert.public_key().clone(), ""),
        };
        let problem = match self.known.check(&self.who, &key) {
            Ok(Seen::New | Seen::Same) => return Ok(true),
            Ok(Seen::Changed) => anyhow!(
                "the other PC's identity changed ({who}); if you reinstalled AsenaScale there, run `asenascale forget {who}`",
                who = self.who
            ),
            Err(e) => e,
        };
        *self.problem.lock().unwrap() = Some(problem);
        Ok(false)
    }
}

/// Runs `cmd` on the AsenaScale server at `addr` (the PC known as `who`, its
/// tailnet IP), handing its output to `on` as it arrives, and returns its exit
/// code. An error from `on` stops it. The other PC's host key must match the
/// one `known` has for `who`; the first one seen is remembered. The first
/// time, the other PC shows an approval dialog; this waits for it.
pub async fn exec(
    addr: SocketAddr,
    who: &str,
    known: &Known,
    key: PrivateKey,
    cmd: &str,
    mut on: impl FnMut(Stream, &[u8]) -> Result<()>,
) -> Result<u32> {
    let config = Arc::new(client::Config {
        // Long enough for the person at the other PC to answer the approval
        // dialog (no traffic flows meanwhile); the server's keepalives keep a
        // quiet command alive past it.
        inactivity_timeout: Some(Duration::from_secs(120)),
        ..Default::default()
    });
    let problem = Arc::new(Mutex::new(None));
    let pin = Pin { who: who.to_string(), known: known.clone(), problem: problem.clone() };
    let unreachable = || anyhow!("can't reach {who} (is AsenaScale running there?)");
    let mut ssh = match tokio::time::timeout(CONNECT_TIMEOUT, client::connect(config, addr, pin)).await {
        Ok(Ok(ssh)) => ssh,
        Ok(Err(e)) => return Err(problem.lock().unwrap().take().unwrap_or_else(|| anyhow!(e).context(unreachable()))),
        Err(_) => return Err(unreachable()),
    };
    // The key is Ed25519: no RSA hash to pick.
    let auth = ssh.authenticate_publickey(whoami(), PrivateKeyWithHashAlg::new(Arc::new(key), None)).await;
    match auth {
        Ok(auth) if auth.success() => {}
        // Refused, and the connection is still up: a real "no".
        Ok(_) if !ssh.is_closed() => {
            bail!("this PC is not allowed there (approve it on the other PC, or `asenascale revoke` and retry)")
        }
        // The connection ended while waiting: most likely the inactivity
        // timeout while nobody answered the approval dialog.
        _ => bail!("no answer from the other PC (is an approval dialog waiting there?)"),
    }
    let mut ch = ssh.channel_open_session().await?;
    ch.exec(true, cmd).await?;
    // No stdin is forwarded: say so, or a command that reads it waits forever.
    ch.eof().await?;
    let mut code = None;
    let result = loop {
        let Some(msg) = ch.wait().await else { break Ok(()) };
        let sent = match msg {
            ChannelMsg::Data { ref data } => on(Stream::Out, data),
            ChannelMsg::ExtendedData { ref data, .. } => on(Stream::Err, data),
            ChannelMsg::ExitStatus { exit_status } => {
                code = Some(exit_status);
                Ok(())
            }
            ChannelMsg::ExitSignal { ref signal_name, .. } => {
                code = Some(signal_code(signal_name));
                Ok(())
            }
            ChannelMsg::Failure => Err(anyhow!("the other PC refused the command")),
            _ => Ok(()),
        };
        if sent.is_err() {
            break sent;
        }
    };
    let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
    result?;
    code.ok_or_else(|| anyhow!("the other PC closed the connection without an exit code"))
}

/// A shell's exit code for a command killed by `sig`: 128 + its number.
fn signal_code(sig: &Sig) -> u32 {
    let n = match sig {
        Sig::HUP => 1,
        Sig::INT => 2,
        Sig::KILL => 9,
        Sig::SEGV => 11,
        Sig::TERM => 15,
        _ => 1,
    };
    128 + n
}

fn whoami() -> String {
    std::env::var("USER").or_else(|_| std::env::var("USERNAME")).unwrap_or_else(|_| "asenascale".into())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use russh::keys::{Algorithm, PrivateKey};

    use super::Stream;
    use crate::known::Known;
    use crate::store::TempDir;
    use crate::server::{Server, State};
    use crate::store::Devices;

    /// A local server that trusts `client`; returns its port and runtime.
    fn server_for(client: &PrivateKey) -> (u16, tokio::runtime::Runtime) {
        std::env::set_var("AS_TEST_LOCAL", "1");
        let state = State::for_test(Devices::only(&[client.public_key().clone()]));
        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
        let listener = rt.block_on(tokio::net::TcpListener::bind(("127.0.0.1", 0))).unwrap();
        let port = listener.local_addr().unwrap().port();
        let config = Arc::new(russh::server::Config {
            server_id: russh::SshId::Standard(crate::server::SERVER_ID.into()),
            keys: vec![PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap()],
            preferred: crate::preferred(),
            inactivity_timeout: Some(Duration::from_secs(30)),
            ..Default::default()
        });
        let mut srv = Server { state };
        rt.spawn(async move {
            use russh::server::Server as _;
            let _ = srv.run_on_socket(config, &listener).await;
        });
        (port, rt)
    }

    /// The identity the tests' server has (the tailnet IP in real use).
    const PEER: &str = "100.64.0.9";

    /// What `exec` streamed, collected.
    #[derive(Debug)]
    struct Got {
        out: Vec<u8>,
        err: Vec<u8>,
        code: u32,
    }

    /// Runs `cmd` with a fresh, throwaway list of known PCs.
    async fn collect(addr: std::net::SocketAddr, key: PrivateKey, cmd: &str) -> anyhow::Result<Got> {
        let dir = TempDir::new(&format!("peer-{}", addr.port()));
        collect_pinned(addr, &Known::in_dir(&dir.0), key, cmd).await
    }

    async fn collect_pinned(addr: std::net::SocketAddr, known: &Known, key: PrivateKey, cmd: &str) -> anyhow::Result<Got> {
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let code = super::exec(addr, PEER, known, key, cmd, |s, d| {
            match s {
                Stream::Out => out.extend_from_slice(d),
                Stream::Err => err.extend_from_slice(d),
            }
            Ok(())
        })
        .await?;
        Ok(Got { out, err, code })
    }

    #[test]
    fn runs_a_builtin_and_a_shell_command() {
        let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let (port, rt) = server_for(&key);
        let addr = ([127, 0, 0, 1], port).into();

        let got = rt.block_on(collect(addr, key.clone(), "mc version")).unwrap();
        assert!(String::from_utf8_lossy(&got.out).starts_with("SSH-2.0-AsenaScale_"), "{got:?}");
        assert_eq!(got.code, 0);

        let got = rt.block_on(collect(addr, key, "echo hi; echo oops >&2; exit 3")).unwrap();
        assert_eq!(String::from_utf8_lossy(&got.out).trim(), "hi");
        assert_eq!(String::from_utf8_lossy(&got.err).trim(), "oops");
        assert_eq!(got.code, 3);
    }

    #[test]
    fn a_failing_sink_stops_the_command() {
        let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let (port, rt) = server_for(&key);
        let addr = ([127, 0, 0, 1], port).into();
        let err = rt
            .block_on(async {
                let dir = TempDir::new("peer-sink");
                super::exec(addr, PEER, &Known::in_dir(&dir.0), key, "echo hi", |_, _| anyhow::bail!("disk full")).await
            })
            .unwrap_err();
        assert!(format!("{err:#}").contains("disk full"), "{err:#}");
    }

    #[test]
    fn a_command_reading_stdin_gets_eof() {
        let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let (port, rt) = server_for(&key);
        let addr = ([127, 0, 0, 1], port).into();
        let got = rt
            .block_on(async { tokio::time::timeout(Duration::from_secs(10), collect(addr, key, "cat")).await })
            .expect("`cat` should end once stdin is closed")
            .unwrap();
        assert!(got.out.is_empty(), "{got:?}");
        assert_eq!(got.code, 0);
    }

    #[test]
    fn a_changed_host_key_is_refused() {
        let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let dir = TempDir::new("peer-pin");
        let known = Known::in_dir(&dir.0);
        // Two servers, each with its own random host key, seen as one PC.
        let (port_a, rt_a) = server_for(&key);
        let (port_b, rt_b) = server_for(&key);

        let got = rt_a.block_on(collect_pinned(([127, 0, 0, 1], port_a).into(), &known, key.clone(), "mc version"));
        assert_eq!(got.unwrap().code, 0);
        let got = rt_a.block_on(collect_pinned(([127, 0, 0, 1], port_a).into(), &known, key.clone(), "mc version"));
        assert_eq!(got.unwrap().code, 0, "same key again is fine");

        let err = rt_b
            .block_on(collect_pinned(([127, 0, 0, 1], port_b).into(), &known, key, "mc version"))
            .unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.contains("identity changed (100.64.0.9)"), "{msg}");
        assert!(msg.contains("asenascale forget 100.64.0.9"), "{msg}");
    }

    #[test]
    fn nothing_answering_is_reported_as_unreachable() {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let err = rt
            .block_on(async {
                // Takes the connection and hangs up, as the tray's forward
                // does when the other PC has no AsenaScale listening.
                let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
                let addr = listener.local_addr().unwrap();
                tokio::spawn(async move {
                    while let Ok((sock, _)) = listener.accept().await {
                        drop(sock);
                    }
                });
                let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
                tokio::time::timeout(Duration::from_secs(10), collect(addr, key, "mc version")).await
            })
            .expect("should fail fast")
            .unwrap_err();
        let msg = format!("{err:#}");
        assert!(msg.starts_with("can't reach 100.64.0.9 (is AsenaScale running there?)"), "{msg}");
    }

    #[test]
    fn signals_map_to_shell_style_codes() {
        use russh::Sig;
        assert_eq!(super::signal_code(&Sig::KILL), 137);
        assert_eq!(super::signal_code(&Sig::TERM), 143);
        assert_eq!(super::signal_code(&Sig::INT), 130);
        assert_eq!(super::signal_code(&Sig::HUP), 129);
        assert_eq!(super::signal_code(&Sig::SEGV), 139);
        assert_eq!(super::signal_code(&Sig::Custom("WINCH".into())), 129);
    }

    /// An unknown key opens the approval dialog (server.rs `auth_publickey`);
    /// run by hand and click "No".
    #[test]
    #[ignore]
    fn an_unapproved_key_is_refused() {
        let trusted = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let stranger = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let (port, rt) = server_for(&trusted);
        let err = rt.block_on(collect(([127, 0, 0, 1], port).into(), stranger, "mc version")).unwrap_err();
        assert!(format!("{err:#}").contains("not allowed"), "{err:#}");
    }
}
