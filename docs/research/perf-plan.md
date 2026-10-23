# perf-plan: SPEED PLAN for the native Obscura Android client

Date: 2026-10-20. Scope: throughput, latency, stalls, CPU and battery only.
Inputs: perf-datapath.md, perf-udp-mode.md, perf-evidence.md, perf-bench.md. I checked their key claims against the code and re-ran part of the bench.

Code revisions: obscuravpn-client 85ef7e2 (HEAD 2026-10-19), obscuravpn-api e94483e (pinned), neptun ff3b555, quinn 0.11.12, quinn-proto 0.11.19, quinn-udp 0.5.16 (all from `rustlib/Cargo.lock`). quinn sources: `~/.cargo/registry/src/index.crates.io-*/`. Upstream history: `/tmp/obscura-research/history`, quinn history: `/tmp/obscura-research/quinn-history`.

Labels:
- [V] I verified it (code read, git history, or my own bench re-run).
- [M] Another agent measured it. I checked that the method and arithmetic are consistent, but I did not re-run it.
- [R] Third-party report. Not reproduced.
- [H] Hypothesis. It needs a device or the real relay to confirm.

My re-run: `/tmp/obscura-research/bench/results/plan_verify.jsonl` (qwbench, Apple Silicon, loopback and emulated 100 Mbit/s / 40 ms RTT / 50 ms drop-tail path).

---

## 0. Summary

- The original app sends WireGuard inside QUIC DATAGRAM frames to a relay. The official WireGuard app sends plain WireGuard UDP to the same relay (Obscura portal config) or to Mullvad. If the comparison used the Obscura portal config, the hop, the exit and the inner MTU (1280) are the same. The difference is the QUIC layer and the Rust data path.
- Five causes explain most of the gap: (1) QUIC costs 3 to 4 times more CPU for each packet. (2) A false MTU black-hole detection splits each full-size upload packet into two. (3) The closed-source relay downlink is unknown, but it is a possible cause. (4) Any network change starts a full reconnect. (5) The opt-in flags (padding, TCP/TLS, small MTU) cost a lot.
- One part of the original analysis is wrong: the "nested congestion control with quinn BBR" theory. The quinn BBR in this configuration never leaves Startup, so its cwnd grows without limit (measured 8.6 MB to 605 MB). It does not throttle uploads. Do not switch to Cubic.
- Plan: (a) Add a "Speed" mode: plain WireGuard over UDP through the same Obscura relay, from the API's `CreateTunnel::UdpPort`. The two-party relay model stays. Only the stealth property is lost. (b) Fix the Stealth (QUIC) mode: an MTU floor with guards, ACK frequency, a smaller queue, socket buffers, rebind instead of reconnect, and liveness and padding fixes. (c) Measure with the protocol in section 5.

---

## 1. Root causes of "slower than WireGuard" (ranked)

Rank = how likely the cause is, multiplied by its size for a typical phone on Wi-Fi and LTE.

| # | Cause | Direction | Impact | Confidence |
|---|---|---|---|---|
| 1 | QUIC data path CPU cost: 3 to 4x CPU per packet compared with WG/UDP, serialized on one quinn driver task | both | High above ~100-300 Mbit/s, and on battery and thermal at every rate | High (mechanism, desktop numbers), medium (phone magnitude) |
| 2 | False MTU black-hole detection in quinn: the QUIC MTU falls to 1200, so every full-size WG packet becomes 2 QUIC datagrams | upload | Medium-high: +65% CPU per packet, 2x loss sensitivity, queued datagrams dropped | High |
| 3 | Relay downlink sender (closed source): no MTU discovery, unknown CC, possible permanent downlink fragmentation after one false black hole | download | Possibly the largest on lossy links | Low-medium [H], decisive test exists |
| 4 | Full reconnect on every network change (TUN re-established, new tunnel), and liveness can declare a loaded tunnel dead | both | Multi-second stalls, possibly reset app connections | High (mechanism), frequency unknown |
| 5 | Opt-in flags: packet padding, TCP/TLS tunnel, small MTU | down / both | Very high if on | High (mechanism), unknown if the user enabled them |
| 6 | Android UDP GSO hazards in quinn-udp 0.5.16 | upload | Device-specific batch loss, reconnects | Medium-low [R]+[V] |
| 7 | Small items: no UDP socket buffer tuning, 1 MiB datagram queue on slow uplinks, per-packet syscalls/locks/copies, 1 Hz persisted log, IPv4-only relays (CLAT) | both | Low each | Medium |

These do not explain the gap if the baseline was the Obscura portal WG config: topology, the inner MTU (the WG Android app uses 1280 when the config has no MTU line: `GoBackend.java:330 builder.setMtu(config.getInterface().getMtu().orElse(1280));` [V]; `wg_conf.rs:4-21` writes no MTU [V]), and WG crypto. If the baseline was Mullvad direct, the extra relay hop adds RTT. That is part of the design and cannot be removed.

### 1.1 Cause 1: QUIC data path CPU cost [V]

Hot path, TX (Android). One `read()` and one `send()` per packet. `rustlib/src/android/tunnel.rs:28-37`:
```rust
let read_loop_task = tokio::spawn(async move {
    let mut buf = Box::new([0; u16_into_usize(TUNNEL_MTU)]);
    loop {
        match fd_watcher.readable().await {
            Ok(mut guard) => match unistd::read(&fd_watcher, &mut buf[..]) {
                Ok(n) => {
                    if n > 0 {
                        tunnel.send(std::iter::once(&buf[..n]))
                    }
                }
```
Then one global mutex for TX and RX. `rustlib/src/quicwg.rs:383-384` and `403-421`:
```rust
fn send<'a>(&self, packets: impl Iterator<Item = &'a [u8]>) {
    let mut wg_state = self.wg_state.lock().unwrap();
...
fn send_single_packet(&self, wg_state: &mut WgState, packet: &[u8]) {
    match wg_state.wg.encapsulate(packet, &mut wg_state.buffer) {
        ...
        TunnResult::WriteToNetwork(wg_message) => {
            ...
            let wg_message = Bytes::copy_from_slice(wg_message);
            let (first, second) = match self.wg_sender.max_wg_message_size() {   // quinn conn lock (quicwg.rs:917)
                Some(max_size) => wg_state.fragmenter.fragment(wg_message, max_size),
                None => (wg_message, None),
            };
            wg_state.tick_stats.wg_tx_fragmented_count += u64::from(second.is_some());
            for msg in once(first).chain(second) {
                self.wg_sender.send_wg_message(msg);                               // conn.send_datagram (quicwg.rs:925), quinn lock again
            }
```
RX: one task, `quicwg.rs:468-489`, takes the same `wg_state` lock, reassembles, decapsulates, and copies. Then `tunnel_state.rs:334-335` (`conn.receive().await` -> `os_impl.packet_for_os(packet)`), which runs a std Mutex plus one `unistd::write` per packet (`android/os_impl.rs:65-67`, `android/tunnel.rs:63`). The Linux build batches up to 100 TUN packets per lock (`os/packet_buffer.rs:3`, `bin/obscura/service/os/linux/tun.rs:72,163-166`) [V]. Android does not.

