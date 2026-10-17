//! The client side of `asenascale exec|shot`: SSH to another PC's
//! AsenaScale server (through a tray-made tailnet forward) and run one command.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use russh::client;
use russh::keys::{PrivateKey, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::ChannelMsg;

/// What one remote command produced.
#[derive(Debug, Default)]
pub struct Output {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub code: u32,
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

/// Runs `cmd` on the AsenaScale server at `addr` and collects its output.
/// The first time, the other PC shows an approval dialog; this waits for it.
pub async fn exec(addr: SocketAddr, key: PrivateKey, cmd: &str) -> Result<Output> {
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
    let mut out = Output::default();
    let mut code = None;
    while let Some(msg) = ch.wait().await {
        match msg {
            ChannelMsg::Data { ref data } => out.stdout.extend_from_slice(data),
            ChannelMsg::ExtendedData { ref data, .. } => out.stderr.extend_from_slice(data),
            ChannelMsg::ExitStatus { exit_status } => code = Some(exit_status),
            _ => {}
        }
    }
    let _ = ssh.disconnect(russh::Disconnect::ByApplication, "", "en").await;
    out.code = code.ok_or_else(|| anyhow!("the other PC closed the connection without an exit code"))?;
    Ok(out)
}

fn whoami() -> String {
    std::env::var("USER").or_else(|_| std::env::var("USERNAME")).unwrap_or_else(|_| "asenascale".into())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use russh::keys::{Algorithm, PrivateKey};

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

    #[test]
    fn runs_a_builtin_and_a_shell_command() {
        let key = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let (port, rt) = server_for(&key);
        let addr = ([127, 0, 0, 1], port).into();

        let out = rt.block_on(super::exec(addr, key.clone(), "mc version")).unwrap();
        assert!(String::from_utf8_lossy(&out.stdout).starts_with("SSH-2.0-AsenaScale_"), "{out:?}");
        assert_eq!(out.code, 0);

        let out = rt.block_on(super::exec(addr, key, "echo hi; exit 3")).unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "hi");
        assert_eq!(out.code, 3);
    }

    /// An unknown key opens the approval dialog (server.rs `auth_publickey`);
    /// run by hand and click "No".
    #[test]
    #[ignore]
    fn an_unapproved_key_is_refused() {
        let trusted = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let stranger = PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).unwrap();
        let (port, rt) = server_for(&trusted);
        let err = rt.block_on(super::exec(([127, 0, 0, 1], port).into(), stranger, "mc version")).unwrap_err();
        assert!(format!("{err:#}").contains("not allowed"), "{err:#}");
    }
}
