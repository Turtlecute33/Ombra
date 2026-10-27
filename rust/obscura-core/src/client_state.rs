use super::{
    errors::{ApiError, TunnelConnectError},
    network_config::TunnelNetworkConfig,
};
use crate::constants::{DEFAULT_API_BACKUP_DOMAIN, DEFAULT_API_URL, DEFAULT_RELAY_SNI};
use crate::debug_bundle::service::NetworkInfo;
use crate::debug_bundle::{debug_info::DebugInfo, dns::DebugTaskDns, http::DebugTaskHttp, task::debug_panic_error, task::run_debug_task};
use crate::dns::DnsResolver;
use crate::errors::ConfigDirty;
use crate::manager::TunnelArgs;
use crate::network_config::DnsContentBlock;
use crate::tunnel_state::TargetState;
use crate::udp_tunnel::{self, UdpTunnelClaims};
use crate::{config::ConfigHandle, config::ConfigKey, net::interface_mtu};
use crate::{config::PinnedLocation, exit_selection::ExitSelectionState};
use crate::{config::RotationReason, net::NetworkInterface, network_config::DnsConfig, quicwg::QuicWgConnHandshaking, wg_key_store::WgKeyStore};
use crate::{config::cached::ConfigCached, exit_selection::ExitSelector};
use crate::{
    config::{self, Config, ConfigLoadError, LocalNetworkAccess, TailscaleBypass, WireGuardBypass},
    errors::RelaySelectionError,
    quicwg::QuicWgConn,
};
use crate::{
    quic_tuning::{MtuChoices, MtuPolicy, QuicTuning, ack_frequency_enabled},
    quicwg::TUNNEL_MTU,
    relay_selection::{RelaySelector, RelayTransport, race_relay_handshakes},
};
use boringtun::x25519::{PublicKey, StaticSecret};
use obscuravpn_api::cmd::{CacheWgKey, ETagCmd, ExitList, ListExits2};
use obscuravpn_api::types::{AccountId, AccountInfo, AuthToken, OneExit, WgPubkey};
use obscuravpn_api::{
    Client, ClientError, Unauthenticated,
    cmd::{ApiErrorKind, AuthOptional, Cmd, CreateTunnel, DeleteTunnel, ListRelays},
    types::{ObfuscatedTunnelConfig, OneRelay, TunnelConfig},
};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Weak};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::watch::{Receiver, Sender};
use uuid::Uuid;

impl NetworkInfo {
    pub fn new(client_state: &ClientStateHandle) -> Self {
        let state = client_state.borrow();
        Self {
            network_interface: state.network_interface.clone(),
            network_interface_mtu: state.network_interface.as_ref().and_then(|interface| interface_mtu(interface).ok()),
        }
    }
}

// A convenience wrapper to act as message receiver (reevaluate when https://rust-lang.github.io/rfcs//3519-arbitrary-self-types-v2.html is stable)
#[derive(Clone)]
pub struct ClientStateHandle(Arc<Sender<ClientState>>);

pub struct ClientState {
    this: WeakClientStateHandle,
    cached_api_client: Option<Arc<Client>>,
    config: ConfigHandle,
    exit_update_lock: Arc<tokio::sync::Mutex<()>>,
    mtu: Option<u16>,
    network_interface: Option<NetworkInterface>,
    /// Bumped by `refresh_os_network_config`. It is part of the target state, so the tunnel loop runs again.
    os_network_config_refresh: u64,
    /// The current network lost full-size packets while the tunnel used the MTU floor (Ombra patch P4, B1 guard 2), so
    /// the next connects on it do without the floor.
    mtu_floor_failed: bool,
    relay_update_lock: Arc<tokio::sync::Mutex<()>>,
    /// The Speed mode tunnels that a task works on (Ombra patch P5).
    udp_tunnel_claims: Arc<UdpTunnelClaims>,
    wg_key_store: WgKeyStore,
    user_agent: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct AccountStatus {
    pub account_info: AccountInfo, // API
    pub last_updated_sec: u64,
}

impl Eq for AccountStatus {}

impl PartialEq for AccountStatus {
    fn eq(&self, other: &Self) -> bool {
        self.last_updated_sec == other.last_updated_sec
    }
}

impl ClientState {
    #[allow(clippy::new_ret_no_self)]
    pub fn new(
        config_dir: PathBuf,
        mut wg_key_store: WgKeyStore,
        config_key: Option<ConfigKey>,
        user_agent: String,
        force_init_inactive: bool,
    ) -> Result<ClientStateHandle, ConfigLoadError> {
        let mut config = ConfigHandle::new(config_dir, &wg_key_store, config_key)?;
        // The config holds the stored key now; the copy from the OS side is wiped (Ombra).
        if let WgKeyStore::Keychain { secret_key, .. } = &mut wg_key_store {
            *secret_key = None;
        }
        if force_init_inactive {
            config.change(|config| config.tunnel_active = false)
        }
        Ok(ClientStateHandle(Arc::new_cyclic(|weak| {
            tokio::sync::watch::channel(ClientState {
                this: WeakClientStateHandle(weak.clone()),
                config,
                cached_api_client: None,
                wg_key_store,
                mtu: None,
                network_interface: None,
                os_network_config_refresh: 0,
                mtu_floor_failed: false,
                exit_update_lock: Default::default(),
                relay_update_lock: Default::default(),
                udp_tunnel_claims: Default::default(),
                user_agent,
            })
            .0
        })))
    }

    pub fn target_state(&self) -> TargetState {
        TargetState {
            tunnel_args: self.config.tunnel_active.then_some(self.config.tunnel_args.clone()),
            network_interface: self.network_interface.clone(),
            dns_content_block: self.config.dns_content_block,
            use_system_dns: match self.config.dns {
                DnsConfig::Default => false,
                DnsConfig::System => true,
            },
            local_network_access: self.config.local_network_access,
            tailscale_bypass: self.config.tailscale_bypass,
            wireguard_bypass: self.config.wireguard_bypass,
            relay_transport: RelayTransport::new(self.config.tunnel_args.mode, &self.config.feature_flags, self.config.sni_relay.as_deref()),
            os_network_config_refresh: self.os_network_config_refresh,
        }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    pub fn base_url(&self) -> String {
        self.config.api_url.clone().unwrap_or(DEFAULT_API_URL.to_string())
    }

    /// The Obscura backup domain first, then the host of the user (OMBRA-SEC-007). The client accepts a certificate of
    /// any of these hosts for every request, also to the primary host, so a user host must not replace the backup domain.
    fn alternative_api_hosts(&self) -> Vec<String> {
        let mut hosts = vec![DEFAULT_API_BACKUP_DOMAIN.to_owned()];
        hosts.extend(self.config.api_host_alternate.clone().filter(|host| host != DEFAULT_API_BACKUP_DOMAIN));
        hosts
    }

    fn make_unauthenticated_api_client(&self) -> Result<Client<Unauthenticated>, ApiError> {
        let base_url = self.base_url();
        let network_interface = self.network_interface.clone();
        let alternative_hosts = self.alternative_api_hosts();
        tracing::info!(
            message_id = "By9iMtd5",
            ?network_interface,
            ?base_url,
            ?alternative_hosts,
            "creating new API client"
        );
        Client::new_unauthenticated(
            base_url,
            alternative_hosts,
            &self.user_agent,
            #[cfg(not(any(target_os = "android", target_os = "linux", target_os = "windows")))]
            network_interface.as_ref().map(|i| i.name.as_str()),
            #[cfg(target_os = "linux")]
            None,
            #[cfg(target_os = "windows")]
            network_interface.as_ref().map(|i| i.ip),
            #[cfg(target_os = "android")]
            None,
            #[cfg(target_os = "linux")]
            Some(crate::net::FWMARK),
            Some(DnsResolver::new(self.this.clone())),
        )
        .map_err(ClientError::from)
        .map_err(ApiError::from)
    }

    fn make_api_client(&self, account_id: AccountId) -> Result<Client, ApiError> {
        self.make_unauthenticated_api_client().map(|client| client.into_authenticated(account_id))
    }
}

pub struct TunnelConnection {
    pub conn: QuicWgConn,
    pub exit: OneExit,
    pub network_config: TunnelNetworkConfig,
    pub relay: OneRelay,
    pub tunnel_id: Uuid,
}

impl ClientStateHandle {
    pub fn borrow(&self) -> tokio::sync::watch::Ref<'_, ClientState> {
        self.0.borrow()
    }

