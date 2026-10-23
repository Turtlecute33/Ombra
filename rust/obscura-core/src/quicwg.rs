use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use bytes::Bytes;
use futures::Stream;
use futures::StreamExt;
use futures::stream::unfold;
use obscuravpn_api::relay_protocol::{MessageCode, MessageContext, MessageHeader, PROTOCOL_IDENTIFIER, RelayOpCode, RelayResponseCode};
use obscuravpn_api::wg_fragment::merge::{ReassembleResult, WgFragmentBuffer};
use obscuravpn_api::wg_fragment::split::WgMessageFragmenter;
use quinn::crypto::rustls::QuicClientConfig;
use quinn::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use quinn::rustls::crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature};
use quinn::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use quinn::rustls::{CertificateError, DigitallySignedStruct, SignatureScheme};
use quinn::{ClientConfig, rustls};
use rand::random;
use serde::{Deserialize, Serialize};
use smallvec::SmallVec;
use std::any::Any;
use std::collections::VecDeque;
use std::iter::once;
use std::mem;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::num::{NonZeroU32, Saturating};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Weak};
use strum::Display;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::watch;
use tokio::time::{Duration, Instant, sleep};
use tokio::time::{sleep_until, timeout};
use tokio::{io, select, spawn};
use tokio_rustls::TlsConnector;
use tokio_rustls::client::TlsStream;
use uuid::Uuid;

use crate::bytes_arena::BytesArena;
use crate::datagram_coalescer::DatagramCoalescer;
use crate::int_helper::usize_into_u64;
use crate::liveness::LivenessChecker;
use crate::logging::DATAPATH_STATS_TARGET;
use crate::net::{NetworkInterface, RelayEndpoint};
use crate::net::{new_nonblocking_tcp, new_udp};
use crate::quic_tuning::{MtuChoices, MtuPolicy, QuicTuning, RelayConnection};
use crate::rate_limited_log;
use crate::tokio::AbortOnDrop;
use crate::wake_instant::WakeInstant;
use crate::wg_control_message_header::{WgControlMessageHeader, WgMessageDirection};

/// QUIC and TCP/TLS mode, as upstream: one initiation, sent 25 times (2.5 s).
const WG_FIRST_HANDSHAKE: FirstHandshake = FirstHandshake { sends: 25, sends_per_initiation: 25 };
const WG_FIRST_HANDSHAKE_TIMEOUT: Duration = Duration::from_millis(100);
/// Speed mode (Ombra patch P5): 5 s without an answer from the exit, then the connect fails with `udpHandshakeFailed`.
/// The exit drops copies of an initiation that it already answered (replay protection), so after a lost response only
/// a new initiation helps: one each second. The copies in between cover what the relay drops while it sets up the port
/// of a new tunnel; a response to the initiation before the last one still counts.
const WG_UDP_FIRST_HANDSHAKE: FirstHandshake = FirstHandshake { sends: 50, sends_per_initiation: 10 };

/// Room for one datagram from the relay in Speed mode. The WireGuard message of a 1500-byte packet is 1532 bytes.
const UDP_RECEIVE_SPACE: usize = 2048;

/// Ideally we would have a shorter QUIC idle timeout at the beginning and no timeout once the connection starts but this is not supported by quinn.
/// In theory this doesn't matter at all because we have setup timeouts and the keepalive checker once connected. But it serves as a failsafe.
pub const QUIC_IDLE_TIMEOUT: Duration = Duration::from_secs(600);

const QUIC_STEP_TIMEOUT: Duration = Duration::from_secs(30);

const CONTROL_STREAM_WINDOW: u32 = 4 * 1024;

/// How fast to call `update_timers`.
///
/// In the boringtun repo they call it at 4Hz, however we have traditionally called it at 1Hz and doesn't seem to have any problems.
const WG_TIMER_TICK: Duration = Duration::from_secs(1);
pub const TUNNEL_MTU: u16 = 1280;

/// Max UDP payload size used by default if network MTU allows for it
pub const DEFAULT_UDP_PAYLOAD_SIZE: u16 = 1350;

pub const IPV4_UDP_OVERHEAD: u16 = 20 + 8;

const LIVENESS_MTU: u16 = 100;

/// neptun's `encapsulate` needs room for the packet plus 32 bytes, and at least 148 bytes (a handshake initiation).
const WG_DATA_MESSAGE_OVERHEAD: usize = 32;
const WG_ENCAPSULATE_MIN_SPACE: usize = 148;

/// Maximum number of fragments in the WireGuard fragment buffer.
///
/// 1Gb/s (not counting tunnel overhead) at 1350B per message results in a message frequency below 100k/s.
/// To cover 100ms of jitter (on top of regular latency) at this speed, the fragment buffer needs to span 10k consecutively sent messages.
/// At 1kB per fragment (half of a message, including a generous margin for allocation and tunnel overhead), this results in a peak memory consumption of 10MB.
///
/// The time span covered (max jitter without packet loss) is inversely proportional to the bandwidth (e.g. at 100Mb/s the 100ms max jitter grows to 1s).
const WG_FRAGMENT_BUFFER_LEN: NonZeroU32 = NonZeroU32::new(10_000).unwrap();

/// Maximum WireGuard fragment size, to prevent fragment buffer bloat due to malicious large packets.
///
/// A 1540B fragment, can hold a 1532B WireGuard message.
/// With 32B overhead per WireGuard message, this allows a single fragment to hold a 1500B IP packet without requiring the second fragment to carry any data.
const WG_FRAGMENT_MAX_SIZE: u16 = 1540;

#[derive(Debug, Error)]
pub enum QuicWgReceiveError {
    #[error("tunnel is dead")]
    TunnelDead,
    /// Large liveness probes were lost while small ones returned (Ombra patch P4, MTU floor guard 2).
    #[error("the path drops full-size packets")]
    LargePacketsLost,
    #[error("quic receive error: {0}")]
    QuicReceiveError(io::Error),
}

#[derive(Debug, Error)]
pub enum QuicWgConnectError {
    #[error("crypto config: {0}")]
    CryptoConfig(anyhow::Error),
    #[error("quic config: {0}")]
    QuicConfig(quinn::ConnectError),
    #[error("quic connect: {0}")]
    TransportConnect(io::Error),
    #[error("timeout {0}")]
    Timeout(&'static str),
    #[error("relay handshake: {0}")]
    RelayHandshake(#[from] QuicWgRelayHandshakeError),
    #[error("wireguard handshake: {0}")]
    WireguardHandshake(QuicWgWireguardHandshakeError),
    #[error("first pong: {0}")]
    FirstPong(QuicWgReceiveError),
}

#[derive(Debug, Error)]
pub enum QuicWgRelayHandshakeError {
    #[error("could not open control stream: {0}")]
    ControlStreamInitError(quinn::ConnectionError),
    #[error("could not receive message from control stream: {0}")]
    ControlStreamMessageReceiveError(io::Error),
    #[error("could not read protocol identifier from control stream: {0}")]
    ProtocolIdentifierReceiveFailed(io::Error),
    #[error("timeout {0}")]
    Timeout(&'static str),
    #[error("relay sent unexpected protocol indentifier: {0:#034x}")]
    UnexpectedProtocolIdentifierReceived(u128),
    #[error("could not write to control stream: {0}")]
    ControlStreamWriteError(io::Error),
    #[error("received {0}")]
    ReceivedErrorResponse(RelayErrorResponse),
}

#[derive(Debug, Error)]
#[error("relay error response code {error_code}: {message}")]
pub struct RelayErrorResponse {
    error_code: NonZeroU32,
    message: String,
}

#[derive(Debug, Error)]
#[error("unexpected relay op code {0:?}")]
pub struct UnexpectedOpCode(RelayOpCode);

impl RelayErrorResponse {
    pub fn new(error_code: NonZeroU32, message: &[u8]) -> Self {
        Self { error_code, message: String::from_utf8_lossy(message).into() }
    }
}

#[derive(Debug, Error)]
pub enum QuicWgWireguardHandshakeError {
    #[error("could not construct inititialization message")]
    InitMessageConstructError,
    #[error("could not send inititialization message: {0}")]
    InitMessageSendError(io::Error),
    #[error("could not receive response message: {0}")]
    RespMessageReceiveError(io::Error),
    #[error("response timeout")]
    RespMessageTimeout,
}

pub struct QuicWgConn {
    wg_state: Mutex<WgState>,
    wg_sender: WgSender,
    wg_receiver: WgReceiver,
    client_public_key: PublicKey,
    exit_public_key: PublicKey,
    _tcp_tls_sender_abort: Option<AbortOnDrop>,
    _quic_control_stream: Option<(quinn::SendStream, quinn::RecvStream)>,
    /// Keep this field last: fields drop in order, so it drops after the data path stopped (Ombra patch P5).
    _close_guard: Option<Box<dyn Any + Send + Sync>>,
}

#[derive(Clone, Copy, Debug)]
pub struct QuicWgTrafficStats {
    pub connected_at: Instant,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub latest_latency_ms: u16,
}

struct WgState {
    buffer: Vec<u8>,
    tx_arena: BytesArena,
    rx_arena: BytesArena,
    next_wg_timers_tick: Instant,
    next_liveness_poll: WakeInstant,
    tick_stats: TickStats,
    traffic_stats: QuicWgTrafficStats,
    wg: Tunn,
    liveness_checker: LivenessChecker,
    fragmenter: WgMessageFragmenter,
    /// `None` in Speed mode: plain WireGuard over UDP has no fragments (Ombra patch P5). There a received datagram is
    /// a slice of a 64 KiB arena chunk, which a buffered fragment would keep alive.
    fragment_buffer: Option<WgFragmentBuffer>,
}

impl WgState {
    /// Encrypts `packet` for the exit and adds the WireGuard message to `wg_messages`, as two fragments when it does
    /// not fit one QUIC datagram. The message is written into the arena, so it needs no copy (C3).
    fn encapsulate(&mut self, packet: &[u8], max_wg_message_size: Option<u16>, wg_messages: &mut WgMessages) {
        let space = packet.len().saturating_add(WG_DATA_MESSAGE_OVERHEAD).max(WG_ENCAPSULATE_MIN_SPACE);
        let wg_message_len = match self.wg.encapsulate(packet, self.tx_arena.space(space)) {
            TunnResult::WriteToNetwork(wg_message) => {
                if let Some(header) = WgControlMessageHeader::parse(wg_message) {
                    header.log(WgMessageDirection::Sent);
                }
                wg_message.len()
            }
            TunnResult::Done => {
                tracing::error!(message_id = "10g8g1D1", "WG encapsulate did not yield a datagram to send");
                return;
            }
            TunnResult::Err(error) => {
                tracing::warn!(message_id = "MAvGA9tf", ?error, "wireguard error");
                return;
            }
            TunnResult::WriteToTunnelV4(_, _) | TunnResult::WriteToTunnelV6(_, _) => {
                tracing::error!(message_id = "mOwsH8Eu", "WG encapsulate yielded a received ip packet");
                return;
            }
        };
        let wg_message = self.tx_arena.take(wg_message_len);
        self.tick_stats.wg_tx_count += 1;
        let (first, second) = match max_wg_message_size {
            Some(max_size) => self.fragmenter.fragment(wg_message, max_size),
            None => (wg_message, None),
        };
        self.tick_stats.wg_tx_fragmented_count += u64::from(second.is_some());
        wg_messages.extend(once(first).chain(second));
    }
}

/// What a WireGuard call produced, after `QuicWgConn::handle_result` sent messages for the network.
enum WgOutput {
    Done,
    MessageSent,
    /// An IP packet for the TUN, of this length, at the start of the destination buffer.
    Packet(usize),
}

/// The WireGuard messages of one send call, without a heap allocation for up to 64 of them.
type WgMessages = SmallVec<[Bytes; 64]>;

#[derive(Clone, Copy, Debug, Default)]
struct TickStats {
    ip_tx_count: Saturating<u64>,
    wg_tx_count: Saturating<u64>,
    wg_tx_fragmented_count: Saturating<u64>,
    ip_rx_count: Saturating<u64>,
    wg_rx_count: Saturating<u64>,
    wg_rx_fragment_buffered_count: Saturating<u64>,
    wg_rx_fragment_reassembled_count: Saturating<u64>,
    wg_rx_fragment_max_message_size: Option<u16>,
    min_ip_tx_size: Option<usize>,
    max_ip_tx_size: Option<usize>,
    min_ip_rx_size: Option<usize>,
    max_ip_rx_size: Option<usize>,
}

#[derive(Debug, Display, Eq, PartialEq, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum TransportKind {
    Quic,
    TcpTls,
    /// Ombra patch P5.
    WireguardUdp,
}

enum Received {
    Packet(Bytes),
    Pong,
}

#[derive(Clone)]
pub struct QuicWgConnPacketSender(Weak<QuicWgConn>);

impl QuicWgConnPacketSender {
    pub fn new(conn: Option<&Arc<QuicWgConn>>) -> Self {
        Self(conn.map(Arc::downgrade).unwrap_or_default())
    }

    pub fn send<'a>(&self, packets: impl Iterator<Item = &'a [u8]>) {
        if let Some(conn) = self.0.upgrade() {
            conn.send(packets)
        }
    }
}

impl QuicWgConn {
    pub async fn connect(
        relay_handshaking: QuicWgConnHandshaking,
        client_secret_key: StaticSecret,
        exit_public_key: PublicKey,
        client_ip_v4: Ipv4Addr,
        ping_target_ip_v4: Ipv4Addr,
        token: Uuid,
    ) -> Result<Self, QuicWgConnectError> {
        let mtu_floor = relay_handshaking.mtu_floor();
        let transport = relay_handshaking.authenticate(token).await?.into_wg_transport();
        tracing::info!(message_id = "UROUZerU", "completed handshake with relay");
        Self::connect_over(
            transport,
            client_secret_key,
            exit_public_key,
            client_ip_v4,
            ping_target_ip_v4,
            mtu_floor.then_some(TUNNEL_MTU),
            WG_FIRST_HANDSHAKE,
        )
        .await
    }

