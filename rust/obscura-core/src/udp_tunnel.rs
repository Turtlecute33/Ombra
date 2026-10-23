//! Speed mode (Ombra patch P5): plain WireGuard over UDP through the entry relay, on an API tunnel made with
//! `CreateTunnel::UdpPort` (`docs/research/perf-udp-mode.md` 6.2). Such a tunnel holds one of the account's slots until
//! it is deleted, so the client deletes every tunnel that it created: after its data path stopped, after a failed
//! connect, after a logout, and the leftovers of an earlier run at the next start. It never deletes a tunnel that it did
//! not create.

use crate::client_state::{ClientStateHandle, WeakClientStateHandle};
use crate::errors::{ApiError, TunnelConnectError};
use crate::net::NetworkInterface;
use crate::network_config::{NetworkConfigError, TunnelNetworkConfig};
use crate::quicwg::{QuicWgConn, QuicWgConnectError, QuicWgWireguardHandshakeError};
use boringtun::x25519::{PublicKey, StaticSecret};
use obscuravpn_api::cmd::{ApiErrorKind, DeleteTunnel};
use obscuravpn_api::reexports::reqwest::StatusCode;
use obscuravpn_api::types::{WgClientConfig, WgServerConfig};
use obscuravpn_api::{Client, ClientError};
use std::collections::BTreeSet;
use std::future::Future;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::watch;
use tokio::time::{sleep, timeout};
use uuid::Uuid;

/// The tunnel MTU without a `udpMtu` setting. The official WireGuard app uses it for a config without an `MTU` line.
const DEFAULT_TUNNEL_MTU: u16 = 1280;
/// The IPv6 minimum.
const MIN_TUNNEL_MTU: u16 = 1280;
/// A 1420-byte packet is a 1452-byte WireGuard message, which fits a 1500-byte path also over IPv6.
const MAX_TUNNEL_MTU: u16 = 1420;

/// The exit's address in the tunnel, the target of the liveness pings. `UdpPort` has no `gateway_ip_v4`: this is the
/// value that obscuravpn-api gives an `Obfuscated` config without one (`types.rs` `default_gateway_ip_v4`).
const GATEWAY_IP_V4: Ipv4Addr = Ipv4Addr::new(10, 64, 0, 1);

/// The wait after the first failed handshake. It doubles after each further failure, up to the cap.
const FIRST_RETRY_DELAY: Duration = Duration::from_secs(5);
const MAX_RETRY_DELAY: Duration = Duration::from_secs(60);

/// How long a connect waits for the deletes of earlier tunnels, so that their slots are free for the new one.
const DELETE_WAIT: Duration = Duration::from_secs(5);

/// The config keeps at most this many ids, so that a tunnel that the API never deletes cannot grow it.
const MAX_OWNED_TUNNEL_IDS: usize = 16;

/// The waits between the deletes of a tunnel that the config forgot (logout, account change): 10 s, doubling, about
/// 40 min in all. Only this task still knows the id, and the API keeps a tunnel slot of the old account for it.
const FORGOTTEN_RETRY_FIRST_DELAY: Duration = if cfg!(test) {
    Duration::from_millis(100)
} else {
    Duration::from_secs(10)
};
const FORGOTTEN_RETRIES: u32 = 8;

/// `udpMtu` of the tunnel arguments, clamped to 1280..=1420.
pub fn tunnel_mtu(udp_mtu: Option<u16>) -> u16 {
    udp_mtu.unwrap_or(DEFAULT_TUNNEL_MTU).clamp(MIN_TUNNEL_MTU, MAX_TUNNEL_MTU)
}

/// The wait before the next connect after `failures` failed handshakes in a row: 5, 10, 20, 40 s, then 60 s. Speed
/// mode never falls back to Stealth by itself, because the network would then see QUIC after WireGuard. The UI offers
/// the switch.
pub fn handshake_retry_delay(failures: u32) -> Duration {
    let doublings = failures.saturating_sub(1).min(8);
    FIRST_RETRY_DELAY.saturating_mul(1 << doublings).min(MAX_RETRY_DELAY)
}

