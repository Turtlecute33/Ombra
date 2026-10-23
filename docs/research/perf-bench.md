# perf-bench: CPU and protocol limits of the Obscura WG-in-QUIC data path

Machine: an Apple Silicon Mac, macOS. Rust 1.89.0 (rustlib toolchain), release profile identical to rustlib (`lto=true, codegen-units=1, panic=abort`). Crate versions pinned from `rustlib/Cargo.lock`: neptun `ff3b5555` (boringtun fork), quinn 0.11.12, quinn-proto 0.11.19, quinn-udp 0.5.16, ring 0.17.14, rustls 0.23.28, tokio 1.45.1. Total bench time about 16 min.

## 1. Key findings (ranked by likely impact on "slow compared to WireGuard")

1. **False MTU black-hole detection drops the QUIC MTU to 1200 for 10 s after the first congestion loss. Then every full-size tunnel packet is split into 2 QUIC datagrams.** (VERIFIED in bench, mechanism partly hypothesis.) The test path was 100 Mbit/s, 40 ms RTT, 50 ms drop-tail queue, and no MTU limit. On it, quinn declared a black hole at about 1 s in every QUIC variant (BBR, Cubic, no-CC, `initial_mtu=1350`). `current_mtu` stayed at 1200 until about 11 s (the `black_hole_cooldown(10 s)` value from rustlib, `quicwg.rs:741`). In the 20 s runs, 51% of packets were fragmented and there were 1.58 UDP packets for each inner packet. A typical speed test lasts 10 to 15 s, so it runs almost fully in this degraded state.
   - At the CPU limit (loopback), fragmentation cuts throughput by 40% (1345 to 805 Mbit/s) and raises CPU per inner packet by 65% (client 11.4 to 18.8 us, server 6.0 to 10.3 us).
   - With constant overload (150 Mbit/s offered into 100 Mbit/s), goodput fell from 97.6 Mbit/s (WG/UDP) to **63.9 Mbit/s** (rustlib config, 95% of packets fragmented, 3 black holes). With `initial_mtu=min_mtu=1350`, goodput was **95.1 Mbit/s** with 0 fragmentation.
   - `min_mtu(1350)` removed the problem fully. `black_hole_cooldown(2 s)` reduced fragmentation from 51% to 11%.
2. **The QUIC data path costs 2 to 4 times the CPU per packet of WG-over-UDP, and it is serialized on one quinn connection driver.** (VERIFIED.) On loopback at the CPU limit (1280 B inner packets):
   - Rustlib path: 1.35 Gbit/s (131 kpps). Client 11.4 us/pkt, server 6.0 us/pkt.
   - The same neptun WG over plain UDP: 3.64 Gbit/s (355 kpps). Client 2.8 us/pkt, server 3.9 us/pkt.
   - wireguard-go on the same Mac: 1.83 Gbit/s at 1280 B and 2.05 Gbit/s at 1420 B. It parallelizes across goroutines.
   - WireGuard crypto is not the bottleneck: neptun encapsulate is 0.73 us/pkt (14 Gbit/s per core). The QUIC AEAD (AES-256-GCM) is 0.2 us/pkt. The cost is in quinn per-packet processing, syscalls, ACK handling and task handoffs.
   - On phone cores this ceiling can drop to a few hundred Mbit/s. That is an extrapolation, see section 5.
