//! The client side of `asenascale exec|shot`: SSH to another PC's
//! AsenaScale server (through a tray-made tailnet forward) and run one command.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use russh::client;
use russh::keys::{PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::ChannelMsg;

/// Which of the remote command's streams a chunk of output came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stream {
    Out,
    Err,
}

struct Trust;

impl client::Handler for Trust {
    type Error = russh::Error;

    // The tailnet already proved who the peer is (WireGuard keys, our own
    // account); the SSH host key adds nothing we could check here.
    async fn check_server_key(&mut self, _key: &PublicKeyOrCertificate) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

/// Runs `cmd` on the AsenaScale server at `addr`, handing its output to `on`
/// as it arrives, and returns its exit code. An error from `on` stops it.
/// The first time, the other PC shows an approval dialog; this waits for it.
pub async fn exec(
    addr: SocketAddr,
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
    let mut ssh = client::connect(config, addr, Trust).await.context("connecting")?;
    let hash = ssh.best_supported_rsa_hash().await?.flatten();
    let auth = ssh
        .authenticate_publickey(whoami(), PrivateKeyWithHashAlg::new(Arc::new(key), hash))
        .await
        .context("signing in")?;
    if !auth.success() {
        bail!("this PC is not allowed there (approve it on the other PC, or `asenascale revoke` and retry)");
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

fn whoami() -> String {
    std::env::var("USER").or_else(|_| std::env::var("USERNAME")).unwrap_or_else(|_| "asenascale".into())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use russh::keys::{Algorithm, PrivateKey};

    use super::Stream;
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

    /// What `exec` streamed, collected.
    #[derive(Debug)]
    struct Got {
        out: Vec<u8>,
        err: Vec<u8>,
        code: u32,
    }

    async fn collect(addr: std::net::SocketAddr, key: PrivateKey, cmd: &str) -> anyhow::Result<Got> {
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let code = super::exec(addr, key, cmd, |s, d| {
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
            .block_on(super::exec(addr, key, "echo hi", |_, _| anyhow::bail!("disk full")))
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