    /// Speed mode (Ombra patch P5): plain WireGuard over UDP to `endpoint`, the relay port of an API tunnel. There is no
    /// relay handshake and no token: the relay knows the tunnel by its port. Messages that do not fit the socket's send
    /// buffer are dropped, as WireGuard does, and none is fragmented.
    pub async fn connect_udp(
        endpoint: SocketAddrV4,
        network_interface: Option<&NetworkInterface>,
        client_secret_key: StaticSecret,
        exit_public_key: PublicKey,
        client_ip_v4: Ipv4Addr,
        ping_target_ip_v4: Ipv4Addr,
    ) -> Result<Self, QuicWgConnectError> {
        let relay = RelayUdpSocket::connect(endpoint, network_interface)
            .await
            .map_err(QuicWgConnectError::TransportConnect)?;
        let relay = Arc::new(relay);
        tracing::info!(message_id = "Gm4tUq7c", %endpoint, "starting WireGuard handshake over UDP");
        let transport = WgTransport {
            sender: WgSender::Udp { relay: relay.clone(), dropped: AtomicU64::new(0) },
            receiver: WgReceiver::Udp { relay, arena: Default::default() },
            quic_control_stream: None,
            tcp_tls_sender_abort: None,
        };
        Self::connect_over(
            transport,
            client_secret_key,
            exit_public_key,
            client_ip_v4,
            ping_target_ip_v4,
            None,
            WG_UDP_FIRST_HANDSHAKE,
        )
        .await
    }

    /// The WireGuard handshake with the exit over `transport`, then the first liveness pong.
    async fn connect_over(
        transport: WgTransport,
        client_secret_key: StaticSecret,
        exit_public_key: PublicKey,
        client_ip_v4: Ipv4Addr,
        ping_target_ip_v4: Ipv4Addr,
        large_probe_mtu: Option<u16>,
        first_handshake: FirstHandshake,
    ) -> Result<Self, QuicWgConnectError> {
        let client_public_key = PublicKey::from(&client_secret_key);
        let WgTransport { sender: mut wg_sender, receiver: mut wg_receiver, quic_control_stream, tcp_tls_sender_abort } = transport;
        let index = random();
        let mut wg = Tunn::new(client_secret_key, exit_public_key, None, None, index, None).unwrap();
        Self::first_wg_handshake(&mut wg, &mut wg_sender, &mut wg_receiver, first_handshake)
            .await
            .map_err(QuicWgConnectError::WireguardHandshake)?;
        tracing::info!(message_id = "TJ4nH30h", "connected to exit");
        let now = Instant::now();
        let wg_state = Mutex::new(WgState {
            wg,
            traffic_stats: QuicWgTrafficStats { connected_at: now, tx_bytes: 0, rx_bytes: 0, latest_latency_ms: 0 },
            buffer: vec![0u8; usize::from(u16::MAX)],
            tx_arena: BytesArena::default(),
            rx_arena: BytesArena::default(),
            next_wg_timers_tick: now + WG_TIMER_TICK,
            next_liveness_poll: WakeInstant::now(),
            liveness_checker: LivenessChecker::new(LIVENESS_MTU, large_probe_mtu, client_ip_v4, ping_target_ip_v4),
            tick_stats: Default::default(),
            fragmenter: Default::default(),
            fragment_buffer: wg_receiver
                .has_fragments()
                .then(|| WgFragmentBuffer::new(WG_FRAGMENT_BUFFER_LEN, WG_FRAGMENT_MAX_SIZE)),
        });
        let conn = Self {
            wg_receiver,
            wg_sender,
            wg_state,
            client_public_key,
            exit_public_key,
            _tcp_tls_sender_abort: tcp_tls_sender_abort,
            _quic_control_stream: quic_control_stream,
            _close_guard: None,
        };
        conn.wait_for_first_pong().await.map_err(QuicWgConnectError::FirstPong)?;
        Ok(conn)
    }

    /// `guard` is dropped with the connection, after its data path stopped: after the socket of the Speed mode closed,
    /// so that nothing is sent to a deleted API tunnel (Ombra patch P5).
    pub fn with_close_guard(mut self, guard: impl Any + Send + Sync) -> Self {
        self._close_guard = Some(Box::new(guard));
        self
    }

    async fn wait_for_first_pong(&self) -> Result<(), QuicWgReceiveError> {
        loop {
            match self.receive_packet_or_pong().await? {
                Received::Pong => return Ok(()),
                Received::Packet(packet) => {
                    tracing::warn!(message_id = "fP3mWq8Z", len = packet.len(), "dropping packet received before first pong")
                }
            }
        }
    }

    fn build_first_wg_handshake_init(wg: &mut Tunn) -> Result<Bytes, QuicWgWireguardHandshakeError> {
        let mut buf = vec![0u8; usize::from(u16::MAX)];
        let data = match wg.format_handshake_initiation(&mut buf, true) {
            TunnResult::WriteToNetwork(data) => Bytes::copy_from_slice(data),
            _ => return Err(QuicWgWireguardHandshakeError::InitMessageConstructError),
        };
        Ok(data)
    }

    async fn wait_for_first_handshake_response(
        wg: &mut Tunn,
        wg_receiver: &mut WgReceiver,
        wg_sender: &WgSender,
    ) -> Result<(), QuicWgWireguardHandshakeError> {
        let mut buf = vec![0u8; usize::from(u16::MAX)];
        timeout(WG_FIRST_HANDSHAKE_TIMEOUT, async {
            while wg.time_since_last_handshake().is_none() {
                let mut datagram = wg_receiver
                    .receive_wg_message()
                    .await
                    .map_err(QuicWgWireguardHandshakeError::RespMessageReceiveError)?;
                if let Some(header) = WgControlMessageHeader::parse(&datagram) {
                    header.log(WgMessageDirection::Received);
                }
                loop {
                    let res = wg.decapsulate(None, &datagram, &mut buf);
                    match Self::handle_result(wg_sender, res) {
                        WgOutput::MessageSent => {
                            datagram.truncate(0);
                            continue;
                        }
                        WgOutput::Packet(_) => tracing::warn!(message_id = "d8pzbt5Z", "unexpected packet during first WG handshake"),
                        WgOutput::Done => break,
                    }
                }
            }
            Ok(())
        })
        .await
        .map_err(|_| QuicWgWireguardHandshakeError::RespMessageTimeout)?
    }

