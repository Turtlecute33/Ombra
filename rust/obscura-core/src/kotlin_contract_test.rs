//! The JSON contract with the Kotlin app (Ombra). The commands and results below are serialized here and kept in
//! `app/src/test/resources/core-contract.json`, which the Kotlin test `CoreContractTest` reads: Kotlin must encode
//! each command exactly as Rust does and must decode each result. After a change of these shapes, write the file
//! again with `OMBRA_WRITE_CONTRACT=1 cargo test --locked --offline kotlin_contract` and run the Kotlin tests.

use std::{
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime},
};

use obscuravpn_api::{
    cmd::{ExitList, FreeTrialStatus, MoneroPaymentStatus, MoneroTopUpId, NnnStatus, ReferralCode, ServiceStatusOutput},
    types::{
        AccountId, AccountInfo, CityCode, CountryCode, FreeTrialInfo, FundingMethod, MoneroPaymentInProgress, OneExit, OneRelay, RelayPreferredExit,
        TopUp, WgClientConfig, WgPubkey, WgServerConfig,
    },
};
use serde_json::{Map, Value, json};
use uuid::Uuid;

use crate::{
    cached_value::CachedValue,
    client_state::AccountStatus,
    config::{PinnedLocation, feature_flags::FeatureFlags},
    errors::ConnectErrorCode,
    exit_selection::ExitSelector,
    local_network::tunnel_routes,
    manager::{ManagerTrafficStats, Status, TunnelArgs, TunnelMode, VpnStatus},
    manager_cmd::{ManagerCmd, ManagerCmdErrorCode, ManagerCmdOk},
    network_config::{DnsContentBlock, OsNetworkConfig, TunnelNetworkConfig},
    quicwg::TransportKind,
    relay_selection::RelaySelector,
};

#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::config::LocalNetworkAccess;

const ACCOUNT_NUMBER: &str = "55550123456780554337";
const STATUS_VERSION: &str = "2b9a3c1e-0d4f-4a9b-8c6e-1f2a3b4c5d6e";

fn fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../app/src/test/resources/core-contract.json")
}

fn unix(seconds: u64) -> SystemTime {
    SystemTime::UNIX_EPOCH + Duration::from_secs(seconds)
}

fn city(country_code: &str, city_code: &str) -> CityCode {
    CityCode { country_code: CountryCode(country_code.into()), city_code: city_code.into() }
}

fn one_exit() -> OneExit {
    OneExit {
        id: "NYC-001".into(),
        city_code: city("US", "nyc"),
        city_name: "New York".into(),
        provider_id: "us-nyc-wg-301".into(),
        provider_url: "https://mullvad.net/servers".into(),
        provider_name: "Mullvad VPN".into(),
        provider_homepage_url: "https://mullvad.net".into(),
        datacenter_id: 42,
        tier: 0,
    }
}

fn one_relay() -> OneRelay {
    OneRelay {
        id: "relay-1".into(),
        city_code: city("DE", "fra"),
        city_name: "Frankfurt".into(),
        preferred_exits: vec![RelayPreferredExit { id: "FRA-001".into() }],
        ip_v4: "192.0.2.10".parse().unwrap(),
        ports: vec![443, 8443],
        tls_cert: vec![0x30, 0x82, 0x01, 0xb3],
    }
}

fn dns_content_block() -> DnsContentBlock {
    // The fields are private to `network_config`.
    serde_json::from_value(json!({
        "ad": true, "tracker": false, "malware": true, "adult": false, "gambling": false, "socialMedia": true,
    }))
    .unwrap()
}

fn speed_tunnel_args() -> TunnelArgs {
    TunnelArgs {
        exit: ExitSelector::Country { country_code: CountryCode("US".into()) },
        relay: RelaySelector::City { city_code: city("DE", "fra") },
        mode: TunnelMode::WireguardUdp,
        udp_mtu: Some(1400),
    }
}

