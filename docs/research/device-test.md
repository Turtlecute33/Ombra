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

## Review fixes on the phone (0.2.10 code, 2026-11-05, Pixel 9 Pro XL, Stealth with padding, always-on + lockdown)

Estimates, one to three runs each.

- Update over the release install: Android restarted the always-on VPN; the VPN network was up again after about 6 s.
  No crash in the log.
- Cold start of the UI process (`am start -W`, `:vpn` running, AOT `speed`): about 435 ms (431-438, 3 runs). Fonte on
  the same phone: about 490 ms. The 300 ms goal of DESIGN is not met by this metric on this phone; the other Compose
  apps do not meet it either.
- Locations list fling: 0.4 % janky frames, 99th percentile 9 ms.
- Rotation on Connect: the live rates go on (PERF-HARDENING-8). Rotation on a settings page keeps the page; after the
  UI process is killed, the app opens on Connect (UI-UX-10).
- Wi-Fi off, then on (Stealth): the VPN network stayed the same (no new `establish()`); pings stopped for about 2 s at
  Wi-Fi off and not at all at Wi-Fi on. This was a QUIC rebind, so the kept-TUN reconnect (VPN-INTEGRATION-1) did not
  run; only the Rust test covers it.

## All configs again on a faster line (Ombra 0.2.10, 2026-11-08)

Pixel 9 Pro XL, Android 17, charging, screen on. Another Wi-Fi network: 5 GHz 802.11ax, -58 dBm, link 864/1729
Mbit/s. Without a VPN the line gave about 1.2 Gbit/s down and 600 Mbit/s up. Ombra 0.2.10 (release), the official
app v1.185, the WireGuard app 1.0.20260315 with the Obscura portal config. All configs used Mullvad exits in the same
city. Ombra and the WireGuard app used an entry relay in the same country. The official app chose its relay at each
connect.

### Method

- The same Go probe (rebuilt), 10 s per test, "steady" drops the first 3 s. Downloads: an NDK zip from
  `dl.google.com`, with other NDK zips and then Cloudflare `__down` as fallbacks. Uploads: Cloudflare `__up`.
  RTT: TCP handshake to speed.cloudflare.com, idle and every 250 ms during the transfer.
- 3 rounds, all 8 configs in random order each round. CPU: utime + stime of the VPN process (for the WireGuard
  app, its only process).
- Always-on and lockdown were off for the test, and on again for Ombra after it.

### Results (median of 3 runs, Mbit/s; the No VPN and WireGuard downloads have 4 runs)

| Config | Down, 4 streams | Down, 1 stream | Up, 4 streams | Idle RTT p50 | Loaded RTT p50, down / up | CPU-s/GB down | CPU-s/GB up |
|---|---|---|---|---|---|---|---|
| No VPN | 1201 | 445 | 607 | 16 ms | 36 / 67 ms | - | - |
| WireGuard app | 292 | 440 | 224 | 25 ms | 74 / 40 ms | 43 | 67 |
| Ombra Speed (MTU 1420) | 405 | 333 | 392 | 25 ms | 35 / 37 ms | 20 | 26 |
| Ombra Stealth | 440 | 327 | 168 | 28 ms | 37 / 26 ms | 19 | 52 |
| Ombra Stealth + padding | 400 | 323 | 166 | 25 ms | 37 / 27 ms | 19 | 54 |
| Ombra TCP/TLS | 476 | 391 | 418 | 25 ms | 41 / 48 ms | 20 | 27 |
| Official app, padding off | 30 | 51 | 70 | 21 ms | 70 / 469 ms | 224 | 150 |
| Official app, padding on | 28 | 39 | 74 | 27 ms | 137 / 428 ms | 276 | 146 |

Single runs, down 4 streams / down 1 stream / up 4 streams:

- No VPN: 1208, 1193, 1213, 925 / 485, 529, 405, 384 / 598, 623, 607
- WireGuard app: 289, 296, 299, 282 / 420, 440, 440, 497 / 224, 231, 224
- Ombra Speed: 405, 381, 428 / 341, 333, 269 / 388, 445, 392
- Ombra Stealth: 440, 349, 457 / 383, 308, 327 / 173, 160, 168
- Ombra Stealth + padding: 384, 428, 400 / 286, 363, 323 / 166, 189, 164
- Ombra TCP/TLS: 530, 476, 461 / 391, 434, 365 / 418, 363, 444
- Official app, padding off: 31, 24, 30 / 51, 36, 61 / 75, 4, 70
- Official app, padding on: 29, 5, 28 / 39, 6, 52 / 74, 4, 88

### Findings

1. Ombra downloads with 4 streams are faster than the WireGuard app in all modes (400 to 476 against 292 Mbit/s),
   with a lower loaded RTT (p95 47 to 113 ms against 141 to 256 ms). With 1 stream the WireGuard app is faster
   (440 against 323 to 391 Mbit/s).
2. Stealth upload stays at about 160 to 190 Mbit/s, with or without padding. On the slower line of 2026-10-23 it was
   177 to 209 Mbit/s. Thus this is a limit of the Stealth path, not of the line: about 27 % of this line. Stealth
   uses about 2 times the CPU per GB uploaded of Speed and TCP/TLS (52 to 54 s against 26 to 27 s). Speed and TCP/TLS
   upload at about 65 to 70 % of the line.
3. TCP/TLS was the fastest mode on this line (476 down, 418 up), with a higher loaded RTT (48 ms during uploads).
   VERIFY: a possible cause is that the outer TCP flow gets kernel offloads (TSO, GRO) and kernel congestion control.
4. Padding had no measurable cost on this line (Stealth against Stealth + padding: in the noise).
5. Official app v1.185: about 30 Mbit/s down and 70 up, 11 to 15 times the CPU per GB downloaded of Ombra, and
   430 to 470 ms loaded RTT during uploads. It chose UDP port 53 on 2 of 6 connects: 4 to 6 Mbit/s and about 2 s
   loaded RTT (finding 1 of 2026-10-22; Ombra avoids port 53 since P6). On 1 connect it chose a relay in another
   country (idle RTT 86 ms). Its medians include these runs.
6. One of the three exit servers in the exit city got `404` from `dl.google.com` for all NDK files (IPv4 and IPv6);
   Cloudflare worked through it. Ombra chose that exit on 2 of 6 test connects. The cause is on the exit side, not in
   Ombra. Those runs were done again through another exit.
7. With lockdown off in Android settings, the Ombra Connect page still said "Android blocks all traffic until Ombra
   connects again". This is the stale Always-on and lockdown display that is open since 2026-10-23.

Not tested: mobile data, other phones.

## Losses in the data path (0.2.12, 2026-11-10)

Pixel 9 Pro XL, Android 17, charging, Stealth with padding, always-on and lockdown on. Another Wi-Fi network: 5 GHz
802.11ax, -59 dBm, link 1152/1921 Mbit/s. The line without a VPN was not measured. All numbers are estimates.

### Method

- The same Go probe, 10 s per test, "steady" drops the first 3 s. Kernel counters before and after each test:
  `Tcp: RetransSegs` and `Udp: RcvbufErrors` (`/proc/net/snmp`), the drops of the TUN device (`/proc/net/dev`).
- A test build (not committed) logged each second: quinn's path stats, the packets that quinn's datagram send queue
  dropped, the drops and the longest length of the TUN write queue, the time of each TUN write. A system property
  switched each change on and off in the installed build, so most comparisons ran on one connection.
- A new connection can get another exit server of the city, and the exits differ. The last table installs the old
  and the new build in turn, 3 rounds.

### Findings

1. Upload, old build: the QUIC path lost 0 packets and had 0 congestion events. The kernel counted 182 TCP
   retransmits in 10 s, and quinn's datagram send queue (256 KiB) dropped 181 packets. Thus all upload losses were
   drops of that queue. The worker thread used about 55 % of a core and the TUN writer about 45 %.