    async fn first_wg_handshake(
        wg: &mut Tunn,
        wg_sender: &mut WgSender,
        wg_receiver: &mut WgReceiver,
        first_handshake: FirstHandshake,
    ) -> Result<(), QuicWgWireguardHandshakeError> {
        let mut handshake_init = Self::build_first_wg_handshake_init(wg)?;
        let mut resends = first_handshake.sends;
        let mut sends_of_initiation = 0;
        loop {
            resends -= 1;
            if sends_of_initiation == first_handshake.sends_per_initiation {
                handshake_init = Self::build_first_wg_handshake_init(wg)?;
                sends_of_initiation = 0;
            }
            sends_of_initiation += 1;
            if let Some(header) = WgControlMessageHeader::parse(&handshake_init) {
                header.log(WgMessageDirection::Sent);
            }
            wg_sender.send_wg_message(handshake_init.clone());
            match Self::wait_for_first_handshake_response(wg, wg_receiver, wg_sender).await {
                Ok(()) => return Ok(()),
                Err(err) => match err {
                    QuicWgWireguardHandshakeError::RespMessageTimeout => {
                        tracing::info!(message_id = "dHFvpzvl", "exit handshake timeout, packet may have gotten lost");
                        if resends == 0 {
                            tracing::info!(
                                message_id = "ZvQp8VQQ",
                                "too many exit handshake resend attempts, exit may not be configured"
                            );
                            break;
                        }
                    }
                    err => return Err(err),
                },
            }
        }
        Err(QuicWgWireguardHandshakeError::RespMessageTimeout)
    }

    /// Sends a message that WireGuard wrote for the network.
    fn handle_result(wg_sender: &WgSender, res: TunnResult<'_>) -> WgOutput {
        match res {
            TunnResult::Done => WgOutput::Done,
            TunnResult::WriteToNetwork(wg_message) => {
                if let Some(header) = WgControlMessageHeader::parse(wg_message) {
                    header.log(WgMessageDirection::Sent);
                }
                wg_sender.send_wg_message(Bytes::copy_from_slice(wg_message));
                WgOutput::MessageSent
            }
            TunnResult::WriteToTunnelV4(packet, ..) | TunnResult::WriteToTunnelV6(packet, ..) => WgOutput::Packet(packet.len()),
            TunnResult::Err(error) => {
                tracing::warn!(message_id = "uQ0xQcPP", ?error, "wireguard error");
                WgOutput::Done
            }
        }
    }

    /// Sends the packets that WireGuard queued while it had no session, after a handshake.
    fn send_queued_packets(wg: &mut Tunn, buffer: &mut [u8], wg_sender: &WgSender, tick_stats: &mut TickStats) {
        // Decapsulating nothing gives only messages for the network.
        while let WgOutput::MessageSent = Self::handle_result(wg_sender, wg.decapsulate(None, &[], buffer)) {
            tick_stats.wg_tx_count += 1;
        }
    }

    /// Ombra patch P4: `wg_state` is locked once per batch of packets (C1), and the messages go to quinn after it is
    /// unlocked (C2), so that the receive path does not wait for quinn's connection lock.
    fn send<'a>(&self, packets: impl Iterator<Item = &'a [u8]>) {
        let max_wg_message_size = self.wg_sender.max_wg_message_size();
        let mut wg_messages = WgMessages::new();
        {
            let mut wg_state = self.wg_state.lock().unwrap();
            if let Some(probe) = wg_state.liveness_checker.sent_traffic() {
                wg_state.encapsulate(&probe, max_wg_message_size, &mut wg_messages);
            }
            for packet in packets {
                wg_state.traffic_stats.tx_bytes += usize_into_u64(packet.len());
                wg_state.tick_stats.ip_tx_count += 1;
                wg_state.tick_stats.min_ip_tx_size = Some(wg_state.tick_stats.min_ip_tx_size.unwrap_or(usize::MAX).min(packet.len()));
                wg_state.tick_stats.max_ip_tx_size = Some(wg_state.tick_stats.max_ip_tx_size.unwrap_or(0).max(packet.len()));
                wg_state.encapsulate(packet, max_wg_message_size, &mut wg_messages);
            }
        }
        self.wg_sender.send_wg_messages(wg_messages);
    }

    pub fn wake(&self) {
        self.send_wake_probe(false)
    }

    /// Sends a liveness probe at once. Until a probe is answered, the tunnel counts as dead after 2 lost probes, as after
    /// a sleep. With `keepalive`, a WireGuard keepalive goes first.
    fn send_wake_probe(&self, keepalive: bool) {
        let max_wg_message_size = self.wg_sender.max_wg_message_size();
        let mut wg_messages = WgMessages::new();
        {
            let mut wg_state = self.wg_state.lock().unwrap();
            if keepalive {
                wg_state.encapsulate(&[], max_wg_message_size, &mut wg_messages);
            }
            let probe = wg_state.liveness_checker.wake();
            wg_state.encapsulate(&probe, max_wg_message_size, &mut wg_messages);
        }
        self.wg_sender.send_wg_messages(wg_messages);
    }

    /// Moves the tunnel to a new socket on `network_interface` after a network change (Ombra patch P4, D1 and D2). The
    /// relay, the exit and the tunnel addresses stay. A liveness probe goes out at once with the strictness after a
    /// wake, so when the relay does not follow the new address, the tunnel is dead in about 2 s and the tunnel loop
    /// connects again.
    pub async fn rebind(&self, network_interface: &NetworkInterface) -> io::Result<()> {
        match &self.wg_sender {
            // quinn sends a PING from the new address, with a new connection ID of the relay.
            WgSender::Quic { conn, .. } => conn.rebind(new_udp(Some(network_interface))?)?,
            WgSender::Udp { relay, .. } => relay.rebind(network_interface).await?,
            WgSender::TcpTls { .. } => {
                return Err(io::Error::new(
                    io::ErrorKind::Unsupported,
                    "a TCP connection cannot move to another network",
                ));
            }
        }
        // In Speed mode a WireGuard keepalive tells the relay the new address at once (D2).
        self.send_wake_probe(self.transport() == TransportKind::WireguardUdp);
        Ok(())
    }

    /// The MTU choices of a QUIC connection, `None` in the other modes.
    pub fn mtu_choices(&self) -> Option<MtuChoices> {
        match &self.wg_sender {
            WgSender::Quic { conn, .. } => Some(conn.mtu_choices()),
            WgSender::TcpTls { .. } | WgSender::Udp { .. } => None,
        }
    }

    pub async fn receive(&self) -> Result<Bytes, QuicWgReceiveError> {
        loop {
            match self.receive_packet_or_pong().await? {
                Received::Packet(packet) => return Ok(packet),
                Received::Pong => {}
            }
        }
    }

    async fn receive_packet_or_pong(&self) -> Result<Received, QuicWgReceiveError> {
        loop {
            let next_liveness_poll;
            let next_wg_timers_tick;
            {
                let wg_state = &mut *self.wg_state.lock().unwrap();
                next_liveness_poll = wg_state.next_liveness_poll;
                next_wg_timers_tick = wg_state.next_wg_timers_tick;
            }

            select! {
                biased;
                _ = sleep_until(next_wg_timers_tick) => {
                    let tick_stats = {
                        let wg_state = &mut *self.wg_state.lock().unwrap();
                        loop {
                            let timer_result = wg_state.wg.update_timers(&mut wg_state.buffer);
                            match Self::handle_result(&self.wg_sender, timer_result) {
                                WgOutput::MessageSent => continue,
                                WgOutput::Packet(_) => tracing::warn!(message_id = "nmuKdNnr", "unexpected packet during update_timers"),
                                WgOutput::Done => break,
                            }
                        }
                        wg_state.next_wg_timers_tick = Instant::now() + WG_TIMER_TICK;
                        mem::take(&mut wg_state.tick_stats)
                    };
                    self.log_datapath_stats(tick_stats);
                }
                result = self.wg_receiver.receive_wg_message() => {
                    let wg_message = result.map_err(QuicWgReceiveError::QuicReceiveError)?;
                    let mut wg_state = self.wg_state.lock().unwrap();
                    let WgState {buffer, rx_arena, wg, traffic_stats, tick_stats, liveness_checker, fragment_buffer, .. } = &mut *wg_state;
                    let wg_message = match reassemble(fragment_buffer.as_mut(), wg_message) {
                        ReassembleResult::NotFragmented(msg) => msg,
                        ReassembleResult::Reassembled(msg) => {
                            tick_stats.wg_rx_fragment_reassembled_count += 1;
                            msg
                        }
                        ReassembleResult::UnmatchedFragment { max_message_size } => {
                            tick_stats.wg_rx_fragment_buffered_count += 1;
                            tick_stats.wg_rx_fragment_max_message_size = Some(max_message_size);
                            continue;
                        }
                    };
                    tick_stats.wg_rx_count += 1;
                    if let Some(header) = WgControlMessageHeader::parse(&wg_message) {
                        header.log(WgMessageDirection::Received);
                    }
                    // A packet for the TUN is decrypted into the arena, so it reaches the TUN without a copy (C3). The space
                    // also fits the answer to a handshake message.
                    let res = wg.decapsulate(None, &wg_message, rx_arena.space(wg_message.len().max(WG_ENCAPSULATE_MIN_SPACE)));
                    if let TunnResult::WriteToNetwork(..) = &res {
                        tick_stats.wg_tx_count += 1;
                    }
                    match Self::handle_result(&self.wg_sender, res) {
                        WgOutput::Packet(len) => {
                            let packet = rx_arena.take(len);
                            liveness_checker.received_traffic();
                            tick_stats.ip_rx_count += 1;
                            tick_stats.min_ip_rx_size = Some(tick_stats.min_ip_rx_size.unwrap_or(usize::MAX).min(packet.len()));
                            tick_stats.max_ip_rx_size = Some(tick_stats.max_ip_rx_size.unwrap_or(0).max(packet.len()));
                            traffic_stats.rx_bytes += usize_into_u64(packet.len());
                            if let Some(latest_latency) = liveness_checker.process_potential_probe_response(&packet) {
                                traffic_stats.latest_latency_ms = u16::try_from(latest_latency.as_millis()).unwrap_or(u16::MAX);
                                return Ok(Received::Pong)
                            }
                            return Ok(Received::Packet(packet))
                        }
                        WgOutput::MessageSent => Self::send_queued_packets(wg, buffer, &self.wg_sender, tick_stats),
                        WgOutput::Done => {}
                    }
                }
                _ = sleep(next_liveness_poll.remaining()) => {
                    let max_wg_message_size = self.wg_sender.max_wg_message_size();
                    let mut wg_messages = WgMessages::new();
                    let poll = {
                        let wg_state = &mut *self.wg_state.lock().unwrap();
                        let poll = loop {
                            match wg_state.liveness_checker.poll() {
                                crate::liveness::LivenessCheckerPoll::Dead => break Err(QuicWgReceiveError::TunnelDead),
                                crate::liveness::LivenessCheckerPoll::LargeProbesLost => break Err(QuicWgReceiveError::LargePacketsLost),
                                crate::liveness::LivenessCheckerPoll::AliveUntil(pending_until) => break Ok(pending_until),
                                crate::liveness::LivenessCheckerPoll::SendPacket(probe) => {
                                    wg_state.encapsulate(&probe, max_wg_message_size, &mut wg_messages)
                                }
                            }
                        };
                        if let Ok(pending_until) = poll {
                            wg_state.next_liveness_poll = pending_until;
                        }
                        poll
                    };
                    self.wg_sender.send_wg_messages(wg_messages);
                    poll?;
                }
            }
        }
    }

