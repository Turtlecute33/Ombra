# rust/obscura-core: speed of the Ombra patches against upstream

Measured on 2026-10-21 (task R5) with the loopback bench of `docs/research/perf-bench.md` section 8. No device and no
real relay: section 6 lists what these numbers do not show.

## Method

- Machine: an Apple Silicon Mac, macOS, Rust 1.89.0. Release profile as in `Cargo.toml` (`lto`, 1 codegen unit,
  `panic = "abort"`). quinn 0.11.12, quinn-proto 0.11.19, quinn-udp 0.5.16 (macOS: no GSO/GRO), neptun `ff3b5555`.
- Harness: `qwbench` in `/tmp/obscura-research/bench` (not in this repo). Client and server are separate processes on
  127.0.0.1 with the multi-thread tokio runtime. The server is a quinn server with the default transport config that
  does what the relay and the exit do (WireGuard receive path of rustlib, inner ACKs). `netem` is a user-space UDP
  proxy with delay, rate and a drop-tail queue (1 ms timer, so it makes micro-bursts).
- The harness cannot link this crate (the data path is private), so it mirrors the code of `feat/rust`:
  - **upstream**: the rustlib transport config (`quicwg.rs` at 85ef7e2) and its data path: one `wg_state` lock per
    packet, `max_datagram_size` under the lock, a copy per message, `send_datagram` under the lock.
  - **Ombra config**: `QuicWgConnHandshaking::quic_config` plus `QuicTuning::apply_to` with `MtuPolicy::Floor`:
    MTU 1350 fixed, black hole cooldown 2 s, MTU search every 60 s, 256 KiB datagram queue, ACK frequency request
    (threshold 3, 5 ms), BBR.
  - **Ombra data path**: one lock per TUN batch (up to 64 packets), `max_datagram_size` once per batch outside the
    lock, messages written into `BytesArena` (the real `bytes_arena.rs`, included by path) and sent after the
    unlock; with padding the logic of `datagram_coalescer.rs`.
  - **Speed mode** (`wireguardUdp`): plain WireGuard to the server on a connected UDP socket with 4 MiB / 2 MiB buffers,
    one lock per batch, arena, `try_send` per message, receive with `try_recv` into the arena. In the saturating
    `sat` runs a full send buffer waits for writability instead of a drop, so that the run measures the path.
  - **WG/UDP upstream-style**: the perf-bench baseline (one `send().await` and one lock per packet).
- Not in the loop: the TUN read and write syscalls, the liveness probes, the relay protocol handshake.
- Load: other programs ran on the machine (two emulators, builds of other worktrees). The tables use the final run
  (15:32 to 15:38), whose upstream numbers match perf-bench (1345 Mbit/s there, 1323 here). Each variant ran
  twice, interleaved; both values or the mean are given. Earlier runs under more load gave lower absolute numbers
  but the same order.
- Total bench time: about 16 minutes.

## 1. Loopback, CPU-bound (`sat`, 5 s, 1280-byte inner packets)

| Variant | Mbit/s (2 runs) | kpps | client us/pkt | server us/pkt | relay ACKs per packet |
|---|---|---|---|---|---|
| upstream config + upstream data path | 1311, 1335 | 129 | 11.6 | 6.0 | 0.36 |
| Ombra config + upstream data path | 1323, 1334 | 130 | 10.6 | 5.5 | 0.21 |
| **Ombra config + Ombra data path** | **1829, 1800** | **177** | **7.9** | **4.8** | 0.21 |
| WG/UDP, upstream-style loop (perf-bench baseline) | 3580, 3594 | 350 | 2.84 | 3.67 | - |
| **Speed mode** | **3797, 3772** | **370** | **2.69** | **3.11** | - |
| Speed mode, 1420-byte packets (`udpMtu` 1420) | 4148, 3452 | 334 | 2.90 | 3.48 | - |

64-byte packets (1 run each): upstream 368 Mbit/s (720 kpps, client 1.76 us/pkt), Ombra 571 Mbit/s (1116 kpps,
1.16 us/pkt), Speed mode 251 Mbit/s (489 kpps, 2.03 us/pkt; quinn puts many small datagrams into one packet, plain
UDP sends each alone).

Result: the Ombra QUIC path carries 37% more on loopback with 31% less client CPU per packet. The config alone (ACK
frequency) saves 8 to 9% client CPU; the data path (C1 to C3) gives the rest. Speed mode needs a third of the client
CPU per packet of the Ombra QUIC path and carries about 2 times as much. In the `sat` runs the Ombra data path makes
the batch of 64 packets at once, so the one-way delay p50 is 0.5 ms against 0.04 ms (p99 1.2 ms against 8.4 ms).
This is the generator, not the tunnel: the TUN reader takes only the packets that are already there.

## 2. Emulated path: 100 Mbit/s, 40 ms RTT, 50 ms drop-tail queue, inner Cubic-like TCP, 20 s

| Variant | goodput Mbit/s | first 10 s | after 10 s | first second | fragmented | black holes | UDP datagrams per inner packet | inner RTT p50 / p99 ms |
|---|---|---|---|---|---|---|---|---|
| upstream | 92.4, 92.5 | 89.8, 90.1 | 94.9, 95.0 | 70, 72 | 50.8%, 50.5% | 1, 1 | 1.58 | 87 / 103, 85 / 97 |
| **Ombra** | **94.2, 94.2** | **93.3, 93.1** | 95.2, 95.2 | 77, 75 | **0%** | **0** | 1.08 | 85 / 97, 85 / 97 |
| **Speed mode** | **96.6, 96.6** | **95.6, 95.6** | 97.6, 97.6 | 78, 78 | - | - | - | 85 / 96, 85 / 96 |