    pub fn subscribe(&self) -> Receiver<ClientState> {
        self.0.subscribe()
    }

    pub(crate) fn change_config(&self, f: impl FnOnce(&mut Config)) {
        self.change(|inner| {
            inner.config.change(|config| {
                f(config);
            })
        });
    }

    fn change<T>(&self, f: impl FnOnce(&mut ClientState) -> T) -> T {
        let mut ret: Option<T> = None;
        self.0.send_modify(|inner| {
            ret = Some(f(inner));
        });
        ret.unwrap()
    }

    /// Log in or out.
    pub fn set_account_id(&self, account_id_and_auth_token: Option<(AccountId, Option<AuthToken>)>) -> Result<(), ConfigDirty> {
        let (account_id, auth_token) = match account_id_and_auth_token {
            Some((account_id, auth_token)) => (Some(account_id), auth_token),
            None => (None, None),
        };
        let account_changed = account_id != self.borrow().config.account_id;
        if account_changed {
            // The config of the next account forgets the Speed mode tunnels of this one, so delete them now with this
            // account's client (Ombra patch P5). A live tunnel deletes itself after its data path stopped.
            let owned = self.borrow().config.owned_udp_tunnel_ids.clone();
            if !owned.is_empty()
                && let Ok(api_client) = self.api_client()
            {
                self.delete_unclaimed_udp_tunnels(api_client, &owned);
            }
        }
        let mut kept_tunnel = false;
        self.change(|inner| {
            inner.config.change(|config| {
                if account_id != config.account_id {
                    // Log-out / Change User. Ombra (patch P3) keeps no list of earlier account ids, so the default
                    // empty `old_account_ids` also clears the ids that an older version stored.
                    let mut next = Config {
                        api_url: config.api_url.take(),
                        account_id,
                        cached_auth_token: auth_token.map(Into::into),
                        in_new_account_flow: config.in_new_account_flow,
                        ..Default::default()
                    };
                    if config.account_id.is_none() && config.tunnel_active {
                        // A login while a tunnel waits for it (always-on starts before the login): keep the tunnel
                        // and every setting of its target state (Ombra). Without this the TUN that blocks traffic
                        // closes for about 1 s, until Kotlin connects again.
                        kept_tunnel = true;
                        next.tunnel_active = true;
                        next.tunnel_args = std::mem::take(&mut config.tunnel_args);
                        next.dns_content_block = std::mem::take(&mut config.dns_content_block);
                        next.dns = std::mem::take(&mut config.dns);
                        next.local_network_access = std::mem::take(&mut config.local_network_access);
                        next.tailscale_bypass = std::mem::take(&mut config.tailscale_bypass);
                        next.wireguard_bypass = std::mem::take(&mut config.wireguard_bypass);
                        next.feature_flags = std::mem::take(&mut config.feature_flags);
                        next.sni_relay = config.sni_relay.take();
                    }
                    *config = next;
                } else {
                    tracing::warn!(message_id = "shia4Eph", "Setting auth token for logged in account. This isn't expected.");
                    config.cached_auth_token = auth_token.map(Into::into);
                }
            });
            tracing::info!(message_id = "Aish2eph", "Clearing cached API client: account ID or auth token changed.");
            inner.cached_api_client = None;
        });
        if account_changed {
            self.borrow().config.delete_backups();
        }
        if kept_tunnel {
            // The target state is the same, so only this ends the wait after the connects that failed without a login.
            crate::tunnel_state::retry_connect_now();
        }
        self.borrow().config.check_persisted()
    }

    pub fn get_cached_exit_list(&self) -> Option<ConfigCached<Arc<ExitList>>> {
        self.borrow().config.cached_exits.clone()
    }

    pub fn get_cached_relay_list(&self) -> Option<ConfigCached<Arc<Vec<OneRelay>>>> {
        self.borrow().config.cached_relays.clone()
    }

    pub fn set_pinned_exits(&self, pinned_locations: Vec<PinnedLocation>) {
        self.change_config(|config| {
            config.pinned_locations = pinned_locations;
        })
    }

    pub fn set_feature_flag(&self, flag: &str, active: bool) {
        self.change_config(|config| {
            config.feature_flags.set(flag, active);
        })
    }

    pub fn set_tunnel_target_state(&self, tunnel_args: Option<TunnelArgs>, active: Option<bool>) {
        self.change_config(|config| {
            if let Some(tunnel_args) = tunnel_args {
                config.tunnel_args = tunnel_args
            }
            if let Some(active) = active {
                config.tunnel_active = active
            }
        });
    }

    pub fn set_api_host_alternate(&self, value: Option<String>) {
        self.change(|inner| {
            inner.config.change(|config| {
                tracing::info!(
                    message_id = "jee1ieWa",
                    api_host_alternate_new = value,
                    api_host_alternate_old = config.api_host_alternate,
                    "Changing API alternate host.",
                );
                config.api_host_alternate = value;
            });
            tracing::info!(message_id = "ohj8Eich", "Clearing cached API client: API alternate host changed.");
            inner.cached_api_client = None;
        })
    }

    pub fn set_sni_relay(&self, value: Option<String>) {
        self.change_config(|config| {
            tracing::info!(
                message_id = "OZYPX4xh",
                sni_relay_new = value,
                sni_relay_old = config.sni_relay,
                "Changing Relay SNI.",
            );
            config.sni_relay = value;
        })
    }

    pub fn set_in_new_account_flow(&self, value: bool) {
        self.change_config(|config| {
            config.in_new_account_flow = value;
        })
    }

    pub fn set_api_url(&self, url: Option<String>) {
        self.change(|inner| {
            inner.config.change(|config| {
                config.api_url = url;
                config.wireguard_key_cache.rotate_now(RotationReason::ApiUrlChanged, &inner.wg_key_store);
            });
            tracing::info!(message_id = "Eequ6ahz", "Clearing cached API client: API URL changed.");
            inner.cached_api_client = None;
        })
    }

    pub fn set_dns_content_block(&self, value: DnsContentBlock) {
        self.change_config(move |config| config.dns_content_block = value)
    }

    pub fn set_network_interface(&self, network_interface: Option<NetworkInterface>) {
        let mtu = if let Some(interface) = &network_interface {
            let read_mtu = match interface_mtu(interface) {
                Ok(mtu) => {
                    tracing::info!(
                        message_id = "eePai0oh",
                        network_interface.mtu = mtu,
                        network_interface.mtu_hint = interface.mtu_hint,
                        network_interface.name = interface.name,
                        "Interface MTU.",
                    );
                    u16::try_from(mtu)
                        .inspect_err(|_| tracing::warn!(message_id = "uKFfXGSc", mtu, "MTU out of range"))
                        .ok()
                }
                Err(error) => {
                    tracing::warn!(
                        message_id = "kah4Ifoh",
                        ?error,
                        network_interface.mtu_hint = interface.mtu_hint,
                        network_interface.name = interface.name,
                        "Failed to get interface MTU.",
                    );
                    None
                }
            };
            // Ombra patch P4: the CLAT-aware hint of the OS side counts too.
            interface.decision_mtu(read_mtu)
        } else {
            None
        };

        self.change(|inner| {
            inner.mtu = mtu;
            if network_interface != inner.network_interface {
                // Another MTU hint for the same network keeps what Rust learned about the network.
                let same_network = matches!(
                    (&inner.network_interface, &network_interface),
                    (Some(old), Some(new)) if old.same_network(new)
                );
                inner.network_interface = network_interface;
                if !same_network {
                    inner.mtu_floor_failed = false;
                    tracing::info!(message_id = "iew0Ahk9", "Clearing cached API client: network interface changed.");
                    inner.cached_api_client = None;
                    // The API addresses of one network's resolver are not fresh on the next (Ombra patch P3).
                    inner.config.change(|config| config.dns_cache.expire_all());
                }
            }
        })
    }