    /// The 1 Hz data path stats, with quinn's path stats counted since the connection started (Ombra patch P4, A2).
    /// DEBUG, and never in the log files (C5). No addresses.
    fn log_datapath_stats(&self, tick_stats: TickStats) {
        if !tracing::enabled!(target: DATAPATH_STATS_TARGET, tracing::Level::DEBUG) {
            return;
        }
        let max_wg_message = self.wg_sender.max_wg_message_size();
        match &self.wg_sender {
            WgSender::Quic { conn, .. } => {
                let stats = conn.stats();
                tracing::debug!(
                    target: DATAPATH_STATS_TARGET,
                    message_id = "WKqFjXMA",
                    max_udp_payload = stats.path.current_mtu,
                    max_wg_message,
                    ?tick_stats,
                    rtt_ms = stats.path.rtt.as_millis(),
                    cwnd = stats.path.cwnd,
                    lost_packets = stats.path.lost_packets,
                    congestion_events = stats.path.congestion_events,
                    black_holes = stats.path.black_holes_detected,
                    mtu_probes_sent = stats.path.sent_plpmtud_probes,
                    mtu_probes_lost = stats.path.lost_plpmtud_probes,
                    udp_tx_datagrams = stats.udp_tx.datagrams,
                    udp_tx_ios = stats.udp_tx.ios,
                    udp_rx_datagrams = stats.udp_rx.datagrams,
                    udp_rx_ios = stats.udp_rx.ios,
                    acks_tx = stats.frame_tx.acks,
                    acks_rx = stats.frame_rx.acks,
                    datagram_send_buffer_space = conn.datagram_send_buffer_space(),
                );
            }
            WgSender::TcpTls { .. } => tracing::debug!(target: DATAPATH_STATS_TARGET, message_id = "WKqFjXMA", ?tick_stats),
            WgSender::Udp { dropped, .. } => tracing::debug!(
                target: DATAPATH_STATS_TARGET,
                message_id = "WKqFjXMA",
                ?tick_stats,
                udp_tx_dropped = dropped.load(Ordering::Relaxed),
            ),
        }
    }

    pub fn traffic_stats(&self) -> QuicWgTrafficStats {
        self.wg_state.lock().unwrap().traffic_stats
    }

    pub fn exit_public_key(&self) -> PublicKey {
        self.exit_public_key
    }

    pub fn client_public_key(&self) -> PublicKey {
        self.client_public_key
    }

    pub fn transport(&self) -> TransportKind {
        match self.wg_sender {
            WgSender::Quic { .. } => TransportKind::Quic,
            WgSender::TcpTls { .. } => TransportKind::TcpTls,
            WgSender::Udp { .. } => TransportKind::WireguardUdp,
        }
    }
}

/// Speed mode (Ombra patch P5): the socket to the relay port of the API tunnel. A network change replaces it (P4, D2).
struct RelayUdpSocket {
    endpoint: SocketAddrV4,
    socket: watch::Sender<Arc<UdpSocket>>,
}

impl RelayUdpSocket {
    async fn connect(endpoint: SocketAddrV4, network_interface: Option<&NetworkInterface>) -> io::Result<Self> {
        let socket = connected_udp_socket(endpoint, network_interface).await?;
        Ok(Self { endpoint, socket: watch::channel(socket).0 })
    }

    fn current(&self) -> Arc<UdpSocket> {
        self.socket.borrow().clone()
    }

    /// Sends from a new socket on `network_interface`. The relay answers the address that sent last.
    async fn rebind(&self, network_interface: &NetworkInterface) -> io::Result<()> {
        let socket = connected_udp_socket(self.endpoint, Some(network_interface)).await?;
        self.socket.send_replace(socket);
        Ok(())
    }

    /// The next datagram from the relay, from the current socket. The arena is never locked across an await.
    async fn receive(&self, arena: &Mutex<BytesArena>) -> io::Result<Bytes> {
        loop {
            let socket = self.current();
            if let Some(datagram) = try_receive_udp(&socket, arena)? {
                return Ok(datagram);
            }
            // A rebind ends the wait: the old socket gets nothing more.
            let mut rebinds = self.socket.subscribe();
            if Arc::ptr_eq(&rebinds.borrow_and_update(), &socket) {
                select! {
                    readable = socket.readable() => readable?,
                    _ = rebinds.changed() => {}
                }
            }
        }
    }
}

/// A UDP socket with the buffers of P4 B6, connected to `endpoint`, so that it receives only the relay's datagrams.
async fn connected_udp_socket(endpoint: SocketAddrV4, network_interface: Option<&NetworkInterface>) -> io::Result<Arc<UdpSocket>> {
    let socket = new_udp(network_interface)?;
    socket.set_nonblocking(true)?;
    let socket = UdpSocket::from_std(socket)?;
    socket.connect(endpoint).await?;
    // tokio sends only after its driver saw the socket writable. Before that, `try_send` would drop the first message.
    socket.writable().await?;
    Ok(Arc::new(socket))
}

/// An ICMP error that the kernel reported for an earlier datagram of a connected UDP socket, such as port unreachable
/// while the relay is not ready for a new tunnel yet. WireGuard ignores these: only the liveness check ends a tunnel.
fn is_udp_path_error(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::ConnectionRefused | io::ErrorKind::ConnectionReset | io::ErrorKind::HostUnreachable | io::ErrorKind::NetworkUnreachable
    )
}

/// The first WireGuard handshake: `sends` initiations, 100 ms apart. A new initiation replaces the old one after each
/// `sends_per_initiation` sends; the sends in between are copies.
#[derive(Clone, Copy, Debug)]
struct FirstHandshake {
    sends: usize,
    sends_per_initiation: usize,
}

pub struct QuicWgConnHandshaking {
    relay_id: String,
    port: u16,
    transport: Transport,
    mtu_floor: bool,
}

impl QuicWgConnHandshaking {
    pub async fn start_quic(
        relay_id: String,
        quic_endpoint: &RelayEndpoint,
        relay_addr: SocketAddr,
        relay_cert: CertificateDer<'static>,
        relay_sni: &str,
        quic_tuning: QuicTuning,
    ) -> Result<Self, QuicWgConnectError> {
        let port = relay_addr.port();
        tracing::info!(
            message_id = "AYsfThUG",
            port = port,
            relay.id = relay_id,
            ?quic_tuning,
            "starting quic wg relay handshake",
        );
        let quic_config = Self::quic_config(relay_cert, &quic_tuning).map_err(QuicWgConnectError::CryptoConfig)?;
        let connecting = quic_endpoint
            .endpoint
            .connect_with(quic_config.clone(), relay_addr, relay_sni)
            .map_err(QuicWgConnectError::QuicConfig)?;
        let connection = timeout(QUIC_STEP_TIMEOUT, connecting)
            .await
            .map_err(|_| QuicWgConnectError::Timeout("quic connect"))?
            .inspect_err(|error| RelayConnection::connect_failed(&quic_tuning, error))
            .map_err(io::Error::other)
            .map_err(QuicWgConnectError::TransportConnect)?;
        let connection = RelayConnection::new(connection, quic_tuning, quic_endpoint.clone());
        let (send, recv) = timeout(QUIC_STEP_TIMEOUT, connection.open_bi())
            .await
            .map_err(|_| QuicWgRelayHandshakeError::Timeout("opening control stream"))?
            .map_err(QuicWgRelayHandshakeError::ControlStreamInitError)?;
        let mut this = Self {
            relay_id,
            port,
            transport: Transport::Quic { conn: connection, send, recv },
            mtu_floor: quic_tuning.mtu == MtuPolicy::Floor,
        };
        this.exchange_protocol_identifiers().await?;
        Ok(this)
    }

    pub async fn start_tcp_tls(
        relay_id: String,
        network_interface: Option<&NetworkInterface>,
        relay_addr: SocketAddr,
        relay_cert: CertificateDer<'static>,
        relay_sni: &str,
    ) -> Result<Self, QuicWgConnectError> {
        let tcp_socket = new_nonblocking_tcp(network_interface).map_err(QuicWgConnectError::TransportConnect)?;
        let tcp_stream = timeout(QUIC_STEP_TIMEOUT, tcp_socket.connect(relay_addr))
            .await
            .map_err(|_| QuicWgConnectError::Timeout("tcp connect"))?
            .map_err(QuicWgConnectError::TransportConnect)?;
        if let Err(error) = tcp_stream.set_nodelay(true) {
            tracing::warn!(message_id = "k9KRCm3G", ?error, "failed to set tcp nodelay");
        }
        let tls_connector = Self::tcp_tls_config(relay_cert).map_err(QuicWgConnectError::CryptoConfig)?;
        let server_name = relay_sni
            .to_string()
            .try_into()
            .map_err(Into::into)
            .map_err(QuicWgConnectError::CryptoConfig)?;
        let tls_stream = timeout(QUIC_STEP_TIMEOUT, tls_connector.connect(server_name, tcp_stream))
            .await
            .map_err(|_| QuicWgConnectError::Timeout("tls handshake"))?
            .map_err(QuicWgConnectError::TransportConnect)?;
        let mut this = Self { relay_id, port: relay_addr.port(), transport: Transport::TcpTls(tls_stream), mtu_floor: false };
        this.exchange_protocol_identifiers().await?;
        Ok(this)
    }

    async fn exchange_protocol_identifiers(&mut self) -> Result<(), QuicWgRelayHandshakeError> {
        let inner = self.exchange_protocol_identifiers_no_timeout();
        timeout(QUIC_STEP_TIMEOUT, inner)
            .await
            .map_err(|_| QuicWgRelayHandshakeError::Timeout("exchanging protocol identifiers"))
            .flatten()
    }

