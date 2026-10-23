#[cfg(any(target_os = "android", target_os = "linux"))]
use crate::config::LocalNetworkAccess;
#[cfg(target_os = "linux")]
use crate::config::{TailscaleBypass, WireGuardBypass};
#[cfg(target_os = "android")]
use crate::local_network::{Route, tunnel_routes};
use ipnetwork::{IpNetwork, Ipv6Network};
use obscuravpn_api::types::{ObfuscatedTunnelConfig, WgClientConfig, WgServerConfig};
use serde::{Deserialize, Serialize};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use strum::EnumIs;
use thiserror::Error;

const MULLVAD_EXIT_PROVIDER_NAME: &str = "Mullvad VPN";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TunnelNetworkConfig {
    pub dns: Vec<IpAddr>,
    pub ipv4: Ipv4Addr,
    pub ipv6: Ipv6Network,
    pub mtu: u16,
}

impl TunnelNetworkConfig {
    pub fn new(tunnel_config: &ObfuscatedTunnelConfig, mtu: u16) -> Result<Self, NetworkConfigError> {
        let dns = tunnel_config.dns.clone();
        if dns.is_empty() {
            return Err(NetworkConfigError::NoDns);
        }

        let Some(ipv4) = tunnel_config.client_ips_v4.first().map(|net| net.ip()) else {
            return Err(NetworkConfigError::NoIpv4Ip);
        };

        let Some(ipv6) = tunnel_config.client_ips_v6.first().cloned() else {
            return Err(NetworkConfigError::NoIpv6Ip);
        };

        Ok(Self { dns, ipv4, ipv6, mtu })
    }

    /// Speed mode (Ombra patch P5): the first IPv4 and IPv6 address of the client and the DNS servers of a
    /// `TunnelConfig::UdpPort`.
    pub fn from_udp(client: &WgClientConfig, server: &WgServerConfig, mtu: u16) -> Result<Self, NetworkConfigError> {
        let dns = server.dnses.clone();
        if dns.is_empty() {
            return Err(NetworkConfigError::NoDns);
        }
        let ipv4 = client
            .addresses
            .iter()
            .find_map(|address| match address {
                IpNetwork::V4(address) => Some(address.ip()),
                IpNetwork::V6(_) => None,
            })
            .ok_or(NetworkConfigError::NoIpv4Ip)?;
        let ipv6 = client
            .addresses
            .iter()
            .find_map(|address| match address {
                IpNetwork::V4(_) => None,
                IpNetwork::V6(address) => Some(*address),
            })
            .ok_or(NetworkConfigError::NoIpv6Ip)?;
        Ok(Self { dns, ipv4, ipv6, mtu })
    }

    fn dummy() -> Self {
        Self {
            dns: vec![IpAddr::V4(Ipv4Addr::new(10, 64, 0, 99))],
            ipv4: Ipv4Addr::new(10, 75, 76, 77),
            ipv6: Ipv6Network::new(Ipv6Addr::new(0xfc00, 0xbbbb, 0xbbbb, 0xbb01, 0, 0, 0xc, 0x4c4d), 128).unwrap(),
            mtu: 1280,
        }
    }
}

#[derive(Clone, Debug, Error)]
pub enum NetworkConfigError {
    #[error("no ipv4 ip")]
    NoIpv4Ip,
    #[error("no ipv6 ip")]
    NoIpv6Ip,
    #[error("no dns")]
    NoDns,
    #[error("no ipv4 endpoint")]
    NoIpv4Endpoint,
}

#[derive(Clone, Copy, Debug, Default, EnumIs, PartialEq, Eq, Serialize, Deserialize)]
pub enum DnsConfig {
    #[default]
    Default,
    System,
}

#[derive(Default, Copy, Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DnsContentBlock {
    ad: bool,
    tracker: bool,
    malware: bool,
    adult: bool,
    gambling: bool,
    social_media: bool,
}

impl DnsContentBlock {
    fn mullvad_dns_ip(self) -> Option<Ipv4Addr> {
        let bitset = u8::from(self.ad)
            | (u8::from(self.tracker) << 1)
            | (u8::from(self.malware) << 2)
            | (u8::from(self.adult) << 3)
            | (u8::from(self.gambling) << 4)
            | (u8::from(self.social_media) << 5);
        (bitset != 0).then_some(Ipv4Addr::new(100, 64, 0, bitset))
    }
}

// Keep synchronized with:
// - Ombra: app/src/main/java/app/ombra/core/model/ (Kotlin model of OsNetworkConfig)
// - apple/shared/NetworkExtensionIpc.swift
//
// Avoid adding information with high-frequency of change to this type, to prevent triggering frequent changes OS network configuration, which can't be deduplicated by checking for changes.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OsNetworkConfig {
    pub dns: Vec<IpAddr>,
    pub ipv4: Ipv4Addr,
    pub ipv6: Ipv6Network,
    #[cfg(target_os = "android")]
    pub routes: Vec<Route>,
    pub mtu: u16,
    pub use_system_dns: bool,
    #[cfg(target_os = "linux")]
    pub local_network_access: bool,
    #[cfg(target_os = "linux")]
    pub tailscale_bypass: bool,
    #[cfg(target_os = "linux")]
    pub wireguard_bypass: bool,
}

