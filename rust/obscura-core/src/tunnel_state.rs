use futures::future::pending;
use obscuravpn_api::types::{OneExit, OneRelay};
use std::convert::Infallible;
use std::ops::ControlFlow;
use std::time::Duration;
use std::{future::Future, sync::Arc};
use strum::EnumIs;
use tokio::select;
use tokio::sync::Notify;
use tokio::sync::watch::{Receiver, Sender, channel};
use tokio::time::{Instant, sleep, sleep_until};
use uuid::Uuid;

use crate::client_state::ClientStateHandle;
use crate::config::{LocalNetworkAccess, TailscaleBypass, WireGuardBypass};
use crate::errors::{ErrorAt, TunnelConnectError};
use crate::exit_selection::ExitSelectionState;
use crate::handover::Handover;
use crate::manager::ManagerTrafficStats;
use crate::net::NetworkInterface;
use crate::network_config::{DnsContentBlock, OsNetworkConfig, TunnelNetworkConfig};
use crate::os::os_trait::Os;
use crate::quicwg::{QuicWgConnPacketSender, QuicWgReceiveError, QuicWgTrafficStats};
use crate::relay_selection::RelayTransport;
use crate::udp_tunnel::handshake_retry_delay;
use crate::{client_state::ClientState, manager::TunnelArgs, quicwg::QuicWgConn};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TargetState {
    pub tunnel_args: Option<TunnelArgs>,
    pub network_interface: Option<NetworkInterface>,
    pub dns_content_block: DnsContentBlock,
    pub use_system_dns: bool,
    pub local_network_access: LocalNetworkAccess,
    pub tailscale_bypass: TailscaleBypass,
    pub wireguard_bypass: WireGuardBypass,
    pub relay_transport: RelayTransport,
    pub os_network_config_refresh: u64,
}

#[derive(derive_more::Debug, EnumIs)]
pub enum TunnelState {
    Disconnected,
    Connecting {
        args: TunnelArgs,
        connect_error: Option<ErrorAt<TunnelConnectError>>,
        disconnect_reason: Option<ErrorAt<QuicWgReceiveError>>,
        offset_traffic_stats: ManagerTrafficStats,
        network_interface: Option<NetworkInterface>,
    },
    Connected {
        args: TunnelArgs,
        tunnel_id: Uuid,
        #[debug(skip)]
        conn: Arc<QuicWgConn>,
        network_config: TunnelNetworkConfig,
        relay: OneRelay,
        exit: OneExit,
        offset_traffic_stats: ManagerTrafficStats,
        network_interface: NetworkInterface,
        relay_transport: RelayTransport,
    },
}

struct Connected {
    conn: Arc<QuicWgConn>,
    exit: OneExit,
    network_config: TunnelNetworkConfig,
    relay: OneRelay,
    tunnel_id: Uuid,
}

impl TunnelState {
    /// The constructed `TunnelState` can not be dropped due to spawned tasks, which hold references.
    pub fn new(client_state: ClientStateHandle, os_impl: Arc<impl Os>) -> Receiver<TunnelState> {
        let (tunnel_state_send, tunnel_state_recv) = channel(TunnelState::Disconnected);
        tokio::spawn(Self::maintain(tunnel_state_send, client_state, os_impl));
        tunnel_state_recv
    }

    pub fn traffic_stats(&self) -> ManagerTrafficStats {
        match self {
            TunnelState::Disconnected => {
                ManagerTrafficStats { connected_ms: 0, conn_id: Uuid::new_v4(), tx_bytes: 0, rx_bytes: 0, latest_latency_ms: 0 }
            }
            TunnelState::Connecting { offset_traffic_stats, .. } => *offset_traffic_stats,
            TunnelState::Connected { conn, offset_traffic_stats, .. } => {
                let mut traffic_stats = *offset_traffic_stats;
                let QuicWgTrafficStats { connected_at, tx_bytes, rx_bytes, latest_latency_ms } = conn.traffic_stats();
                traffic_stats.connected_ms = traffic_stats
                    .connected_ms
                    .saturating_add(connected_at.elapsed().as_millis().try_into().unwrap_or(u64::MAX));
                traffic_stats.rx_bytes = traffic_stats.rx_bytes.saturating_add(rx_bytes);
                traffic_stats.tx_bytes = traffic_stats.tx_bytes.saturating_add(tx_bytes);
                traffic_stats.latest_latency_ms = latest_latency_ms;
                traffic_stats
            }
        }
    }

