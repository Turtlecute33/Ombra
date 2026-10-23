# device-test: Ombra, the Obscura app and the WireGuard app on a phone (2026-10-22/23)

Phone: Pixel 9 Pro XL, Android 17, charging, screen on. Ombra 0.1.0 (release), Obscura v1.181,
WireGuard 1.0.20260315 with a WireGuard config from the Obscura portal. All three used the same entry relay and
Mullvad exits in the same city and data centre.

## Method

- A static Go probe run from `adb shell` (uid 2000, inside the VPN): HTTP/1.1 over TLS to speed.cloudflare.com
  (the PoP next to the exit), 6 s per test, 1 or 4 TCP streams. "Steady" drops the first 3 s.
- RTT: TCP handshake to the test host, 10 times idle, then every 250 ms during the transfer.
- CPU: utime + stime of the VPN process (`app.ombra:vpn`, `net.obscura.vpnclientapp:vpnservice`,
  `com.wireguard.android`) per GB moved.
- 3 rounds, all configs in random order each round. The apps were driven over adb with a guard that stops the
  run when another app has the focus.
- speed.cloudflare.com answers 429 after about 0.6 to 1 GB of downloads per IP within an hour. Those runs are
  left out; some cells have 2 rounds.

## Results (Wi-Fi 5 GHz, -49 dBm, median Mbit/s)

| Config | Down, 4 streams | Up, 4 streams | Down, 1 stream | Loaded RTT up p50/p95 | CPU-s/GB down |
|---|---|---|---|---|---|
| No VPN | 351 | 382 | 324 | 87/111 ms | - |
| WireGuard app | 281 | 198 | 324 | 29/44 ms | 50 |
| Ombra Speed (MTU 1420) | 135 | 209 | 180 | 24/43 ms | 47 |
| Ombra Stealth | 35 | 85 | 64 | 39/51 ms | 254 |
| Obscura app | 31 | 72 | 54 | 291/327 ms | 315 |
| Ombra Stealth + padding | 38 | 84 | 116 | 39/56 ms | 230 |
| Obscura app + padding | 30 | 76 | 58 | 341/975 ms | 329 |

## Findings

1. Port 53 (fixed in P6). On one Wi-Fi network the race picked UDP port 53 of the relay in 2 of 4 connects (also
   the Obscura app). There Stealth fell to 3 to 5 Mbit/s upload and 15 Mbit/s download, with a QUIC RTT of
   0.6 to 3.9 s at 40 to 300 packets per second. Ports 443 and 50000 of the same relay: 67 to 70 Mbit/s up.
2. Coalescing (fixed in P7). Without padding, each inner TCP ACK of a download was one QUIC packet (about 1600
   per second), and 15 to 40 of them per second were lost, each a congestion event. With padding on, the P4
   coalescer halved the uplink packets, the losses went to about 0 and one download stream doubled.
3. Stealth CPU, both apps: about one core during any transfer, about 70 % of it in the kernel. The relay sends
   no GRO-sized trains (`udp_rx_ios` = `udp_rx_datagrams`) and the 8 tokio workers switch context about 50 000
   times each. Open: fewer workers, UDP GRO.
4. Speed mode download: about half of the WireGuard app, without a full core in use. A fast path in the
   receive loop that skipped the per-message `select!` (two new timers per message) gave no gain on the phone
   (134/187 Mbit/s down with 4/1 streams, 135/180 before) and was reverted. The cause is still open.
5. Safety checks: IPv4, IPv6 and DNS (100.64.0.7, Private DNS off) go through the tunnel. With always-on and
   lockdown, a force-stopped Ombra let no traffic out for 30 s (Android does not restart a force-stopped
   always-on app). With Diagnostics off Ombra wrote 5 lines to logcat in a connect, none with secrets; the
   Obscura app logs relay IPs and tunnel IDs at INFO every second. Idle `:vpn` CPU: 0.19 s per minute.
6. `MainActivity` acted on `app.ombra.action.CONNECT` from any app (`am start` connected Ombra and pushed the
   WireGuard app aside). Now only through the not exported `ConnectAlias`.