fn account_info() -> AccountInfo {
    AccountInfo {
        active: true,
        apple_subscription: None,
        auto_renews: None,
        current_expiry: Some(1_800_000_000),
        free_month_on_next_payment: false,
        free_trial: Some(FreeTrialInfo { active: false, expires_at: 1_600_000_000 }),
        google_subscription: None,
        has_paid: true,
        has_referrer: false,
        id: AccountId::from_string_unchecked(ACCOUNT_NUMBER.into()),
        monero_pending_payments: vec![MoneroPaymentInProgress { id: MoneroTopUpId("m-1".into()), status: MoneroPaymentStatus::Confirming }],
        nnn: None,
        primary_funding: Some(FundingMethod::TopUp),
        received_free_months: 0,
        referral_code: ReferralCode("REF123".into()),
        stripe_subscription: None,
        top_up: Some(TopUp { active: true, credit_expires_at: 1_800_000_000 }),
    }
}

fn status(vpn_status: VpnStatus, logged_in: bool) -> Status {
    let mut feature_flags = FeatureFlags::default();
    feature_flags.set("quicFramePadding", true);
    feature_flags.set("forceSmallMtu", true);
    Status {
        version: Uuid::parse_str(STATUS_VERSION).unwrap(),
        vpn_status,
        account_id: logged_in.then(|| AccountId::from_string_unchecked(ACCOUNT_NUMBER.into())),
        in_new_account_flow: false,
        pinned_locations: vec![PinnedLocation { country_code: "US".into(), city_code: "nyc".into(), pinned_at: unix(1_700_000_000) }],
        last_chosen_exit: ExitSelector::City { city_code: city("US", "nyc") },
        last_exit: ExitSelector::Any {},
        api_url: "https://v1.api.prod.obscura.net/api".into(),
        account: logged_in.then(|| AccountStatus { account_info: account_info(), last_updated_sec: 1_700_000_100 }),
        auto_connect: true,
        feature_flags,
        feature_flag_keys: FeatureFlags::KEYS.iter().map(ToString::to_string).collect(),
        use_system_dns: false,
        local_network_access: false,
        tailscale_bypass: false,
        wireguard_bypass: false,
        dns_content_block: dns_content_block(),
    }
}

/// The Android `OsNetworkConfig` of a Speed mode tunnel to a Mullvad exit with DNS content blocking.
fn os_network_config() -> Value {
    let client = WgClientConfig {
        wg_pubkey: WgPubkey([1; 32]),
        addresses: vec!["10.75.0.2/32".parse().unwrap(), "fc00:bbbb:bbbb:bb01::1:2/128".parse().unwrap()],
    };
    let server = WgServerConfig {
        wg_pubkey: WgPubkey([2; 32]),
        endpoints: vec!["192.0.2.10:51820".parse().unwrap()],
        dnses: vec!["10.64.0.1".parse().unwrap()],
    };
    let tunnel = TunnelNetworkConfig::from_udp(&client, &server, 1400).unwrap();
    let config = OsNetworkConfig::new(
        &tunnel,
        "Mullvad VPN",
        dns_content_block(),
        false,
        #[cfg(any(target_os = "android", target_os = "linux"))]
        LocalNetworkAccess::Disabled,
        #[cfg(target_os = "linux")]
        Default::default(),
        #[cfg(target_os = "linux")]
        Default::default(),
    );
    let mut value = serde_json::to_value(&config).unwrap();
    let object = value.as_object_mut().unwrap();
    // Host builds have no `routes`, Linux has fields that Android has not: keep the Android shape.
    object.retain(|key, _| ["dns", "ipv4", "ipv6", "mtu", "useSystemDns"].contains(&key.as_str()));
    object.insert("routes".into(), serde_json::to_value(tunnel_routes(&config.dns, false)).unwrap());
    value
}