3. **The outer congestion controller matters, and the current BBR choice is acceptable.** (VERIFIED.) With inner Cubic-like TCP over the tunnel, quinn BBR (rustlib default) and a fixed-window "no CC" controller behaved the same as WG/UDP for goodput and latency. quinn BBR's cwnd grew to about 25 MB, so it never limits. Cubic or NewReno as the outer CC added 17 to 150 ms of queueing delay (p99 inner RTT 110 to 199 ms against 46 to 93 ms). With a greedy source and 1% random loss, Cubic and NewReno collapsed to 2.5 Mbit/s with 3 to 4 s latency, because the 1 MiB datagram send buffer fills. Do not switch to Cubic.
4. **QUIC ACK overhead: the receiver sends about 0.37 ACK packets per data packet.** The ACK-frequency extension (`ack_eliciting_threshold=10`, `max_ack_delay=5 ms`) reduced this to 0.085. It cut CPU per packet by 18% (client) and 17% (server) and raised loopback throughput by 3%. (VERIFIED in bench. On the real relay this needs relay support, which is unknown.)
5. **`pad_to_mtu` (feature flag `quicFramePadding`) multiplies the wire cost of small packets by 9.2.** Without coalescing, a 64 B packet was 146 B on the wire without padding and 1350 B with padding. A download sends many small TCP ACKs uplink (about 4.5k/s at 100 Mbit/s). With padding these need about 49 Mbit/s of uplink instead of about 5 Mbit/s, which can cap downloads on cellular uplinks. The flag is off by default (`client_state.rs:540`). (VERIFIED wire size. The uplink-cap effect on downloads is computed, not measured.)
6. **Packet count:** inner MTU 1280 (`quicwg.rs:61`) against the usual WireGuard 1420 gives 11% more packets for the same TCP data (MSS 1240 against 1380). It also adds about 30 B of QUIC overhead per packet. This is a fixed cost, smaller than items 1 and 2.

## 2. Methodology

Bench crate: `/tmp/obscura-research/bench/qwbench` (single binary `qwbench`). Runner: `scripts/run_matrix.zsh`. Summary: `scripts/summ.py`. Raw data: `results/*.jsonl`. No device, no root, no change to the upstream repo.

- **rustlib has no loopback mode.** `rustlib/examples/connect.rs` needs the real API, an account and a relay. `src/bin/obscura` is the Linux/Windows service. There are no benches, so I wrote one.
- **(a) `qwbench wg`**: in-memory neptun handshake, then a single-threaded loop for 1 s per item. Items: `Tunn::encapsulate` into a reused buffer; `decapsulate` of pre-encrypted in-order batches (4096); the "obscura send path" (Mutex lock + encapsulate into a 64 KiB buffer + `Bytes::copy_from_slice` + fragmenter check, as in `quicwg.rs:403-421`); the "obscura recv path" (reassemble + decapsulate + copy, as in `quicwg.rs:468-507`). Raw ring AEAD seal at 1350 B is a reference point.
- **(b)/(c) `qwbench server|client`**: separate processes on 127.0.0.1, tokio multi-thread runtime (same as `android/ffi.rs:48`).
  - QUIC client: the TransportConfig is copied verbatim from `quicwg.rs:733-745`. Flags (`--cc --initial-mtu --min-mtu --bh-cooldown --dsb --ackfreq --pad`) select variants. Endpoint: `EndpointConfig::default`, `default_runtime`.
  - QUIC server: quinn default TransportConfig (Cubic). The real relay config is unknown, and it only governs the reverse direction in these tests.
  - WG handshake runs over DATAGRAM frames. The send path mirrors rustlib exactly: it takes the wg_state lock, encapsulates, copies into `Bytes`, calls `conn.max_datagram_size()` under the lock, and fragments with a verbatim copy of the obscuravpn-api `wg_fragment` split/merge logic (`src/frag.rs`). Then it calls `send_datagram` under the lock (drop-oldest). The receive path mirrors rustlib: `read_datagram`, reassemble, decapsulate into a 64 KiB buffer, copy.
  - UDP baseline: the same neptun crypto over a plain UDP socket, one `send` or `recv` per packet. This is WireGuard semantics: no outer CC, no extra copy.
  - Modes:
    - `sat`: greedy, uses `send_datagram_wait`, so it is CPU or CC bound.
    - `rate`: fixed offered load in 1 ms ticks with drop-oldest `send_datagram`, to measure bufferbloat and drops.
    - `tcp`: an ACK-clocked Cubic-like inner sender inside the tunnel. ACK every 2nd packet or on a gap, slow start, Cubic growth with Reno-friendly floor, beta 0.7, one reduction per RTT, RTO max(200 ms, 3 srtt), cwnd cap 4096 packets, no retransmissions. Goodput is counted at the receiver.
  - Metrics: receiver goodput and latency (shared CLOCK_MONOTONIC timestamps in the payload), process CPU (getrusage user+sys) per inner packet, and quinn stats (black holes, MTU timeline per 1 s, lost packets, cwnd, UDP datagram counts).
