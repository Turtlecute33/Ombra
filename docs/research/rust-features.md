# rust-features: Obscura rustlib core feature map

Sources read in full (all paths absolute):

- Client core: `/tmp/obscura-research/obscuravpn-client/rustlib/src` at commit `85ef7e2692dc811a023360f08865bfb90815649c` (2026-10-19). Paths below are relative to `rustlib/` unless stated.
- API crate at the exact pinned rev: `/tmp/obscura-research/obscuravpn-api` at `e94483e7331b521d4d29ee7411a83e4adeffcc9d` (2026-10-16). Paths prefixed `api:`.
- WireGuard impl: `~/.cargo/git/checkouts/boringtun-896ba2793962ccef/ff3b555/neptun` (rev `ff3b5555...`, crate `neptun` 0.6.0).
- quinn-proto / quinn-udp sources in `~/.cargo/registry` (to confirm defaults). Lockfile pins: quinn 0.11.12, quinn-proto 0.11.19, quinn-udp 0.5.16, rustls 0.23.28, tokio 1.45.1, reqwest 0.13.5 (Sovereign fork), toolchain 1.89.0.
- Existing Android glue, only where it shows how the core is driven: `/tmp/obscura-research/obscuravpn-client/android/app/src/main/java/net/obscura/vpnclientapp/...`.

Labels: **[V]** = verified in code; **[I]** = inference (not proven by code I read).

---

## 0. Feature matrix

| Feature | Core support | How to drive it (ManagerCmd JSON / FFI) | Notes |
|---|---|---|---|
| Connect / disconnect | Yes [V] | `{"setTunnelArgs":{"args":{"exit":{...}},"active":true}}` / `{"setTunnelArgs":{"args":null,"active":false}}` | Target-state model. Core retries forever with 1 s debounce. On Android the core forces `tunnel_active=false` at process start (`force_init_inactive=true`, android/ffi.rs:67). |
| Two-hop: Obscura relay (entry) + exit (Mullvad) | Yes [V] | automatic | WireGuard end-to-end client to exit, carried inside QUIC datagrams to the relay. |
| QUIC transport (default) | Yes [V] | default | ALPN `h3`, pinned self-signed relay cert, SNI default `example.com`, BBR, PMTUD up to 1350 B. |
| TCP+TLS transport | Yes [V] | `{"setFeatureFlag":{"flag":"tcpTlsTunnel","active":true}}` | ALPN `h2`, same relay ports, WG framed in a TLS stream. |
| Plain WireGuard over UDP (no relay) | No in core [V] | n/a | API has `CreateTunnel::UdpPort` + `TunnelConfig::UdpPort`, core rejects it (`UnexpectedTunnelKind`, client_state.rs:506-508). |
| QUIC packet padding (anti traffic analysis) | Yes, flag [V] | flag `quicFramePadding` | quinn `pad_to_mtu`. Costs bandwidth. |
| Force small MTU (1200 B UDP payload) | Yes, flag [V] | flag `forceSmallMtu` | Also automatic when interface MTU < 1378. |
| WG message fragmentation | Yes, automatic [V] | none | 2-fragment split, type byte 170, in `obscuravpn-api::wg_fragment`. |
| Exit choice: any / country / city / specific server | Yes [V] | `ExitSelector` in `TunnelArgs` | JSON shapes in section 2. |
| Entry relay choice | **No** [V] | n/a | Always a latency race over all relays from the API. Needs a core patch (section 10). |
| Auto-select best entry | Yes [V] | automatic | QUIC/TLS handshake + 3x relay `Ping` op RTT, min RTT wins, max 5 successful relays, deadline 30 s, shrinks to min(3x first success, 5 s). |
| Auto-select best exit for selector | Yes [V] | automatic | Rank: relay-preferred exit > same city as relay > same country > `tier` > random. Adaptive exclusion on retries. |
| Favourites (pinned locations) | Storage only [V] | `{"setPinnedExits":{"exits":[{"country_code":"..","city_code":"..","pinned_at":<unix s>}]}}` | Not used by selection logic. Reset on logout. |
| Last chosen exit memory | Yes [V] | read `Status.lastChosenExit`, `Status.lastExit` | Set on successful connect. |
| Exit / relay lists | Yes [V] | `{"refreshExitList":{"freshness":<ms>}}`, `{"getExitList":{"knownVersion":null}}` | ETag cached, persisted. Relay list is not exposed via ManagerCmd (only inside `VpnStatus.connected.relay`). |
| DNS: provider DNS pushed by API | Yes [V] | default | Example `10.64.0.1` (Mullvad in-tunnel resolver). |
| DNS content blocking (ads, trackers, malware, adult, gambling, social) | Yes, Mullvad exits only [V] | `{"setDnsContentBlock":{"value":{"ad":true,"tracker":true,"malware":true,"adult":false,"gambling":false,"socialMedia":false}}}` | Maps to `100.64.0.<bitmask>`. Ignored if exit provider is not exactly `"Mullvad VPN"`. |
| "Use system DNS" | Flag only [V] | `{"setUseSystemDns":{"enable":true}}` | Core only passes `useSystemDns` to the OS layer. Kotlin skips `addDnsServer` when true. Leak risk. |
| Custom DNS / DoH / DoT | **No** [V] | n/a | Not in core. App could override `OsNetworkConfig.dns` on the Kotlin side [I]. |
| Local network access (LAN bypass) | Yes [V] | `{"setLocalNetworkAccess":{"enable":false}}` | **Default Enabled.** On Android implemented as a route set (0/0 and ::/0 minus LAN ranges, DNS IPs kept in tunnel). |
| Per-app split tunnel | **No** in core [V] | Kotlin `VpnService.Builder.addDisallowedApplication` | Core is app-agnostic. The app itself must be excluded (it already is in the official app). |
| Tailscale / WireGuard bypass | Linux only [V] | `setTailscaleBypass`, `setWireGuardBypass` | No effect on Android (fields cfg-gated to Linux). |
| Kill switch | Flag exists, no Android effect [V] | flag `killSwitch` | Android: rely on system "Always-on VPN" + "Block connections without VPN". Core does block traffic while (re)connecting by installing a dummy TUN config (section 1.11). |
| IPv6 inside tunnel | Yes [V] | automatic | Tunnel gets an IPv6 ULA address, routes `::/0`. Outer transport is IPv4 only. |
| Liveness / auto reconnect | Yes [V] | automatic | ICMP echo to gateway (`10.64.0.1` default). Dead after >4 lost probes (>1 after wake). |
| Wake hint (fast dead-tunnel detection after sleep) | Core yes, Android FFI **no** [V] | `Manager::wake()` exists, exported only for Apple (`apple/ffi.rs:179`) | Needs a 1-function JNI addition. |
| Network roaming | Yes, by full reconnect [V] | Kotlin calls `setNetworkInterface(name, index)` / `unsetNetworkInterface()` | Interface change => drop tunnel, new relay race, new `CreateTunnel` API call. No QUIC migration used. |
| Traffic stats + latency | Yes [V] | `{"getTrafficStats":{}}` | `{connectedMs, connId, txBytes, rxBytes, latestLatencyMs}`; latency = ICMP RTT to exit gateway. |
| Status stream | Yes [V] | `{"getStatus":{"knownVersion":<uuid or null>}}` long-poll | Returns when status version differs. |
| Login with account number | Yes [V] | `{"login":{"accountId":"<20 digits>","validate":true}}` | Gets auth token via `POST /api/token2`, PoW solver on rate limit. |
| Account creation | Indirect [I] | UI generates a random 20-digit number (19 random + Verhoeff) and calls `login(validate=true)` | Core has no `CreateAccount` cmd. |
| Account info (active, expiry, funding) | Yes [V] | `{"apiGetAccountInfo":{}}` | Cached in config, exposed in `Status.account`. |
| Logout | Yes [V] | `{"logout":{}}` | Resets almost all config, **keeps `old_account_ids`**. |
| Delete account | Yes [V] | `{"apiDeleteAccount":{}}` | `DELETE /api/account`. |
| Service status (trial availability) | Yes [V] | `{"apiServiceStatus":{}}` | Unauthenticated. |
| Payments: Google Play | Yes [V] | `apiGoogleBillingDetails`, `apiGoogleAssociateAccount` | |
| Payments: Lightning / Monero / Stripe / prices | API crate only [V] | not in ManagerCmd | Official UI opens `https://obscura.com/pay#account_id=...`. |
| Tunnel slot list / delete | API crate only [V] | not in ManagerCmd | Core never deletes tunnels. `TunnelLimitExceeded` => `noSlotsLeft`. |
| WG key management | Yes [V] | `{"rotateWgKey":{}}` | Client-generated x25519; 30-day rotation; Android stores secret **in plaintext** in config.json. |
| API endpoint override / censorship fallback | Yes [V] | `setApiUrl`, `setApiHostAlternate` | Fallback chain: primary host -> alternate hosts (default `crimsonlance.net`) -> no-SNI. Pinned DNS seed IP. |
| Relay SNI override | Yes [V] | `{"setSniRelay":{"host":"..."}}` | Any valid DNS name. |
| Auto-connect setting | Storage only [V] | `{"setAutoConnect":{"enable":true}}` | App implements behaviour. |
| Debug bundle | Yes [V] | `createDebugBundle`, `getDebugInfo` | Local zip only; performs test DNS/HTTPS requests to apple.com, google.com, Obscura API. |
| Telemetry / analytics / crash upload | **None** [V] | n/a | Logs are local only. |