2. Cause 1, the TUN reader (fixed, P24): after a batch of 100 packets the TUN is still readable and the reader task
   read the next batch at once. quinn sends at most 20 datagrams each time its connection task runs. With a yield
   after a full batch the queue dropped nothing between the events of finding 3.
3. Cause 2, quinn's BBR (open): at the start of each upload and every 10 s after that, the window fell to about 50
   to 140 KB for 200 ms or more (ProbeRTT with a stale bandwidth estimate, quinn-proto 0.11.19). The queue dropped
   130, 975 and 1236 packets at 0, 10 and 20 s of a 25 s upload, and the rate fell from about 350 to about 210
   Mbit/s each time.
4. No congestion controller in place of BBR (a window of `u64::MAX`), with the yield: on Wi-Fi the upload with 4
   streams was about 300 Mbit/s and flat from the first second (1 stream about 215). On mobile data (uplink about
   20 Mbit/s) the ping during an upload was 1.2 to 2.2 s in 3 of 4 runs, against 0.2 to 0.3 s with BBR, also with
   a UDP send buffer of 128 KiB. BBR keeps the queue in front of the radio short. Thus BBR stays.
5. Download, old build: the TUN write queue (1024 packets) dropped 2500 to 7000 packets in 10 s, while the writer
   thread used about 40 % of a core. Single TUN writes took up to 15 ms (10 to 30 writes each second took 1 ms or
   more): the writer waited for a CPU about 36 % of the time it wanted to run. Packets also come in bursts: up to
   1500 in 10 ms, against about 400 on average.
6. Queue length, one connection, 3 rounds, download with 4 streams (Mbit/s; ping during the download p50/p95):

   | Queue | Runs | Drops in 10 s | Ping |
   |---|---|---|---|
   | 1024 | 339, 398, 424 | 3800 to 7200 | 36-43 / 65-74 ms |
   | 2048 | 559, 519, 447 | 1800 to 5800 | 43-46 / 67-100 ms |
   | 4096 | 633, 577, 445 | 0, 0, 750 | 44-55 / 65-122 ms |
   | 8192 | 719, 456, 454 | 0 | 42-45 / 67-79 ms |

   The longest queue was about 3700 packets, so 4096 is sufficient (P25).
7. Idle ping, sparse traffic: with one ping each 2 s, most pings took about 75 ms; with one each 0.2 to 0.5 s, 25
   to 28 ms. Speed mode gave the same. The Wi-Fi packet counters show the cause: after about 1 s without traffic,
   the first packet left the phone 36 to 40 ms after Ombra sent it (the Wi-Fi power save of the phone). Ombra
   cannot change this. One part was Ombra's: the first packet after a second of silence waited 2 ms behind its
   liveness probe (P26).

### Old against new build (Wi-Fi, interleaved, 3 rounds, Mbit/s steady)

Both builds have BBR. The old build is the 0.2.11 code.

| Test | 0.2.11 | 0.2.12 | Median |
|---|---|---|---|
| Upload, 4 streams | 134, 144, 176 | 263, 219, 287 | 144 -> 263 |
| Upload, 1 stream | 105, 72, 107 | 144, 127, 143 | 105 -> 143 |
| Download, 4 streams | 370, 403, 488 | 487, 627, 491 | 403 -> 491 |
| Download, 1 stream | 320, 200, 356 | 301, 456, 467 | 320 -> 456 |
| CPU-s/GB, upload, 4 streams | 62, 53, 51 | 38, 45, 35 | 53 -> 38 |
| CPU-s/GB, download, 4 streams | 20, 16, 17 | 16, 17, 16 | no change |
| Idle ping p50 | 25, 30, 29 ms | 25, 29, 28 ms | no change |
| Ping during upload, 4 streams, p50 / p95 | 26/35, 26/34, 27/36 ms | 31/43, 29/42, 31/44 ms | +4 / +8 ms |
| Ping during download, 4 streams, p50 / p95 | 35/63, 35/68, 36/57 ms | 41/71, 39/67, 36/59 ms | +4 / +4 ms |
| Ping during download, 1 stream, p50 / p95 | 35/57, 31/60, 33/58 ms | 32/57, 34/57, 39/75 ms | no change |

