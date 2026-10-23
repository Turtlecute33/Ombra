//! Network handover (Ombra patch P4, `docs/research/perf-plan.md` section 2, D1 and D2): when only the network changes,
//! a connected tunnel moves to a socket on the new network instead of a full reconnect. The inner addresses, the exit
//! and the TUN stay, so app connections survive.

use crate::net::NetworkInterface;
use crate::quic_tuning::MtuChoices;
use crate::quicwg::TransportKind;

/// What a connected tunnel does when the network interface changed and nothing else in the target state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Handover {
    /// The same network, only the MTU hint changed, and the tunnel needs nothing else.
    Keep,
    /// A new socket on the new network, then a probe at once. Without an answer the tunnel is dead in about 2 s.
    Rebind,
    /// A full reconnect, as upstream after every network change.
    Reconnect,
}

impl Handover {
    /// `conn_mtu`: the MTU choices of the QUIC connection (`None` in the other modes). `new_mtu`: the choices that a
    /// new QUIC connection would make on the new network.
    pub fn decide(
        transport: TransportKind,
        conn_mtu: Option<MtuChoices>,
        new_mtu: MtuChoices,
        old: &NetworkInterface,
        new: &NetworkInterface,
    ) -> Self {
        if transport == TransportKind::Quic && conn_mtu != Some(new_mtu) {
            // The floor or the small UDP payload would differ: a new connection chooses them for this network.
            return Self::Reconnect;
        }
        if old.same_network(new) {
            return Self::Keep;
        }
        match transport {
            TransportKind::Quic | TransportKind::WireguardUdp => Self::Rebind,
            // A TCP connection cannot move to another network.
            TransportKind::TcpTls => Self::Reconnect,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::positive_u31::PositiveU31;

    const LARGE: MtuChoices = MtuChoices { floor: true, small_udp_payload: false };
    const SMALL: MtuChoices = MtuChoices { floor: false, small_udp_payload: true };
    /// No floor after the fallback race or guard 2, but full-size payloads.
    const NO_FLOOR: MtuChoices = MtuChoices { floor: false, small_udp_payload: false };

    fn network(name: &str, network_handle: u64, mtu_hint: Option<u16>) -> NetworkInterface {
        NetworkInterface { name: name.into(), index: PositiveU31::try_from(5).unwrap(), network_handle, mtu_hint }
    }

    #[test]
    fn quic_moves_to_another_network_with_the_same_mtu_choices() {
        let wifi = network("wlan0", 100, Some(1500));
        let cellular = network("rmnet0", 200, Some(1500));
        let decide = |conn_mtu, new_mtu, new: &NetworkInterface| Handover::decide(TransportKind::Quic, Some(conn_mtu), new_mtu, &wifi, new);
        assert_eq!(decide(LARGE, LARGE, &cellular), Handover::Rebind);
        assert_eq!(decide(SMALL, SMALL, &cellular), Handover::Rebind);
        // The same interface name, but another network (another Wi-Fi).
        assert_eq!(decide(LARGE, LARGE, &network("wlan0", 300, Some(1500))), Handover::Rebind);
        // Only the hint changed and the choices stay.
        assert_eq!(decide(LARGE, LARGE, &network("wlan0", 100, Some(1480))), Handover::Keep);
    }

    #[test]
    fn quic_reconnects_when_the_mtu_choices_change() {
        let wifi = network("wlan0", 100, Some(1500));
        let decide = |conn_mtu, new_mtu, new: &NetworkInterface| Handover::decide(TransportKind::Quic, Some(conn_mtu), new_mtu, &wifi, new);
        // CLAT starts on the same network and its MTU is too small for the floor.
        assert_eq!(decide(LARGE, SMALL, &network("wlan0", 100, Some(1280))), Handover::Reconnect);
        assert_eq!(decide(LARGE, SMALL, &network("rmnet0", 200, Some(1280))), Handover::Reconnect);
        // A network that allows the floor again.
        assert_eq!(decide(SMALL, LARGE, &network("rmnet0", 200, None)), Handover::Reconnect);
        assert_eq!(decide(NO_FLOOR, LARGE, &network("rmnet0", 200, None)), Handover::Reconnect);
        // The relay would send full-size payloads to a small-MTU network.
        assert_eq!(decide(NO_FLOOR, SMALL, &network("rmnet0", 200, Some(1280))), Handover::Reconnect);
    }

    #[test]
    fn speed_mode_moves_to_any_network_and_ignores_the_mtu() {
        let wifi = network("wlan0", 100, Some(1500));
        let decide = |new: &NetworkInterface| Handover::decide(TransportKind::WireguardUdp, None, SMALL, &wifi, new);
        assert_eq!(decide(&network("rmnet0", 200, Some(1280))), Handover::Rebind);
        assert_eq!(decide(&network("wlan0", 100, Some(1280))), Handover::Keep);
    }

    #[test]
    fn tcp_tls_reconnects_on_another_network() {
        let wifi = network("wlan0", 100, Some(1500));
        let decide = |new: &NetworkInterface| Handover::decide(TransportKind::TcpTls, None, LARGE, &wifi, new);
        assert_eq!(decide(&network("rmnet0", 200, None)), Handover::Reconnect);
        assert_eq!(decide(&network("wlan0", 100, Some(1400))), Handover::Keep);
    }

    #[test]
    fn without_network_handles_the_interface_tells_the_network() {
        let en0 = network("en0", 0, None);
        let decide = |new: &NetworkInterface| Handover::decide(TransportKind::Quic, Some(LARGE), LARGE, &en0, new);
        assert_eq!(decide(&network("en1", 0, None)), Handover::Rebind);
        assert_eq!(decide(&network("en0", 0, Some(1500))), Handover::Keep);
    }
}