---

## 1. Connection architecture

### 1.1 Hop model

- Client builds a WireGuard (neptun/boringtun) session directly with the **exit**: `Tunn::new(client_secret_key, exit_public_key, None, None, index, None)` (quicwg.rs:246). The exit public key comes from the API tunnel config `exit_pubkey` (client_state.rs:403). [V]
- WireGuard messages are sent to the **Obscura relay** as QUIC DATAGRAM frames (`conn.send_datagram`, quicwg.rs:925) or as framed messages in a TCP+TLS stream (quicwg.rs:842-893). [V]
- The relay forwards WG messages to the exit. The relay can see the client IP but only encrypted WG payload. The exit sees WG traffic from the relay, not the client IP. [I: consistent with the code, the relay holds no WG keys in the client code path; forwarding happens server side.]
- Exits carry `provider_name`, e.g. `"Mullvad VPN"` (api: cmd/exit2.rs:28-44; network_config.rs:14). Exits are Mullvad WireGuard servers today [V for the provider name constant and test data; I for "all exits are Mullvad"].
- Relay auth token = the tunnel UUID created via the API (`QuicWgConn::connect(..., tunnel_id)` client_state.rs:405; `authenticate(token)` sends `RelayOpCode::Token` with `token.as_bytes()` quicwg.rs:679-685). [V]

### 1.2 Connect sequence (client_state.rs `connect` 389-417, `new_tunnel` 419-510, `select_relay` 512-608)

1. Spawn background exit-list refresh if older than 60 s (`maybe_update_exits(Duration::from_secs(60))`, :427). [V]
2. `select_relay`: refresh relay list if older than 60 s (:515); use cached list if present (:521-525). Read flags `tcp_tls_tunnel`, `quic_frame_padding`, `force_small_mtu` and interface MTU (:536-544). [V]
3. `race_relay_handshakes` (relay_selection.rs:74-122): one IPv4 UDP socket + one quinn Endpoint; for each relay (max 100) and each port (max 5): QUIC (or TCP/TLS) handshake, protocol-identifier exchange, then `measure_rtt` = 3x `Ping` op, min RTT (quicwg.rs:657-677). [V]
4. Pick lowest RTT. Deadline 30 s (:556); after each new best, deadline = start + min(3x elapsed, 5 s) (:585); stop after 5 distinct relays succeeded (:595). Losers get `Stop` op and graceful close (`abandon`, quicwg.rs:695-710), 1 s grace (relay_selection.rs:10). [V]
5. Exit list: cached or wait for refresh (:436-440). `select_next_exit(selector, exits, closest_relay)` (exit_selection.rs:51-72). No match -> `NoExit`. [V]
6. Get WG key pair (`use_key_pair`, sets `first_use`) (:462-463). [V]
7. API `POST /api/tunnel` with `CreateTunnel::Obfuscated { id: Some(new uuid), label: None, wg_pubkey, relay: Some(relay.id), exit: Some(exit.id) }` (:474-480). On `WgKeyRotationRequired` rotate key and fail this attempt (:484-498). [V]
8. Check `tunnel.relay.id == closest_relay.id` else `UnexpectedRelay` (:503-505); require `TunnelConfig::Obfuscated` (:506-508). [V]
9. Build `TunnelNetworkConfig` with MTU `TUNNEL_MTU = 1280` (:396); requires non-empty DNS, at least one IPv4 and one IPv6 client address (network_config.rs:25-40). [V]
10. `QuicWgConn::connect` (quicwg.rs:232-274): send relay `Token` op, wait OK; WG handshake init, resend up to 25 times with 100 ms timeout each (2.5 s) (:46-47, :329-361); then wait for first liveness pong (ICMP reply) before reporting connected (:272). [V]
11. Persist `last_chosen_exit` (only if selector != Any), `last_chosen_exit_selector`, `last_exit_selector` (:409-415). [V]
12. tunnel_state.rs: set the real `OsNetworkConfig` (FFI `ffiSetNetworkConfig` returns a TUN fd), then forward packets until the target changes or the tunnel dies (:307-345). [V]