    fn set_disconnected(&mut self) {
        *self = Self::Disconnected;
    }

    fn set_connecting(&mut self, new_args: &TunnelArgs, network_interface: &Option<NetworkInterface>, disconnect_reason: Option<QuicWgReceiveError>) {
        match self {
            this @ Self::Connected { .. } | this @ Self::Disconnected => {
                *this = Self::Connecting {
                    args: new_args.clone(),
                    connect_error: None,
                    disconnect_reason: disconnect_reason.map(Into::into),
                    offset_traffic_stats: this.traffic_stats(),
                    network_interface: network_interface.clone(),
                }
            }
            // Ombra patch P1: the last error belongs to the old args (for example `udpHandshakeFailed` after a switch
            // from Speed mode to QUIC), so new args start without one.
            Self::Connecting { args, connect_error, .. } => {
                if args != new_args {
                    *connect_error = None;
                    *args = new_args.clone();
                }
            }
        }
    }

    fn set_connected(&mut self, args: &TunnelArgs, network_interface: &NetworkInterface, relay_transport: &RelayTransport, connected: Connected) {
        let Connected { conn, exit, network_config, relay, tunnel_id } = connected;
        *self = Self::Connected {
            args: args.clone(),
            network_interface: network_interface.clone(),
            relay_transport: relay_transport.clone(),
            tunnel_id,
            conn: conn.clone(),
            network_config,
            relay,
            exit,
            offset_traffic_stats: match self {
                Self::Connected { conn: old_conn, offset_traffic_stats, .. } if Arc::ptr_eq(old_conn, &conn) => *offset_traffic_stats,
                Self::Connected { .. } | Self::Connecting { .. } | Self::Disconnected => self.traffic_stats(),
            },
        };
    }

    fn set_connect_error(&mut self, error: TunnelConnectError) {
        let Self::Connecting { connect_error, .. } = self else {
            tracing::error!(
                message_id = "jZGhFRZh",
                "trying to set connect error on non-connecting tunnel state, this should be impossible"
            );
            return;
        };
        *connect_error = Some(error.into())
    }

    pub fn get_conn(&self) -> Option<Arc<QuicWgConn>> {
        match self {
            TunnelState::Disconnected => None,
            TunnelState::Connecting { .. } => None,
            TunnelState::Connected { conn, .. } => Some(conn.clone()),
        }
    }

    fn get_connected(&self) -> Option<Connected> {
        match self {
            TunnelState::Disconnected => None,
            TunnelState::Connecting { .. } => None,
            TunnelState::Connected { conn, exit, network_config, relay, tunnel_id, .. } => Some(Connected {
                conn: conn.clone(),
                exit: exit.clone(),
                network_config: network_config.clone(),
                relay: relay.clone(),
                tunnel_id: *tunnel_id,
            }),
        }
    }

    fn matches_target(&self, target: &TargetState) -> bool {
        match self {
            Self::Disconnected => target.tunnel_args.is_none(),
            Self::Connecting { .. } => false,
            Self::Connected { args, network_interface, relay_transport, .. } => {
                Some(args) == target.tunnel_args.as_ref()
                    && Some(network_interface) == target.network_interface.as_ref()
                    && *relay_transport == target.relay_transport
            }
        }
    }

