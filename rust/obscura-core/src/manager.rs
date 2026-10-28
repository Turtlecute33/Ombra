use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};

use camino::Utf8PathBuf;
use obscuravpn_api::{
    cmd::{
        AppleAssociateAccount, AppleAssociateAccountOutput, AuthOptional, Cmd, DeleteAccount, DeleteAccountOutput, ExitList, GetAccountInfo,
        GoogleAssociateAccount, GoogleBillingDetails, GoogleBillingDetailsOutput, ReferralCode, ServiceStatus, ServiceStatusOutput, UseReferralCode,
    },
    types::{AccountId, AccountInfo, OneExit, OneRelay, WgPubkey},
};
use serde::{Deserialize, Serialize};
use tokio::select;
use tokio::sync::watch::{Receiver, Sender, channel};
use uuid::Uuid;

use crate::{
    backoff::Backoff,
    cached_value::CachedValue,
    client_state::{AccountStatus, ClientState, ClientStateHandle},
    config::{Config, ConfigKey, ConfigLoadError, PinnedLocation, cached::ConfigCached, feature_flags::FeatureFlags},
    debug_bundle::{
        bundle_info::BundleInfo,
        create_debug_bundle,
        debug_info::DebugInfo,
        service,
        service::{NetworkInfo, ServiceDebugBundleHandle, ServiceDebugBundleToken},
    },
    errors::{ApiError, ConfigDirty, ConfigDirtyOrApiError, ConnectErrorCode},
    exit_selection::ExitSelector,
    logging::LogPersistence,
    manager_cmd::{ManagerCmdErrorCode, ManagerCmdOk, PeerUid},
    net::NetworkInterface,
    network_config::DnsContentBlock,
    os::os_trait::Os,
    quicwg::TransportKind,
    relay_selection::RelaySelector,
    tunnel_state::TunnelState,
    wg_key_store::WgKeyStore,
};

pub struct Manager {
    client_state: ClientStateHandle,
    tunnel_state: Receiver<TunnelState>,
    status_watch: Sender<Status>,
    log_persistence: Option<LogPersistence>,
    service_debug_bundles: Mutex<HashMap<ServiceDebugBundleToken, Utf8PathBuf>>,
}

// Keep synchronized with ../../apple/shared/NetworkExtensionIpc.swift
#[derive(Debug, Serialize, PartialEq, Eq, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub version: Uuid,
    pub vpn_status: VpnStatus,
    pub account_id: Option<AccountId>,
    pub in_new_account_flow: bool,
    pub pinned_locations: Vec<PinnedLocation>,
    pub last_chosen_exit: ExitSelector,
    pub last_exit: ExitSelector,
    pub api_url: String,
    pub account: Option<AccountStatus>,
    pub auto_connect: bool,
    pub feature_flags: FeatureFlags,
    pub feature_flag_keys: Vec<String>,
    pub use_system_dns: bool,
    pub local_network_access: bool,
    pub tailscale_bypass: bool,
    pub wireguard_bypass: bool,
    pub dns_content_block: DnsContentBlock,
}

impl Status {
    fn new(version: Uuid, vpn_status: VpnStatus, client_state: &ClientState) -> Self {
        let Config {
            account_id,
            in_new_account_flow,
            pinned_locations,
            last_chosen_exit_selector,
            last_exit_selector,
            cached_account_status,
            auto_connect,
            feature_flags,
            dns,
            dns_content_block,
            local_network_access,
            tailscale_bypass,
            wireguard_bypass,
            ..
        } = client_state.config();
        let api_url = client_state.base_url();
        Self {
            version,
            vpn_status,
            account_id: account_id.clone(),
            in_new_account_flow: *in_new_account_flow,
            pinned_locations: pinned_locations.clone(),
            last_chosen_exit: last_chosen_exit_selector.clone(),
            last_exit: last_exit_selector.clone(),
            api_url,
            account: cached_account_status.clone(),
            auto_connect: *auto_connect,
            feature_flags: feature_flags.clone(),
            feature_flag_keys: FeatureFlags::KEYS.iter().map(ToString::to_string).collect(),
            use_system_dns: dns.is_system(),
            local_network_access: local_network_access.is_enabled(),
            tailscale_bypass: tailscale_bypass.is_enabled(),
            wireguard_bypass: wireguard_bypass.is_enabled(),
            dns_content_block: *dns_content_block,
        }
    }
}

