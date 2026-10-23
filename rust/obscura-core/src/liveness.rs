use crate::wake_instant::WakeInstant;
use etherparse::{IcmpEchoHeader, Icmpv4Type, PacketBuilder, SlicedPacket, TransportSlice};
use rand::{RngCore, thread_rng};
use static_assertions::{const_assert, const_assert_ne};
use std::cmp::min;
use std::collections::VecDeque;
use std::net::Ipv4Addr;
use std::time::Duration;

const MAX_ALLOWED_LOST_PROBES: usize = 4;
const MAX_ALLOWED_LOST_PROBES_AFTER_SLEEP: usize = 1;
const BUSY_PING_PERIOD: Duration = Duration::from_secs(1);
const IDLE_PING_PERIOD: Duration = Duration::from_secs(55);
const_assert_ne!(
    0,
    crate::quicwg::QUIC_IDLE_TIMEOUT
        .saturating_sub(IDLE_PING_PERIOD)
        .saturating_sub(Duration::from_millis(4999))
        .as_millis()
);
const MIN_PROBE_LOST_PERIOD: Duration = Duration::from_secs(1);
const MAX_PROBE_LOST_PERIOD: Duration = Duration::from_secs(30);
const SLOW_PONG_WINDOW: u32 = 100;
/// Ombra patch P4 (B1 guard 2): with the MTU floor, every 10th busy probe is a large one.
const LARGE_PROBE_INTERVAL: u32 = 10;
const MAX_LARGE_PROBES_LOST_IN_A_ROW: usize = 3;
/// More than the probes of the longest probe lost period (30 s at one busy probe per second) plus the allowed losses.
const MAX_OUTSTANDING_PROBES: usize = 64;

// Randomly generated value to reliably distinguish our probes from other pings
const PROBE_PREFIX: &[u8; 32] = b"obs-ping\x75\xf8\xb9\x47\x4b\xe1\x61\xeb\x1c\xb1\xeb\x5e\xc0\x6c\xde\xb7\xa1\x1b\x7b\xe5\x85\xca\x3a\x95";

pub struct LivenessChecker {
    next_id_seq: u32,
    mtu: u16,
    /// The size of the large probes, or `None` when the tunnel sends only small ones. With the MTU floor, quinn never
    /// lowers the MTU, so a path that drops full-size packets while small ones pass is found only by these probes.
    large_probe_mtu: Option<u16>,
    busy_probes_since_large_probe: u32,
    large_probes_lost_in_a_row: usize,
    src_ip: Ipv4Addr,
    dst_ip: Ipv4Addr,
    sent_user_traffic_since_last_ping: bool,
    /// Probes sent after the last packet from the exit (Ombra patch P4, C4).
    probes_sent_since_received_traffic: usize,
    is_waking: bool,
    outstanding_pongs: VecDeque<SentPing>,
    last_ping_sent_at: Option<WakeInstant>,
    slowest_pongs: VecDeque<ReceivedPong>,
}

struct SentPing {
    sent_at: WakeInstant,
    id_seq: u32,
    payload: Vec<u8>,
    large: bool,
}

impl LivenessChecker {
    pub fn new(mtu: u16, large_probe_mtu: Option<u16>, client_ip: Ipv4Addr, ping_target_ip: Ipv4Addr) -> Self {
        Self {
            next_id_seq: 0,
            mtu,
            large_probe_mtu,
            busy_probes_since_large_probe: 0,
            large_probes_lost_in_a_row: 0,
            src_ip: client_ip,
            dst_ip: ping_target_ip,
            sent_user_traffic_since_last_ping: false,
            probes_sent_since_received_traffic: 0,
            is_waking: false,
            outstanding_pongs: Default::default(),
            last_ping_sent_at: None,
            slowest_pongs: Default::default(),
        }
    }

