use crate::config::feature_flags::FeatureFlags;
use crate::constants::DEFAULT_RELAY_SNI;
use crate::errors::RelaySelectionError;
use crate::manager::TunnelMode;
use crate::net::{NetworkInterface, RelayEndpoint, new_quic, new_udp};
use crate::quic_tuning::QuicTuning;
use crate::quicwg::{QuicWgConnHandshaking, QuicWgConnectError};
use obscuravpn_api::types::{CityCode, CountryCode, OneRelay};
use serde::{Deserialize, Serialize};
use std::cmp::min;
use std::collections::BTreeSet;
use std::sync::Arc;
use std::time::Duration;
use tokio::spawn;
use tokio::task::JoinSet;
use tokio::time::{Instant, timeout, timeout_at};

const ABANDON_GRACE_PERIOD: Duration = Duration::from_secs(1);

/// How long the race with the MTU floor waits for a first relay before it races the same relays without the floor.
const MTU_FLOOR_ANSWER_TIMEOUT: Duration = Duration::from_secs(2);

/// Many networks handle port 53 as DNS: they proxy it, limit its rate or queue it. The handshake RTT on port 53 is then
/// the same as on the other ports, but bulk traffic collapses. On one Wi-Fi network, port 53 of a relay gave 3 to 5 Mbit/s
/// upload with 0.6 to 3.9 s RTT, ports 443 and 50000 of the same relay 67 to 70 Mbit/s (Ombra patch P6). Port 53 wins
/// the race only when it is this much faster than the other ports, or when no other port answers.
const DNS_PORT_PENALTY: Duration = Duration::from_millis(25);

fn dns_port_penalty(port: u16) -> Duration {
    if port == 53 { DNS_PORT_PENALTY } else { Duration::ZERO }
}

/// The RTT that the race compares.
fn race_rtt(port: u16, rtt: Duration) -> Duration {
    rtt + dns_port_penalty(port)
}

/// The entry relays that may take part in the relay race (Ombra patch P1). Same JSON shape as `ExitSelector`.
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone)]
#[serde(rename_all = "camelCase")]
pub enum RelaySelector {
    Any {},
    Relay {
        id: String,
    },
    Country {
        country_code: CountryCode,
    },
    City {
        #[serde(flatten)]
        city_code: CityCode,
    },
}

impl RelaySelector {
    pub fn matches(&self, candidate: &OneRelay) -> bool {
        match self {
            RelaySelector::Any {} => true,
            RelaySelector::Relay { id } => candidate.id == *id,
            RelaySelector::Country { country_code } => candidate.city_code.country_code == *country_code,
            RelaySelector::City { city_code } => candidate.city_code == *city_code,
        }
    }
}

impl Default for RelaySelector {
    fn default() -> Self {
        RelaySelector::Any {}
    }
}

/// How the client reaches the entry relay: the tunnel mode with the feature flags and the SNI relay that apply to
/// it (Ombra patch P1). It is part of the target state, so a settings change that affects the live tunnel
/// reconnects at once, and a change that does not affect it does nothing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RelayTransport {
    Quic {
        sni: String,
        frame_padding: bool,
        force_small_mtu: bool,
    },
    TcpTls {
        sni: String,
    },
    WireguardUdp,
}

impl RelayTransport {
    pub fn new(mode: TunnelMode, feature_flags: &FeatureFlags, sni_relay: Option<&str>) -> Self {
        let enabled = |flag: Option<bool>| flag.unwrap_or(false);
        let sni = sni_relay.unwrap_or(DEFAULT_RELAY_SNI).to_owned();
        match mode {
            TunnelMode::WireguardUdp => Self::WireguardUdp,
            TunnelMode::Quic if !enabled(feature_flags.tcp_tls_tunnel) => Self::Quic {
                sni,
                frame_padding: enabled(feature_flags.quic_frame_padding),
                force_small_mtu: enabled(feature_flags.force_small_mtu),
            },
            TunnelMode::Quic | TunnelMode::TcpTls => Self::TcpTls { sni },
        }
    }
}