// Keep synchronized with ../../apple/shared/NetworkExtensionIpc.swift
#[derive(Debug, Serialize, PartialEq, Eq, Clone, Deserialize)]
#[serde(rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum VpnStatus {
    Connecting {
        tunnel_args: TunnelArgs,
        connect_error: Option<ConnectErrorCode>,
        reconnecting: bool,
    },
    Connected {
        tunnel_args: TunnelArgs,
        exit: OneExit,
        relay: OneRelay,
        client_public_key: WgPubkey,
        exit_public_key: WgPubkey,
        transport: TransportKind,
    },
    Disconnected {},
}

/// Every field after `exit` is an Ombra addition (patch P1) with a serde default, so upstream JSON and older
/// config files still decode.
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct TunnelArgs {
    pub exit: ExitSelector,
    #[serde(default)]
    pub relay: RelaySelector,
    #[serde(default)]
    pub mode: TunnelMode,
    /// Tunnel MTU in `TunnelMode::WireguardUdp`. The other modes ignore it.
    #[serde(default)]
    pub udp_mtu: Option<u16>,
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone, Copy, Default)]
#[serde(rename_all = "camelCase")]
pub enum TunnelMode {
    /// WireGuard in QUIC datagrams to the relay (upstream behaviour).
    #[default]
    Quic,
    /// WireGuard over TLS over TCP to the relay, the same as the `tcpTlsTunnel` feature flag.
    TcpTls,
    /// Plain WireGuard over UDP to the relay (Ombra patch P5).
    WireguardUdp,
}

impl VpnStatus {
    fn from_tunnel_state(tunnel_state: &TunnelState) -> Self {
        match tunnel_state {
            TunnelState::Disconnected => VpnStatus::Disconnected {},
            TunnelState::Connecting { args, connect_error, disconnect_reason, offset_traffic_stats: _, network_interface: _ } => {
                VpnStatus::Connecting {
                    tunnel_args: args.clone(),
                    connect_error: connect_error.as_ref().map(|error_at| ConnectErrorCode::from(&error_at.error)),
                    reconnecting: disconnect_reason.is_some(),
                }
            }
            TunnelState::Connected {
                args,
                conn,
                relay,
                exit,
                network_config: _,
                offset_traffic_stats: _,
                network_interface: _,
                relay_transport: _,
                tunnel_id: _,
            } => VpnStatus::Connected {
                tunnel_args: args.clone(),
                relay: relay.clone(),
                exit: exit.clone(),
                client_public_key: WgPubkey(conn.client_public_key().to_bytes()),
                exit_public_key: WgPubkey(conn.exit_public_key().to_bytes()),
                transport: conn.transport(),
            },
        }
    }
}