/// Starts the data path of a Speed mode tunnel from the config that the API gave for it: the WireGuard handshake with
/// the exit through the relay port, then the first liveness pong. No API call.
pub async fn connect(
    client: &WgClientConfig,
    server: &WgServerConfig,
    udp_mtu: Option<u16>,
    client_secret_key: StaticSecret,
    network_interface: Option<&NetworkInterface>,
) -> Result<(QuicWgConn, TunnelNetworkConfig), TunnelConnectError> {
    let network_config = TunnelNetworkConfig::from_udp(client, server, tunnel_mtu(udp_mtu))?;
    // The relay sockets are IPv4 only, as in the other modes (`net::new_udp`).
    let endpoint = server
        .endpoints
        .iter()
        .find_map(|endpoint| match endpoint {
            SocketAddr::V4(endpoint) => Some(*endpoint),
            SocketAddr::V6(_) => None,
        })
        .ok_or(NetworkConfigError::NoIpv4Endpoint)?;
    let exit_public_key = PublicKey::from(server.wg_pubkey.0);
    let conn = QuicWgConn::connect_udp(
        endpoint,
        network_interface,
        client_secret_key,
        exit_public_key,
        network_config.ipv4,
        GATEWAY_IP_V4,
    )
    .await
    .map_err(|error| match error {
        QuicWgConnectError::WireguardHandshake(QuicWgWireguardHandshakeError::RespMessageTimeout) => TunnelConnectError::UdpHandshakeFailed,
        error => error.into(),
    })?;
    Ok((conn, network_config))
}

/// The owned tunnel ids that a task works on right now: the live tunnel, a connect, a delete. The other ids in
/// `Config::owned_udp_tunnel_ids` are leftovers.
pub struct UdpTunnelClaims(watch::Sender<BTreeSet<Uuid>>);

impl Default for UdpTunnelClaims {
    fn default() -> Self {
        Self(watch::channel(BTreeSet::new()).0)
    }
}

impl UdpTunnelClaims {
    fn claim(&self, id: Uuid) {
        self.0.send_modify(|claimed| {
            claimed.insert(id);
        });
    }

    fn release(&self, id: Uuid) {
        self.0.send_modify(|claimed| {
            claimed.remove(&id);
        });
    }

    /// Claims the ids of `owned` that no task works on, and returns them.
    fn claim_leftovers(&self, owned: &[Uuid]) -> Vec<Uuid> {
        let mut leftovers = Vec::new();
        self.0.send_if_modified(|claimed| {
            leftovers = owned.iter().copied().filter(|id| claimed.insert(*id)).collect();
            !leftovers.is_empty()
        });
        leftovers
    }

    #[cfg(test)]
    pub(crate) fn claim_for_test(&self, id: Uuid) {
        self.claim(id)
    }

    /// Waits until no id is claimed, at most `max_wait`. Returns false on timeout.
    async fn wait_until_none(&self, max_wait: Duration) -> bool {
        let mut claimed = self.0.subscribe();
        matches!(timeout(max_wait, claimed.wait_for(BTreeSet::is_empty)).await, Ok(Ok(_)))
    }
}

/// A Speed mode tunnel that this client asks the API for. Its id is in the config before the create call. Dropping the
/// lease deletes the tunnel: after a failed connect, or with the connection after its data path stopped
/// (`QuicWgConn::with_close_guard`).
pub struct UdpTunnelLease {
    id: Uuid,
    /// `None` once the API said that it made no tunnel.
    deleter: Option<TunnelDeleter>,
}

impl UdpTunnelLease {
    pub fn id(&self) -> Uuid {
        self.id
    }

    /// The create call failed. When the API rejected it, there is no tunnel and the id leaves the config. Otherwise the
    /// tunnel can exist, and the drop deletes it.
    pub fn create_failed(mut self, error: &ApiError, client_state: &ClientStateHandle) {
        if create_rejected(error)
            && let Some(deleter) = self.deleter.take()
        {
            client_state.forget_owned_udp_tunnel(self.id);
            deleter.claims.release(self.id);
        }
    }
}