fn all_connect_error_codes() -> Vec<ConnectErrorCode> {
    use ConnectErrorCode::*;
    let all = vec![
        AccountExpired,
        ApiError,
        ApiRateLimitExceeded,
        ApiUnreachable,
        InvalidAccountId,
        NoInternet,
        NoLongerSupported,
        NoRelay,
        NoSlotsLeft,
        NotLoggedIn,
        Other,
        UdpHandshakeFailed,
    ];
    // A new variant fails to compile here: add it to the list above too.
    for code in &all {
        match code {
            AccountExpired | ApiError | ApiRateLimitExceeded | ApiUnreachable | InvalidAccountId | NoInternet | NoLongerSupported | NoRelay
            | NoSlotsLeft | NotLoggedIn | Other | UdpHandshakeFailed => {}
        }
    }
    all
}

fn all_manager_cmd_error_codes() -> Vec<ManagerCmdErrorCode> {
    use ManagerCmdErrorCode::*;
    let all = vec![
        ApiAssociateAccountConflict,
        ApiError,
        ApiInvalidAccountId,
        ApiNoLongerSupported,
        ApiRateLimitExceeded,
        ApiSaleNotFound,
        ApiSignupLimitExceeded,
        ApiUnreachable,
        ConfigSaveError,
        NotLoggedIn,
        Other,
    ];
    // A new variant fails to compile here: add it to the list above too.
    for code in &all {
        match code {
            ApiAssociateAccountConflict
            | ApiError
            | ApiInvalidAccountId
            | ApiNoLongerSupported
            | ApiRateLimitExceeded
            | ApiSaleNotFound
            | ApiSignupLimitExceeded
            | ApiUnreachable
            | ConfigSaveError
            | NotLoggedIn
            | Other => {}
        }
    }
    all
}

fn all_transport_kinds() -> Vec<TransportKind> {
    use TransportKind::*;
    let all = vec![Quic, TcpTls, WireguardUdp];
    for kind in &all {
        match kind {
            Quic | TcpTls | WireguardUdp => {}
        }
    }
    all
}

fn all_tunnel_modes() -> Vec<TunnelMode> {
    use TunnelMode::*;
    let all = vec![Quic, TcpTls, WireguardUdp];
    for mode in &all {
        match mode {
            Quic | TcpTls | WireguardUdp => {}
        }
    }
    all
}