    fn probe_lost_period(&self) -> Duration {
        self.slowest_pongs
            .front()
            .map(|pong| pong.rtt * 2)
            .unwrap_or(MIN_PROBE_LOST_PERIOD)
            .clamp(MIN_PROBE_LOST_PERIOD, MAX_PROBE_LOST_PERIOD)
    }

    // returns the number of likely lost probes, as well as when the next one would be considered lost
    fn lost_probe_count_and_time_of_next_loss(&self, now: WakeInstant) -> (usize, Option<WakeInstant>) {
        let probe_lost_period = self.probe_lost_period();
        for (i, outstanding_pong) in self.outstanding_pongs.iter().enumerate() {
            let expires_at = outstanding_pong.sent_at + probe_lost_period;
            if expires_at > now {
                return (self.without_probes_before_received_traffic(i), Some(expires_at));
            }
        }
        (self.without_probes_before_received_traffic(self.outstanding_pongs.len()), None)
    }

    /// A packet from the exit proves that the tunnel worked after the probes sent before it, so of the `lost_probes`
    /// oldest outstanding probes only those sent after it count. Probes share the datagram queue with bulk data, and
    /// on a slow uplink they can wait longer than the lost period while the tunnel works (Ombra patch P4, C4).
    fn without_probes_before_received_traffic(&self, lost_probes: usize) -> usize {
        let probes_before_received_traffic = self.outstanding_pongs.len().saturating_sub(self.probes_sent_since_received_traffic);
        lost_probes.saturating_sub(probes_before_received_traffic)
    }

    /// Call for each packet from the exit.
    pub fn received_traffic(&mut self) {
        self.probes_sent_since_received_traffic = 0;
    }

    // Call when sending a packet that does not originate from the liveness checker. May return a packet for sending.
    #[must_use = "may return a packet, which needs to be sent"]
    pub fn sent_traffic(&mut self) -> Option<Vec<u8>> {
        self.sent_traffic_at(WakeInstant::now())
    }

    fn sent_traffic_at(&mut self, now: WakeInstant) -> Option<Vec<u8>> {
        if self.last_ping_sent_at.is_none_or(|last_ping| now > last_ping + BUSY_PING_PERIOD) {
            // Ping is overdue. Don't wait for next poll call.
            tracing::info!(message_id = "k5jg6f3w", "liveness checker sent_traffic returning packet");
            return Some(self.send_ping(now, true));
        }
        self.sent_user_traffic_since_last_ping = true;
        None
    }

    // Call after sleep. Reduces the number of lost probes needed to classify as dead until a probe succeeded. Returns a packet for sending.
    #[must_use = "the returned packet needs to be sent"]
    pub fn wake(&mut self) -> Vec<u8> {
        tracing::info!(message_id = "OsZ6HBJO", "liveness checker wake called");
        // Reset state to force an "aggressive" liveness check when the user wakes the device.
        // - Higher risk of broken connection after sleep.
        // - Likely chance that something will use the network (we probably woke for a reason).
        let now = WakeInstant::now();
        *self = Self::new(self.mtu, self.large_probe_mtu, self.src_ip, self.dst_ip);
        self.is_waking = true;
        // Immediately test connection after wake.
        self.send_ping(now, false)
    }

    pub fn poll(&mut self) -> LivenessCheckerPoll {
        self.poll_at(WakeInstant::now())
    }