Numbers (Apple Silicon, 1280 B inner packets, CPU-bound loopback):

| Path | Goodput | Client us/pkt | Source |
|---|---|---|---|
| rustlib QUIC config | 1277 Mbit/s | 11.9 | [V] my re-run |
| neptun WG over plain UDP | 3468 Mbit/s | 2.9 | [V] my re-run |
| rustlib QUIC + ACK frequency | 1220 Mbit/s (within run noise) | 9.7 (-18%) | [V] my re-run |
| rustlib QUIC, MTU 1200 (all fragmented) | 805 Mbit/s | 18.8 | [M] perf-bench |
| wireguard-go, same Mac | 1830 Mbit/s | n/a | [M] perf-bench |

Two other harnesses agree (perf-udp-mode: 1.03-1.44 vs 3.35 Gbit/s) [M]. WG crypto is not the limit: neptun encapsulate is 0.73 us/pkt [M]. The profile shows that the single quinn `ConnectionDriver` is the limit (packet build, AES-GCM, sendmsg, ACK processing, all under one connection lock) [M].
Phone ceiling [H]: big core ~550-750, mid core ~300, little core ~130-170 Mbit/s for the QUIC path, and ~40% less while fragmented. This is extrapolated from single-core ratios, not measured. The battery cost per GB scales the same way.

### 1.2 Cause 2: false MTU black-hole detection -> fragmentation [V]

Byte budget: `TUNNEL_MTU = 1280` (`quicwg.rs:61`), `DEFAULT_UDP_PAYLOAD_SIZE = 1350` (`:64`). quinn `max_size = current_mtu - (1 + dcid_len + 4 + tag) - 9` (`quinn-proto connection/datagrams.rs:82-96`, `connection/mod.rs:3763-3776`, `frame.rs:901-903`). At MTU 1350 with an 8 B CID, the result is 1312 = 1280 + 32 WG overhead: exactly 0 bytes of slack. Below 1350, every full-size packet is split in two (`quicwg.rs:413-416`).

Config, `quicwg.rs:739-745`:
```rust
let mut mtu_discovery_config = MtuDiscoveryConfig::default();
mtu_discovery_config.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);
mtu_discovery_config.black_hole_cooldown(Duration::from_secs(10));
transport_config.mtu_discovery_config(Some(mtu_discovery_config));
transport_config.max_idle_timeout(Some(QUIC_IDLE_TIMEOUT.try_into()?));
transport_config.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
transport_config.pad_to_mtu(quic_frame_padding);
```
`initial_mtu` and `min_mtu` stay at the default of 1200 (`quinn-proto config/transport.rs:385-386`, `lib.rs:332`) [V].

The detector bug still exists in quinn-proto 0.11.19. `connection/mtud.rs:405-410`:
```rust
fn on_non_probe_acked(&mut self, pn: u64, len: u16) {
    if len < self.acked_mtu {
        // We've already seen a larger packet since the most recent suspicious loss burst;
        // nothing to do.
        return;
    }
```
- After the 1350 B MTU probe is acked, `acked_mtu = 1350` (`mtud.rs:398`).
- A full-size data packet is about 1341-1344 B: 1312 + 3 frame + 1 + 8 CID + 1-4 PN + 16 tag. So data acks almost never refresh `largest_post_loss_packet`.
- Because of this, every later congestion loss burst of packets > 1200 B is judged "suspicious" (`mtud.rs:459-489`). More than 3 bursts (`BLACK_HOLE_THRESHOLD = 3`, `:528`) set MTU = 1200.
- At that moment, all queued 1312 B datagrams are discarded (`datagrams.rs:182-198`, `drop_oversized`, called at `connection/mod.rs:1429,1799,3089`).
- The 0.11.19 fix (b0afb6e6, `#2792`) only handles deliveries of exactly equal size [V, quinn-history].

My re-run, emulated 100 Mbit/s, 40 ms RTT, 50 ms drop-tail, inner Cubic-like TCP, 15 s [V]:

| Config | Fragmented | Black holes | Goodput | MTU timeline |
|---|---|---|---|---|
| rustlib (current) | 68% (93,178 of 136,255) | 2 | 91.3 Mbit/s | 1200 from 1 s to 10 s |
| `initial_mtu=min_mtu=1350` | 0% | 0 | 93.4 Mbit/s | 1350 throughout |
| `black_hole_cooldown(2 s)` | 15% | 1 | 93.2 Mbit/s | 1200 from 1 s to 2 s |

Under overload (150 Mbit/s offered into 100), goodput was 63.9 vs 95.1 Mbit/s for the current config vs the 1350 floor [M]. On lossy links: a fragmented message is lost if either half is lost. So the inner loss is about 2x, and inner TCP throughput (Mathis, ~1/sqrt(p)) is about 29% lower.