/// The commands that Kotlin sends, as Rust serializes them. Kotlin builds the same ones by name.
fn commands() -> Vec<(&'static str, ManagerCmd)> {
    let set_feature_flag = |flag: &str, active| ManagerCmd::SetFeatureFlag { flag: flag.into(), active };
    vec![
        ("apiDeleteAccount", ManagerCmd::ApiDeleteAccount {}),
        ("apiGetAccountInfo", ManagerCmd::ApiGetAccountInfo {}),
        ("apiServiceStatus", ManagerCmd::ApiServiceStatus {}),
        ("getExitList", ManagerCmd::GetExitList { known_version: Some(vec![0, 1, 2, 3]) }),
        ("getExitListFirst", ManagerCmd::GetExitList { known_version: None }),
        ("getRelayList", ManagerCmd::GetRelayList { known_version: Some(vec![0, 1, 2]) }),
        ("getRelayListFirst", ManagerCmd::GetRelayList { known_version: None }),
        (
            "getStatus",
            ManagerCmd::GetStatus { known_version: Some(Uuid::parse_str(STATUS_VERSION).unwrap()) },
        ),
        ("getStatusFirst", ManagerCmd::GetStatus { known_version: None }),
        ("getTrafficStats", ManagerCmd::GetTrafficStats {}),
        (
            "login",
            ManagerCmd::Login { account_id: AccountId::from_string_unchecked(ACCOUNT_NUMBER.into()), validate: true },
        ),
        ("logout", ManagerCmd::Logout {}),
        ("ping", ManagerCmd::Ping {}),
        ("refreshExitList", ManagerCmd::RefreshExitList { freshness: Duration::from_secs(60) }),
        ("refreshOsNetworkConfig", ManagerCmd::RefreshOsNetworkConfig {}),
        ("refreshRelayList", ManagerCmd::RefreshRelayList { freshness: Duration::from_secs(60) }),
        ("rotateWgKey", ManagerCmd::RotateWgKey {}),
        (
            "setApiHostAlternate",
            ManagerCmd::SetApiHostAlternate { host: Some("api.example.org".into()) },
        ),
        ("setApiHostAlternateDefault", ManagerCmd::SetApiHostAlternate { host: None }),
        ("setApiUrl", ManagerCmd::SetApiUrl { url: None }),
        ("setAutoConnect", ManagerCmd::SetAutoConnect { enable: true }),
        ("setDnsContentBlock", ManagerCmd::SetDnsContentBlock { value: dns_content_block() }),
        ("setFeatureFlag.forceSmallMtu", set_feature_flag("forceSmallMtu", false)),
        ("setFeatureFlag.quicFramePadding", set_feature_flag("quicFramePadding", true)),
        ("setFeatureFlag.tcpTlsTunnel", set_feature_flag("tcpTlsTunnel", true)),
        ("setInNewAccountFlow", ManagerCmd::SetInNewAccountFlow { value: true }),
        ("setLocalNetworkAccess", ManagerCmd::SetLocalNetworkAccess { enable: false }),
        (
            "setPinnedExits",
            ManagerCmd::SetPinnedExits {
                exits: vec![PinnedLocation { country_code: "US".into(), city_code: "nyc".into(), pinned_at: unix(1_700_000_000) }],
            },
        ),
        ("setSniRelay", ManagerCmd::SetSniRelay { host: Some("sni.example.org".into()) }),
        ("setSniRelayDefault", ManagerCmd::SetSniRelay { host: None }),
        // OmbraVpnService: connect with the settings, connect with the stored args, disconnect.
        (
            "setTunnelArgs.connect",
            ManagerCmd::SetTunnelArgs { args: Some(speed_tunnel_args()), active: Some(true) },
        ),
        (
            "setTunnelArgs.connectStored",
            ManagerCmd::SetTunnelArgs { args: None, active: Some(true) },
        ),
        ("setTunnelArgs.disconnect", ManagerCmd::SetTunnelArgs { args: None, active: Some(false) }),
        // SettingsSync: new args for a running or a stopped tunnel.
        (
            "setTunnelArgs.settings",
            ManagerCmd::SetTunnelArgs {
                args: Some(TunnelArgs {
                    exit: ExitSelector::Exit { id: "NYC-001".into() },
                    relay: RelaySelector::Relay { id: "relay-1".into() },
                    mode: TunnelMode::TcpTls,
                    udp_mtu: None,
                }),
                active: None,
            },
        ),
        ("setUseSystemDns", ManagerCmd::SetUseSystemDns { enable: true }),
    ]
}

/// The Ok payloads that Kotlin decodes, serialized as `jsonFfi` does.
fn results() -> Vec<(&'static str, Value)> {
    let ok = |ok: ManagerCmdOk| serde_json::to_value(ok).unwrap();
    let connected = VpnStatus::Connected {
        tunnel_args: speed_tunnel_args(),
        exit: one_exit(),
        relay: one_relay(),
        client_public_key: WgPubkey([1; 32]),
        exit_public_key: WgPubkey([2; 32]),
        transport: TransportKind::WireguardUdp,
    };
    let connecting = VpnStatus::Connecting {
        tunnel_args: speed_tunnel_args(),
        connect_error: Some(ConnectErrorCode::UdpHandshakeFailed),
        reconnecting: true,
    };
    let traffic_stats = ManagerTrafficStats {
        connected_ms: 61_000,
        conn_id: Uuid::parse_str("0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0").unwrap(),
        tx_bytes: 1234,
        rx_bytes: 567_890,
        latest_latency_ms: 42,
    };
    let service_status = ServiceStatusOutput {
        free_trial: FreeTrialStatus { available: true },
        nnn: NnnStatus { claimed_months_target: 100_000, claimed_months_total: 1234, submissions_open: true },
    };
    vec![
        ("apiGetAccountInfo", ok(account_info().into())),
        ("apiServiceStatus", ok(service_status.into())),
        ("empty", ok(().into())),
        (
            "getExitList",
            ok(ManagerCmdOk::GetExitList(CachedValue {
                version: vec![0, 1, 2, 3],
                last_updated: unix(1_700_000_000),
                value: Arc::new(ExitList { exits: vec![one_exit()] }),
            })),
        ),
        (
            "getRelayList",
            ok(ManagerCmdOk::GetRelayList(CachedValue {
                version: vec![0, 1, 2],
                last_updated: unix(1_700_000_001),
                value: Arc::new(vec![one_relay()]),
            })),
        ),
        ("getStatus.connected", ok(ManagerCmdOk::GetStatus(status(connected, true)))),
        ("getStatus.connecting", ok(ManagerCmdOk::GetStatus(status(connecting, false)))),
        (
            "getStatus.disconnected",
            ok(ManagerCmdOk::GetStatus(status(VpnStatus::Disconnected {}, true))),
        ),
        ("getTrafficStats", ok(ManagerCmdOk::GetTrafficStats(traffic_stats))),
        ("osNetworkConfig", os_network_config()),
    ]
}