    fn poll_at(&mut self, now: WakeInstant) -> LivenessCheckerPoll {
        let (lost_probes, next_probe_loss) = self.lost_probe_count_and_time_of_next_loss(now);
        let max_lost_probes = if self.is_waking {
            MAX_ALLOWED_LOST_PROBES_AFTER_SLEEP
        } else {
            MAX_ALLOWED_LOST_PROBES
        };
        if lost_probes > max_lost_probes {
            tracing::error!(
                message_id = "2sonYhc2",
                lost_probes,
                max_lost_probes,
                "liveness checker poll returning Dead"
            );
            return LivenessCheckerPoll::Dead;
        }
        if self.large_probes_lost_in_a_row >= MAX_LARGE_PROBES_LOST_IN_A_ROW {
            tracing::error!(
                message_id = "Hn3vKq8T",
                large_probes_lost_in_a_row = self.large_probes_lost_in_a_row,
                "liveness checker poll returning LargeProbesLost: the path drops full-size packets"
            );
            return LivenessCheckerPoll::LargeProbesLost;
        }

        let ping_period = if self.sent_user_traffic_since_last_ping || lost_probes != 0 {
            BUSY_PING_PERIOD
        } else {
            IDLE_PING_PERIOD
        };
        tracing::info!(
            message_id = "KZjNGhxu",
            lost_probes,
            max_lost_probes,
            since_last_ping_ms = self.last_ping_sent_at.map(|i| now.saturating_duration_since(i).as_millis()),
            until_next_probe_loss_ms = next_probe_loss.map(|i| i.saturating_duration_since(now).as_millis()),
            ping_period_ms = ping_period.as_millis(),
            "liveness checker probe loss ok"
        );

        if self.last_ping_sent_at.is_none_or(|last_ping| last_ping + ping_period <= now) {
            tracing::info!(message_id = "7UnUaqos", "liveness checker poll returning SendPacket",);
            let busy = self.sent_user_traffic_since_last_ping;
            return LivenessCheckerPoll::SendPacket(self.send_ping(now, busy));
        }

        const_assert!(BUSY_PING_PERIOD.as_nanos() <= IDLE_PING_PERIOD.as_nanos());
        let mut next_poll = now + BUSY_PING_PERIOD;
        if let Some(next_probe_loss) = next_probe_loss {
            next_poll = min(next_poll, next_probe_loss)
        }
        if let Some(last_ping_sent_at) = self.last_ping_sent_at {
            next_poll = min(next_poll, last_ping_sent_at + ping_period)
        }
        tracing::info!(
            message_id = "Yd79pARH",
            until_next_poll_ms = next_poll.saturating_duration_since(now).as_millis(),
            "liveness checker poll returning AliveUntil",
        );
        LivenessCheckerPoll::AliveUntil(next_poll)
    }

    // Checks if a packet is an expected probe response and returns the probe latency if it is.
    pub fn process_potential_probe_response(&mut self, packet: &[u8]) -> Option<Duration> {
        self.process_potential_probe_response_at(packet, WakeInstant::now())
    }

    fn process_potential_probe_response_at(&mut self, packet: &[u8], now: WakeInstant) -> Option<Duration> {
        let ip = SlicedPacket::from_ip(packet).ok()?;
        let Some(TransportSlice::Icmpv4(icmp)) = ip.transport else { return None };
        let pong_id_seq = {
            let Icmpv4Type::EchoReply(IcmpEchoHeader { id, seq }) = icmp.icmp_type() else {
                return None;
            };
            let id = id.to_be_bytes();
            let seq = seq.to_be_bytes();
            u32::from_be_bytes([id[0], id[1], seq[0], seq[1]])
        };
        if !icmp.payload().starts_with(PROBE_PREFIX) {
            return None;
        }
        let last_sent_id_seq = self.next_id_seq.wrapping_sub(1);
        let mut matched_pong_index = None;
        for (i, SentPing { payload, id_seq, .. }) in self.outstanding_pongs.iter().enumerate() {
            if payload == icmp.payload() && *id_seq == pong_id_seq {
                matched_pong_index = Some(i);
                break;
            }
        }
        if let Some(matched_pong_index) = matched_pong_index {
            let sent_at = self.outstanding_pongs[matched_pong_index].sent_at;
            let probe_rtt = now.checked_duration_since(sent_at).unwrap_or_default();
            self.update_slowest_pongs_list(pong_id_seq, probe_rtt);
            self.count_large_probe_losses(matched_pong_index);
            self.outstanding_pongs.drain(0..=matched_pong_index);
            tracing::info!(
                message_id = "ETUFSKaF",
                pong_id_seq,
                last_sent_id_seq,
                ?probe_rtt,
                outstanding_pongs_len = self.outstanding_pongs.len(),
                slowest_pongs_len = self.slowest_pongs.len(),
                slowest_pong_rtt = ?self.slowest_pongs.front().map(|p|p.rtt),
                "received liveness checker pong"
            );
            self.is_waking = false;
            Some(probe_rtt)
        } else {
            tracing::info!(
                message_id = "tDMDB46X",
                pong_id_seq,
                last_sent_id_seq,
                "ignoring liveness checker pong with unrecognized payload"
            );
            None
        }
    }