7. Accepting the VPN consent of the Obscura app moved always-on and lockdown to it (system event, uid 1000)
   after they were off for Ombra. Check the always-on app after a switch between VPN apps.

## The fixes on the phone (0.2.0, same Wi-Fi, 2026-10-23, 1 to 2 runs each)

| Config | Down, 4 streams | Up | Down, 1 stream | 0.1.0 median (range) |
|---|---|---|---|---|
| Stealth | 37, 40 | 101, 76 | 88, 81 | 35 / 85 (80-94) / 64 (63-74) |
| Stealth + padding | 36 | 90 | 85 | 38 / 84 / 116 (84-148) |
| Speed | 134 | 194 | 187 | 135 / 209 / 180 |

P7 lifts one Stealth download stream by about 30 % and the upload a little; padded mode is unchanged, as
expected. In the interleaved run the 0.1.0 build gave 2 Mbit/s upload on the same exit, probably on port 53
(P6; with Diagnostics off the port is not logged). Open: 3 full interleaved rounds, the tokio worker count.

## Data path threads: P10 and P11 (2026-10-23, evening)

Another Wi-Fi network: 5 GHz 802.11ax, -51 dBm, link 541/573 Mbit/s. Same entry relay and exit city. Downloads from
`dl.google.com` (an NDK zip), because speed.cloudflare.com answered 429 after a few runs; uploads to
speed.cloudflare.com. Same Go probe: 8 to 10 s per test, steady part. A/B rounds interleave the builds (install, wait
for the tunnel, test). The network varied by up to 30 % between runs, so single runs mean little.

### What limited the download

- Speed mode, 0.2.0: 129 to 161 Mbit/s with 4 streams. Per run the UDP socket dropped 47 to 504 datagrams
  (`RcvbufErrors`), and the loaded RTT p95 was up to 1 s: the socket buffer of the relay socket filled up.
- One task did receive, WireGuard decryption and TUN write for each packet, in sequence. A TUN write runs the receive
  path of the inner IP stack (netfilter, TCP, the wakeup of the app) in the calling thread.
- CPU per packet was about the same as the WireGuard app; the WireGuard app spread it over about 1.8 cores.
- User-space profile (simpleperf on a build with `<profileable android:shell="true"/>`, not committed; kernel samples
  are not allowed): 55 % of Ombra's user CPU in Speed mode is `chacha20_poly1305_open` of ring (the ARMv8 assembly).
- The 4 tokio workers each ran at about 25 %, on little cores (0-3, 0.8 to 1.2 GHz) and middle cores (4-6, 1.5 to
  2.2 GHz). All of Ombra's native threads were SCHED_BATCH, inherited from the Kotlin thread that starts the core.

### Steps (Mbit/s, 4 streams unless stated)

| Build | Mode | Down | Down, 1 stream | Up |
|---|---|---|---|---|
| 0.2.0 | Speed | 129, 161 | - | - |
| + TUN writer thread, 4 workers | Speed | 283, 286, 285 | - | 182 |
| + TUN writer thread, 4 workers | Stealth + padding | 228, 230 | 191 | 91, 90 |
| + TUN writer thread, 3 workers | Stealth + padding | 248, 263 | 231 | 93, 91 |
| + TUN writer thread, 2 workers | Stealth + padding | 305, 281 | 250 | 117, 103 |
| + TUN writer thread, 1 worker | Stealth + padding | 283, 297 | 147, 253, 245, 264 | 197, 184 |
| + TUN writer thread, 1 worker | Speed | 313, 308 | 289 | 294, 305 |

Fewer workers were faster in every mode, and one worker used about half the CPU of four (0.94 against 1.6 cores in
Speed mode at 300 Mbit/s). Speed mode with 3 workers: 234 and 237 down, 179 and 167 up.

SCHED_NORMAL for the core threads: with nice 0 the TUN write queue (1024 packets) overflowed in 8 of the 5 s log
windows of six downloads, with nice -4 in 3, with nice -10 in 3 of twelve. The speed difference was within the noise.

### 0.2.0 against P10 + P11, Stealth with padding, interleaved, 2 rounds