    /// The MTU choices of a new QUIC connection on the current network (Ombra patch P4, D1).
    pub fn quic_mtu_choices(&self, force_small_mtu: bool) -> MtuChoices {
        let inner = self.borrow();
        MtuChoices::for_path(inner.mtu, force_small_mtu, inner.mtu_floor_failed)
    }

    /// Makes the tunnel loop apply the OS network config again while connecting or connected, also when it did not
    /// change, so that the OS side picks up its own settings (on Android: split tunnel, custom DNS). No reconnect.
    pub fn refresh_os_network_config(&self) {
        self.change(|inner| inner.os_network_config_refresh = inner.os_network_config_refresh.wrapping_add(1))
    }

    /// Connects on the current network do without the MTU floor until the network interface changes.
    pub fn set_mtu_floor_failed(&self) {
        self.change(|inner| inner.mtu_floor_failed = true)
    }

    pub fn set_auto_connect(&self, enable: bool) {
        self.change_config(|config| {
            config.auto_connect = enable;
        })
    }

    pub fn set_use_system_dns(&self, enable: bool) {
        self.change_config(|config| config.dns = if enable { DnsConfig::System } else { DnsConfig::Default })
    }

    pub fn set_local_network_access(&self, enable: bool) {
        self.change_config(|config| {
            config.local_network_access = if enable {
                LocalNetworkAccess::Enabled
            } else {
                LocalNetworkAccess::Disabled
            }
        })
    }

    pub fn set_tailscale_bypass(&self, enable: bool) {
        self.change_config(|config| config.tailscale_bypass = if enable { TailscaleBypass::Enabled } else { TailscaleBypass::Disabled })
    }

    pub fn set_wireguard_bypass(&self, enable: bool) {
        self.change_config(|config| config.wireguard_bypass = if enable { WireGuardBypass::Enabled } else { WireGuardBypass::Disabled })
    }

    pub async fn connect(
        &self,
        args: &TunnelArgs,
        relay_transport: &RelayTransport,
        network_interface: Option<&NetworkInterface>,
        selection_state: &mut ExitSelectionState,
    ) -> Result<TunnelConnection, TunnelConnectError> {
        // Speed mode tunnels of an earlier run hold slots in every mode (Ombra patch P5).
        self.delete_leftover_udp_tunnels();
        self.prepare_wg_key().await;
        if *relay_transport == RelayTransport::WireguardUdp {
            return self.connect_wireguard_udp(args, network_interface, selection_state).await;
        }
        let exit_selector = &args.exit;
        let (tunnel_id, tunnel_config, wg_sk, exit, relay, handshaking) = self
            .new_tunnel(exit_selector, &args.relay, relay_transport, network_interface, selection_state)
            .await?;
        let network_config = TunnelNetworkConfig::new(&tunnel_config, TUNNEL_MTU)?;
        let client_ip_v4 = network_config.ipv4;
        tracing::info!(
            message_id = "AtKb082I",
            tunnel.id =% tunnel_id,
            exit.public_key =? tunnel_config.exit_pubkey,
            "finishing tunnel connection");
        let remote_pk = PublicKey::from(tunnel_config.exit_pubkey.0);
        let ping_keepalive_ip = tunnel_config.gateway_ip_v4;
        let conn = QuicWgConn::connect(handshaking, wg_sk.clone(), remote_pk, client_ip_v4, ping_keepalive_ip, tunnel_id).await?;
        tracing::info!(message_id = "A2FDGY4A", tunnel.id =% tunnel_id, "tunnel connected");
        self.remember_exit(exit_selector, &exit);
        Ok(TunnelConnection { conn, exit, network_config, relay, tunnel_id })
    }

    /// Speed mode (Ombra patch P5): plain WireGuard over UDP on a `CreateTunnel::UdpPort` API tunnel. The relay race
    /// measures the RTT over QUIC as in the other modes, then its handshake is abandoned. A failed handshake does not
    /// fall back to QUIC.
    async fn connect_wireguard_udp(
        &self,
        args: &TunnelArgs,
        network_interface: Option<&NetworkInterface>,
        selection_state: &mut ExitSelectionState,
    ) -> Result<TunnelConnection, TunnelConnectError> {
        let (closest_relay, handshaking, exit) = self
            .select_relay_and_exit(&args.exit, &args.relay, &RelayTransport::WireguardUdp, network_interface, selection_state)
            .await?;
        tokio::spawn(handshaking.abandon());
        self.wait_for_udp_tunnel_deletes().await;

        // The create call and the delete of the lease use the same client, also when the account changes meanwhile.
        let (account_id, api_client) = self.account_api_client()?;
        let lease = self.lease_udp_tunnel(api_client.clone());
        let tunnel_id = lease.id();
        let (wg_private_key, wg_public_key) = self.use_wg_key_pair();
        tracing::info!(
            message_id = "Tq6wZn3H",
            exit.id = exit,
            relay.id = closest_relay.id,
            relay.ip_v4 =% closest_relay.ip_v4,
            tunnel.id =% tunnel_id,
            "creating WireGuard UDP tunnel",
        );
        let cmd = CreateTunnel::UdpPort {
            id: Some(tunnel_id),
            label: None,
            wg_pubkey: wg_public_key,
            relay: Some(closest_relay.id.clone()),
            exit: Some(exit),
        };
        // The create call runs to its end in its own task, also when this connect is cancelled. The lease drops only
        // after it, so the delete never overtakes the create.
        let this = self.clone();
        let (created, lease) = tokio::spawn(async move { (this.api_request_with(&account_id, &api_client, cmd).await, lease) })
            .await
            .unwrap();
        let tunnel = match created {
            Ok(tunnel) => tunnel,
            Err(error) => {
                lease.create_failed(&error, self);
                self.handle_create_tunnel_error(&error);
                return Err(error.into());
            }
        };
        if Uuid::parse_str(&tunnel.id).ok() != Some(tunnel_id) {
            tracing::error!(message_id = "Jb8nTf2X", tunnel.id =% tunnel_id, api_tunnel.id = tunnel.id, "API made the tunnel with another id");
            let this = self.clone();
            tokio::spawn(async move {
                if let Err(error) = this.api_request(DeleteTunnel { id: tunnel.id }).await {
                    tracing::warn!(message_id = "Wm1cRv9L", ?error, "failed to delete the tunnel with the unexpected id");
                }
            });
            return Err(TunnelConnectError::InvalidTunnelId);
        }
        if tunnel.relay.id != closest_relay.id {
            return Err(TunnelConnectError::UnexpectedRelay);
        }
        let TunnelConfig::UdpPort { client, server } = tunnel.config else {
            return Err(TunnelConnectError::UnexpectedTunnelKind);
        };
        tracing::info!(
            message_id = "Ks5dHw7E",
            tunnel.id =% tunnel_id,
            exit.public_key =? server.wg_pubkey,
            server.endpoints =? server.endpoints,
            "finishing WireGuard UDP tunnel connection",
        );
        let (conn, network_config) = udp_tunnel::connect(&client, &server, args.udp_mtu, wg_private_key, network_interface).await?;
        tracing::info!(message_id = "Yc3gFp6R", tunnel.id =% tunnel_id, "tunnel connected");
        self.remember_exit(&args.exit, &tunnel.exit);
        Ok(TunnelConnection {
            conn: conn.with_close_guard(lease),
            exit: tunnel.exit,
            network_config,
            relay: tunnel.relay,
            tunnel_id,
        })
    }

    fn remember_exit(&self, exit_selector: &ExitSelector, exit: &OneExit) {
        self.change_config(|config| {
            if *exit_selector != (ExitSelector::Any {}) {
                config.last_chosen_exit = Some(exit.id.clone());
                config.last_chosen_exit_selector = exit_selector.clone();
            };
            config.last_exit_selector = exit_selector.clone();
        });
    }

    pub(crate) fn use_wg_key_pair(&self) -> (StaticSecret, WgPubkey) {
        self.change(|inner| inner.config.change(|config| config.wireguard_key_cache.use_key_pair(&inner.wg_key_store)))
    }