    // Maintain list of the slow pongs (high rtt) with these requirements:
    // - must correspond to any of the last SLOW_PONG_WINDOW sent pings
    // - no more recent pong was slower
    // - in order of respective sent ping (oldest first)
    fn update_slowest_pongs_list(&mut self, id_seq: u32, rtt: Duration) {
        while self
            .slowest_pongs
            .front()
            .is_some_and(|oldest| id_seq.wrapping_sub(oldest.id_seq) > SLOW_PONG_WINDOW)
        {
            self.slowest_pongs.pop_front();
        }
        while self.slowest_pongs.back().is_some_and(|newest| newest.rtt <= rtt) {
            self.slowest_pongs.pop_back();
        }
        self.slowest_pongs.push_back(ReceivedPong { id_seq, rtt });
    }

    /// Probes sent before the answered one count as lost. Large ones lost while a small one returns mean that the path
    /// drops full-size packets; an answered large one shows that it does not.
    fn count_large_probe_losses(&mut self, answered_index: usize) {
        if self.outstanding_pongs[answered_index].large {
            self.large_probes_lost_in_a_row = 0;
        } else {
            self.large_probes_lost_in_a_row += self.outstanding_pongs.range(..answered_index).filter(|ping| ping.large).count();
        }
    }

    fn next_probe_is_large(&mut self, busy: bool) -> bool {
        if !busy || self.large_probe_mtu.is_none() {
            return false;
        }
        self.busy_probes_since_large_probe += 1;
        let large = self.busy_probes_since_large_probe >= LARGE_PROBE_INTERVAL;
        if large {
            self.busy_probes_since_large_probe = 0;
        }
        large
    }

    /// `busy`: the probe is sent because the tunnel carries user traffic.
    fn send_ping(&mut self, now: WakeInstant, busy: bool) -> Vec<u8> {
        let large = self.next_probe_is_large(busy);
        let size = self.large_probe_mtu.filter(|_| large).unwrap_or(self.mtu);
        self.last_ping_sent_at = Some(now);
        self.probes_sent_since_received_traffic += 1;
        self.sent_user_traffic_since_last_ping = false;

        let id_seq = self.next_id_seq;
        self.next_id_seq += 1;
        let id_seq_bytes = id_seq.to_be_bytes();
        let id = u16::from_be_bytes(id_seq_bytes[0..2].try_into().unwrap());
        let seq = u16::from_be_bytes(id_seq_bytes[2..4].try_into().unwrap());
        let builder = PacketBuilder::ipv4(self.src_ip.octets(), self.dst_ip.octets(), 255).icmpv4_echo_request(id, seq);
        let overhead = builder.size(0);
        debug_assert_eq!(overhead, 28);
        let mut payload: Vec<u8> = vec![0; usize::from(size) - overhead];
        payload[0..32].copy_from_slice(PROBE_PREFIX);
        thread_rng().fill_bytes(&mut payload[32..]);
        let total_size = builder.size(payload.len());
        debug_assert_eq!(total_size, usize::from(size));
        let mut packet = Vec::<u8>::with_capacity(total_size);
        builder.write(&mut packet, &payload).unwrap();

        self.outstanding_pongs.push_back(SentPing { sent_at: now, id_seq, payload, large });
        self.forget_oldest_probes_before_received_traffic();
        packet
    }