    async fn exchange_protocol_identifiers_no_timeout(&mut self) -> Result<(), QuicWgRelayHandshakeError> {
        let mut buffer = PROTOCOL_IDENTIFIER.to_be_bytes();
        match &mut self.transport {
            Transport::Quic { send, recv, .. } => {
                send.write_all(&buffer)
                    .await
                    .map_err(io::Error::other)
                    .map_err(QuicWgRelayHandshakeError::ControlStreamWriteError)?;
                recv.read_exact(&mut buffer)
                    .await
                    .map_err(io::Error::other)
                    .map_err(QuicWgRelayHandshakeError::ProtocolIdentifierReceiveFailed)?;
            }
            Transport::TcpTls(tls_stream) => {
                tls_stream
                    .write_all(&buffer)
                    .await
                    .map_err(QuicWgRelayHandshakeError::ControlStreamWriteError)?;
                tls_stream.flush().await.map_err(QuicWgRelayHandshakeError::ControlStreamWriteError)?;
                tls_stream
                    .read_exact(&mut buffer)
                    .await
                    .map_err(QuicWgRelayHandshakeError::ProtocolIdentifierReceiveFailed)?;
            }
        }
        let relay_protocol_identifier = u128::from_be_bytes(buffer);
        if relay_protocol_identifier != PROTOCOL_IDENTIFIER {
            return Err(QuicWgRelayHandshakeError::UnexpectedProtocolIdentifierReceived(relay_protocol_identifier));
        }
        Ok(())
    }

    /// Whether the QUIC connection keeps a UDP payload size of at least 1350 bytes (Ombra patch P4, B1).
    pub fn mtu_floor(&self) -> bool {
        self.mtu_floor
    }

    pub async fn measure_rtt(&mut self) -> Result<Duration, QuicWgConnectError> {
        let mut start_time = Instant::now();
        let mut min_rtt = Duration::MAX;
        for _ in 0..3 {
            self.send_op(RelayOpCode::Ping, &[]).await?;
            self.recv_ok_resp().await?;
            let end_time = Instant::now();
            if let Some(last_rtt) = end_time.checked_duration_since(start_time) {
                min_rtt = min_rtt.min(last_rtt);
            }
            start_time = end_time;
        }
        tracing::info!(
            message_id = "CyF9avyp",
            "relay {} port {} min rtt is {}ms",
            &self.relay_id,
            self.port,
            min_rtt.as_millis()
        );
        Ok(min_rtt)
    }

    async fn authenticate(mut self, token: Uuid) -> Result<Transport, QuicWgConnectError> {
        self.send_op(RelayOpCode::Token, token.as_bytes()).await?;
        self.recv_ok_resp().await?;
        tracing::info!(message_id = "3rOUXFti", "relay confirmed token");
        let Self { transport, .. } = self;
        Ok(transport)
    }

    async fn stop(&mut self) -> Result<(), QuicWgRelayHandshakeError> {
        tracing::info!(message_id = "eTR2QPCB", "sending stop op to relay {} port {}", &self.relay_id, &self.port,);
        self.send_op(RelayOpCode::Stop, &[]).await?;
        self.recv_ok_resp().await?;
        tracing::info!(message_id = "3BwlgMb7", "relay {} port {} confirmed stop", &self.relay_id, self.port);
        Ok(())
    }

    pub async fn abandon(mut self) {
        if let Err(error) = self.stop().await {
            tracing::warn!(message_id = "b0UeytEt", ?error, "error while abandoning handshake")
        } else {
            match &mut self.transport {
                Transport::Quic { send, .. } => {
                    _ = send.finish();
                    _ = send.stopped().await;
                }
                Transport::TcpTls(tls_stream) => {
                    _ = tls_stream.shutdown().await;
                }
            }
            drop(self);
        }
    }

    fn rustls_config(relay_cert: CertificateDer<'static>) -> Result<rustls::ClientConfig, anyhow::Error> {
        let default_provider = Arc::new(rustls::crypto::ring::default_provider());
        let crypto = rustls::ClientConfig::builder_with_provider(default_provider.clone())
            .with_safe_default_protocol_versions()?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(VerifyVpnServerCert { cert: relay_cert, provider: default_provider }))
            .with_no_client_auth();
        Ok(crypto)
    }

    fn tcp_tls_config(relay_cert: CertificateDer<'static>) -> Result<TlsConnector, anyhow::Error> {
        let mut crypto = Self::rustls_config(relay_cert)?;
        crypto.alpn_protocols = vec![b"h2".to_vec()];
        Ok(TlsConnector::from(Arc::new(crypto)))
    }

    fn quic_config(relay_cert: CertificateDer<'static>, quic_tuning: &QuicTuning) -> Result<ClientConfig, anyhow::Error> {
        let mut crypto = Self::rustls_config(relay_cert)?;
        crypto.alpn_protocols = vec![b"h3".to_vec()];
        let crypto = QuicClientConfig::try_from(crypto)?;
        let mut client_cfg = ClientConfig::new(Arc::new(crypto));
        let mut transport_config = quinn::TransportConfig::default();
        transport_config.max_concurrent_uni_streams(0u8.into());
        transport_config.max_concurrent_bidi_streams(0u8.into());
        transport_config.stream_receive_window(CONTROL_STREAM_WINDOW.into());
        transport_config.receive_window(CONTROL_STREAM_WINDOW.into());
        transport_config.send_window(CONTROL_STREAM_WINDOW.into());
        transport_config.max_idle_timeout(Some(QUIC_IDLE_TIMEOUT.try_into()?));
        // Keep BBR. Tunnel traffic is app-limited, so quinn's BBR stays in Startup and never limits it; Cubic as the
        // outer controller adds queueing delay and collapses under random loss (docs/research/perf-plan.md 1.7, B4).
        transport_config.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
        quic_tuning.apply_to(&mut transport_config);
        client_cfg.transport_config(Arc::new(transport_config));
        Ok(client_cfg)
    }

    async fn recv_ok_resp(&mut self) -> Result<(), QuicWgRelayHandshakeError> {
        let inner = self.recv_ok_resp_no_timeout();
        timeout(QUIC_STEP_TIMEOUT, inner)
            .await
            .map_err(|_| QuicWgRelayHandshakeError::Timeout("awaiting op response"))
            .flatten()
    }

    async fn recv_ok_resp_no_timeout(&mut self) -> Result<(), QuicWgRelayHandshakeError> {
        loop {
            let (message_code, context_id, arg) = match &mut self.transport {
                Transport::Quic { recv, .. } => recv_message(recv).await,
                Transport::TcpTls(tls_stream) => recv_message(tls_stream).await,
            }
            .map_err(QuicWgRelayHandshakeError::ControlStreamMessageReceiveError)?;
            let MessageCode::Response(response_code) = message_code else {
                tracing::warn!(
                    message_id = "xfums1F8",
                    ?message_code,
                    "ignoring unexpected relay initiated message during handshake"
                );
                continue;
            };
            if context_id != MessageContext::MIN_CLIENT_INITIATED {
                tracing::warn!(message_id = "EfCIJy4z", ?context_id, "ignoring response with non-zero context id",);
                continue;
            }
            match response_code {
                RelayResponseCode::Ok => {
                    if !arg.is_empty() {
                        tracing::warn!(message_id = "xd0PY4bH", "ignoring {} additional payload bytes on ok response", arg.len());
                    }
                }
                RelayResponseCode::Error(error_code) => {
                    return Err(QuicWgRelayHandshakeError::ReceivedErrorResponse(RelayErrorResponse::new(
                        error_code, &arg,
                    )));
                }
            }
            break Ok(());
        }
    }

    async fn send_op(&mut self, op: RelayOpCode, arg: &[u8]) -> Result<(), QuicWgRelayHandshakeError> {
        let inner = self.send_op_no_timeout(op, arg);
        timeout(QUIC_STEP_TIMEOUT, inner)
            .await
            .map_err(|_| QuicWgRelayHandshakeError::Timeout("sending op"))
            .flatten()
    }

    async fn send_op_no_timeout(&mut self, op: RelayOpCode, arg: &[u8]) -> Result<(), QuicWgRelayHandshakeError> {
        let op = MessageCode::Op(op);
        let context_id = MessageContext::MIN_CLIENT_INITIATED;
        match &mut self.transport {
            Transport::Quic { send, .. } => send_message(send, op, context_id, arg).await,
            Transport::TcpTls(tls_stream) => send_message(tls_stream, op, context_id, arg).await,
        }
        .map_err(QuicWgRelayHandshakeError::ControlStreamWriteError)
    }

    pub fn transport_kind(self) -> TransportKind {
        match self.transport {
            Transport::Quic { .. } => TransportKind::Quic,
            Transport::TcpTls(..) => TransportKind::TcpTls,
        }
    }
}

enum Transport {
    Quic {
        conn: RelayConnection,
        send: quinn::SendStream,
        recv: quinn::RecvStream,
    },
    TcpTls(TlsStream<TcpStream>),
}

/// How WireGuard messages reach the relay, with what must live as long as the connection.
struct WgTransport {
    sender: WgSender,
    receiver: WgReceiver,
    quic_control_stream: Option<(quinn::SendStream, quinn::RecvStream)>,
    tcp_tls_sender_abort: Option<AbortOnDrop>,
}

impl Transport {
    fn into_wg_transport(self) -> WgTransport {
        let tls_stream = match self {
            Transport::Quic { conn, send, recv } => {
                // Ombra patch P7: also without padding.
                let coalescer = DatagramCoalescer::spawn(quinn::Connection::clone(&conn));
                return WgTransport {
                    sender: WgSender::Quic { conn: conn.clone(), coalescer },
                    receiver: WgReceiver::Quic(conn),
                    quic_control_stream: Some((send, recv)),
                    tcp_tls_sender_abort: None,
                };
            }
            Transport::TcpTls(tls_stream) => tls_stream,
        };
        let (relay_read, relay_write) = io::split(tls_stream);

        let (traffic_state, traffic_state_watch) = watch::channel(WgTrafficState::default());
        let sender = WgSender::TcpTls { traffic_state: traffic_state.clone() };
        let receiver = WgReceiver::new_tcp_tls(traffic_state.clone(), relay_read);

        let abort_handle = spawn(async move {
            if let Err(error) = write_queued_messages(traffic_state, traffic_state_watch, relay_write).await {
                tracing::error!(message_id = "xKBeN0Jb", ?error, "ending tcp tls relay send loop due to send error");
            }
        })
        .abort_handle()
        .into();

        WgTransport { sender, receiver, quic_control_stream: None, tcp_tls_sender_abort: Some(abort_handle) }
    }
}