### 1.3 Relay control protocol (exact) [V]

- Protocol identifier: `PROTOCOL_IDENTIFIER: u128 = 0x033d77afd0ded498c6ec25c75c5b0cf2`, sent big-endian (16 bytes) by both sides first (api: relay_protocol.rs:5; quicwg.rs:625-655).
- Message = 8-byte header `[context_id: u32 BE][payload_length: u32 BE]` + payload. Payload = 4-byte code (BE) + argument (api: relay_protocol.rs:43-74; quicwg.rs:997-1005). `payload_length = 4 + arg.len()`. Receiver ignores lengths < 4 or > 65535+4 (quicwg.rs:1023).
- Op codes: `Ping = 0`, `Stop = 1`, `Token = 2`, `WireGuard = 3` (api: relay_protocol.rs:9-14). Response code `0 = Ok`, non-zero = error (+ UTF-8 message).
- Context IDs: even = client-initiated (client uses 0), odd = relay-initiated (api: relay_protocol.rs:28-41).
- QUIC: one bidirectional control stream (client opens). WG messages travel as QUIC datagrams, not on the stream. TCP+TLS: everything on one stream; WG messages are `Op(WireGuard)` messages; the client must answer relay-initiated ops with `Ok` (quicwg.rs:842-893, 961-984). TCP send queue capped at 1000 WG messages (drop beyond) (quicwg.rs:934-936).

### 1.4 QUIC transport config (quicwg.rs:712-748, net.rs:102-130) [V]

- rustls with **ring** provider explicitly (quicwg.rs:713), TLS 1.2/1.3 safe defaults, ALPN `h3`.
- Cert check: `VerifyVpnServerCert` accepts only if the end-entity cert **byte-equals** `relay.tls_cert` from the relay list; server name is ignored (quicwg.rs:1047-1066). Pinning, no CA.
- `max_concurrent_uni_streams(0)`, `max_concurrent_bidi_streams(0)` (relay cannot open streams).
- `stream_receive_window = receive_window = send_window = 4096` (`CONTROL_STREAM_WINDOW`, quicwg.rs:55). These are stream flow-control limits only; datagrams are not flow controlled.
- `MtuDiscoveryConfig`: `upper_bound(1350)` (`DEFAULT_UDP_PAYLOAD_SIZE`), `black_hole_cooldown(10 s)`.
- `max_idle_timeout(600 s)` (`QUIC_IDLE_TIMEOUT`, quicwg.rs:51).
- Congestion control: `quinn::congestion::BbrConfig::default()` (quinn marks BBR "Experimental").
- `pad_to_mtu(quic_frame_padding)`.
- Not set (quinn defaults apply, from quinn-proto config/transport.rs:320-357): `keep_alive_interval = None`, `initial_mtu = 1200`, `datagram_receive_buffer_size = 1_250_000`, `datagram_send_buffer_size = 1 MiB`, `enable_segmentation_offload = true`, `initial_rtt = 333 ms`.
- Endpoint: `EndpointConfig::default()` (max UDP payload 1472) unless small MTU: `max_udp_payload_size(1200)` when interface MTU < 1350+28 or flag `forceSmallMtu` (net.rs:105-127).
- Per-step timeout 30 s (`QUIC_STEP_TIMEOUT`, quicwg.rs:53) for connect, open stream, id exchange, each op.

### 1.5 TCP+TLS transport (quicwg.rs:587-615, 722-726) [V]

- Non-blocking TCP socket, `TCP_NODELAY`, rustls with same pinned-cert verifier, ALPN `h2`, same SNI.
- Same ports as QUIC (`relay.ports`).
- Enabled only by feature flag `tcpTlsTunnel`. There is no automatic QUIC->TCP fallback [V: `use_tcp_tls` is a plain bool from the flag, client_state.rs:539].

### 1.6 Ports [V]

- Ports come from the API per relay (`OneRelay.ports: Vec<u16>`). API test data: `[53, 443]` (api: cmd/relay.rs:33). The client probes every port (max 5 per relay) and keeps the lowest RTT (relay, port) pair. No user port choice.

### 1.7 Obfuscation [V]

- QUIC looks like HTTP/3: ALPN `h3`, SNI `example.com` by default (`DEFAULT_RELAY_SNI`, constants.rs:12), override with `setSniRelay`. TCP mode looks like HTTPS/2 (ALPN `h2`).
- Optional `pad_to_mtu` for QUIC packet size uniformity.
- WG messages are hidden inside QUIC/TLS encryption, so DPI does not see WG headers.

### 1.8 MTU and fragmentation [V]

- TUN MTU and WG inner MTU fixed at `TUNNEL_MTU = 1280` (quicwg.rs:61). WG data message for a 1280 B packet = 1312 B (32 B WG overhead).
- Outer UDP payload: PMTUD from 1200 up to 1350 (or fixed 1200 in small-MTU mode).
- If a WG message is larger than `conn.max_datagram_size()`, `WgMessageFragmenter::fragment` splits it in two halves, each with an 8-byte header: `[type=170][flags: bit0=second fragment][max_message_size u16 LE][message_idx u32 LE]` (api: wg_fragment/mod.rs:7-12, split.rs:10-41).
- Reassembly ring buffer 10_000 slots, max fragment size 1540 B (quicwg.rs:77, 83; api: wg_fragment/merge.rs:32-77).
- quinn-udp sets DF (no IP fragmentation) on Linux/Android [V in quinn-udp unix.rs:126-135].
- [I] Until PMTUD reaches ~1350, near-full-size packets are fragmented (initial MTU 1200). At 1350 a 1312 B WG message fits only if QUIC header overhead (DCID length + PN + 16 B tag + frame byte) stays under ~38 B. Per-second tick logs show `wg_tx_fragmented_count`.

### 1.9 WireGuard specifics [V]

- No preshared key, no persistent keepalive (`Tunn::new(.., None, None, index, None)`; neptun noise/mod.rs:205-212). No post-quantum PSK.
- `update_timers` called at 1 Hz (`WG_TIMER_TICK`, quicwg.rs:60).
- Single `Mutex<WgState>` guards encapsulate and decapsulate (neptun methods take `&mut self`, noise/mod.rs:274, 305).