Version history [V, git]:
- Up to v1.160 (2026-05-12): fixed `initial_mtu = min_mtu = 1350` (49295c6).
- c2fb8b8 (2026-05-21): "Enable client-side QUIC MTU discovery unconditionally" removed the floor.
- v1.162 (2026-06-04) to v1.181 (2026-09-29): MTU discovery plus the Obscura quinn fork 8f690a2. The fork has the older `if len <= self.acked_mtu { return; }` (`quinn-history`, `git show 8f690a2:quinn-proto/src/connection/mtud.rs:401`). That version pins the MTU at 1200 for most of a bulk transfer [R quinn #2791].
- v1.182 (2026-10-07): crates.io quinn-proto 0.11.19 (845d6c7). The problem still appears at the start of each bulk upload, as shown above.

This cause hits uploads. During downloads the client sends only small packets (inner ACKs), and the detector ignores packets of 1200 B or less.

### 1.3 Cause 3: relay downlink sender [H, but with code clues]

- The download speed comes from the relay's QUIC sender. The relay is closed source.
- `net.rs:117` [V]: `// TODO: Remove once relays does MTU discovery https://linear.app/soveng/issue/OBS-3201/...`. So the relays do not run MTU discovery today.
- In quinn, if MTU discovery is disabled, the black-hole detector still runs. One detection sets `current_mtu = min_mtu` permanently, because no probe raises it again (`mtud.rs:47-56`, `155-166`; `paths.rs:91`) [V]. If the relay is quinn with MTUD off, `initial_mtu` 1350 and the default `min_mtu` 1200 [H], one false black hole fragments every full-size download packet until the next reconnect.
- If the relay uses a loss-based outer CC (quinn default is Cubic, `transport.rs:400` [V]) with a 1 MiB datagram queue: at 1% random loss, measured 3.3-4.2 of 40 Mbit/s and 2.4-3.0 s p99 delay, against 39.6 Mbit/s with BBR or no CC [M]. Plain WireGuard has no outer CC.
- Decisive checks (section 5): (a) `wg_rx_fragment_reassembled_count` > 0 during a download on a normal network = relay-side fragmentation. (b) Lossy-link A/B against Speed mode on the same relay and exit.

### 1.4 Cause 4: reconnect instead of roaming [V mechanism, H frequency]

- `tunnel_state.rs:163-171`: `Connected` matches only if `Some(network_interface) == target_network_interface`. A mismatch drops the tunnel (`:203-229`).
- Reconnect = 1 s debounce (`:177`) + relay race (up to `min(3 x first reply, 5 s)`, `client_state.rs:556-592`) + new cached API client (`client_state.rs:353-356`) + `CreateTunnel` + QUIC, token and WG handshake + first pong.
- After every connect, `set_os_network_config` runs again (`tunnel_state.rs:324`). On Android this calls `Builder().establish()` again (`ObscuraVpnService.kt:391-425`).
- A new tunnel may get new inner IPs or a new exit [H]. If so, every app TCP connection resets. WireGuard roams without any of this.
- The shell triggers this on every `onAvailable`/`onLost` (`ObscuraVpnService.kt:204-220`, `457-466`).
- Liveness [V]: the tunnel is dead after more than 4 probes are lost (`liveness.rs:10-23,108-125`). A probe is lost after `clamp(2 x slowest pong RTT, 1 s, 30 s)` (`:61-66`). The probes share the datagram queue with bulk data. On a slow uplink a full 1 MiB queue holds 1.7 s at 5 Mbit/s and 4.2 s at 2 Mbit/s. So a long upload (for example a photo backup on LTE) can trigger "dead" and a full reconnect [H].
- Upstream fought this: c44edac (2026-05-08) "mitigates the liveness checker timer issue on Android", ref OBS-3465 "android-v1159-unexpectedly-reconnects-halting-segmentation-offload" [V].

### 1.5 Cause 5: opt-in flags [V]

On Android, all three appear under Settings > Experimental (`obscura-ui/src/views/Settings.tsx:215-225`). All three default to off (`client_state.rs:539-541`).
- "Enable packet padding" (`quicFramePadding`): quinn pads every Data-space packet to the MTU, including ACK-only packets (`connection/mod.rs:561`, `:929-931`). An inner TCP ACK becomes about 1378 B on the wire instead of about 150 B. A download then needs uplink of about 50% of the download rate (one ACK per 2 segments). Measured: the ACK stream of a 100 Mbit/s download costs 53.8 Mbit/s of uplink instead of 5.5 [M; arithmetic 4880/s x 1378 B x 8 = 53.8 Mbit/s]. On LTE the download speed caps at about 2x the uplink speed. The UI text says only "may slightly increase bandwidth usage" (`en.json:167`). Only the uplink is padded. The relay decides about the downlink.
- "Use TCP/TLS tunnel": TCP-over-TCP. Each received relay op queues an OK reply (`quicwg.rs:966-970`). Each message is 3 `write_all` + `flush` with TCP_NODELAY (`quicwg.rs:997-1005`, `:599`). The queue tail-drops above 1000 packets (`:932-937`).
- "Small MTU compatibility": the endpoint `max_udp_payload_size(1200)` (`net.rs:105-126`). The relay then splits every full-size downlink packet.

### 1.6 Cause 6: Android GSO [V code, R field]

- quinn-udp 0.5.16 enables UDP GSO on Android for kernel 4.18 and later (`unix.rs:811-858`). On EIO or EINVAL it drops that transmit and halts GSO only after the failure (`unix.rs:345-360`).
- Field reports: EIO on cellular, vendor kernels with silently broken GSO, and ECN-cmsg EIO (the fix is only in quinn-udp 0.6.2+) [R quinn #2042, #2246, #2399, #2718].
- Each new endpoint (each reconnect) probes GSO again.

### 1.7 Rejected or corrected claims

| Claim (source) | Verdict | Evidence |
|---|---|---|
| "Nested CC with quinn's experimental BBR is the #1 cause; switch to Cubic" (perf-evidence 1 and 8.2, perf-udp-mode 6.3) | **Rejected** | BBR exits Startup only when not app-limited (`bbr/mod.rs:372-375`). quinn sets `app_limited = buf.is_empty() && !congestion_blocked` (`connection/mod.rs:947`). In Startup, `cwnd += bytes_acked` (`bbr/mod.rs:330`), and loss does not shrink the window in Startup (`:485-490`). My re-run: cwnd 8.6 MB to 605 MB (BDP 0.5 MB), so it never limits. Cubic as the outer CC adds 17-150 ms of queueing and collapses to 2.5 Mbit/s at 1% loss [M]. Keep `BbrConfig`. |
| "quinn-proto 0.11.18+ fixed the black-hole problem" (perf-evidence 2) | **Corrected** | It is still triggered in 0.11.19 (section 1.2). |
| "`initial_mtu(1350)` fixes fragmentation" (perf-datapath F7) | **Partly** | It removes start-up fragmentation only. With the black hole, 51% of packets are still fragmented [M]. |
| "Keep `min_mtu` at 1200" (perf-datapath F7) vs "`min_mtu(1350)`" (perf-bench) | **Resolved** | Use the 1350 floor with guards (B1). Upstream shipped the floor until v1.160. |
| "1 MiB datagram queue causes bufferbloat in normal use" (perf-evidence 1) | **Corrected** | The queue fills only when the driver or the uplink cannot keep up: CPU-bound phone, slow uplink with kernel back-pressure, or after an MTU drop. With app-limited traffic it stays empty [M + V code]. |
| ACK frequency "cuts ACK overhead" (perf-bench 4) | **Narrowed** | It is a client request to the relay. It cuts the relay's ACKs for our uploads. It does not cut our ACKs for downloads (the relay controls those). quinn always advertises `min_ack_delay` (`transport_parameters.rs:174`), so a quinn relay supports it and the ClientHello does not change. |
| `datagram_receive_buffer_size` increase (perf-datapath F6, perf-bench 6) | **Dropped** | No measured benefit. The default 1.25 MB already holds about 950 full packets. |

---

## 2. Concrete changes

Order of work: A (diagnostics) -> B1-B3, B6, C4 (small and safe) -> D1 (handover) -> F (Speed mode, section 3) -> C1-C3 -> E.

### A. Diagnostics first (no behaviour change)

**A1. Check the original app (no code).**
- Note the app version (Settings > About) and the three Experimental flags.
- Export the debug bundle. In `rust-log.ndjson`, grep:
  - message_id `WKqFjXMA` (1 Hz, `quicwg.rs:450-456`): read `max_udp_payload`, `max_wg_message`, `tick_stats.wg_tx_fragmented_count`, `wg_rx_fragment_reassembled_count`.
  - `2sonYhc2` / "liveness checker poll returning Dead".
  - `tls1cZot` "tunnel failed".
  - "halting segmentation offload".
  - `TF51QUHb` / `kq0AuTsT` (small-payload mode).
- Fragment counts > 0 during an upload confirm cause 2. Counts > 0 in `rx` during a download, with `max_udp_payload` 1350, point to cause 3.

**A2. 1 Hz debug stats in our build.**
- Where: `quicwg.rs:450-456`.
- Change: add `conn.stats()` `path.{rtt,cwnd,current_mtu,lost_packets,congestion_events,black_holes_detected,sent_plpmtud_probes,lost_plpmtud_probes}`, `udp_tx/udp_rx.{datagrams,ios}`, `frame_rx.acks`, and `conn.datagram_send_buffer_space()` (all exist: `quinn-proto connection/stats.rs:136-175`, `quinn connection.rs:508`).
- Debug builds only, or a user-enabled "diagnostics" toggle. Write to logcat only, never to persisted storage, no addresses.
- Privacy: release builds must not persist per-second traffic traces (see C5).

### B. Stealth (QUIC) mode tuning (rustlib patches)

**B1. MTU floor 1350 with guards (main fix for cause 2).**
- Where: `quicwg.rs:728-748` `quic_config()`. Pass `floor: bool` from `relay_selection.rs:75-86` (which already has `mtu` and `force_small_mtu`).
- Change:
  ```rust
  let mut m = MtuDiscoveryConfig::default();
  m.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);
  if floor {                                   // interface MTU >= 1378, CLAT-aware (see section 4), !force_small_mtu
      transport_config.initial_mtu(DEFAULT_UDP_PAYLOAD_SIZE);
      transport_config.min_mtu(DEFAULT_UDP_PAYLOAD_SIZE); // detector can no longer drop below 1350 (mtud.rs:465)
  } else {
      if mtu_allows_1350 { transport_config.initial_mtu(DEFAULT_UDP_PAYLOAD_SIZE); } // no start-up fragmentation; Initial stays 1200 B
      m.black_hole_cooldown(Duration::from_secs(2));  // was 10 s
      m.interval(Duration::from_secs(60));            // was 600 s: recover from a failed search
  }
  transport_config.mtu_discovery_config(Some(m));
  ```
- Guard 1, handshake fallback: start the relay race with `floor = true`. If no relay answers within 2 s, start a second race with `floor = false` on the same endpoint (in `client_state.rs:536-552`). A path that cannot carry 1350 B fails at the Initial packet (padded to `min_mtu`, `connection/mod.rs:478,646`). It does not fail mid-flow.
- Guard 2, mid-flow safety: in floor mode, make every 10th busy liveness probe 1280 B (`LivenessChecker::new` takes the probe size; `liveness.rs:47,245`; today `LIVENESS_MTU = 100`, `quicwg.rs:68`). If 3 large probes in a row are lost while small ones return, reconnect with `floor = false`.
- Expected gain: upload fragmentation 68% -> 0% [V]. CPU per packet -40% vs the fragmented state (18.8 -> 11.4 us) [M]. Goodput under overload +49% [M]. Steady TCP goodput +2% [V]. Loss amplification halved. On a phone that is CPU-bound, expect most of the 40% to show as throughput [H].
- Risk:
  - Privacy: the QUIC Initial grows from 1200 to 1350 B. The handshake shape changes, but it is the same as upstream up to v1.160. No change to encryption or the relay/exit split.
  - Relay compatibility: proven, because upstream shipped this config for about 3 months (49295c6, 2026-02-22 -> c2fb8b8, 2026-05-21; releases up to v1.160).
  - Paths below 1378 B: +2 s connect through the fallback.
  - Battery: none (the large probe is 1.3 KB every 10 s while busy).
- Measure: `black_holes_detected == 0` and `wg_tx_fragmented_count == 0` during an iperf3 upload. Compare the mean of the first 10 s with the steady state (section 5).

**B2. ACK frequency request.**
- Where: `quic_config()`.
- Change:
  ```rust
  let mut af = quinn::AckFrequencyConfig::default();
  af.ack_eliciting_threshold(quinn::VarInt::from_u32(10));
  af.max_ack_delay(Some(Duration::from_millis(5)));
  transport_config.ack_frequency_config(Some(af));
  ```
- Gain: client CPU per packet -18% on uploads [V re-run: 11.87 -> 9.74 us]. Fewer downlink ACK packets (ACK/data 0.37 -> 0.085) [M]. Active only if the relay advertises `min_ack_delay` (`connection/mod.rs:3601-3603`).
- Risk: no ClientHello change, because quinn always sends `min_ack_delay`. Loss detection is up to 5 ms later. A non-quinn relay with a different draft could reject the frame [H]. Test against 5+ real relays and remove the setting on any connection error.
- Measure: `frame_rx.acks / udp_tx.datagrams` during an upload, and CPU-s/GB.

**B3. Smaller datagram send queue.**
- Where: `quic_config()`.
- Change: `transport_config.datagram_send_buffer_size(256 * 1024);` (default 1 MiB, `transport.rs:396`). Optionally 128 KiB.
- Gain: worst-case queue delay at 5 Mbit/s is 1.68 s -> 0.42 s, and at 20 Mbit/s 0.42 s -> 0.1 s. Smaller burst loss on an MTU drop. Probes are less delayed (cause 4). The 128 KiB variant was measured at -37 ms p99 when the queue fills [M].
- Risk: more head drops under overload, which only makes inner TCP react sooner. No wire change.
- Measure: ping p95 during an LTE upload.

**B4. Keep BBR as is, and guard it.**
- Keep `BbrConfig::default()` (`quicwg.rs:744`).
- Add a CI regression run of qwbench phases A/B/D on every quinn upgrade. quinn's BBRv3 work (PR #2481 [R]) can change this "never limits" behaviour.

**B5. Do not change `pad_to_mtu` defaults.** Keep it opt-in (see E1).

**B6. UDP socket buffers (QUIC socket and the Speed-mode socket).**
- Where: `net.rs:53-55` `new_udp()` only. Not in `new_socket()`: SO_RCVBUF on TCP disables receive autotuning and would hurt TCP/TLS mode.
- Change:
  ```rust
  let s = new_socket(network_interface, Type::DGRAM, Some(Protocol::UDP))?;
  let _ = s.set_recv_buffer_size(4 << 20);
  let _ = s.set_send_buffer_size(2 << 20);
  tracing::info!(rcv = ?s.recv_buffer_size(), snd = ?s.send_buffer_size(), "udp socket buffers");
  ```
  wireguard-go requests 7 MiB (`conn/controlfns_linux.go:55-56`) [V]. Without CAP_NET_ADMIN the kernel caps at `rmem_max`/`wmem_max`, so log the effective value.
- Gain [H]: fewer receive drops when the endpoint driver is late (little core, GC, thermal). These drops look like path loss to the relay CC and inner TCP.
- Risk: memory only.
- Measure: `Udp: RcvbufErrors` in `/proc/net/snmp` before and after a download (section 5).

**B7. GSO.**
- Keep GSO on (it is the main syscall saver).
- Watch the log for "halting segmentation offload" and send errors (message_id `8EkAaj9z`, `quicwg.rs:925-929`).
- If it repeats on a device and network type, set `transport_config.enable_segmentation_offload(false)` for that network type (`transport.rs:56`).
- Track quinn-udp 0.6.2+ (ECN EIO fix) for a later quinn update.

### C. Data path (CPU, both modes)

**C1. Batch TUN reads on Android.**
- Where: `android/tunnel.rs:28-52`.
- Change: reuse `os::packet_buffer::PacketBuffer` as Linux does. Loop `unistd::read` until EAGAIN or 100 packets, then call `tunnel.send(pb.take_iter())` once. Sketch in perf-datapath section 6.
- Gain [H]: one `wg_state` lock, one liveness check and one BOOTTIME read per batch instead of per packet. Expect 5-10% CPU per packet at high rates.
- Risk: none.

**C2. Do not hold `wg_state` across quinn calls.**
- Where: `quicwg.rs:383-421`.
- Change: read `max_wg_message_size()` once per batch, encapsulate and fragment into a local `SmallVec<[Bytes; 64]>` under the lock, drop the lock, then call `send_datagram` for each item.
- Gain [H]: RX decapsulation stops waiting for TX quinn locks, so there is less jitter. The the Mac bench saw no throughput change from caching alone [M]. Measure on a phone before you keep it.

**C3. Remove the per-packet heap copy.**
- Where: `quicwg.rs:412` and `:374`.
- Change: encapsulate directly into a `BytesMut::with_capacity(1312+)` from a small pool, then `freeze()`.
- Gain: about 4% CPU per packet [M].
- Risk: none.

**C4. Liveness: real traffic counts as proof of life.**
- Where: `liveness.rs:108-125`, called from the RX path `quicwg.rs:494-505`.
- Change: when a WG packet decapsulates successfully, mark the tunnel alive (reset the lost count). Return `Dead` only if probes are lost AND no packet arrived from the exit in the lost period.
- Gain: no false reconnect during long uploads on slow links (cause 4).
- Risk: none. A decrypted packet proves the exit is alive.

**C5. Logging.**
- Where: `quicwg.rs:450-456` (1 Hz INFO `tick_stats`) and `logging.rs` persistence.
- Change: set this line to DEBUG, and never persist it in release builds.
- Gain: fewer wakeups and flash writes (battery). Privacy: no local per-second traffic-volume trace.

**C6. Optional: a data-path runtime [H].**
- Today: `Runtime::new()` with workers = CPU count (`android/ffi.rs:48`).
- Try: a dedicated 2-worker runtime for the TUN, quinn and receive tasks. This reduces cross-core wakeups on phones with deep idle states. Keep it only if section 5 shows a gain.
- Done as P10 (2026-10-23): the one runtime has 1 worker, and the TUN writes have their own thread. On the phone 1 worker was fastest in every mode (`device-test.md`).

### D. Network handover (cause 4)

**D1. Rebind instead of reconnect (Stealth).**
- Where:
  - `tunnel_state.rs:163-171` (`matches_target`).
  - `QuicWgConn` stores a clone of the `quinn::Endpoint` (created in `relay_selection.rs:85-86`).
  - Kotlin `ObscuraVpnService.kt:457-466` passes `network.networkHandle`.
- Change:
  1. On a new physical network, Rust creates a new UDP socket (B6 buffers) and binds it to that network with NDK `android_setsocknetwork(handle, fd)` (API 23).
  2. Call `endpoint.rebind(sock)`. quinn then calls `local_address_changed()`: it moves to an unused relay-issued CID and sends a PING (`quinn connection.rs:1099-1102`, `quinn-proto connection/mod.rs:3106-3109`) [V].
  3. Call `conn.wake()` (`quicwg.rs:397`) to send a liveness probe at once, with the existing after-sleep strictness (`MAX_ALLOWED_LOST_PROBES_AFTER_SLEEP = 1`, `liveness.rs:11`).
  4. Keep the TUN fd. Do not call `establish()` again unless the network config really changed.
  5. Do a full reconnect only if (a) liveness is dead within about 3 s, or (b) the new interface needs a different floor or small-MTU decision.
- Gain [H]: the stall goes from about 2-5 s, plus possible app connection resets, to about 1 RTT. The inner IP and the exit stay the same.
- Risk:
  - Compatibility: the relay may refuse migration (`disable_active_migration`, RFC 9000 s9). The liveness fallback covers this. Test on real relays.
  - Privacy: the relay sees old and new client IPs on one connection. It can already link them through the account and the tunnel token. The CID changes, so observers on the two access networks cannot link by CID.
  - Battery: fewer handshakes, so it improves.
- Measure: the handover test in section 5.

**D2. Speed mode roaming.** Same steps: new bound socket, then send a WG keepalive at once so that the relay learns the new source (`Tunn::encapsulate(&[])` gives a keepalive). If there is no reply in 3 s, create a new tunnel.

### E. Opt-in features and UI

**E1. Padding: keep it, make it cheaper and honest.**
- Where: the `quicwg.rs` send path when `quic_frame_padding` is on.
- Change: hold WG messages under 600 B for at most 2 ms, or until about 1250 B are pending, then submit them together. quinn packs queued datagrams into one packet while they fit (`datagrams.rs:200-215`).
- Gain: the inner ACK stream of a 100 Mbit/s download goes from 53.8 to 5.4 Mbit/s of uplink, and every packet is still 1350 B [M].
- Risk: up to 2 ms more delay on small packets, only with padding on. Size uniformity stays. Timing becomes coarser, which helps privacy.
- UI text: "Uses upload bandwidth of up to about half of your download speed. Pads only the upload direction."

**E2. TCP/TLS: batch writes.**
- Where: `quicwg.rs:842-891,997-1005`.
- Change: build header + code + payload in one buffer, write all queued packets and OKs, then flush once.
- The plaintext byte stream does not change. Only the TLS record and TCP segment boundaries change.
- Show this mode only as a fallback for networks that block UDP.

**E3. Small-MTU flag: replace with detection.** See the CLAT-aware MTU item in section 4. Keep the flag as a manual override.

### Expected gains (summary)

| Change | Throughput | Latency / stalls | CPU / battery | Confidence |
|---|---|---|---|---|
| F Speed mode | QUIC path -> WG/UDP: 2.7x desktop loopback ceiling [V], WG parity expected | No outer queue or ACKs | -75% client CPU per packet [V desktop] | High (mechanism), relay port reachability unknown |
| B1 MTU floor | Uploads: 0% fragmentation, +2% to +49% | No burst drops at MTU fall | -40% vs fragmented | High |
| B2 ACK freq | +0-5% | none | -18% on uploads | Medium (needs relay support) |
| B3 queue 256 KiB | none | -75% worst-case queue delay | none | Medium |
| B6 socket buffers | fewer loss events [H] | fewer spikes [H] | none | Low-medium |
| C1-C3 | +5-15% at the CPU ceiling [H] | less jitter [H] | -5-15% [H] | Medium |
| C4 liveness | none | removes false reconnects | fewer handshakes | High |
| D1 rebind | no restart after handover | stall about 2-5 s -> about 1 RTT [H] | fewer handshakes | Medium (relay support unknown) |
| E1 coalesce | download on LTE with padding: up to ~2x | +<=2 ms on small packets | fewer radio packets | High (mechanism) |

---

## 3. Transport modes: recommendation and implementation route

### 3.1 Recommendation

- **Speed (WireGuard UDP through an Obscura relay)**: a good default on normal (uncensored) networks, after the section 5 A/B confirms it. It is the only way to reach WireGuard parity. QUIC keeps a 2.5-3x CPU cost per packet even after B and C.
- **Stealth (QUIC, tuned per section 2)**: for networks that block or detect WireGuard, and for users who want the network to see QUIC/h3 instead of WireGuard.
- **Stealth-TCP (TCP/TLS)**: a fallback only, for networks that block UDP.
- Fallback rules:
  - Speed may fall back to Stealth if no WG handshake completes in about 5 s.
  - Stealth must never fall back to Speed automatically, because that would show WireGuard to the network.
- No automatic per-Wi-Fi rules: reading the SSID needs location permission, and that is a privacy cost. Give a manual toggle with a clear explanation.

Privacy (Speed vs Stealth). Rows marked [H] depend on the relay design; the API shape and Obscura's statements support them.

| Observer | Stealth | Speed |
|---|---|---|
| Local network / ISP | QUIC to an Obscura relay IP, ALPN h3, SNI `example.com` (`quicwg.rs:730`, `constants.rs:12`) | WireGuard (easy to fingerprint) to an Obscura relay IP |
| Relay | client IP, timing, volume, tunnel token | client IP, timing, volume, port [H] |
| Exit (Mullvad) | relay IP, plaintext destinations | same [H] |
| Packet sizes | WG 16 B padding; optional QUIC padding (uplink only) | WG 16 B padding; no padding option |

On an uncensored network the privacy loss is "the network sees WireGuard instead of QUIC". Relay IPs are listable by any account, so VPN use is visible in both modes. The two-party split (relay knows who, exit knows what) does not change [H strong].

### 3.2 Speed mode, Phase 1: neptun inside rustlib (smallest change)

API facts [V]:
- `obscuravpn-api src/cmd/tunnel/create.rs:9-24`: `CreateTunnel::UdpPort { id, label, wg_pubkey, relay: Option<String>, exit: Option<String> }`, `POST tunnel`, `AuthRequired`.
- Response `TunnelConfig::UdpPort { client: WgClientConfig { wg_pubkey, addresses }, server: WgServerConfig { wg_pubkey, endpoints, dnses } }` (`src/types.rs:403-419`).
- The private key stays on the device (`examples/api_cli.rs:139-167`).
- The README says "Warning: This is not an officially supported service." (`README.md:34`).

Steps:
1. `quicwg.rs`:
   - Add `WgSender::Udp { sock: Arc<tokio::net::UdpSocket> }` and `WgReceiver::Udp(..)` beside `:899` and `:943`.
   - Add `TransportKind::WireguardUdp` (`:206`).
   - `max_wg_message_size()` returns `None`, so there is no fragmentation.
   - Send with `try_send`. Drop on `WouldBlock`, as WireGuard does.
   - Add `QuicWgConn::connect_udp(..)`, which reuses `first_wg_handshake` (`:329`), `wait_for_first_pong` (`:276`), liveness and timers.
2. Socket:
   - `net::new_udp` (`net.rs:53`) plus B6 buffers, `connect(endpoint)`, bound to the current Network (D1).
   - Phase 1b [H gain]: replace `try_send` and `recv` with `quinn_udp::UdpSocketState` GSO/GRO batches (it is already a dependency). This is the same technique as wireguard-go's sendmmsg/GSO (`conn/bind_std.go`).
3. `client_state.rs`:
   - Add `new_tunnel_udp`, a copy of `new_tunnel` (`:419-510`).
   - Keep `select_relay` for the RTT race, then `abandon()` the QUIC handshake.
   - Send `CreateTunnel::UdpPort { id: Some(uuid), label: None, wg_pubkey, relay: Some(id), exit: Some(id) }` instead of `Obfuscated` (`:474-480`).
   - Match `TunnelConfig::UdpPort` (instead of `:506`).
   - Use the same key cache.
4. `network_config.rs`: build the OS config from `addresses` and `dnses`. The ping target is `10.64.0.1` (`types.rs:421-423`).
5. Lifecycle:
   - Write the UUID locally before the create call. The client-chosen `id` makes retries idempotent.
   - On disconnect: stop the data path, then `DELETE tunnel {id}`.
   - On start or on `TunnelLimitExceeded`: delete only our own stale IDs. Never touch portal configs.
   - Keep `label` null, so the server sees no client marker.
6. FFI: `TunnelArgs { exit, mode: TunnelMode }` (today `manager.rs:139-141`), with serde default `quic` so that old configs keep working.
7. MTU:
   - Start with 1280: this is what the user measured with the WG app.
   - After connect, send in-tunnel ICMP echoes to `10.64.0.1` at total sizes 1380 and 1420.
   - If they pass on this network type, offer 1380 (the next `establish()` applies it). MSS 1340 vs 1240 means 7% fewer packets.
   - Never set more than 1420.
8. Keepalive: none by default (battery). Optional 25 s on cellular if inbound push after idle fails (carrier NAT timeout) [H].

Before shipping, test one live tunnel (about 10 min):
- the endpoint IP equals the relay IP;
- the port is unique per tunnel;
- the public IP is the Mullvad exit;
- the status changes in `list-tunnels`;
- the tunnel survives Wi-Fi <-> LTE (relay roaming);
- the port is reachable on a restrictive network.

Expected gain: all QUIC costs go away (section 1.1: 11.9 -> 2.9 us client CPU per packet on the Mac [V]). There is no fragmentation, no outer queue, and no QUIC ACK uplink. Throughput should match the official WG app within noise. The remaining single-lock limit of neptun is about 3.5 Gbit/s on the Mac [V], far above phone links.

### 3.3 Speed and Stealth, Phase 2: GotaTun engine

GotaTun 0.10.2 (Mullvad, MPL-2.0 [V `LICENSE`], compatible with GPL-3.0) has separate tokio tasks for outgoing, incoming and timers, and a pluggable `UdpTransportFactory`. Replace the neptun `Tunn` + `Mutex<WgState>` pipeline in both modes: Stealth gets a QUIC-datagram transport, and Speed gets the stock UDP factory. This removes the single lock (cause 1, TX/RX coupling). Do this only after Phase 1 and the A/B; it is a larger change. Do not use wireguard-go/GoBackend: it needs a second runtime and its own VpnService, and Mullvad reports a crash history [R].

---

## 4. Android-side checklist

| Item | Upstream state | Action for our client |
|---|---|---|
| TUN MTU | `setMtu(networkConfig.mtu)` = 1280 (`ObscuraVpnService.kt:400`, `client_state.rs:396`) [V] | Stealth: 1280 (fixed by the 1350 relay budget, IPv6 needs >= 1280). Speed: 1280 default, 1380 after the in-tunnel probe. Never above the path budget. |
| `setBlocking` | not called; the fd is non-blocking by default (AOSP VpnService doc) | Keep it non-blocking. tokio `AsyncFd` needs EAGAIN (`android/tunnel.rs:43`). |
| `setUnderlyingNetworks` | called on every best-network change (`:459`) [V] | Keep. Also call it after D1 rebind. |
| Outer socket binding | not bound on Android (`net.rs:45-48` ignores the interface) [V] | Bind with `android_setsocknetwork` to the tracked network (D1). This makes handover explicit and lets quinn rotate the CID. |
| `setMetered(false)` | set (`:405`) [V] | Keep. Otherwise Android treats the VPN as metered and throttles sync and Play. |
| Network tracking | `registerBestMatchingNetworkCallback(INTERNET + NOT_VPN)` with `onAvailable`/`onLost` only (`:198-220`) [V] | Add `onLinkPropertiesChanged` (MTU and CLAT changes). Send the network handle and the CLAT-aware MTU to Rust. |
| CLAT-aware MTU | reads only the base interface MTU (`client_state.rs:321-331`, `net.rs:73-95`) [V] | If `java.net.NetworkInterface.getByName("v4-" + iface)` exists, use `min(base, v4)`. Relays are IPv4-only, so IPv6-only carriers go through CLAT [H]. |
| Re-establish | `establish()` on every connect (`tunnel_state.rs:324` -> `applyNetworkConfig`) [V] | Keep the fd when the config is unchanged (D1). |
| UDP socket buffers | default [V] | 4 MiB receive, 2 MiB send on UDP only (B6). Log the effective values. |
| Foreground service | `systemExempted` + `android:process=":vpnservice"` (`AndroidManifest.xml:65-66`) [V] | Keep. Start it only after consent (android-platform.md finding 3). |
| Doze / battery optimisation | VPN bound with `BIND_FOREGROUND_SERVICE` keeps network in Doze (android-platform.md finding 4) | Do not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Detect OEM "Restricted" battery mode (Samsung, Xiaomi) and show a one-time hint to set "Unrestricted". |
| Thread priority [H] | default nice 0 | Optional A/B: `setpriority(PRIO_PROCESS, 0, -4)` (THREAD_PRIORITY_DISPLAY level) in tokio `on_thread_start` for the data-path runtime only. Keep it only if loaded latency improves without a battery cost. Never use audio-level priorities. |
| Wakeups | 1 Hz WG timer tick plus 1 Hz INFO log while busy, 55 s idle ping (`quicwg.rs:60`, `liveness.rs:13`) [V] | C5 (log to DEBUG). Keep the idle ping in Stealth (QUIC idle 600 s). Speed: no keepalive by default. |
| MTE | `android:memtagMode="async"` (`AndroidManifest.xml:20`) [V] | Keep (security). The cost is small, and C3 removes per-packet allocations. |
| Handover | full reconnect [V] | D1/D2: rebind, wake probe, fall back to reconnect. |

---

## 5. Benchmark and verification protocol (on the phone)

Setup:
- Phone: Termux (F-Droid) with `pkg install iperf3 jq`. Laptop: `adb` (USB debugging on).
- Server: your own VPS with `iperf3 -s -p 5201`. Open the port only for the test. The server sees only the exit IP.
- Choose one exit city that Obscura and Mullvad share. Use the same relay and exit for all Obscura variants.
- Configurations:
  - W1: official WireGuard app, Mullvad config to that exit, MTU 1280 (and a variant with MTU 1380).
  - W2: official WireGuard app, Obscura portal config, same relay and exit, no MTU line (1280). This is the reference.
  - O1: original Obscura app, Experimental flags off. Record the version.
  - O1f: O1 with the flags the user normally uses.
  - NS: our client, Stealth. NF: our client, Speed.
- Conditions: same place, battery saver off, screen on, not charging, other VPNs off. Write down the Wi-Fi band and RSSI, or the LTE/5G RSRP.
- Run the test once on Wi-Fi and once on cellular.

Runs (5 rounds; in each round, all configs in random order):
```
SRV=<vps-ip>; M=<config>; N=<round>
iperf3 -c $SRV -p 5201 -R -t 30 -i 1 -J > dl_${M}_${N}.json          # download, 1 stream
iperf3 -c $SRV -p 5201    -t 30 -i 1 -J > ul_${M}_${N}.json          # upload, 1 stream
iperf3 -c $SRV -p 5201 -R -P 4 -t 30 -J > dl4_${M}_${N}.json         # download, 4 streams
ping -i 0.2 -c 25 $SRV > idle_${M}_${N}.txt                           # idle RTT
( ping -i 0.2 -c 140 $SRV > lul_${M}_${N}.txt & ) ; iperf3 -c $SRV -t 30 > /dev/null   # RTT under upload
iperf3 -c $SRV -u -b 50M -l 1200 -R -t 20 -J > udp_${M}_${N}.json    # loss and jitter at a fixed rate, fits the 1280 MTU
```
Extract:
```
jq '[.intervals[:10][].sum.bits_per_second]|add/length/1e6' ul_X_1.json   # first 10 s (black-hole window)
jq '[.intervals[10:][].sum.bits_per_second]|add/length/1e6' ul_X_1.json   # steady state
jq '.end.sum.lost_percent, .end.sum.jitter_ms' udp_X_1.json
```

Handover (each config, 3 times):
- Run `iperf3 -c $SRV -R -t 60 -i 1`. At 20 s turn Wi-Fi off. At 40 s turn it back on.
- Record (a) the seconds below 10% of the median, (b) whether iperf3 ends with a connection error (a reset means the inner IP or exit changed), and (c) the public IP before and after (`curl -s https://am.i.mullvad.net/ip`).

CPU and battery proxy (each config, 2 times):
```
PID=$(adb shell pidof <package or package:process>)
adb shell cat /proc/$PID/stat | awk '{print $14+$15}'     # CPU ticks (1/100 s) before
# phone: iperf3 -c $SRV -R -n 1G
adb shell cat /proc/$PID/stat | awk '{print $14+$15}'     # after; (after-before)/100 = CPU-s per GB
```
Use `com.wireguard.android` for W1 and W2 (the tunnel runs in the app process). For Obscura, use its VPN process (upstream: `:vpnservice`).

Kernel UDP drops, if readable from `adb shell`:
- Run `adb shell cat /proc/net/snmp | grep -A1 '^Udp:'` before and after each download.
- A rise in `RcvbufErrors` means the socket buffer is too small (B6).

Root-cause checks:
- O1 debug bundle (A1): fragment counters during `ul_*` (cause 2) and during `dl_*` (cause 3), and "Dead" and "halting segmentation offload" lines (causes 4, 6).
- NS debug stats (A2): `black_holes_detected`, `current_mtu`, fragment counters, `frame_rx.acks`.
- Relay downlink CC (cause 3), optional, needs Linux: run upstream's Linux `obscura` service and `wg-quick` with the W2 config on a Linux VM or VPS. Add ingress loss with `tc` + IFB + `netem loss 0.5% / 1% / 2%`, and compare single-stream downloads. If QUIC falls far below WireGuard (for example, under 30% of it at 1%), the relay uses a loss-based CC. Report that to Obscura.

Pass criteria (median of 5; a difference counts only if the medians differ by more than the larger IQR):
- NF: download and upload >= 95% of W2. Loaded-RTT p95 <= W2 + 10 ms. CPU-s/GB <= 1.1 x W2. Handover without reset.
- NS: fragment counters 0 on normal networks. Upload first-10 s >= 90% of steady state. Throughput >= O1 in every test. Handover stall <= 2 s without reset (if the relay supports migration). CPU-s/GB at least 30% below O1 on uploads.
- If NS stays far below W2 on downloads after all client fixes, the remaining gap is relay-side (cause 3). Use the A/B data as evidence for Obscura.

---

## 6. Open items that need the real relay or a device

- Relay QUIC stack: MTU discovery (`net.rs:117` says no), `min_mtu`, CC, datagram queue, ACK-frequency support, `disable_active_migration`, CID length (0 bytes of slack at 8 B).
- `UdpPort` tunnels: endpoint port policy, roaming, IPv6 endpoints, whether the inner IP is stable across tunnels, and churn limits for create and delete on every connect.
- Phone CPU ceilings: cross-compile `qwbench` for aarch64-linux-android and run it on 2-3 phone classes (big and little cores) to replace the [H] extrapolations.
- Report upstream to quinn: the `on_non_probe_acked` early return (`mtud.rs:406`) keeps false positives when data packets are slightly smaller than the last probe. Repro: `/tmp/obscura-research/bench` phase A.
- Report to Obscura: the B1 data, the relay `min_mtu` hypothesis, and the padding UI text.

Files: this plan `/tmp/obscura-research/notes/perf-plan.md`; my re-run data `/tmp/obscura-research/bench/results/plan_verify.jsonl`; bench `/tmp/obscura-research/bench` (qwbench, `scripts/run_matrix.zsh`).