    /// The tunnel was not created. The next connect attempt retries.
    fn handle_create_tunnel_error(&self, error: &ApiError) {
        match error.api_error_kind() {
            Some(ApiErrorKind::WgKeyRotationRequired {}) => {
                tracing::warn!(
                    message_id = "1Dittpzj",
                    ?error,
                    "server indicated that key rotation is required immediately"
                );
                self.change(|inner| {
                    inner
                        .config
                        .change(|config| config.wireguard_key_cache.rotate_now(RotationReason::ApiRequested, &inner.wg_key_store))
                });
            }
            // Our own leftover Speed mode tunnels may hold the slots (Ombra patch P5).
            Some(ApiErrorKind::TunnelLimitExceeded {}) => self.delete_leftover_udp_tunnels(),
            _ => {}
        }
    }

    async fn new_tunnel(
        &self,
        exit_selector: &ExitSelector,
        relay_selector: &RelaySelector,
        relay_transport: &RelayTransport,
        network_interface: Option<&NetworkInterface>,
        selection_state: &mut ExitSelectionState,
    ) -> anyhow::Result<(Uuid, ObfuscatedTunnelConfig, StaticSecret, OneExit, OneRelay, QuicWgConnHandshaking), TunnelConnectError> {
        let (closest_relay, handshaking, exit) = self
            .select_relay_and_exit(exit_selector, relay_selector, relay_transport, network_interface, selection_state)
            .await?;
        // Deleted Speed mode tunnels free their slots first (Ombra patch P5).
        self.wait_for_udp_tunnel_deletes().await;

        let tunnel_id = Uuid::new_v4();
        let (wg_private_key, wg_public_key) = self.use_wg_key_pair();
        tracing::info!(
            message_id = "Ahv4Eequ",
            exit.id = exit,
            relay.id = closest_relay.id,
            relay.ip_v4 =% closest_relay.ip_v4,
            tunnel.id =% tunnel_id,
            "creating tunnel",
        );

        let cmd = CreateTunnel::Obfuscated {
            id: Some(tunnel_id),
            label: None,
            wg_pubkey: wg_public_key,
            relay: Some(closest_relay.id.clone()),
            exit: Some(exit.clone()),
        };
        let tunnel = match self.api_request(cmd).await {
            Ok(t) => t,
            Err(error) => {
                // Let the main maintenance loop handle retries.
                self.handle_create_tunnel_error(&error);
                return Err(error.into());
            }
        };

        if tunnel.relay.id != closest_relay.id {
            return Err(TunnelConnectError::UnexpectedRelay);
        }
        let TunnelConfig::Obfuscated(config) = tunnel.config else {
            return Err(TunnelConnectError::UnexpectedTunnelKind);
        };
        Ok((tunnel_id, config, wg_private_key, tunnel.exit, tunnel.relay, handshaking))
    }

    /// The relay with the lowest RTT, its handshake, and the id of the exit for it.
    async fn select_relay_and_exit(
        &self,
        exit_selector: &ExitSelector,
        relay_selector: &RelaySelector,
        relay_transport: &RelayTransport,
        network_interface: Option<&NetworkInterface>,
        selection_state: &mut ExitSelectionState,
    ) -> Result<(OneRelay, QuicWgConnHandshaking, String), TunnelConnectError> {
        let this = self.clone();
        let exit_update = tokio::spawn(async move {
            let r = this.maybe_update_exits(Duration::from_secs(60)).await;
            if let Err(error) = &r {
                tracing::warn!(message_id = "oH5aigha", ?error, "Failed to update exit list: {}", error);
            }
            r
        });

        let (closest_relay, handshaking) = self.select_relay(relay_selector, relay_transport, network_interface).await?;

        let exit_list = if let Some(l) = self.get_cached_exit_list() {
            l
        } else {
            exit_update.await.unwrap().map_err(TunnelConnectError::ApiError)?
        };

        let exit = selection_state
            .select_next_exit(exit_selector, &exit_list.value.exits, &closest_relay)
            .map(|e| e.id.clone());
        let Some(exit) = exit else {
            tracing::error!(
                message_id = "naiThei6",
                exit_selector =? exit_selector,
                "No exits matching selector."
            );
            return Err(TunnelConnectError::NoExit);
        };

        tracing::info!(
            message_id = "eiR8ixoh",
            exit.id = exit,
            exit_selector =? exit_selector,
            "Selected exit"
        );
        Ok((closest_relay, handshaking, exit))
    }

    pub async fn select_relay(
        &self,
        relay_selector: &RelaySelector,
        relay_transport: &RelayTransport,
        network_interface: Option<&NetworkInterface>,
    ) -> Result<(OneRelay, QuicWgConnHandshaking), TunnelConnectError> {
        let (sni, use_tcp_tls, quic_frame_padding, force_small_mtu) = match relay_transport {
            RelayTransport::Quic { sni, frame_padding, force_small_mtu } => (sni.clone(), false, *frame_padding, *force_small_mtu),
            RelayTransport::TcpTls { sni } => (sni.clone(), true, false, false),
            // Speed mode (Ombra patch P5) races over QUIC only for the RTT, with the SNI setting, and then abandons it.
            RelayTransport::WireguardUdp => {
                let sni = self.borrow().config.sni_relay.clone().unwrap_or_else(|| DEFAULT_RELAY_SNI.to_owned());
                (sni, false, false, false)
            }
        };
        let speed_mode = *relay_transport == RelayTransport::WireguardUdp;
        let this = self.clone();
        let relay_update = tokio::spawn(async move {
            let r = this.maybe_update_relays(Duration::from_secs(60)).await;
            if let Err(error) = &r {
                tracing::warn!(message_id = "J8LVTgQm", ?error, "Failed to update relay list: {}", error,);
            }
            r
        });
        let relays = if let Some(l) = self.get_cached_relay_list() {
            l
        } else {
            relay_update.await.unwrap()?
        };
        let candidates: Vec<OneRelay> = relays.value.iter().filter(|relay| relay_selector.matches(relay)).cloned().collect();
        if candidates.is_empty() {
            tracing::error!(
                message_id = "Rk4fZp8T",
                ?relay_selector,
                relays.count = relays.value.len(),
                "no relay matches the relay selector"
            );
            return Err(RelaySelectionError::NoMatchingRelay.into());
        }

        tracing::info!(
            message_id = "eech6Ier",
            relays.staleness_s =? relays.staleness().as_secs_f32(),
            relays.version =? relays.version(),
            relays.candidates = candidates.len(),
            sni = sni,
            "Racing relays",
        );
        let (mtu, mtu_floor_failed) = {
            let inner = self.borrow();
            (inner.mtu, inner.mtu_floor_failed)
        };
        let quic_tuning = QuicTuning {
            frame_padding: quic_frame_padding,
            // The Speed mode does not use the QUIC connection, so its race needs no MTU floor and no fallback race.
            mtu: match speed_mode {
                true => MtuPolicy::Discover,
                false => MtuPolicy::for_path(mtu, force_small_mtu, mtu_floor_failed),
            },
            ack_frequency: !speed_mode && ack_frequency_enabled(),
        };
        let race = race_relay_handshakes(network_interface, &candidates, sni, use_tcp_tls, quic_tuning, force_small_mtu, mtu)?;
        Ok(race.select().await?)
    }

    pub fn make_unauthenticated_api_client(&self) -> Result<Client<Unauthenticated>, ApiError> {
        self.borrow().make_unauthenticated_api_client()
    }

    pub fn make_api_client(&self, account_id: AccountId) -> Result<Client, ApiError> {
        self.borrow().make_api_client(account_id)
    }

    pub(crate) fn api_client(&self) -> Result<Arc<Client>, ApiError> {
        self.account_api_client().map(|(_, api_client)| api_client)
    }

    /// The API client with the account that it belongs to (Ombra): both come from the same state.
    pub(crate) fn account_api_client(&self) -> Result<(AccountId, Arc<Client>), ApiError> {
        if self.borrow().config.account_id.is_none() {
            return Err(ApiError::NotLoggedIn);
        }

        self.change(|inner| {
            let account_id = inner.config.account_id.clone().ok_or(ApiError::NotLoggedIn)?;
            if let Some(api_client) = inner.cached_api_client.clone() {
                Ok((account_id, api_client))
            } else {
                let api_client = Arc::new(inner.make_api_client(account_id.clone())?);
                if let Some(auth_token) = inner.config.cached_auth_token.clone() {
                    api_client.set_auth_token(Some(auth_token.into()));
                }
                Ok((account_id, inner.cached_api_client.insert(api_client).clone()))
            }
        })
    }