### 1.10 Liveness and reconnect (liveness.rs, tunnel_state.rs) [V]

- Probe: ICMPv4 echo, total size 100 B (`LIVENESS_MTU`, quicwg.rs:68), TTL 255, from client IPv4 to `gateway_ip_v4` (API field, default `10.64.0.1`, api: types.rs:421-433). Payload starts with a fixed 32-byte prefix `b"obs-ping\x75\xf8..."` + random bytes (liveness.rs:26, 233-255).
- Ping period: 1 s when user traffic was sent since the last ping or probes are outstanding-lost (`BUSY_PING_PERIOD`), else 55 s (`IDLE_PING_PERIOD`) (liveness.rs:12-13, 127-131). A ping is also sent inline with user traffic when overdue (liveness.rs:83-92).
- Loss timeout per probe: 2x slowest RTT among recent pongs (window 100), clamped to [1 s, 30 s] (liveness.rs:21-23, 61-67).
- Dead when lost probes > 4 (`MAX_ALLOWED_LOST_PROBES`), or > 1 after `wake()` until one pong arrives (liveness.rs:10-11, 108-125).
- Clock: `WakeInstant` uses `CLOCK_BOOTTIME` on Android (advances in suspend) (wake_instant.rs:47-55).
- Latency reported = probe RTT (quicwg.rs:503-506), surfaced as `latestLatencyMs`.
- On death or QUIC error: state -> `Connecting{reconnecting:true}`; loop retries every >= 1 s (`DEBOUNCE_PERIOD`, tunnel_state.rs:177). **No exponential backoff** on connect errors. Each retry is a full connect (new relay race, new API tunnel). `ExitSelectionState` excludes already-tried exits: same exit id excluded, datacenter excluded after 2 picks, city after 4, country after 6; filters reset when exhausted, on success, or on disconnect (exit_selection.rs:51-86).
- WG key rotation check happens only when target becomes "disconnected" (tunnel_state.rs:197-201).

### 1.11 Leak protection during (re)connect [V]

- Before every connect attempt the core calls `set_os_network_config(OsNetworkConfig::dummy(..), QuicWgConnPacketSender::new(None))` (tunnel_state.rs:250-268). Android establishes a TUN with dummy addresses (`10.75.76.77`, `fc00:bbbb:bbbb:bb01::c:4c4d/128`, DNS `10.64.0.99` or the content-block IP, MTU 1280; network_config.rs:42-49) and routes. Packets read from it go to a null sender and are dropped. So while the user wants "connected", traffic is captured and blackholed until the real tunnel is up.
- If `set_os_network_config` fails -> `SetOsNetworkConfig` error (maps to `other`).
- With target network interface `None` -> error `NoInternet` and wait (tunnel_state.rs:365-379).
- Android docs: enforcement is routing only unless system Always-on + Block connections without VPN (docs/traffic-enforcement.md:15).

### 1.12 Roaming [V]

- Kotlin `registerBestMatchingNetworkCallback(INTERNET + NOT_VPN)` -> `setNetworkInterface(name, index)` (ObscuraVpnService.kt:198-220, 457-466). Core stores it, reads interface MTU via `SIOCGIFMTU` (client_state.rs:321-359), and drops the cached API client.
- `TargetState` includes `network_interface`; `matches_target` compares it (tunnel_state.rs:163-171). Any interface change => tunnel torn down and fully reconnected.
- On Android the core does **not** bind sockets to the interface (net.rs:45-48; API client gets `None`, client_state.rs:156-157). Correct routing relies on the app being excluded from its own VPN (`addDisallowedApplication(packageName)`, ObscuraVpnService.kt:398).

---

## 2. Entry relay, exit selection, data model

### 2.1 Entry relay [V]

- No user choice. `ManagerCmd` and `TunnelArgs` have no relay field. Selection = latency race over the full relay list (section 1.2). The API `CreateTunnel` does accept `relay: Option<String>` and the core already sends the chosen id.

### 2.2 Exit selector (exit_selection.rs:9-40) [V]

```rust
#[serde(rename_all = "camelCase")]           // renames variants only, fields stay snake_case
pub enum ExitSelector {
    Any {},
    Exit { id: String },
    Country { country_code: CountryCode },     // CountryCode(pub String) -> plain string
    City { #[serde(flatten)] city_code: CityCode }, // -> { country_code, city_code }
}
```

JSON (matches obscura-ui/src/bridge/commands.ts:129-148):

```json
{"any":{}}
{"exit":{"id":"<exit id>"}}
{"country":{"country_code":"us"}}
{"city":{"country_code":"us","city_code":"nyc"}}
```

`TunnelArgs` (manager.rs:137-141): `{"exit": <ExitSelector>}`; default `{"exit":{"any":{}}}`.

Country code case: UI comment says lowercase (`obscura-ui/src/common/api.ts:8`); API test data shows `"US"`. Matching is exact string equality (exit_selection.rs:26-33), so always copy codes from the exit list. [V]

### 2.3 Exit ranking (exit_selection.rs:74-79) [V]

Sort key, highest wins: `(is_preferred_by_relay, same_city_as_relay, same_country_as_relay, tier, random u32)`. So `Any` picks an exit close to the selected (lowest-latency) relay.

### 2.4 Data model (api: types.rs) [V]

`OneExit` (types.rs:518-538), JSON snake_case:

```json
{"id":"NYC-001","country_code":"US","city_code":"nyc","city_name":"New York",
 "provider_id":"us-nyc-wg-301","provider_url":"https://mullvad.net/servers",
 "provider_name":"Mullvad VPN","provider_homepage_url":"https://mullvad.net",
 "datacenter_id":42,"tier":0}
```

- `datacenter_id: u32` default random if absent; `tier: u8` default 255 if absent; higher tier preferred.
- No tags, load, IPv6 flag, ownership flag, or exit IP in the model.

`ExitList` (`GET /api/exits2`, ETag): `{"exits":[OneExit...]}` (api: cmd/exit2.rs:12-24). Legacy `GET /api/exits` returns a bare array.

`OneRelay` (types.rs:484-511), `GET /api/relays` (ETag):

```json
{"id":"NYC-001","country_code":"US","city_code":"nyc","city_name":"New York",
 "preferred_exits":[{"id":"nyc-wg-30"}],"ip_v4":"192.0.2.1","ports":[53,443],
 "tls_cert":"<base64 DER>"}
```