| Build | Down, 4 streams | Down, 1 stream | Up | Idle RTT p50 | Loaded RTT p50, up |
|---|---|---|---|---|---|
| 0.2.0 | 46, 42, 40, 39 | 90, 95 | 94, 91, 95, 92 | 18 to 22 ms | 27 to 35 ms |
| P10 | 263, 214, 294, 308 | 263, 272 | 216, 198, 173, 226 | 19 to 31 ms | 21 to 27 ms |

P11 (a small message after a quiet moment does not wait, also with padding), 3 interleaved rounds of 3x20 TCP
handshakes: idle RTT p50 22.3 to 24.2 ms before, 18.8 to 20.4 ms after; p95 25.7 to 31.4 ms before, 20.5 to 26.2 ms
after. Wi-Fi uplink per MB downloaded: 34 to 40 kB before, 24 to 40 kB after.

### All on the same Wi-Fi, final build (one run each)

| Config | Down, 4 streams | Down, 1 stream | Up, 4 streams | Idle RTT p50 | Loaded RTT p50, up |
|---|---|---|---|---|---|
| No VPN | 240, 251 | 163 | 361, 363 | 11, 16 ms | 96, 93 ms |
| WireGuard app (Obscura portal config) | 183, 165 | 261 | 111, 114 | 16, 21 ms | 83, 88 ms |
| Ombra Speed (MTU 1420) | 310, 288 | 189 | 280, 275 | 16, 20 ms | 36, 37 ms |
| Ombra Stealth + padding | 270, 300 | 159 | 177, 209 | 16, 20 ms | 20, 23 ms |

Earlier the same evening the WireGuard app gave 221 and 223 down and 191 with one stream, and no VPN 301 to 313
down. The downloads of all configs are at the limit of this Wi-Fi; the uploads are not.

### Tried and dropped

- UDP GRO and `recvmmsg` in Speed mode (quinn-udp `UdpSocketState` on the relay socket, 4 messages of 96 KiB per
  call). GRO worked (about 18 datagrams per receive), but Ombra's CPU per MB (with GRO 2.5 to 2.6 ticks, without 2.3 to 2.5) and the system
  softirq time did not change: decryption and TUN writes cost more than the receive calls.
- A TUN reader thread instead of the tokio task. Stealth upload fell (149 to 178 against 193 to 210 Mbit/s), because
  each batch must wake the quinn task on the worker; Speed mode did not gain (247 to 281 against 267 to 292).

### Open

- Stealth upload is 49 to 58 % of the line, Speed upload about 77 %. Neither the worker (60 to 66 %) nor the TUN writer
  (46 to 55 %, it runs the inner TCP send path after each ACK) is at a full core.
- Ombra's Settings showed Always-on VPN and Block connections as Off while Android had both on for Ombra. The values
  are read at a service start, when the UI attaches and on wake, not when they change in Android's settings while the
  UI stays attached.

## Official app against Ombra 0.2.1 (2026-10-23, same Wi-Fi as above)

Official Obscura app v1.181 and Ombra 0.2.1 (P10 + P11). The official app chooses its entry relay at each connect;
it was connected again until it used the same relay as Ombra (for the padding run it used another relay, so its RTT
is left out). One run per row, 8 s per test.

| Config | Down, 4 streams | Down, 1 stream | Up, 4 streams | Idle RTT p50 | Loaded RTT p50, up |
|---|---|---|---|---|---|
| Official app, default (padding off) | 29, 30 | 39 | 93, 83 | 16, 20 ms | 109, 249 ms |
| Official app, padding on (other relay) | 28, 30 | 43 | 74, 80 | - | - |
| Ombra Stealth with padding, run 1 | 290, 232 | 253 | 167, 196 | 17, 20 ms | 24, 24 ms |
| Ombra Stealth with padding, run 2 | 254, 286 | 283 | 298, 282 | 15, 20 ms | 56, 41 ms |
| Ombra Speed (MTU 1420) | 266, 272 | 267 | 276, 245 | 16, 20 ms | 51, 52 ms |

CPU time per GB downloaded: the official app 315 s (first test above, another network), Ombra 0.2.1 about 23 s in
Speed mode and about 25 s in Stealth (from the per-thread CPU of the runs above).
