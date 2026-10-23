//! Test stand-ins for an entry relay on the loopback interface: a QUIC and a TCP/TLS server that speak the relay
//! control protocol (they echo the protocol identifier and answer every op with OK) and a plain UDP port (the Speed
//! mode), with a WireGuard exit behind them that answers pings, and a UDP path that drops datagrams. Also a
//! stand-in for the tunnel commands of the API, and a logged-in client state that uses both.

use crate::client_state::{ClientState, ClientStateHandle};
use crate::config::cached::{ConfigCached, Version};
use crate::exit_selection::ExitSelector;
use crate::manager::{TunnelArgs, TunnelMode};
use crate::quicwg::{DEFAULT_UDP_PAYLOAD_SIZE, recv_message, send_message};
use crate::relay_selection::RelaySelector;
use crate::tokio::AbortOnDrop;
use crate::wg_key_store::WgKeyStore;
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use bytes::Bytes;
use etherparse::{IcmpEchoHeader, Icmpv4Type, PacketBuilder, SlicedPacket, TransportSlice};
use obscuravpn_api::cmd::ExitList;
use obscuravpn_api::relay_protocol::{MessageCode, MessageContext, MessageHeader, RelayOpCode, RelayResponseCode};
use obscuravpn_api::types::{
    AccountId, AuthToken, CityCode, CountryCode, OneExit, OneRelay, OneTunnel, TunnelConfig, TunnelStatus, WgClientConfig, WgPubkey, WgServerConfig,
};
use obscuravpn_api::wg_fragment::merge::{ReassembleResult, WgFragmentBuffer};
use quinn::crypto::rustls::QuicServerConfig;
use quinn::rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use quinn::{ConnectionId, ConnectionIdGenerator, EndpointConfig, rustls};
use ring::rand::SystemRandom;
use ring::signature::{Ed25519KeyPair, KeyPair};
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::num::NonZeroU32;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::select;
use tokio::time::{sleep, timeout};
use tokio_rustls::TlsAcceptor;

pub const CLIENT_TUNNEL_IP: Ipv4Addr = Ipv4Addr::new(10, 75, 0, 2);
pub const EXIT_TUNNEL_IP: Ipv4Addr = Ipv4Addr::new(10, 64, 0, 1);

pub struct TestRelay {
    pub addr: SocketAddr,
    pub tcp_tls_addr: SocketAddr,
    /// The relay port of a Speed mode tunnel (Ombra patch P5).
    pub udp_addr: SocketAddr,
    pub cert: CertificateDer<'static>,
    client_secret_key: [u8; 32],
    pub exit_public_key: PublicKey,
    tcp_tls_counts: Arc<TcpTlsCounts>,
    _servers: [AbortOnDrop; 3],
}

/// The relay ops that the TCP/TLS server sent, and the OKs that the client answered them with.
#[derive(Default)]
struct TcpTlsCounts {
    relay_ops: AtomicUsize,
    oks: AtomicUsize,
}

impl TestRelay {
    /// Uses 8-byte connection IDs, the length that the byte budget of `DEFAULT_UDP_PAYLOAD_SIZE` assumes.
    pub fn spawn() -> Self {
        Self::spawn_for_client(rand::random())
    }