- **netem**: a userspace UDP proxy (`qwbench netem`), symmetric. Per-direction one-way delay, rate limit with a drop-tail queue in ms, Bernoulli loss. Socket buffers 7 MiB. Timer granularity 1 ms, which gives micro-bursts.
- **Profiling**: macOS `sample` on the client and server during a `sat` run (`results/sample_*.txt`).
- **wireguard-go**: copy of `golang.zx2c4.com/wireguard@v0.0.0-20261006164505-2631ce99a06f` with an added `BenchmarkThroughputSized` (`wggo/wireguard/device/obscura_bench_test.go`). It is a clone of upstream `BenchmarkThroughput` with a 1280/1420/64 B IPv4 packet: two devices over `conn.NewDefaultBind` on loopback with channel TUNs.

Limits of the method:
- macOS: quinn-udp has no GSO/GRO here (`fast-apple-datapath` is off), so there is 1 sendmsg per QUIC packet. Android/Linux uses GSO with up to 10 segments (`quinn connection.rs:1341`) and GRO.
- The Android tun read/write syscalls (`android/tunnel.rs:32,63`) are not in the loop.
- Only client to server data was tested. Downlink uses the relay CC and MTU, which are unknown.
- Most netem runs are single runs. The 1% loss phase (B) was repeated twice, and loopback repeats agree within 0.5% (L-obscura 1345.0 against K-obscura-again 1344.9).

## 3. Results

### 3.1 (a) neptun microbench, single thread, the Mac P-core

| bench | 64 B | 576 B | 1280 B | 1420 B |
|---|---|---|---|---|
| neptun encapsulate ns/pkt (Gbit/s) | 173 (3.0) | 354 (13.0) | 728 (14.1) | 758 (15.0) |
| neptun decapsulate ns/pkt | 170 | 421 | 719 | 888 |
| rustlib send wrapper (lock+copy+frag check) | 171 | 381 | 757 (+4%) | 839 (+11%, splits in 2 at 1312) |
| rustlib recv wrapper (reassemble+copy) | 182 | 461 | 753 (+5%) | 925 |

Reference at 1350 B: ring ChaCha20-Poly1305 seal 744 ns (14.5 Gbit/s), AES-128-GCM 199 ns (54 Gbit/s), AES-256-GCM 206 ns (53 Gbit/s). WG crypto is about 6% of the per-packet CPU of the full QUIC path. Allocations and copies in rustlib add only about 4%.

### 3.2 (b)+(c) Loopback, CPU-bound (`sat`, 5 s, 1280 B unless noted)

| variant | goodput Mbit/s | kpps | client us/pkt | server us/pkt | UDP pkts per inner pkt |
|---|---|---|---|---|---|
| rustlib config (BBR) | 1345 | 131 | 11.4 | 6.0 | 1.03 |
| quinn default (Cubic, MTU up to 1452) | 1382 | 135 | 11.3 | 5.9 | 1.00 |
| rustlib + fixed-window "no CC" | 1368 | 134 | 11.4 | 5.9 | 1.03 |
| rustlib + cached max_datagram_size | 1350 | 132 | 11.5 | 6.0 | 1.03 |
| rustlib + ACK frequency (thr 10, 5 ms) | 1386 | 135 | 9.4 | 4.9 | 1.01 (ACKs/pkt 0.367 to 0.085) |
| QUIC datagrams only, no WG (1312 B) | 1783 | 170 | 8.4 | 5.0 | 1.03 |
| **WG over UDP (neptun)** | **3637** | **355** | **2.8** | **3.9** | 1 |
| rustlib, MTU forced 1200 (all fragmented) | 805 | 79 | 18.8 | 10.3 | 2.0 |
| rustlib 64 B packets | 434 | 848 | 1.8 | 0.8 | 0.10 (quinn coalesces ~10 datagrams) |
| WG over UDP 64 B | 249 | 486 | 2.1 | 2.9 | 1 |
| wireguard-go 1280 B / 1420 B / 64 B | 1830 / 2053 / 96 | 179 / 181 / 188 | 26 us/pkt both peers combined (Go benchmark spin included) | | |

Per-packet breakdown on the QUIC sender (the Mac): the WG layer is about 0.76 us and the quinn/QUIC layer about 8.4 us (QUIC-only run). The remaining about 2.3 us come from the lock coupling between the app and the driver.

