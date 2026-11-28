use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use std::{
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, copy_bidirectional},
    net::{TcpListener, TcpStream},
    time::timeout,
};
use tokio_rustls::{
    TlsConnector,
    rustls::{ClientConfig, RootCertStore, pki_types::ServerName},
};
use uuid::Uuid;

#[derive(Clone, Debug, Deserialize)]
pub struct CoreConfig {
    pub listen: SocketAddr,
    pub server: String,
    pub port: u16,
    pub uuid: Uuid,
    pub sni: Option<String>,
    #[serde(default = "default_timeout")]
    pub connect_timeout_secs: u64,
}

fn default_timeout() -> u64 {
    10
}

impl CoreConfig {
    fn validate(&self) -> Result<()> {
        ensure!(
            self.listen.ip().is_loopback(),
            "SOCKS listener must use a loopback address; remote access has no authentication"
        );
        ensure!(!self.server.trim().is_empty(), "server is empty");
        ensure!(self.port != 0, "server port must be non-zero");
        ensure!(
            (1..=120).contains(&self.connect_timeout_secs),
            "connect_timeout_secs must be 1..=120"
        );
        if let Some(sni) = &self.sni {
            ensure!(
                !sni.trim().is_empty(),
                "sni must be omitted or a non-empty DNS name"
            );
        }
        Ok(())
    }
}

/// Run the experimental SOCKS5 -> VLESS/TLS TCP proxy until Ctrl+C.
pub async fn run(config: CoreConfig) -> Result<()> {
    config.validate()?;
    let tls = Arc::new(make_tls_config());
    let listener = TcpListener::bind(config.listen)
        .await
        .with_context(|| format!("cannot bind SOCKS listener {}", config.listen))?;
    eprintln!(
        "LimeNet experimental core listening on {}",
        listener.local_addr()?
    );

    let shutdown = tokio::signal::ctrl_c();
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let (client, peer) = accepted.context("SOCKS accept failed")?;
                let cfg = config.clone();
                let tls = tls.clone();
                tokio::spawn(async move {
                    if let Err(error) = handle_client(client, cfg, tls).await {
                        eprintln!("SOCKS session from {peer} ended: {error:#}");
                    }
                });
            }
            _ = &mut shutdown => {
                eprintln!("LimeNet experimental core stopping");
                return Ok(());
            }
        }
    }
}

fn make_tls_config() -> ClientConfig {
    let roots = RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth()
}

async fn handle_client(
    mut client: TcpStream,
    config: CoreConfig,
    tls_config: Arc<ClientConfig>,
) -> Result<()> {
    client.set_nodelay(true)?;
    socks_negotiate(&mut client).await?;
    let destination = match socks_read_connect(&mut client).await {
        Ok(destination) => destination,
        Err(error) => {
            let _ = socks_reply(&mut client, 1).await;
            return Err(error);
        }
    };

    let upstream = open_vless_tunnel(&config, tls_config, &destination).await;
    let mut upstream = match upstream {
        Ok(stream) => stream,
        Err(error) => {
            let _ = socks_reply(&mut client, 5).await;
            return Err(error);
        }
    };
    socks_reply(&mut client, 0).await?;
    let _ = copy_bidirectional(&mut client, &mut upstream)
        .await
        .context("TCP relay failed")?;
    Ok(())
}

async fn open_vless_tunnel(
    config: &CoreConfig,
    tls_config: Arc<ClientConfig>,
    destination: &(String, u16),
) -> Result<tokio_rustls::client::TlsStream<TcpStream>> {
    let upstream_addr = format_host_port(&config.server, config.port);
    let upstream = timeout(
        Duration::from_secs(config.connect_timeout_secs),
        TcpStream::connect(&upstream_addr),
    )
    .await
    .context("VLESS connection timed out")?
    .with_context(|| format!("cannot connect to VLESS server {upstream_addr}"))?;
    upstream.set_nodelay(true)?;

    let tls_name = config.sni.clone().unwrap_or_else(|| config.server.clone());
    let name = server_name(&tls_name)?;
    let mut upstream = timeout(
        Duration::from_secs(config.connect_timeout_secs),
        TlsConnector::from(tls_config).connect(name, upstream),
    )
    .await
    .context("TLS handshake timed out")?
    .context("TLS handshake failed")?;

    write_vless_request(&mut upstream, config.uuid, destination).await?;
    read_vless_response(&mut upstream).await?;
    Ok(upstream)
}