`ObfuscatedTunnelConfig` (types.rs:425-444):

```json
{"client_pubkey":"<b64>","client_ips_v4":["10.64.12.34/32"],
 "client_ips_v6":["fc00:bbbb:bbbb:bb01::2/128"],"dns":["10.64.0.1"],
 "gateway_ip_v4":"10.64.0.1","exit_pubkey":"<b64>"}
```

`OneTunnel`: `{id, label?, status:{type:created|connected|disconnected, when}, config:{type:"obfuscated", ...}, relay: OneRelay, exit: OneExit}`.

### 2.5 Pinned locations and memory [V]

`PinnedLocation` (config/persistence.rs:411-419): `{"country_code": String, "city_code": String, "pinned_at": <unix seconds>}`. Stored only; not used by the selector. `last_chosen_exit_selector` (non-Any successful selector) and `last_exit_selector` (any) exposed as `Status.lastChosenExit` / `Status.lastExit`.

### 2.6 Lists caching [V]

- Persisted in config as `ConfigCached { version: Artificial(uuid) | ETag(bytes), last_updated, value }` (config/cached.rs). Revalidated with `If-None-Match`.
- `GetExitList { knownVersion }` long-polls until a cached list exists with a different version (manager.rs:394-415). Output `CachedValue`: `{"version":"<base64>","last_updated":<unix s>,"value":{"exits":[...]}}` (cached_value.rs:7-16).
- Relay list has no ManagerCmd getter.

---

## 3. DNS, IPv6, LAN, split tunnelling

### 3.1 DNS servers [V]

- Default: `ObfuscatedTunnelConfig.dns` from the API (example `10.64.0.1`).
- Content blocking (network_config.rs:69-90, 125-131): if `exit.provider_name == "Mullvad VPN"` and any flag set, DNS = single IP `100.64.0.<b>` with bits `ad=1, tracker=2, malware=4, adult=8, gambling=16, social_media=32`. Otherwise the API DNS list is used (flags silently ignored on non-Mullvad exits).
- `DnsConfig { Default, System }` (network_config.rs:62-67) -> `OsNetworkConfig.useSystemDns`. Android Kotlin then adds no DNS servers (ObscuraVpnService.kt:407-414). The official Android UI never sets it.
- No custom DNS, DoH, DoT, DNSSEC options in core.
- Android Private DNS (system DoT) overrides VPN DNS; official app only warns (ObscuraVpnService.kt:407-411).

### 3.2 OsNetworkConfig sent to Android (network_config.rs:97-113; Kotlin OsNetworkConfig.kt) [V]

```json
{"dns":["10.64.0.1"],"ipv4":"10.64.12.34","ipv6":"fc00:bbbb:bbbb:bb01::2/128",
 "routes":[{"address":"0.0.0.0","prefix":0},{"address":"::","prefix":0}],
 "mtu":1280,"useSystemDns":false}
```

Note `ipv4` is a bare address (no prefix); Kotlin defaults /32. `ipv6` is an `Ipv6Network` string with prefix.

### 3.3 Local network access (local_network.rs) [V]

- Config default **Enabled** (config/persistence.rs:303-308).
- Disabled -> routes `0.0.0.0/0`, `::/0`.
- Enabled -> 0/0 minus `LAN_V4` = {10/8, 172.16/12, 192.168/16, 169.254/16, 255.255.255.255/32, 224.0.0.0/24, 239/8}, and ::/0 minus `LAN_V6` = {fe80::/10, fc00::/7, ff01::/16 .. ff05::/16}; DNS server IPs inside those ranges are re-added as host routes so DNS stays in the tunnel (local_network.rs:8-60, test 130-194).
- Side effect [V]: with LAN access on, the whole `10.0.0.0/8` except the DNS host leaves the tunnel, including in-tunnel Mullvad addresses other than the DNS IP. The liveness gateway `10.64.0.1` equals the default DNS, so it stays routed [V for default values].

### 3.4 IPv6 [V]

- Tunnel requires and assigns an IPv6 address (`NoIpv6Ip` error otherwise) and routes `::/0`.
- Outer sockets are IPv4 only (`Socket::new(Domain::IPV4, ..)`, net.rs:29; relay `ip_v4` only). [I] On IPv6-only mobile networks this depends on Android CLAT/464XLAT.
- Liveness is IPv4 ICMP only.

### 3.5 Split tunnelling [V]

- None in core. Per-app include/exclude must be done in Kotlin via `VpnService.Builder`. The app's own package must stay excluded (core sockets are not protected or bound on Android).

---

## 4. Account, auth, payments, API transport

### 4.1 Login / token [V]

- `Login { account_id, validate }` (manager.rs:215-236). `validate=true`: `POST /api/token2` `{"account_id":"...","pow":null}` -> `{"auth_token":"...","url_override":{"api":"..","web":".."}|null}` (api: token.rs). `url_override` switches the API URL (demo accounts) and retries, max 10.
- `validate=false`: stores the account id without network access; token acquired lazily on first request.
- Auth: `Authorization: Bearer <token>`; on `MissingOrInvalidAuthToken` the client re-acquires, max 3 tries (api: client.rs:243-279).
- Rate limit: `RateLimitExceeded`/`SignupLimitExceeded` may carry `pow_challenge {nonce, threshold(hex 32B), puzzles}`; client solves Argon2id (m=16 MiB, t=1, p=1, nonce as secret) with 10 s timeout and retries (api: client.rs:326-371, pow.rs:8-101).
- Token cached in memory and persisted plaintext in `config.json` `cached_auth_token` (client_state.rs:636-643).
- Account number format: 20 digits, last = Verhoeff check digit; display `XXXX - XXXX - XXXX - XXXX - XXXX`; Debug prints only the 3-digit hint (api: types.rs:15-122).

### 4.2 Account info (api: types.rs:133-208) [V]

`GET /api/account` -> `AccountInfo`:

```json
{"id":"...","active":true,"top_up":{"active":true,"credit_expires_at":1000},
 "stripe_subscription":null,"apple_subscription":null,"google_subscription":null,
 "primary_funding":"top_up","monero_pending_payments":[{"id":"..","status":"confirming"}],
 "referral_code":"asdf","has_referrer":true,"received_free_months":2,
 "current_expiry":1000,"auto_renews":null,"has_paid":true,
 "free_month_on_next_payment":true,"free_trial":{"active":false,"expires_at":900},"nnn":null}
```