Profile of the client (`results/sample_client.txt`):
- The app task spent 1238 of 1430 samples (87%) blocked in `pthread_mutex` inside `quinn::Connection::max_datagram_size`, waiting for the `ConnectionDriver`.
- On-CPU top of stack: `__sendmsg` 964, `__recvmsg` 230, `chacha20_poly1305_seal` 160, AES-GCM about 38.
- The limit is the one quinn driver task: packet build, encryption and sendmsg, under the connection lock.
- Caching `max_datagram_size` removed contention samples but did not change throughput.

### 3.3 Emulated paths with inner Cubic-like TCP (`tcp` mode)

| scenario | variant | goodput | frag % | black holes | inner RTT p50/p99 ms | client us/pkt |
|---|---|---|---|---|---|---|
| A: 100 Mbit/s, RTT 40, q 50 ms, 0 loss, 20 s | rustlib (BBR) | 92.4 | 50.8 | 2 | 83.6 / 92.8 | 34.6 |
| | rustlib + initial_mtu 1350 | 92.5 | 51.0 | 1 | 87.3 / 92.4 | 50.0 |
| | rustlib + Cubic | 92.3 | 51.2 | 6 | **101.4 / 110.8** | 54.5 |
| | rustlib + no-CC | 92.6 | 50.8 | 1 | 87.3 / 92.2 | 54.2 |
| | rustlib + initial=min_mtu 1350 (G) | **94.2** | **0** | 0 | 84.5 / 93.0 | 69.8 |
| | rustlib + bh cooldown 2 s (G) | 93.7 | 11.3 | 3 | 85.5 / 92.7 | 58.8 |
| | WG/UDP | 96.6 | - | - | 85.1 / 92.4 | 24.6 |
| F: 600 Mbit/s, RTT 20, q 20 ms, 0 loss, 15 s | rustlib | 565 | 0 | 0 | 37.4 / 40.3 | 15.8 |
| | rustlib + no-CC | 553 | 67.3 | 4 | 36.9 / 43.1 | 20.5 |
| | rustlib + no-CC + min_mtu 1350 | 566 | 0 | 0 | 35.0 / 38.1 | 16.2 |
| | WG/UDP | 579 | - | - | 37.4 / 42.9 | 10.4 |
| B: 100 Mbit/s, RTT 40, 1% loss (2 reps) | all variants | 2.5 to 4.2 | ~0 | 0 | ~44 / 46 (Cubic: 131 to 199) | |
| C: 400 Mbit/s, RTT 20, 0.1% loss | all variants | 20 to 27 | 0 | 0 | ~24 / 26 to 44 (Cubic 63) | |

B and C are limited by the inner TCP loss response (Mathis limit about 3 Mbit/s at 1% and RTT 40 ms). They do not separate outer variants, except that outer Cubic adds a latency tail. The client us/pkt values at 9 kpps are dominated by wakeups and are not comparable to the loopback numbers.

### 3.4 Offered-load runs (`rate`, path A, 10 s): bufferbloat and drops

| variant | offered | goodput | latency p50/p99 ms | notes |
|---|---|---|---|---|
| rustlib (BBR) | 150 | **63.9** | 70.7 / 87.6 | 95% fragmented, 3 black holes, MTU 1200 for the whole run |
| rustlib + initial=min_mtu 1350 | 150 | **95.1** | 70.8 / 78.8 | 0 fragmentation |
| rustlib + Cubic | 150 | 93.4 | **107.5 / 122.9** | 53.5k datagrams dropped in quinn's 1 MiB send buffer |
| rustlib + Cubic + datagram_send_buffer 128 KiB | 150 | 93.5 | 70.6 / 75.4 | same drops, 37 ms less delay |
| WG/UDP | 150 | 97.6 | 70.7 / 72.0 | |
| rustlib (BBR) | 80 | 80.0 | 21.8 / 30.7 | |
| rustlib + Cubic | 80 | 79.1 | 21.8 / **115.2** | |
| WG/UDP | 80 | 80.0 | 21.6 / 22.8 | |
| greedy + 1% loss: BBR / Cubic / NewReno | sat | 66.3 / **2.5 / 2.6** | 110 / 2847 / 2888 (p50) | BBR: 5 black holes, 86% fragmented |

MTU timeline, path A, rustlib config (1 s samples): `0s:1312 1s:1200 ... 10s:1200 11s:1350 ... 20s:1350`. The same shape appeared for `initial_mtu=1350`, no-CC, and F/no-CC.