An earlier run of the same comparison (the new build then also without BBR) gave downloads of 325, 500, 328 against
547, 556, 646 Mbit/s with 4 streams and 340, 363, 334 against 450, 468, 450 with 1 stream.

In the new build the upload losses that remain between the BBR events are drops of the kernel TUN queue (500
packets, the newest packet): 0 to 770 in 10 s. During downloads the kernel UDP receive buffer overflows more often
at the higher rate (375 to 1560 `RcvbufErrors` in 10 s against 0 to 340).

Speed mode as a check (build with the longer queue and the yield): upload 358 and 380, download 435 and 506
(4 streams), 359 (1 stream).

### Mobile data (LTE, uplink about 20 Mbit/s, upload with 4 streams, one or two runs each)

| Build | Upload | Ping during the upload p50 / p95 | TCP retransmits |
|---|---|---|---|
| 0.2.11 code | 20, 22 Mbit/s | 212/234, 334/432 ms | 431, 1816 |
| 0.2.12 | 16 Mbit/s | 223/316 ms | 210 |
| Without BBR | 23 Mbit/s | 1183/2186 ms (5 s run: 1121/2139 ms) | 4967 |
| Without BBR, UDP send buffer 128 KiB | 15 Mbit/s | 1363/2493 ms | 3078 |
| Without BBR, UDP send buffer 512 KiB | 14 Mbit/s | 93/157 ms | 4 |

The idle ping on mobile data was about 54 ms. The runs differ much; the last row shows it.

### Tried and not kept

- No congestion controller in place of BBR: finding 4.
- The worker and the TUN writer on the CPUs 4 to 6 (`sched_setaffinity`): downloads in the noise, uploads not faster.
  The network receive softirq of this phone runs on the CPUs 4 and 5.