    /// Packets from the exit keep a tunnel alive whose probes get no answer (C4), so without a limit the outstanding
    /// probes would grow by one each second. Probes sent before the last packet from the exit never count as lost, so
    /// the oldest of them go first, and the loss count stays the same.
    fn forget_oldest_probes_before_received_traffic(&mut self) {
        let probes_before_received_traffic = self.outstanding_pongs.len().saturating_sub(self.probes_sent_since_received_traffic);
        let excess = self.outstanding_pongs.len().saturating_sub(MAX_OUTSTANDING_PROBES);
        self.outstanding_pongs.drain(..excess.min(probes_before_received_traffic));
    }
}

#[must_use = "this `LivenessCheckerPoll` may need to be handled"]
#[derive(Debug)]
pub enum LivenessCheckerPoll {
    Dead,
    /// Large probes were lost while small ones returned (Ombra patch P4, B1 guard 2).
    LargeProbesLost,
    AliveUntil(WakeInstant),
    SendPacket(Vec<u8>),
}

struct ReceivedPong {
    id_seq: u32,
    rtt: Duration,
}

#[cfg(test)]
mod tests {
    use super::*;

    const CLIENT_IP: Ipv4Addr = Ipv4Addr::new(10, 64, 0, 2);
    const GATEWAY_IP: Ipv4Addr = Ipv4Addr::new(10, 64, 0, 1);
    const SMALL: u16 = 100;
    const LARGE: u16 = 1280;

    fn pong(probe: &[u8]) -> Vec<u8> {
        let ip = SlicedPacket::from_ip(probe).unwrap();
        let Some(TransportSlice::Icmpv4(icmp)) = ip.transport else {
            panic!("probe is not ICMPv4")
        };
        let Icmpv4Type::EchoRequest(IcmpEchoHeader { id, seq }) = icmp.icmp_type() else {
            panic!("probe is not an echo request")
        };
        let mut reply = Vec::new();
        PacketBuilder::ipv4(GATEWAY_IP.octets(), CLIENT_IP.octets(), 64)
            .icmpv4_echo_reply(id, seq)
            .write(&mut reply, icmp.payload())
            .unwrap();
        reply
    }

    /// Sends user traffic for `seconds`, so one busy probe goes out per second, and answers the probes that `answer`
    /// accepts. Returns the sizes of all probes and the time at the end.
    fn busy_tunnel(
        checker: &mut LivenessChecker,
        start: WakeInstant,
        seconds: u32,
        mut answer: impl FnMut(&[u8]) -> bool,
    ) -> (Vec<usize>, WakeInstant) {
        let mut now = start;
        let mut sizes = Vec::new();
        for _ in 0..seconds {
            now = now + Duration::from_millis(1001);
            let Some(probe) = checker.sent_traffic_at(now) else { continue };
            sizes.push(probe.len());
            if answer(&probe) {
                assert!(
                    checker
                        .process_potential_probe_response_at(&pong(&probe), now + Duration::from_millis(20))
                        .is_some()
                );
            }
        }
        (sizes, now)
    }

    fn is_large(probe: &[u8]) -> bool {
        probe.len() == usize::from(LARGE)
    }

    #[test]
    fn test_probe_packet_size() {
        let mut checker = LivenessChecker::new(SMALL, None, Ipv4Addr::LOCALHOST, Ipv4Addr::LOCALHOST);
        assert_eq!(checker.send_ping(WakeInstant::now(), true).len(), usize::from(SMALL));
    }