enum WgSender {
    Quic {
        conn: RelayConnection,
        /// Small messages share a packet (Ombra patch P4, E1; without padding P7).
        coalescer: DatagramCoalescer,
    },
    TcpTls {
        traffic_state: watch::Sender<WgTrafficState>,
    },
    /// Speed mode (Ombra patch P5).
    Udp {
        relay: Arc<RelayUdpSocket>,
        /// Messages that the socket did not take, since the connection started.
        dropped: AtomicU64,
    },
}

impl WgSender {
    fn max_wg_message_size(&self) -> Option<u16> {
        match self {
            WgSender::Quic { conn, .. } => conn.max_datagram_size().and_then(|s| u16::try_from(s).ok()),
            WgSender::TcpTls { .. } | WgSender::Udp { .. } => None,
        }
    }

    fn send_wg_message(&self, wg_message: Bytes) {
        self.send_wg_messages(once(wg_message));
    }

    fn send_wg_messages(&self, wg_messages: impl IntoIterator<Item = Bytes>) {
        match self {
            WgSender::Quic { coalescer, .. } => coalescer.send(wg_messages),
            WgSender::TcpTls { traffic_state } => {
                traffic_state.send_modify(|traffic_state| {
                    for wg_message in wg_messages {
                        if traffic_state.queued_packets.len() < 1000 {
                            traffic_state.queued_packets.push_back(wg_message);
                        }
                    }
                });
            }
            // Never waits: when the send buffer is full, the message is dropped, as WireGuard does, and the inner TCP
            // sees a loss. An ICMP error of an earlier datagram also drops one message.
            WgSender::Udp { relay, dropped } => {
                let socket = relay.current();
                for wg_message in wg_messages {
                    if socket.try_send(&wg_message).is_err() {
                        dropped.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
        }
    }
}

enum WgReceiver {
    Quic(RelayConnection),
    TcpTls {
        traffic_state: watch::Sender<WgTrafficState>,
        #[allow(clippy::type_complexity)]
        recv_message_stream: tokio::sync::Mutex<Pin<Box<dyn Stream<Item = Result<(MessageCode, MessageContext, Vec<u8>), io::Error>> + Send>>>,
    },
    /// Speed mode (Ombra patch P5). The datagrams go into the arena, so there is no allocation per message.
    Udp {
        relay: Arc<RelayUdpSocket>,
        arena: Mutex<BytesArena>,
    },
}

impl WgReceiver {
    /// Whether the relay can send a WireGuard message in two fragments.
    fn has_fragments(&self) -> bool {
        match self {
            WgReceiver::Quic(_) | WgReceiver::TcpTls { .. } => true,
            WgReceiver::Udp { .. } => false,
        }
    }

    fn new_tcp_tls(traffic_state: watch::Sender<WgTrafficState>, relay_read: ReadHalf<TlsStream<TcpStream>>) -> Self {
        let recv_message_stream = Box::pin(unfold(relay_read, |mut relay_read| async move {
            let item = recv_message(&mut relay_read).await;
            Some((item, relay_read))
        }));
        Self::TcpTls { traffic_state, recv_message_stream: tokio::sync::Mutex::new(recv_message_stream) }
    }

    async fn receive_wg_message(&self) -> io::Result<Bytes> {
        loop {
            return match self {
                WgReceiver::Quic(conn) => conn.read_datagram().await.map_err(io::Error::other),
                WgReceiver::TcpTls { traffic_state, recv_message_stream } => {
                    let (code, context_id, arg) = recv_message_stream.lock().await.next().await.unwrap()?;
                    let op_code = match code {
                        MessageCode::Op(op_code) => {
                            traffic_state.send_modify(|traffic_state| traffic_state.queued_oks.push_back(context_id));
                            op_code
                        }
                        MessageCode::Response(RelayResponseCode::Ok) => continue,
                        MessageCode::Response(RelayResponseCode::Error(error_code)) => {
                            return Err(io::Error::other(RelayErrorResponse::new(error_code, &arg)));
                        }
                    };
                    match op_code {
                        RelayOpCode::WireGuard => Ok(arg.into()),
                        op_code => Err(io::Error::other(UnexpectedOpCode(op_code))),
                    }
                }
                WgReceiver::Udp { relay, arena } => relay.receive(arena).await,
            };
        }
    }
}

/// Without a fragment buffer every message is whole: a message in the fragment format then fails to decrypt.
fn reassemble(fragment_buffer: Option<&mut WgFragmentBuffer>, wg_message: Bytes) -> ReassembleResult {
    match fragment_buffer {
        Some(fragment_buffer) => fragment_buffer.reassemble(wg_message),
        None => ReassembleResult::NotFragmented(wg_message),
    }
}

/// A datagram that the socket already has, or `None`.
fn try_receive_udp(socket: &UdpSocket, arena: &Mutex<BytesArena>) -> io::Result<Option<Bytes>> {
    let mut arena = arena.lock().unwrap();
    loop {
        match socket.try_recv(arena.space(UDP_RECEIVE_SPACE)) {
            Ok(len) => return Ok(Some(arena.take(len))),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(None),
            Err(error) if is_udp_path_error(&error) => {
                rate_limited_log!(
                    Duration::from_secs(5),
                    tracing::info!(message_id = "Vr8cKw2N", ?error, "ignoring an ICMP error from the path to the relay")
                );
            }
            Err(error) => return Err(error),
        }
    }
}

#[derive(Default)]
struct WgTrafficState {
    queued_oks: VecDeque<MessageContext>,
    queued_packets: VecDeque<Bytes>,
}

impl WgTrafficState {
    fn is_empty(&self) -> bool {
        self.queued_oks.is_empty() && self.queued_packets.is_empty()
    }
}

/// Writes the queued OKs and WireGuard messages to the relay, each batch in one buffer with one flush, so that TLS
/// makes few records and TCP few segments (Ombra patch P4, E2). The plaintext byte stream is the same as with one write
/// and flush per message. OKs go first, as upstream.
async fn write_queued_messages(
    traffic_state: watch::Sender<WgTrafficState>,
    mut traffic_state_watch: watch::Receiver<WgTrafficState>,
    mut relay_write: WriteHalf<TlsStream<TcpStream>>,
) -> io::Result<()> {
    let mut queued = WgTrafficState::default();
    let mut buffer = Vec::new();
    loop {
        // Taking the queue is no news for the watch. The emptied queues go back, with their capacity.
        traffic_state.send_if_modified(|traffic_state| {
            mem::swap(traffic_state, &mut queued);
            false
        });
        if queued.is_empty() {
            _ = traffic_state_watch.changed().await;
            continue;
        }
        buffer.clear();
        for context_id in queued.queued_oks.drain(..) {
            encode_message(&mut buffer, MessageCode::Response(RelayResponseCode::Ok), context_id, &[])?;
        }
        for packet in queued.queued_packets.drain(..) {
            encode_message(
                &mut buffer,
                MessageCode::Op(RelayOpCode::WireGuard),
                MessageContext::MIN_CLIENT_INITIATED,
                &packet,
            )?;
        }
        write_and_flush(&mut relay_write, &buffer).await?;
    }
}

#[derive(Debug, Error)]
#[error("message payload length {0} does not fit u32")]
struct PayloadTooLong(usize);

pub(crate) async fn send_message<T: AsyncWrite + Unpin>(
    transport: &mut T,
    code: MessageCode,
    context_id: MessageContext,
    arg: &[u8],
) -> Result<(), io::Error> {
    let mut buffer = Vec::with_capacity(12 + arg.len());
    encode_message(&mut buffer, code, context_id, arg)?;
    write_and_flush(transport, &buffer).await
}

fn encode_message(buffer: &mut Vec<u8>, code: MessageCode, context_id: MessageContext, arg: &[u8]) -> io::Result<()> {
    let payload_length = u32::try_from(4 + arg.len()).map_err(|_| io::Error::other(PayloadTooLong(arg.len())))?;
    buffer.extend_from_slice(&<[u8; 8]>::from(MessageHeader { context_id, payload_length }));
    buffer.extend_from_slice(&code.to_bytes());
    buffer.extend_from_slice(arg);
    Ok(())
}

async fn write_and_flush<T: AsyncWrite + Unpin>(transport: &mut T, bytes: &[u8]) -> io::Result<()> {
    transport.write_all(bytes).await?;
    transport.flush().await
}

async fn recv_skip<T: AsyncRead + Unpin>(transport: &mut T, mut n: usize) -> Result<(), io::Error> {
    let mut buffer = vec![0u8; u16::MAX.into()];
    while n >= buffer.len() {
        transport.read_exact(&mut buffer).await?;
        n -= buffer.len();
    }
    if n > 0 {
        transport.read_exact(&mut buffer[0..n]).await?;
    }
    Ok(())
}

pub(crate) async fn recv_message<T: AsyncRead + Unpin>(transport: &mut T) -> Result<(MessageCode, MessageContext, Vec<u8>), io::Error> {
    loop {
        let header = MessageHeader::from(recv_fixed::<8, _>(transport).await?);
        let len = header.payload_length_usize();
        if len < 4 || len > usize::from(u16::MAX) + 4 {
            tracing::warn!(message_id = "1gPHoHdA", len, "ignoring relay message with payload too small or large");
            recv_skip(transport, len).await?;
            continue;
        }
        let mut payload = vec![0u8; len];
        transport.read_exact(&mut payload).await?;
        let (code, arg) = payload.split_at_checked(4).unwrap();
        let code = code.try_into().unwrap_or([u8::MAX; 4]);
        let Some(code) = MessageCode::from_bytes(code, header.context_id, true) else {
            // Forward compatibility with future relay protocol changes
            tracing::warn!(message_id = "OK8fVfBL", "ignoring relay message with unknown op code");
            continue;
        };
        return Ok((code, header.context_id, arg.to_vec()));
    }
}

async fn recv_fixed<const N: usize, T: AsyncRead + Unpin>(transport: &mut T) -> Result<[u8; N], io::Error> {
    let mut buf = [0u8; N];
    transport.read_exact(&mut buf[..]).await?;
    Ok(buf)
}

#[derive(Debug)]
struct VerifyVpnServerCert {
    cert: CertificateDer<'static>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for VerifyVpnServerCert {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        match self.cert == *end_entity {
            true => Ok(ServerCertVerified::assertion()),
            false => Err(rustls::Error::InvalidCertificate(CertificateError::ApplicationVerificationFailure)),
        }
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constants::DEFAULT_RELAY_SNI;
    use crate::net::{new_quic, new_udp};
    use crate::test_relay::{CLIENT_TUNNEL_IP, EXIT_TUNNEL_IP, LossyPath, TestRelay, echo_reply, echo_request};
    use std::collections::BTreeSet;

    async fn start_quic(relay: &TestRelay, addr: SocketAddr, tuning: QuicTuning) -> QuicWgConnHandshaking {
        let endpoint = new_quic(new_udp(None).unwrap(), None, false).unwrap();
        QuicWgConnHandshaking::start_quic("test".into(), &endpoint, addr, relay.cert.clone(), DEFAULT_RELAY_SNI, tuning)
            .await
            .unwrap()
    }

    fn quic_connection(handshaking: &QuicWgConnHandshaking) -> &RelayConnection {
        let Transport::Quic { conn, .. } = &handshaking.transport else {
            unreachable!("started a QUIC handshake")
        };
        conn
    }

    /// The largest WireGuard message that the connection can send in one QUIC datagram.
    async fn max_wg_message_size(relay: &TestRelay, addr: SocketAddr, mtu: MtuPolicy) -> u16 {
        let handshaking = start_quic(relay, addr, QuicTuning { frame_padding: false, mtu, ack_frequency: false }).await;
        let conn = quic_connection(&handshaking);
        if mtu == MtuPolicy::Floor {
            assert_eq!(conn.stats().path.current_mtu, DEFAULT_UDP_PAYLOAD_SIZE);
        }
        u16::try_from(conn.max_datagram_size().unwrap()).unwrap()
    }

    #[tokio::test]
    async fn full_size_packet_fits_one_datagram_at_mtu_1350_with_an_8_byte_cid() {
        let relay = TestRelay::spawn();
        // A 1280-byte IP packet plus 32 bytes of WireGuard data message overhead.
        let full_size_message = Bytes::from(vec![0u8; usize::from(TUNNEL_MTU) + 32]);

        let floor = max_wg_message_size(&relay, relay.addr, MtuPolicy::Floor).await;
        assert_eq!(usize::from(floor), full_size_message.len(), "no slack at 1350 bytes with an 8-byte CID");
        let (_, second) = WgMessageFragmenter::default().fragment(full_size_message.clone(), floor);
        assert!(second.is_none());

        // Without the floor on a path below 1350 bytes, the same message is split in two.
        let small_path = LossyPath::small_mtu(relay.addr, 1300);
        let discovered = max_wg_message_size(&relay, small_path.addr, MtuPolicy::Discover).await;
        let (_, second) = WgMessageFragmenter::default().fragment(full_size_message, discovered);
        assert!(second.is_some());
    }

    #[tokio::test]
    async fn ack_frequency_request_reaches_a_quinn_relay() {
        let relay = TestRelay::spawn();
        for ack_frequency in [false, true] {
            let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency };
            let mut handshaking = start_quic(&relay, relay.addr, tuning).await;
            handshaking.measure_rtt().await.unwrap();
            let sent_requests = quic_connection(&handshaking).stats().frame_tx.ack_frequency;
            assert_eq!(sent_requests > 0, ack_frequency);
        }
    }

    async fn connect_over(relay: &TestRelay, handshaking: QuicWgConnHandshaking) -> QuicWgConn {
        let token = Uuid::new_v4();
        QuicWgConn::connect(
            handshaking,
            relay.client_secret_key(),
            relay.exit_public_key,
            CLIENT_TUNNEL_IP,
            EXIT_TUNNEL_IP,
            token,
        )
        .await
        .unwrap()
    }

    async fn connect(relay: &TestRelay, addr: SocketAddr, tuning: QuicTuning) -> QuicWgConn {
        connect_over(relay, start_quic(relay, addr, tuning).await).await
    }

    /// Sends the packets as one batch and returns the sequence numbers of the echo replies.
    async fn round_trip(conn: &QuicWgConn, packets: &[Vec<u8>]) -> BTreeSet<u16> {
        conn.send(packets.iter().map(Vec::as_slice));
        let mut replies = BTreeSet::new();
        while replies.len() < packets.len() {
            let reply = timeout(Duration::from_secs(5), conn.receive()).await.unwrap().unwrap();
            let request = packets.iter().find(|request| echo_reply(request).as_deref() == Some(&reply[..])).unwrap();
            replies.insert(u16::from_be_bytes([request[26], request[27]]));
        }
        replies
    }

    #[tokio::test]
    async fn a_batch_of_full_size_packets_round_trips_unfragmented_with_the_floor() {
        let relay = TestRelay::spawn();
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency: true };
        let conn = connect(&relay, relay.addr, tuning).await;
        let packets: Vec<Vec<u8>> = (0..100).map(|seq| echo_request(seq, usize::from(TUNNEL_MTU))).collect();
        assert_eq!(round_trip(&conn, &packets).await, (0..100).collect());
        let wg_state = conn.wg_state.lock().unwrap();
        assert_eq!(wg_state.tick_stats.wg_tx_fragmented_count.0, 0);
        assert_eq!(wg_state.tick_stats.wg_rx_fragment_reassembled_count.0, 0);
        assert!(wg_state.fragment_buffer.is_some());
    }