fn server_name(name: &str) -> Result<ServerName<'static>> {
    if let Ok(ip) = name.parse::<IpAddr>() {
        let ip = match ip {
            IpAddr::V4(v4) => v4.into(),
            IpAddr::V6(v6) => v6.into(),
        };
        return Ok(ServerName::IpAddress(ip));
    }
    ServerName::try_from(name.to_owned()).context("invalid TLS SNI name")
}

fn format_host_port(host: &str, port: u16) -> String {
    if host.parse::<Ipv6Addr>().is_ok() {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

async fn socks_negotiate<S>(stream: &mut S) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut header = [0u8; 2];
    stream
        .read_exact(&mut header)
        .await
        .context("incomplete SOCKS greeting")?;
    ensure!(header[0] == 5, "unsupported SOCKS version");
    let mut methods = vec![0u8; header[1] as usize];
    stream
        .read_exact(&mut methods)
        .await
        .context("incomplete SOCKS methods")?;
    if methods.contains(&0) {
        stream.write_all(&[5, 0]).await?;
        Ok(())
    } else {
        stream.write_all(&[5, 0xff]).await?;
        bail!("SOCKS client did not offer the no-auth method");
    }
}

async fn socks_read_connect<S>(stream: &mut S) -> Result<(String, u16)>
where
    S: AsyncRead + Unpin,
{
    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .await
        .context("incomplete SOCKS request")?;
    ensure!(header[0] == 5, "unsupported SOCKS request version");
    ensure!(header[1] == 1, "only SOCKS CONNECT is supported");
    ensure!(header[2] == 0, "invalid SOCKS reserved byte");
    let host = match header[3] {
        1 => {
            let mut bytes = [0; 4];
            stream.read_exact(&mut bytes).await?;
            IpAddr::V4(Ipv4Addr::from(bytes)).to_string()
        }
        3 => {
            let len = stream.read_u8().await? as usize;
            ensure!(len > 0, "empty SOCKS domain");
            let mut bytes = vec![0; len];
            stream.read_exact(&mut bytes).await?;
            String::from_utf8(bytes).context("SOCKS domain is not UTF-8")?
        }
        4 => {
            let mut bytes = [0; 16];
            stream.read_exact(&mut bytes).await?;
            IpAddr::V6(Ipv6Addr::from(bytes)).to_string()
        }
        _ => bail!("unsupported SOCKS address type"),
    };
    let port = stream.read_u16().await?;
    ensure!(port != 0, "SOCKS destination port must be non-zero");
    Ok((host, port))
}

async fn socks_reply<S>(stream: &mut S, status: u8) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    stream
        .write_all(&[5, status, 0, 1, 0, 0, 0, 0, 0, 0])
        .await?;
    Ok(())
}

async fn write_vless_request<S>(
    stream: &mut S,
    uuid: Uuid,
    destination: &(String, u16),
) -> Result<()>
where
    S: AsyncWrite + Unpin,
{
    let (host, port) = destination;
    let mut request = Vec::with_capacity(64 + host.len());
    request.push(0); // VLESS version
    request.extend_from_slice(uuid.as_bytes());
    request.push(0); // no addons
    request.push(1); // TCP command
    request.extend_from_slice(&port.to_be_bytes());
    if let Ok(ip) = host.parse::<IpAddr>() {
        match ip {
            IpAddr::V4(ip) => {
                request.push(1);
                request.extend_from_slice(&ip.octets());
            }
            IpAddr::V6(ip) => {
                request.push(3);
                request.extend_from_slice(&ip.octets());
            }
        }
    } else {
        let domain = host.as_bytes();
        ensure!(
            !domain.is_empty() && domain.len() <= u8::MAX as usize,
            "VLESS domain must be 1..=255 bytes"
        );
        request.push(2);
        request.push(domain.len() as u8);
        request.extend_from_slice(domain);
    }
    stream
        .write_all(&request)
        .await
        .context("failed to write VLESS request")?;
    stream.flush().await?;
    Ok(())
}