Upstream: quinn takes a congestion loss for a black hole at about 1 s, the MTU goes to 1200 until about 11 s, and
half of the packets go in two fragments (perf-plan 1.2). In the first 10 s, the length of a typical speed test,
upstream gets 3 Mbit/s less than Ombra. With the MTU floor (B1) the MTU stays at 1350, nothing is fragmented, and
the client needs 25% less CPU per packet at this rate (45 and 43 us against 67 and 51 us; at 9 kpps this is mostly
wakeups). Speed mode has no
QUIC overhead (about 30 bytes per packet) and gets 2.4 Mbit/s more.

## 3. Overload: 150 Mbit/s offered into the same path, 10 s

| Variant | goodput Mbit/s | fragmented | black holes | one-way delay p50 / p99 ms |
|---|---|---|---|---|
| upstream | 62.5 | 95.9% | 4 | 70.6 / 87.6 |
| **Ombra** | **95.0** | 0% | 0 | 71.1 / 79.2 |
| **Speed mode** | **97.6** | - | - | 70.7 / 76.5 |

The Ombra queue of 256 KiB (B3) drops 1296 datagrams at the sender instead of letting them wait; the bottleneck
queue drops the rest in all variants.

## 4. Padding on: uplink cost of a stream of inner TCP ACKs

64-byte packets at 4.5 kpps, one at a time (the uplink of a 100 Mbit/s download), 10 s, loopback.

| Variant | uplink on the wire | UDP datagrams | added one-way delay p50 / p99 |
|---|---|---|---|
| upstream, padding off | 4.51 Mbit/s | 44543 | - (0.05 / 0.33 ms) |
| upstream, padding on (`quicFramePadding`) | **48.16 Mbit/s** | 44594 | - (0.06 / 0.20 ms) |
| Ombra, padding off | 4.50 Mbit/s | 44590 | - (0.05 / 0.16 ms) |
| **Ombra, padding on (E1 coalescer)** | **4.05 Mbit/s** | **3753** | **1.6 / 2.7 ms** |

With E1 the padded ACK stream costs less uplink than upstream without padding: 12 ACKs share one 1350-byte packet.
The price is up to about 2.7 ms on each small packet (the 2 ms batch plus the 1 ms timer granularity).

## 5. ACK frequency and BBR (why the threshold is 3)

The first run of this bench used the B2 values of perf-plan (threshold 10, max_ack_delay 5 ms). In 2 of 4
saturated loopback runs quinn's BBR stayed at its minimum window (5 to 8 KB) and the tunnel ran at 6 to 14 Mbit/s for
3 to 5 s instead of about 1300 Mbit/s. Mechanism (quinn-proto 0.11.19 `congestion/bbr`): BBR enters ProbeRTT with
a 4-packet window the first time the connection is cwnd-limited (`probe_rtt_last_started_at` is `None`), and its
smallest window is 4 packets. With a threshold of 10 such a window gets its ACK only from the timer, BBR measures
the timer as the delivery rate, and on a path whose RTT is shorter than the timer the window stays small.

Saturated loopback runs with each setting (all runs of R5, quiet and loaded machine):

| ACK frequency request | runs with a collapse | client us/pkt, same conditions (T2) |
|---|---|---|
| threshold 10, 5 ms | 2 of 14 | 20.8 |
| threshold 10, 1 ms | 1 of 6 | 19.7 |
| threshold 3, 5 ms (now) | 0 of 12 | 23.5 |
| threshold 3, 1 ms | 0 of 3 | - |
| off | 0 of 9 | 26.9 |

On emulated paths with 2 ms RTT (20 and 300 Mbit/s, inner TCP) no setting collapsed, and all gave the same goodput.
With threshold 3 every 4-packet window gets an immediate ACK, so the timer can no longer set the rate. The patch
keeps a `const_assert` for this. Whether the real relay honours the request is unknown (perf-plan section 6).

## 6. What these numbers do not show

- Phone CPUs: the per-packet costs scale, the ratios should hold. Android has GSO/GRO, macOS here has none.
- The real relay: its congestion controller, its MTU handling, ACK frequency support and the downlink direction.
  All QUIC numbers here are uploads from the client.
- The TUN syscalls and the batch sizes that a real TUN gives.
- Handover (D1, D2) and the liveness changes: covered by the loopback tests in `quicwg.rs` and `tunnel_state.rs`, not
  by a speed number.

## Reproduce

```zsh
cd /tmp/obscura-research/bench/qwbench && cargo build --release --offline
cd .. && OUT=results/r5_final.jsonl zsh scripts/run_r5.zsh L L64 A D P   # sections 1 to 4, about 5 min
zsh scripts/run_r5_ackfreq.zsh T T2 T3 S N                              # section 5, about 6 min
```

Raw results: `results/r5.jsonl` (first run, threshold 10), `results/r5_final.jsonl`, `results/r5_ackfreq.jsonl`.
Variant flags: `--base=obscura` (upstream config), `--base=ombra [--ombra-mtu=floor|startlarge|discover]
[--ackfreq=0]`, `--dp=ombra` (Ombra data path; with `--transport=udp` Speed mode), `--spaced=1` (one packet at a time
in `rate` mode). `QW_ACKTHR` and `QW_ACKDELAY_US` set the ACK frequency values.