type RaceResult = (Result<(QuicWgConnHandshaking, Duration), QuicWgConnectError>, OneRelay, u16);

pub struct RelayHandshakeRace {
    tasks: JoinSet<RaceResult>,
    /// The same race without the MTU floor, started when no relay answered the floor race in time (Ombra patch P4, B1
    /// guard 1).
    floor_fallback: Option<RaceSpec>,
}

impl RelayHandshakeRace {
    pub async fn abandon(mut self) {
        self.tasks.abort_all();
        let mut abandons = JoinSet::new();
        let mut aborted = 0usize;
        let mut failed = 0usize;
        while let Some(task_result) = self.tasks.join_next().await {
            match task_result {
                Ok((Ok((handshaking, _rtt)), _relay, _port)) => {
                    abandons.spawn(handshaking.abandon());
                }
                Ok((Err(_), _relay, _port)) => failed += 1,
                Err(join_error) => {
                    if join_error.is_cancelled() {
                        aborted += 1;
                    }
                }
            }
        }
        tracing::info!(
            message_id = "Uw4kRz2n",
            aborted,
            failed,
            stopping = abandons.len(),
            "abandoned relay handshake race"
        );
        let _ = timeout(ABANDON_GRACE_PERIOD, async move { while abandons.join_next().await.is_some() {} }).await;
    }

    pub async fn next(&mut self) -> Option<(OneRelay, u16, Duration, QuicWgConnHandshaking)> {
        while let Some(task_result) = self.tasks.join_next().await {
            let Ok((result, relay, port)) = task_result else { continue };
            match result {
                Ok((handshaking, rtt)) => {
                    tracing::info!(
                        message_id = "7NCuscqm",
                        relay.id,
                        port,
                        rtt_ms = rtt.as_millis(),
                        "successfully started handshake with relay"
                    );
                    return Some((relay, port, rtt, handshaking));
                }
                Err(error) => {
                    tracing::warn!(
                        message_id = "Drl0nTSh",
                        ?error,
                        relay.id,
                        port,
                        "failed to connect during relay selection"
                    );
                }
            }
        }
        None
    }

    /// Picks the relay with the lowest RTT. A path that cannot carry 1350-byte packets loses the Initial packets of the
    /// floor race, so when no relay answers it within 2 s, the same relays race again without the floor (guard 1).
    pub async fn select(mut self) -> Result<(OneRelay, QuicWgConnHandshaking), RelaySelectionError> {
        let start = Instant::now();
        let mut deadline = start + Duration::from_secs(30);

        let mut relays_connected_successfully = BTreeSet::new();
        let mut best_candidate = None;

        loop {
            let waiting_for_floor = best_candidate.is_none() && self.floor_fallback.is_some();
            let wait_until = if waiting_for_floor {
                deadline.min(start + MTU_FLOOR_ANSWER_TIMEOUT)
            } else {
                deadline
            };
            let (relay, port, rtt, handshaking) = match timeout_at(wait_until, self.next()).await {
                Ok(Some(n)) => n,
                Ok(None) | Err(_) if waiting_for_floor => {
                    self.start_floor_fallback();
                    continue;
                }
                Ok(None) => {
                    tracing::info!(message_id = "aeY9Acha", "all relay handshake attempts finished",);
                    break;
                }
                Err(error) => {
                    tracing::info!(
                        message_id = "Eixooph8",
                        ?error,
                        deadline_s = (deadline - start).as_secs_f32(),
                        "relay selection deadline reached",
                    );
                    break;
                }
            };
            relays_connected_successfully.insert(relay.id.clone());

            let rejected = if best_candidate
                .as_ref()
                .is_some_and(|(_, best_port, best_rtt, _)| race_rtt(*best_port, *best_rtt) < race_rtt(port, rtt))
            {
                Some(handshaking)
            } else {
                // Only wait for 3x the time it took to find the best candidate. The chance that future relays have better RTT is minimal and it wastes time and increases the chance that we hang for a long time waiting on unreachable relays.
                // A port 53 candidate counts with its penalty, so that the other ports have time to answer (Ombra patch P6).
                deadline = start + min((start.elapsed() + dns_port_penalty(port)) * 3, Duration::from_secs(5));

                best_candidate
                    .replace((relay, port, rtt, handshaking))
                    .map(|(_, _, _, replaced)| replaced)
            };
            if let Some(rejected) = rejected {
                spawn(rejected.abandon());
            }

            if relays_connected_successfully.len() >= 5 {
                // With the 5 unique relays we have a high probability of having a very good candidate. Waiting for more responses just slows down connection for very minimal benefit.
                tracing::info!(message_id = "YeiNgo7k", "relay count limit reached",);
                break;
            }
        }
        let Some((relay, port, rtt, handshaking)) = best_candidate else {
            self.abandon().await;
            return Err(RelaySelectionError::NoSuccess);
        };
        spawn(self.abandon());
        tracing::info!(
            message_id = "Xdbn2PYb",
            relay.id,
            port,
            rtt_ms = rtt.as_millis(),
            mtu_floor = handshaking.mtu_floor(),
            "selected relay"
        );
        Ok((relay, handshaking))
    }

