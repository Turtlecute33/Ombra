//! QUIC transport settings for the connection to the entry relay (Ombra patch P4, `docs/research/perf-plan.md` section
//! 2 B). The settings that upstream uses unchanged stay in `QuicWgConnHandshaking::quic_config`.

use crate::net::RelayEndpoint;
use crate::quicwg::{DEFAULT_UDP_PAYLOAD_SIZE, IPV4_UDP_OVERHEAD};
use quinn::{AckFrequencyConfig, ConnectionError, MtuDiscoveryConfig, TransportConfig, TransportErrorCode, VarInt};
use static_assertions::const_assert;
use std::ops::Deref;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

/// The smallest interface MTU that carries a full 1350-byte UDP payload over IPv4.
const MIN_INTERFACE_MTU_FOR_LARGE_PAYLOAD: u16 = DEFAULT_UDP_PAYLOAD_SIZE + IPV4_UDP_OVERHEAD;

/// The QUIC minimum and quinn's default start value.
const QUIC_MIN_UDP_PAYLOAD_SIZE: u16 = 1200;

/// Without the floor, quinn-proto 0.11.19 can take a congestion loss of full-size packets for a black hole
/// (`connection/mtud.rs` `on_non_probe_acked`). Upstream waits 10 s before it searches again and 600 s after a failed
/// search, and each full-size packet is split in two for that time.
const BLACK_HOLE_COOLDOWN: Duration = Duration::from_secs(2);
const MTU_DISCOVERY_INTERVAL: Duration = Duration::from_secs(60);

/// quinn's default is 1 MiB, which holds 1.7 s of data on a 5 Mbit/s uplink before the oldest datagram is dropped.
const DATAGRAM_SEND_BUFFER_SIZE: usize = 256 * 1024;

/// The ACK frequency request (B2): the relay ACKs every 4th ack-eliciting packet, at most 5 ms late, instead of every
/// 2nd. Fewer ACKs for our uploads means less work for the single quinn driver. quinn sends the request only when the
/// relay announced support (`min_ack_delay`), and the ClientHello does not change.
///
/// The threshold stays below quinn BBR's smallest window of 4 packets, its window in ProbeRTT. With a threshold of 10, a
/// window of 4 packets waits for the 5 ms timer before its ACK, BBR then measures the timer instead of the path, and on
/// a short path the window stays at 4 packets: on loopback the tunnel ran at 8 to 18 Mbit/s for seconds
/// (`rust/PERF.md`).
const ACK_ELICITING_THRESHOLD: u32 = 3;
const MAX_ACK_DELAY: Duration = Duration::from_millis(5);
/// `calculate_min_window` of quinn-proto 0.11 `congestion/bbr`: 4 packets.
const BBR_MIN_WINDOW_PACKETS: u32 = 4;
const_assert!(ACK_ELICITING_THRESHOLD < BBR_MIN_WINDOW_PACKETS);

static ACK_FREQUENCY: AckFrequencySwitch = AckFrequencySwitch::new();

/// How the QUIC connection chooses its UDP payload size (the "MTU" in quinn).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MtuPolicy {
    /// Fixed at 1350 bytes, so the MTU never falls below what a full-size WireGuard message needs (B1). The Initial
    /// packets are padded to 1350 bytes, so a path that cannot carry them fails the handshake instead of a live
    /// tunnel.
    Floor,
    /// Starts at 1350 bytes, and black hole detection can lower it. The fallback when the floor did not connect.
    StartLarge,
    /// Starts at 1200 bytes and discovers the path MTU, for a small interface MTU, `forceSmallMtu`, or a path that
    /// lost full-size packets with the floor.
    Discover,
}

impl MtuPolicy {
    /// The policy of the first relay race on the current network. `interface_mtu` is `None` when it is not known.
    pub fn for_path(interface_mtu: Option<u16>, force_small_mtu: bool, floor_failed: bool) -> Self {
        let interface_fits_large_payload = interface_mtu.is_none_or(|mtu| mtu >= MIN_INTERFACE_MTU_FOR_LARGE_PAYLOAD);
        if interface_fits_large_payload && !force_small_mtu && !floor_failed {
            Self::Floor
        } else {
            Self::Discover
        }
    }

    /// The policy of the second relay race when no relay answered with this one (guard 1).
    pub fn fallback(self) -> Option<Self> {
        match self {
            Self::Floor => Some(Self::StartLarge),
            Self::StartLarge | Self::Discover => None,
        }
    }

    fn initial_mtu(self) -> u16 {
        match self {
            Self::Floor | Self::StartLarge => DEFAULT_UDP_PAYLOAD_SIZE,
            Self::Discover => QUIC_MIN_UDP_PAYLOAD_SIZE,
        }
    }

    fn min_mtu(self) -> u16 {
        match self {
            Self::Floor => DEFAULT_UDP_PAYLOAD_SIZE,
            Self::StartLarge | Self::Discover => QUIC_MIN_UDP_PAYLOAD_SIZE,
        }
    }
}