    /// Only into the config of `account_id` (Ombra): a request that was in flight during a logout or an account change
    /// must not give the old account's token to the next config.
    fn cache_auth_token(&self, account_id: &AccountId, api_client: &Client) {
        let auth_token = api_client.get_auth_token();
        self.change(|inner| {
            inner.config.change(|config| {
                if config.account_id.as_ref() == Some(account_id) {
                    config.cached_auth_token = auth_token.map(Into::into);
                }
            });
        })
    }

    pub async fn api_request<C: Cmd>(&self, cmd: C) -> Result<C::Output, ApiError> {
        let (account_id, api_client) = self.account_api_client()?;
        self.api_request_with(&account_id, &api_client, cmd).await
    }

    /// [`Self::api_request`] with a client that the caller got earlier from [`Self::account_api_client`].
    pub(crate) async fn api_request_with<C: Cmd>(&self, account_id: &AccountId, api_client: &Client, cmd: C) -> Result<C::Output, ApiError> {
        let result = api_client.run(cmd).await;
        self.cache_auth_token(account_id, api_client);
        self.expire_dns_cache_if_unreachable(&result);
        Ok(result?)
    }

    pub async fn api_request_without_auth<C: Cmd<Auth = AuthOptional>>(&self, cmd: C) -> Result<C::Output, ApiError> {
        let api_client = self.make_unauthenticated_api_client()?;
        let result = api_client.run(cmd).await;
        self.expire_dns_cache_if_unreachable(&result);
        Ok(result?)
    }

    pub async fn cached_api_request<C: ETagCmd>(&self, cmd: C, etag: Option<&[u8]>) -> Result<obscuravpn_api::Response<C::Output>, ApiError> {
        let (account_id, api_client) = self.account_api_client()?;
        let result = api_client.run_with_etag(cmd, etag).await;
        self.cache_auth_token(&account_id, &api_client);
        self.expire_dns_cache_if_unreachable(&result);
        Ok(result?)
    }

    /// A fresh API DNS cache entry can hold an address that no longer works. While the API is unreachable, each resolve
    /// also starts a system lookup again, as upstream does on every resolve (Ombra patch P3).
    pub(crate) fn expire_dns_cache_if_unreachable<T>(&self, result: &Result<T, ClientError>) {
        if let Err(ClientError::RequestExecError(error)) = result
            && (error.is_connect() || error.is_timeout())
        {
            self.expire_dns_cache();
        }
    }

    pub fn base_url(&self) -> String {
        self.borrow().base_url()
    }

    pub fn user_agent(&self) -> String {
        self.borrow().user_agent.clone()
    }

    pub async fn maybe_update_exits(&self, freshness: Duration) -> Result<ConfigCached<Arc<ExitList>>, ApiError> {
        let exit_update_lock = self.borrow().exit_update_lock.clone();
        let _exit_update_guard = exit_update_lock.lock().await;

        let prev = self.borrow().config.cached_exits.clone();
        if let Some(list) = &prev
            && list.staleness() < freshness
        {
            tracing::info!(
                message_id = "Io3jai7l",
                exits.staleness_s =? list.staleness().as_secs_f32(),
                exits.version =? list.version(),
                "Exit list fresh.",
            );
            return Ok(prev.unwrap());
        }

        let res = self.cached_api_request(ListExits2 {}, prev.as_ref().and_then(|p| p.etag())).await?;

        let etag = res.etag().map(|e| e.to_vec());

        let Some(body) = res.into_body() else {
            let Some(prev) = prev else {
                return Err(ClientError::Other(anyhow::anyhow!("got response without body despite sending no etag")).into());
            };
            tracing::info!(
                message_id = "dd1oXUQV",
                exits.staleness_s = prev.staleness().as_secs_f32(),
                exits.version =? prev.version(),
                "Exit list etag matches"
            );
            let cached_exits = prev.revalidated(etag);
            self.change_config(|config| config.cached_exits = Some(cached_exits.clone()));
            return Ok(cached_exits);
        };

        let version = match etag {
            Some(b) => config::cached::Version::ETag(b),
            None => {
                tracing::warn!(message_id = "meequa8P", "Exit list had no ETag.");
                config::cached::Version::artificial()
            }
        };
        let cached_exits = ConfigCached::new(Arc::new(body), version);
        tracing::info!(
            message_id = "hvrg8jRW",
            exits.count = cached_exits.value.exits.len(),
            version_old =? prev.as_ref().map(|p| p.version()),
            version_new =? cached_exits.version(),
            staleness_s =? prev.as_ref().map(|p| p.staleness().as_secs_f32()),
            "Exit list updated."
        );
        self.change_config(|config| config.cached_exits = Some(cached_exits.clone()));
        Ok(cached_exits)
    }

    pub async fn maybe_update_relays(&self, freshness: Duration) -> Result<ConfigCached<Arc<Vec<OneRelay>>>, ApiError> {
        let relay_update_lock = self.borrow().relay_update_lock.clone();
        let _relay_update_guard = relay_update_lock.lock().await;

        let prev = self.borrow().config.cached_relays.clone();
        if let Some(list) = &prev
            && list.staleness() < freshness
        {
            tracing::info!(
                message_id = "07da0OEW",
                relays.staleness_s = list.staleness().as_secs_f32(),
                relays.version =? list.version(),
                "Relay list fresh.",
            );
            return Ok(prev.unwrap());
        }

        let res = self.cached_api_request(ListRelays {}, prev.as_ref().and_then(|p| p.etag())).await?;

        let etag = res.etag().map(|e| e.to_vec());

        let Some(body) = res.into_body() else {
            let Some(prev) = prev else {
                return Err(ClientError::Other(anyhow::anyhow!("got response without body despite sending no etag")).into());
            };
            tracing::info!(
                message_id = "g7cbIVp2",
                relays.staleness_s = prev.staleness().as_secs_f32(),
                relays.version =? prev.version(),
                "Relay list etag matches"
            );
            let cached_relays = prev.revalidated(etag);
            self.change_config(|config| config.cached_relays = Some(cached_relays.clone()));
            return Ok(cached_relays);
        };

        let version = match etag {
            Some(b) => config::cached::Version::ETag(b),
            None => {
                tracing::warn!(message_id = "C0DRoScG", "Relay list had no ETag.");
                config::cached::Version::artificial()
            }
        };
        let cached_relays = ConfigCached::new(Arc::new(body), version);
        tracing::info!(
            message_id = "cAS1rZLA",
            relays.count = cached_relays.value.len(),
            version_old =? prev.as_ref().map(|p| p.version()),
            version_new =? cached_relays.version(),
            staleness_s =? prev.as_ref().map(|p| p.staleness().as_secs_f32()),
            "Relay list updated."
        );
        self.change_config(|config| config.cached_relays = Some(cached_relays.clone()));

        Ok(cached_relays)
    }

    pub fn update_account_info(&self, account_info: &AccountInfo) {
        let response_time = SystemTime::now();
        let last_updated_sec = response_time.duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO).as_secs();
        let account = Some(AccountStatus { account_info: account_info.clone(), last_updated_sec });
        self.change_config(|config| config.cached_account_status = account);
    }

    // Only intended to be called after use (on disconnect). Rotation schedules are fairly arbitrary, so using the key one more time is fine. The benefit is that we don't trigger rotation if the user stops using the client, but the client is still auto-starting. This does not imply the effect of `Self::register_cached_wireguard_key_if_new`. It's the callers responsibility to ensure that registration is triggered asap.
    pub fn rotate_wireguard_key_if_required(&self) {
        self.change(|inner| {
            inner.config.change(|config| {
                let max_age = config.wg_key_max_age();
                config.wireguard_key_cache.rotate_if_required(max_age, &inner.wg_key_store);
            })
        })
    }