- `active` is the canonical funded flag. `current_expiry` (unix s) is None when auto-renewing or inactive. `primary_funding` in `apple_subscription | free_trial | google_subscription | stripe_subscription | top_up | unknown`.
- Stored as `AccountStatus {"account_info":{..},"last_updated_sec":u64}` in config and `Status.account`.
- No "devices/slots" counter in AccountInfo. Slot limit only appears as `TunnelLimitExceeded` -> `ConnectErrorCode::noSlotsLeft`.

### 4.3 Other account cmds [V]

- `ApiServiceStatus` -> `{"free_trial":{"available":bool},"nnn":{"claimed_months_target":u64,"claimed_months_total":u64,"submissions_open":bool}}` (unauthenticated).
- `ApiDeleteAccount` -> `{}`.
- `Logout` (client_state.rs:205-239): replaces config with defaults, keeping only `api_url`, `in_new_account_flow`, and **`old_account_ids` (previous account id appended)**. WG key, token, caches, flags, pinned locations, DNS cache are reset.

### 4.4 Payments [V]

- In ManagerCmd: Google Play only (`apiGoogleBillingDetails {promoCode}` -> `{product_id, base_plan_id, offer_id}`; `apiGoogleAssociateAccount {purchaseToken, promoCode}`), and Apple.
- In API crate but not wired: `CreateLightningTopUp {months, sale}` -> `{id, invoice, millisatoshis}` (`POST lightning/top_up`), `CheckLightningTopUp {id}` -> `{status: unpaid|paid|expired}`; `CreateMoneroTopUp {months>=3, sale}` -> `{id, pay_uri}`, `CheckMoneroTopUp`; Stripe checkout/portal/top-up; `ListPrices` (GET `prices`); referral and friend codes; newsletter. API docs say the payments API is "only officially supported for our partners" (api: doc/payments.md).
- Official UI path: open `https://obscura.com/pay#account_id=<id>` (fragment, not sent to server by the browser), `.../subscription/stripe/checkout#account_id=`, `.../account/tunnels#account_id=` (obscura-ui/src/common/accountUtils.ts:130-148).

### 4.5 API transport, fallback, anti-censorship [V]

- Base URL `https://v1.api.prod.obscura.net/api` (constants.rs:6-7), override `setApiUrl` (also rotates WG key, client_state.rs:306-315).
- Alternate hosts: `[api_host_alternate or "crimsonlance.net"]` (constants.rs:11; client_state.rs:138).
- `send_http` (api: client.rs:405-452): try primary; on connect/timeout error try each alternate host (same path); then retry primary **without SNI**; else return first error.
- Cert verification: bundled `webpki-roots` (not the Android system store), certificate must be valid for any of {primary host, alternate hosts} (api: client.rs:454-546). TLS provider on Android = aws-lc-rs installed as process default (android/ffi.rs:54-56).
- Timeouts: total 60 s, read 10 s (api: client.rs:381-383). Response cap 5 MB.
- DNS: custom resolver with persisted cache seeded `v1.api.prod.obscura.net -> <API IP>` (constants.rs:8-9; config/dns_cache.rs). Cache hit returns immediately but **a system lookup is still spawned on every resolve** and its result written back (dns.rs:19-40). Lookups run outside the VPN on Android (app excluded).
- API over the tunnel: not supported on Android [V: app excluded, no binding]. Domain fronting: no true fronting; the alternate domain and no-SNI retry are the anti-blocking measures.

---

## 5. Feature flags, user agent, telemetry, privacy

### 5.1 Feature flags (config/feature_flags.rs) [V]

`FeatureFlags` JSON camelCase: `{"quicFramePadding":bool?,"killSwitch":bool?,"forceSmallMtu":bool?,"tcpTlsTunnel":bool?}`; unknown keys preserved. `SetFeatureFlag{flag, active}`: `active=true` sets `Some(true)`, `active=false` sets `None` (feature_flags.rs:27-29). Unknown flag names are logged and ignored. `Status.featureFlagKeys` = `["quicFramePadding","killSwitch","forceSmallMtu","tcpTlsTunnel"]`.

| Flag | Effect | Android |
|---|---|---|
| `quicFramePadding` | quinn `pad_to_mtu(true)` | works |
| `killSwitch` | Apple on-demand; Linux netfilter chain | no effect in core |
| `forceSmallMtu` | endpoint `max_udp_payload_size(1200)` | works |
| `tcpTlsTunnel` | TCP+TLS instead of QUIC | works |

[I] `#[skip_serializing_none]` is placed after `#[derive]` (feature_flags.rs:8-9); serde_with documents it must come before derive, so unset flags may serialize as `null`. Accept both `null` and missing.

### 5.2 User agent [V]

Passed by the host app at init (`ObscuraLibrary.initialize(configDir, userAgent)`). Official Android: `obscura.net/android/<versionName>` (ObscuraVpnService.kt:168). API docs: identity + increasing version, no user info (api: doc/user-agent.md).

### 5.3 Network traffic generated by the core [V]

- API: `token2`, `account` (GET/DELETE), `exits2`, `relays`, `tunnel` (POST), `cache_wg_key` (POST, after disconnect when account active; backoff 0, 1 s .. 60 s, 10 attempts; manager.rs:307-334), `status`, `google/*`, `apple/*`.
- Relays: on every connect, handshakes to up to 100 relays x 5 ports in parallel, 3 pings each; losers get `Stop`. Only the chosen relay gets the token.
- Debug only (`getDebugInfo` / `createDebugBundle`): DNS lookups of `www.apple.com`, `google.com`, `v1.api.prod.obscura.net`; HTTPS GET `https://www.apple.com/robots.txt`, `https://google.com/robots.txt`, `https://v1.api.prod.obscura.net/api/ping` (with and without SNI), `https://apple.com/api/ping` and `https://google.com/api/ping` against Obscura IPs (client_state.rs:845-917). Cert checks disabled for these probes (debug_bundle/http.rs:49-53).
- **No telemetry, analytics, crash reporting, or log upload** in rustlib or obscuravpn-api (all modules read; grep for upload/telemetry/analytics/sentry finds nothing). `NoticesClient` (api: notices.rs) exists but is not used by rustlib.

### 5.4 Privacy-relevant behaviour (for the new app) [V unless marked]