/// Whether the API answered a create call with an error that it gives without making the tunnel. No answer, an internal
/// error or an unknown error can come after the tunnel was made.
fn create_rejected(error: &ApiError) -> bool {
    !matches!(
        error.api_error_kind(),
        None | Some(ApiErrorKind::InternalError {} | ApiErrorKind::Unknown(_))
    )
}

impl Drop for UdpTunnelLease {
    fn drop(&mut self) {
        if let Some(deleter) = self.deleter.take() {
            let claims = deleter.claims.clone();
            if !spawn_in_runtime(deleter.delete(self.id)) {
                tracing::warn!(message_id = "Nq3vLs8D", tunnel.id =% self.id, "no runtime to delete the tunnel now");
                claims.release(self.id);
            }
        }
    }
}

/// Deletes owned tunnels with the API client of the account that created them, also after a logout.
#[derive(Clone)]
struct TunnelDeleter {
    api_client: Arc<Client>,
    client_state: WeakClientStateHandle,
    claims: Arc<UdpTunnelClaims>,
}

impl TunnelDeleter {
    /// Deletes the tunnel `id`, which the caller claimed. The id leaves the config when the tunnel is gone. Otherwise
    /// it stays for a later delete. An id that the config no longer has gets its later deletes from this task.
    async fn delete(self, id: Uuid) {
        let outcome = self.try_delete(id).await;
        self.claims.release(id);
        if outcome == DeleteOutcome::Retry && self.config_forgot(id) {
            // The claim is released: a connect of the next account must not wait for this delete.
            let mut delay = FORGOTTEN_RETRY_FIRST_DELAY;
            for _ in 0..FORGOTTEN_RETRIES {
                sleep(delay).await;
                if self.client_state.upgrade().is_none() || self.try_delete(id).await == DeleteOutcome::Gone {
                    return;
                }
                delay = delay.saturating_mul(2);
            }
            tracing::warn!(message_id = "Gx4tNb7Q", tunnel.id =% id, "stopped trying to delete the API tunnel of an earlier account");
        }
    }

    async fn try_delete(&self, id: Uuid) -> DeleteOutcome {
        let result = self.api_client.run(DeleteTunnel { id: id.to_string() }).await;
        let client_state = self.client_state.upgrade();
        if let Some(client_state) = &client_state {
            client_state.expire_dns_cache_if_unreachable(&result);
        }
        let result = result.map_err(ApiError::from);
        let outcome = DeleteOutcome::of(&result);
        match outcome {
            DeleteOutcome::Gone => {
                tracing::info!(message_id = "Lw5hRz1K", tunnel.id =% id, ?result, "deleted the API tunnel");
                if let Some(client_state) = &client_state {
                    client_state.forget_owned_udp_tunnel(id);
                }
            }
            DeleteOutcome::Retry => {
                tracing::warn!(message_id = "Fc7pXe4T", tunnel.id =% id, ?result, "failed to delete the API tunnel, trying again later");
            }
        }
        outcome
    }

    fn config_forgot(&self, id: Uuid) -> bool {
        self.client_state
            .upgrade()
            .is_some_and(|client_state| !client_state.borrow().config().owned_udp_tunnel_ids.contains(&id))
    }
}

#[derive(Debug, PartialEq, Eq)]
enum DeleteOutcome {
    /// The API has no such tunnel for this account (any more).
    Gone,
    /// The API may still have the tunnel: no answer, or an answer that a later try may change.
    Retry,
}

impl DeleteOutcome {
    fn of(result: &Result<(), ApiError>) -> Self {
        let Err(error) = result else { return Self::Gone };
        match error.api_error_kind() {
            Some(ApiErrorKind::BadRequest {}) => Self::Gone,
            // A name that this client does not know, for a request that the API refused (such as "no such tunnel"):
            // the API has no tunnel with this id that this account could delete.
            Some(ApiErrorKind::Unknown(_)) if is_refused_request(error) => Self::Gone,
            // Anything else can change: no answer, a server failure, a limit, the auth, a route or client version that
            // the API no longer takes. A forgotten id would keep its tunnel slot for ever.
            _ => Self::Retry,
        }
    }
}