- A wait for room in the TUN write queue (the packets then wait in quinn's receive buffer, which drops the oldest):
  about 11 % faster downloads, less than the longer queue.
- A block of the worker thread when the TUN write queue is full: as fast as the longer queue (500 to 660 Mbit/s),
  but the worker then reads no packets from the TUN, and the kernel TUN queue dropped 500 to 1700 upload packets
  in 10 s.
- A UDP receive buffer of 1 MiB with that block: 190 to 260 Mbit/s.

### Open

- quinn's BBR at the start of an upload and every 10 s (finding 3). A fix must keep the short queue on a slow uplink.
  Fixed on the same day, see the next section (P27).
- Speed mode on mobile data is not tested. It has no congestion control and a UDP send buffer of 2 MiB.

Not tested: other phones, TCP/TLS mode, downloads on mobile data.

## More relays, BBR without ProbeRTT, receive buffer (0.2.12, 2026-11-10, afternoon)

The same phone, Wi-Fi network and settings as the section above (link about 1200/2100 Mbit/s, -57 dBm). All
numbers are estimates, Mbit/s "steady" (the first 3 s dropped), 10 s per test if not stated.

### Method

- "Old" is the 0.2.11 code and "morning build" is P24 to P26. Both were installed in turn (2 rounds) on four pairs of
  entry relay and exit, which the Locations screen set.
- A test build (not committed) had a copy of quinn's BBR with a log of its state each second, and system properties
  that switched ProbeRTT off and set the size of the UDP receive buffer. Thus the BBR comparisons ran on one
  connection.
- The upload host sometimes stopped to read a request (always at 10 747 904 bytes; the kernel counted zero-window
  probes and no timeouts). This came more often with a fast start of the upload. The probe now cancels a request
  that moves no byte for 1.5 s. On mobile data this watchdog must be off: it cancels slow, correct requests.

### Old against morning build on four pairs (2 rounds each)

| Entry / exit | Upload, 4 streams | Download, 4 streams | Download, 1 stream | Upload, 1 stream |
|---|---|---|---|---|
| Zürich / Milan | 158, 202 -> 215, 228 | 367, 354 -> 544, 521 | 263, 313 -> 426, 424 | 87, 67 -> 106, 183 |
| Frankfurt / Frankfurt | 156, 139 -> 62, 189 | 314 -> 349 | 292 -> 352 | 70, 68 -> 17, 92 |
| Milan / Zurich | 135, 192 -> 244, 258 | 355, 408 -> 510, 523 | 330, 317 -> 444, 327 | 97, 114 -> 39, 143 |
| Atlanta / Atlanta (ping about 130 to 200 ms) | 84, 52 -> 119, 79 | 266, 304 -> 350, 273 | 167, 163 -> 197, 201 | 18, 36 -> 54, 30 |

- The morning build was faster in 13 of the 16 averages.
- Frankfurt: the uploads of 62 and 17 came on one connection (relay FRA-002, exit de-fra-wg-404). A second
  connection to this pair gave 57 and 22 with the test build, and its log showed a BBR window of 1 to 50 MB with
  about 0.3 MB in flight. Thus Ombra did not limit these uploads. In round 2 the downloads gave no result with both
  builds (exit de-fra-wg-405).
- The upload with 1 stream changed much from run to run with both builds. The cause is the next finding.

### quinn's BBR (P27)

1. The bandwidth estimate of quinn's BBR was 31 to 38 Mbit/s while the path carried about 300. quinn calls `on_ack`
   for each packet of an ACK frame with the same time, so a sample is one packet divided by the time between two
   ACK frames.
2. Outside ProbeRTT this BBR did not limit the fast path: its window was 1 MB to 1 GB (the term for ACK aggregation
   grows when the estimate is too low).
3. In ProbeRTT the window is 0.75 times the estimate times the minimum RTT: about 60 KB, for 200 ms or more, at the
   first ACK that is not app-limited (the start of each upload after a quiet time) and every 10 s after that. An
   upload with 4 streams started at 60 to 80 Mbit/s and needed about 9 s to come to 300.
4. Stock BBR against the same code without ProbeRTT, on one connection:

   | Entry / exit | Upload, 1 stream | Upload, 4 streams |
   |---|---|---|
   | Zürich / Milan, 3 rounds | 148, 129, 141 -> 240, 191, 244 | 250, 192, 227 -> 385, 332, 290 |
   | Frankfurt / Frankfurt, 2 rounds of 12 s | 113, 92 -> 183, 170 | 244, 276 -> 342, 342 |
   | Milan / Zurich, 2 rounds of 12 s | 115, 100 -> 161 (one run stopped by the upload host) | 266, 287 -> 325, 281 |
   | Atlanta / Atlanta, 3 rounds | 58, 57, 50 -> 89, 59, 62 | 112, 74, 164 -> 139, 160, 70 |

   Without ProbeRTT, an upload with 4 streams ran at 280 to 410 Mbit/s from the first second (Zürich / Milan).
   Each TCP retransmit was then a drop of the kernel TUN queue (500 packets): the phone is the limit. Downloads with
   4 streams, 4 runs each in changed order: 662, 531, 605, 627 with stock BBR and 609, 668, 660, 566 without ProbeRTT.
5. Mobile data (uplink about 25 Mbit/s, idle ping about 49 ms), upload with 4 streams, one connection, in turn:

   | | Upload | Ping during the upload p50 / p95 |
   |---|---|---|
   | Stock BBR, 2 runs of 8 s | 23, 28 | 406/482, 507/1512 ms |
   | Without ProbeRTT, 2 runs of 8 s | 27, 23 | 417/533, 631/690 ms |
   | Stock BBR, 3 runs with the probe watchdog on | 27, 20, 29 | 923/2297, 650/727, 500/607 ms |
   | Without ProbeRTT, 3 runs with the probe watchdog on | 13, 25, 25 | 1246/2208, 467/625, 457/518 ms |

   No difference that is larger than the change from run to run. The ping was higher than in the morning (0.2 to
   0.3 s) for both. The test used about 456 MB of mobile data.

### UDP receive buffer (P28)

Zürich / Milan with one exit server, a new connection for each run, 4 and 8 MiB in turn, download with 4 streams:

| Buffer | Runs | Median | `RcvbufErrors` in 10 s | Ping during the download p50 / p95 (median) |
|---|---|---|---|---|
| 4 MiB | 434, 487, 543, 508, 548, 312, 364, 416 | about 460 | 300 to 2080 | 44 / 77 ms |
| 8 MiB | 578, 659, 372, 650, 653, 549, 411, 608 | about 590 | 0 to 390 | 45 / 102 ms |

8 MiB was faster in 7 of the 8 pairs. Download with 1 stream, 3 runs each: 389, 443, 484 against 449, 419, 437 (no
change). 8 MiB is the `rmem_max` of this phone.

### 0.2.11 code against the final build (P24 to P28), Zürich / Milan, interleaved, 3 rounds

| Test | 0.2.11 | 0.2.12 | Median |
|---|---|---|---|
| Upload, 4 streams | 166, 186, 172 | 357, 295, 361 | 172 -> 357 |
| Upload, 1 stream | 99, 89, 133 | 167, 177, 262 | 99 -> 177 |
| Download, 4 streams | 322, 412, 294 | 678, 573, 687 | 322 -> 678 |
| Download, 1 stream | 325, 361, 309 | 421, 486, 479 | 325 -> 479 |
| CPU-s/GB, upload, 4 streams | 55, 49, 53 | 28, 33, 29 | 53 -> 29 |
| CPU-s/GB, download, 4 streams | 22, 19, 22 | 14, 16, 15 | 22 -> 15 |
| Idle ping p50 | 29, 29, 29 ms | 30, 25, 28 ms | no change |
| Ping during upload, 4 streams, p50 / p95 | 26/39, 26/34, 26/35 ms | 31/41, 31/45, 33/42 ms | +5 / +7 ms |
| Ping during download, 4 streams, p50 / p95 | 35/63, 36/79, 32/67 ms | 38/65, 44/82, 51/91 ms | +9 / +15 ms |
| Ping during download, 1 stream, p50 / p95 | 34/54, 32/59, 34/54 ms | 36/72, 41/91, 37/62 ms | +3 / +18 ms |

The exit was a city, so each connection got one of three exit servers. With the final build the kernel counted no
`RcvbufErrors`, and each TCP retransmit of an upload was a drop of the kernel TUN queue.

### Tried and not kept

- A small controller of our own in place of BBR (window = the largest delivery rate of the last 8 round trips times
  the minimum RTT plus 0.1 s; also 4 times the minimum RTT): about equal to BBR without ProbeRTT near the relay, but
  on the Atlanta pair uploads stayed at 35 to 47 Mbit/s against 60 to 160. The window follows the rate, and the
  inner TCP then does not grow.
- A wrapper around quinn's `Bbr` that hides ProbeRTT: not possible. The 10 s timer of `Bbr` starts again only when
  ProbeRTT starts, and without the "app-limited" flag `Bbr` never leaves Startup.

### Open

- The bandwidth estimate of quinn's BBR (finding 1) is not corrected. It decides the window on a slow uplink.
- Relay FRA-002 with exit de-fra-wg-404: slow uploads, cause not known (not Ombra's congestion control).
- Speed mode on mobile data is not tested.

Not tested: other phones, TCP/TLS mode, downloads on mobile data, P28 on mobile data.
