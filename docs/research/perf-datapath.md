# perf-datapath: Obscura Android data path, throughput and latency audit

Scope: TUN fd -> WireGuard (neptun) -> QUIC DATAGRAM (quinn 0.11) -> UDP -> relay -> exit, both directions.
Sources: upstream client HEAD (2026-10-19) at /tmp/obscura-research/obscuravpn-client, API crate at pinned rev e94483e (/tmp/obscura-research/obscuravpn-api), neptun at ff3b5555 (copied to /tmp/obscura-research/neptun), crates from Cargo.lock: quinn 0.11.12, quinn-proto 0.11.19, quinn-udp 0.5.16, ring 0.17.14, tokio 1.45.1, rustls 0.23.28.
Measurements: throwaway harness at /tmp/obscura-research/perf-bench (src/main.rs). It uses the exact upstream client TransportConfig against a local quinn server through an emulated bottleneck. Machine: Apple Silicon, macOS (no UDP GSO). Phone numbers are NOT measured.

Labels: [V] = verified in code or by measurement. [H] = hypothesis, needs on-device data.

---

## 1. Summary (ranked)

| # | Bottleneck | Impact | Confidence | Fix location | Wire / privacy risk |
|---|---|---|---|---|---|
| 1 | "Enable packet padding" flag pads EVERY uplink QUIC packet (incl. TCP ACK carriers) to 1350 B. Measured: ACK stream of a 100 Mbit/s download costs 53.8 Mbit/s uplink (5.5 without). Download is capped by uplink on asymmetric links. | High (if flag on) | High (mechanism) / unknown if user enabled it | rustlib: coalesce small packets before quinn (measured 53.8 -> 5.4 Mbit/s, all packets still 1350 B) | None on wire. Keeps uniform size. Adds <=2 ms to small packets. |
| 2 | Relay-side downlink sender (closed source): its congestion controller, 1 MiB datagram queue (quinn default), fixed 1350 B payload. quinn default CC is Cubic. Measured with Cubic at 1% random loss: 3.3-4.2 of 40 Mbit/s, p99 one-way delay 2.4-3.0 s. BBR: 39.6. | High (if relay uses loss-based CC) | Low-medium (not observable from code) | Not fixable in client. Measure, report upstream. Client can only avoid self-inflicted loss (#4). | n/a |
| 3 | Any change of underlying network tears down the whole tunnel and does a full reconnect (relay race + API CreateTunnel + QUIC + WG handshake + first pong). No QUIC migration. WireGuard roams. | Medium-high (multi-second stalls) | High (mechanism) | rustlib: try `Endpoint::rebind` + keep `QuicWgConn`; fall back to reconnect on liveness failure | Standard QUIC (RFC 9000 s9). Relay support unknown, must test. Relay can link old and new client IP (it can already via new tunnel). |
| 4 | UDP socket buffers never set (no SO_RCVBUF/SO_SNDBUF in rustlib or quinn). wireguard-go sets 7 MiB. Kernel drops on bursts become "network loss" for relay CC and inner TCP. | Medium | Medium [H] | rustlib net.rs | None |
| 5 | Android TX path is 1 packet per wakeup; one `Mutex<WgState>` serializes TX and RX crypto and is held across 2 quinn lock acquisitions per packet. Linux/Windows builds batch up to 100 packets per lock. | Medium at >=200 Mbit/s, low on cellular | High (mechanism), measured per-packet costs | rustlib android/tunnel.rs + quicwg.rs | None |
| 6 | quinn default queues: 1 MiB datagram send queue (head-drop), 1.25 MB receive queue. Up to 1 MiB / rate of extra delay when the sender is throttled (0.42 s at 20 Mbit/s, 1.7 s at 5 Mbit/s). Measured: Cubic 380-480 ms one-way; BBR (upstream) no bloat when app-limited. | Medium for latency under load, only when throttled | Medium | rustlib quicwg.rs quic_config | None (advertised max_datagram_frame_size is capped at 65535, unchanged) |
| 7 | Fragmentation edges: initial MTU 1200 (first ~3 RTT: measured 12% of packets fragmented in first second), black-hole fallback to 1200 for >=10 s, MTUD probe loss -> stuck below 1350 for 600 s, CLAT (464XLAT) MTU not considered, relay CID > 8 B or relay max_datagram_frame_size < 1321 -> every full packet fragmented. Each fragmented packet = 2 UDP packets, +5.4% bytes, ~2x loss probability. | Low-medium (steady state fits exactly) | Medium | rustlib quicwg.rs, net.rs, Android shell | None |
| 8 | "Use TCP/TLS tunnel" flag: TCP-over-TCP, plus one app-level OK message per received WG packet, 3 TLS writes + flush per message, 1000-packet queue. | High (if flag on) | High (mechanism) | rustlib quicwg.rs: batch writes and OKs | None (same byte stream; only TLS record boundaries change) |
| 9 | "Small MTU compatibility" flag: client advertises max_udp_payload_size 1200 -> relay fragments every full-size downlink packet. | Medium-high (if flag on) | High | UI/education; auto-detect instead | None |
| 10 | Liveness checker can declare a loaded tunnel dead (5 probes lost, probe lost period min 1 s) -> full reconnect. | Low-medium | Medium [H] | rustlib liveness.rs | None |
| 11 | Small items: per-packet allocs/copies (~0.3 us), 2 tokio timers per RX packet (measured 40 ns), clock_gettime(BOOTTIME) per packet, 1 Hz INFO logs, double AEAD (QUIC adds ~27% of WG crypto cost), IPv4-only relays (CLAT on IPv6-only carriers), 55 s idle ping vs 30 s carrier NAT timeouts. | Low | High | various | None |

First action, before code changes: read the official app's debug log for the fields in section 5. They tell which of #1, #3, #7, #8, #9, #10 apply to the phone and network.

---

## 2. Data path map (verified)

### 2.1 TX: app -> TUN -> WG -> QUIC -> UDP

1. Android shell: `VpnService.Builder` with `setMtu(networkConfig.mtu)` (= 1280), no `setBlocking` call (ObscuraVpnService.kt:394-425). Android default is non-blocking ("By default, the file descriptor returned by establish is non-blocking", VpnService.java:877) [V]. fd handed to Rust with `detachFd()` (ObscuraVpnService.kt:125).
2. TUN read task, one packet per `read()` and one `tunnel.send()` per packet (android/tunnel.rs:28-52) [V]:
   ```rust
   let mut buf = Box::new([0; u16_into_usize(TUNNEL_MTU)]);   // 1280 B
   loop {
       match fd_watcher.readable().await {
           Ok(mut guard) => match unistd::read(&fd_watcher, &mut buf[..]) {
               Ok(n) => { if n > 0 { tunnel.send(std::iter::once(&buf[..n])) } }
               Err(Errno::EAGAIN) => { guard.clear_ready(); }
   ```
   Compare Linux: drains up to 100 packets into `PacketBuffer` then one `tunnel.send(iter)` (bin/obscura/service/os/linux/tun.rs:72-92, 159-168; os/packet_buffer.rs:3-4) [V].
3. `QuicWgConn::send` takes the single `wg_state` mutex for the whole batch, checks liveness (reads CLOCK_BOOTTIME), encapsulates (quicwg.rs:383-395) [V]:
   ```rust
   let mut wg_state = self.wg_state.lock().unwrap();
   if let Some(packet) = wg_state.liveness_checker.sent_traffic() { self.send_single_packet(&mut wg_state, &packet); }
   for packet in packets { ... self.send_single_packet(&mut wg_state, packet); }
   ```
4. `send_single_packet` (quicwg.rs:403-426) [V], still under the lock:
   ```rust
   match wg_state.wg.encapsulate(packet, &mut wg_state.buffer) {
       TunnResult::WriteToNetwork(wg_message) => {
           let wg_message = Bytes::copy_from_slice(wg_message);                 // alloc + copy
           let (first, second) = match self.wg_sender.max_wg_message_size() {   // quinn lock #1
               Some(max_size) => wg_state.fragmenter.fragment(wg_message, max_size),
               None => (wg_message, None),
           };
           for msg in once(first).chain(second) { self.wg_sender.send_wg_message(msg); } // quinn lock #2 + wake
   ```
   `send_wg_message` -> `conn.send_datagram(wg_message)` (quicwg.rs:922-931), which locks quinn's connection state and wakes the driver (quinn connection.rs:448-466). `max_datagram_size()` also locks it (connection.rs:495-501) [V].
5. neptun `encapsulate`: no locks on the data path except an atomic counter; copies src into dst and seals in place with ring ChaCha20-Poly1305 (neptun session.rs:197-231; noise/mod.rs:274-297). Timers use a cached time value, no clock read per packet (timers.rs:131-152) [V]. ring 0.17.14 on aarch64 uses the integrated `chacha20_poly1305_seal/open` asm (ring src/aead/chacha20_poly1305/integrated.rs:112-117) [V].
6. quinn ConnectionDriver task: `drive_transmit` builds packets (DATAGRAM frames, AES-GCM via rustls **ring** provider, quicwg.rs:713) and calls `sendmsg` with UDP GSO, max 10 segments per call and max 20 datagrams per wakeup (quinn connection.rs:1014-1080, 1334, 1341) [V]. Datagrams are congestion-controlled and paced (quinn-proto connection/mod.rs:616, 627) [V].

TX hops: TUN task -> (quinn mutex + waker) -> ConnectionDriver. 1 task hop. Copies: kernel->buf, buf->WG dst, dst->Bytes, Bytes->QUIC packet, kernel copy. 1 allocation (+2 if fragmented).

### 2.2 RX: UDP -> QUIC -> WG -> TUN

1. quinn EndpointDriver: `recvmmsg` (BATCH_SIZE 32) with UDP GRO (64 segments) (quinn-udp unix.rs:465-503, 804-806, 1000-1025) [V]; each received buffer is copied into a `BytesMut` (quinn endpoint.rs:798) and sent to the connection over an unbounded mpsc channel [V].
2. ConnectionDriver decrypts, queues DATAGRAM frames (drops oldest above 1.25 MB, quinn-proto connection/datagrams.rs:124-152), notifies the reader [V].
3. `TunnelState::maintain` task loops `conn.receive()` then `os_impl.packet_for_os(packet)` (tunnel_state.rs:332-343) [V]. Inside, per packet (quicwg.rs:437-512) [V]:
   ```rust
   select! { biased;
       _ = sleep_until(next_wg_timers_tick) => { ... }               // new Sleep per packet
       result = self.wg_receiver.receive_wg_message() => {
           let mut wg_state = self.wg_state.lock().unwrap();          // same mutex as TX
           ... fragment_buffer.reassemble(wg_message) ...
           let res = wg.decapsulate(None, &wg_message, buffer);
           ... Bytes::copy_from_slice(packet) ...                      // alloc + copy (handle_result, :374)
           liveness_checker.process_potential_probe_response(&packet) // parses every packet
       }
       _ = sleep(next_liveness_poll.remaining()) => { ... }          // new Sleep + clock read per packet
   ```
4. `RevocableOs::packet_for_os` (os/os_trait.rs:63-67: tokio RwLock `try_read`) -> `AndroidOsImpl::packet_for_os` (android/os_impl.rs:65-69: std Mutex) -> `unistd::write(fd, packet)`, one syscall per packet, failures rate-limited-logged (android/tunnel.rs:56-69) [V].

RX hops: EndpointDriver -> (mpsc) -> ConnectionDriver -> (Notify) -> maintain task. 2 task hops. Runtime: `Runtime::new()` multi-thread, workers = CPU count (android/ffi.rs:48) [V].

### 2.3 Configuration actually used (verified values)

| Item | Value | Where |
|---|---|---|
| TUN MTU | 1280 | quicwg.rs:61, client_state.rs:396, ObscuraVpnService.kt:400 |
| QUIC max UDP payload (MTUD upper bound) | 1350 | quicwg.rs:64, 739-742 |
| MTUD black-hole cooldown | 10 s (quinn default 60 s) | quicwg.rs:741 |
| MTUD interval, min change | 600 s, 20 B (quinn defaults) | quinn-proto config/transport.rs:746-755 |
| initial_mtu / min_mtu | 1200 / 1200 (defaults) | quinn-proto lib.rs:332, transport.rs:385-386 |
| Small-payload mode | endpoint max_udp_payload_size 1200 if interface MTU < 1378 or flag | net.rs:102-127 |
| Congestion controller | BBR (quinn "Experimental!") | quicwg.rs:744; quinn-proto congestion/bbr/mod.rs:18 |
| Padding | `pad_to_mtu(quic_frame_padding)` (UI flag) | quicwg.rs:745 |
| Stream windows | 4 KiB (control stream only; datagrams have no flow control) | quicwg.rs:55, 734-738 |
| datagram_send_buffer_size | 1 MiB (default) | quinn-proto transport.rs:396 |
| datagram_receive_buffer_size | 1.25 MB (default); advertised as min(x, 65535) | transport.rs:395; transport_parameters.rs:170-172 |
| ACK policy | ACK after >1 ack-eliciting pkt, max_ack_delay 25 ms; no ack_frequency_config | quinn-proto connection/spaces.rs:651, 718-721 |
| QUIC idle timeout / keepalive | 600 s / none | quicwg.rs:51, 743 |
| WG timers | update_timers at 1 Hz; no WG persistent keepalive | quicwg.rs:60, 246 |
| Liveness | ICMP echo, 100 B, every 1 s when busy, 55 s idle; dead after >4 lost | liveness.rs:10-13, 21-23, 117-125 |
| UDP socket | IPv4 only, bind 0.0.0.0:0, no buffer sizes, not bound to a Network on Android | net.rs:28-55 |
| GSO / GRO | on if kernel >= 4.18 / UDP_GRO accepted | quinn-udp unix.rs:126-128, 811-850, 1000-1025 |
| Crypto | WG: ring ChaCha20-Poly1305. QUIC: rustls ring provider (process default is aws-lc-rs, ffi.rs:54-56, not used for relay QUIC) | neptun session.rs:8; quicwg.rs:713 |
| Fragment buffer | 10,000 slots, 1540 B max fragment | quicwg.rs:77, 83 |

### 2.4 Byte budget (verified by formula and by measurement)

quinn max datagram = current_mtu - (1 flags + DCID + 4 PN bound + 16 tag) - 9 (DATAGRAM frame bound) (quinn-proto connection/datagrams.rs:82-96, connection/mod.rs:3763-3776, frame.rs:901-903).
- At 1350 with an 8-byte relay CID: 1350 - 29 - 9 = **1312**. A 1280-byte inner packet is a 1312-byte WG message (DATA_OVERHEAD_SZ 32, neptun mod.rs:92). It fits with **0 bytes spare** [V: harness prints `max_dgram Some(1312)` with quinn's default 8-byte CID].
- At 1200: 1162. Any inner packet > 1130 B is split into 2 fragments of 8 + len/2 B (wg_fragment/split.rs:10-41).
- On-wire size of one full-size packet: about 1370 B IPv4 (1312 + ~30 QUIC + 28). Plain WireGuard: 1340 B. Tunnel tax is ~2.2%. When fragmented: 2 packets, ~1444 B (+5.4%), and the message is lost if either half is lost.
- Downlink: the client CID is quinn's default 8 B (cid_generator.rs:49-55), so a relay that sends 1350 B payloads has the same 1312 B budget.

---

## 3. Measurements (Apple Silicon, macOS, release build; phones not measured)

| Test | Result |
|---|---|
| ring ChaCha20-Poly1305 seal, 1280 B (WG) | 731 ns/pkt, 14.0 Gbit/s per core |
| ring AES-128-GCM seal, 1340 B (QUIC) | 197 ns/pkt, 54.5 Gbit/s per core |
| neptun encapsulate 1280 B + `Bytes::copy_from_slice` | 850 ns/pkt |
| neptun decapsulate + `Bytes::copy_from_slice` | 753 ns/pkt |
| `select!` with 2 new tokio Sleeps per RX packet (quicwg.rs:447-523 pattern) | +38-39 ns/pkt |
| quinn DATAGRAM ceiling, upstream client cfg, both ends in 1 process, no GSO | 1.2-2.1 Gbit/s (run-to-run noise); "no CC" 1.3-2.2 |
| Startup, 20 Mbit/s, 40 ms RTT, first 1 s, upstream cfg | 236 of 1907 packets fragmented (max_dgram 1162 at start) |
| Same with `initial_mtu(1350)` | 0 fragmented |
| Bottleneck 20 Mbit/s policer, 40 ms, unresponsive 24 Mbit/s source | BBR 1 MiB: p99 46 ms. BBR 128 KiB: p99 42 ms. **Cubic 1 MiB: p50 379 ms, p99 458 ms**, goodput 17.5. No CC: p99 42 ms |
| 50 Mbit/s, 1% random loss, 40 ms, 40 Mbit/s offered | BBR: 39.6 Mbit/s, p99 42-43 ms. **Cubic: 3.3-4.2 Mbit/s, p99 2.4-3.0 s**. No CC: 39.6 |
| 84 B datagrams at 4880/s (= inner ACKs of a 100 Mbit/s download) | pad off: 5.52 Mbit/s on wire. **pad on: 53.80 Mbit/s**. pad on + 10-packet coalescing: 5.38 Mbit/s, still all 1350 B |

Observation [V]: quinn's BBR never leaves Startup when traffic is app-limited, because `check_if_full_bw_reached` returns early on `app_limited` (quinn-proto congestion/bbr/mod.rs:372-375) and Startup grows cwnd by bytes_acked (mod.rs:309-336). quinn sets `app_limited = buf.is_empty() && !congestion_blocked` (connection/mod.rs:947). In the harness cwnd reached 20-40 MB. quinn's pacer derives rate from cwnd/RTT, not from BBR's pacing_rate (connection/pacing.rs:92). Result: for normal tunnel traffic the upstream client's outer CC is close to "no CC", which is good. When traffic is not app-limited, BBR enters ProbeRTT at once (probe_rtt_last_started_at is None, mod.rs:213-221) and every 10 s, with window 0.75 x BDP for >=200 ms.

Phone estimate [H]: big cores ~2-4x slower than the Mac single-thread, little cores ~5-10x. On a big core WG + QUIC crypto is still < 3 us/pkt/direction, so crypto alone is not the bottleneck below ~300 Mbit/s. Syscalls, wakeups and lock hand-offs dominate.

---

## 4. Findings in detail, with changes

### F1. Packet padding flag multiplies uplink traffic. Impact high when on. Confidence high (mechanism, measured).
- [V] UI exposes it on Android: `ExperimentalSettings` is always rendered (obscura-ui/src/views/Settings.tsx:26-35) and shows every flag except KillSwitch (Settings.tsx:215-225). Text: "Make network packets a uniform size ..." and warning "may slightly increase bandwidth usage" (obscura-ui/src/translations/en.json:167-169). A privacy-focused user is likely to turn it on.
- [V] `pad_to_mtu` pads every 1-RTT UDP datagram, including ACK-only packets, to the current MTU (quinn-proto connection/mod.rs:561, 929-931).
- [V] Measured: inner TCP ACK stream of a 100 Mbit/s download: 5.5 -> 53.8 Mbit/s uplink (9.7x). Client QUIC ACK-only packets for the relay's data are padded too, so the real cost is 54-108% of the download rate [H for the upper bound]. On LTE (e.g. 100 down / 20 up) the download caps near the uplink rate. WireGuard has no such cost.
- [V] Only the uplink is padded. The relay decides about downlink padding, so the "uniform size" property is one-way.
- Change (rustlib, quicwg.rs send path): when padding is on, hold WG messages smaller than ~600 B for at most 2 ms (or until ~1200 B are pending), then submit them together. quinn packs all queued datagrams into one padded packet. Measured: 53.8 -> 5.38 Mbit/s, every packet still 1350 B. Optional: carry a quinn patch with `send_datagrams(iter)` that locks once, so the driver cannot run between submissions.
- Privacy: packet sizes stay uniform. Timing becomes batched (less fine-grained). Cost: <= 2 ms extra latency on small packets only when padding is on. No wire change.
- Also fix the UI text: state the real cost (up to ~1x of download as upload).

### F2. Relay downlink sender is the main unknown. Impact high. Confidence low-medium.
- [V] Download speed is set by the relay's QUIC sender (CC, pacing, datagram queue, MTU), not by the client. The relay is closed source. quinn's default CC is Cubic (quinn-proto config/transport.rs:400) and default datagram send queue is 1 MiB (transport.rs:396).
- [V] Harness: Cubic as outer CC under 1% random loss delivers 3.3-4.2 of 40 Mbit/s with 2.4-3.0 s p99 delay, because the outer CC backs off for losses that the inner TCP also handles (double back-off), and the 1 MiB queue then fills. BBR and no-CC deliver 39.6.
- [H] If the relay uses Cubic/NewReno with defaults, Wi-Fi/cellular random loss alone explains "many speed issues" that plain WireGuard (no outer CC) does not have.
- Change: none possible in the client. Test plan with our Linux build of rustlib (obscura CLI): add 0.5/1/2% ingress loss with `tc netem` on an IFB device, run a single-stream download through Obscura and through direct WireGuard to the same Mullvad exit. A steep drop only on Obscura indicates a loss-based relay CC. Report to Obscura.
- Do NOT try to hide loss from the relay by acknowledging packets not received: quinn skips packet numbers to detect this and closes the connection, and it is a CC circumvention.

### F3. Full reconnect on every network change. Impact medium-high (stalls). Confidence high.
- [V] `TargetState` includes `network_interface` (client_state.rs:112-125). `matches_target` compares it (tunnel_state.rs:163-171), and a mismatch drops the tunnel and reconnects (tunnel_state.rs:203-229). Reconnect = 1 s debounce (tunnel_state.rs:177) + relay race over up to 100 relays x 5 ports (relay_selection.rs:88-119) + 3 control-stream pings + API `CreateTunnel` + QUIC + WG handshake + first pong.
- [V] The Android shell updates the interface on every `onAvailable`/`onLost` of the best non-VPN network (ObscuraVpnService.kt:198-220, 457-466).
- Change (rustlib, experimental flag): on interface change with the QUIC transport, create a new UDP socket and call `quinn::Endpoint::rebind(socket)`; keep `QuicWgConn`; keep WG session. Treat `network_interface` as "rebind" instead of "reconnect" in `matches_target`. If the liveness checker reports dead within ~3 s, fall back to the current reconnect. Recompute small-MTU mode for the new interface.
- Compat: QUIC migration is standard. A relay that set `disable_active_migration` or does not handle path validation will fail; the fallback covers this. Must be tested against real relays.
- Privacy: the relay sees old and new client IPs on one connection. It can already link them through tunnel creation on the same account. quinn uses a fresh CID after migration if the relay issued spare CIDs, so passive observers on the two networks cannot link them by CID.

### F4. No UDP socket buffer tuning. Impact medium. Confidence medium [H].
- [V] `new_socket` sets no buffer sizes (net.rs:28-51). quinn-udp sets none by default (it only offers `set_recv_buffer_size`, unix.rs:257-265). wireguard-go uses 7 MiB socket buffers.
- [H] With GRO and pacer bursts from the relay, a small default receive buffer drops packets during short scheduling stalls of the EndpointDriver (e.g. little core, GC in the app process, thermal). The relay and inner TCP see this as path loss, which is especially bad if F2 applies.
- Change (net.rs, before `socket.bind`): `socket.set_recv_buffer_size(4 << 20)`, `socket.set_send_buffer_size(2 << 20)`, then log the effective values (the kernel caps at rmem_max/wmem_max and doubles the value). No wire impact.
- Verify on device: log effective sizes; compare `udp_rx` counters in quinn stats with the relay's sent counts if available, or look for loss without matching RTT increase.

### F5. Serialized per-packet datapath on Android. Impact medium at high rates. Confidence high (mechanism).
- [V] TX: 1 read syscall, 1 lock of `wg_state`, 1 BOOTTIME clock read, 2 quinn mutex acquisitions and 1 waker per packet (sections 2.1 steps 2-4). The wg_state lock is held while waiting on quinn's connection mutex, which the ConnectionDriver holds while it builds and sends up to 20 packets. RX waits on the same wg_state lock (quicwg.rs:470). So TX and RX crypto cannot overlap, and RX latency gets jitter from TX.
- [V] Linux already batches (linux/tun.rs:72-92). Android does not.
- Changes (rustlib, no wire impact):
  1. android/tunnel.rs: read until EAGAIN into `PacketBuffer` (max 100 packets), then one `tunnel.send(pb.take_iter())`, same as Linux.
  2. quicwg.rs `send`: call `max_wg_message_size()` once per batch; encrypt and fragment into a local `Vec<Bytes>` under the lock; release the lock; then submit to quinn. Same on RX for `handle_result` WriteToNetwork.
  3. Optional quinn patch: `Connection::send_datagrams(impl Iterator<Item=Bytes>)` and `read_datagrams(&mut Vec<Bytes>)` that take the state lock once per batch.
  4. Optional neptun patch: split `Tunn` so encapsulate uses only `&Session` (Session already has an atomic send counter and its own receive-window mutex, session.rs:11-18), allowing separate TX and RX locks.
- Not fixable without server support: quinn processes one connection on one driver task, so QUIC crypto for a single tunnel uses one core. wireguard-go parallelizes crypto over all cores.

### F6. Large default datagram queues. Impact medium for latency under load. Confidence medium.
- [V] Send: 1 MiB, oldest datagrams are dropped when full (quinn-proto connection/datagrams.rs:28-56, 154-161; quinn uses `drop = true`, connection.rs:454). Queue delay when the sender is throttled: 1,048,576 B x 8 / rate = 1.68 s at 5 Mbit/s, 0.42 s at 20, 0.17 s at 50.
- [V] Receive: 1.25 MB, oldest dropped (datagrams.rs:124-152). Fills only when the maintain task is slower than the network.
- [V] With upstream BBR and app-limited traffic, the send queue stays empty (harness). It fills when the ConnectionDriver is CPU-bound, during BBR ProbeRTT, or after a black-hole MTU drop.
- [V] When MTU drops, queued datagrams larger than the new limit are all discarded (datagrams.rs:58-71, 182-198): a large queue makes this a large burst loss.
- Change (quicwg.rs:728-748): `datagram_send_buffer_size(256 * 1024)` and `datagram_receive_buffer_size(Some(512 * 1024))`. Advertised `max_datagram_frame_size` is `min(x, 65535)` (transport_parameters.rs:170-172), so the ClientHello transport parameters do not change. No wire or fingerprint change.
- Keep BBR. Do not switch to Cubic/NewReno (F2 numbers). A "no CC" controller gave no gain over quinn BBR in app-limited tests and only +5-10% at the CPU ceiling; it deviates from RFC 9002/9221, so it is not recommended.

### F7. Fragmentation edges. Impact low-medium. Confidence medium.
- [V] Steady state fits with 0 bytes spare (section 2.4). Any of these causes every full-size packet to be fragmented:
  - Startup: initial MTU 1200 until MTUD confirms 1350 (3 probes: 1275, 1312, 1350, quinn-proto connection/mtud.rs:326-347). Measured 12% of packets fragmented in the first second at 20 Mbit/s / 40 ms.
  - Black hole detected (more than 3 "suspicious" loss bursts of packets > 1200 B, mtud.rs:364-500, 528): MTU = 1200 for >=10 s (quicwg.rs:741), and queued oversized datagrams are dropped (F6). With bulk traffic nearly all packets are > 1200 B, so tail-loss bursts on Wi-Fi/LTE can look suspicious [H].
  - If the 1350 probe is lost 3 times, the search ends below 1350 (~1330-1340 -> max datagram 1300-1310 < 1312) and stays there for the 600 s interval [V by reading the search code].
  - IPv6-only carrier with 464XLAT: IPv4 goes through `v4-<iface>` with MTU = underlying - 28, but `interface_mtu` reads the underlying interface (client_state.rs:322-331, net.rs:73-95). With underlying MTU 1378-1405 the client chooses 1350 although the IPv4 path is too small [H, carrier dependent].
  - Relay CID longer than 8 B, or relay `max_datagram_frame_size` < 1321 [unknown, check log field `max_wg_message`].
- Changes (no wire change):
  - `transport_config.initial_mtu(1350)` when not in small-payload mode. Client Initial packets are still padded to `min(config.min_mtu, current_mtu)` = 1200 (quinn-proto connection/mod.rs:478, 646, 921), so the handshake size does not change. Measured 0 startup fragments.
  - `MtuDiscoveryConfig::interval(Duration::from_secs(60))` so a failed search recovers in 1 minute, not 10.
  - Android shell: pass the effective IPv4 MTU (min of underlying and `v4-<iface>` if present, or `LinkProperties.getMtu()` when > 0) instead of only the base interface.
  - Keep `min_mtu` at 1200 (raising it risks a permanent black hole).

### F8. TCP/TLS mode is chatty. Impact high when on. Confidence high (mechanism).
- [V] Every received WG op queues an OK response to the relay (quicwg.rs:966-970). Each message is written as 3 `write_all` + `flush` (quicwg.rs:997-1005) with TCP_NODELAY (quicwg.rs:599). The sender pops one packet at a time through a `watch` channel (quicwg.rs:842-891) and tail-drops above 1000 queued packets (quicwg.rs:932-937). Plus TCP-over-TCP meltdown under loss.
- [H] rustls encrypts per write call, so each WG packet becomes 3 TLS records (+~66 B, 3 AEAD calls).
- Change: build header+code+payload in one buffer; write all queued packets and all queued OKs, then flush once. The plaintext byte stream is identical, only record and segment boundaries change. Reduce the queue to ~200 packets.
- UI: say clearly that this mode is for UDP-blocked networks only.

### F9. Small-MTU flag. Impact medium-high when on. Confidence high.
- [V] `force_small_mtu` sets endpoint `max_udp_payload_size(1200)` (net.rs:105-126), which the relay uses for its downlink: 1162 B budget, so every full-size downlink packet is fragmented (2 packets, ~2x loss sensitivity).
- Change: automatic detection (F7) and clear UI text; do not keep it on by default.

### F10. Liveness false "dead" under load. Impact low-medium. Confidence medium [H].
- [V] Probes are queued behind all data in the same datagram queue. Lost period = clamp(2 x slowest pong RTT of last 100, 1 s, 30 s); dead when > 4 probes are past that period (liveness.rs:10-23, 61-79, 108-125). Head-drop in a full 1 MiB queue can drop probes.
- Change: F6 bounds the delay. In addition, do not count a probe as lost while the datagram send queue is non-empty and older than the probe (use `datagram_send_buffer_space()`), or require "no RX traffic at all" in addition to lost probes before declaring dead. No wire change.

### F11. Low-impact items (verified unless marked)
- Per-packet: 1-2 allocations and ~4 memcpy per direction (~0.3 us); RX re-creates 2 tokio Sleeps (measured 38-39 ns); `liveness.process_potential_probe_response` parses every RX packet with etherparse; CLOCK_BOOTTIME read per TX packet (wake_instant.rs; vDSO on newer arm64 kernels, a syscall on older ones [H]).
- Logging: no per-packet logs; errors on the hot path are rate-limited with a global mutex (rate_limited_log.rs:1-13). While busy: 1 Hz tick stats (quicwg.rs:451-456) and 2-4 liveness INFO lines/s (liveness.rs:87, 132-158, 192-201), each written to logcat and to a JSON file with XZ rotation (logging.rs:43-70, 82-98). Battery cost, not throughput.
- Double AEAD: QUIC AES-GCM adds ~27% of the WG ChaCha cost on ARMv8 with crypto extensions. On rare ARMv8 cores without AES instructions, AES-GCM falls back to slow software [H]; preferring ChaCha20 there would change the ClientHello order (fingerprint), so only do it on such devices.
- Fragment buffer may pin whole GRO receive allocations (quinn endpoint.rs:798 slices one BytesMut per batch); memory only.
- Relays are IPv4 only (`ip_v6` unused, obscuravpn-api types.rs:495-505; net.rs:29). On IPv6-only carriers all traffic goes through CLAT (userspace clatd on Android <= 12 [H]).
- Idle ping every 55 s (liveness.rs:13) is longer than common carrier UDP NAT timeouts (~30 s) [H]: inbound traffic after idle can be lost until the client sends. Trade-off with battery.
- Relay is chosen only by client->relay RTT (relay_selection.rs, client_state.rs:536-600); the exit is ranked by the relay's preferences and city (exit_selection.rs:74-79). Path stretch through the relay is part of the design.
- Inner MTU 1280 cannot grow: the relay's fixed 1350 B payload leaves no room, and IPv6 requires >= 1280. Inner TCP MSS is 1240 (v4) / 1220 (v6); no MSS clamping problem.

---

## 5. Diagnostics to run first (no code change)

From the official app's debug bundle / log (rust-log.ndjson), on the user's device and networks:
1. Feature flags: `quicFramePadding`, `tcpTlsTunnel`, `forceSmallMtu` (config `feature_flags`). If padding is on, F1 is very likely the main cause.
2. message_id `WKqFjXMA` (1 Hz): `max_udp_payload` should be 1350 and `max_wg_message` >= 1312. Then `relay CID length = max_udp_payload - 30 - max_wg_message`. Non-zero `wg_tx_fragmented_count` or `wg_rx_fragment_reassembled_count` in `tick_stats` during bulk traffic = F7/F9 is active.
3. message_id `2sonYhc2` (liveness dead) and `tls1cZot` (tunnel failed) counts per hour: F10/F3.
4. message_id `TF51QUHb` / `kq0AuTsT`: small-payload mode active.
5. message_id `iew0Ahk9` / `eePai0oh`: how often the interface changes (F3).

In our build, add to the 1 Hz log (debug builds only, no addresses): quinn `conn.stats()` `path.rtt`, `path.cwnd`, `path.current_mtu`, `path.lost_packets`, `path.congestion_events`, `path.black_holes_detected`, `path.sent_plpmtud_probes`, `path.lost_plpmtud_probes`, `udp_rx`, `udp_tx`, and `datagram_send_buffer_space()`.

A/B on one device and one network, same Mullvad exit: Obscura (flags off) vs WireGuard; single-stream and 8-stream download/upload; loaded latency (ping during upload); Wi-Fi <-> LTE handover.

---

## 6. Proposed patch order for our rustlib fork

1. F1 coalescing when padding is on; honest UI text. (rustlib + UI)
2. F6 queue sizes + F7 `initial_mtu(1350)` + MTUD interval 60 s. (3 lines in quicwg.rs quic_config; plumb the small-MTU decision from net.rs)
3. F4 socket buffers. (net.rs)
4. F5 Android TUN batching + no lock across quinn calls. (android/tunnel.rs, quicwg.rs)
5. Diagnostics in the 1 Hz log.
6. F3 QUIC migration behind a flag, with fallback; test against real relays.
7. F8 TCP/TLS batching; F10 liveness tweak; CLAT-aware MTU in the Android shell.

Sketch for items 2-3:
```rust
// quicwg.rs quic_config()
transport_config.datagram_send_buffer_size(256 * 1024);
transport_config.datagram_receive_buffer_size(Some(512 * 1024)); // advertised value stays 65535
let mut mtu_discovery_config = MtuDiscoveryConfig::default();
mtu_discovery_config.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);
mtu_discovery_config.black_hole_cooldown(Duration::from_secs(10));
mtu_discovery_config.interval(Duration::from_secs(60));
transport_config.mtu_discovery_config(Some(mtu_discovery_config));
if !small_payload_mode { transport_config.initial_mtu(DEFAULT_UDP_PAYLOAD_SIZE); }

// net.rs new_socket(), before bind
let _ = socket.set_recv_buffer_size(4 << 20);
let _ = socket.set_send_buffer_size(2 << 20);
tracing::info!(rcvbuf = ?socket.recv_buffer_size(), sndbuf = ?socket.send_buffer_size(), "udp socket buffers");
```

Sketch for item 4 (android/tunnel.rs):
```rust
let mut pb = PacketBuffer::default();
loop {
    let Ok(mut guard) = fd_watcher.readable().await else { break };
    while let Some(buf) = pb.buffer() {
        match unistd::read(&fd_watcher, buf) {
            Ok(0) => break,
            Ok(n) => pb.commit(u16::try_from(n).unwrap_or(0)),
            Err(Errno::EAGAIN) => { guard.clear_ready(); break }
            Err(error) => { tracing::error!(message_id = "eagh6Noh", ?error, "failed to read from tun"); return }
        }
    }
    tunnel.send(pb.take_iter());
}
```

## 7. Rejected ideas
- Larger inner MTU: relay payload is fixed at 1350, so downlink would fragment; IPv6 needs >= 1280.
- Switching outer CC to Cubic/NewReno: collapses under random loss (measured).
- Disabling outer CC: no measured gain over quinn BBR for app-limited traffic; spec deviation.
- Fake ACKs to hide loss from the relay: breaks with quinn PN skipping; CC circumvention.
- Multiple parallel QUIC connections for multi-core crypto: needs relay support (one tunnel token per connection).
- Lower `min_mtu` floor changes or zero-length client CIDs: small or no gain, fingerprint change.

## 8. Reproduce the measurements
```
cd /tmp/obscura-research/perf-bench
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=example.com
openssl x509 -in cert.pem -outform DER -out cert.der
openssl pkcs8 -topk8 -nocrypt -in key.pem -outform DER -out key.der
cargo build --release --offline
./target/release/perf-bench crypto|timers|ceiling|bloat|loss|mtu|pad
```
The harness emulates the relay as quinn with fixed 1350 B payload and no MTUD; this is an assumption about the closed relay. Rate/loss/delay are emulated in a wrapper `AsyncUdpSocket` on the client uplink only.