    /// Before each new tunnel (Ombra patch P14). Upstream checks the key age only when the tunnel stops, and an
    /// always-on tunnel almost never stops. A new key is registered before the create call, so the API removes the old
    /// keys also when the client is never disconnected. A failed registration does not stop the connect.
    async fn prepare_wg_key(&self) {
        self.rotate_wireguard_key_if_required();
        if let Err(error) = self.register_cached_wireguard_key_if_new().await {
            tracing::warn!(message_id = "Kx7mRw2D", ?error, "could not register the wireguard key before connecting");
        }
    }

    pub fn set_wg_key_max_age(&self, secs: u64) {
        self.change_config(|config| config.wg_key_max_age_secs = Some(secs))
    }

    // Registers the current wireguard key via the API server if it has not been registered yet. Because this function is a NOOP after first successful use (until key rotation), it may be called frequently. Most importantly it should be called after disconnecting (due to possible key rotation) and after observing that the user paid.
    pub async fn register_cached_wireguard_key_if_new(&self) -> Result<(), ApiError> {
        let key_pair = self.change(|inner| {
            inner
                .config
                .change(|config| config.wireguard_key_cache.need_registration(&inner.wg_key_store))
        });
        let Some((current_public_key, old_public_keys)) = key_pair else {
            tracing::info!(message_id = "DLRFU37X", "public wireguard key already registered");
            return Ok(());
        };
        let cmd = CacheWgKey { public_key: current_public_key, previous_public_keys: old_public_keys.clone() };
        match self.api_request(cmd).await {
            Ok(()) => {
                self.change_config(|config| config.wireguard_key_cache.registered(current_public_key, &old_public_keys));
                tracing::info!(message_id = "OsG3QuGx", "successfully registered public wireguard key");
                Ok(())
            }
            Err(error) => {
                if matches!(error.api_error_kind(), Some(ApiErrorKind::WgKeyRotationRequired {})) {
                    tracing::warn!(
                        message_id = "n89x3fJF",
                        ?error,
                        "server indicated that key rotation is required immediately"
                    );
                    self.change(|inner| {
                        inner.config.change(|config| {
                            config.wireguard_key_cache.rotate_now(RotationReason::ApiRequested, &inner.wg_key_store);
                        })
                    })
                }
                Err(error)
            }
        }
    }

    pub fn rotate_wg_key(&self) {
        self.change(|inner| {
            inner.config.change(|config| {
                config.wireguard_key_cache.rotate_now(RotationReason::Manual, &inner.wg_key_store);
            })
        })
    }

    pub async fn get_debug_info(&self) -> DebugInfo {
        let config;
        let network_interface;
        let network_interface_mtu;
        {
            let this = self.borrow();
            config = this.config().clone().into();
            network_interface = this.network_interface.clone();
            network_interface_mtu = this.network_interface.as_ref().and_then(|interface| interface_mtu(interface).ok());
        }

        let dns_apple = tokio::spawn(run_debug_task(DebugTaskDns::run("www.apple.com")));
        let dns_google = tokio::spawn(run_debug_task(DebugTaskDns::run("google.com")));
        let dns_obscura = tokio::spawn(run_debug_task(DebugTaskDns::run("v1.api.prod.obscura.net")));

        let dns_apple = dns_apple.await.unwrap_or_else(debug_panic_error);
        let dns_google = dns_google.await.unwrap_or_else(debug_panic_error);
        let dns_obscura = dns_obscura.await.unwrap_or_else(debug_panic_error);

        let http_apple = tokio::spawn(run_debug_task(DebugTaskHttp::run(
            "https://www.apple.com/robots.txt",
            Some(dns_apple.result.get().map(|dns| dns.addrs.clone()).unwrap_or_default()),
            true,
            None,
        )));
        let http_google = tokio::spawn(run_debug_task(DebugTaskHttp::run(
            "https://google.com/robots.txt",
            Some(dns_google.result.get().map(|dns| dns.addrs.clone()).unwrap_or_default()),
            true,
            None,
        )));

        let http_nosni = tokio::spawn(run_debug_task(DebugTaskHttp::run(
            "https://v1.api.prod.obscura.net/api/ping",
            Some(dns_obscura.result.get().map(|dns| dns.addrs.clone()).unwrap_or_default()),
            false,
            None,
        )));

        let http_obscura = tokio::spawn(run_debug_task(DebugTaskHttp::run(
            "https://v1.api.prod.obscura.net/api/ping",
            Some(dns_obscura.result.get().map(|dns| dns.addrs.clone()).unwrap_or_default()),
            true,
            None,
        )));
        let http_obscura_apple = tokio::spawn(run_debug_task(DebugTaskHttp::run(
            "https://apple.com/api/ping",
            Some(dns_obscura.result.get().map(|dns| dns.addrs.clone()).unwrap_or_default()),
            true,
            None,
        )));
        let http_obscura_google = tokio::spawn(run_debug_task(DebugTaskHttp::run(
            "https://google.com/api/ping",
            Some(dns_obscura.result.get().map(|dns| dns.addrs.clone()).unwrap_or_default()),
            true,
            None,
        )));

        DebugInfo {
            config,
            dns_apple,
            dns_google,
            dns_obscura,
            http_apple: http_apple.await.unwrap_or_else(debug_panic_error),
            http_google: http_google.await.unwrap_or_else(debug_panic_error),
            http_nosni: http_nosni.await.unwrap_or_else(debug_panic_error),
            http_obscura: http_obscura.await.unwrap_or_else(debug_panic_error),
            http_obscura_apple: http_obscura_apple.await.unwrap_or_else(debug_panic_error),
            http_obscura_google: http_obscura_google.await.unwrap_or_else(debug_panic_error),
            network_interface,
            network_interface_mtu,
        }
    }

    pub fn update_dns_cache(&self, name: &str, addrs: &[SocketAddr]) {
        self.change_config(|config| {
            config.dns_cache.set(name, addrs);
        })
    }

    pub(crate) fn expire_dns_cache(&self) {
        self.change_config(|config| config.dns_cache.expire_all())
    }

    pub(crate) fn downgrade(&self) -> WeakClientStateHandle {
        WeakClientStateHandle(Arc::downgrade(&self.0))
    }

    pub(crate) fn udp_tunnel_claims(&self) -> Arc<UdpTunnelClaims> {
        self.borrow().udp_tunnel_claims.clone()
    }
}

#[derive(Clone)]
pub struct WeakClientStateHandle(Weak<Sender<ClientState>>);