## 4. Verified code facts (upstream HEAD, file:line)

- QUIC transport config, `rustlib/src/quicwg.rs:733-745`:
  ```rust
  let mut transport_config = quinn::TransportConfig::default();
  transport_config.max_concurrent_uni_streams(0u8.into());
  transport_config.max_concurrent_bidi_streams(0u8.into());
  transport_config.stream_receive_window(CONTROL_STREAM_WINDOW.into());   // 4 KiB, streams only
  transport_config.receive_window(CONTROL_STREAM_WINDOW.into());
  transport_config.send_window(CONTROL_STREAM_WINDOW.into());
  let mut mtu_discovery_config = MtuDiscoveryConfig::default();
  mtu_discovery_config.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);              // 1350
  mtu_discovery_config.black_hole_cooldown(Duration::from_secs(10));
  transport_config.mtu_discovery_config(Some(mtu_discovery_config));
  transport_config.max_idle_timeout(Some(QUIC_IDLE_TIMEOUT.try_into()?));
  transport_config.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
  transport_config.pad_to_mtu(quic_frame_padding);
  ```
  These settings leave the defaults unchanged for: `initial_mtu` (1200), `min_mtu` (1200), datagram send buffer (1 MiB), datagram receive buffer (1.25 MB), and ACK frequency (none). Defaults are in quinn-proto `config/transport.rs:364-406`. The stream windows do not affect datagrams.
- Size budget: `TUNNEL_MTU = 1280` (`quicwg.rs:61`) and `DEFAULT_UDP_PAYLOAD_SIZE = 1350` (`:64`).
  - A WG data message is 1280 + 32 = 1312 B.
  - quinn `max_size()` = MTU - (1 + DCID + 4 + 16) - 9 (quinn-proto `connection/datagrams.rs:82-96`). With MTU 1350 and an 8-byte CID this is exactly 1312 (measured `max_dg_end: 1312`), so there are **0 bytes of slack**.
  - Any DCID longer than 8 B from the relay, any MTU below 1350, the time before MTUD finishes (measured `max_dg_at_start` 1274 at 40 ms RTT), or a black-hole fallback makes every full-size packet fragment.
- Per-packet send path, `quicwg.rs:403-421` (called with `wg_state` Mutex held, `:384`):
  ```rust
  match wg_state.wg.encapsulate(packet, &mut wg_state.buffer) {
      TunnResult::WriteToNetwork(wg_message) => {
          ...
          let wg_message = Bytes::copy_from_slice(wg_message);
          let (first, second) = match self.wg_sender.max_wg_message_size() {   // -> conn.max_datagram_size(): quinn lock (:917)
              Some(max_size) => wg_state.fragmenter.fragment(wg_message, max_size),
              None => (wg_message, None),
          };
          for msg in once(first).chain(second) { self.wg_sender.send_wg_message(msg); } // conn.send_datagram(): quinn lock again (:925)
  ```
  The fragmenter (obscuravpn-api `src/wg_fragment/split.rs`) halves the message and adds an 8 B header and 2 Vec allocations.