    fn start_floor_fallback(&mut self) {
        if let Some(fallback) = self.floor_fallback.take() {
            tracing::info!(
                message_id = "Zq5cTe1W",
                "no relay answered with the MTU floor in time, racing again without it"
            );
            fallback.spawn_handshakes(&mut self.tasks);
        }
    }
}

/// Everything that one relay race needs to start its handshakes.
#[derive(Clone)]
struct RaceSpec {
    network_interface: Option<NetworkInterface>,
    relays: Vec<OneRelay>,
    sni: Arc<String>,
    use_tcp_tls: bool,
    quic_endpoint: RelayEndpoint,
    quic_tuning: QuicTuning,
}

impl RaceSpec {
    fn spawn_handshakes(&self, tasks: &mut JoinSet<RaceResult>) {
        // Maximum number of relays and ports per relay to probe. These limits should be high enough that a non-malicious API server won't exceed them.
        // This prevents memory exhaustion issues in case a malicious API server sends a large number of relays or ports.
        const MAX_RELAYS: usize = 100;
        const MAX_PORTS_PER_RELAY: usize = 5;

        for relay in self.relays.iter().take(MAX_RELAYS) {
            for &port in relay.ports.iter().take(MAX_PORTS_PER_RELAY) {
                let quic_endpoint = self.quic_endpoint.clone();
                let relay_addr = (relay.ip_v4, port).into();
                let relay_cert = relay.tls_cert.clone().into();
                let relay = relay.clone();
                let sni = self.sni.clone();
                let network_interface = self.network_interface.clone();
                let use_tcp_tls = self.use_tcp_tls;
                let quic_tuning = self.quic_tuning;
                tasks.spawn(async move {
                    let result: Result<(QuicWgConnHandshaking, Duration), QuicWgConnectError> = async {
                        let mut handshaking = match use_tcp_tls {
                            true => {
                                QuicWgConnHandshaking::start_tcp_tls(relay.id.clone(), network_interface.as_ref(), relay_addr, relay_cert, &sni).await
                            }
                            false => {
                                QuicWgConnHandshaking::start_quic(relay.id.clone(), &quic_endpoint, relay_addr, relay_cert, &sni, quic_tuning).await
                            }
                        }?;
                        let rtt = handshaking.measure_rtt().await?;
                        Ok((handshaking, rtt))
                    }
                    .await;
                    (result, relay, port)
                });
            }
        }
    }
}