impl Manager {
    /// The constructed `Arc<Manager>` can not be dropped due to spawned tasks, which hold references.
    /// The `network_interface` watcher must keep receiving updates until dropped.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        config_dir: PathBuf,
        wg_key_store: WgKeyStore,
        config_key: Option<ConfigKey>,
        user_agent: String,
        os_impl: Arc<impl Os>,
        network_interface: Receiver<Option<NetworkInterface>>,
        log_persistence: Option<LogPersistence>,
        force_init_inactive: bool,
    ) -> Result<Arc<Self>, ConfigLoadError> {
        let client_state = ClientState::new(config_dir, wg_key_store, config_key, user_agent, force_init_inactive)?;
        let tunnel_state = TunnelState::new(client_state.clone(), os_impl.clone());
        let initial_status = Status::new(Uuid::new_v4(), VpnStatus::Disconnected {}, &client_state.borrow());
        let this = Arc::new(Self {
            tunnel_state,
            client_state,
            status_watch: channel(initial_status).0,
            log_persistence,
            service_debug_bundles: Mutex::new(HashMap::new()),
        });
        tokio::spawn(Self::wireguard_key_registraction_task(this.clone(), ()));
        tokio::spawn(Self::propagate_updates_to_status_task(this.clone(), ()));
        tokio::spawn(Self::preferred_network_interface_task(this.clone(), network_interface));
        Ok(this)
    }

    pub async fn maybe_update_exits(&self, freshness: Duration) -> Result<(), ApiError> {
        self.client_state.maybe_update_exits(freshness).await.map(|_| ())
    }

    pub async fn maybe_update_relays(&self, freshness: Duration) -> Result<(), ApiError> {
        self.client_state.maybe_update_relays(freshness).await.map(|_| ())
    }

    pub fn subscribe(&self) -> Receiver<Status> {
        self.status_watch.subscribe()
    }

    pub fn traffic_stats(&self) -> ManagerTrafficStats {
        self.tunnel_state.borrow().traffic_stats()
    }

    pub async fn login(&self, account_id: AccountId, validate: bool) -> Result<(), ConfigDirtyOrApiError> {
        let mut auth_token = None;
        if validate {
            const MAX_ATTEMPTS: usize = 10;
            for _ in 0..MAX_ATTEMPTS {
                let api_client = self.client_state.make_api_client(account_id.clone())?;
                let output = api_client.acquire_auth_token().await.map_err(ApiError::from)?;
                if let Some(url_override) = output.url_override {
                    // TODO: https://linear.app/soveng/issue/OBS-2268/override-web-url-for-apple-demo-accounts
                    self.set_api_url(Some(url_override.api));
                } else {
                    auth_token = Some(output.auth_token);
                    break;
                }
            }
            if auth_token.is_none() {
                return Err(ApiError::ApiClient(anyhow::format_err!("exceeded {MAX_ATTEMPTS} URL overrides").into()).into());
            }
        }
        self.client_state.set_account_id(Some((account_id, auth_token)))?;
        Ok(())
    }

    pub fn logout(&self) -> Result<(), ConfigDirty> {
        self.client_state.set_account_id(None)
    }

    pub fn set_api_url(&self, value: Option<String>) {
        self.client_state.set_api_url(value);
    }

    pub async fn api_request<C: Cmd>(&self, cmd: C) -> Result<C::Output, ApiError> {
        self.client_state.api_request(cmd).await
    }

    pub async fn api_request_without_auth<C: Cmd<Auth = AuthOptional>>(&self, cmd: C) -> Result<C::Output, ApiError> {
        self.client_state.api_request_without_auth(cmd).await
    }

    pub async fn apple_associate_account(&self, app_transaction_jws: String) -> Result<AppleAssociateAccountOutput, ApiError> {
        self.api_request(AppleAssociateAccount { app_transaction_jws }).await
    }

    pub async fn delete_account(&self) -> Result<DeleteAccountOutput, ApiError> {
        self.api_request(DeleteAccount {}).await
    }

    pub async fn get_account_info(&self) -> Result<AccountInfo, ApiError> {
        let account_info = self.api_request(GetAccountInfo()).await?;
        self.client_state.update_account_info(&account_info);
        Ok(account_info)
    }

    /// Ombra patch P16: the referrer gets a free month when this account first pays, and so does this account.
    pub async fn use_referral_code(&self, code: String) -> Result<(), ApiError> {
        self.api_request(UseReferralCode { code: ReferralCode(code) }).await
    }

    pub async fn get_service_status(&self) -> Result<ServiceStatusOutput, ApiError> {
        self.api_request_without_auth(ServiceStatus {}).await
    }

    pub async fn google_associate_account(&self, purchase_token: String, promo_code: Option<String>) -> Result<(), ApiError> {
        self.api_request(GoogleAssociateAccount { purchase_token, promo_code }).await
    }

    pub async fn google_billing_details(&self, promo_code: Option<String>) -> Result<GoogleBillingDetailsOutput, ApiError> {
        self.api_request(GoogleBillingDetails { promo_code }).await
    }

    async fn propagate_updates_to_status_task(this: Arc<Self>, _: ()) {
        let mut tunnel_state_recv = this.tunnel_state.clone();
        let mut client_state_recv = this.client_state.subscribe();
        tunnel_state_recv.mark_changed();
        loop {
            let cont = select! {
                res = tunnel_state_recv.changed() => res.is_ok(),
                res = client_state_recv.changed() => res.is_ok(),
            };
            if !cont {
                break;
            };
            this.status_watch.send_if_modified(|status| {
                let vpn_status = VpnStatus::from_tunnel_state(&tunnel_state_recv.borrow_and_update());
                let client_state = client_state_recv.borrow_and_update();
                let mut new_status = Status::new(status.version, vpn_status, &client_state);
                if new_status == *status {
                    return false;
                }
                new_status.version = Uuid::new_v4();
                *status = new_status;
                true
            });
        }
        tracing::info!(message_id = "NUeloeKe", "propagate_updates_to_status_task stops")
    }

    async fn wireguard_key_registraction_task(this: Arc<Self>, _: ()) {
        let mut status_subscription = this.subscribe();
        let mut last_status_version = None;
        loop {
            {
                let status_result = status_subscription
                    .wait_for(|status| {
                        let changed = Some(status.version) != last_status_version;
                        let active = status.account.as_ref().is_some_and(|account_status| account_status.account_info.active);
                        let disconnected = matches!(status.vpn_status, VpnStatus::Disconnected {});
                        changed && active && disconnected
                    })
                    .await;
                let Ok(status) = status_result else {
                    tracing::info!(message_id = "dvTbaTk7", "status subscription closed unexpectedly");
                    return;
                };
                last_status_version = Some(status.version);
            }
            let mut backoff = Backoff::BACKGROUND.take(10);
            while backoff.wait().await {
                let Err(error) = this.client_state.register_cached_wireguard_key_if_new().await else {
                    continue;
                };
                tracing::warn!(message_id = "DhL1YJG0", ?error, "failed attempt to register cached wireguard key");
            }
        }
    }

    pub async fn preferred_network_interface_task(this: Arc<Self>, mut network_interface_watch: Receiver<Option<NetworkInterface>>) {
        loop {
            let preferred_network_interface = network_interface_watch.borrow_and_update().clone();
            this.client_state.set_network_interface(preferred_network_interface);
            if network_interface_watch.changed().await.is_err() {
                tracing::error!(message_id = "ybeBsPfE", "status subscription closed unexpectedly");
                return;
            }
        }
    }

    pub async fn create_debug_bundle(
        &self,
        user_feedback: Option<String>,
        bundle_info: BundleInfo,
        android_cache_dir: Option<Utf8PathBuf>,
    ) -> anyhow::Result<String> {
        let log_dir = self.log_persistence.as_ref().map(LogPersistence::log_dir).map(ToOwned::to_owned);
        let debug_info = self.get_debug_info().await;
        tokio::task::spawn_blocking(move || create_debug_bundle(user_feedback, bundle_info, debug_info, log_dir, android_cache_dir).map(Into::into))
            .await?
    }

    pub async fn create_service_debug_bundle(&self, peer_uid: Option<PeerUid>) -> Result<ServiceDebugBundleHandle, ()> {
        let config = self.client_state.borrow().config().clone().into();
        let network_info = NetworkInfo::new(&self.client_state);
        let debug_info = self.get_debug_info().await;
        let log_dir = self.log_persistence.as_ref().map(LogPersistence::log_dir).map(ToOwned::to_owned);
        let bundle = service::create_service_debug_bundle(&config, &network_info, &debug_info, log_dir.as_deref(), peer_uid).await?;
        self.service_debug_bundles
            .lock()
            .unwrap()
            .insert(bundle.token.clone(), bundle.path.clone());
        Ok(bundle)
    }

    pub async fn delete_service_debug_bundle(&self, token: ServiceDebugBundleToken) -> Result<(), ()> {
        let Some(path) = self.service_debug_bundles.lock().unwrap().remove(&token) else {
            tracing::error!(message_id = "fP3jVt6N", "unknown service debug bundle token");
            return Err(());
        };
        tokio::fs::remove_dir_all(&path)
            .await
            .map_err(|error| tracing::error!(message_id = "mR7kZc2V", ?error, "failed to remove service debug bundle dir"))?;
        tracing::info!(message_id = "bQ7wJf4S", %path, "deleted service debug bundle dir");
        Ok(())
    }

    pub async fn get_debug_info(&self) -> DebugInfo {
        self.client_state.get_debug_info().await
    }

    pub fn wake(&self) {
        if let Some(conn) = self.tunnel_state.borrow().get_conn() {
            conn.wake();
        }
        crate::tunnel_state::retry_connect_now();
    }

    pub async fn get_exit_list(&self, known_version: Option<Vec<u8>>) -> Result<CachedValue<Arc<ExitList>>, ManagerCmdErrorCode> {
        self.wait_for_list(known_version, |config| config.cached_exits.as_ref()).await
    }

    pub async fn get_relay_list(&self, known_version: Option<Vec<u8>>) -> Result<CachedValue<Arc<Vec<OneRelay>>>, ManagerCmdErrorCode> {
        self.wait_for_list(known_version, |config| config.cached_relays.as_ref()).await
    }

    /// Waits until the cached list exists and its version differs from `known_version`.
    async fn wait_for_list<T>(
        &self,
        known_version: Option<Vec<u8>>,
        cached_list: impl Fn(&Config) -> Option<&ConfigCached<Arc<T>>>,
    ) -> Result<CachedValue<Arc<T>>, ManagerCmdErrorCode> {
        let mut watch = self.client_state.subscribe();
        let client_state = watch
            .wait_for(|client_state| cached_list(client_state.config()).is_some_and(|list| Some(list.version_bytes()) != known_version.as_deref()))
            .await
            .map_err(|error| {
                tracing::error!(?error, message_id = "ahcieM1h", "list subscription channel closed: {}", error,);
                ManagerCmdErrorCode::Other
            })?;
        let cached = cached_list(client_state.config()).unwrap();
        Ok(CachedValue {
            version: cached.version_bytes().to_vec(),
            last_updated: cached.last_updated,
            value: cached.value.clone(),
        })
    }

    pub fn run_on_client_state(&self, f: impl FnOnce(&ClientStateHandle)) -> Result<ManagerCmdOk, ManagerCmdErrorCode> {
        f(&self.client_state);
        Ok(ManagerCmdOk::Empty)
    }
}

