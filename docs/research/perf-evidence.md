# perf-evidence: why the Obscura app is slower than WireGuard (external evidence + upstream history)

Date: 2026-10-20. Scope: throughput, latency, battery only.
Labels: [V] = verified by me (code read, git history, primary doc). [R] = reported by a third party, not reproduced. [H] = hypothesis or estimate.

Research artefacts (kept for other agents):
- /tmp/obscura-research/history: full upstream history (`git clone --filter=blob:none`), 1411 commits, 2024-11-28 to 2026-10-19, HEAD 85ef7e2 (same as the shallow clone).
- /tmp/obscura-research/quinn-history: quinn-rs/quinn history plus the Obscura fork commit 8f690a2.
- /tmp/obscura-research/mullvad-app: sparse clone of mullvad/mullvadvpn-app (mullvad-masque-proxy only).
- /tmp/obscura-research/refs: RFC 9221/9298/9484 text, Privacy Guides thread dump (pg.json, pg_hits.txt).

## 1. Bottom line: most likely root causes (ranked)

| # | Cause | Direction | Evidence | Confidence |
|---|---|---|---|---|
| 1 | Nested congestion control with quinn's experimental, unmaintained BBR (v1 port) plus a 1 MiB drop-oldest datagram queue in front of it. Inner TCP sees up to 1 MiB of extra queue (0.84 s at 10 Mbit/s), RTT spikes, head drops, and liveness-ping loss. Plain WireGuard has no outer controller and no extra queue. | upload (client side); download if relay uses the same config | [V] code + [V] quinn source + [V] quinn maintainer statement + [R] MASQUE studies | high that it costs speed/latency; magnitude [H] |
| 2 | quinn fork bugs shipped in every Android release v1.162 to v1.181 (2026-06-05 to 2026-09-30): black-hole detector false positives in bulk transfer pin the QUIC MTU at 1200, so every full-size WireGuard packet (1312 B) is split into 2 QUIC datagrams. Fixed upstream in quinn-proto 0.11.18; Obscura adopted it in v1.182 (tag 2026-10-07). | both (client upload verified; relay unknown) | [V] git timeline + [V] quinn code + [R] quinn #2791 production numbers | high for that version window |
| 3 | Android UDP GSO hazards in quinn-udp (GSO is on for Android, Obscura never turns it off): EIO on cellular drops a whole GSO batch (up to 64 packets) per new connection; some vendor kernels break GSO silently; some kernels return EIO for the ECN cmsg so all sends fail (fix only in quinn-udp 0.6.2+). Obscura's own Cargo.toml says "quinn-udp 0.5.6 regressed upload speeds on Android". Their Linear ticket OBS-3465 is titled "android v1.15.9 unexpectedly reconnects; halting segmentation offload". | upload, reconnects | [V] code + [V] commit refs + [R] quinn #2042/#2145/#2246/#2399/#2718, quic-go #4446 | medium-high (device and carrier dependent) |
| 4 | Reconnect storms: liveness checker declares the tunnel dead after 5 probes are "lost" at a 1 s to 2xRTT deadline, but probes travel through the same congested 1 MiB queue (and can be head-dropped). Each reconnect = relay race, new QUIC + WG handshake, BBR restart, MTU discovery restart at 1200 (fragmentation), GSO re-probe. | both | [V] code + [R] user reports of drops/hangs + [V] upstream commits fighting this | medium [H] for the coupling |
| 5 | Optional "Enable packet padding" (default off). If on, every datagram with app data is padded to the MTU. Inner TCP ACKs grow from about 154 B to 1378 B on the wire, so a download needs uplink of about 50% of the download rate (vs about 5.6%). On a 10 Mbit/s uplink, download caps near 20 Mbit/s. UI text says only "may slightly increase bandwidth usage". | download | [V] code, numbers [H] arithmetic | high if the user enabled it |
| 6 | UDP socket buffers left at kernel default (no SO_RCVBUF/SO_SNDBUF). wireguard-go asks for 7 MiB. Small rcvbuf drops bursts at high download rates when the quinn driver task is late. | download | [V] code both sides; effect [H] | medium |
| 7 | Per-packet userspace cost: double AEAD, two heap copies per packet, one mutex for TX and RX, task hops through the quinn driver, one TUN syscall per packet, per-second INFO log line persisted to disk. quinn reaches about 1/3 of line rate where msquic reaches line rate (server test). Matters mainly above about 100 to 200 Mbit/s and for battery. | both | [V] code + [R] quinn #2262, Mullvad statement, Google/KIT QUIC CPU data | medium for fast Wi-Fi/5G, low for typical LTE |
| 8 | Battery: idle liveness ping every 55 s (WireGuard config has no keepalive), 1 Hz timer wakeups, QUIC ACK traffic, double crypto. | n/a | [V] code + [R] LTE tail-energy literature | medium [H] |