    /// A relay whose exit accepts only the client with this WireGuard secret key.
    pub fn spawn_for_client(client_secret_key: [u8; 32]) -> Self {
        let client_public_key = PublicKey::from(&StaticSecret::from(client_secret_key));
        let exit_secret_key: [u8; 32] = rand::random();
        let exit_public_key = PublicKey::from(&StaticSecret::from(exit_secret_key));
        let (cert, key) = self_signed_certificate();
        let tls = |alpn: &[u8]| {
            let mut tls = rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(&[&rustls::version::TLS13])
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(vec![cert.clone()], key.clone_key())
                .unwrap();
            tls.alpn_protocols = vec![alpn.to_vec()];
            tls
        };
        let mut server_config = quinn::ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls(b"h3")).unwrap()));
        // Full-size answers of the exit fit one datagram from the start, as they do from a relay.
        let mut transport_config = quinn::TransportConfig::default();
        transport_config.initial_mtu(DEFAULT_UDP_PAYLOAD_SIZE);
        server_config.transport_config(Arc::new(transport_config));
        let mut endpoint_config = EndpointConfig::default();
        endpoint_config.cid_generator(|| Box::new(EightByteConnectionIds));
        let socket = std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let endpoint = quinn::Endpoint::new(endpoint_config, Some(server_config), socket, quinn::default_runtime().unwrap()).unwrap();
        let addr = endpoint.local_addr().unwrap();
        let new_exit = move || Tunn::new(StaticSecret::from(exit_secret_key), client_public_key, None, None, rand::random(), None).unwrap();
        let quic_server = AbortOnDrop::spawn(async move {
            while let Some(incoming) = endpoint.accept().await {
                let exit = new_exit();
                tokio::spawn(async move {
                    let Ok(connection) = incoming.await else { return };
                    tokio::spawn(serve_exit(connection.clone(), exit));
                    let _ = serve_control_stream(&connection).await;
                    connection.closed().await;
                });
            }
        });
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        listener.set_nonblocking(true).unwrap();
        let listener = TcpListener::from_std(listener).unwrap();
        let tcp_tls_addr = listener.local_addr().unwrap();
        let acceptor = TlsAcceptor::from(Arc::new(tls(b"h2")));
        let tcp_tls_counts = Arc::new(TcpTlsCounts::default());
        let counts = tcp_tls_counts.clone();
        let tcp_tls_server = AbortOnDrop::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                let (acceptor, exit, counts) = (acceptor.clone(), new_exit(), counts.clone());
                tokio::spawn(async move {
                    let Ok(stream) = acceptor.accept(stream).await else { return };
                    let _ = serve_tcp_tls(stream, exit, &counts).await;
                });
            }
        });
        let udp_socket = std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        udp_socket.set_nonblocking(true).unwrap();
        let udp_socket = UdpSocket::from_std(udp_socket).unwrap();
        let udp_addr = udp_socket.local_addr().unwrap();
        let udp_server = AbortOnDrop::spawn(serve_udp_exit(udp_socket, new_exit()));
        Self {
            addr,
            tcp_tls_addr,
            udp_addr,
            cert,
            client_secret_key,
            exit_public_key,
            tcp_tls_counts,
            _servers: [quic_server, tcp_tls_server, udp_server],
        }
    }

    /// Waits until the client answered every relay op of the TCP/TLS server with OK, and returns their number.
    pub async fn tcp_tls_oks_for_all_relay_ops(&self) -> usize {
        for _ in 0..100 {
            let relay_ops = self.tcp_tls_counts.relay_ops.load(Ordering::Relaxed);
            if self.tcp_tls_counts.oks.load(Ordering::Relaxed) == relay_ops {
                return relay_ops;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("the client did not answer every relay op with OK")
    }

    pub fn client_secret_key(&self) -> StaticSecret {
        StaticSecret::from(self.client_secret_key)
    }

    /// The relay as the API lists it, reached at `addr` (the relay itself or a path to it).
    pub fn listing(&self, addr: SocketAddr) -> OneRelay {
        OneRelay {
            id: format!("test-relay-{}", addr.port()),
            city_code: CityCode { country_code: CountryCode("DE".into()), city_code: "fra".into() },
            city_name: "Frankfurt".into(),
            preferred_exits: Vec::new(),
            ip_v4: Ipv4Addr::LOCALHOST,
            ports: vec![addr.port()],
            tls_cert: self.cert.to_vec(),
        }
    }
}

async fn serve_control_stream(connection: &quinn::Connection) -> io::Result<()> {
    let (mut send, mut recv) = connection.accept_bi().await.map_err(io::Error::other)?;
    let mut protocol_identifier = [0u8; 16];
    recv.read_exact(&mut protocol_identifier).await.map_err(io::Error::other)?;
    send.write_all(&protocol_identifier).await.map_err(io::Error::other)?;
    loop {
        // `recv_message` reads as the client, so a client op arrives as a response code: only the context matters.
        let (_code, context_id, _arg) = recv_message(&mut recv).await?;
        if context_id.client_initiated() {
            send_message(&mut send, MessageCode::Response(RelayResponseCode::Ok), context_id, &[]).await?;
        }
    }
}

/// The exit behind the QUIC server.
async fn serve_exit(connection: quinn::Connection, mut exit: Tunn) {
    let mut fragments = WgFragmentBuffer::new(NonZeroU32::new(1000).unwrap(), 1540);
    while let Ok(datagram) = connection.read_datagram().await {
        let wg_message = match fragments.reassemble(datagram) {
            ReassembleResult::NotFragmented(wg_message) | ReassembleResult::Reassembled(wg_message) => wg_message,
            ReassembleResult::UnmatchedFragment { .. } => continue,
        };
        for answer in exit_answers(&mut exit, &wg_message) {
            let _ = connection.send_datagram(answer);
        }
    }
}

/// The exit behind the relay port of a Speed mode tunnel. It answers the address that sent last, as a relay that
/// follows a roaming client.
async fn serve_udp_exit(socket: UdpSocket, mut exit: Tunn) {
    let mut buffer = vec![0u8; usize::from(u16::MAX)];
    while let Ok((len, from)) = socket.recv_from(&mut buffer).await {
        for answer in exit_answers(&mut exit, &buffer[..len]) {
            let _ = socket.send_to(&answer, from).await;
        }
    }
}

/// The TCP/TLS server: answers client ops with OK, gives WireGuard messages to the exit and sends its answers as relay
/// ops, which the client answers with OK.
async fn serve_tcp_tls(stream: tokio_rustls::server::TlsStream<TcpStream>, mut exit: Tunn, counts: &TcpTlsCounts) -> io::Result<()> {
    let (mut read, mut write) = tokio::io::split(stream);
    let mut protocol_identifier = [0u8; 16];
    read.read_exact(&mut protocol_identifier).await?;
    write.write_all(&protocol_identifier).await?;
    write.flush().await?;
    loop {
        let (code, context_id, arg) = recv_client_message(&mut read).await?;
        if context_id.relay_initiated() {
            if RelayResponseCode::from_bytes(code).is_ok() {
                counts.oks.fetch_add(1, Ordering::Relaxed);
            }
        } else if let Some(RelayOpCode::WireGuard) = RelayOpCode::from_bytes(code) {
            for answer in exit_answers(&mut exit, &arg) {
                counts.relay_ops.fetch_add(1, Ordering::Relaxed);
                send_message(
                    &mut write,
                    MessageCode::Op(RelayOpCode::WireGuard),
                    MessageContext::MIN_RELAY_INITIATED,
                    &answer,
                )
                .await?;
            }
        } else {
            send_message(&mut write, MessageCode::Response(RelayResponseCode::Ok), context_id, &[]).await?;
        }
    }
}

/// One message from the client, read as the relay does: code, context and argument.
async fn recv_client_message(read: &mut (impl AsyncRead + Unpin)) -> io::Result<([u8; 4], MessageContext, Vec<u8>)> {
    let mut header = [0u8; 8];
    read.read_exact(&mut header).await?;
    let header = MessageHeader::from(header);
    let mut code = [0u8; 4];
    read.read_exact(&mut code).await?;
    let mut arg = vec![0u8; header.payload_length_usize() - code.len()];
    read.read_exact(&mut arg).await?;
    Ok((code, header.context_id, arg))
}

/// The WireGuard messages with which the exit answers `wg_message`: handshake messages and the replies to pings. Other
/// packets are dropped.
fn exit_answers(exit: &mut Tunn, wg_message: &[u8]) -> Vec<Bytes> {
    let mut buffer = vec![0u8; usize::from(u16::MAX)];
    // As the timer tick of a real peer: among other things, it resets the handshake rate limit once per second.
    let _ = exit.update_timers(&mut buffer);
    let mut answers = Vec::new();
    let mut input = wg_message;
    loop {
        match exit.decapsulate(None, input, &mut buffer) {
            TunnResult::WriteToNetwork(answer) => {
                answers.push(Bytes::copy_from_slice(answer));
                input = &[];
            }
            TunnResult::WriteToTunnelV4(packet, _) => {
                let mut reply_buffer = vec![0u8; usize::from(u16::MAX)];
                if let Some(reply) = echo_reply(packet)
                    && let TunnResult::WriteToNetwork(wg_reply) = exit.encapsulate(&reply, &mut reply_buffer)
                {
                    answers.push(Bytes::copy_from_slice(wg_reply));
                }
                return answers;
            }
            _ => return answers,
        }
    }
}

/// An ICMP echo request of `size` bytes from the client to the exit.
pub fn echo_request(seq: u16, size: usize) -> Vec<u8> {
    let builder = PacketBuilder::ipv4(CLIENT_TUNNEL_IP.octets(), EXIT_TUNNEL_IP.octets(), 64).icmpv4_echo_request(7, seq);
    let payload = vec![u8::try_from(seq % 256).unwrap(); size - builder.size(0)];
    let mut packet = Vec::new();
    builder.write(&mut packet, &payload).unwrap();
    packet
}

/// The answer to an ICMP echo request, or `None` for other packets.
pub fn echo_reply(request: &[u8]) -> Option<Vec<u8>> {
    let ip = SlicedPacket::from_ip(request).ok()?;
    let Some(etherparse::NetSlice::Ipv4(ipv4)) = ip.net else { return None };
    let Some(TransportSlice::Icmpv4(icmp)) = ip.transport else { return None };
    let Icmpv4Type::EchoRequest(IcmpEchoHeader { id, seq }) = icmp.icmp_type() else {
        return None;
    };
    let header = ipv4.header();
    let mut reply = Vec::new();
    PacketBuilder::ipv4(header.destination(), header.source(), 64)
        .icmpv4_echo_reply(id, seq)
        .write(&mut reply, icmp.payload())
        .ok()?;
    Some(reply)
}

struct EightByteConnectionIds;

impl ConnectionIdGenerator for EightByteConnectionIds {
    fn generate_cid(&mut self) -> ConnectionId {
        ConnectionId::new(&rand::random::<[u8; 8]>())
    }

    fn cid_len(&self) -> usize {
        8
    }

    fn cid_lifetime(&self) -> Option<Duration> {
        None
    }
}

/// A UDP path between a client and `upstream` that loses some datagrams.
pub struct LossyPath {
    pub addr: SocketAddr,
    _forwarder: AbortOnDrop,
}

impl LossyPath {
    /// Drops client datagrams larger than `max_payload` bytes, like a link with a small MTU.
    pub fn small_mtu(upstream: SocketAddr, max_payload: usize) -> Self {
        Self::spawn(upstream, move |_, datagram| datagram.len() <= max_payload, |_| true)
    }

    /// Drops the first `count` datagrams from `upstream`.
    pub fn losing_first_answers(upstream: SocketAddr, count: usize) -> Self {
        let mut answers = 0;
        let keep_answer = move |_: &[u8]| {
            answers += 1;
            answers > count
        };
        Self::spawn(upstream, |_, _| true, keep_answer)
    }

    /// Forwards only the datagrams of the first client address, like a relay that does not follow a client to a new
    /// network.
    pub fn first_client_only(upstream: SocketAddr) -> Self {
        let mut first = None;
        Self::spawn(upstream, move |from, _| *first.get_or_insert(from) == from, |_| true)
    }

    /// Forwards the datagrams of the address that sent the last WireGuard handshake initiation: like a Speed mode relay
    /// that learns the client address of a tunnel only from its handshake, not from later messages.
    pub fn client_from_wireguard_handshake(upstream: SocketAddr) -> Self {
        const HANDSHAKE_INITIATION: u8 = 1;
        let mut client = None;
        let keep_uplink = move |from, datagram: &[u8]| {
            if datagram.first() == Some(&HANDSHAKE_INITIATION) {
                client = Some(from);
            }
            client == Some(from)
        };
        Self::spawn(upstream, keep_uplink, |_| true)
    }

    /// Forwards the datagrams that `keep_uplink` (from the client) and `keep_downlink` (from `upstream`) accept. The
    /// answers go to the client that sent the last forwarded datagram.
    fn spawn(
        upstream: SocketAddr,
        mut keep_uplink: impl FnMut(SocketAddr, &[u8]) -> bool + Send + 'static,
        mut keep_downlink: impl FnMut(&[u8]) -> bool + Send + 'static,
    ) -> Self {
        let socket = || {
            let socket = std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            socket.set_nonblocking(true).unwrap();
            socket
        };
        let (client_side, relay_side) = (socket(), socket());
        relay_side.connect(upstream).unwrap();
        let addr = client_side.local_addr().unwrap();
        let (client_side, relay_side) = (UdpSocket::from_std(client_side).unwrap(), UdpSocket::from_std(relay_side).unwrap());
        let forwarder = AbortOnDrop::spawn(async move {
            let mut client = None;
            let mut uplink = vec![0u8; 65536];
            let mut downlink = vec![0u8; 65536];
            loop {
                select! {
                    received = client_side.recv_from(&mut uplink) => if let Ok((len, from)) = received
                        && keep_uplink(from, &uplink[..len])
                    {
                        client = Some(from);
                        let _ = relay_side.send(&uplink[..len]).await;
                    },
                    received = relay_side.recv(&mut downlink) => if let (Ok(len), Some(client)) = (received, client)
                        && keep_downlink(&downlink[..len])
                    {
                        let _ = client_side.send_to(&downlink[..len], client).await;
                    },
                }
            }
        });
        Self { addr, _forwarder: forwarder }
    }
}

/// The API over plain HTTP on the loopback interface, with only the tunnel commands. `POST tunnel` answers with
/// `tunnel`, with the requested id, and `DELETE tunnel` with OK. `on_request` sees each request before the answer.
pub struct TestApi {
    pub url: String,
    state: Arc<TestApiState>,
    _server: AbortOnDrop,
}

struct TestApiState {
    tunnel: Option<OneTunnel>,
    on_request: Box<dyn Fn(&ApiRequest) + Send + Sync>,
    requests: Mutex<Vec<ApiRequest>>,
    create_delay: Mutex<Duration>,
}

#[derive(Clone, Debug)]
pub struct ApiRequest {
    pub method: String,
    pub body: serde_json::Value,
    pub received_at: std::time::Instant,
}

impl TestApi {
    pub fn spawn(tunnel: Option<OneTunnel>, on_request: impl Fn(&ApiRequest) + Send + Sync + 'static) -> Self {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        listener.set_nonblocking(true).unwrap();
        let listener = TcpListener::from_std(listener).unwrap();
        let url = format!("http://{}/api", listener.local_addr().unwrap());
        let state = Arc::new(TestApiState {
            tunnel,
            on_request: Box::new(on_request),
            requests: Default::default(),
            create_delay: Default::default(),
        });
        let server_state = state.clone();
        let server = AbortOnDrop::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                let state = server_state.clone();
                tokio::spawn(async move {
                    let _ = serve_api_request(stream, &state).await;
                });
            }
        });
        Self { url, state, _server: server }
    }

    /// Answers each later `POST tunnel` only after `delay`.
    pub fn delay_creates(&self, delay: Duration) {
        *self.state.create_delay.lock().unwrap() = delay;
    }

    pub fn requests(&self) -> Vec<ApiRequest> {
        self.state.requests.lock().unwrap().clone()
    }

    /// The `id` of each request with this method so far.
    pub fn tunnel_ids(&self, method: &str) -> Vec<String> {
        let requests = self.requests();
        let matching = requests.iter().filter(|request| request.method == method);
        matching.filter_map(|request| request.body["id"].as_str().map(str::to_owned)).collect()
    }

    /// The ids of the `DELETE tunnel` requests so far.
    pub fn deleted_tunnel_ids(&self) -> Vec<String> {
        self.tunnel_ids("DELETE")
    }
}