    async fn maintain(tunnel_state: Sender<TunnelState>, client_state: ClientStateHandle, os_impl: Arc<impl Os>) -> ! {
        let mut client_state_watch = client_state.subscribe();

        // Delay processing new states or retrying after error for at least this long.
        const DEBOUNCE_PERIOD: Duration = Duration::from_secs(1);

        let mut last_start: Option<Instant> = None;
        let mut disconnect_reason = None;
        let mut selection_state = ExitSelectionState::default();
        let mut os_network_config_refresh = 0;
        // Failed Speed mode handshakes in a row (Ombra patch P5).
        let mut udp_handshake_failures = 0;
        // Other failed connects in a row (Ombra patch P9).
        let mut connect_failures = 0;
        // Whether the OS has a TUN from this loop, dummy or real (Ombra).
        let mut os_network_config_set = false;

        loop {
            if let Some(last_start) = last_start {
                sleep_until(last_start + DEBOUNCE_PERIOD).await;
            }
            last_start = Some(Instant::now());

            let target_state = client_state_watch.borrow_and_update().target_state();
            tracing::info!(
                message_id = "KT91bgvI",
                ?target_state,
                ?disconnect_reason,
                "not in target state or tunnel broke"
            );

            if target_state.os_network_config_refresh != os_network_config_refresh {
                // `refreshOsNetworkConfig`: OS-side settings that Rust does not see changed (on Android: split tunnel,
                // custom DNS), so the next set_os_network_config must reach the OS even with the same config. A
                // connected tunnel stays up.
                os_network_config_refresh = target_state.os_network_config_refresh;
                os_impl.forget_os_network_config();
            }

            if !tunnel_state.borrow().is_disconnected() && target_state.tunnel_args.is_none() {
                // Target state changed to disconnected, which means we will disconnect, but are in another state.
                // This is the right time for key rotations without unnecessarily rotating keys of permanently unused devices.
                client_state.rotate_wireguard_key_if_required()
            }

            if disconnect_reason.is_none() {
                // Ombra patch P4 (D1, D2): a live tunnel follows a change of only the network where it can.
                follow_network_change(&tunnel_state, &target_state, &client_state).await;
            }

            // Drop tunnel if args changed or tunnel broke and change to connecting or disconnected as desired
            if !tunnel_state.borrow().matches_target(&target_state) || disconnect_reason.is_some() {
                tunnel_state.send_modify(|tunnel_state| match &target_state {
                    TargetState {
                        tunnel_args: None,
                        network_interface: _,
                        dns_content_block: _,
                        use_system_dns: _,
                        local_network_access: _,
                        tailscale_bypass: _,
                        wireguard_bypass: _,
                        relay_transport: _,
                        os_network_config_refresh: _,
                    } => tunnel_state.set_disconnected(),
                    TargetState {
                        tunnel_args: Some(target_args),
                        network_interface,
                        dns_content_block: _,
                        use_system_dns: _,
                        local_network_access: _,
                        tailscale_bypass: _,
                        wireguard_bypass: _,
                        relay_transport: _,
                        os_network_config_refresh: _,
                    } => tunnel_state.set_connecting(target_args, network_interface, disconnect_reason.take()),
                });
            }

            match &target_state {
                TargetState {
                    tunnel_args: Some(target_args),
                    network_interface: Some(target_network_interface),
                    dns_content_block,
                    use_system_dns,
                    local_network_access,
                    tailscale_bypass,
                    wireguard_bypass,
                    relay_transport,
                    os_network_config_refresh: _,
                } => {
                    #[cfg(not(any(target_os = "android", target_os = "linux")))]
                    let _ = local_network_access;
                    #[cfg(not(target_os = "linux"))]
                    let _ = (tailscale_bypass, wireguard_bypass);
                    let cf: ControlFlow<(), Connected> = if let Some(connected) = tunnel_state.borrow().get_connected() {
                        // Already connected, continue with next steps
                        ControlFlow::Continue(connected)
                    } else {
                        // Not connected, but target state indicates that this is possible and desired. Start capturing traffic and connect.
                        if let Err(()) = os_impl
                            .set_os_network_config(
                                OsNetworkConfig::dummy(
                                    *dns_content_block,
                                    *use_system_dns,
                                    #[cfg(any(target_os = "android", target_os = "linux"))]
                                    *local_network_access,
                                    #[cfg(target_os = "linux")]
                                    *tailscale_bypass,
                                    #[cfg(target_os = "linux")]
                                    *wireguard_bypass,
                                ),
                                QuicWgConnPacketSender::new(None),
                            )
                            .await
                        {
                            tracing::error!(message_id = "eTwAHomq", "failed to set dummy network config");
                            tunnel_state.send_modify(|tunnel_state| tunnel_state.set_connect_error(TunnelConnectError::SetOsNetworkConfig));
                            ControlFlow::Break(())
                        } else {
                            os_network_config_set = true;
                            match poll_until_change(
                                &mut client_state_watch,
                                &target_state,
                                client_state.connect(target_args, relay_transport, Some(target_network_interface), &mut selection_state),
                            )
                            .await
                            {
                                None => {
                                    tracing::info!(
                                        message_id = "SmLhzVwY",
                                        "target state or tunnel arguments changed while trying to connect"
                                    );
                                    ControlFlow::Break(())
                                }
                                Some(Err(error)) => {
                                    tracing::error!(message_id = "OfLfwKhf", ?error, "failed to connect");
                                    let udp_handshake_failed = matches!(error, TunnelConnectError::UdpHandshakeFailed);
                                    tunnel_state.send_modify(|tunnel_state| tunnel_state.set_connect_error(error));
                                    if udp_handshake_failed {
                                        // Each attempt makes and deletes an API tunnel, so wait longer after each
                                        // failure. A change of the target state (such as a switch to Stealth) ends
                                        // the wait and starts over.
                                        udp_handshake_failures += 1;
                                        let delay = handshake_retry_delay(udp_handshake_failures);
                                        tracing::info!(
                                            message_id = "Rb6tVz1M",
                                            failures = udp_handshake_failures,
                                            delay_s = delay.as_secs(),
                                            "waiting before the next WireGuard UDP connect"
                                        );
                                        if poll_until_change(&mut client_state_watch, &target_state, sleep(delay)).await.is_none() {
                                            udp_handshake_failures = 0;
                                        }
                                    } else {
                                        connect_failures += 1;
                                        let delay = connect_retry_delay(connect_failures);
                                        if !delay.is_zero() {
                                            tracing::info!(
                                                message_id = "pW3kRf8e",
                                                failures = connect_failures,
                                                delay_s = delay.as_secs(),
                                                "waiting before the next connect"
                                            );
                                            if poll_until_change(&mut client_state_watch, &target_state, retry_wait(delay))
                                                .await
                                                .is_none()
                                            {
                                                connect_failures = 0;
                                            }
                                        }
                                    }
                                    ControlFlow::Break(())
                                }
                                Some(Ok(connection)) => {
                                    tracing::info!(
                                        message_id = "icGquatl",
                                        tunnel.id =% connection.tunnel_id,
                                        "connected successfully"
                                    );
                                    selection_state = ExitSelectionState::default();
                                    udp_handshake_failures = 0;
                                    ControlFlow::Continue(Connected {
                                        conn: Arc::new(connection.conn),
                                        exit: connection.exit,
                                        network_config: connection.network_config,
                                        relay: connection.relay,
                                        tunnel_id: connection.tunnel_id,
                                    })
                                }
                            }
                        }
                    };
                    if let ControlFlow::Continue(connected) = cf {
                        let tunnel_id = connected.tunnel_id;
                        let conn = connected.conn.clone();
                        // Reached connected state, set OS network config and update published tunnel state
                        let os_network_config = OsNetworkConfig::new(
                            &connected.network_config,
                            &connected.exit.provider_name,
                            *dns_content_block,
                            *use_system_dns,
                            #[cfg(any(target_os = "android", target_os = "linux"))]
                            *local_network_access,
                            #[cfg(target_os = "linux")]
                            *tailscale_bypass,
                            #[cfg(target_os = "linux")]
                            *wireguard_bypass,
                        );
                        if let Err(()) = os_impl
                            .set_os_network_config(os_network_config, QuicWgConnPacketSender::new(Some(&conn)))
                            .await
                        {
                            tracing::error!(message_id = "t7QzSTGu", tunnel.id =% tunnel_id, "failed to set network config");
                            tunnel_state.send_modify(|tunnel_state| tunnel_state.set_connect_error(TunnelConnectError::SetOsNetworkConfig));
                            // A failed connect too (Ombra patch P9): each attempt makes an API tunnel. Close this one
                            // before the wait.
                            drop((conn, connected));
                            connect_failures += 1;
                            let delay = connect_retry_delay(connect_failures);
                            if !delay.is_zero()
                                && poll_until_change(&mut client_state_watch, &target_state, retry_wait(delay))
                                    .await
                                    .is_none()
                            {
                                connect_failures = 0;
                            }
                        } else {
                            os_network_config_set = true;
                            connect_failures = 0;
                            tunnel_state.send_modify(|tunnel_state| {
                                tunnel_state.set_connected(target_args, target_network_interface, relay_transport, connected)
                            });
                            // forward traffic until target state changes or the tunnel fails
                            disconnect_reason = poll_until_change(&mut client_state_watch, &target_state, async {
                                loop {
                                    match conn.receive().await {
                                        Ok(packet) => os_impl.packet_for_os(packet),
                                        Err(error) => {
                                            tracing::error!(message_id = "tls1cZot", tunnel.id =% tunnel_id, ?error, "tunnel failed");
                                            break error;
                                        }
                                    }
                                }
                            })
                            .await;
                            if let Some(QuicWgReceiveError::LargePacketsLost) = disconnect_reason {
                                // Guard 2 of the MTU floor: reconnect without the floor on this network.
                                client_state.set_mtu_floor_failed();
                            }
                        }
                    }
                }
                TargetState {
                    tunnel_args: None,
                    network_interface: _,
                    dns_content_block: _,
                    use_system_dns: _,
                    local_network_access: _,
                    tailscale_bypass: _,
                    wireguard_bypass: _,
                    relay_transport: _,
                    os_network_config_refresh: _,
                } => {
                    selection_state = ExitSelectionState::default();
                    udp_handshake_failures = 0;
                    connect_failures = 0;
                    tracing::info!(message_id = "axfILRQy", "reached disconnected target state");
                    // The data path stopped, and the last Speed mode tunnel deletes itself. Delete what earlier runs
                    // left (Ombra patch P5).
                    client_state.delete_leftover_udp_tunnels();
                    if let Err(()) = os_impl.unset_os_network_config().await {
                        tracing::error!(message_id = "PEgDYAz0", "failed to unset network config");
                    } else {
                        os_network_config_set = false;
                        // nothing to do until target args change
                        poll_until_change(&mut client_state_watch, &target_state, pending::<Infallible>()).await;
                    }
                }
                TargetState {
                    tunnel_args: Some(_),
                    network_interface: None,
                    dns_content_block,
                    use_system_dns,
                    local_network_access,
                    tailscale_bypass,
                    wireguard_bypass,
                    relay_transport: _,
                    os_network_config_refresh: _,
                } => {
                    #[cfg(not(any(target_os = "android", target_os = "linux")))]
                    let _ = local_network_access;
                    #[cfg(not(target_os = "linux"))]
                    let _ = (tailscale_bypass, wireguard_bypass);
                    tracing::warn!(message_id = "0K9Nep8g", "stuck in connecting state without target interface");
                    selection_state = ExitSelectionState::default();
                    tunnel_state.send_modify(|tunnel_state| tunnel_state.set_connect_error(TunnelConnectError::NoInternet));
                    // A connect that starts without a network captures traffic too (Ombra), so apps do not send outside
                    // the tunnel when the network comes. A TUN that is up stays as it is.
                    if !os_network_config_set {
                        let dummy = OsNetworkConfig::dummy(
                            *dns_content_block,
                            *use_system_dns,
                            #[cfg(any(target_os = "android", target_os = "linux"))]
                            *local_network_access,
                            #[cfg(target_os = "linux")]
                            *tailscale_bypass,
                            #[cfg(target_os = "linux")]
                            *wireguard_bypass,
                        );
                        if let Err(()) = os_impl.set_os_network_config(dummy, QuicWgConnPacketSender::new(None)).await {
                            tracing::error!(message_id = "Vq8sNw2D", "failed to set dummy network config without a network");
                        } else {
                            os_network_config_set = true;
                        }
                    }
                    // nothing to do until target args changes or a network device becomes available
                    poll_until_change(&mut client_state_watch, &target_state, pending::<Infallible>()).await;
                }
            }
        }
    }
}