    #[tokio::test]
    async fn tcp_tls_round_trips_a_batch_and_answers_every_relay_op() {
        let relay = TestRelay::spawn();
        let handshaking = QuicWgConnHandshaking::start_tcp_tls("test".into(), None, relay.tcp_tls_addr, relay.cert.clone(), DEFAULT_RELAY_SNI)
            .await
            .unwrap();
        let conn = connect_over(&relay, handshaking).await;
        assert_eq!(conn.transport(), TransportKind::TcpTls);
        let packets: Vec<Vec<u8>> = (0..100).map(|seq| echo_request(seq, usize::from(TUNNEL_MTU))).collect();
        assert_eq!(round_trip(&conn, &packets).await, (0..100).collect());
        // The exit's handshake response, the first pong and the 100 replies came as relay ops.
        assert!(relay.tcp_tls_oks_for_all_relay_ops().await >= 102);
    }

    #[test]
    fn a_batch_is_the_byte_stream_of_one_message_at_a_time() {
        let wg_message = [7u8; 100];
        let messages = [
            (MessageCode::Response(RelayResponseCode::Ok), MessageContext::MIN_RELAY_INITIATED, &[][..]),
            (
                MessageCode::Op(RelayOpCode::WireGuard),
                MessageContext::MIN_CLIENT_INITIATED,
                &wg_message[..],
            ),
            (
                MessageCode::Op(RelayOpCode::WireGuard),
                MessageContext::MIN_CLIENT_INITIATED,
                &wg_message[..10],
            ),
        ];
        let mut batch = Vec::new();
        let mut one_at_a_time = Vec::new();
        for (code, context_id, arg) in messages {
            encode_message(&mut batch, code, context_id, arg).unwrap();
            // Upstream wrote the header, the code and the argument, each with its own write.
            let payload_length = u32::try_from(4 + arg.len()).unwrap();
            one_at_a_time.extend_from_slice(&<[u8; 8]>::from(MessageHeader { context_id, payload_length }));
            one_at_a_time.extend_from_slice(&code.to_bytes());
            one_at_a_time.extend_from_slice(arg);
        }
        assert_eq!(batch, one_at_a_time);
    }

    #[test]
    fn without_a_fragment_buffer_no_fragment_is_kept() {
        let message = Bytes::from(vec![4u8; usize::from(TUNNEL_MTU) + 32]);
        let (first, second) = WgMessageFragmenter::default().fragment(message.clone(), 1200);
        let mut fragment_buffer = WgFragmentBuffer::new(WG_FRAGMENT_BUFFER_LEN, WG_FRAGMENT_MAX_SIZE);
        assert!(matches!(
            reassemble(Some(&mut fragment_buffer), first.clone()),
            ReassembleResult::UnmatchedFragment { .. }
        ));
        assert!(matches!(
            reassemble(Some(&mut fragment_buffer), second.unwrap()),
            ReassembleResult::Reassembled(whole) if whole == message
        ));
        assert!(matches!(reassemble(None, first.clone()), ReassembleResult::NotFragmented(kept) if kept == first));
    }