async fn serve_api_request(stream: TcpStream, state: &TestApiState) -> io::Result<()> {
    let mut stream = BufReader::new(stream);
    let mut request_line = String::new();
    stream.read_line(&mut request_line).await?;
    let received_at = std::time::Instant::now();
    let mut parts = request_line.split_whitespace();
    let (method, path) = (parts.next().unwrap_or_default().to_owned(), parts.next().unwrap_or_default().to_owned());
    let mut content_length = 0;
    loop {
        let mut header = String::new();
        stream.read_line(&mut header).await?;
        if header.trim().is_empty() {
            break;
        }
        if let Some((name, value)) = header.split_once(':')
            && name.eq_ignore_ascii_case("content-length")
        {
            content_length = value.trim().parse().unwrap_or(0);
        }
    }
    let mut body = vec![0u8; content_length];
    stream.read_exact(&mut body).await?;
    let request = ApiRequest { method, body: serde_json::from_slice(&body).unwrap_or_default(), received_at };
    (state.on_request)(&request);
    state.requests.lock().unwrap().push(request.clone());

    let (status, answer) = match (request.method.as_str(), path.as_str(), &state.tunnel) {
        ("POST", "/api/tunnel", Some(tunnel)) => {
            let delay = *state.create_delay.lock().unwrap();
            sleep(delay).await;
            let mut tunnel = tunnel.clone();
            tunnel.id = request.body["id"].as_str().unwrap_or_default().to_owned();
            ("200 OK", serde_json::to_string(&tunnel).unwrap())
        }
        ("DELETE", "/api/tunnel", _) => ("200 OK", String::new()),
        _ => ("404 Not Found", r#"{"error":{"NoApiRoute":{}},"msg":"no route"}"#.to_owned()),
    };
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{answer}",
        answer.len()
    );
    stream.get_mut().write_all(response.as_bytes()).await?;
    stream.get_mut().shutdown().await
}