impl WeakClientStateHandle {
    pub fn upgrade(&self) -> Option<ClientStateHandle> {
        self.0.upgrade().map(ClientStateHandle)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manager::TunnelMode;
    use crate::quicwg::{QuicWgConnPacketSender, TransportKind};
    use crate::test_relay::{ApiRequest, SpeedSetup, echo_reply, echo_request, wait_until};
    use obscuravpn_api::types::CountryCode;
    use tempfile::tempdir;
    use tokio::time::{sleep, timeout};

    fn client_state(config_dir: &std::path::Path) -> ClientStateHandle {
        ClientState::new(config_dir.into(), WgKeyStore::None, None, "test".into(), false).unwrap()
    }

    #[test]
    fn transport_settings_change_the_target_only_when_they_apply_to_the_mode() {
        let dir = tempdir().unwrap();
        let client_state = client_state(dir.path());
        let target = || client_state.borrow().target_state();
        client_state.set_tunnel_target_state(Some(TunnelArgs::default()), Some(true));

        let before = target();
        client_state.set_feature_flag("quicFramePadding", true);
        assert_ne!(target(), before, "padding applies to QUIC");
        let before = target();
        client_state.set_feature_flag("forceSmallMtu", true);
        assert_ne!(target(), before, "small MTU applies to QUIC");
        let before = target();
        client_state.set_sni_relay(Some("sni.example".into()));
        assert_ne!(target(), before, "SNI applies to QUIC");
        let before = target();
        client_state.set_feature_flag("killSwitch", true);
        assert_eq!(target(), before, "killSwitch has no effect on the tunnel");

        client_state.set_tunnel_target_state(Some(TunnelArgs { mode: TunnelMode::TcpTls, ..Default::default() }), None);
        let before = target();
        client_state.set_feature_flag("quicFramePadding", false);
        client_state.set_feature_flag("forceSmallMtu", false);
        client_state.set_feature_flag("tcpTlsTunnel", true);
        assert_eq!(
            target(),
            before,
            "TCP/TLS ignores the QUIC options and already has the effect of tcpTlsTunnel"
        );
        client_state.set_sni_relay(None);
        assert_ne!(target(), before, "SNI applies to TCP/TLS");

        let before = target();
        let relay = RelaySelector::Country { country_code: CountryCode("DE".into()) };
        client_state.set_tunnel_target_state(Some(TunnelArgs { relay, mode: TunnelMode::TcpTls, ..Default::default() }), None);
        assert_ne!(target().tunnel_args, before.tunnel_args, "a relay change reconnects");
    }

    /// OMBRA-SEC-007: the host of the user comes after the Obscura backup domain and does not replace it.
    #[test]
    fn a_user_api_host_comes_after_the_backup_domain() {
        let dir = tempdir().unwrap();
        let client_state = client_state(dir.path());
        assert_eq!(client_state.borrow().alternative_api_hosts(), [DEFAULT_API_BACKUP_DOMAIN]);
        client_state.set_api_host_alternate(Some("api.example".into()));
        assert_eq!(client_state.borrow().alternative_api_hosts(), [DEFAULT_API_BACKUP_DOMAIN, "api.example"]);
        client_state.set_api_host_alternate(Some(DEFAULT_API_BACKUP_DOMAIN.into()));
        assert_eq!(client_state.borrow().alternative_api_hosts(), [DEFAULT_API_BACKUP_DOMAIN]);
    }

    /// Ombra patch P4: the MTU hint of the OS side counts in the MTU floor decision, and another hint for the same
    /// network keeps what the client learned about the network.
    #[test]
    fn mtu_hint_drives_the_mtu_floor_decision() {
        let dir = tempdir().unwrap();
        let client_state = client_state(dir.path());
        // The loopback interface: its MTU allows the floor, also where it has another name and Rust reads no MTU.
        let lo0 = |network_handle, mtu_hint| NetworkInterface {
            name: "lo0".into(),
            index: crate::positive_u31::PositiveU31::try_from(1).unwrap(),
            network_handle,
            mtu_hint,
        };
        let policy = || {
            let inner = client_state.borrow();
            MtuPolicy::for_path(inner.mtu, false, inner.mtu_floor_failed)
        };
        client_state.set_network_interface(Some(lo0(1, None)));
        assert_eq!(policy(), MtuPolicy::Floor);
        // CLAT with a small MTU.
        client_state.set_network_interface(Some(lo0(1, Some(1280))));
        assert_eq!(client_state.borrow().mtu, Some(1280));
        assert_eq!(policy(), MtuPolicy::Discover);
        client_state.set_network_interface(Some(lo0(1, Some(1480))));
        assert_eq!(policy(), MtuPolicy::Floor);

        client_state.set_mtu_floor_failed();
        client_state.set_network_interface(Some(lo0(1, Some(1500))));
        assert!(client_state.borrow().mtu_floor_failed, "the same network with another hint");
        client_state.set_network_interface(Some(lo0(2, Some(1500))));
        assert!(!client_state.borrow().mtu_floor_failed, "another network");
    }

    #[test]
    fn account_change_keeps_no_earlier_account_ids() {
        let dir = tempdir().unwrap();
        let config_json = || std::fs::read_to_string(dir.path().join("config.json")).unwrap();
        let [earlier, current, next] = ["test-account-earlier", "test-account-current", "test-account-next"].map(String::from);
        // A config.json that an older build wrote: it kept the earlier account id.
        let config = Config {
            account_id: Some(AccountId::from_string_unchecked(current.clone())),
            old_account_ids: vec![AccountId::from_string_unchecked(earlier.clone())],
            ..Default::default()
        };
        config::save(dir.path(), &config).unwrap();
        let client_state = client_state(dir.path());

        client_state.set_account_id(None).unwrap();
        assert_eq!(client_state.borrow().config().account_id, None);
        assert!(client_state.borrow().config().old_account_ids.is_empty());
        assert!(!config_json().contains(&earlier) && !config_json().contains(&current));

        client_state
            .set_account_id(Some((AccountId::from_string_unchecked(current.clone()), None)))
            .unwrap();
        client_state
            .set_account_id(Some((AccountId::from_string_unchecked(next.clone()), None)))
            .unwrap();
        assert!(client_state.borrow().config().old_account_ids.is_empty());
        assert!(!config_json().contains(&current) && config_json().contains(&next));
    }

    /// A login while a tunnel waits for it keeps the tunnel and its settings, so its TUN stays. A change of account
    /// and a logout still stop the tunnel.
    #[test]
    fn a_login_keeps_a_tunnel_that_waits_for_it() {
        let dir = tempdir().unwrap();
        let client_state = client_state(dir.path());
        let account = |id: &str| Some((AccountId::from_string_unchecked(id.into()), None));
        let relay = RelaySelector::Country { country_code: CountryCode("DE".into()) };
        client_state.set_tunnel_target_state(
            Some(TunnelArgs { relay, mode: TunnelMode::WireguardUdp, ..Default::default() }),
            Some(true),
        );
        client_state.set_feature_flag("quicFramePadding", true);
        client_state.set_sni_relay(Some("sni.example".into()));
        let waiting = client_state.borrow().target_state();

        client_state.set_account_id(account("test-account-a")).unwrap();
        assert_eq!(client_state.borrow().target_state(), waiting);
        assert_eq!(client_state.borrow().config().feature_flags.quic_frame_padding, Some(true));

        client_state.set_account_id(account("test-account-b")).unwrap();
        assert_eq!(
            client_state.borrow().target_state().tunnel_args,
            None,
            "an account change stops the tunnel"
        );

        client_state.set_tunnel_target_state(None, Some(true));
        client_state.set_account_id(None).unwrap();
        assert_eq!(client_state.borrow().target_state().tunnel_args, None, "a logout stops the tunnel");
    }

    /// The copy of a config file that did not parse can hold the account number and a token: an account change
    /// deletes it. The secret key from the OS side is wiped once the config is loaded.
    #[test]
    fn an_account_change_deletes_config_backups_and_the_key_from_the_os_is_wiped() {
        let dir = tempdir().unwrap();
        std::fs::write(dir.path().join("config.json"), "not json").unwrap();
        let key_store = WgKeyStore::Keychain {
            secret_key: Some(zeroize::Zeroizing::new(vec![7; 32])),
            set_secret_key: Box::new(|_: &[u8; 32]| true),
        };
        let client_state = ClientState::new(dir.path().into(), key_store, None, "test".into(), false).unwrap();
        assert!(matches!(
            &client_state.borrow().wg_key_store,
            WgKeyStore::Keychain { secret_key: None, .. }
        ));
        let backups = || {
            let names = std::fs::read_dir(dir.path())
                .unwrap()
                .map(|entry| entry.unwrap().file_name().into_string().unwrap());
            names.filter(|name| name.starts_with("config-backup-")).count()
        };
        assert_eq!(backups(), 1);

        client_state
            .set_account_id(Some((AccountId::from_string_unchecked("test-account-a".into()), None)))
            .unwrap();
        assert_eq!(backups(), 0);
    }

    /// The token of a request that was in flight during an account change stays out of the next account's config.
    #[test]
    fn a_token_goes_only_into_the_config_of_its_account() {
        let dir = tempdir().unwrap();
        let client_state = client_state(dir.path());
        let [a, b] = ["test-account-a", "test-account-b"].map(|id| AccountId::from_string_unchecked(id.into()));
        client_state.set_account_id(Some((a.clone(), None))).unwrap();
        let (account_id, api_client) = client_state.account_api_client().unwrap();
        assert_eq!(account_id, a);
        api_client.set_auth_token(Some(AuthToken::from("test-token-a".to_string())));

        client_state.set_account_id(Some((b, None))).unwrap();
        client_state.cache_auth_token(&account_id, &api_client);
        assert_eq!(client_state.borrow().config().cached_auth_token, None);
        client_state.set_account_id(None).unwrap();
        client_state.cache_auth_token(&account_id, &api_client);
        assert_eq!(client_state.borrow().config().cached_auth_token, None, "also not after a logout");

        client_state.set_account_id(Some((a, None))).unwrap();
        client_state.cache_auth_token(&account_id, &api_client);
        assert_eq!(client_state.borrow().config().cached_auth_token.as_deref(), Some("test-token-a"));
    }

    /// Ombra patch P5 on the loopback interface, with stand-ins for the API and the relay: the race over QUIC, the
    /// `UdpPort` create call with the tunnel id already in config.json, pings in the tunnel, and the delete after the
    /// connection is dropped.
    #[tokio::test]
    async fn speed_mode_creates_uses_and_deletes_its_api_tunnel() {
        let setup = SpeedSetup::new(|relay| relay.udp_addr);
        let SpeedSetup { client_state, api, args, .. } = &setup;
        let connection = client_state
            .connect(args, &RelayTransport::WireguardUdp, None, &mut ExitSelectionState::default())
            .await
            .unwrap();
        let tunnel_id = connection.tunnel_id;
        assert_eq!(*setup.id_in_config_at_create.lock().unwrap(), [true]);
        let requests = api.requests();
        // The key of a new config is not registered yet: that comes first (Ombra patch P14).
        let [
            ApiRequest { method: register_method, path: register_path, body: register, .. },
            ApiRequest { method, path, body: create, .. },
        ] = &requests[..]
        else {
            panic!("unexpected requests {requests:?}")
        };
        assert_eq!((register_method.as_str(), register_path.as_str()), ("POST", "/api/cache_wg_key"));
        assert_eq!(register["public_key"], serde_json::to_value(setup.client_public_key).unwrap());
        assert_eq!((method.as_str(), path.as_str()), ("POST", "/api/tunnel"));
        let RelaySelector::Relay { id: relay_id } = &args.relay else {
            unreachable!()
        };
        let ExitSelector::Exit { id: exit_id } = &args.exit else {
            unreachable!()
        };
        assert_eq!(
            *create,
            serde_json::json!({
                "type": "udp_port",
                "id": tunnel_id.to_string(),
                "label": null,
                "wg_pubkey": serde_json::to_value(setup.client_public_key).unwrap(),
                "relay": relay_id,
                "exit": exit_id,
            })
        );
        assert_eq!(client_state.borrow().config().owned_udp_tunnel_ids, [tunnel_id]);
        assert_eq!(connection.conn.transport(), TransportKind::WireguardUdp);
        assert_eq!(connection.network_config.mtu, 1400);

        let conn = Arc::new(connection.conn);
        let request = echo_request(1, 1400);
        QuicWgConnPacketSender::new(Some(&conn)).send(std::iter::once(request.as_slice()));
        let reply = timeout(Duration::from_secs(5), conn.receive()).await.unwrap().unwrap();
        assert_eq!(Some(&reply[..]), echo_reply(&request).as_deref());
        assert!(api.deleted_tunnel_ids().is_empty(), "the live tunnel was deleted");

        drop(conn);
        wait_until("the tunnel is deleted", || api.deleted_tunnel_ids() == [tunnel_id.to_string()]).await;
        let forgotten = || client_state.borrow().config().owned_udp_tunnel_ids.is_empty();
        wait_until("the config forgets the tunnel", forgotten).await;
    }

    /// A connect that is cancelled while the API makes the tunnel deletes the tunnel only after the API answered, so
    /// the delete cannot reach the API first and leave the tunnel behind.
    #[tokio::test]
    async fn a_connect_cancelled_during_the_create_call_deletes_after_the_answer() {
        const CREATE_DELAY: Duration = Duration::from_millis(300);
        // Bound as a whole: a pattern with `..` on the temporary would drop the relay and the config dir at once.
        let setup = SpeedSetup::new(|relay| relay.udp_addr);
        let SpeedSetup { client_state, api, args, .. } = &setup;
        api.delay_creates(CREATE_DELAY);
        let mut selection_state = ExitSelectionState::default();
        let connect = client_state.connect(args, &RelayTransport::WireguardUdp, None, &mut selection_state);
        tokio::select! {
            _ = connect => panic!("connected although the create call was still waiting"),
            () = wait_until("the create call reaches the API", || !api.tunnel_ids("POST").is_empty()) => {}
        }

        wait_until("the tunnel is deleted", || !api.deleted_tunnel_ids().is_empty()).await;
        assert_eq!(api.deleted_tunnel_ids(), api.tunnel_ids("POST"));
        let requests = api.requests();
        let [_register, create, delete] = &requests[..] else {
            panic!("unexpected requests {requests:?}")
        };
        assert!(delete.received_at >= create.received_at + CREATE_DELAY, "{requests:?}");
        wait_until("the config forgets the tunnel", || {
            client_state.borrow().config().owned_udp_tunnel_ids.is_empty()
        })
        .await;
    }

    /// Leftovers of an earlier run are deleted at the next start, and those of an account at its logout, with its own
    /// client. Ids that a task works on are left alone.
    #[tokio::test]
    async fn leftover_speed_tunnels_are_deleted_at_start_and_at_logout() {
        let setup = SpeedSetup::new(|relay| relay.udp_addr);
        let SpeedSetup { client_state, api, .. } = &setup;
        let [leftover, live, at_logout] = [(); 3].map(|()| Uuid::new_v4());
        client_state.change_config(|config| config.owned_udp_tunnel_ids = vec![leftover, live]);
        client_state.udp_tunnel_claims().claim_for_test(live);

        client_state.delete_leftover_udp_tunnels();
        wait_until("the leftover is deleted", || api.deleted_tunnel_ids() == [leftover.to_string()]).await;
        wait_until("the config forgets it", || client_state.borrow().config().owned_udp_tunnel_ids == [live]).await;

        client_state.change_config(|config| config.owned_udp_tunnel_ids.push(at_logout));
        client_state.set_account_id(None).unwrap();
        assert!(client_state.borrow().config().owned_udp_tunnel_ids.is_empty());
        wait_until("the logout deletes the leftover", || api.deleted_tunnel_ids().len() == 2).await;
        assert_eq!(api.deleted_tunnel_ids(), [leftover.to_string(), at_logout.to_string()]);
    }

    /// The config of the next account does not have the old account's tunnel ids, so a failed delete at logout is
    /// tried again by the delete task, with the old account's client.
    #[tokio::test]
    async fn a_delete_that_fails_at_logout_is_tried_again() {
        let setup = SpeedSetup::new(|relay| relay.udp_addr);
        let SpeedSetup { client_state, api, .. } = &setup;
        let at_logout = Uuid::new_v4();
        client_state.change_config(|config| config.owned_udp_tunnel_ids = vec![at_logout]);
        api.fail_deletes(2);

        client_state.set_account_id(None).unwrap();
        wait_until("the first delete", || api.deleted_tunnel_ids().len() == 1).await;
        let start = std::time::Instant::now();
        client_state.wait_for_udp_tunnel_deletes().await;
        assert!(
            start.elapsed() < Duration::from_secs(1),
            "a connect waits for the deletes that are tried again"
        );
        wait_until("the delete is tried again until it works", || api.deleted_tunnel_ids().len() == 3).await;
        assert_eq!(api.deleted_tunnel_ids(), vec![at_logout.to_string(); 3]);
        sleep(Duration::from_millis(500)).await;
        assert_eq!(api.deleted_tunnel_ids().len(), 3, "no delete after it worked");
    }

    /// Ombra patch P14: before each new tunnel a key rotates once it is as old as the max age that Kotlin sets, also
    /// when its registration fails (here: no account).
    #[tokio::test]
    async fn a_due_key_rotates_before_a_new_tunnel() {
        let dir = tempdir().unwrap();
        let client_state = client_state(dir.path());
        let (_, first) = client_state.use_wg_key_pair();
        client_state.prepare_wg_key().await;
        assert_eq!(client_state.use_wg_key_pair().1, first, "the default max age is 30 days");

        client_state.set_wg_key_max_age(0);
        assert_eq!(client_state.borrow().config().wg_key_max_age(), Duration::ZERO);
        client_state.prepare_wg_key().await;
        assert_ne!(client_state.use_wg_key_pair().1, first);
    }
}