    /// A UDP packet from the client that the test exit drops, so that nothing comes back.
    fn udp_packet(len: usize) -> Vec<u8> {
        let builder = etherparse::PacketBuilder::ipv4(CLIENT_TUNNEL_IP.octets(), EXIT_TUNNEL_IP.octets(), 64).udp(40000, 9);
        let payload = vec![0u8; len - builder.size(0)];
        let mut packet = Vec::new();
        builder.write(&mut packet, &payload).unwrap();
        packet
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn with_padding_small_packets_that_follow_closely_share_a_full_size_packet() {
        let relay = TestRelay::spawn();
        let tuning = QuicTuning { frame_padding: true, mtu: MtuPolicy::Floor, ack_frequency: false };
        let conn = connect(&relay, relay.addr, tuning).await;
        let WgSender::Quic { conn: quic, .. } = &conn.wg_sender else {
            unreachable!("connected over QUIC")
        };
        let packet = udp_packet(100);
        sleep(Duration::from_millis(20)).await;
        let before = quic.stats().udp_tx;
        // Separate TUN reads within 2 ms, like inner TCP ACKs during a download, while the quinn driver runs on the
        // other worker. No sleep between them: on a busy machine a sleep can take longer than the 2 ms.
        for _ in 0..5 {
            conn.send(once(packet.as_slice()));
        }
        sleep(Duration::from_millis(20)).await;
        let after = quic.stats().udp_tx;
        // The first one goes at once after the quiet moment, the next four share a packet. On a busy machine the first
        // packet can be built late enough to carry all five.
        let datagrams = after.datagrams - before.datagrams;
        assert!((1..=2).contains(&datagrams), "{datagrams} datagrams");
        assert_eq!(after.bytes - before.bytes, datagrams * u64::from(DEFAULT_UDP_PAYLOAD_SIZE), "padded");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn without_padding_small_packets_that_follow_closely_share_a_packet() {
        let relay = TestRelay::spawn();
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency: false };
        let conn = connect(&relay, relay.addr, tuning).await;
        let WgSender::Quic { conn: quic, .. } = &conn.wg_sender else {
            unreachable!("connected over QUIC")
        };
        let packet = udp_packet(100);
        sleep(Duration::from_millis(20)).await;
        let before = quic.stats().udp_tx;
        for _ in 0..5 {
            conn.send(once(packet.as_slice()));
        }
        sleep(Duration::from_millis(20)).await;
        let after = quic.stats().udp_tx;
        // The first one goes at once after the quiet moment, the next four share a packet. On a busy machine the first
        // packet can be built late enough to carry all five.
        let datagrams = after.datagrams - before.datagrams;
        assert!((1..=2).contains(&datagrams), "{datagrams} datagrams");
        assert!(after.bytes - before.bytes < 2 * u64::from(DEFAULT_UDP_PAYLOAD_SIZE), "not padded");
    }

    /// Collects the field names of the data path stats events.
    struct DatapathStatsFields(Arc<Mutex<Vec<&'static str>>>);

    impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for DatapathStatsFields {
        fn on_event(&self, event: &tracing::Event<'_>, _ctx: tracing_subscriber::layer::Context<'_, S>) {
            if event.metadata().target() == DATAPATH_STATS_TARGET {
                self.0.lock().unwrap().extend(event.metadata().fields().iter().map(|field| field.name()));
            }
        }
    }

    #[tokio::test]
    async fn datapath_stats_carry_quinn_path_stats_when_enabled() {
        use tracing_subscriber::layer::SubscriberExt;
        let relay = TestRelay::spawn();
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency: false };
        let conn = connect(&relay, relay.addr, tuning).await;
        let fields = Arc::new(Mutex::new(Vec::new()));
        let subscriber = tracing_subscriber::registry().with(DatapathStatsFields(fields.clone()));
        tracing::subscriber::with_default(subscriber, || conn.log_datapath_stats(TickStats::default()));
        let fields = fields.lock().unwrap();
        for field in [
            "tick_stats",
            "max_udp_payload",
            "rtt_ms",
            "cwnd",
            "black_holes",
            "acks_rx",
            "datagram_send_buffer_space",
        ] {
            assert!(fields.contains(&field), "{field} missing in {fields:?}");
        }
    }

    async fn connect_udp(relay: &TestRelay, endpoint: SocketAddr) -> Result<QuicWgConn, QuicWgConnectError> {
        let SocketAddr::V4(endpoint) = endpoint else {
            unreachable!("the test relay listens on IPv4")
        };
        QuicWgConn::connect_udp(
            endpoint,
            None,
            relay.client_secret_key(),
            relay.exit_public_key,
            CLIENT_TUNNEL_IP,
            EXIT_TUNNEL_IP,
        )
        .await
    }

    #[tokio::test]
    async fn udp_round_trips_a_batch_of_1420_byte_packets_unfragmented() {
        let relay = TestRelay::spawn();
        let conn = connect_udp(&relay, relay.udp_addr).await.unwrap();
        assert_eq!(conn.transport(), TransportKind::WireguardUdp);
        assert_eq!(conn.wg_sender.max_wg_message_size(), None);
        let packets: Vec<Vec<u8>> = (0..100).map(|seq| echo_request(seq, 1420)).collect();
        assert_eq!(round_trip(&conn, &packets).await, (0..100).collect());
        assert_eq!(conn.wg_state.lock().unwrap().tick_stats.wg_tx_fragmented_count.0, 0);
        assert!(conn.wg_state.lock().unwrap().fragment_buffer.is_none(), "Speed mode has no fragments");
        let WgSender::Udp { dropped, .. } = &conn.wg_sender else {
            unreachable!("connected over UDP")
        };
        assert_eq!(dropped.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn udp_handshake_without_an_answer_fails_after_about_5_s() {
        let relay = TestRelay::spawn();
        // A closed port: the kernel answers with ICMP port unreachable, which must not end the handshake early.
        let closed = std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap();
        let started = Instant::now();
        let error = connect_udp(&relay, closed).await.err().unwrap();
        let elapsed = started.elapsed();
        assert!(
            matches!(
                error,
                QuicWgConnectError::WireguardHandshake(QuicWgWireguardHandshakeError::RespMessageTimeout)
            ),
            "{error:?}"
        );
        assert!(elapsed >= Duration::from_millis(4500) && elapsed < Duration::from_secs(8), "{elapsed:?}");
    }

    /// The exit drops copies of an initiation that it already answered, so a lost response needs a new initiation.
    #[tokio::test]
    async fn udp_handshake_survives_lost_responses() {
        let relay = TestRelay::spawn();
        let path = LossyPath::losing_first_answers(relay.udp_addr, 2);
        let started = Instant::now();
        let conn = connect_udp(&relay, path.addr).await.unwrap();
        assert!(started.elapsed() < Duration::from_millis(3500), "{:?}", started.elapsed());
        let packets = [echo_request(1, 1420)];
        assert_eq!(round_trip(&conn, &packets).await, [1].into());
    }

    /// The socket of a Speed mode connection.
    fn udp_socket(conn: &QuicWgConn) -> Arc<UdpSocket> {
        let WgSender::Udp { relay, .. } = &conn.wg_sender else {
            unreachable!("connected over UDP")
        };
        relay.current()
    }

    fn local_addr(conn: &QuicWgConn) -> SocketAddr {
        match &conn.wg_sender {
            WgSender::Quic { conn, .. } => conn.local_addr(),
            WgSender::Udp { .. } => udp_socket(conn).local_addr().unwrap(),
            WgSender::TcpTls { .. } => unreachable!("connected over UDP"),
        }
    }

    /// The network after a change: the loopback interface of macOS, where the sockets are bound to the interface.
    fn loopback() -> NetworkInterface {
        NetworkInterface {
            name: "lo0".into(),
            index: crate::positive_u31::PositiveU31::try_from(1).unwrap(),
            network_handle: 0,
            mtu_hint: None,
        }
    }

    /// Receives for 3 s. A tunnel whose probe after a move got no answer would be dead by then.
    async fn stays_alive(conn: &QuicWgConn) -> bool {
        timeout(Duration::from_secs(3), conn.receive()).await.is_err()
    }

    /// Receives until the liveness check fails, and returns how long that took.
    async fn time_until_dead(conn: &QuicWgConn) -> Duration {
        let started = Instant::now();
        let error = timeout(Duration::from_secs(10), conn.receive()).await.unwrap().unwrap_err();
        assert!(matches!(error, QuicWgReceiveError::TunnelDead), "{error:?}");
        started.elapsed()
    }

    /// Ombra patch P4 (D1): after a network change the QUIC tunnel goes on from a new socket, on the same connection.
    #[tokio::test]
    async fn quic_tunnel_moves_to_a_new_socket() {
        let relay = TestRelay::spawn();
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency: false };
        let conn = connect(&relay, relay.addr, tuning).await;
        assert_eq!(conn.mtu_choices(), Some(MtuChoices { floor: true, small_udp_payload: false }));
        let before = local_addr(&conn);
        conn.rebind(&loopback()).await.unwrap();
        assert_ne!(local_addr(&conn), before);
        let packets: Vec<Vec<u8>> = (0..10).map(|seq| echo_request(seq, usize::from(TUNNEL_MTU))).collect();
        assert_eq!(round_trip(&conn, &packets).await, (0..10).collect());
        assert!(stays_alive(&conn).await);
    }

    /// Ombra patch P4 (D1): when the relay ignores the new address, the tunnel is dead in about 2 s, so the tunnel loop
    /// connects again.
    #[tokio::test]
    async fn quic_tunnel_is_dead_soon_when_the_relay_ignores_the_new_socket() {
        let relay = TestRelay::spawn();
        let path = LossyPath::first_client_only(relay.addr);
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency: false };
        let conn = connect(&relay, path.addr, tuning).await;
        conn.rebind(&loopback()).await.unwrap();
        let elapsed = time_until_dead(&conn).await;
        assert!(elapsed < Duration::from_millis(3500), "{elapsed:?}");
    }

    /// Ombra patch P4 (D2): after a network change the Speed mode tunnel goes on from a new socket, with the same
    /// WireGuard session.
    #[tokio::test]
    async fn udp_tunnel_moves_to_a_new_socket() {
        let relay = TestRelay::spawn();
        let conn = connect_udp(&relay, relay.udp_addr).await.unwrap();
        assert_eq!(conn.mtu_choices(), None);
        let before = local_addr(&conn);
        conn.rebind(&loopback()).await.unwrap();
        assert_ne!(local_addr(&conn), before);
        let packets = [echo_request(1, 1420)];
        assert_eq!(round_trip(&conn, &packets).await, [1].into());
        assert!(stays_alive(&conn).await);
    }

    /// Ombra patch P4 (D2): when the relay ignores the new address, the tunnel is dead in about 2 s, and the tunnel loop
    /// makes a new API tunnel.
    #[tokio::test]
    async fn udp_tunnel_is_dead_soon_when_the_relay_ignores_the_new_socket() {
        let relay = TestRelay::spawn();
        let path = LossyPath::client_from_wireguard_handshake(relay.udp_addr);
        let conn = connect_udp(&relay, path.addr).await.unwrap();
        conn.rebind(&loopback()).await.unwrap();
        let elapsed = time_until_dead(&conn).await;
        assert!(elapsed < Duration::from_millis(3500), "{elapsed:?}");
    }

    /// Records whether the socket was already closed when it was dropped.
    struct SocketClosedCheck(std::sync::Weak<UdpSocket>, Arc<std::sync::atomic::AtomicBool>);

    impl Drop for SocketClosedCheck {
        fn drop(&mut self) {
            self.1.store(self.0.upgrade().is_none(), Ordering::Relaxed);
        }
    }

    #[tokio::test]
    async fn close_guard_drops_after_the_udp_socket_closed() {
        let relay = TestRelay::spawn();
        let conn = connect_udp(&relay, relay.udp_addr).await.unwrap();
        let socket = Arc::downgrade(&udp_socket(&conn));
        let socket_closed = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let conn = conn.with_close_guard(SocketClosedCheck(socket, socket_closed.clone()));
        drop(conn);
        assert!(socket_closed.load(Ordering::Relaxed));
    }

    #[tokio::test]
    async fn fragmented_packets_round_trip_on_a_small_path() {
        let relay = TestRelay::spawn();
        let small_path = LossyPath::small_mtu(relay.addr, 1300);
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Discover, ack_frequency: false };
        let conn = connect(&relay, small_path.addr, tuning).await;
        let packets: Vec<Vec<u8>> = (0..20).map(|seq| echo_request(seq, usize::from(TUNNEL_MTU))).collect();
        assert_eq!(round_trip(&conn, &packets).await, (0..20).collect());
        assert!(conn.wg_state.lock().unwrap().tick_stats.wg_tx_fragmented_count.0 > 0);
    }
}