/// A 4xx answer that is not about the auth or a rate limit.
fn is_refused_request(error: &ApiError) -> bool {
    matches!(
        error,
        ApiError::ApiClient(ClientError::ApiError(error))
            if error.status.is_client_error()
                && error.status != StatusCode::UNAUTHORIZED
                && error.status != StatusCode::TOO_MANY_REQUESTS
    )
}

fn spawn_in_runtime(future: impl Future<Output = ()> + Send + 'static) -> bool {
    let Ok(runtime) = tokio::runtime::Handle::try_current() else {
        return false;
    };
    runtime.spawn(future);
    true
}

impl ClientStateHandle {
    /// Writes a new tunnel id to the config, before the API hears of it. `api_client` (of the logged-in account) later
    /// deletes the tunnel; the create call must use the same client.
    pub(crate) fn lease_udp_tunnel(&self, api_client: Arc<Client>) -> UdpTunnelLease {
        let claims = self.udp_tunnel_claims();
        let id = Uuid::new_v4();
        claims.claim(id);
        self.change_config(|config| {
            config.owned_udp_tunnel_ids.push(id);
            let excess = config.owned_udp_tunnel_ids.len().saturating_sub(MAX_OWNED_TUNNEL_IDS);
            for forgotten in config.owned_udp_tunnel_ids.drain(..excess) {
                tracing::warn!(message_id = "Pz2kQm6B", tunnel.id =% forgotten, "too many undeleted API tunnels, forgetting the oldest");
            }
        });
        UdpTunnelLease { id, deleter: Some(TunnelDeleter { api_client, client_state: self.downgrade(), claims }) }
    }

    /// Starts to delete the owned tunnels that no task works on: leftovers of an earlier run or of a delete that failed.
    /// They hold slots in every tunnel mode. Does nothing while logged out.
    pub fn delete_leftover_udp_tunnels(&self) {
        let owned = self.borrow().config().owned_udp_tunnel_ids.clone();
        if owned.is_empty() {
            return;
        }
        if let Ok(api_client) = self.api_client() {
            self.delete_unclaimed_udp_tunnels(api_client, &owned);
        }
    }

    /// Deletes the ids of `owned` that no task works on, with `api_client`, which may belong to an account that is
    /// logging out.
    pub(crate) fn delete_unclaimed_udp_tunnels(&self, api_client: Arc<Client>, owned: &[Uuid]) {
        let claims = self.udp_tunnel_claims();
        let leftovers = claims.claim_leftovers(owned);
        if leftovers.is_empty() {
            return;
        }
        tracing::info!(message_id = "Ue9sGd3W", count = leftovers.len(), "deleting leftover API tunnels");
        let deleter = TunnelDeleter { api_client, client_state: self.downgrade(), claims: claims.clone() };
        for id in leftovers {
            if !spawn_in_runtime(deleter.clone().delete(id)) {
                claims.release(id);
            }
        }
    }

    /// Waits until the deletes of earlier tunnels are done, at most 5 s, so that their slots are free for a new tunnel.
    /// Call only while no tunnel is connected.
    pub(crate) async fn wait_for_udp_tunnel_deletes(&self) {
        if !self.udp_tunnel_claims().wait_until_none(DELETE_WAIT).await {
            tracing::warn!(message_id = "Hd4mYx8S", "API tunnel deletes still running, creating the tunnel anyway");
        }
    }