/// When the target differs from the connected tunnel only in the network interface, the tunnel moves to the new network
/// or keeps going (`Handover`), and then matches the target. Otherwise, or when the move fails, nothing changes, so the
/// caller connects again.
async fn follow_network_change(tunnel_state: &Sender<TunnelState>, target: &TargetState, client_state: &ClientStateHandle) {
    let (conn, old_interface) = match &*tunnel_state.borrow() {
        TunnelState::Connected { args, network_interface, relay_transport, conn, .. }
            if Some(args) == target.tunnel_args.as_ref() && *relay_transport == target.relay_transport =>
        {
            (conn.clone(), network_interface.clone())
        }
        _ => return,
    };
    let Some(new_interface) = target.network_interface.as_ref().filter(|new| **new != old_interface) else {
        return;
    };
    let force_small_mtu = matches!(target.relay_transport, RelayTransport::Quic { force_small_mtu: true, .. });
    let new_mtu = client_state.quic_mtu_choices(force_small_mtu);
    let handover = Handover::decide(conn.transport(), conn.mtu_choices(), new_mtu, &old_interface, new_interface);
    tracing::info!(message_id = "Wd3kPn7Q", ?handover, "network changed while connected");
    match handover {
        Handover::Keep => {}
        Handover::Rebind => {
            if let Err(error) = conn.rebind(new_interface).await {
                tracing::warn!(
                    message_id = "Hs6vBq1J",
                    ?error,
                    "failed to move the tunnel to the new network, reconnecting"
                );
                return;
            }
        }
        Handover::Reconnect => return,
    }
    tunnel_state.send_modify(|tunnel_state| {
        if let TunnelState::Connected { network_interface, .. } = tunnel_state {
            *network_interface = new_interface.clone();
        }
    });
}