- The API sees the device's real IP, the account (token), and the chosen relay and exit on every `CreateTunnel` (each connect and reconnect). [I: server-side retention unknown.]
- Every relay in the list sees a handshake from the device IP on each connect.
- System DNS lookups of the API hostname happen on the underlying network on each API request (dns.rs:20).
- On Android, `config.json` in the app files dir holds in plaintext: account id, auth token, WG secret key (`WgKeyStore::Plaintext`, TODO OBS-2699, android/ffi.rs:60-62), old account ids, cached account info, DNS cache, cached relay/exit lists.
- `Status` JSON contains the full account number (`accountId`, and `account.account_info.id`). The official Kotlin service logs raw status JSON through the Rust logger (`"getStatus completed $data"`, ObscuraVpnService.kt:363; `"status updated $status"` :325), so the full account number lands in persisted logs. Avoid this in the new app.
- Logs (logging.rs): logcat tag `ObscuraNative` plus persisted JSON lines in `<filesDir>/rust-log/rust-log.ndjson`, rotated at 10 MB, 24 files kept, xz level 2, level INFO forced (`EnvFilter::from_default_env().add_directive(INFO)`). Content includes relay ids/IPs, exit ids, tunnel ids, client WG pubkey, interface names, API IPs, per-second tick stats, liveness events. `ConfigDebug` redacts account id, token, WG key, account status.
- Debug bundle: local zip (`<android_cache_dir>/debug-archives/Obscura Debug Bundle <ts>.zip`) with info, debug info, rust logs, `logcat -d`, process exit reasons (debug_bundle/mod.rs:136-173). Not uploaded by core.
- WG key: x25519 from `OsRng`; rotated 30 days after first use (checked at disconnect), on API demand, on API URL change, or manually (config/persistence.rs:595-645). Tunnel addresses are stable until key rotation (docs/traffic-enforcement.md:74).

---

## 6. Performance knobs

### 6.1 Present today [V]

- Release profile: `codegen-units = 1`, `lto = true` (fat), `panic = "abort"`, `debug = "line-tables-only"` (Cargo.toml:19-23). Toolchain 1.89.0.
- quinn-udp >= 0.5.16 pinned because 0.5.6 regressed Android upload speed (Cargo.toml:51-54).
- quinn-udp on Android: GRO (`UDP_GRO=104`), GSO (`UDP_SEGMENT=103`, kernel >= 4.18), `recvmmsg` batch 32, DF set (quinn-udp 0.5.16 unix.rs:126-135, 806-830, 1000-1010). quinn `enable_segmentation_offload = true` default.
- BBR congestion control; PMTUD 1200..1350; idle timeout 600 s.
- Tokio multi-thread runtime, default worker count (`Runtime::new()`, android/ffi.rs:48).

### 6.2 Hot path shape [V]

- TX: TUN read task (android/tunnel.rs:28-52) reads one packet per `read()` into a 1280 B buffer, calls `tunnel.send(once(packet))` -> lock `Mutex<WgState>` -> `encapsulate` -> `Bytes::copy_from_slice` -> optional fragment -> `conn.send_datagram` (quicwg.rs:383-426, 922-931).
- RX: single loop in `TunnelState::maintain` -> `conn.receive()` -> `read_datagram` -> reassemble -> lock same mutex -> `decapsulate` -> `Bytes::copy_from_slice` -> `os_impl.packet_for_os` -> lock `Mutex<Option<Tun>>` -> one `write()` per packet (tunnel_state.rs:332-343; android/os_impl.rs:65-69; tunnel.rs:56-69).
- Crypto per packet: WG ChaCha20-Poly1305 (neptun) + QUIC AEAD (ring, TLS 1.3 suite).

### 6.3 Tuning opportunities for Android speed [I]

1. Batch TUN reads (loop until `EAGAIN`, pass an iterator to `send`, which already accepts `impl Iterator`); reuse `os/packet_buffer.rs` (`PacketBuffer`, 100 packets).
2. Remove per-packet copies (encapsulate directly into `BytesMut`).
3. Separate TX/RX contention: one `Mutex<WgState>` serialises both directions; neptun needs `&mut`, so consider a dedicated crypto task per direction or a lock-free split.
4. Raise UDP socket buffers (`SO_RCVBUF`/`SO_SNDBUF` via socket2 in `net.rs::new_udp`; unprivileged apps are capped by `rmem_max`/`wmem_max`).
5. Raise `datagram_receive_buffer_size` / `datagram_send_buffer_size` for high bandwidth-delay paths; consider `initial_mtu(1350)` when interface MTU >= 1500 to avoid early fragmentation.
6. Compare BBR (experimental in quinn) vs Cubic on real mobile links.
7. Lower log level in release (INFO + per-second tick logs + JSON file writes cost CPU and battery). `logging.rs:78-80` hardcodes the INFO directive.
8. Add JNI `wake()` and call it on screen-on / doze exit, so dead tunnels are found in ~1 lost probe.
9. Add exponential backoff to connect retries for battery (core retries every 1 s).
10. QUIC NAT keepalive: idle ping every 55 s; some mobile NATs expire UDP mappings faster. A `keep_alive_interval` (e.g. 25 s) would trade battery for fewer silent drops.
11. `debug = "line-tables-only"` only affects size; strip symbols in the Gradle packaging step.

---

## 7. ManagerCmd reference (exact JSON) [V]

Encoding: externally tagged, variant and field names camelCase (`#[serde(rename_all = "camelCase", rename_all_fields = "camelCase")]`, manager_cmd.rs:129-131). Nested API types keep snake_case.

```json
{"apiAppleAssociateAccount":{"appTransactionJws":"..."}}
{"apiDeleteAccount":{}}
{"apiGetAccountInfo":{}}
{"apiGoogleAssociateAccount":{"purchaseToken":"...","promoCode":null}}
{"apiGoogleBillingDetails":{"promoCode":null}}
{"apiServiceStatus":{}}
{"createDebugBundle":{"userFeedback":null,"bundleInfo":{"AppVersion":"..","AndroidSDK":36},"androidCacheDir":"/data/user/0/<pkg>/cache"}}
{"createServiceDebugBundle":{}}
{"getDebugInfo":{}}
{"getExitList":{"knownVersion":null}}           // or base64 version
{"getStatus":{"knownVersion":null}}             // or status UUID string
{"getTrafficStats":{}}
{"terminateProcess":{}}                         // exits process after 3 s
{"login":{"accountId":"01234567890123456789","validate":true}}
{"logout":{}}
{"ping":{}}
{"refreshExitList":{"freshness":60000}}         // milliseconds
{"deleteServiceDebugBundle":{"token":"<uuid>"}}
{"rotateWgKey":{}}
{"setApiHostAlternate":{"host":null}}
{"setApiUrl":{"url":null}}
{"setAutoConnect":{"enable":true}}
{"setDnsContentBlock":{"value":{"ad":true,"tracker":true,"malware":true,"adult":false,"gambling":false,"socialMedia":false}}}
{"setFeatureFlag":{"flag":"tcpTlsTunnel","active":true}}
{"setInNewAccountFlow":{"value":false}}
{"setPinnedExits":{"exits":[{"country_code":"us","city_code":"nyc","pinned_at":1760000000}]}}
{"setSniRelay":{"host":"example.com"}}
{"setTunnelArgs":{"args":{"exit":{"city":{"country_code":"us","city_code":"nyc"}}},"active":true}}
{"setUseSystemDns":{"enable":false}}
{"setLocalNetworkAccess":{"enable":false}}
{"setTailscaleBypass":{"enable":false}}
{"setWireGuardBypass":{"enable":false}}
```