- quinn holds the connection lock for the whole driver poll, including UDP sends. `quinn-0.11.12/src/connection.rs:259`: `let conn = &mut *self.conn.state.lock("poll");` then `drive_transmit` (`:1016-1072`, up to `MAX_TRANSMIT_DATAGRAMS = 20` transmits per poll, `:1334`). `send_datagram` uses drop-oldest (`connection.rs:454`: `datagrams().send(data, true)`).
- Receive path: one task does everything serially. In `tunnel_state.rs:334-335` the loop is `conn.receive()` then `os_impl.packet_for_os(packet)`. `packet_for_os` takes a Mutex and does a blocking `unistd::write` per packet (`android/os_impl.rs:65-68`, `android/tunnel.rs:63`). Decapsulation is under the same `wg_state` Mutex as the send path (`quicwg.rs:470,489`).
- Android tun reader: one `read()` per packet into a 1280 B buffer, then `tunnel.send(once(..))` (`android/tunnel.rs:29-35`). There is no batching.
- UDP socket: no `SO_RCVBUF`/`SO_SNDBUF` tuning (`net.rs:28-50`). wireguard-go sets 7 MiB and tries `*BUFFORCE` (`conn/controlfns.go:18`, `controlfns_linux.go:55-59`).
- Small-MTU handling: if the interface MTU is below 1378 or `force_small_mtu` is set, `EndpointConfig::max_udp_payload_size(1200)` (`net.rs:105-127`) limits what the relay sends, which forces downlink fragmentation too.
- quinn BBR is labelled "Experimental! Use at your own risk." (`quinn-proto congestion/bbr/mod.rs:19`). The pacer is window-based (`connection/pacing.rs:7-14`), not BBR's pacing rate.
- Black-hole detector: more than `BLACK_HOLE_THRESHOLD = 3` "suspicious" loss bursts drop the MTU to `min_mtu` (`connection/mtud.rs:355-365, 528`). A burst is suspicious if its smallest packet is larger than the largest size acknowledged since then. quinn's own comment notes that packet size fluctuates with packet-number length (`datagrams.rs:83-85`).
- The client already logs useful data every 1 s: `tick_stats` with `wg_tx_fragmented_count` and `wg_rx_fragment_reassembled_count`, and `max_wg_message` (`quicwg.rs:451-456`, message_id `WKqFjXMA`). **The user's debug bundle from the original app can confirm or refute finding 1 on the real relay.**
- TCP/TLS transport is only used through the `tcpTlsTunnel` feature flag (`client_state.rs:539`, `relay_selection.rs:103-110`). There is no automatic fallback, so it is not a hidden cause unless the user enabled it.

## 5. Bottleneck analysis

Verified on the Mac:
- WG crypto (0.73 us/pkt) and rustlib allocations and copies (+4%) are minor.
- The QUIC sender is limited by the single quinn `ConnectionDriver`: packet build, AES-GCM, per-packet sendmsg on macOS, and ACK processing (0.37 ACK packets per data packet), all under one mutex that the app thread also needs.
- The receive side costs about half as much.
- At link speeds up to 600 Mbit/s on the Mac, goodput is within 2.5 to 4.5% of WG/UDP when the MTU stays at 1350. The large losses come from fragmentation after the false black-hole detection.

Hypotheses (no device available):
- **Phone CPU.** Single-core ratios against the Mac: flagship X4/X925 about 1.8 to 2.5 times slower, mid-range A78/A715 about 4 times, little cores about 8 to 10 times. This gives an approximate CPU ceiling for the rustlib path of 550 to 750, about 300, and 130 to 170 Mbit/s. In the MTU-1200 state it is 40% lower. WG/UDP crypto alone stays above 1 Gbit/s on mid-range cores. Android GSO reduces sendmsg cost compared with macOS. The per-packet tun read/write syscalls (2 per packet, not in this bench) add cost.
- **Socket buffers.** The default receive buffer on Android (`rmem_default`, often 208 KiB, about 155 packets) can overflow during bursts while the single receive task is blocked in the tun write. wireguard-go requests 7 MiB.
- **Downlink.** If the relay uses quinn with MTUD and a loss-based CC, the same black-hole and CC effects apply to downloads, and the client cannot fix them. The `max_udp_payload_size(1200)` path forces relay-side fragmentation.

## 6. Recommended client settings (maximum datagram throughput, no privacy loss)

```rust
let mut t = quinn::TransportConfig::default();
// control stream only (unchanged from rustlib)
t.max_concurrent_uni_streams(0u8.into()); t.max_concurrent_bidi_streams(0u8.into());
t.stream_receive_window(4096u32.into()); t.receive_window(4096u32.into()); t.send_window(4096);
// MTU: start at 1350 and keep a 1350 floor when the interface MTU allows it (>= 1378, no force_small_mtu)
let mut m = quinn::MtuDiscoveryConfig::default();
m.upper_bound(1350);
m.black_hole_cooldown(Duration::from_secs(2));  // measured: 51% -> 11% fragmented if min_mtu is not used
t.mtu_discovery_config(Some(m));
t.initial_mtu(1350);
t.min_mtu(1350);        // measured: 0% fragmentation, +49% goodput under overload; see risk below
// CC: keep BBR (or a fixed large window). Measured: Cubic/NewReno add 17-150 ms delay and collapse under loss.
t.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
// bound queueing delay when the CC does limit (1 MiB = 84 ms at 100 Mbit/s); measured -37 ms with Cubic
t.datagram_send_buffer_size(256 * 1024);
// absorb receive bursts while the single receive task writes to the tun (hypothesis)
t.datagram_receive_buffer_size(Some(2 * 1024 * 1024));
// fewer ACK packets: measured -17..18% CPU/pkt on both ends; ignored if the relay lacks the extension
let mut af = quinn::AckFrequencyConfig::default();
af.ack_eliciting_threshold(quinn::VarInt::from_u32(10));
af.max_ack_delay(Some(Duration::from_millis(5)));
t.ack_frequency_config(Some(af));
t.pad_to_mtu(false);    // padding only as an explicit opt-in privacy mode (9.2x wire cost on small packets)
t.enable_segmentation_offload(true); // default; GSO on Android
t.max_idle_timeout(Some(Duration::from_secs(600).try_into()?));
```