/// Whether the endpoint asks the relays for UDP payloads of at most 1200 bytes: on a small interface MTU or with
/// `forceSmallMtu`, as upstream.
pub fn small_udp_payload(interface_mtu: Option<u16>, force_small_mtu: bool) -> bool {
    force_small_mtu || interface_mtu.is_some_and(|mtu| mtu < MIN_INTERFACE_MTU_FOR_LARGE_PAYLOAD)
}

/// What a QUIC relay connection chose for the network's MTU when it was made: the floor (B1) and the small UDP payload.
/// It moves to a new network only when a new connection there would choose the same (D1).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MtuChoices {
    pub floor: bool,
    pub small_udp_payload: bool,
}

impl MtuChoices {
    /// The choices of a new connection on a network with this interface MTU.
    pub fn for_path(interface_mtu: Option<u16>, force_small_mtu: bool, floor_failed: bool) -> Self {
        Self {
            floor: MtuPolicy::for_path(interface_mtu, force_small_mtu, floor_failed) == MtuPolicy::Floor,
            small_udp_payload: small_udp_payload(interface_mtu, force_small_mtu),
        }
    }
}

/// The QUIC settings that the client chooses for one relay connection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct QuicTuning {
    pub frame_padding: bool,
    pub mtu: MtuPolicy,
    /// Ask the relay for fewer ACKs. See `ack_frequency_enabled`.
    pub ack_frequency: bool,
}

impl QuicTuning {
    pub fn apply_to(&self, transport_config: &mut TransportConfig) {
        transport_config.initial_mtu(self.mtu.initial_mtu());
        transport_config.min_mtu(self.mtu.min_mtu());
        // With the floor, discovery has nothing left to find: the upper bound is the start value.
        let mut mtu_discovery_config = MtuDiscoveryConfig::default();
        mtu_discovery_config.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);
        mtu_discovery_config.black_hole_cooldown(BLACK_HOLE_COOLDOWN);
        mtu_discovery_config.interval(MTU_DISCOVERY_INTERVAL);
        transport_config.mtu_discovery_config(Some(mtu_discovery_config));
        transport_config.datagram_send_buffer_size(DATAGRAM_SEND_BUFFER_SIZE);
        transport_config.pad_to_mtu(self.frame_padding);
        if self.ack_frequency {
            let mut ack_frequency_config = AckFrequencyConfig::default();
            ack_frequency_config.ack_eliciting_threshold(VarInt::from_u32(ACK_ELICITING_THRESHOLD));
            ack_frequency_config.max_ack_delay(Some(MAX_ACK_DELAY));
            transport_config.ack_frequency_config(Some(ack_frequency_config));
        }
    }
}

/// Whether new relay connections ask for fewer ACKs (B2). On at start. Off for the rest of the process life once a
/// connection that asked for it closed with a QUIC transport error, because a relay may not handle the request.
pub fn ack_frequency_enabled() -> bool {
    ACK_FREQUENCY.is_on()
}

struct AckFrequencySwitch(AtomicBool);

impl AckFrequencySwitch {
    const fn new() -> Self {
        Self(AtomicBool::new(true))
    }

    fn is_on(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }

    /// Call only for connections that asked for fewer ACKs.
    fn connection_closed(&self, reason: &ConnectionError) {
        if is_transport_error(reason) && self.0.swap(false, Ordering::Relaxed) {
            tracing::warn!(
                message_id = "Vt8sQe3L",
                %reason,
                "relay connection closed with a QUIC transport error, no longer asking relays for fewer ACKs"
            );
        }
    }
}

/// The peer broke the QUIC protocol, or closed the connection because it found that we did.
fn is_transport_error(reason: &ConnectionError) -> bool {
    match reason {
        ConnectionError::TransportError(_) => true,
        ConnectionError::ConnectionClosed(close) => close.error_code != TransportErrorCode::NO_ERROR,
        ConnectionError::VersionMismatch
        | ConnectionError::ApplicationClosed(_)
        | ConnectionError::Reset
        | ConnectionError::TimedOut
        | ConnectionError::LocallyClosed
        | ConnectionError::CidsExhausted => false,
    }
}

/// A QUIC connection to a relay. Each place that gives up on the connection drops its handle, so the drop checks how
/// the connection closed: this covers the relay race, the relay and WireGuard handshakes and the live tunnel.
#[derive(Clone)]
pub struct RelayConnection {
    conn: quinn::Connection,
    quic_tuning: QuicTuning,
    endpoint: RelayEndpoint,
}

impl RelayConnection {
    pub fn new(conn: quinn::Connection, quic_tuning: QuicTuning, endpoint: RelayEndpoint) -> Self {
        Self { conn, quic_tuning, endpoint }
    }

    pub fn quic_tuning(&self) -> &QuicTuning {
        &self.quic_tuning
    }

    pub fn mtu_choices(&self) -> MtuChoices {
        MtuChoices { floor: self.quic_tuning.mtu == MtuPolicy::Floor, small_udp_payload: self.endpoint.small_udp_payload }
    }