`BundleInfo` keys are PascalCase with special names `AndroidSDK`, `DotNETFramework`, `OSArchitecture`, `OSVersion`, `OSVersionString`, `PID`, `RAMAvailableGiB`, `RAMLogicalGiB`, `RAMPhysicalGiB`, `SourceID`; only `AppVersion` is required (debug_bundle/bundle_info.rs:4-46).

Results (`ManagerCmdOk` is `#[serde(untagged)]`, manager_cmd.rs:220-240): the raw output JSON; `Empty` serialises as `null`. Errors: Java `ErrorCodeException(String)` with one of `apiAssociateAccountConflict, apiError, apiInvalidAccountId, apiNoLongerSupported, apiRateLimitExceeded, apiSaleNotFound, apiSignupLimitExceeded, apiUnreachable, configSaveError, notLoggedIn, other` (manager_cmd.rs:43-58).

---

## 8. Output shapes [V]

`Status` (manager.rs:54-74, camelCase):

```json
{"version":"<uuid>","vpnStatus":{...},"accountId":"<full number>|null","inNewAccountFlow":false,
 "pinnedLocations":[...],"lastChosenExit":{"any":{}},"lastExit":{"any":{}},
 "apiUrl":"https://v1.api.prod.obscura.net/api","account":{"account_info":{...},"last_updated_sec":0}|null,
 "autoConnect":false,"featureFlags":{...},"featureFlagKeys":[...],"useSystemDns":false,
 "localNetworkAccess":true,"tailscaleBypass":true,"wireguardBypass":true,
 "dnsContentBlock":{"ad":false,"tracker":false,"malware":false,"adult":false,"gambling":false,"socialMedia":false}}
```

`VpnStatus` (manager.rs:118-135):

```json
{"connecting":{"tunnelArgs":{"exit":{...}},"connectError":"noRelay"|null,"reconnecting":true}}
{"connected":{"tunnelArgs":{...},"exit":OneExit,"relay":OneRelay,"clientPublicKey":"<b64>","exitPublicKey":"<b64>","transport":"quic"|"tcpTls"}}
{"disconnected":{}}
```

`connectError` values (errors.rs:22-37): `accountExpired, apiError, apiRateLimitExceeded, apiUnreachable, invalidAccountId, noInternet, noLongerSupported, noRelay, noSlotsLeft, notLoggedIn, other`.

`ManagerTrafficStats` (manager.rs:423-431): `{"connectedMs":u64,"connId":"<uuid>","txBytes":u64,"rxBytes":u64,"latestLatencyMs":u16}`. Bytes are inner IP bytes, accumulated across reconnects within one session; `connId` is random per call while disconnected and fixed for a connecting/connected session (tunnel_state.rs:74-92, 98-111).

---

## 9. Persisted config (`<configDir>/config.json`, config/persistence.rs:226-291) [V]

Fields: `api_host_alternate, api_url, account_id, auto_connect, dns_content_block, dns_cache, old_account_ids, feature_flags, in_new_account_flow, cached_auth_token, cached_exits, cached_relays, pinned_locations, last_chosen_exit (deprecated), last_chosen_exit_selector, last_exit_selector, tunnel_active, tunnel_args, sni_relay, wireguard_key_cache, dns ("Default"|"System"), local_network_access ("Enabled"|"Disabled"), tailscale_bypass, wireguard_bypass, cached_account_status`. Every field uses a tolerant deserializer (bad value -> default). Broken file -> renamed `config-backup-<ts>.json` and reset. Writes are atomic (temp file + fsync + rename). `configDir` on Android = `context.getFilesDir()`.

---

## 10. Gaps the new app must handle or patch [V for gap, I for proposed fix]

1. **Entry relay choice**: add e.g. `relay: Option<RelaySelector>` to `TunnelArgs` (with `#[serde(default)]`) and filter `relays.value` before `race_relay_handshakes` in `client_state.rs::select_relay`; exit ranking already uses the relay's city and `preferred_exits`. Also expose the relay list (new `GetRelayList` cmd).
2. **JNI surface is tied to class names**: `net/obscura/vpnclientapp/client/ObscuraLibrary$FfiHandle`, `.../client/ErrorCodeException`, `.../services/ObscuraVpnService` (static `ffiSetNetworkConfig(String): Int`, `ffiGetProcessExitReasons(): String`) and `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_{initialize,jsonFfi,setNetworkInterface,unsetNetworkInterface,flushAndStopPersistedLog,forwardLog}` (android/class_cache.rs:19-24, android/ffi.rs). Keep these names or patch them.
3. **Wake**: add a JNI `wake` export calling `global.manager.wake()`.
4. **Key storage**: use `WgKeyStore::Keychain { secret_key, set_secret_key }` backed by Android Keystore-wrapped storage instead of `Plaintext` (android/ffi.rs:62). Also consider encrypting `config.json` (account id, token).
5. **Payments**: add ManagerCmd variants for `CreateLightningTopUp`, `CheckLightningTopUp`, `CreateMoneroTopUp`, `CheckMoneroTopUp`, `ListPrices` (GET with `code: None` only), or open the `obscura.com/pay#account_id=` URL.
6. **Custom DNS / DoH**: not in core; implement on Kotlin side (override `dns`, adjust routes for LAN-range resolvers) or add a `DnsConfig::Custom(Vec<IpAddr>)` variant.
7. **Privacy defaults**: consider default `localNetworkAccess=false`, logging level WARN, not logging Status JSON, clearing `old_account_ids` on logout.
8. **Kill switch on Android**: guide the user to system Always-on + Block connections without VPN (`Settings.ACTION_VPN_SETTINGS`); core already blackholes during reconnect.
9. **Plain WireGuard mode**: not available; would need a new transport using `TunnelConfig::UdpPort`.