fn enums() -> Value {
    json!({
        "connectErrorCode": all_connect_error_codes(),
        "featureFlag": FeatureFlags::KEYS,
        "managerCmdErrorCode": all_manager_cmd_error_codes(),
        "transportKind": all_transport_kinds(),
        "tunnelMode": all_tunnel_modes(),
    })
}

fn fixture() -> Value {
    let commands: Map<String, Value> = commands()
        .into_iter()
        .map(|(name, cmd)| (name.into(), serde_json::to_value(cmd).unwrap()))
        .collect();
    let results: Map<String, Value> = results().into_iter().map(|(name, value)| (name.into(), value)).collect();
    json!({
        "about": "Written by rust/obscura-core/src/kotlin_contract_test.rs, read by CoreContractTest.kt. Do not edit.",
        "commands": commands,
        "enums": enums(),
        "results": results,
    })
}

#[test]
fn kotlin_contract_fixture_is_current() {
    let fixture = fixture();
    let path = fixture_path();
    if std::env::var_os("OMBRA_WRITE_CONTRACT").is_some() {
        std::fs::write(&path, serde_json::to_string_pretty(&fixture).unwrap() + "\n").unwrap();
    }
    let stored: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
    for section in ["commands", "enums", "results"] {
        for (name, value) in fixture[section].as_object().unwrap() {
            assert_eq!(
                &stored[section][name],
                value,
                "{section}.{name} changed: write {} again (see the top of this file)",
                path.display()
            );
        }
        assert_eq!(
            stored[section].as_object().unwrap().len(),
            fixture[section].as_object().unwrap().len(),
            "{section}"
        );
    }
}

#[test]
fn kotlin_contract_commands_decode() {
    for (name, cmd) in commands() {
        let json = serde_json::to_value(&cmd).unwrap();
        let decoded: ManagerCmd = serde_json::from_value(json.clone()).unwrap();
        assert_eq!(serde_json::to_value(decoded).unwrap(), json, "{name}");
        if let ManagerCmd::SetFeatureFlag { flag, .. } = cmd {
            assert!(FeatureFlags::KEYS.contains(&flag.as_str()), "{flag} is not a feature flag");
        }
    }
}

#[test]
fn kotlin_debug_bundle_command_decodes() {
    // Kotlin sends only these two `BundleInfo` fields (CoreContractTest.kt has the same string).
    let json = r#"{"createDebugBundle":{"userFeedback":null,"bundleInfo":{"AppVersion":"1.0","AndroidSDK":36},"androidCacheDir":"/data/user/0/app.ombra/cache"}}"#;
    let ManagerCmd::CreateDebugBundle { user_feedback: None, bundle_info, android_cache_dir: Some(dir) } = serde_json::from_str(json).unwrap() else {
        panic!("unexpected command");
    };
    assert_eq!(bundle_info.app_version, "1.0");
    assert_eq!(bundle_info.android_sdk, Some(36));
    assert_eq!(dir, "/data/user/0/app.ombra/cache");
}