impl OsNetworkConfig {
    pub fn new(
        tunnel_network_config: &TunnelNetworkConfig,
        exit_provider_name: &str,
        dns_content_block: DnsContentBlock,
        use_system_dns: bool,
        #[cfg(any(target_os = "android", target_os = "linux"))] local_network_access: LocalNetworkAccess,
        #[cfg(target_os = "linux")] tailscale_bypass: TailscaleBypass,
        #[cfg(target_os = "linux")] wireguard_bypass: WireGuardBypass,
    ) -> Self {
        let dns = if exit_provider_name == MULLVAD_EXIT_PROVIDER_NAME
            && let Some(dns) = dns_content_block.mullvad_dns_ip()
        {
            vec![IpAddr::from(dns)]
        } else {
            tunnel_network_config.dns.clone()
        };

        Self {
            #[cfg(target_os = "android")]
            routes: tunnel_routes(&dns, local_network_access.is_enabled()),
            dns,
            ipv4: tunnel_network_config.ipv4,
            ipv6: tunnel_network_config.ipv6,
            mtu: tunnel_network_config.mtu,
            use_system_dns,
            #[cfg(target_os = "linux")]
            local_network_access: local_network_access.is_enabled(),
            #[cfg(target_os = "linux")]
            tailscale_bypass: tailscale_bypass.is_enabled(),
            #[cfg(target_os = "linux")]
            wireguard_bypass: wireguard_bypass.is_enabled(),
        }
    }

    /// Dummy OS network config. May be used if valid values are needed by an API before the real values are known. The values are picked from ranges we expect for our tunnels.
    pub fn dummy(
        dns_content_block: DnsContentBlock,
        use_system_dns: bool,
        #[cfg(any(target_os = "android", target_os = "linux"))] local_network_access: LocalNetworkAccess,
        #[cfg(target_os = "linux")] tailscale_bypass: TailscaleBypass,
        #[cfg(target_os = "linux")] wireguard_bypass: WireGuardBypass,
    ) -> Self {
        Self::new(
            &TunnelNetworkConfig::dummy(),
            MULLVAD_EXIT_PROVIDER_NAME,
            dns_content_block,
            use_system_dns,
            #[cfg(any(target_os = "android", target_os = "linux"))]
            local_network_access,
            #[cfg(target_os = "linux")]
            tailscale_bypass,
            #[cfg(target_os = "linux")]
            wireguard_bypass,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use obscuravpn_api::types::WgPubkey;

    fn udp_port_config(addresses: &[&str], dnses: &[&str]) -> (WgClientConfig, WgServerConfig) {
        let client = WgClientConfig { wg_pubkey: WgPubkey([1; 32]), addresses: addresses.iter().map(|a| a.parse().unwrap()).collect() };
        let server = WgServerConfig {
            wg_pubkey: WgPubkey([2; 32]),
            endpoints: vec!["192.0.2.10:51820".parse().unwrap()],
            dnses: dnses.iter().map(|d| d.parse().unwrap()).collect(),
        };
        (client, server)
    }

    #[test]
    fn udp_port_config_gives_the_first_address_of_each_family() {
        let (client, server) = udp_port_config(
            &[
                "fc00:bbbb:bbbb:bb01::1:2/128",
                "10.75.0.2/32",
                "10.75.0.3/32",
                "fc00:bbbb:bbbb:bb01::1:3/128",
            ],
            &["10.64.0.1"],
        );
        let config = TunnelNetworkConfig::from_udp(&client, &server, 1400).unwrap();
        assert_eq!(
            config,
            TunnelNetworkConfig {
                dns: vec!["10.64.0.1".parse().unwrap()],
                ipv4: "10.75.0.2".parse().unwrap(),
                ipv6: "fc00:bbbb:bbbb:bb01::1:2/128".parse().unwrap(),
                mtu: 1400,
            }
        );
    }

    #[test]
    fn udp_port_config_needs_both_families_and_dns() {
        let missing = |addresses: &[&str], dnses: &[&str]| {
            let (client, server) = udp_port_config(addresses, dnses);
            TunnelNetworkConfig::from_udp(&client, &server, 1280).unwrap_err().to_string()
        };
        assert_eq!(missing(&["fc00::2/128"], &["10.64.0.1"]), "no ipv4 ip");
        assert_eq!(missing(&["10.75.0.2/32"], &["10.64.0.1"]), "no ipv6 ip");
        assert_eq!(missing(&["10.75.0.2/32", "fc00::2/128"], &[]), "no dns");
    }
}