Not a differentiator if the user compared against Obscura's own WireGuard config: the topology (relay then Mullvad exit) and the TUN MTU (1280) are the same (section 2). Overhead bytes add only about 2.2% (section 5.4).

## 2. What "WireGuard" most likely meant, and why that isolates the QUIC layer

- [V] Obscura WireGuard configs are `TunnelConfig::UdpPort` tunnels that choose a relay and an exit: `obscuravpn-api/src/cmd/tunnel/create.rs:10-16` (`UdpPort { id, label, wg_pubkey, relay: Option<String>, exit: Option<String> }`) and `src/types.rs:404`. Founder statement: "Even when using the WireGuard config, you still benefit from our Two-Party Relay design" (Privacy Guides forum post #103, 2025-12-31).
- [V] Generated config has no `MTU =` and no `PersistentKeepalive` line: `obscuravpn-api/src/wg_conf.rs:4-21`.
- [V] The WireGuard Android app then uses MTU 1280: `builder.setMtu(config.getInterface().getMtu().orElse(1280));` (wireguard-android GoBackend.java). Obscura app TUN MTU is also 1280: `TUNNEL_MTU` (`rustlib/src/quicwg.rs:61`), passed at `client_state.rs:396`.
- [V] WireGuard Android (wireguard-go) uses UDP GSO/GRO with automatic fallback on EIO (`conn/errors_linux.go`: "EIO is returned by udp_send_skb() if the device driver does not have tx checksumming enabled") and requests 7 MiB socket buffers (`conn/controlfns_linux.go:55-59`, `socketBufferSize = 7 << 20`). Go treats GOOS=android as linux for these files.
- Result: same relay, same exit, same inner MTU. The differences are (a) the QUIC layer with its own congestion control, queue and MTU logic, (b) the Rust client data path. If instead the user compared with Mullvad direct, the extra hop adds RTT (BlockDYOR: 11 ms to 276 ms to Tokyo), which hurts TCP more on lossy links.

## 3. Verified upstream code facts (hot paths)

### 3.1 QUIC transport config, `rustlib/src/quicwg.rs:728-747`
```rust
let mut transport_config = quinn::TransportConfig::default();
...
let mut mtu_discovery_config = MtuDiscoveryConfig::default();
mtu_discovery_config.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);          // 1350
mtu_discovery_config.black_hole_cooldown(Duration::from_secs(10));
transport_config.mtu_discovery_config(Some(mtu_discovery_config));
transport_config.max_idle_timeout(Some(QUIC_IDLE_TIMEOUT.try_into()?));
transport_config.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
transport_config.pad_to_mtu(quic_frame_padding);
```
- No `datagram_send_buffer_size`, `datagram_receive_buffer_size`, `initial_mtu`, `min_mtu`, `enable_segmentation_offload` calls anywhere in `rustlib/src` (grep). So quinn defaults apply: send buffer `1024 * 1024`, receive buffer `STREAM_RWND` = 1.25 MB (`quinn-proto-0.11.19/src/config/transport.rs:395-396`), initial/min MTU 1200 (`lib.rs:332`), GSO on.
- BBR has been the controller since the first public commit (b7ff205, 2024-11-28). quinn-proto `congestion/bbr/mod.rs:19`: `/// Experimental! Use at your own risk.` BBR initial window = 200 x 1200 = 240,000 B (`bbr/mod.rs:647-648`).
- The quinn pacer uses only RTT and cwnd, never BBR's pacing rate (`connection/mod.rs:627-633`: `self.path.pacing.delay(smoothed_rtt, bytes_to_send, self.path.current_mtu(), self.path.congestion.window(), now)`).

### 3.2 Send path, `quicwg.rs:383-427` and `922-933`
```rust
fn send<'a>(&self, packets: impl Iterator<Item = &'a [u8]>) {
    let mut wg_state = self.wg_state.lock().unwrap();          // one Mutex for TX and RX
    if let Some(packet) = wg_state.liveness_checker.sent_traffic() {
        self.send_single_packet(&mut wg_state, &packet);      // ping rides the same queue
    }
    for packet in packets { ... self.send_single_packet(&mut wg_state, packet); }
}
...
let wg_message = Bytes::copy_from_slice(wg_message);          // heap copy per packet
let (first, second) = match self.wg_sender.max_wg_message_size() {
    Some(max_size) => wg_state.fragmenter.fragment(wg_message, max_size),
    None => (wg_message, None),
};
...
if let Err(error) = conn.send_datagram(wg_message) {          // never blocks, drops oldest
```
- [V] `quinn-0.11.12/src/connection.rs:445-448`: "Previously queued datagrams which are still unsent may be discarded to make space for this datagram, in order of oldest to newest." Receive side also drops: `quinn-proto datagrams.rs:145-148` (`"dropping stale datagram"`).
- [V] Android TUN reader: one `read()` per packet, then `tunnel.send(std::iter::once(&buf[..n]))` (`rustlib/src/android/tunnel.rs:28-37`). Receive: `conn.receive()` then `packet_for_os` then one `write()` per packet (`tunnel_state.rs:334-335`, `android/os_impl.rs:65-68`, `android/tunnel.rs:56-68`).

### 3.3 MTU budget
- [V] `quicwg.rs:61-66`: `TUNNEL_MTU = 1280`, `DEFAULT_UDP_PAYLOAD_SIZE = 1350`, `IPV4_UDP_OVERHEAD = 20 + 8`.
- [V] quinn max datagram = `current_mtu - (1 + dcid_len + 4 + 16) - 9` (`datagrams.rs:82-96`, `connection/mod.rs:3763-3776`, `frame.rs:902`). A full WireGuard data message for a 1280 B packet is 1312 B. At MTU 1350 it fits only if the relay CID is at most 8 B (quinn default is 8): zero margin. At MTU 1200 the limit is 1162 B, so every full-size packet becomes 2 datagrams (`fragmenter.fragment`, 8 B header each, `obscuravpn-api/src/wg_fragment/mod.rs:9-12`).
- [V] quinn MTU search from 1200 with upper bound 1350 and `minimum_change` 20 probes 1275, 1312, then 1350. If the path cannot carry 1350 (IPv4 path MTU below 1378 B), it stops below 1350 and every full-size packet is fragmented for the life of the connection.

### 3.4 UDP socket
- [V] `rustlib/src/net.rs:53-55`: `new_udp` creates the socket with no buffer sizing. No `SO_RCVBUF`/`SO_SNDBUF` anywhere in `rustlib/src`.

### 3.5 Liveness, `rustlib/src/liveness.rs`
- [V] `MAX_ALLOWED_LOST_PROBES = 4`, `BUSY_PING_PERIOD = 1 s`, `IDLE_PING_PERIOD = 55 s`, `MIN_PROBE_LOST_PERIOD = 1 s` (lines 10-23). Lost deadline = 2 x slowest RTT of the last 100 pongs, clamped 1 s to 30 s (lines 60-66). More than 4 lost returns `Dead` (lines 108-124), which tears down and reconnects.

### 3.6 Padding flag
- [V] Default off: `client_state.rs:540` `quic_frame_padding.unwrap_or(false)`. UI: "Enable packet padding", warning "Enabling this feature may slightly increase bandwidth usage" (`obscura-ui/src/translations/en.json:167-169`). quinn doc for `pad_to_mtu`: "Pad UDP datagrams carrying application data to current maximum UDP payload size ... it increases bandwidth usage" (`transport.rs:226-238`).

### 3.7 Logging and other per-packet costs
- [V] Every 1 s while connected, an INFO line with `tick_stats` (packet counts and sizes) is logged (`quicwg.rs:450-456`). On Android INFO goes to logcat and to a persisted ndjson file, 10 MB x 24 files, XZ (`android/ffi.rs:53`, `logging.rs:44-60,78-86`). Cost: wakeups and flash writes. Privacy side note: this is a local per-second traffic-volume trace.
- [V] Before v1.160 (commit 97fbbc8, 2026-05-09) the Android TUN path logged a warning per packet for "packet larger than MTU" and "writing packet to tun failed".
- [V] Release builds enable MTE async: `android/app/src/main/AndroidManifest.xml:20` `android:memtagMode="async"` (only active on MTE hardware with MTE enabled; small cost per heap allocation, and the data path allocates per packet).

## 4. Upstream git history: perf-relevant changes (from /tmp/obscura-research/history)

GitHub issues and discussions are disabled on the public repo (`hasIssuesEnabled:false`, `hasDiscussionsEnabled:false`); PR numbers in commits refer to the internal repo. Commit-message search terms: perf, speed, throughput, mtu, congestion, gso, bbr, buffer, slow, latency, quinn, quic, udp, batch, pacing, keepalive, datagram, queue.

| Date | Commit | Change | Perf relevance |
|---|---|---|---|
| 2024-11-28 | b7ff205 | Initial public commit already sets `BbrConfig` | Outer BBR from day 1 |
| 2024-12-13 | 531831e | Switch WireGuard to neptun (boringtun fork): saturating Duration math, fix "spurious rekeys killing the connection" (OBS-790, OBS-922) | Stability, not throughput |
| 2025-01-14 | 62f41d1 | QUIC idle timeout 5 s | Fast failover, more reconnects |
| 2025-04-17 | cebf3ed | Log detailed ip/wg/quic tx/rx stats | Diagnostics (origin of per-second log) |
| 2025-05-31 | 27728f9 | TCP/TLS tunnel (later feature flag fd2c574, 2025-10-15) | TCP-over-TCP if enabled; KB: "may reduce performance" |
| 2025-07-04 | 6f0e13b | QUIC datagram padding; switch to fork `Sovereign-Engineering/quinn` rev 8f690a2 | Fork based on quinn main of 2025-05-08 plus one commit; froze quinn fixes for 15 months |
| 2025-08-06 | d9088ca | Keepalives cut from every 1 s to: after 1 s unanswered TX, after 60 s idle, every 5 min with traffic | Battery, radio wakeups |
| 2025-09-25 | 160f8ea | Lower idle ping period (now 55 s) | Idle radio wakeups |
| 2025-12-02 | cd80bd4 | Basic Android VpnService integration | Android data path starts |
| 2025-12-18 | cd86d12 | Linux: don't append to full packet buffer | Linux only |
| 2026-02-22 | 49295c6 | "support small MTUs": WG fragmentation shim, `DEFAULT_UDP_PAYLOAD_SIZE = 1350`, `max_udp_payload_size(1200)` when interface MTU < 1378, fixed `initial_mtu=min_mtu=1350` otherwise | MTU handling |
| 2026-05-08 | c44edac | QUIC idle timeout 60 s to 600 s. Message: "mitigates the liveness checker timer issue on Android". Ref Linear "OBS-3465/android-v1159-unexpectedly-reconnects-halting-segmentation-offload" | Confirms Android GSO EIO events and reconnects in the field |
| 2026-05-09 | 97fbbc8 | Rate-limit per-packet log spam (Android TUN) | CPU/battery fix |
| 2026-05-21 | c2fb8b8 | "Enable client-side QUIC MTU discovery unconditionally": removes fixed `initial_mtu/min_mtu = 1350` | Now starts at 1200 and can fall to 1200 on black-hole detection: interacts with the fork's detector bug (item 2) |
| 2026-06-08 | f06330d | Log PMTU | Diagnostics |
| 2026-08-19 | 3c2022d | Windows: wintun ring capacity reduced to 8 MiB, "matches wireguard-go" | Windows only |
| 2026-08-28 | 9d32f54 | Limit QUIC flow-control windows to 4 KiB (control stream only) | Memory; datagrams are not flow controlled (RFC 9221 §5.3) |
| 2026-09-17 | 3615d1a | Wait for first liveness pong before "connected" | Connect UX |
| 2026-10-05 | 845d6c7 | Back to crates.io quinn 0.11.12, quinn-proto 0.11.19, quinn-udp 0.5.16; comment: "quinn-udp 0.5.6 regressed upload speeds on Android. This is fixed in 0.5.8 and later. https://github.com/quinn-rs/quinn/issues/2042" | Brings black-hole fix, ACK bundling, kernel GSO gate |
| 2026-10-12 | bb5c6e6 | Time out relay handshake setup steps | Connect time |

Release mapping [V] (`git merge-base --is-ancestor`): v1.160 (2026-05-18) has fixed MTU 1350; v1.162 (2026-06-05) to v1.181 (2026-09-30) have unconditional MTU discovery plus the fork; v1.182 (tag 2026-10-07, no GitHub release yet) has the quinn update. The Android app reached Google Play in May 2026, so most Android users ran v1.162 to v1.181. Release note v1.162: "Automatically detect and use client-to-relay MTU for QUIC connection." No release note mentions throughput.

What the fork lacked [V] (`merge-base` against 8f690a2): `81f9cd99` (2025-05-23, disable GSO on kernels < 4.18), `a43918cf` (2025-09-29, black-hole detector comparisons), `b0afb6e6`/`dcb9eabe` (2026-08-20, black-hole fix and 0.11.x backport), `912d648e` (2026-09-02, bundle ACK frames into DATAGRAM packets), datagram memory accounting fixes (2026-06/08). It did include `6b901a3c` (sanitise GSO `segment_size`, Android).

## 5. External evidence

### 5.1 quinn BBR (cause 1)
- [V] Ralith (quinn maintainer), quinn #2156, 2025-02-18: "Our BBR congestion controller is highly experimental and not currently maintained. It's known to be substantially behind the upstream BBR spec, and was never very well tested. As a result, you may experience excessive packet loss, adversely affect other network traffic, and/or fail to make full use of the link under some conditions."
- [R] quinn PR #2481 (BBRv3, still open): Ralith says the old BBR "hasn't received any attention in years".
- [R] moq-dev/moq #686 (kixelated, 2025-11-15): "Quinn has BBR support but it's horribly broken from every indication I've seen." Closed 2026-09 as superseded.
- [V] Mullvad's own QUIC (MASQUE) client uses quinn `TransportConfig::default()`, i.e. Cubic, and leaves datagram buffers at default (`mullvad-masque-proxy/src/client/mod.rs:151-164`, with "TODO: Set datagram_send_buffer_size if needed").

### 5.2 Nested congestion control and MASQUE/WARP measurements (cause 1)
- [V] RFC 9221 §5.4: "DATAGRAM frames employ the QUIC connection's congestion controller ... The sender MUST either delay sending the frame until the controller allows it or drop the frame".
- [V] RFC 9484 §10: "the proxied traffic will incur at least two nested congestion controllers. When tunneled packets are sent using QUIC DATAGRAM frames, the outer HTTP connection MAY disable congestion control for those packets that contain only QUIC DATAGRAM frames encapsulating IP packets."
- [V] RFC 9298 §6 (UDP proxying) is stricter: "MUST NOT disable congestion control unless it has an out-of-band way of knowing with absolute certainty that the inner traffic is congestion-controlled."
- [V] TUM, "Reliable VPNs with MASQUE" (Holzinger et al., ANRW 2026 slides): quiche-based MASQUE forwards about 650 Mbit/s max vs about 1,750 Mbit/s for WireGuard and OpenVPN on the same testbed; under random/burst loss "MASQUE shows delay, due to congestion control".
- [R] Kühlewind et al., "Evaluation of QUIC-based MASQUE proxying" (EPIQ 2021): nested congestion control is the main issue; datagram mode beats stream mode. TECC (NSDI 2024): moves CC to the tunnel to fix nested-CC mismatch. ns-3 LTE study (2023): datagram mode +4.5% completion time vs stream mode +30.9%.
- [R] Cloudflare WARP: community thread "1.1.1.1 WARP very slow but MASQUE is reason" reports about 35/11.4 Mbit/s with MASQUE vs 128/21 with WireGuard on iPhone/iPad (from search snippet; page returned HTTP 403 to direct fetch, so numbers not verified). usque README: QUIC userspace "making the implementation slower"; quic-go Reno gives slow ramp-up.
- [R] Mullvad on its QUIC mode: "This is computationally very expensive and affects throughput" (TechRadar, 2025-11); "no performance or privacy benefits" on open networks (mobile announcement, 2025-10).

### 5.3 quinn black-hole detector (cause 2)
- [R] quinn #2791 and backport PR #2799 (merged 2026-08-20): in bulk downloads at about 150 to 200 Mbit/s the MTU "dropped from the discovered 1452 down to min_mtu (1200) shortly after the transfer starts, and it stays there"; black-hole counter 3316 per connection vs 0 to 2 after the fix. Backport text: on 0.11.x "the connection stays pinned at the floor for the rest of a bulk transfer". Fix is in quinn-proto 0.11.18+ [V] (`git tag --contains dcb9eabe`).
- [V] quinn-proto 0.11.12 (fork base) contains the buggy `if len <= self.acked_mtu { return; }` in `on_non_probe_acked` (`mtud.rs`).
- [H] Effect on Obscura v1.162 to v1.181: congestion loss (BBRv1 tends to cause it) triggers a false black hole, MTU falls to 1200, every 1312 B WireGuard message becomes 2 QUIC packets: about 1442 B on the wire instead of about 1370 B (+5%), 2x packets, syscalls and ACKs, and per-packet loss probability about doubles (either fragment lost = packet lost). Obscura sets `black_hole_cooldown` to 10 s, so it re-probes and can fall again.

### 5.4 Overhead arithmetic (why bytes are not the main cause) [H]
Per 1280 B inner IPv4 packet (TCP MSS 1240): WireGuard app 1312 + 28 = 1340 B on wire (92.5% goodput). Obscura unfragmented about 1312 + about 30 QUIC + 28 = about 1370 B (90.5%). Difference about 2.2%. Fragmented (MTU 1200): about 1442 B (86%) and 2 packets.

### 5.5 Android UDP GSO/GRO (cause 3)
- [V] Kernel: UDP_SEGMENT since Linux 4.18 (commit cb586c63e3fc); UDP_GRO since 5.0 (commit e20cf8d3f1f7). GKI: Android 12+ devices shipping 5.10+ must use GKI; all current GKI branches (5.10 to 6.18) have both. Older devices on 4.14/4.19 vendor kernels remain.
- [V] quinn-udp enabled GSO and GRO on Android on 2024-11-16 (46acdea4, a694602c; release 0.5.8). quinn-udp 0.5.16 still enables both on Android (`unix.rs:811-858`, `1000-1027`), and only halts GSO after the first EIO/EINVAL (`unix.rs:345-360`).
- [R] quinn #2042 (2024-11-14): accidental GSO on Android; EIO "os error 5" on mobile data; thomaseizinger (Firezone): failures "only on mobile data", works on Wi-Fi.
- [R] quinn #2145 (2025-01-28, Android tunnel app over quinn): with quinn-udp 0.5.9 "what previously took 1-2 seconds ... took 5-8 seconds", logs "halting segmentation offload". Closed after segment_size sanitising (0.5.12).
- [R] quinn #2399 (open since 2025-09-25): sockets pass the GSO check then EIO on first send; the whole batch is dropped, quinn-udp "simply returns an error"; "Android + cellular" cited. Partial-send PR #2748 pending.
- [R] quinn #2246 (open): Galaxy A32 5G, Android 13, kernel 4.14.186: transfers over about 100 KB time out; `enable_segmentation_offload(false)` fixes it. quic-go #4446: OnePlus 7 Pro, kernel 4.14.180, GSO accepted but streams slow or stuck. quinn-udp 0.5.13+ disables GSO on kernels < 4.18 (81f9cd99); the Obscura fork did not have this.
- [R] quinn #2718 (2026-07-07, Pixel 9a, Android 17): some kernels return EIO for the ECN cmsg, fallback only on EINVAL, "no datagrams are ever sent". Fix 89837617 is only in quinn-udp 0.6.2+ [V]; 0.5.16 (current pin) lacks it. Reporter also hit an unrelated EPERM, so field impact is unclear.
- [V] Obscura note: the Cargo.toml comment ties an Android upload-speed regression to quinn-udp. The history shows 0.5.8+ enables GSO on Android on purpose, so "fixed in 0.5.8" refers to the accidental-GSO bug only. The open problems above apply to 0.5.16.
- [R] Firefox (Max Inden, 2025-09-14): Android rejects single-segment GSO; on API 25 and lower ECN sendmsg gives EINVAL; x86 Android uses socketcall.

### 5.6 Throughput ceilings and CPU (cause 7)
- [V] quinn #2262 (open, 2025-06-05): VM to VM, 10 ms RTT: iperf3 TCP 958 Mbit/s, msquic secnetperf 914 Mbit/s, quinn perf 324 Mbit/s average.
- [R] quinn #2153: 5G, 100 ms RTT, quinn 0.55 MB/s vs TCP 6.9 MB/s; cause flow-control windows (streams; not our datagram case, but shows defaults matter).
- [R] Google SIGCOMM 2017 / EPIQ 2020: QUIC about 2x CPU of TCP+TLS; UDP sendmsg up to 3.5x cycles/byte of TCP. KIT 2025 (König et al.): QUIC "remains noticeably lower than TCP's while demanding significantly higher CPU resources"; they raised UDP buffers because defaults "are known to be insufficient".
- [H] Crypto is not the limit on modern ARM (about 100 Mbit/s of double AEAD is a few % of one core). Per-packet syscalls, wakeups, copies and the shared mutex dominate.

### 5.7 Battery (cause 8)
- [R] Huang et al., MobiSys 2012: LTE RRC tail 11.576 s; tail radio power about 1060 mW vs 11 mW idle (RadioProphet, PAM 2014).
- [H] A ping every 55 s that wakes the radio keeps it in the high-power tail about 11.6/55 = 21% of idle time (2012 LTE timers; modern 4G/5G often use shorter timers and C-DRX, and Doze can defer). The WireGuard config has no keepalive, so idle cost is near zero.
- [R] Mullvad GotaTun (Rust, boringtun fork) on Android: users report "better speeds and lower battery usage" vs wireguard-go; no benchmarks published.

### 5.8 User reports and reviews (little hard data)
- [R] BlockDYOR (Italy to Tokyo): download 69.05 to 60.36 Mbit/s (-12.6%), upload 19.08 to 13.94 Mbit/s (-27%), ping 11 to 276 ms. Upload loses more, which fits client-side (upload) causes 1 to 3 [H].
- [R] Privacy Guides forum post #146 (JollyPirate1, 2026-08-04): "Even while on a single, stable connection, Obscura VPN intermittently stops working ... rushing products out (the Android app included)". App Store: "constantly disconnects ... When I leave WiFi and go to cellular data, it seems to hang."
- [R] Positive reports: kycnot.me ("you don't notice the two hops"), techbits.cc (July 2026, router WireGuard config plus Android app: "Speeds were fine"), AlternativeTo.
- No Reddit thread found (search engines and reddit JSON returned nothing usable). HN threads (43109903, 49807003) have no speed data; founder: "QUIC has less overhead than MASQUE by definition".

## 6. Quantified effects to expect [H]

- 1 MiB datagram queue drain time = 8.39 Mbit / uplink rate: 2 Mbit/s 4.2 s; 5 Mbit/s 1.7 s; 10 Mbit/s 0.84 s; 20 Mbit/s 0.42 s; 50 Mbit/s 0.17 s. During a bulk upload, all other tunnel traffic (DNS, web, calls) waits behind it. The liveness probes also wait; with a 1 s minimum deadline, 5 delayed or head-dropped probes cause a full reconnect.
- Padding on: uplink needed for download ACKs = about 50% of download rate (ACK per 2 segments: 1378 B per 2 x 1370 B) vs about 5.6% off.
- MTU pinned at 1200: +5% bytes vs unfragmented, 2x packets, about 2x inner loss rate.
- GSO EIO: up to 64 x 1350 B = about 86 KB lost per event, once per new QUIC connection on affected cellular links.

## 7. How to confirm on the user's device (no secrets needed)

- Obscura debug bundle or our client logs: per-second `tick_stats` has `wg_tx_fragmented_count`, `wg_rx_fragment_reassembled_count`, `max_udp_payload`, `max_wg_message` (`quicwg.rs:450-456`). Any non-zero fragment count during bulk transfer means cause 2 or a small path MTU.
- logcat: "halting segmentation offload" (cause 3), "liveness checker poll returning Dead" (cause 4), "error while sending quic datagram packet" (send errors).
- `adb shell cat /proc/net/snmp`: `Udp: RcvbufErrors SndbufErrors` before and after a speed test (cause 6).
- Check feature flags in app settings: "Enable packet padding" and "Use TCP/TLS" (causes 5 and TCP-over-TCP).
- A/B on the same relay and exit: Obscura app vs Obscura WireGuard config in the WireGuard app, upload and download, Wi-Fi and cellular, plus a loaded-latency test (ping during upload).

## 8. Implications for our client (speed without weaker privacy)

1. Offer a "WireGuard (UdpPort)" transport in our app, using the same API (`CreateTunnel::UdpPort` with relay and exit). Same two-party relay privacy per Obscura's own statement. Cost: observers see WireGuard, not HTTP/3, and no QUIC padding. Keep QUIC as the censorship mode. This directly reproduces what the user measured as fast.
2. For the QUIC mode: replace BBR with quinn's maintained Cubic (Mullvad's choice) or a tunnel-specific controller, and A/B test. RFC 9484 permits disabling CC for DATAGRAM-only packets; RFC 9298 is stricter. A light controller plus pacing is a defensible middle.
3. Cut the datagram send queue from 1 MiB to a latency target (for example 20 to 50 ms at the estimated rate, or 64 to 128 KiB), or use `send_datagram_wait` to push back into the TUN read loop so the kernel drops and inner TCP reacts early. Do not let liveness probes sit behind bulk data.
4. Pin quinn-proto >= 0.11.19 and quinn-udp >= 0.5.16; backport the quinn-udp EIO cmsg fix (#2719) and GSO batch retry (#2724/#2748), or disable GSO on cellular (`enable_segmentation_offload(false)` when the network has TRANSPORT_CELLULAR). Keep GRO.
5. Set UDP SO_RCVBUF/SO_SNDBUF (try 4 to 7 MiB like wireguard-go) before handing the socket to quinn.
6. MTU: keep TUN 1280, ensure the WG message never needs fragmentation on normal paths (start at 1350 when the interface MTU allows, as v1.160 did, but keep discovery for small paths), and alert on fragment counters.
7. Padding: keep the option (privacy), but state the real cost (about 50% uplink of download rate) in the UI; consider padding to size buckets instead of full MTU as a separate, reviewed trade-off.
8. Data path: per-packet buffer reuse (no `copy_from_slice` per packet), split TX/RX locking if the WireGuard API allows, batch TUN reads per wakeup, move the per-second tick log to DEBUG and do not persist traffic stats (also a privacy gain).
9. Battery: make idle liveness adaptive (no pings when the screen is off and no traffic, rely on QUIC path checks on network change).

## 9. Open questions (not verifiable from here)
- Relay side: congestion controller, datagram queue size, CID length, MTU logic, and whether it shares the fork bugs. Download speed depends on it.
- Which app version and settings the user ran, which carrier MTU, and whether the comparison was against Obscura's WireGuard config or Mullvad direct.
- Relay capacity and load (no public data).

## Sources
- Upstream: https://github.com/Sovereign-Engineering/obscuravpn-client (commits cited above), https://github.com/Sovereign-Engineering/obscuravpn-api, fork https://github.com/Sovereign-Engineering/quinn (rev 8f690a2)
- quinn: https://github.com/quinn-rs/quinn/issues/2042, /issues/2145, /issues/2153, /issues/2156, /issues/2246, /issues/2262, /issues/2399, /issues/2718, /issues/2791, /pull/2481, /pull/2747, /pull/2799, /issues/1899
- https://github.com/moq-dev/moq/issues/686
- https://github.com/quic-go/quic-go/issues/4446
- https://max-inden.de/post/fast-udp-io-in-firefox/
- RFC 9221 https://www.rfc-editor.org/rfc/rfc9221 ; RFC 9298 https://www.rfc-editor.org/rfc/rfc9298 ; RFC 9484 https://www.rfc-editor.org/rfc/rfc9484
- Kühlewind et al. EPIQ 2021 https://dl.acm.org/doi/10.1145/3488660.3493806 ; TECC NSDI 2024 https://www.usenix.org/system/files/nsdi24-zhang-jiaxing.pdf ; ns-3 LTE MASQUE https://dl.acm.org/doi/fullHtml/10.1145/3590003.3590995
- Holzinger et al. "Reliable VPNs with MASQUE" https://datatracker.ietf.org/meeting/126/materials/slides-126-anrw-measurement-reliable-virtual-private-networks-00
- König et al. KIT 2025 https://doc.tm.kit.edu/2025-Examining-the-Heterogeneous-Throughput-Performance-Landscape-of-QUIC-Implementations-Koenig-et-al.pdf
- Google QUIC CPU (EPIQ 2020) https://conferences.sigcomm.org/sigcomm/2020/files/slides/epiq/0%20QUIC%20and%20HTTP_3%20CPU%20Performance.pdf ; Langley et al. SIGCOMM 2017 https://dl.acm.org/doi/10.1145/3098822.3098842
- Cloudflare community https://community.cloudflare.com/t/1-1-1-1-warp-very-slow-but-masque-is-reason/924725 (403 on fetch) ; usque https://github.com/Diniboy1123/usque
- Mullvad: https://mullvad.net/en/blog/introducing-quic-obfuscation-for-wireguard ; https://www.techradar.com/vpn/vpn-services/mullvad-vpn-adds-ultra-fast-obfuscation-to-beat-wireguard-blocking ; https://www.cloudwards.net/news/mullvad-vpn-expands-quic-obfuscation-to-android-and-ios/ ; GotaTun https://www.phoronix.com/news/GotaTun-Rust-WireGuard-OSS
- wireguard-go https://github.com/WireGuard/wireguard-go (conn/bind_std.go, conn/errors_linux.go, conn/controlfns_linux.go) ; wireguard-android GoBackend.java
- Kernel: UDP GRO e20cf8d3f1f7 https://lwn.net/Articles/768995/ ; Android GKI https://source.android.com/docs/core/architecture/kernel/android-common
- Huang et al. MobiSys 2012 https://web.eecs.umich.edu/~zmao/Papers/RRC4G_mobisys2012.pdf ; RadioProphet https://web.eecs.umich.edu/~zmao/Papers/HuangPAM2014.pdf
- Reviews/reports: https://blockdyor.com/obscura-vpn-review/ ; https://kycnot.me/service/obscura-vpn ; https://techbits.cc/obscura-multihop-vpn/ ; https://discuss.privacyguides.net/t/obscura-vpn/26408 ; https://apps.apple.com/us/app/obscura-vpn/id6746820048 ; https://news.ycombinator.com/item?id=43109903 ; https://news.ycombinator.com/item?id=49807003 ; https://obscura.com/blog/bootstrapping-trust/ ; https://obscura.com/knowledge/troubleshooting-connection-issues/