#[derive(Debug, Copy, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagerTrafficStats {
    pub connected_ms: u64,
    pub conn_id: Uuid,
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub latest_latency_ms: u16,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{cached::Version, save};
    use crate::manager_cmd::ManagerCmd;
    use crate::os::os_trait::RecordingOs;
    use obscuravpn_api::types::{CityCode, CountryCode};
    use serde_json::json;
    use tempfile::tempdir;
    use tokio::time::timeout;

    /// `ONE_RELAY` of CoreModelTest.kt.
    fn one_relay_json() -> serde_json::Value {
        json!({
            "id": "relay-1",
            "country_code": "DE",
            "city_code": "fra",
            "city_name": "Frankfurt",
            "preferred_exits": [{"id": "FRA-001"}],
            "ip_v4": "192.0.2.10",
            "ports": [443, 8443],
            "tls_cert": "MIIBszCCAVmgAwIBAgIUQ0VSVA==",
        })
    }

    /// A manager on a config dir that has `config`. Nothing in it uses the network while it is not logged in.
    fn manager(config: &Config, os: Arc<RecordingOs>) -> (Arc<Manager>, tempfile::TempDir) {
        let dir = tempdir().unwrap();
        save(dir.path(), config).unwrap();
        // A closed network interface channel only stops the task that follows it.
        let network_interface = channel(None).1;
        let manager = Manager::new(
            dir.path().into(),
            WgKeyStore::None,
            None,
            "test".into(),
            os,
            network_interface,
            None,
            true,
        )
        .unwrap();
        (manager, dir)
    }

    #[tokio::test]
    async fn relay_list_long_poll() {
        let relay: OneRelay = serde_json::from_value(one_relay_json()).unwrap();
        let config = Config {
            cached_relays: Some(ConfigCached::new(Arc::new(vec![relay]), Version::ETag(vec![0, 1, 2]))),
            ..Default::default()
        };
        let (manager, _dir) = manager(&config, Default::default());

        let ok = ManagerCmd::GetRelayList { known_version: None }.run(&manager, None).await.unwrap();
        let json = serde_json::to_value(ok).unwrap();
        assert_eq!(json["version"], "AAEC");
        assert!(json["last_updated"].is_u64());
        assert_eq!(json["value"], json!([one_relay_json()]));

        // The client already has this version, so the call waits for a new one.
        assert!(
            timeout(Duration::from_millis(200), manager.get_relay_list(Some(vec![0, 1, 2])))
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn refresh_os_network_config_reaches_the_os() {
        let os = Arc::new(RecordingOs::default());
        let (manager, _dir) = manager(&Config::default(), os.clone());

        let ok = ManagerCmd::RefreshOsNetworkConfig {}.run(&manager, None).await.unwrap();
        assert_eq!(serde_json::to_string(&ok).unwrap(), "null");
        timeout(Duration::from_secs(5), async {
            while !os.calls().contains(&"forget") {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("the tunnel loop did not forget the OS network config");
    }

    #[test]
    fn transport_kind_json() {
        assert_eq!(serde_json::to_value(TransportKind::WireguardUdp).unwrap(), json!("wireguardUdp"));
        assert_eq!(serde_json::to_value(TransportKind::TcpTls).unwrap(), json!("tcpTls"));
    }

    #[test]
    fn upstream_tunnel_args_decode_with_defaults() {
        let args: TunnelArgs = serde_json::from_value(json!({"exit": {"any": {}}})).unwrap();
        assert_eq!(
            args,
            TunnelArgs { exit: ExitSelector::Any {}, relay: RelaySelector::Any {}, mode: TunnelMode::Quic, udp_mtu: None }
        );

        // A config.json written before patch P1.
        let config: Config = serde_json::from_value(json!({
            "tunnel_active": true,
            "tunnel_args": {"exit": {"country": {"country_code": "US"}}},
        }))
        .unwrap();
        assert_eq!(
            config.tunnel_args,
            TunnelArgs { exit: ExitSelector::Country { country_code: CountryCode("US".into()) }, ..Default::default() }
        );
        assert!(config.tunnel_active);
    }

    #[test]
    fn tunnel_args_json_matches_kotlin() {
        // The shapes of app/src/test/java/app/ombra/core/model/CoreModelTest.kt.
        let quic = json!({
            "exit": {"city": {"country_code": "US", "city_code": "nyc"}},
            "relay": {"country": {"country_code": "DE"}},
            "mode": "quic",
            "udpMtu": null,
        });
        let wireguard_udp = json!({
            "exit": {"exit": {"id": "NYC-001"}},
            "relay": {"relay": {"id": "relay-1"}},
            "mode": "wireguardUdp",
            "udpMtu": 1400,
        });
        for json in [quic, wireguard_udp.clone()] {
            let args: TunnelArgs = serde_json::from_value(json.clone()).unwrap();
            assert_eq!(serde_json::to_value(&args).unwrap(), json);
        }
        let args: TunnelArgs = serde_json::from_value(wireguard_udp).unwrap();
        assert_eq!(args.relay, RelaySelector::Relay { id: "relay-1".into() });
        assert_eq!(args.mode, TunnelMode::WireguardUdp);
        assert_eq!(args.udp_mtu, Some(1400));

        assert_eq!(serde_json::to_value(TunnelMode::TcpTls).unwrap(), json!("tcpTls"));
        assert_eq!(
            serde_json::to_value(TunnelArgs::default()).unwrap(),
            json!({"exit": {"any": {}}, "relay": {"any": {}}, "mode": "quic", "udpMtu": null})
        );
    }

    #[test]
    fn set_tunnel_args_from_kotlin_and_upstream() {
        let cmd: ManagerCmd = serde_json::from_value(json!({"setTunnelArgs": {
            "args": {
                "exit": {"any": {}},
                "relay": {"city": {"country_code": "de", "city_code": "fra"}},
                "mode": "tcpTls",
                "udpMtu": null,
            },
            "active": true,
        }}))
        .unwrap();
        let ManagerCmd::SetTunnelArgs { args: Some(args), active: Some(true) } = cmd else {
            panic!("unexpected command {cmd:?}");
        };
        let fra = CityCode { country_code: CountryCode("de".into()), city_code: "fra".into() };
        assert_eq!(
            args,
            TunnelArgs { relay: RelaySelector::City { city_code: fra }, mode: TunnelMode::TcpTls, ..Default::default() }
        );

        let cmd: ManagerCmd = serde_json::from_value(json!({"setTunnelArgs": {"args": {"exit": {"any": {}}}, "active": true}})).unwrap();
        assert!(matches!(cmd, ManagerCmd::SetTunnelArgs { args: Some(args), active: Some(true) } if args == TunnelArgs::default()));
    }

    #[test]
    fn connecting_status_carries_the_full_tunnel_args() {
        let status = VpnStatus::Connecting {
            tunnel_args: TunnelArgs::default(),
            connect_error: Some(ConnectErrorCode::NoRelay),
            reconnecting: false,
        };
        assert_eq!(
            serde_json::to_value(status).unwrap(),
            json!({"connecting": {
                "tunnelArgs": {"exit": {"any": {}}, "relay": {"any": {}}, "mode": "quic", "udpMtu": null},
                "connectError": "noRelay",
                "reconnecting": false,
            }})
        );
    }
}