async fn read_vless_response<S>(stream: &mut S) -> Result<()>
where
    S: AsyncRead + Unpin,
{
    let mut header = [0u8; 2];
    stream
        .read_exact(&mut header)
        .await
        .context("incomplete VLESS response")?;
    ensure!(
        header[0] == 0,
        "unsupported VLESS response version {}",
        header[0]
    );
    let mut addons = vec![0; header[1] as usize];
    stream
        .read_exact(&mut addons)
        .await
        .context("incomplete VLESS response addons")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::generate_simple_self_signed;
    use tokio::io::duplex;
    use tokio_rustls::TlsAcceptor;
    use tokio_rustls::rustls::{
        ServerConfig,
        pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer},
    };

    fn test_config(listen: &str) -> CoreConfig {
        CoreConfig {
            listen: listen.parse().unwrap(),
            server: "edge.example".to_string(),
            port: 443,
            uuid: Uuid::nil(),
            sni: None,
            connect_timeout_secs: 10,
        }
    }

    #[test]
    fn config_rejects_non_loopback_socks_listener() {
        assert!(test_config("0.0.0.0:10808").validate().is_err());
        assert!(test_config("127.0.0.1:10808").validate().is_ok());
        assert!(test_config("[::1]:10808").validate().is_ok());
    }

    #[tokio::test]
    async fn vless_request_encodes_domain_destination() {
        let id = Uuid::parse_str("00112233-4455-6677-8899-aabbccddeeff").unwrap();
        let (mut writer, mut reader) = duplex(64);
        let write = tokio::spawn(async move {
            write_vless_request(&mut writer, id, &("example.com".to_string(), 443)).await
        });
        let mut buf = vec![0; 18 + 5 + 11];
        reader.read_exact(&mut buf).await.unwrap();
        write.await.unwrap().unwrap();
        assert_eq!(
            &buf[..18],
            &[
                0, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
                0xee, 0xff, 0
            ]
        );
        assert_eq!(
            &buf[18..],
            &[
                1, 1, 187, 2, 11, b'e', b'x', b'a', b'm', b'p', b'l', b'e', b'.', b'c', b'o', b'm'
            ]
        );
    }

    #[tokio::test]
    async fn socks_greeting_selects_no_auth() {
        let (mut server, mut client) = duplex(32);
        let task = tokio::spawn(async move { socks_negotiate(&mut server).await });
        client.write_all(&[5, 2, 2, 0]).await.unwrap();
        let mut response = [0; 2];
        client.read_exact(&mut response).await.unwrap();
        assert_eq!(response, [5, 0]);
        task.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn socks_connect_parses_domain_and_port() {
        let (mut server, mut client) = duplex(64);
        let task = tokio::spawn(async move { socks_read_connect(&mut server).await });
        client.write_all(&[5, 1, 0, 3, 11]).await.unwrap();
        client.write_all(b"example.com").await.unwrap();
        client.write_all(&[1, 187]).await.unwrap();
        assert_eq!(
            task.await.unwrap().unwrap(),
            ("example.com".to_string(), 443)
        );
    }

    #[tokio::test]
    async fn vless_response_consumes_only_header_and_keeps_payload() {
        let (mut server, mut client) = duplex(32);
        let task = tokio::spawn(async move {
            read_vless_response(&mut server).await.unwrap();
            let mut payload = [0; 2];
            server.read_exact(&mut payload).await?;
            Ok::<_, std::io::Error>(payload)
        });
        client
            .write_all(&[0, 2, 0xaa, 0xbb, b'O', b'K'])
            .await
            .unwrap();
        let payload = task.await.unwrap().unwrap();
        assert_eq!(&payload, b"OK");
    }

    #[tokio::test]
    async fn socks_connect_reports_upstream_failure_to_client() {
        let reserved = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let unused_port = reserved.local_addr().unwrap().port();
        drop(reserved);

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let listen_addr = listener.local_addr().unwrap();
        let config = CoreConfig {
            listen: listen_addr,
            server: "127.0.0.1".to_string(),
            port: unused_port,
            uuid: Uuid::nil(),
            sni: None,
            connect_timeout_secs: 1,
        };
        let tls_config = Arc::new(make_tls_config());
        let (client, server) = tokio::join!(TcpStream::connect(listen_addr), listener.accept());
        let mut client = client.unwrap();
        let (server, _) = server.unwrap();
        let task = tokio::spawn(handle_client(server, config, tls_config));

        client.write_all(&[5, 1, 0]).await.unwrap();
        let mut method = [0; 2];
        client.read_exact(&mut method).await.unwrap();
        assert_eq!(method, [5, 0]);

        client
            .write_all(&[5, 1, 0, 1, 127, 0, 0, 1, 0, 80])
            .await
            .unwrap();
        let mut reply = [0; 10];
        client.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply[0..2], [5, 5]);
        assert!(task.await.unwrap().is_err());
    }

    #[tokio::test]
    async fn local_tls_vless_server_relays_socks_tcp_data() {
        let cert_key = generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
        let certificate = cert_key.cert.der().clone();
        let private_key =
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(cert_key.key_pair.serialize_der()));
        let server_config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![certificate.clone()], private_key)
            .unwrap();
        let mut roots = RootCertStore::empty();
        roots.add(certificate).unwrap();
        let client_config = Arc::new(
            ClientConfig::builder()
                .with_root_certificates(roots)
                .with_no_client_auth(),
        );

        let vless_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let vless_addr = vless_listener.local_addr().unwrap();
        let expected_uuid = Uuid::parse_str("00112233-4455-6677-8899-aabbccddeeff").unwrap();
        let vless_server = tokio::spawn(async move {
            let (stream, _) = vless_listener.accept().await.unwrap();
            let acceptor = TlsAcceptor::from(Arc::new(server_config));
            let mut stream = acceptor.accept(stream).await.unwrap();

            let mut header = [0; 19];
            stream.read_exact(&mut header).await.unwrap();
            assert_eq!(header[0], 0, "VLESS version");
            assert_eq!(&header[1..17], expected_uuid.as_bytes(), "VLESS UUID");
            assert_eq!(header[17], 0, "VLESS addons length");
            assert_eq!(header[18], 1, "VLESS TCP command");

            let mut destination = [0; 4];
            stream.read_exact(&mut destination).await.unwrap();
            assert_eq!(u16::from_be_bytes([destination[0], destination[1]]), 7);
            assert_eq!(destination[2], 2, "domain address type");
            assert_eq!(destination[3], 9, "domain length");
            let mut domain = [0; 9];
            stream.read_exact(&mut domain).await.unwrap();
            assert_eq!(&domain, b"echo.test");

            stream.write_all(&[0, 0]).await.unwrap();
            let mut payload = [0; 4];
            stream.read_exact(&mut payload).await.unwrap();
            assert_eq!(&payload, b"ping");
            stream.write_all(b"pong").await.unwrap();
            stream.shutdown().await.unwrap();
        });

        let socks_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socks_addr = socks_listener.local_addr().unwrap();
        let core_config = CoreConfig {
            listen: socks_addr,
            server: "127.0.0.1".to_string(),
            port: vless_addr.port(),
            uuid: expected_uuid,
            sni: Some("localhost".to_string()),
            connect_timeout_secs: 3,
        };
        let (client, accepted) =
            tokio::join!(TcpStream::connect(socks_addr), socks_listener.accept());
        let mut client = client.unwrap();
        let (core_client, _) = accepted.unwrap();
        let core_task = tokio::spawn(handle_client(core_client, core_config, client_config));

        client.write_all(&[5, 1, 0]).await.unwrap();
        let mut method = [0; 2];
        client.read_exact(&mut method).await.unwrap();
        assert_eq!(method, [5, 0]);

        client
            .write_all(&[
                5, 1, 0, 3, 9, b'e', b'c', b'h', b'o', b'.', b't', b'e', b's', b't', 0, 7,
            ])
            .await
            .unwrap();
        let mut reply = [0; 10];
        client.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply[0..2], [5, 0]);

        client.write_all(b"ping").await.unwrap();
        client.shutdown().await.unwrap();
        let mut response = [0; 4];
        client.read_exact(&mut response).await.unwrap();
        assert_eq!(&response, b"pong");

        core_task.await.unwrap().unwrap();
        vless_server.await.unwrap();
    }

    #[test]
    fn brackets_ipv6_vless_endpoint() {
        assert_eq!(format_host_port("2001:db8::1", 443), "[2001:db8::1]:443");
        assert_eq!(format_host_port("edge.example", 443), "edge.example:443");
    }
}