    /// Moves the endpoint, and with it this connection, to `socket` (D1). quinn then uses a new connection ID of the
    /// relay and sends a PING from the new address.
    pub fn rebind(&self, socket: std::net::UdpSocket) -> std::io::Result<()> {
        self.endpoint.endpoint.rebind(socket)
    }

    #[cfg(test)]
    pub fn local_addr(&self) -> std::net::SocketAddr {
        self.endpoint.endpoint.local_addr().unwrap()
    }

    /// For a connection attempt that failed before there was a connection.
    pub fn connect_failed(quic_tuning: &QuicTuning, reason: &ConnectionError) {
        if quic_tuning.ack_frequency {
            ACK_FREQUENCY.connection_closed(reason);
        }
    }
}

impl Deref for RelayConnection {
    type Target = quinn::Connection;

    fn deref(&self) -> &quinn::Connection {
        &self.conn
    }
}

impl Drop for RelayConnection {
    fn drop(&mut self) {
        if self.quic_tuning.ack_frequency
            && let Some(reason) = self.conn.close_reason()
        {
            ACK_FREQUENCY.connection_closed(&reason);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn floor_only_when_the_interface_carries_large_payloads() {
        assert_eq!(MtuPolicy::for_path(None, false, false), MtuPolicy::Floor);
        assert_eq!(MtuPolicy::for_path(Some(1500), false, false), MtuPolicy::Floor);
        assert_eq!(MtuPolicy::for_path(Some(1378), false, false), MtuPolicy::Floor);
        assert_eq!(MtuPolicy::for_path(Some(1377), false, false), MtuPolicy::Discover);
        assert_eq!(MtuPolicy::for_path(Some(1280), false, false), MtuPolicy::Discover);
        assert_eq!(MtuPolicy::for_path(Some(1500), true, false), MtuPolicy::Discover);
        assert_eq!(MtuPolicy::for_path(None, true, false), MtuPolicy::Discover);
        // Guard 2 found that this path drops full-size packets.
        assert_eq!(MtuPolicy::for_path(Some(1500), false, true), MtuPolicy::Discover);
    }

    #[test]
    fn only_the_floor_has_a_fallback_race() {
        assert_eq!(MtuPolicy::Floor.fallback(), Some(MtuPolicy::StartLarge));
        assert_eq!(MtuPolicy::StartLarge.fallback(), None);
        assert_eq!(MtuPolicy::Discover.fallback(), None);
    }

    #[test]
    fn ack_frequency_goes_off_for_good_after_a_transport_error() {
        let switch = AckFrequencySwitch::new();
        let closed_by_relay =
            |error_code| ConnectionError::ConnectionClosed(quinn::ConnectionClose { error_code, frame_type: None, reason: Default::default() });
        for reason in [
            ConnectionError::TimedOut,
            ConnectionError::LocallyClosed,
            ConnectionError::Reset,
            ConnectionError::ApplicationClosed(quinn::ApplicationClose { error_code: VarInt::from_u32(1), reason: Default::default() }),
            closed_by_relay(TransportErrorCode::NO_ERROR),
        ] {
            switch.connection_closed(&reason);
            assert!(switch.is_on(), "{reason}");
        }
        switch.connection_closed(&closed_by_relay(TransportErrorCode::FRAME_ENCODING_ERROR));
        assert!(!switch.is_on());
        switch.connection_closed(&ConnectionError::TimedOut);
        assert!(!switch.is_on());

        let switch = AckFrequencySwitch::new();
        switch.connection_closed(&ConnectionError::TransportError(TransportErrorCode::PROTOCOL_VIOLATION.into()));
        assert!(!switch.is_on());
    }

    #[test]
    fn mtu_choices_of_each_path() {
        let choices = |floor, small_udp_payload| MtuChoices { floor, small_udp_payload };
        assert_eq!(MtuChoices::for_path(Some(1500), false, false), choices(true, false));
        assert_eq!(MtuChoices::for_path(None, false, false), choices(true, false));
        assert_eq!(MtuChoices::for_path(Some(1378), false, false), choices(true, false));
        // A small MTU, also from the CLAT-aware hint.
        assert_eq!(MtuChoices::for_path(Some(1377), false, false), choices(false, true));
        assert_eq!(MtuChoices::for_path(Some(1500), true, false), choices(false, true));
        // Guard 2 found that the path drops full-size packets: no floor, but full-size payloads are allowed.
        assert_eq!(MtuChoices::for_path(Some(1500), false, true), choices(false, false));
    }

    #[test]
    fn mtu_bounds_of_each_policy() {
        let bounds = |policy: MtuPolicy| (policy.initial_mtu(), policy.min_mtu());
        assert_eq!(bounds(MtuPolicy::Floor), (1350, 1350));
        assert_eq!(bounds(MtuPolicy::StartLarge), (1350, 1200));
        assert_eq!(bounds(MtuPolicy::Discover), (1200, 1200));
    }
}