/// The config that the API gives for a Speed mode tunnel to the exit of `relay` through `endpoint`.
pub fn udp_port_config(relay: &TestRelay, client_public_key: WgPubkey, endpoint: SocketAddr) -> (WgClientConfig, WgServerConfig) {
    let client = WgClientConfig {
        wg_pubkey: client_public_key,
        addresses: vec!["10.75.0.2/32".parse().unwrap(), "fc00:bbbb:bbbb:bb01::2/128".parse().unwrap()],
    };
    let server = WgServerConfig {
        wg_pubkey: WgPubkey(relay.exit_public_key.to_bytes()),
        endpoints: vec![endpoint],
        dnses: vec![EXIT_TUNNEL_IP.into()],
    };
    (client, server)
}

/// A logged-in client state with a `TestApi` and a `TestRelay` (Ombra patch P5). Its relay and exit lists hold only
/// these, and the API makes Speed mode tunnels through `endpoint(&relay)`. Nothing leaves the loopback interface.
pub struct SpeedSetup {
    pub client_state: ClientStateHandle,
    pub api: TestApi,
    pub client_public_key: WgPubkey,
    /// Speed mode to the relay and the exit, with MTU 1400.
    pub args: TunnelArgs,
    /// For each create call: whether config.json held the requested id at that time.
    pub id_in_config_at_create: Arc<Mutex<Vec<bool>>>,
    _relay: TestRelay,
    _dir: tempfile::TempDir,
}