/// Ends the wait between failed connects (Ombra patch P9).
static RETRY_CONNECT_NOW: Notify = Notify::const_new();

/// The phone woke up: a wait between failed connects ends at once.
pub fn retry_connect_now() {
    // One stored permit: a wake that comes just before the wait starts still ends it.
    RETRY_CONNECT_NOW.notify_one();
}

/// Waits `delay` or until [`retry_connect_now`].
async fn retry_wait(delay: Duration) {
    select! {
        _ = sleep(delay) => {}
        _ = RETRY_CONNECT_NOW.notified() => {}
    }
}

/// The wait after `failures` failed QUIC or TCP/TLS connects in a row, in addition to the 1 s between all attempts
/// (Ombra patch P9). Upstream tries again each second without end; on a network that blocks the relays, such as a
/// captive portal, that is a relay race each second. 0, 1, 3, 7, 15, then 29 s: about 30 s between the attempts. A
/// change of the target state (also another network), a wake and a success end the wait and start over.
fn connect_retry_delay(failures: u32) -> Duration {
    let doublings = failures.saturating_sub(1).min(5);
    Duration::from_secs((1u64 << doublings) - 1).min(Duration::from_secs(29))
}

// Run future, until complete or until the watch channel signals a change.
async fn poll_until_change<O>(watch: &mut Receiver<ClientState>, target_state: &TargetState, fut: impl Future<Output = O>) -> Option<O> {
    select! {
        _ = watch.wait_for(|new| new.target_state() != *target_state) => None,
        o = fut => Some(o),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::os::os_trait::RecordingOs;
    use crate::positive_u31::PositiveU31;
    use crate::test_relay::{LossyPath, SpeedSetup, wait_until};
    use crate::wg_key_store::WgKeyStore;
    use std::net::Ipv4Addr;
    use tokio::time::timeout;

    /// The loopback interface of macOS, where the sockets are bound to the network interface, as the network with this
    /// handle.
    fn loopback(network_handle: u64) -> NetworkInterface {
        NetworkInterface { name: "lo0".into(), index: PositiveU31::try_from(1).unwrap(), network_handle, mtu_hint: None }
    }

    #[test]
    fn new_args_while_connecting_drop_the_last_connect_error() {
        let has_error = |state: &TunnelState| matches!(state, TunnelState::Connecting { connect_error: Some(_), .. });
        let speed = TunnelArgs { mode: crate::manager::TunnelMode::WireguardUdp, ..Default::default() };
        let mut state = TunnelState::Disconnected;
        state.set_connecting(&speed, &None, None);
        state.set_connect_error(TunnelConnectError::UdpHandshakeFailed);

        // The next attempt with the same args keeps the error until it ends.
        state.set_connecting(&speed, &None, None);
        assert!(has_error(&state));

        // "Switch to Stealth" in the app.
        let quic = TunnelArgs { mode: crate::manager::TunnelMode::Quic, ..speed };
        state.set_connecting(&quic, &None, None);
        assert!(!has_error(&state));
        assert!(matches!(&state, TunnelState::Connecting { args, .. } if *args == quic));
    }

    #[test]
    fn connect_retry_delay_grows_to_about_30_s() {
        let delays: Vec<u64> = (1..=8).map(|failures| connect_retry_delay(failures).as_secs()).collect();
        assert_eq!(delays, [0, 1, 3, 7, 15, 29, 29, 29]);
        assert_eq!(connect_retry_delay(u32::MAX), Duration::from_secs(29));
    }

    #[tokio::test]
    async fn refresh_os_network_config_sets_the_config_again_while_connecting() {
        let dir = tempfile::tempdir().unwrap();
        let client_state = ClientState::new(dir.path().into(), WgKeyStore::None, "test".into(), false).unwrap();
        let os = Arc::new(RecordingOs::default());
        let mut tunnel_state = TunnelState::new(client_state.clone(), os.clone());

        // Not logged in: each connect attempt sets the dummy config, then fails before any network access.
        client_state.set_network_interface(Some(loopback(0)));
        client_state.set_tunnel_target_state(Some(TunnelArgs::default()), Some(true));
        let failed_attempt = tunnel_state.wait_for(|state| matches!(state, TunnelState::Connecting { connect_error: Some(_), .. }));
        timeout(Duration::from_secs(10), failed_attempt).await.unwrap().unwrap();
        assert!(os.calls().contains(&"set"));
        assert!(!os.calls().contains(&"forget"));

        let before = os.calls().len();
        client_state.refresh_os_network_config();
        let set_after_forget = || {
            let calls = os.calls();
            let new_calls = &calls[before..];
            new_calls
                .iter()
                .position(|call| *call == "forget")
                .is_some_and(|forget| new_calls[forget..].contains(&"set"))
        };
        timeout(Duration::from_secs(10), async {
            while !set_after_forget() {
                sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("the config was not set again after the refresh");
        assert!(tunnel_state.borrow().is_connecting());
    }

    /// A connect that starts without a network sets the dummy config, so apps cannot send outside the tunnel when the
    /// network comes.
    #[tokio::test]
    async fn a_connect_without_a_network_captures_traffic() {
        let dir = tempfile::tempdir().unwrap();
        let client_state = ClientState::new(dir.path().into(), WgKeyStore::None, "test".into(), false).unwrap();
        let os = Arc::new(RecordingOs::default());
        let mut tunnel_state = TunnelState::new(client_state.clone(), os.clone());

        client_state.set_tunnel_target_state(Some(TunnelArgs::default()), Some(true));
        let no_internet = tunnel_state.wait_for(|state| {
            matches!(
                state,
                TunnelState::Connecting { connect_error: Some(ErrorAt { error: TunnelConnectError::NoInternet, .. }), .. }
            )
        });
        timeout(Duration::from_secs(10), no_internet).await.unwrap().unwrap();
        timeout(Duration::from_secs(10), async {
            while !os.calls().contains(&"set") {
                sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("no TUN while waiting for a network");
    }

    /// Ombra patch P5: a Speed mode handshake without an answer fails with `udpHandshakeFailed` and its API tunnel is
    /// deleted. The next attempt waits 5 s instead of 1 s, without a fallback to QUIC, and a target change ends the
    /// wait at once.
    #[tokio::test]
    async fn failed_speed_handshake_deletes_its_tunnel_and_backs_off() {
        // A closed port: nothing answers the handshake.
        let setup = SpeedSetup::new(|_| std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap());
        let SpeedSetup { client_state, api, args, .. } = &setup;
        let created = || api.tunnel_ids("POST");
        let mut tunnel_state = TunnelState::new(client_state.clone(), Arc::new(RecordingOs::default()));
        client_state.set_network_interface(Some(loopback(0)));
        client_state.set_tunnel_target_state(Some(args.clone()), Some(true));

        let handshake_failed = |state: &TunnelState| {
            matches!(
                state,
                TunnelState::Connecting { connect_error: Some(ErrorAt { error: TunnelConnectError::UdpHandshakeFailed, .. }), .. }
            )
        };
        timeout(Duration::from_secs(20), tunnel_state.wait_for(handshake_failed))
            .await
            .unwrap()
            .unwrap();
        let failed_at = Instant::now();
        wait_until("the failed tunnel is deleted", || !api.deleted_tunnel_ids().is_empty()).await;
        assert_eq!(api.deleted_tunnel_ids(), created());
        wait_until("the config forgets it", || client_state.borrow().config().owned_udp_tunnel_ids.is_empty()).await;

        sleep_until(failed_at + Duration::from_secs(3)).await;
        assert_eq!(created().len(), 1, "the next attempt did not wait");
        assert!(handshake_failed(&tunnel_state.borrow()));

        client_state.set_tunnel_target_state(None, Some(false));
        timeout(Duration::from_secs(2), tunnel_state.wait_for(TunnelState::is_disconnected))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(created().len(), 1);
    }

    /// Connects the Speed mode tunnel of `setup` on the network with handle 1 and returns its API tunnel id.
    async fn connect_speed_tunnel(setup: &SpeedSetup, tunnel_state: &mut Receiver<TunnelState>) -> Uuid {
        setup.client_state.set_network_interface(Some(loopback(1)));
        setup.client_state.set_tunnel_target_state(Some(setup.args.clone()), Some(true));
        let state = timeout(Duration::from_secs(10), tunnel_state.wait_for(TunnelState::is_connected))
            .await
            .unwrap()
            .unwrap();
        let TunnelState::Connected { tunnel_id, .. } = &*state else {
            unreachable!()
        };
        *tunnel_id
    }

    /// Ombra patch P4 (D2): a network change moves the Speed mode tunnel to a socket on the new network. The connection
    /// and its API tunnel stay.
    #[tokio::test]
    async fn speed_tunnel_follows_a_network_change() {
        let setup = SpeedSetup::new(|relay| relay.udp_addr);
        let mut tunnel_state = TunnelState::new(setup.client_state.clone(), Arc::new(RecordingOs::default()));
        connect_speed_tunnel(&setup, &mut tunnel_state).await;
        let conn = tunnel_state.borrow().get_conn().unwrap();

        setup.client_state.set_network_interface(Some(loopback(2)));
        let on_new_network =
            |state: &TunnelState| matches!(state, TunnelState::Connected { network_interface, .. } if network_interface.network_handle == 2);
        timeout(Duration::from_secs(5), tunnel_state.wait_for(on_new_network))
            .await
            .unwrap()
            .unwrap();
        // Longer than a tunnel without an answer to the probe after the move lives.
        sleep(Duration::from_secs(3)).await;
        let same_conn = tunnel_state.borrow().get_conn().is_some_and(|current| Arc::ptr_eq(&current, &conn));
        assert!(same_conn, "the tunnel connected again");
        assert_eq!(setup.api.tunnel_ids("POST").len(), 1);
        assert!(setup.api.deleted_tunnel_ids().is_empty());
    }

    /// Ombra patch P4 (D2): when the relay ignores the new address, a new API tunnel replaces the old one within a few
    /// seconds, and the old one is deleted.
    #[tokio::test]
    async fn speed_tunnel_reconnects_when_the_relay_ignores_the_new_network() {
        let mut path = None;
        let setup = SpeedSetup::new(|relay| path.insert(LossyPath::client_from_wireguard_handshake(relay.udp_addr)).addr);
        let mut tunnel_state = TunnelState::new(setup.client_state.clone(), Arc::new(RecordingOs::default()));
        let first_tunnel_id = connect_speed_tunnel(&setup, &mut tunnel_state).await;

        setup.client_state.set_network_interface(Some(loopback(2)));
        let changed_at = Instant::now();
        let new_tunnel = |state: &TunnelState| matches!(state, TunnelState::Connected { tunnel_id, .. } if *tunnel_id != first_tunnel_id);
        timeout(Duration::from_secs(10), tunnel_state.wait_for(new_tunnel))
            .await
            .unwrap()
            .unwrap();
        // About 2 s until the tunnel is dead, then a full connect.
        assert!(changed_at.elapsed() < Duration::from_secs(6), "{:?}", changed_at.elapsed());
        wait_until("the old tunnel is deleted", || {
            setup.api.deleted_tunnel_ids() == [first_tunnel_id.to_string()]
        })
        .await;
        assert_eq!(setup.api.tunnel_ids("POST").len(), 2);
    }
}
