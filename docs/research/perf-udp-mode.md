# perf-udp-mode: plain WireGuard over UDP vs WireGuard-in-QUIC (Obscura)

Revisions read:
- obscuravpn-client 85ef7e2 (2026-10-19), `/tmp/obscura-research/obscuravpn-client`
- obscuravpn-api e94483e (pinned rev, 2026-10-16), `/tmp/obscura-research/obscuravpn-api`; local cargo checkout 2ca8fb7 (2026-07-28). Tunnel code is identical in both, except `type Auth = AuthRequired` was added.
- neptun ff3b555 (2024-12-12), `~/.cargo/git/checkouts/boringtun-896ba2793962ccef/ff3b555`
- quinn 0.11.12 / quinn-proto 0.11.19 / quinn-udp 0.5.16 (from rustlib Cargo.lock)
- Clones for this note, all in `/tmp/obscura-research/perf-udp-mode/`: wireguard-go 2631ce9 (2026-10-06), wireguard-android e7b3a3c (2026-03-17), gotatun 1d46b77 (0.10.2, 2026-10-20), and a loopback benchmark crate in `bench/`.

Tags: [V] = verified in code or primary source. [H] = hypothesis or inference, not verified.

## TL;DR

1. [V] The API has a second tunnel type, `CreateTunnel::UdpPort`, that gives you a standard WireGuard config. It is the same thing as "Manage WireGuard configs" in the Obscura user portal, which is now documented for Android through the official WireGuard app. rustlib never uses it: it only creates `Obfuscated` (QUIC or TCP/TLS) tunnels.
2. [H, strong] UDP mode is still two-hop. Your phone sends WireGuard UDP to an Obscura relay on a port given to that tunnel. The relay forwards it to a Mullvad exit. The WireGuard session is end-to-end with Mullvad, the same as in QUIC mode. The privacy model (relay knows who, exit knows what) does not change. Before shipping, confirm this with one live tunnel (method in section 1.3).
3. [V] Per packet, QUIC mode adds a second AEAD, a QUIC state machine, QUIC ACK traffic, an outer congestion controller (quinn BBR, marked "Experimental! Use at your own risk."), a 1 MiB drop-oldest datagram queue, and WireGuard fragmentation when the QUIC MTU is below 1350.
4. [V, loopback] Benchmark on an Apple Silicon over loopback, neptun at the same rev as rustlib, 1280-byte inner packets. Median CPU per packet (both ends together): WG/UDP 5.6 us, WG/QUIC 17.6 to 19.1 us, WG/QUIC fragmented 28 us. Median delivered throughput: 3.3 Gbit/s, 1.0 to 1.4 Gbit/s, and 0.9 Gbit/s. QUIC mode costs about 3x the CPU per packet, and 5x when fragmented.
5. [V] If the WireGuard config has no `MTU` line, the official WireGuard Android app uses MTU 1280, the same value as rustlib. So if you compared against that app, MTU does not explain the speed gap. The likely causes are QUIC overhead, nested congestion control, the single-threaded pipeline, or relay load.
6. Engines for UDP mode:
   - (a) neptun inside rustlib: least work, but single-threaded because crypto runs under a lock.
   - (b) wireguard-go via `com.wireguard.android:tunnel:1.0.20260102`: multi-core crypto and recvmmsg/sendmmsg with UDP GSO/GRO, but no TUN offload on Android, a second runtime (Go), and a crash history.
   - (c) kernel WireGuard: needs root, so it is not available.
   - (d) GotaTun (Mullvad, MPL-2.0, Rust, in Mullvad's Android app since 2025.10): best long-term option.
7. Recommendation: offer two modes, "Speed (WireGuard UDP)" and "Stealth (QUIC)". Ship Speed first as a small rustlib change that reuses neptun (Phase 1), then move both modes to GotaTun (Phase 2). Never fall back from Stealth to UDP automatically. The fallback from UDP to QUIC is safe.

---

## 1. Static WireGuard UDP relay tunnel (obscuravpn-api)

### 1.1 Verified facts

**API call.** `POST {base}/tunnel`. Auth is required. The base URL defaults to `https://v1.api.prod.obscura.net/api` (rustlib `src/constants.rs:6-7`), and the client adds a trailing `/` (`obscuravpn-api/src/client.rs:112-115`), so the path becomes `/api/tunnel`. The body is the serde JSON of the command (`client.rs:281-296`).

`src/cmd/tunnel/create.rs:7-31`:
```rust
#[serde(tag = "type", rename_all = "snake_case")]
pub enum CreateTunnel {
    UdpPort { id: Option<Uuid>, label: Option<String>, wg_pubkey: WgPubkey, relay: Option<String>, exit: Option<String> },
    Obfuscated { id: Option<Uuid>, label: Option<String>, wg_pubkey: WgPubkey, relay: Option<String>, exit: Option<String> },
}
impl Cmd for CreateTunnel { type Output = OneTunnel; type Auth = AuthRequired;
    const METHOD: http::Method = http::Method::POST; const PATH: &'static str = super::PATH; } // "tunnel"
```

Request JSON for UDP mode (derived from the serde attributes above):
```json
{"type":"udp_port","id":"<uuid or null>","label":null,"wg_pubkey":"<base64 32B>","relay":"<relay id or null>","exit":"<exit id or null>"}
```

**Response.** `OneTunnel` (`src/types.rs:362-377`) with `config: TunnelConfig` (`types.rs:401-419`):
```rust
pub enum TunnelConfig { UdpPort { client: WgClientConfig, server: WgServerConfig }, Obfuscated(ObfuscatedTunnelConfig) }
pub struct WgClientConfig { pub wg_pubkey: WgPubkey, pub addresses: Vec<ipnetwork::IpNetwork> }
pub struct WgServerConfig { pub wg_pubkey: WgPubkey, pub endpoints: Vec<net::SocketAddr>, pub dnses: Vec<net::IpAddr> }
```

Response JSON shape (internally tagged):
```json
{"id":"…","label":"…(optional)","status":{"type":"created|connected|disconnected","when":1725050273},
 "config":{"type":"udp_port",
           "client":{"wg_pubkey":"…","addresses":["10.x.x.x/32","fc00:bbbb:bbbb:bb01:…/128"]},
           "server":{"wg_pubkey":"…","endpoints":["<ip>:<port>"],"dnses":["10.64.0.1"]}},
 "relay":{OneRelay},"exit":{OneExit}}
```
The address values above are an example. The repo has no UdpPort JSON fixture, so the address families are inferred from the Obfuscated fixture (`create.rs:55-56`). [H]

**Example flow** (`examples/api_cli.rs:139-167`): the client makes the key pair locally (`StaticSecret::random_from_rng(OsRng)`, l.141) and sends only the public key. It calls `CreateTunnel::UdpPort`, then `build_wg_conf(Some(tunnel.id), sk, client, server)`. The private key never leaves the client.

**Generated config** (`src/wg_conf.rs:4-21`):
```rust
conf += &format!("Address = {}\n", client.addresses.iter().format(","));
conf += &format!("DNS = {}\n", server.dnses.iter().format(","));
conf += &format!("PublicKey = {}\n", server.wg_pubkey);
conf += "AllowedIPs = 0.0.0.0/0,::0/0\n";
conf += &format!("Endpoint = {}\n", server.endpoints.first().unwrap());
```
The config has no `MTU`, no `PersistentKeepalive`, and no PSK. It is a full tunnel for IPv4 and IPv6.

**Delete** (`cmd/tunnel/delete.rs:4-13`): `DELETE {base}/tunnel` with body `{"id":"<tunnel id>"}`, empty output.
**List** (`cmd/tunnel/list.rs:5-12`): `GET {base}/tunnel` returns `Vec<OneTunnel>`. This list contains both kinds of tunnel.
**Slot error** (`cmd/mod.rs:207-210`): `TunnelLimitExceeded {}`, "Delete another tunnel and retry." rustlib maps it to `NoSlotsLeft` (`rustlib/src/errors.rs:57`). UI text (`obscura-ui/src/translations/en.json:402`): "You cannot have more than five active devices. Disconnect a device or delete a WireGuard config in your user portal".
**Key error** (`cmd/mod.rs:212-215`): `WgKeyRotationRequired {}`.

**Slot accounting** (Obscura KB, https://obscura.com/knowledge/downloading-obscura/, dated 2026-09-18): "Each WireGuard config permanently reserves one of your 5 connection slots, even when the device isn't connected." "To free a slot, delete the config from your account." App (QUIC) tunnels use a slot only while they are connected.

**`relay_protocol.rs` is not part of UDP mode.** It defines the control protocol inside QUIC or TLS: a 128-bit protocol identifier, op codes `Ping/Stop/Token/WireGuard`, and an 8-byte header (`relay_protocol.rs:5-14, 43-74`). rustlib uses it for the token handshake, which sends the tunnel UUID (`quicwg.rs:679-685`), and for TCP/TLS framing. A static UDP tunnel has no token step, so the relay can only identify the tunnel by its UDP port. [H, strong, see 1.2]

**Exits are Mullvad.** The rustlib constant is `MULLVAD_EXIT_PROVIDER_NAME = "Mullvad VPN"` (`network_config.rs:14`). DNS blocking uses Mullvad 100.64.0.x addresses (`network_config.rs:81-89,125-131`). Obscura's blog (https://obscura.com/blog/bootstrapping-trust/) says: "WireGuard packets are end-to-end encrypted to Mullvad's servers."

**History.** `UdpPort`, `wg_conf.rs`, and the README section all date from the first public commit, 1e7f0a1 (2024-11-26), and have not changed since (`git log -S UdpPort`).

### 1.2 What the client sends, and to whom (inferred)

- [H, strong] `server.endpoints` is an Obscura relay IP plus a port given to this tunnel. Evidence: the variant name `UdpPort`; README "UDP relay tunnel"; the request takes `relay` and `exit` IDs; the response contains both `relay: OneRelay` and `exit: OneExit`; the portal asks for "Relay Server" and optionally "Exit Server" (https://obscura.com/knowledge/using-obscura-via-wireguard/, 2026-09-16); and no token exists, so a port per tunnel is the only way to demultiplex.
- [H, strong] `server.wg_pubkey` is the Mullvad exit key, so WireGuard runs end-to-end client to exit, as in QUIC mode (`exit_pubkey`, `types.rs:443`). The exit sees the relay IP and not the user IP. It is still two-hop.
- [H] Lifetime: the tunnel stays until it is deleted, because the KB says "permanently reserves". `TunnelStatus` (`types.rs:379-399`) has created, connected, and disconnected states, so the server tracks activity, probably from relay traffic. The README line "Idle static tunnels are not removed automatically by clients if no tunnel slots are left" means the user must delete them.
- [H] README "Common issues": if you delete a tunnel while it is connected, all traffic stops, because AllowedIPs is 0/0 into a dead peer. "You are probably connected to another tunnel already" suggests a conflict, maybe a routing loop when two full tunnels run, or a key or IP conflict at the exit. In our app, tear down the old tunnel before you create a new one.
- [H] MTU: the API does not give one. The client must choose.
- [H] IPv6: `endpoints` is a `Vec<SocketAddr>`, so it can contain IPv6. We do not know if the relays publish IPv6 for UDP. For QUIC, rustlib uses only `relay.ip_v4` (`types.rs:502-505`; `relay_selection.rs:96`) and its socket is IPv4 only (`net.rs:29`, `Domain::IPV4`).
- [H] DNS: `dnses` is probably `10.64.0.1`, the same as the Obfuscated fixture and the KB. Mullvad blocking addresses 100.64.0.x work because the exit is Mullvad.
- [H] Relay side, no packet authentication: the relay cannot authenticate WireGuard packets, because they are encrypted to Mullvad. If it learns the client address from the most recent packet, an off-path sender who knows the relay IP and port can redirect the return path. The attacker gets ciphertext only, so this is a DoS risk. In QUIC mode the token stops this.
- [H] Port number: we do not know it. If it is a high UDP port and not 443, some hotel, corporate, or mobile networks that allow only UDP/443 will block it. QUIC relays listen on `ports: [53, 443]` (fixture `relay.rs:33`).

### 1.3 Verify before shipping (one test, about 10 minutes)

Run `cargo run --example api_cli -- --account-no "$OBS_ACCOUNT_ID" create-static-tunnel --wg-conf > wg0.conf`. Read the account number from the Keychain. Do not print it. Then check:
1. `Endpoint` IP equals `relay.ip_v4`, or another IP of the relay that the JSON on stderr shows.
2. The endpoint port. Is it unique per tunnel and stable?
3. `addresses` contains both v4 and v6.
4. Public IP seen from the tunnel equals the exit (Mullvad) IP, and the WireGuard handshake works.
5. `list-tunnels` shows the status changing, and `delete-all-tunnels` cleans up.
6. Roaming: switch Wi-Fi to LTE and confirm traffic continues. This proves the relay learns the client address again.

### 1.4 Stability risk

- README: "Warning: This is not an officially supported service." The crate README also says "This crate may expose experimental interfaces … completely unsupported."
- Against this: Obscura now documents the same feature for end users in its KB (Android, Windows, QR, file import, slot rules; pages dated 2026-09-16 and 2026-09-18), and it has been in the API since 2024-11 without change. The risk that the API shape changes is low to medium.
- The real risk is support and policy. Obscura can change slot rules or rate limits, or limit the number of tunnel creations. One create and one delete per connect is more churn than portal users make.
- Mitigation: keep QUIC as a fallback that always works. Use client-chosen `id` values so retries are idempotent. Delete on disconnect. Clean up stale tunnels on start.

---

## 2. Does rustlib support UDP (non-QUIC) transport? No.

- [V] There is only one tunnel creation, and it is `CreateTunnel::Obfuscated` (`client_state.rs:474-480`). Any other config kind is rejected: `let TunnelConfig::Obfuscated(config) = tunnel.config else { return Err(TunnelConnectError::UnexpectedTunnelKind) }` (`client_state.rs:506-508`).
- [V] Transports are `enum Transport { Quic{..}, TcpTls(TlsStream<TcpStream>) }` (`quicwg.rs:819-826`) and `TransportKind { Quic, TcpTls }` (`quicwg.rs:204-209`).
- [V] Selection is a feature flag only: `tcpTlsTunnel` (`config/feature_flags.rs:11-22, 45-52`). It is read in `client_state.rs:536-544` and used in `relay_selection.rs:103-110`. Set it with `ManagerCmd::SetFeatureFlag { flag, active }` (`manager_cmd.rs:292`). The other flags are `quicFramePadding`, `killSwitch`, and `forceSmallMtu`. The old `force_tcp_tls_relay_transport` is marked removed (`config/persistence.rs:290`).
- [V] The UDP code in rustlib is only the QUIC socket (`net.rs:53-55,102-130`).

So UDP mode needs new code. Section 6 gives the minimal patch.

---

## 3. Why QUIC mode is slower: the hot path, with numbers

### 3.1 Per-packet path (verified code)

Upload. One `read()` per packet from the TUN (`android/tunnel.rs:28-37`):
```rust
let mut buf = Box::new([0; u16_into_usize(TUNNEL_MTU)]);
loop { match fd_watcher.readable().await { Ok(mut guard) => match unistd::read(&fd_watcher, &mut buf[..]) {
    Ok(n) => { if n > 0 { tunnel.send(std::iter::once(&buf[..n])) } } ...
```
Then a global mutex, encapsulation, a copy, a possible split into fragments, and `send_datagram` (`quicwg.rs:383-421`):
```rust
let mut wg_state = self.wg_state.lock().unwrap();
...
match wg_state.wg.encapsulate(packet, &mut wg_state.buffer) {
    TunnResult::WriteToNetwork(wg_message) => {
        let wg_message = Bytes::copy_from_slice(wg_message);
        let (first, second) = match self.wg_sender.max_wg_message_size() {
            Some(max_size) => wg_state.fragmenter.fragment(wg_message, max_size),
            None => (wg_message, None),
        };
        for msg in once(first).chain(second) { self.wg_sender.send_wg_message(msg); }
```
```rust
WgSender::Quic { conn } => { if let Err(error) = conn.send_datagram(wg_message) { rate_limited_log!(...) } }   // quicwg.rs:922-930
```
Download. One task runs `select!` and builds two timers for each received datagram (`quicwg.rs:437-525`). It does reassembly, then `decapsulate` under the same mutex, then `Bytes::copy_from_slice(packet)` (`quicwg.rs:373-374`), then a mutex and a `write()` to the TUN for each packet (`android/os_impl.rs:65-69`, `android/tunnel.rs:56-69`).

So every packet goes through one global `Mutex<WgState>` for both directions, with two copies or more per packet, plus the copy and encryption inside quinn. neptun `Tunn::encapsulate` takes `&mut self` (`neptun/src/noise/mod.rs:274`). NepTUN upstream says "all the crypto work in NepTUN is done under the lock held on the tunnel, and thus cannot be truly parallelized" (https://github.com/NordSecurity/NepTUN/pull/84, still open). This single-thread limit also applies to a UDP mode built on neptun.

### 3.2 Outer congestion control and queueing (verified config, effect inferred)

QUIC config (`quicwg.rs:728-748`):
```rust
mtu_discovery_config.upper_bound(DEFAULT_UDP_PAYLOAD_SIZE);           // 1350
transport_config.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
transport_config.pad_to_mtu(quic_frame_padding);                       // default false
```
- [V] quinn BBR is documented as "Experimental! Use at your own risk." (`quinn-proto-0.11.19/src/congestion/bbr/mod.rs:19`).
- [V] DATAGRAM frames are ack-eliciting, so congestion control and pacing block them (`quinn-proto/src/connection/mod.rs:604-640`).
- [V] Datagrams that are blocked wait in a send buffer of `datagram_send_buffer_size` = 1 MiB by default (`quinn-proto/src/config/transport.rs:396`). When it is full, the oldest are dropped (`transport.rs:300-309`, `connection/datagrams.rs:154-173`). rustlib does not change this value.
- [H] Effect: an inner TCP flow sees a second congestion controller, with up to 1 MiB of hidden queue before any loss. Queue delay at 1 MiB is 0.84 s at 10 Mbit/s, 0.17 s at 50 Mbit/s, and 0.08 s at 100 Mbit/s. Inner TCP sees a high RTT and late losses, which gives slow ramp-up and bufferbloat on variable mobile uplinks. The same happens on the relay for downloads, but we do not know the relay configuration. Plain WireGuard has no outer congestion controller and no queue, so inner TCP controls the real path.
- [V] The project has already had Android upload problems in this layer: "quinn-udp 0.5.6 regressed upload speeds on Android. This is fixed in 0.5.8 and later." (`rustlib/Cargo.toml:51-54`).

### 3.3 MTU and fragmentation (verified)

- `TUNNEL_MTU: u16 = 1280`, `DEFAULT_UDP_PAYLOAD_SIZE: u16 = 1350` (`quicwg.rs:61-64`). quinn's `max_datagram_size` is current_mtu minus the 1-RTT overhead minus the datagram frame bound. At current_mtu 1350 the benchmark measured exactly 1312, and 1280 + 32 = 1312 (WG overhead). So a 1280 packet fits only when PMTUD reaches 1350, and only if the relay's connection ID is 8 bytes or shorter.
- quinn starts at `INITIAL_MTU = 1200` (`quinn-proto/src/lib.rs:332`). Until PMTUD succeeds, the maximum datagram is 1162 bytes, so every full-size packet is split into two QUIC datagrams.
- If the interface MTU is below 1378, or `forceSmallMtu` is set, the client advertises `max_udp_payload_size(1200)` (`net.rs:105-126`). The relay must then split every full-size download packet, permanently.
- [V, bench] Download with this cap: twice as many UDP packets (relay udp_tx 803k and 1.06M for 402k and 530k inner packets), median goodput 905 Mbit/s instead of 1438, CPU per packet 28 us instead of 17.6 (about 1.6x).

### 3.4 Bytes and packets on the wire (verified arithmetic)

| per full packet, IPv4 path | tunnel MTU | outer overhead | overhead % | inner TCP MSS |
|---|---|---|---|---|
| QUIC mode (rustlib) | 1280 | 32 WG + up to 38 QUIC (1350-1312) + 28 UDP/IP = up to 98 B | up to 7.7 % | 1240 |
| UDP mode, same MTU | 1280 | 32 WG + 28 = 60 B | 4.7 % | 1240 |
| UDP mode, MTU 1380 (Mullvad default) | 1380 | 60 B | 4.3 % | 1340 |
| UDP mode, MTU 1420 (wg-quick on a 1500 link) | 1420 | 60 B | 4.2 % | 1380 |

- MTU 1420 needs 10 % fewer packets for the same bytes as 1280 (1240/1380 = 0.90).
- QUIC ACKs: the client sends ACK packets for downloaded datagrams. Measured on loopback: 0.17 to 0.30 ACK datagrams for each data datagram. The RFC 9000 default is up to 0.5. These packets add uplink load and radio wake-ups. Plain WireGuard sends nothing back at the transport layer.
- Mullvad documents 1380 as its WireGuard default (https://mullvad.net/en/help/using-mullvad-vpn-app).

### 3.5 Loopback benchmark (measured)

Source: `/tmp/obscura-research/perf-udp-mode/bench/src/main.rs`. Configuration:
- neptun ff3b555 and quinn 0.11.12, with the rustlib Cargo.lock.
- The client QUIC config is copied from `quicwg.rs:728-748`.
- Flow control uses a window of 256 packets.
- Inner packets are 1280 B.
- Hardware: an Apple Silicon Mac, release build with LTO. Each case ran 3 to 7 times.
- To run it again: make a P-256 self-signed `cert.der` and `key.der` (PKCS8) for CN/SAN `relay` with `openssl`, then `cargo run --release --offline -- all|udp|down|downsmall`.

| case | goodput (median, range) | CPU per packet, both ends (median, range) |
|---|---|---|
| neptun enc+dec only, 1 core | 6.6 Gbit/s (4.5 to 7.1) | 1.5 us (1.4 to 2.1) |
| WG over plain UDP | 3.35 Gbit/s (1.76 to 3.60) | 5.6 us (5.2 to 8.2) |
| WG over QUIC, upload, BBR (rustlib) | 1.03 Gbit/s (0.81 to 1.44) | 19.1 us (16.4 to 27.4) |
| WG over QUIC, upload, Cubic | 1.20 Gbit/s (0.91 to 1.47) | 17.9 us (16.5 to 22.8) |
| WG over QUIC, download, 1350 path | 1.44 Gbit/s (1.00 to 1.44) | 17.6 us (17.6 to 23.8) |
| WG over QUIC, download, client cap 1200, fragmented | 0.91 Gbit/s (0.69 to 0.91) | 28.0 us (27.3 to 39.0) |

What this shows:
- [V] QUIC mode costs about 3.1 to 3.4x the CPU per packet of plain UDP, and about 5x when fragmented. Loopback throughput is about 3x lower.
- [H] The QUIC numbers include relay-side QUIC work. The client share is about half.
- [H] The numbers come from a desktop SoC over loopback, with no loss and no RTT. On a phone core the cost per packet is probably 2 to 4x higher. For example, 300 Mbit/s is 29.3 kpkt/s, which at 2 to 4x of about 9.5 us is about 0.55 to 1.1 of a core on the client for QUIC mode. Battery cost scales the same way.
- Loopback cannot show the effects in 3.2 (nested congestion control and queueing). On mobile networks those effects are probably the main cause of the "speed issues". [H]

### 3.6 Other factors (hypotheses, measure them)

- [H] Relay CPU: each QUIC relay must terminate QUIC in userspace for every packet. A UDP forward can run in the kernel (DNAT or XDP). When relays are busy, QUIC users slow down first, and static-UDP users do not.
- [H] On IPv6-only mobile networks (464XLAT), the IPv4-only socket (`net.rs:29`) sends all traffic through CLAT.

### 3.7 A/B test that separates the causes (do this first)

Use the same phone, network, relay, and exit:
- (1) Obscura app (QUIC).
- (2) Official WireGuard app with a portal config for that relay and exit, MTU not set, so 1280.
- (3) The same as (2) with `MTU = 1380`.
- (4) Optional: a Mullvad config straight to the same exit, single hop.

Measure iperf3 or speedtest down and up, ping under load, and battery mAh per GB.
- (1) vs (2) shows the cost of QUIC.
- (2) vs (3) shows the effect of MTU.
- (2) vs (4) shows the cost of the relay hop.

---

## 4. Engine comparison for a UDP mode on Android

| | (a) neptun in rustlib | (b) wireguard-go (wireguard-android `tunnel`) | (c) kernel WireGuard | (d) GotaTun (Mullvad) |
|---|---|---|---|---|
| Version | ff3b555 (2024-12) | Maven `com.wireguard.android:tunnel` latest **1.0.20260102** [V, Maven metadata]; repo HEAD pins wireguard-go f333402bd9cb (`tunnel/tools/libwg-go/go.mod`) | GKI has the module, but the user cannot reach it | 0.10.2 (2026-10-20) |
| Licence | BSD-3 | Java glue Apache-2.0 (`COPYING`), wireguard-go MIT. Both GPL-3.0 compatible | n/a | MPL-2.0 from 2026-03-05, BSD-3 before (`README.md`). No "Incompatible With Secondary Licenses" notice, so GPL-3.0 compatible |
| Crypto threads | 1, under a lock (`mod.rs:274`, NepTUN PR #84) | `runtime.NumCPU()` workers for encryption, decryption, and handshake (`device/device.go:311-318`) | n/a | Separate tokio tasks for outgoing, incoming, and timers (`gotatun/src/device/mod.rs:239-262`) |
| UDP batching | none in rustlib (one `send`/`recv` per packet) | recvmmsg/sendmmsg on `linux` and `android` (`conn/bind_std.go:178-190`), up to 128 per call (`conn/conn.go:19`). UDP GSO and GRO are probed at runtime (`conn/features_linux.go:14-29`) | n/a | sendmmsg on Linux and Android. On Android, receive is plain `recv_from` (`udp/socket/linux.rs:365-385`). GRO is on for Linux only ("TODO: missing constants on Android", l.265) |
| TUN offload (TSO/GRO) | no | **no on Android.** VpnService makes the TUN with `IFF_TUN \| IFF_NO_PI` (AOSP `services/core/jni/com_android_server_connectivity_Vpn.cpp:67`), and wireguard-go sets `batchSize = 1` when `IFF_VNET_HDR` is missing (`tun/tun_linux.go:528-543`) | n/a | no (same TUN) |
| Integration | small patch to `quicwg.rs` and `client_state.rs`. Same VpnService, kill switch, and key cache | Go runtime inside the process next to tokio. `GoBackend` uses its own `GoBackend$VpnService` from the library manifest and builds the `VpnService.Builder` itself (`GoBackend.java:297-350`; it calls `protect()` on its sockets at l.349-350). JNI symbols are fixed to `com.wireguard.android.backend.GoBackend` (`tools/libwg-go/jni.c:18-62`). To keep our own VpnService you must build libwg-go yourself and call `wgTurnOn(name, fd, uapi)` (`api-android.go:76-99`) | root only. The official app's kernel backend uses `RootShell` (`WgQuickBackend.java:14,51-58`) | Rust crate with pluggable `UdpTransportFactory`, `IpSend`, and `IpRecv` (`device/transports.rs:22-54`, `device/builder.rs:94-173`), so a QUIC datagram transport can plug in too |
| Risk | low code risk, performance limit stays | Mullvad: ">85% of all crashes reported have stemmed from the wireguard-go" (their fork). Two runtimes, larger APK | not available | young project, but audited (Assured, 2026-02) and in production at Mullvad on Android since 2025.10 |

Throughput evidence (all URLs):
- Tailscale, "Userspace isn't slow, some kernel interfaces are!", 2022-12-13. TUN TSO/GRO plus sendmmsg/recvmmsg took wireguard-go from 2.42 to 5.36 Gbit/s. Linux only, and it needs TUN offload, which Android does not have. https://tailscale.com/blog/throughput-improvements and PR https://github.com/WireGuard/wireguard-go/pull/64
- Tailscale, "Surpassing 10Gb/s over Tailscale", 2023-04-13. UDP GSO (Linux 4.18+) and GRO (5.0+) took it from 8.36 to 13.0 Gbit/s on bare metal (i5-12400), and from 5.34 to 7.32 Gbit/s on AWS c6i.8xlarge. https://tailscale.com/blog/more-throughput and upstream https://github.com/WireGuard/wireguard-go/pull/75. Only the UDP-side part applies on Android.
- Tailscale, QUIC/UDP throughput follow-up (v1.54). Needs TUN UDP offload (Linux 6.2), which does not apply to the Android VpnService TUN. https://tailscale.com/blog/quic-udp-throughput
- Mullvad, "Announcing GotaTun", 2025-12-19. Android crash rate went from 0.40 % to 0.01 %. Users report "better speeds and lower battery usage". Mullvad gives no benchmark. https://mullvad.net/en/blog/announcing-gotatun-the-future-of-wireguard-at-mullvad-vpn ; audit: https://mullvad.net/en/blog/2026/3/6/a-security-audit-of-gotatun-is-now-available
- Anecdote, Pixel 8, HN 2025-12: wireguard-go "struggled to pull even 100mbps", GotaTun "500mbps+". https://news.ycombinator.com/item?id=46324543. The same thread has an Obscura employee (kevincox): "faced with boringtun bugs … switched our client to be based on top of NepTUN".
- Official WireGuard app MTU default: `builder.setMtu(config.getInterface().getMtu().orElse(1280));` (`GoBackend.java:330`). A config from `wg_conf.rs` has no MTU, so the app uses 1280. [V]

Conclusion:
- (b) gives multi-core crypto today, but on Android it does not get the large TUN-offload gains, and it costs a second runtime and a separate VpnService path.
- (a) removes all the QUIC costs in section 3 with the least risk.
- (d) is the right long-term engine for both modes.

---

## 5. Privacy: QUIC vs UDP mode

| Observer | QUIC (Stealth) | WireGuard UDP (Speed) |
|---|---|---|
| Local network / ISP (DPI) | QUIC v1 to a relay IP on 53 or 443. ALPN `h3` (`quicwg.rs:730`) and SNI `example.com` by default (`constants.rs:12`), readable from QUIC Initial packets. The SNI does not match the IP, which is a weak sign. A long single flow at high volume is a heuristic sign. Not a fixed fingerprint | WireGuard is easy to fingerprint: type byte 1 to 4, three zero reserved bytes, handshake of 148 / 92 / 64 bytes. Blocked in Russia since 2023 (TSPU; https://github.com/net4people/bbs/issues/274), and by AmneziaWG-class DPI elsewhere. The network sees "this device uses WireGuard". The mac1 of each handshake initiation is in clear text and keyed by the exit public key, so an observer with a list of exit keys can tell which exit server you use |
| ISP, by IP only | Relay IPs are Obscura's and any account can list them (`ListRelays`). VPN use is visible in both modes | same |
| Relay (Obscura) | client IP, timing, volume, exit choice, tunnel UUID token. No plaintext | client IP, timing, volume, exit choice. No token. Sees WireGuard receiver index and counter. No plaintext |
| Exit (Mullvad) | relay IP, client WG pubkey, plaintext destinations | same |
| Packet sizes | leaked, WG pads to 16 B. `quicFramePadding` (default off) can pad to MTU, which hides sizes but costs bandwidth | leaked, WG pads to 16 B. No padding option |
| Linkability across sessions | new tunnel UUID per connect (`client_state.rs:461`). WG key reused until it rotates after 30 days (`config/persistence.rs:639`) | static tunnel = fixed relay port + fixed key. If reused, a passive observer can link sessions across networks. **Fix:** make a tunnel per connect and delete it after, to match QUIC mode |

Downsides of UDP mode:
- Censorship and "VPN detected" risk. Do not use it in censored places, or with an adversary who must not learn that you use WireGuard.
- Ports may be blocked. [H]
- No relay-side authentication, so a DoS / redirect risk exists. [H]
- It depends on a feature that the API repo calls unsupported.

In networks without censorship, both modes keep two-hop separation and end-to-end WireGuard to the exit. But only Speed mode shows the local network which exit server you use (mac1, see the table). [H]

---

## 6. Recommendation and least-risk implementation

### 6.1 Product

- Offer a "Connection mode" setting: **Stealth (QUIC)**, which is upstream behaviour, and **Speed (WireGuard UDP)**. Keep TCP/TLS as a hidden fallback ("Stealth, TCP").
- Default: Stealth until the A/B test in 3.7 confirms the gain. Then let the user choose. Without a censorship threat, Speed is reasonable.
- Fallback rule: Speed may fall back to Stealth when the handshake fails after about 5 s. Stealth must never fall back to UDP, because that shows WireGuard to the network.
- Show slot usage. The mode uses 1 slot while connected, the same as the app, because the tunnel is deleted on disconnect.

### 6.2 Phase 1: UDP mode inside rustlib with neptun (smallest change, GPL fork)

1. `quicwg.rs`: add `WgSender::Udp { sock: Arc<tokio::net::UdpSocket> }` and `WgReceiver::Udp(Arc<tokio::net::UdpSocket>)`.
   - Send with `sock.try_send(&msg)`. Drop on `WouldBlock`, as WireGuard does.
   - Receive into a pooled 2 KiB buffer.
   - `max_wg_message_size()` returns `None`, so there is no fragmentation. `transport()` returns a new `TransportKind::WireguardUdp`.
   - Add `QuicWgConn::connect_udp(endpoint, sk, exit_pk, client_ip_v4, ping_ip)`, which reuses `first_wg_handshake` (`quicwg.rs:329-361`) and `wait_for_first_pong`.
   - Make the socket with `net::new_udp` (`net.rs:53`), with an IPv6 variant if the endpoint is v6. Then `connect(endpoint)`, set it nonblocking, and set SO_RCVBUF/SO_SNDBUF to about 4 MiB.
   - The Android VPN loop is already avoided by `addDisallowedApplication(own package)` (`ObscuraVpnService.kt:398`).
2. `client_state.rs`: add `new_tunnel_udp`, a copy of `new_tunnel` (`client_state.rs:419-510`) with these changes:
   - Keep `select_relay` for the RTT race, then `abandon()` the QUIC handshake. An alternative is `relay: None`, which lets the API choose, but this is not verified.
   - Call `CreateTunnel::UdpPort { id: Some(tunnel_id), label: None, wg_pubkey, relay: Some(id), exit: Some(id) }`.
   - Match `TunnelConfig::UdpPort { client, server }`.
   - Use the same key cache (`use_key_pair`), with `WgKeyRotationRequired` handled as today.
3. `network_config.rs`: add `TunnelNetworkConfig::from_udp(&WgClientConfig, &WgServerConfig, mtu)`. It takes the first v4 and v6 values from `addresses`, and `dns = dnses`. The ping target is `10.64.0.1`, because `UdpPort` has no `gateway_ip_v4`; this value is the same as `default_gateway_ip_v4()` in `types.rs:421-423`.
   - MTU for UDP mode: 1380 by default, user-settable from 1280 to 1420.
4. Lifecycle:
   - Write the tunnel UUID to config before the create call. The client-chosen `id` makes retries idempotent.
   - On disconnect: stop the data path first, then `DeleteTunnel { id }` outside the tunnel. Delete only after teardown, because of the README issue.
   - On start, or on `TunnelLimitExceeded`: delete only the IDs we own. Never touch portal configs.
5. API and FFI shape (follows the existing `ManagerCmd` style, `manager_cmd.rs:128-202`):
```rust
// manager.rs:137-141 today: pub struct TunnelArgs { pub exit: ExitSelector }
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone, Default)]
#[serde(rename_all = "camelCase")]
pub struct TunnelArgs {
    pub exit: ExitSelector,
    #[serde(default)]
    pub mode: TunnelMode,            // new
}
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone, Copy, Default)]
#[serde(rename_all = "camelCase")]
pub enum TunnelMode { #[default] Quic, TcpTls, WireguardUdp }
// quicwg.rs:206 TransportKind gets a WireguardUdp variant (already reported in VpnStatus::Connected.transport, manager.rs:132)
```
   Kotlin sends the existing command with a new field: `{"setTunnelArgs":{"args":{"exit":{"any":{}},"mode":"wireguardUdp"},"active":true}}`. Older configs default to `quic`.

Expected effect: this removes the QUIC crypto and state machine, the ACK uplink traffic, the outer BBR and 1 MiB queue, and the fragmentation. It also allows MTU 1380 or 1420. The single-thread limit stays.

### 6.3 Phase 2: GotaTun as the engine for both modes

- Replace the neptun `Tunn` and the `Mutex<WgState>` pipeline with a GotaTun `Device`.
  - IP side: `IpSend` and `IpRecv` over the VpnService fd.
  - UDP mode: `UdpSocketFactory`.
  - Stealth: a custom `UdpTransportFactory` that sends WG messages as QUIC datagrams (`device/transports.rs:22-54`).
- This also fixes the per-packet locking and copying in Stealth mode.
- Also tune Stealth mode, and A/B test each change:
  - `datagram_send_buffer_size` at about 64 to 128 KiB, to cut hidden queueing.
  - Compare Cubic with BBR.
  - Batch TUN reads.

### 6.4 Not recommended

- wireguard-go via GoBackend. Use it only if Phases 1 and 2 fail. It forces a separate VpnService class, a Go runtime, and a second kill-switch path, and Mullvad's crash data is against it.
- Kernel WireGuard: not possible without root.