    fn forget_owned_udp_tunnel(&self, id: Uuid) {
        self.change_config(|config| config.owned_udp_tunnel_ids.retain(|owned| *owned != id));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::quicwg::{QuicWgConnPacketSender, TransportKind};
    use crate::test_relay::{EXIT_TUNNEL_IP, TestRelay, echo_reply, echo_request, udp_port_config};
    use obscuravpn_api::cmd::ApiErrorBody;
    use obscuravpn_api::types::WgPubkey;

    #[test]
    fn tunnel_mtu_defaults_to_1280_and_stays_in_1280_to_1420() {
        assert_eq!(tunnel_mtu(None), 1280);
        assert_eq!(tunnel_mtu(Some(1380)), 1380);
        assert_eq!(tunnel_mtu(Some(1420)), 1420);
        assert_eq!(tunnel_mtu(Some(1500)), 1420);
        assert_eq!(tunnel_mtu(Some(576)), 1280);
        assert_eq!(tunnel_mtu(Some(0)), 1280);
    }

    #[test]
    fn handshake_retry_delay_doubles_from_5_s_up_to_60_s() {
        let delays: Vec<u64> = (1..=7).map(|failures| handshake_retry_delay(failures).as_secs()).collect();
        assert_eq!(delays, [5, 10, 20, 40, 60, 60, 60]);
        assert_eq!(handshake_retry_delay(u32::MAX), MAX_RETRY_DELAY);
    }

    #[test]
    fn leftovers_are_the_owned_ids_that_no_task_works_on() {
        let claims = UdpTunnelClaims::default();
        let [live, deleting, leftover, other_leftover] = [(); 4].map(|()| Uuid::new_v4());
        claims.claim(live);
        claims.claim(deleting);
        let owned = [leftover, live, deleting, other_leftover];
        assert_eq!(claims.claim_leftovers(&owned), [leftover, other_leftover]);
        // Claimed now, so a second cleanup leaves them to the first one.
        assert!(claims.claim_leftovers(&owned).is_empty());
        claims.release(deleting);
        assert_eq!(claims.claim_leftovers(&owned), [deleting]);
    }

    #[tokio::test]
    async fn wait_for_deletes_ends_when_the_last_claim_is_released() {
        let claims = Arc::new(UdpTunnelClaims::default());
        assert!(claims.wait_until_none(Duration::ZERO).await);
        let id = Uuid::new_v4();
        claims.claim(id);
        assert!(!claims.wait_until_none(Duration::from_millis(50)).await);
        let releaser = claims.clone();
        tokio::spawn(async move { releaser.release(id) });
        assert!(claims.wait_until_none(Duration::from_secs(5)).await);
    }

    fn api_error(status: StatusCode, error: ApiErrorKind) -> Result<(), ApiError> {
        let body = ApiErrorBody { error, msg: String::new(), detail: None };
        Err(ApiError::ApiClient(ClientError::ApiError(obscuravpn_api::cmd::ApiError { status, body })))
    }

    #[test]
    fn a_delete_is_tried_again_unless_the_api_has_no_such_tunnel() {
        assert_eq!(DeleteOutcome::of(&Ok(())), DeleteOutcome::Gone);
        assert_eq!(
            DeleteOutcome::of(&api_error(StatusCode::BAD_REQUEST, ApiErrorKind::BadRequest {})),
            DeleteOutcome::Gone
        );
        let unknown = |name: &str| ApiErrorKind::Unknown(serde_json::json!({ name: {} }));
        assert_eq!(
            DeleteOutcome::of(&api_error(StatusCode::NOT_FOUND, unknown("TunnelNotFound"))),
            DeleteOutcome::Gone
        );
        // A server failure that this client has no name for, such as a relay that did not answer the API.
        assert_eq!(
            DeleteOutcome::of(&api_error(StatusCode::BAD_GATEWAY, unknown("TunnelStopFailed"))),
            DeleteOutcome::Retry
        );
        for status in [StatusCode::UNAUTHORIZED, StatusCode::TOO_MANY_REQUESTS] {
            assert_eq!(DeleteOutcome::of(&api_error(status, unknown("NewLimit"))), DeleteOutcome::Retry);
        }

        assert_eq!(DeleteOutcome::of(&Err(ApiError::NotLoggedIn)), DeleteOutcome::Retry);
        let transport_error = ApiError::ApiClient(ClientError::Other(anyhow::anyhow!("connection refused")));
        assert_eq!(DeleteOutcome::of(&Err(transport_error)), DeleteOutcome::Retry);
        for kind in [
            ApiErrorKind::InternalError {},
            ApiErrorKind::RateLimitExceeded { pow_challenge: None },
            ApiErrorKind::AccountExpired {},
            ApiErrorKind::MissingOrInvalidAuthToken {},
            // The delete route moved, or the API no longer takes this client version.
            ApiErrorKind::NoApiRoute {},
            ApiErrorKind::NoLongerSupported {},
        ] {
            assert_eq!(
                DeleteOutcome::of(&api_error(StatusCode::INTERNAL_SERVER_ERROR, kind)),
                DeleteOutcome::Retry
            );
        }
    }

    #[test]
    fn a_failed_create_is_deleted_unless_the_api_rejected_it() {
        let rejected = |status, kind| create_rejected(&api_error(status, kind).unwrap_err());
        assert!(rejected(StatusCode::CONFLICT, ApiErrorKind::TunnelLimitExceeded {}));
        assert!(rejected(StatusCode::BAD_REQUEST, ApiErrorKind::WgKeyRotationRequired {}));
        assert!(rejected(
            StatusCode::TOO_MANY_REQUESTS,
            ApiErrorKind::RateLimitExceeded { pow_challenge: None }
        ));
        assert!(rejected(StatusCode::UNAUTHORIZED, ApiErrorKind::MissingOrInvalidAuthToken {}));

        assert!(!rejected(StatusCode::INTERNAL_SERVER_ERROR, ApiErrorKind::InternalError {}));
        let unknown = ApiErrorKind::Unknown(serde_json::json!({"TunnelStartFailed": {}}));
        assert!(!rejected(StatusCode::INTERNAL_SERVER_ERROR, unknown));
        let timeout = ApiError::ApiClient(ClientError::Other(anyhow::anyhow!("timed out")));
        assert!(!create_rejected(&timeout));
    }

    #[tokio::test]
    async fn speed_tunnel_from_the_api_config_answers_an_in_tunnel_ping() {
        let relay = TestRelay::spawn();
        let client_public_key = WgPubkey(PublicKey::from(&relay.client_secret_key()).to_bytes());
        // An IPv6 endpoint first: the relay sockets are IPv4 only, so the tunnel uses the IPv4 one.
        let (client, mut server) = udp_port_config(&relay, client_public_key, relay.udp_addr);
        server.endpoints.insert(0, "[2001:db8::1]:51820".parse().unwrap());

        let (conn, network_config) = connect(&client, &server, Some(1500), relay.client_secret_key(), None).await.unwrap();
        assert_eq!(conn.transport(), TransportKind::WireguardUdp);
        assert_eq!(network_config.mtu, 1420);
        assert_eq!(network_config.dns, [std::net::IpAddr::from(EXIT_TUNNEL_IP)]);
        assert_eq!(network_config.ipv4, Ipv4Addr::new(10, 75, 0, 2));

        // The first pong already came back; now a ping of the full tunnel MTU.
        let conn = Arc::new(conn);
        let request = echo_request(1, usize::from(network_config.mtu));
        QuicWgConnPacketSender::new(Some(&conn)).send(std::iter::once(request.as_slice()));
        let reply = timeout(Duration::from_secs(5), conn.receive()).await.unwrap().unwrap();
        assert_eq!(Some(&reply[..]), echo_reply(&request).as_deref());
        assert!(conn.traffic_stats().rx_bytes >= u64::from(network_config.mtu));
    }

    #[tokio::test]
    async fn speed_tunnel_needs_an_ipv4_endpoint() {
        let relay = TestRelay::spawn();
        let (client, mut server) = udp_port_config(&relay, WgPubkey([0; 32]), "[2001:db8::1]:51820".parse().unwrap());
        server.endpoints.retain(SocketAddr::is_ipv6);
        let error = connect(&client, &server, None, relay.client_secret_key(), None).await.err().unwrap();
        assert!(
            matches!(error, TunnelConnectError::NetworkConfig(NetworkConfigError::NoIpv4Endpoint)),
            "{error:?}"
        );
    }
}