impl SpeedSetup {
    pub fn new(endpoint: impl FnOnce(&TestRelay) -> SocketAddr) -> Self {
        // As the apps do at start. A second install fails, which is fine.
        let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
        let dir = tempfile::tempdir().unwrap();
        let client_state = ClientState::new(dir.path().into(), WgKeyStore::None, "test".into(), false).unwrap();
        // A cached auth token, so that no request asks for one. The login also resets the WireGuard key, so it comes
        // first.
        let account_id = AccountId::from_string_unchecked("test-account".into());
        client_state
            .set_account_id(Some((account_id, Some(AuthToken::from("test-token".to_owned())))))
            .unwrap();
        let (client_secret_key, client_public_key) = client_state.use_wg_key_pair();
        let relay = TestRelay::spawn_for_client(client_secret_key.to_bytes());
        let listing = relay.listing(relay.addr);
        let exit = OneExit {
            id: "FRA-EXIT-1".into(),
            city_code: CityCode { country_code: CountryCode("DE".into()), city_code: "fra".into() },
            city_name: "Frankfurt".into(),
            datacenter_id: 1,
            provider_id: "de-fra-wg-001".into(),
            provider_url: "https://servers.example/de-fra-wg-001".into(),
            provider_name: "Mullvad VPN".into(),
            provider_homepage_url: "https://mullvad.net".into(),
            tier: 0,
        };
        let (client, server) = udp_port_config(&relay, client_public_key, endpoint(&relay));
        let tunnel = OneTunnel {
            id: String::new(),
            label: None,
            status: TunnelStatus::Created { when: Default::default() },
            config: TunnelConfig::UdpPort { client, server },
            relay: listing.clone(),
            exit: exit.clone(),
        };
        let config_file = dir.path().join("config.json");
        let id_in_config_at_create = Arc::new(Mutex::new(Vec::new()));
        let id_in_config = id_in_config_at_create.clone();
        let api = TestApi::spawn(Some(tunnel), move |request| {
            if request.method == "POST" {
                let config_json = std::fs::read_to_string(&config_file).unwrap();
                id_in_config
                    .lock()
                    .unwrap()
                    .push(config_json.contains(request.body["id"].as_str().unwrap()));
            }
        });
        let args = TunnelArgs {
            exit: ExitSelector::Exit { id: exit.id.clone() },
            relay: RelaySelector::Relay { id: listing.id.clone() },
            mode: TunnelMode::WireguardUdp,
            udp_mtu: Some(1400),
        };
        client_state.change_config(|config| {
            // Not `set_api_url`: it would rotate the WireGuard key that the relay knows.
            config.api_url = Some(api.url.clone());
            config.api_host_alternate = Some("127.0.0.1".into());
            config.cached_relays = Some(ConfigCached::new(Arc::new(vec![listing]), Version::artificial()));
            config.cached_exits = Some(ConfigCached::new(Arc::new(ExitList { exits: vec![exit] }), Version::artificial()));
        });
        Self { client_state, api, client_public_key, args, id_in_config_at_create, _relay: relay, _dir: dir }
    }
}