    #[test]
    fn every_tenth_busy_probe_is_large_with_the_floor() {
        let mut checker = LivenessChecker::new(SMALL, Some(LARGE), CLIENT_IP, GATEWAY_IP);
        let (sizes, now) = busy_tunnel(&mut checker, WakeInstant::now(), 30, |_| true);
        assert_eq!(sizes.len(), 30);
        let large: Vec<usize> = sizes
            .iter()
            .enumerate()
            .filter(|(_, size)| **size == usize::from(LARGE))
            .map(|(i, _)| i)
            .collect();
        assert_eq!(large, [9, 19, 29]);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));

        // Idle probes and the probe after a wake are small.
        assert_eq!(checker.send_ping(now, false).len(), usize::from(SMALL));
        assert_eq!(checker.wake().len(), usize::from(SMALL));

        let mut without_floor = LivenessChecker::new(SMALL, None, CLIENT_IP, GATEWAY_IP);
        let (sizes, _) = busy_tunnel(&mut without_floor, WakeInstant::now(), 30, |_| true);
        assert!(sizes.iter().all(|size| *size == usize::from(SMALL)));
    }

    #[test]
    fn three_large_probes_lost_while_small_ones_return() {
        let mut checker = LivenessChecker::new(SMALL, Some(LARGE), CLIENT_IP, GATEWAY_IP);
        // The third large probe is sent at second 30 and counts as lost when the small one after it returns.
        let (_, now) = busy_tunnel(&mut checker, WakeInstant::now(), 30, |probe| !is_large(probe));
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));
        let (_, now) = busy_tunnel(&mut checker, now, 1, |probe| !is_large(probe));
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::LargeProbesLost));
    }

    #[test]
    fn an_answered_large_probe_resets_the_large_probe_losses() {
        let mut checker = LivenessChecker::new(SMALL, Some(LARGE), CLIENT_IP, GATEWAY_IP);
        let mut large_probes = 0;
        // Large probes 1, 2, 4 and 5 are lost, the third returns.
        let (_, now) = busy_tunnel(&mut checker, WakeInstant::now(), 51, |probe| {
            if !is_large(probe) {
                return true;
            }
            large_probes += 1;
            large_probes == 3
        });
        assert_eq!(large_probes, 5);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));
    }

    #[test]
    fn packets_from_the_exit_keep_the_tunnel_alive_while_probes_are_lost() {
        let mut checker = LivenessChecker::new(SMALL, None, CLIENT_IP, GATEWAY_IP);
        let mut now = WakeInstant::now();
        for _ in 0..20 {
            now = now + Duration::from_millis(1001);
            assert!(checker.sent_traffic_at(now).is_some());
            checker.received_traffic();
            assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));
        }
        // The exit goes quiet: dead once more than 4 probes sent after its last packet are lost.
        let (_, now) = busy_tunnel(&mut checker, now, 5, |_| false);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));
        let (_, now) = busy_tunnel(&mut checker, now, 1, |_| false);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::Dead));
    }

    #[test]
    fn unanswered_probes_stay_few_while_the_exit_sends_packets() {
        let mut checker = LivenessChecker::new(SMALL, Some(LARGE), CLIENT_IP, GATEWAY_IP);
        let mut now = WakeInstant::now();
        for _ in 0..1000 {
            now = now + Duration::from_millis(1001);
            assert!(checker.sent_traffic_at(now).is_some());
            checker.received_traffic();
            assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));
            assert!(checker.outstanding_pongs.len() <= MAX_OUTSTANDING_PROBES);
        }
        // The probes after the last packet from the exit still count.
        let (_, now) = busy_tunnel(&mut checker, now, 5, |_| false);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::AliveUntil(_)));
        let (_, now) = busy_tunnel(&mut checker, now, 1, |_| false);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::Dead));
    }

    #[test]
    fn all_probes_lost_is_dead_not_large_probes_lost() {
        let mut checker = LivenessChecker::new(SMALL, Some(LARGE), CLIENT_IP, GATEWAY_IP);
        let (_, now) = busy_tunnel(&mut checker, WakeInstant::now(), 10, |_| false);
        assert!(matches!(checker.poll_at(now), LivenessCheckerPoll::Dead));
    }
}