/// `mtu` and `force_small_mtu` set up the endpoint as upstream does: the relay is asked to send small packets when the
/// interface MTU is too small for 1350-byte payloads.
pub fn race_relay_handshakes(
    network_interface: Option<&NetworkInterface>,
    relays: &[OneRelay],
    sni: String,
    use_tcp_tls: bool,
    quic_tuning: QuicTuning,
    force_small_mtu: bool,
    mtu: Option<u16>,
) -> Result<RelayHandshakeRace, RelaySelectionError> {
    let udp = new_udp(network_interface).map_err(RelaySelectionError::UdpSetup)?;
    let quic_endpoint = new_quic(udp, mtu, force_small_mtu).map_err(RelaySelectionError::QuicSetup)?;
    let spec = RaceSpec {
        network_interface: network_interface.cloned(),
        relays: relays.to_vec(),
        sni: Arc::new(sni),
        use_tcp_tls,
        quic_endpoint,
        quic_tuning,
    };
    let mut tasks = JoinSet::new();
    spec.spawn_handshakes(&mut tasks);
    let floor_fallback = match (use_tcp_tls, quic_tuning.mtu.fallback()) {
        (false, Some(mtu)) => Some(RaceSpec { quic_tuning: QuicTuning { mtu, ..quic_tuning }, ..spec }),
        _ => None,
    };
    Ok(RelayHandshakeRace { tasks, floor_fallback })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::quic_tuning::MtuPolicy;
    use crate::test_relay::{LossyPath, TestRelay};
    use serde_json::json;

    fn relay(id: &str, country_code: &str, city_code: &str) -> OneRelay {
        OneRelay {
            id: id.into(),
            city_code: CityCode { country_code: CountryCode(country_code.into()), city_code: city_code.into() },
            city_name: city_code.into(),
            preferred_exits: Vec::new(),
            ip_v4: "192.0.2.10".parse().unwrap(),
            ports: vec![443],
            tls_cert: Vec::new(),
        }
    }

    fn city(country_code: &str, city_code: &str) -> CityCode {
        CityCode { country_code: CountryCode(country_code.into()), city_code: city_code.into() }
    }

    #[test]
    fn relay_selector_json_matches_kotlin() {
        let cases = [
            (RelaySelector::Any {}, json!({"any": {}})),
            (RelaySelector::Relay { id: "relay-1".into() }, json!({"relay": {"id": "relay-1"}})),
            (
                RelaySelector::Country { country_code: CountryCode("DE".into()) },
                json!({"country": {"country_code": "DE"}}),
            ),
            (
                RelaySelector::City { city_code: city("DE", "fra") },
                json!({"city": {"country_code": "DE", "city_code": "fra"}}),
            ),
        ];
        for (selector, expected) in cases {
            assert_eq!(serde_json::to_value(&selector).unwrap(), expected);
            assert_eq!(serde_json::from_value::<RelaySelector>(expected).unwrap(), selector);
        }
        assert_eq!(RelaySelector::default(), RelaySelector::Any {});
    }

    #[test]
    fn relay_selector_filters_candidates() {
        let relays = [relay("fra-1", "DE", "fra"), relay("ber-1", "DE", "ber"), relay("nyc-1", "US", "nyc")];
        let selected = |selector: RelaySelector| -> Vec<&str> { relays.iter().filter(|r| selector.matches(r)).map(|r| r.id.as_str()).collect() };
        assert_eq!(selected(RelaySelector::Any {}), ["fra-1", "ber-1", "nyc-1"]);
        assert_eq!(selected(RelaySelector::Relay { id: "ber-1".into() }), ["ber-1"]);
        assert_eq!(
            selected(RelaySelector::Country { country_code: CountryCode("DE".into()) }),
            ["fra-1", "ber-1"]
        );
        assert_eq!(selected(RelaySelector::City { city_code: city("US", "nyc") }), ["nyc-1"]);
        // Codes are matched verbatim, as for exits.
        assert!(selected(RelaySelector::Country { country_code: CountryCode("de".into()) }).is_empty());
        assert!(selected(RelaySelector::Relay { id: "gone".into() }).is_empty());
    }

    #[test]
    fn port_53_wins_the_race_only_when_clearly_faster() {
        let ms = Duration::from_millis;
        // The device case: the same relay, 13 ms on port 53 and on port 443.
        assert!(race_rtt(443, ms(13)) < race_rtt(53, ms(13)));
        assert!(race_rtt(50000, ms(17)) < race_rtt(53, ms(13)));
        assert!(race_rtt(53, ms(10)) < race_rtt(443, ms(40)));
        assert_eq!(race_rtt(443, ms(13)), ms(13));
    }

    fn flags(json: serde_json::Value) -> FeatureFlags {
        serde_json::from_value(json).unwrap()
    }

    #[test]
    fn relay_transport_applies_only_the_settings_of_its_mode() {
        let none = flags(json!({}));
        let all = flags(json!({"quicFramePadding": true, "forceSmallMtu": true, "tcpTlsTunnel": true, "killSwitch": true}));
        let quic_options = flags(json!({"quicFramePadding": true, "forceSmallMtu": true, "killSwitch": true}));

        assert_eq!(
            RelayTransport::new(TunnelMode::Quic, &none, None),
            RelayTransport::Quic { sni: DEFAULT_RELAY_SNI.into(), frame_padding: false, force_small_mtu: false }
        );
        assert_eq!(
            RelayTransport::new(TunnelMode::Quic, &quic_options, Some("sni.example")),
            RelayTransport::Quic { sni: "sni.example".into(), frame_padding: true, force_small_mtu: true }
        );
        // The tcpTlsTunnel flag still works, and then the QUIC options do not matter.
        assert_eq!(
            RelayTransport::new(TunnelMode::Quic, &all, None),
            RelayTransport::TcpTls { sni: DEFAULT_RELAY_SNI.into() }
        );
        assert_eq!(
            RelayTransport::new(TunnelMode::TcpTls, &none, None),
            RelayTransport::new(TunnelMode::TcpTls, &all, None)
        );
        assert_eq!(
            RelayTransport::new(TunnelMode::TcpTls, &none, Some("sni.example")),
            RelayTransport::TcpTls { sni: "sni.example".into() }
        );
        assert_eq!(
            RelayTransport::new(TunnelMode::WireguardUdp, &all, Some("sni.example")),
            RelayTransport::WireguardUdp
        );
    }

    async fn race_with_the_floor(relay: OneRelay) -> (QuicWgConnHandshaking, Duration) {
        let tuning = QuicTuning { frame_padding: false, mtu: MtuPolicy::Floor, ack_frequency: false };
        let started = Instant::now();
        let race = race_relay_handshakes(None, &[relay], DEFAULT_RELAY_SNI.into(), false, tuning, false, None).unwrap();
        let (_, handshaking) = race.select().await.unwrap();
        (handshaking, started.elapsed())
    }

    #[tokio::test]
    async fn floor_race_wins_on_a_path_that_carries_1350_bytes() {
        let relay = TestRelay::spawn();
        let (handshaking, elapsed) = race_with_the_floor(relay.listing(relay.addr)).await;
        assert!(handshaking.mtu_floor());
        assert!(elapsed < MTU_FLOOR_ANSWER_TIMEOUT);
    }

    #[tokio::test]
    async fn races_without_the_floor_when_no_relay_answers_in_time() {
        let relay = TestRelay::spawn();
        // The padded 1350-byte Initial packets of the floor race never arrive.
        let path = LossyPath::small_mtu(relay.addr, 1300);
        let (handshaking, elapsed) = race_with_the_floor(relay.listing(path.addr)).await;
        assert!(!handshaking.mtu_floor());
        assert!(elapsed >= MTU_FLOOR_ANSWER_TIMEOUT);
    }
}