pub async fn wait_until(what: &str, condition: impl Fn() -> bool) {
    timeout(Duration::from_secs(10), async {
        while !condition() {
            sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("timed out waiting until {what}"));
}

/// A self-signed Ed25519 certificate. The crate has no certificate generator, so the DER is written by hand.
fn self_signed_certificate() -> (CertificateDer<'static>, PrivateKeyDer<'static>) {
    let pkcs8 = Ed25519KeyPair::generate_pkcs8(&SystemRandom::new()).unwrap();
    let key_pair = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
    let ed25519 = der(0x30, &[&der(0x06, &[&[0x2b, 0x65, 0x70]])]);
    let common_name = der(0x30, &[&der(0x06, &[&[0x55, 0x04, 0x03]]), &der(0x0c, &[b"relay.test"])]);
    let name = der(0x30, &[&der(0x31, &[&common_name])]);
    let validity = der(0x30, &[&der(0x17, &[b"250101000000Z"]), &der(0x17, &[b"491231235959Z"])]);
    let public_key = der(0x30, &[&ed25519, &der(0x03, &[&[0], key_pair.public_key().as_ref()])]);
    let version_3 = der(0xa0, &[&der(0x02, &[&[2]])]);
    let serial = der(0x02, &[&[1]]);
    let tbs = der(0x30, &[&version_3, &serial, &ed25519, &name, &validity, &name, &public_key]);
    let signature = key_pair.sign(&tbs);
    let cert = der(0x30, &[&tbs, &ed25519, &der(0x03, &[&[0], signature.as_ref()])]);
    (CertificateDer::from(cert), PrivatePkcs8KeyDer::from(pkcs8.as_ref().to_vec()).into())
}

fn der(tag: u8, parts: &[&[u8]]) -> Vec<u8> {
    let content = parts.concat();
    let mut encoded = vec![tag];
    // DER needs the shortest length form.
    match u8::try_from(content.len()) {
        Ok(len) if len < 0x80 => encoded.push(len),
        Ok(len) => encoded.extend_from_slice(&[0x81, len]),
        Err(_) => {
            let [high, low] = u16::try_from(content.len()).unwrap().to_be_bytes();
            encoded.extend_from_slice(&[0x82, high, low]);
        }
    }
    encoded.extend_from_slice(&content);
    encoded
}