- `min_mtu(1350)` risk: if the path really cannot carry 1350 B UDP payloads, quinn's docs warn that the handshake can fail (`config/transport.rs:205-213`). Use it only when the interface MTU is at least 1378 and `force_small_mtu` is off. On handshake failure, or if a large liveness probe fails, retry with the current rustlib config. A softer alternative is a 1 to 2 s cooldown without `min_mtu`.
- UDP socket: before `quinn::Endpoint::new` (`net.rs:102-128`), set `SO_RCVBUF`/`SO_SNDBUF` to 4 to 7 MiB through socket2, as wireguard-go does.
- Data path, low priority (no measured throughput gain on the Mac): stop calling `conn.max_datagram_size()` per packet under the `wg_state` lock. Read it on the 1 s tick or every N packets. This reduces coupling between the uplink lock and the quinn lock.
- Privacy impact: none for the MTU, CC, buffer, ACK-frequency and socket-buffer changes. Encryption and the relay/exit split stay the same. Turning `pad_to_mtu` off removes a traffic-analysis mitigation, so keep it as an informed opt-in that shows its bandwidth cost.

## 7. Open checks (need a device or the real relay)

- On a real connection (or the user's original app logs, `WKqFjXMA` lines), check `max_wg_message`, `wg_tx_fragmented_count` and `wg_rx_fragment_reassembled_count` during a speed test. Non-zero counts with `max_wg_message` below 1312 confirm finding 1 on real paths and show whether the relay CID is longer than 8 B.
- Cross-compile `qwbench` for aarch64-linux-android and run it on 2 to 3 phone classes. Pin the client to big and little cores to measure the real CPU ceiling with GSO.
- Find out whether the relay is quinn-based (ACK frequency support, downlink CC, MTUD and CID length).

## 8. Reproduce, and what was installed

- Nothing was installed system-wide. Rust 1.89.0, Go 1.27.2 (`/opt/homebrew/bin/go`) and the OpenSSL CLI were already present.
- Cargo used the shared `~/.cargo` registry and git caches, which already held the pinned crates.
- Go modules (wireguard-go and x/crypto, x/net, x/sys) were downloaded into `/tmp/obscura-research/bench/gopath`, and that cache was then deleted. The source copy stays at `bench/wggo/wireguard` and the test binary at `bench/wggo/device.test`.
- A throw-away self-signed P-256 test cert for the local QUIC server is in `bench/certs/`.
- Files:
  - Bench source: `/tmp/obscura-research/bench/qwbench/src/{main,cfg,common,frag,inner,net,wgbench}.rs`
  - Runner: `/tmp/obscura-research/bench/scripts/run_matrix.zsh` (phases `L A B C D E F G K`)
  - Summary: `/tmp/obscura-research/bench/scripts/summ.py`
  - Raw results: `/tmp/obscura-research/bench/results/{matrix.jsonl,wg_micro.jsonl,blackhole.jsonl,wggo_bench.txt,sample_client.txt,sample_server.txt}`
  - quinn sources used for citations: `/tmp/obscura-research/bench/src-ref/{quinn-0.11.12,quinn-proto-0.11.19,quinn-udp-0.5.16}`
- Commands:
  - `qwbench wg --secs=1`
  - `scripts/run_matrix.zsh L A B C D E F G K`
  - `WGBENCH_SIZE=1280 bench/wggo/device.test -test.run XXX -test.bench ThroughputSized -test.benchtime 1000000x`
