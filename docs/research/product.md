# Obscura VPN: product research (key: `product`)

Research date: 2026-10-20. Upstream repo HEAD: `85ef7e2692dc811a023360f08865bfb90815649c` (2026-10-19, "feat: NNN banner + popup (#1171)"), `tag.json` version `1.182` (unreleased; latest GitHub release is v1.181, 2026-09-30). API crate: `obscuravpn-api` rev `e94483e7331b521d4d29ee7411a83e4adeffcc9d` (2026-10-15), checked out at `~/.cargo/git/checkouts/obscuravpn-api-e8686c0572b4accd/e94483e`.

Legend: **[V]** = verified by me in code, on an official page, or in a store listing. **[I]** = inference or recommendation. Paths without a prefix are relative to `/tmp/obscura-research/obscuravpn-client/`. `api:` = relative to the obscuravpn-api checkout.

Note on domains [V]: `https://obscura.net` returns `308 Permanent Redirect` to `https://obscura.com/`. The marketing site, KB and blog now live on obscura.com. The API stays on `v1.api.prod.obscura.net` (`rustlib/src/constants.rs:6-7`), support mail stays `support@obscura.net` (`obscura-ui/src/common/links.ts:1`).

---

## 0. Summary for the native client

- Product = "Two-Party Relay": Obscura runs the first hop (relay), Mullvad runs the exit. WireGuard runs end to end from the device to the Mullvad exit. Obscura carries the WireGuard packets inside QUIC unreliable datagrams (RFC 9221), with a TCP/TLS fallback. [V]
- User picks the **exit** (any / country / city / exact exit id). The **entry relay is not user-selectable** in any official app: the client races QUIC handshakes to the relays from the API and keeps the lowest RTT. Only the web WireGuard-config generator lets the user choose a relay. The API `CreateTunnel` accepts `relay: Option<String>`, so a native client can technically pin a relay. [V for facts, I for "can pin"]
- Official Android app (v1.181) = WebView (React/Mantine) UI over the Rust core. minSdk 31, targetSdk 36. Features: connect, quick connect, exit list with pins and search, DNS content blocking (6 categories), local network access, WireGuard key rotation, experimental flags (packet padding, small MTU, TCP/TLS), auto-connect at launch, notification actions, debug bundles, Play billing (play flavour) or none (foss flavour). No split tunnelling, no custom DNS, no Quick Settings tile, no in-app kill switch (uses system Always-on), no relay choice, no port choice. [V]
- Gaps a privacy-expert native client can fill: per-app split tunnelling, QS tile, relay pinning (if FFI allows), exit-key cross-check against Mullvad public data, no cloud backup of config, Android Keystore wrapping of the WG secret, configurable key-rotation interval, lockdown guidance and detection, custom DNS, IPv6 control, fully native Compose UI. [I]

---

## 1. Main features of the official clients

### 1.1 Platforms and minimum versions [V]

| Platform | Distribution | Minimum OS | Source |
|---|---|---|---|
| macOS | DMG from obscura.com (not Homebrew), Sparkle updater (`https://pkgs.obscura.net/macos/appcast.xml`) | macOS 14.0+ Sonoma | https://obscura.com/knowledge/downloading-obscura/ |
| iOS | App Store id `6746820048` | iOS 17.0+ | same; https://apps.apple.com/app/obscura-vpn/id6746820048 |
| Android | Google Play `net.obscura.vpnclientapp`, Obtainium (GitHub releases APK `obscura-<ver>.apk`) | Android 12.0+ (minSdk 31) | https://obscura.com/download/android/ ; `android/app/build.gradle.kts:19` |
| Windows | MSI x64 / ARM64 from `windows-pkgs.obscura.com` (e.g. `Obscura_1.180_x64.msi`), Microsoft Store id `SovereignEngineering.ObscuraVPN_rxzjtnvhd6q16` (site.webmanifest) | Windows 10 20H1+ | https://obscura.com/download/windows/ |
| Linux | apt/dnf/pacman repos at `https://linux-pkgs.obscura.com/{deb,rpm,arch}`; GUI + experimental CLI | Debian 13, Ubuntu 24.04+ (site) / 26.04 (README), Fedora 44, RHEL 10 (+EPEL), Arch; x64 only | https://obscura.com/download/linux/ ; `README.md:414-422` |
| Any | WireGuard config from web portal (`/account/tunnels/`) | any WireGuard client | https://obscura.com/knowledge/using-obscura-via-wireguard/ |

Not on F-Droid, Accrescent or IzzyOnDroid (none listed on official pages; launch post says "We're also working on publishing versions for other alternative app stores"). [V]

### 1.2 Architecture: two-party relay, WireGuard over QUIC [V]

Homepage FAQ "Give me the technical details!" (https://obscura.com/#faq-technical), verbatim:

> Obscura's servers relay the WireGuard tunnel between your device and Mullvad's servers. Essentially, WireGuard-over-QUIC.
> `<user>  -->  Obscura Server  -->  Mullvad Server  -->  <internet>`
> - Obscura's servers are unable to decrypt the WireGuard packets it relays, since they are encrypted to a Mullvad server's WireGuard pubkey.
> - Mullvad's servers never see your connecting IP since Obscura's servers are effectively doing NAT.
> Your device connects to Obscura's servers over QUIC, meaning:
> - Obscura connections are harder to detect or block since they look like regular internet traffic (HTTP/3 uses QUIC for transport).
> - Obscura avoids the TCP-over-TCP meltdown problem since we use QUIC's unreliable datagram extension.

Blog "Trust, 2-Party Relays, and QUIC" (2025-02-11, https://obscura.com/blog/bootstrapping-trust/): "For obfuscation, we use quinn-rs's implementation of RFC 9221 to tunnel WireGuard packets over QUIC." Footnote 8: "Your locally-generated WireGuard pubkey is registered with Mullvad's servers, and WireGuard's regular Noise_IK handshake happens between you and the Mullvad exit server."

Code facts:
- Transport kinds: `TransportKind { Quic = 'quic', TcpTls = 'tcpTls' }` (`obscura-ui/src/common/appContext.ts:123-126`).
- QUIC TLS SNI defaults to `"example.com"`: `pub const DEFAULT_RELAY_SNI: &str = "example.com";` (`rustlib/src/constants.rs:12`); overridable via config `sni_relay` / dev command `setSniRelay` (`rustlib/src/client_state.rs:527`, `obscura-ui/src/views/DeveloperView.tsx:106`). Relay TLS cert is pinned per relay (`relay.tls_cert` from API, `rustlib/src/relay_selection.rs:97`).
- API backup domain: `DEFAULT_API_BACKUP_DOMAIN: &str = "crimsonlance.net"`; API IP seed `<API IP>` (`rustlib/src/constants.rs:8,11`).
- Tunnel MTU constant `TUNNEL_MTU: u16 = 1280` (`rustlib/src/quicwg.rs:61`). Release v1.162: "Automatically detect and use client-to-relay MTU for QUIC connection."
- Two tunnel kinds in the API: `CreateTunnel::UdpPort {..}` (plain WireGuard config, "static tunnel") and `CreateTunnel::Obfuscated {..}` (app QUIC tunnel), both with `relay: Option<String>, exit: Option<String>` (`api:src/cmd/tunnel/create.rs:9-24`). Example response: `"dns": ["10.64.0.1"]`, `"ports": [53, 443]` on the relay (`api:src/cmd/tunnel/create.rs:57,73`).

### 1.3 Exit location choice [V]

- Selector type (`rustlib/src/exit_selection.rs:11-23`, mirrored in `obscura-ui/src/bridge/commands.ts:125-148`):
  ```ts
  export interface TunnelArgs { exit: ExitSelector }
  export type ExitSelector =
    | { any: {} }
    | { exit: { id: string } }
    | { city: { country_code: string, city_code: string } }
    | { country: { country_code: string } };
  ```
- UI exposes: **Quick Connect** = `vpnConnect({ any: {} })` (`obscura-ui/src/views/ConnectionView.tsx:173`, `LocationView.tsx:378`), city selection from the Location list, **Pinned** cities, **Last chosen** city, search box, continent grouping (`ContinentAF..ContinentSA`, en.json:85-91; Mexico grouped under "Latin America", `api.ts:18`). No UI for country-only or exact-exit-id selection, although the selector supports both.
- Exit object shown to UI (`obscura-ui/src/common/api.ts:6-15`): `id, country_code, city_code, city_name, provider_id, provider_url, provider_name, provider_homepage_url`. API adds `datacenter_id: u32` and `tier: u8` ("Higher tier servers should be preferred") (`api:src/types.rs:519-538`).
- Automatic exit ranking inside a selector (`rustlib/src/exit_selection.rs:74-87`): rank tuple `(is_preferred_by_relay, same_city_as_relay, same_country_as_relay, tier, random)`; adaptive exclusion on retries (same exit id; datacenter used ≥2; city ≥4; country ≥6).
- "Auto-connect at launch" (`autoConnectStartup`, en.json:58-59): "If you last chose "Quick Connect", Obscura will "Quick Connect" at next launch. If you last chose a specific location, Obscura will connect to that location at next launch."
- Pinned location shape (`obscura-ui/src/common/appContext.ts:157-163`): `{ country_code: string, city_code: string, pinned_at: number /* seconds since UNIX epoch */ }`.
- Location counts on obscura.com (FAQ + KB, verbatim lists, counted by me): **41 exit cities in 28 countries** (NA 11, LatAm 4, Europe 19, Asia 4, Africa 1, Oceania 2). Pricing page claims "Connections to 33+ countries" (mismatch; the live API exit list is authoritative). Exits are Mullvad servers, mostly DataPacket-hosted (Carl, Privacy Guides #35; App Store dev reply "We currently use DataPacket as our hosting provider"). Users asked for "owned" Mullvad servers (PG #141): not offered.

### 1.4 Entry relay choice [V + I]

- **Not user-selectable in any official app** [V]. `TunnelArgs` only has `exit` (`rustlib/src/manager.rs:139-141`). The relay is chosen by `select_relay` (`rustlib/src/client_state.rs:512-608`):
  - Relay list from API `ListRelays` (cached; refreshed if older than 60 s).
  - `race_relay_handshakes` opens a QUIC (or TCP/TLS) handshake to every relay and port in parallel, capped at `MAX_RELAYS = 100` and `MAX_PORTS_PER_RELAY = 5` (`rustlib/src/relay_selection.rs:88-91`), then measures RTT.
  - Deadline 30 s; after the first success the deadline shrinks to `min(3 × elapsed, 5 s)`; stop after 5 distinct relays answered; keep lowest RTT.
  - The server must then create the tunnel on that relay, else `TunnelConnectError::UnexpectedRelay` (`client_state.rs:503-505`).
- Web WireGuard generator: "Choose your server location (Relay Server) and optionally an Exit Server then click Generate config" (https://obscura.com/knowledge/using-obscura-via-wireguard/). [V]
- HN (Carl, 2026-09): "We allow you to choose an exit location". Privacy Guides #30 (2025-05): "The setting to use the fastest connection seems to always use the exit server in the same city as the entry server." [V]
- [I] Because `CreateTunnel::Obfuscated` takes `relay: Option<String>`, a native client could offer "Entry relay: Automatic (fastest) / <city>" by racing only the chosen relay's ports. Whether the FFI exposes this is out of my scope (see `rust-ffi.md`). Privacy note for the UI: Cure53 OBS-01-005 says the race leaks client RTT to relays and on-path observers, so a pinned relay also reduces RTT fingerprinting.

### 1.5 Port selection [V]

Not user-selectable. Relays advertise ports in the API (`ports: Vec<u16>`, `api:src/types.rs:505-506`; example `[53, 443]`). The client races up to 5 ports per relay automatically.

### 1.6 DNS features [V]

- Default resolver: Mullvad `10.64.0.1` via the exit (tunnel config `dns`). Carl, PG #21: "right now by default, we set your DNS to Mullvad's 10.64.0.1 DNS without any Ad-Blocking."
- Content blocking: 6 categories mapped to Mullvad's blocking resolvers `100.64.0.<bitset>` (`rustlib/src/network_config.rs:71-90`):
  ```rust
  pub struct DnsContentBlock { ad, tracker, malware, adult, gambling, social_media } // serde camelCase
  let bitset = ad | tracker<<1 | malware<<2 | adult<<3 | gambling<<4 | social_media<<5;
  (bitset != 0).then_some(Ipv4Addr::new(100, 64, 0, bitset))
  ```
  Only applied when `exit_provider_name == "Mullvad VPN"` (`network_config.rs:14,125`). UI labels (en.json:128-133): "Block ads", "Block trackers", "Block malware", "Block gambling", "Block adult content", "Block social media". Android got DNS content blocking in v1.160 (2026-05-18).
- Blocklists (KB, verbatim): "As of June 2026, the lists include EasyList, Hagezi, AdGuard, OISD, URLhaus and Mullvad's custom tracker blocklist." (https://obscura.com/knowledge/dns-options/)
- "Use system DNS" / "Use installed custom system DNS profile": Apple and Linux only. `const SUPPORTS_DNS_SELECTION = !IS_ANDROID && !IS_WINDOWS;` (`obscura-ui/src/views/Settings.tsx:43`). Android code comment: "useSystemDns is always false on Android because no UI surface writes it." (`android/.../services/ObscuraVpnService.kt:407`).
- Android Private DNS: app shows "Android's Private DNS is turned on, this overrides Obscura's DNS settings." (en.json:46; `Settings.tsx:87-91`).
- No custom DNS IP / DoH / DoT input in any app. Carl (PG #23, 2025-05): "In the long run, likely all of these" (custom servers, DoH). Not shipped as of Oct 2026.
- WireGuard configs: user may edit `DNS = 10.64.0.1` to a `100.64.0.X` Mullvad address (KB).

### 1.7 Kill switch / leak prevention [V]

KB https://obscura.com/knowledge/kill-switch/ (updated 2025-12-18):
- Apple: Settings > Experimental > "On demand kill switch" (feature flag `killSwitch`, shown only on Apple: `AVAILABLE_FLAGS = { [KillSwitch]: IS_APPLE }`, `Settings.tsx:215-217`). "The kill switch activates an on-demand rule that automatically restarts the VPN if it crashes. While the VPN is restarting, all device traffic is blocked".
- Android (verbatim steps): "Open the Settings App / Search for "VPN" / Click the Gear icon next to Obscura VPN / Enable Always-on VPN / Enable Block connections without VPN".
- Apple "Strict Leak Prevention" = `includeAllNetworks`, label "[Unstable] Enable Strict Leak Prevention", blocks LAN, known reconnect bugs (en.json:352-357; https://obscura.com/knowledge/strict-leak-protection-bugs/, https://obscura.com/knowledge/tunnelvision/).
- Linux: nftables enforcement while target state is connected; Windows: routes only. See section 4 (`docs/traffic-enforcement.md`).

### 1.8 Network options [V]

- "Allow local network access" (Android + Linux only, `Settings.tsx:196-201`; added v1.163, 2026-06-09). On Android it changes the route set (`rustlib/src/local_network.rs:40` `tunnel_routes(dns_servers, allow_local_network_access)`).
- "Allow Tailscale traffic", "Allow WireGuard® traffic": Linux only (`Settings.tsx:202-209`).
- "Rotate WireGuard® key" button (all platforms). KB: "Rotating your WireGuard key issues you a new Mullvad profile, which may give you a new IP address." Automatic schedule rotation every 30 days: `const MAX_AGE: Duration = Duration::from_secs(60 * 60 * 24 * 30); // 30 days` (`rustlib/src/config/persistence.rs:639`).
- Windows / Linux: TPM sealing of the WG secret key bound to PCR 7 (`rustlib/src/wg_key_store/mod.rs:1-5`). Not on Android or Apple.

### 1.9 Experimental feature flags [V]

`rustlib/src/config/feature_flags.rs` (serde camelCase): `quicFramePadding`, `killSwitch`, `forceSmallMtu`, `tcpTlsTunnel`. UI text (en.json:162-172):
- "Enable packet padding": "Make network packets a uniform size to mitigate against size-based packet correlation by global network observers." Warning: "may slightly increase bandwidth usage". Carl (HN): "it's an experimental feature, we're not 100% happy about how we implemented it so we've left it experimental and are working on a v2." Upstreamed to quinn (PG #99). Answer to Cure53 OBS-01-006.
- "Small MTU compatibility": "Assume the underlying network has a small MTU."
- "Use TCP/TLS tunnel": "Use TCP/TLS instead of QUIC (which is UDP-based) for the tunnel to Obscura servers. This may allow you to use Obscura on networks that restrict UDP traffic."
- "On demand kill switch" (Apple only).

### 1.10 Transparency and status features [V]

- Exit verification: Location page shows exit node, provider and exit **WireGuard public key**, with "Matches published key for <provider_id>" linking to `provider_url` (Mullvad server page) (`obscura-ui/src/views/LocationView.tsx:423-539`; en.json:154). FAQ: "You can check this key against what Mullvad publishes here (https://mullvad.net/servers)".
- "Copy public key" (client key) (en.json:100).
- Traffic stats: `TrafficStats { connectedMs, connId, txBytes, rxBytes, latestLatencyMs }` (`commands.ts:216-222`); UI shows "Current Session" timer.
- "Check my Connection" opens `https://obscura.com/check/` (`ConnectionView.tsx:134`).
- Notices from API (`Notice { type: 'Error'|'Warn'|'Important', content }`, `commands.ts:180-183`).
- Debug bundle: ZIP created on demand, never auto-sent; contents listed at https://obscura.com/knowledge/debug-bundles/.

### 1.11 Account model [V]

- Account number: 20 digits = 19 random digits + 1 Verhoeff check digit, generated **client-side** with CSPRNG, shown in 4-digit groups (`obscura-ui/src/common/accountUtils.ts`: `ACCOUNT_ID_LENGTH = 19`, `USER_ACCOUNT_NUMBER_LEN = 20`, `ACCOUNT_ID_DISPLAY_CHUNK_SIZE = 4`). Errors: "Account Number is too short (should be 20)", "Mistyped Account Number" (en.json:40-42).
- No email, name or phone. Login with number only. Logout "will reset preferences" (en.json:274-275).
- Delete account in app (Account tab). "If you log in again, a new account will be created with the same ID." (en.json:117). Top-up credit is lost; Stripe sub is auto-cancelled; Apple/Google subs must be cancelled manually (https://obscura.com/knowledge/deleting-account/).
- Free trial: server-controlled; button text switches to "Start Trial" when `status.free_trial.available` (`obscura-ui/src/App.tsx:251`, `LogInView.tsx:138`).
- Connection slots: **5 simultaneous** (raised from 3 on 2026-08-25). App uses a slot only while connected; each WireGuard config reserves one slot permanently. Errors: "You cannot have more than five active devices. Disconnect a device or delete a WireGuard config in your user portal" / "Too many simultaneous connections." (en.json:402,409).

### 1.12 Payments and promotions [V]

- Price: **$8.00/month** USD (https://obscura.com/pricing/). Play IAP "$8.00 per item". Not refundable ("YOUR PAYMENT IS NONREFUNDABLE", Terms).
- Subscriptions: card via Stripe (web), Apple App Store (iOS), Google Play (Android play flavour). "Cryptocurrency payments aren't supported for subscriptions."
- Top-ups (one-time time credit): card, **Bitcoin Lightning**, **Monero** (no on-chain BTC). Monero top-ups need a minimum of 3 months (PG #68). A top-up pauses an active subscription until credit is used.
- API endpoints exist for in-app crypto (`api:src/cmd/lightning/create.rs` `POST lightning/top_up` returning `{ id, invoice, millisatoshis }`; `monero/top_up`; `prices`), but the client `ManagerCmd` does not expose them (variant list at `rustlib/src/manager_cmd.rs:133-215`). Apps open `https://obscura.com/pay#account_id=...` instead (`accountUtils.ts:139`). [V]
- Promo codes ("Have a promo code?"); launch codes ANDROID26, WINDOWS26, LINUX26 (25% off).
- Referrals: both parties get one free month; regenerable random link id; association deleted one month after reward (https://obscura.com/knowledge/referrals/).
- "Circle of Trust" partner discounts (Ente, addy.io) via `/account/friends/` and API `friend_codes/*` (https://obscura.com/circle-of-trust/).
- Newest (2026-10-19): "NNN" lock-in promo: "Break out of your VPN lock-in." / "Get up to 2 years of credit by showing us your receipt" (en.json:262-268); upload of another VPN's receipt via web `https://obscura.com/account/nnn/` (API `nnn/submit` takes `claimed_months, email?, file, original_file_name, vpn_provider?`). [I] A privacy-focused client can hide this promo.

### 1.13 Feature timeline 2025-2026 [V]

| Date | Event | Source |
|---|---|---|
| 2025-02-11 | Launch (macOS), Mullvad partnership | blog bootstrapping-trust; https://mullvad.net/en/blog/mullvad-partnered-with-obscura-vpn |
| 2025-05 | Web WireGuard configs, web sign-up | PG #11-#14 |
| 2025 (by Sep) | iOS app | App Store reviews from 09/2025 |
| 2025-07-16 | Cure53 OBS-01 report (audit May 2025), published 2025-12-16 | report PDF |
| 2025-11-03 | Monero top-ups; Kill Switch (Apple, experimental) | PG #67 |
| 2025-12 | Referral program (KB 2025-12-23) | KB |
| 2026-05-18 | v1.160: Android DNS content blocking | GitHub release |
| 2026-05-19 | Android GA (Play + Obtainium) | blog |
| 2026-06-05 | v1.162: auto client-to-relay MTU; Android MTE, edge-to-edge | release |
| 2026-06-09 | v1.163: Allow local network access | release |
| 2026-06-30 | v1.165: connection buttons in notification | release |
| 2026-07-24 | iOS v1.167: "Adopt Liquid Glass" | App Store history |
| 2026-08-25 | Windows app; 5 slots (from 3) | blog |
| 2026-09-02 | v1.177: "Remove explicit tunnel deletion, improving time-to-connection." | release |
| 2026-09-08 / 10-02 | Circle of Trust (Ente, addy.io) | blog |
| 2026-09-22 | Linux GUI + experimental CLI; relicensed PolyForm Noncommercial 1.0.0 → GPLv3 (`GPL-3.0-only WITH GPL-3.0-linking-source-exception`, `linux/common/net.obscura.vpn.gui.metainfo.xml:6`) | blog |
| 2026-09-30 | v1.181 (Android/iOS/desktop) | release |
| 2026-10-19 | NNN lock-in promo (HEAD) | git log |

### 1.14 Features NOT offered (verified absent) [V]

Searched app code, API crate and all obscura.com pages:
- No split tunnelling (per app or per site). Carl (PG #40, 2025-08): "No current support in our apps"; browser extension for per-site bypass "will be our team's focus after shipping Android, Windows, and Linux" (PG #134, 2026-04).
- No post-quantum key exchange, no DAITA, no Tor integration, no extra hops or alternative exit providers (only Mullvad), no "Lite" plan (Carl PG #131: "we really don't have plans for that right now"), no owned-server filter.
- No custom DNS server entry; no user port/protocol choice beyond the TCP/TLS toggle; no relay choice in apps.
- No widgets, no Siri/App Intents, no Android Quick Settings tile (`grep TileService` = 0 hits), no reproducible builds yet ("plan to provide").
- Web portal only: WireGuard config management, referrals, top-up, subscription, NNN.

---

## 2. Security and privacy model

### 2.1 Who sees what [V]

KB "Relay Nodes & Exit Nodes" (updated 2026-09-23), verbatim:
> **Relay Servers (First Hop):** The relay node is the first server your device connects to. It's operated by Obscura and is the only server that knows your real IP address. However, unlike single-hop VPNs, it can't see your traffic. Your data is encrypted so that only the exit node can decrypt it, meaning the relay forwards it without being able to read it.
> **Exit Servers (Second Hop):** The exit node is the location where your traffic appears to come from when visiting a website. It's operated by our partner, Mullvad, an independent, privacy-focused VPN provider based in Sweden. The exit node can see the websites you visit (just like any VPN endpoint), but has no idea who you are. It only knows the traffic came from an Obscura relay, not from your device.
> **Why Two Hops?** ... The Obscura relay knows where you're connecting from, but not what websites you're visiting. The exit server knows what websites are being visited, but not where the users are connecting from. Even if one server is compromised, an attacker can't link your identity to your browsing activity.

Homepage FAQ "What user data can Obscura see?": "Obscura only sees your connecting IP address and necessary payment info – never your actual internet traffic. We physically can't decrypt your internet traffic ... and never log your connecting IP address."

| Party | Sees | Does not see |
|---|---|---|
| Obscura relay + API | client IP, account number, auth token, payment metadata, connect times, bytes, chosen exit, packet sizes/timing, client WG **public** key (registers it with Mullvad) | decrypted traffic, SNI, DNS queries (all inside WireGuard to Mullvad) |
| Mullvad exit | decrypted traffic, destinations, DNS queries, client WG public key (stable until rotation) | client IP (sees relay IP), Obscura account, payment |
| Local network / ISP | QUIC (or TLS) to a relay IP, SNI `example.com` by default, packet sizes/timing | content, destination |

### 2.2 Privacy policy (https://obscura.com/legal/, last updated 2024-11-14) [V]

- "IP Addresses: We do not persist, log, or otherwise store your IP address when you use our VPN."
- Stripe gets card and email for subscriptions; "This information is not retained by Obscura".
- Website uses Plausible analytics; site also served at Tor v3 onion `ngmmbxlzfpptluh4tbdt57prk3zxmq4ztew7l2whmg7hkqaof2nzf7id.onion`.
- Company: Sovereign Engineering Inc., New York, USA; New York law; arbitration. No warrant canary found.
- Play Data safety: "No data shared with third parties"; "This app may collect these data types: Personal info and App info and performance"; "Data is encrypted in transit"; "You can request that data be deleted".

### 2.3 Threat model and audit [V]

Cure53 "Audit-Report Obscura Protocol & MacOS VPN Client 06.2025" (dated 16 Jul 2025, audit in May 2025, commit `6c79c298320946e6c5be0506801d32b81d884602`; https://obscura.com/obs-01-report.v3.pdf; blog https://obscura.com/blog/obscuras-first-audit/). Scope: WP1 macOS app, WP2 network extension (Rust), WP3 protocol design (+ private threat-model PDF). Android, iOS, Windows, Linux **not audited**.
- Adversary (verbatim): "The adversarial model assumes a passive or active network attacker capable of observing, modifying, inserting, or replaying traffic. Post-compromise scenarios of relay servers should also be considered."
- "The protocol prioritizes low latency over maximum protection against global attackers".
- "The design of the protocol changes the security requirements of the WireGuard protocol, since the WireGuard server no longer can learn (and verify) the sender's identity."
- Findings: OBS-01-003 Unbounded vector allocation on message receipt (Medium, fixed: max 65 kB); OBS-01-004 Unbounded parallel QUIC handshakes on relay selection (Low, fixed: max 100 relays); OBS-01-001 Long-term key storage via Keychain (Info, fixed on macOS); OBS-01-002 fixed 25-retry WG handshake limit (Info; `WG_FIRST_HANDSHAKE_RESENDS: usize = 25`, `rustlib/src/quicwg.rs:46`); OBS-01-005 Client-relay RTT leakage on relay selection (Info, open); OBS-01-006 QUIC data packets unpadded (Info, fixed via optional padding).
- Verdict: "Obscura is a well-engineered privacy solution with no major security vulnerabilities within its defined threat model."

`docs/traffic-enforcement.md:3-6` states the client-side goals: "Traffic leaves the machine only through the tunnel. / Tunnel flows cannot be impersonated from outside. / The exit cannot initiate flows."

### 2.4 Known critiques of the model (useful for an expert UI) [V quotes, I conclusions]

- Exit key distribution is trusted to Obscura. Carl on HN (2026-09): "It's the latter right now (which is not ideal), but I think Mullvad is going to sign their server pubkeys pretty soon and we'll switch to that." PG #190: "the exit's key is vended by the same first hop, and the client trusts it as is. Obscura's API is also what registers the client's key with Mullvad." [I] Native client can auto-check the exit pubkey against Mullvad's public server list (fetched through the tunnel) and show a verified badge.
- Mullvad can link sessions by the client WG public key until rotation (PG #104, RethinkDNS dev). [I] Offer shorter rotation (e.g. daily / per connect) on top of the 30-day default.
- Relay sees metadata: "connecting IP, account number, connection timestamps, bytes transferred, exit chosen, and timing/size patterns. Choosing not to log the IP is a policy commitment, not an architectural impossibility" (PG #188).
- US jurisdiction (PG #171, #185; HN). Price ($8) and Mullvad dependency (HN, Play review). CAPTCHAs on Mullvad IPs (Play review; Reddit "network security" KB).
- QUIC can be throttled in censored countries (HN, PG #108); TCP/TLS is the fallback.
- Android: "connectivity checks & captive portal detection on Android happen outside of the VPN tunnel" even with lockdown (PG #88). [I] Show this caveat in the kill-switch screen.

---

## 3. Branding

### 3.1 Colours [V]

**Desktop/mobile app UI (Mantine theme, `obscura-ui/src/components/Mantine.tsx:95-102`):**
- `primaryColor: 'orange'`; button gradient `defaultGradient: { from: '#FF7A49', to: '#FF6025', deg: 180 }` ("see figma design for buttons").
- Dark palette (Mantine v6 dark): `dark: ['#C1C2C5','#A6A7AB','#909296','#5c5f66','#4F5156','#393939','#353535','#313131','#303030','#222528']`.
- Pre-render background `#313131` (`obscura-ui/src/index.css:3`). Disconnect button `{ variant: 'light', c: 'red.7', bg: 'red.1' }`. Connected text uses Mantine `green.7/green.8`; "Last chosen" `green.7`; selected location border `teal.5`.
- Promo/dark card `#222528`; `#F2F2F2`; Location highlight gradient `rgba(246,152,85,0.487)` (light) / `rgba(181,75,0,0.75)` (dark) (`views/Location.module.css:70-73`).
- SVG asset palette (counts): `#FF6025` brand orange, `#FFC7B2` peach, `#FFEBE3` light peach, `#D54D1A` dark orange, `#222528` near-black, `#F6F8F7` off-white, `#E4E4E4` grey, accents `#44A5FF`, `#5279F8`, `#F9D952`, `#FF678E`, `#A04AD3`, `#2FA73B`, `#17B26A`.

**Official Android native resources (`android/app/src/main/res/values/colors.xml:3-12`, `themes.xml`):** `orange_400 #f56b39`, `orange_600 #dc4d26`, `orange_800 #9f2d00`, `slate_400 #90a1b9`, `slate_600 #45556c`, `slate_800 #303030`, `background_light #ffffff`, `background_dark #303030`. Light theme `colorPrimary=@color/orange_600`; night `colorPrimary=@color/orange_400`, `colorOnPrimary=black`. Parent `Theme.Material3.DayNight.NoActionBar`.

**Website obscura.com (`/a/BaseLayout.*.css`, hashed name):**
- `theme-color` / manifest `#ff5e24`.
- Orange scale: `--orange-1:#ffede6; --orange-2:#ffc7b3; --orange-3:#ff5e24; --orange-4:#d54c1a; --orange-5:#953109`.
- Neutral scale: `--neutral-1:#f6f8f7; --neutral-2:#c6cbcd; --neutral-3:#989ea4; --neutral-4:#5c6066; --neutral-5:#232629`.
- Green: `--green-1:#dcf9df; --green-2:#b0f2b6; --green-3:#53d560; --green-4:#2fa73b; --green-5:#0c7e17`. Red: `--red-1:#fdeded; --red-2:#ffb5b3; --red-3:#f4352f; --red-4:#c92c26; --red-5:#950e09`. Blue `--blue-100:#dceefe` ... `--blue-600:#2473eb`.
- Light tokens (`:root`): `--primary: var(--orange-3)`, `--primary-foreground: var(--orange-1)`, `--foreground: var(--neutral-5)`, `--muted: var(--neutral-1)`, `--muted-foreground: var(--neutral-3)`, `--secondary-foreground: var(--neutral-4)`, `--success: var(--green-4)`, `--destructive: var(--red-3)`, `--card: white`, `--radius: .5rem`. Dark (`:root .dark`): `--primary: var(--orange-1)`, `--primary-foreground: var(--orange-3)`, `--foreground: var(--neutral-1)`, `--success: var(--green-1)`, `--destructive: var(--red-1)`.
- Store screenshots: background ~`#F6F8F7`, orange `#FF6025` pixel blocks.

[I] Compose seed: primary `#FF6025` (brand), gradient `#FF7A49→#FF6025` for the main CTA only; dark surfaces `#222528`/`#232629`/`#303030`; light surface `#F6F8F7`; success `#2FA73B`; error `#F4352F`; peach container `#FFC7B2`/`#FFEDE6`. Prefer a fixed brand scheme over dynamic colour (or make dynamic optional).

### 3.2 Logo and mascot [V]

- App icon / favicon (`https://obscura.com/favicon.svg`, Play icon): pixel-art, `shape-rendering="crispEdges"`, an orange (`#FF6025`) square with a retro CRT/TV robot: off-white screen (`#F6F8F7`) with black pixel eyes and smile, two black antennae with peach tips (`#FFC7B2`), peach and black pixel shading.
- Wordmark: lowercase "obscura" in blocky geometric glyphs (`obscura-ui/src/res/obscura-wordmark.svg`, 127×27). Store art pairs the TV icon + "obscura".
- Mascot states (`obscura-ui/src/res/mascots/`): `connected-mascot`, `connected-first-time-mascot`, `connecting-1..4-mascot`, `not-connected-mascot`, `thinking-mascot`, `validating-mascot`, `dead-mascot`, `hat`. Status decorations in `res/deco/` (connecting light/dark frames, disconnected, offline, orange top).

### 3.3 Fonts [V]

- Website: body `"Manrope Variable"` (200-800); display `"Jersey 10"` (`--font-display`); pixel `"Press Start 2P"` (`--font-pixel`); mono `"Geist Mono Variable"`; `Flags` font for flag emoji. All self-hosted woff2 under `/a/`.
- Store headlines use a pixel display face (looks like Jersey 10 / pixel). App UI uses system fonts: `-apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, Roboto, Helvetica, Arial, sans-serif` and mono `source-code-pro, ui-monospace, ...` (`Mantine.tsx:22-23`).
- [I] For Compose: Manrope (OFL) for UI text, Jersey 10 (OFL) only for a few display headings, Geist Mono / JetBrains Mono for keys and IPs. Bundle fonts (no Google Fonts downloads).

### 3.4 Taglines and store copy [V]

- "Privacy that's more than a promise" (site title, AppStream summary).
- "Can't log. Won't lag. Works anywhere." (Play/App Store/Linux metainfo).
- Play short description: "Privately connect anywhere".
- Play screenshot captions: "The VPN that Can't log. Won't lag. Works anywhere." / "Works where other VPNs don't." / "Best-in-class two-party privacy" / "Dedicated servers optimized for speed".
- In-app strings: "Connected to Obscura", "Enjoy the free and open internet!", "Traffic is protected", "Secured via Mullvad VPN" (`securedBy`), "Quick Connect", "Check my connection".

### 3.5 Store screenshots (Play, phone + tablet) [V, viewed]

1. Orange full-bleed poster: TV icon + "obscura" wordmark, pixel headline "The VPN that / Can't log. / Won't lag. / Works anywhere." (last phrase in orange on a white block), white pixel blocks at bottom.
2. "Works where other VPNs don't." over off-white with orange pixel-block corners. Phone: white Connection screen, centred mascot, green "Connected to Obscura", grey "Enjoy the free and open internet!", divider "Connected to", dropdown "🇺🇸 New York, NY", pale-red full-width "Disconnect" button, underlined "Check my connection ↗".
3. "Best-in-class two-party privacy": Location screen card with green shield, "Connected to Amsterdam, Netherlands", "Traffic is protected", pale-red "Disconnect", "Current Session 00:01:56", collapsible row "Secured via Mullvad VPN" (orange), then "North America" list.
4. "Dedicated servers optimized for speed": list of white rounded cards (flag, "Boston, MA" / "United States", outlined pin icon at right), grouped under continent headers.
Style: very clean, white cards with soft borders, lots of whitespace, small type, orange used sparingly, green for connected state, pale red for disconnect.

---

## 4. Upstream `docs/` directory (complete)

The directory has exactly three files. `README.md:13` says: "Conventions, terminology, and intended behavior are documented in the docs directory. Contributions must align with these documents or change them accordingly."

### 4.1 `docs/glossary.md` (verbatim, whole file)

> The product consists of two kinds of components:
> - **Service**: Background worker, which runs independently of user sessions and UI. It manages the tunnel and network configuration.
> - **UI**: The components a user may interact with directly to observe and change service state, such as the graphical user interfaces, the command line interface, and the tray icon. May be qualified further using GUI, CLI, or tray.

Note [V]: the glossary does **not** define "relay", "exit", "tunnel" or "pinned location". Definitions from other official sources:
- Relay / exit: see KB quotes in 2.1.
- Tunnel: API object `OneTunnel` created by `POST tunnel` (`CreateTunnel`), kinds `udp_port` (WireGuard config, reserves a slot) and `obfuscated` (app QUIC tunnel) (`api:src/cmd/tunnel/create.rs`). UI calls WireGuard configs "WireGuard® Configurations" (en.json:415); the portal path is `/account/tunnels/`.
- Pinned location: a `{country_code, city_code, pinned_at}` city that the user pinned; shown in a "Pinned" section above the full list (`LocationView.tsx:76-150`).
- Quick Connect: connect with `ExitSelector::Any {}`.
- Slot: KB "5 simultaneous connection slots per account. This isn't a device limit".

### 4.2 `docs/codestyle.md` (verbatim)

> ## Naming
> Prefer names that don't require the import path to be meaningful and unique. This allows us to use bare imports without negatively impacting readability.
> E.g. prefer:
> - `GuiCmd` over `gui::Cmd`
> - `auto_connect_if_enabled()` over `auto_connect::run()`

### 4.3 `docs/traffic-enforcement.md` (summary with key quotes)

- Purpose: "what traffic we allow while the user wants to be connected." Goals (lines 3-6): only through tunnel; tunnel flows not impersonable; exit cannot initiate flows. "Enforcement starts when the target state becomes connected, which includes connecting or reconnecting transient states." (line 8)
- Per platform (lines 12-15):
  - "**Linux.** Nftables tables and chains enforce only desired traffic flows. FWMARK on the service's own relay and API traffic allows bypassing this firewall."
  - "**Windows.** Routing only. Two half-default routes (per address family) beat the system default route, but routes more specific than /1 win."
  - "**Apple.** Routing only, unless strict leak prevention is opted into, which enables `includeAllNetworks` mode".
  - "**Android.** Routing only, unless the user enables "Always-on VPN" and "Block connections without VPN" in system settings."
- Egress exceptions table (lines 25-36), Android column: Loopback allowed; Service traffic allowed; "Traffic via explicitly bound device/address" **denied**; Traffic into our tun allowed; Local network destination ranges **default-allowed**; DHCP and IPv6 ND allowed; Forwarded flows NA; WireGuard devices NA; Tailscale NA.
- TunnelVision (CVE-2024-3661) (lines 44-51): "**Android.** The OS prioritizes VPN route configuration."
- Exit can inject packets; only Linux filters to conntrack replies (lines 53-55).
- CVE-2019-14899 tunnel-address injection from LAN: Linux drops (lines 66-70). ARP for tunnel address dropped on Linux "since tunnel addresses are stable until the WireGuard key is rotated, this would identify a machine across networks" (lines 72-74).
- Linux fwmark `0x6f627363` ("obsc") exempts flows (lines 76-94).

[I] Implication for our Android client: Android enforcement relies on system lockdown; the client should detect `VpnService.isLockdownEnabled()/isAlwaysOn()` (API 29+) and guide the user. Note the tunnel IP is stable until key rotation, a cross-network identifier toward LAN peers on weak-host systems.

---

## 5. Official Android app: what it offers, complaints, opportunities

### 5.1 Facts [V]

- Package `net.obscura.vpnclientapp`; label "Obscura VPN"; GitHub release asset `obscura-1.181.apk`; Play: 4.0★, 27 reviews, 1K+ downloads, updated 2026-09-30, IAP "$8.00 per item". iOS: 4.3★, 32 ratings.
- Build: `compileSdk = 36`, `minSdk = 31`, `targetSdk = 36`, buildTools 37.0.0, Kotlin, Hilt, JVM 21 (`android/app/build.gradle.kts:12-20,72-74`). Flavours `foss` (default, no billing) and `play` (Play Billing + Play Services) (`build.gradle.kts:60-69`).
- UI: `ObscuraWebView` hosting the React/Mantine bundle; `MainActivity` `launchMode="singleInstance"`, heavy `configChanges` "`WebView` necessitates aggressive use of `android:configChanges`" (`AndroidManifest.xml:25-35`). Deep link scheme `obscuravpn://`.
- VPN service `ObscuraVpnService` in separate process `:vpnservice`, `foregroundServiceType="systemExempted"`, permission `BIND_VPN_SERVICE` (`AndroidManifest.xml:61-72`). Permissions: INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SYSTEM_EXEMPTED, POST_NOTIFICATIONS.
- `android:memtagMode="async"` (MTE, since v1.162). WebView metrics opt-out meta-data.
- VpnService.Builder (`ObscuraVpnService.kt:391-425`): `addDisallowedApplication(own package)`; `setMtu`; `setMetered(false)` (inherit underlying); DNS from config; IPv4 + IPv6 addresses; routes from Rust; `allowFamily(AF_INET/AF_INET6)`; `setUnderlyingNetworks` (line 459). No other disallowed/allowed apps (no split tunnel). No `setBlocking`, no `allowBypass`.
- Notification: status text + actions "Disconnect"/"Quick Connect" and "Location" (`res/values/strings.xml:3-10`, `NotificationManager.kt:76-113`).
- Settings visible on Android: auto-connect at launch; DNS content blocking (no system-DNS option); Private DNS warning; Experimental: packet padding, small MTU, TCP/TLS; Network: rotate WG key, local network access; Appearance light/dark/system. Account: number, expiry, Play subscription status, manage on web, delete, logout. About: debug bundle, licences.
- Kill switch: only via system Always-on + Block connections (KB).
- Error strings for other VPNs with Always-on: "Do you have another VPN app with Always-On enabled ..." (en.json:394-395).
- Backup: `android:allowBackup="true"`; `data_extraction_rules.xml` excludes only `rust-log` from cloud backup and device transfer. Config lives in `context.getFilesDir()` (`ObscuraLibrary.java:20`) as `config.json` (`rustlib/src/config/persistence.rs:29`), which holds `account_id`, `cached_auth_token` and `wireguard_key_cache` (`persistence.rs:229-276`) (plaintext key store on Android; sealing only on Windows/Linux). So account credentials are eligible for (E2E-encrypted) Google cloud backup and device transfer. [V for rules and paths; content of config.json from persistence.rs]
- `google-services.json` is present in `android/app/` but no Firebase plugin or Firebase dependency is applied (grep for firebase/crashlytics = 0 hits). [V]

### 5.2 Complaints and limitations (reviews, forums) [V quotes]

- Price: "The monthly payment for currently on the market is not worth it. Very expensive." (Play, 2026-07-21). PG #127/#130 ask for a cheaper single-device plan.
- CAPTCHAs: "Some websites will serve captchas due to Mullvad being the exit nodes" (Play, 2026-05-19).
- Limited features: techbits.cc review (2026-07) calls the Android app simple/limited; lists auto-connect, DNS blocking, key rotation, LAN access, theme, padding, small MTU, TCP/TLS. Slot limit (then 3) made router use "clunky".
- Split tunnelling requested repeatedly (PG #18, #28, #39, #42).
- Custom DNS / DoH requested (PG #22).
- Reliability on network change (iOS): "whenever the phone moves out of Wi-Fi range ... the Obscura VPN disconnects and does not reconnect automatically" (PG #138); "you're rushing products out (the Android app included) before they're ready" (PG #146). Windows LAN access missing (PG #191).
- Support responsiveness: "Support never replies" (PG #138, #148).
- Payment privacy: Stripe Link autofill concern (PG #154-#160); "Stripe's telemetry are loaded even when you just want to pay with Crypto" (PG #181, disputed by Carl).
- Distribution: only Play + Obtainium; users ask for alternative stores; GrapheneOS users asked about MTE (now enabled).
- API errors: "unable to connect to the Obscura API" on some locations (App Store review).
- Earlier changelog bugs on Android: invalid state after app restart (fixed v1.177), Debug Bundle on Android 12 (v1.177), crash without Play Services (v1.166), top/bottom not interactable (v1.170), landscape account creation (v1.162), low memory (v1.162).

### 5.3 Where a native Compose client can do better [I]

1. Native Compose UI (no WebView, no JS bridge, no `configChanges` hacks); instant start, predictive back, edge-to-edge, Material 3 Expressive motion, large-screen layouts.
2. Quick Settings tile (connect / disconnect / last location), app shortcuts, optional Glance widget.
3. Per-app split tunnelling (`addAllowedApplication`/`addDisallowedApplication`), with an "exclude only" safe default and a warning that excluded apps leak IP.
4. Kill-switch UX: detect Always-on and lockdown state, deep-link to `Settings.ACTION_VPN_SETTINGS`, show the captive-portal caveat.
5. Exit choice by country, city and exact server (selector already supports `country` and `exit`), with tier/datacenter info, pinned and recent, latency hints. Entry relay: "Automatic (fastest)" plus optional pin, if the FFI can pass a relay id.
6. Exit key verification: compare the connected exit pubkey with Mullvad's published list and show "verified" or a red warning.
7. Privacy hardening: `allowBackup=false` (or exclude all), wrap the WG secret / account token with Android Keystore (StrongBox if present), `FLAG_SECURE` on account screens, no Play Services in a FOSS build, configurable WG key rotation interval, hide marketing promos, no analytics.
8. DNS: content-block categories plus custom DNS (IPv4/IPv6) inside the tunnel, Private DNS detection with a direct fix link.
9. Transport panel: QUIC (default) / TCP-TLS, packet padding, small MTU; show current transport, relay city, exit, RTT (`latestLatencyMs`), tx/rx, session time.
10. Robust reconnection on Wi-Fi/cellular handover (`setUnderlyingNetworks`, `NetworkCallback`), clear "Reconnecting" state, battery-friendly.
11. Account: number entry with Verhoeff validation and 4-digit grouping, copy with clipboard sensitivity flag (`ClipDescription.EXTRA_IS_SENSITIVE`), slot usage display, open web pay/top-up (Lightning/Monero) in a browser, in-app Lightning invoice only if API access is available.

---

## 6. Sources

Official:
- https://obscura.com/ (308 from https://obscura.net) ; FAQ anchors `#faq-technical`, `#faq-trust`
- https://obscura.com/pricing/ ; https://obscura.com/legal/ ; https://obscura.com/check/ ; https://obscura.com/circle-of-trust/ ; https://obscura.com/cursed-knowledge/
- https://obscura.com/download/android/ ; /download/ios/ ; /download/mac/ ; /download/windows/ ; /download/linux/ ; /download/wireguard/
- KB: https://obscura.com/knowledge/relay-and-exit-nodes/ ; /dns-options/ ; /troubleshooting-dns/ ; /kill-switch/ ; /strict-leak-protection-bugs/ ; /tunnelvision/ ; /managing-devices/ ; /payments/ ; /referrals/ ; /using-obscura-via-wireguard/ ; /troubleshooting-connection-issues/ ; /debug-bundles/ ; /deleting-account/ ; /downloading-obscura/ ; /bypass-reddit-network-security-block/ ; /service-order/
- Blog: https://obscura.com/blog/bootstrapping-trust/ ; /obscuras-first-audit/ ; /introducing-obscura-android/ ; /introducing-obscura-windows/ ; /linux-at-long-last/ ; /announcing-circle-of-trust/ ; /addy-io-joins-circle-of-trust/
- Cure53 report: https://obscura.com/obs-01-report.v3.pdf
- Site assets: https://obscura.com/site.webmanifest ; https://obscura.com/favicon.svg ; https://obscura.com/a/BaseLayout.DB9nJKha.css (hashed)
- GitHub: https://github.com/Sovereign-Engineering/obscuravpn-client (issues disabled; releases v1.160-v1.181) ; https://github.com/Sovereign-Engineering/obscuravpn-api
- Play: https://play.google.com/store/apps/details?id=net.obscura.vpnclientapp ; App Store: https://apps.apple.com/us/app/obscura-vpn/id6746820048
- Mullvad: https://mullvad.net/en/blog/mullvad-partnered-with-obscura-vpn ; https://mullvad.net/servers ; https://github.com/mullvad/dns-blocklists

Third party:
- Hacker News thread "Obscura: VPN that can't log your activity" (2026-09-22): https://news.ycombinator.com/item?id=49807003
- Privacy Guides: https://discuss.privacyguides.net/t/obscura-vpn/26408 ; https://discuss.privacyguides.net/t/introducing-obscura-vpn-for-android/38015
- Reviews: https://techbits.cc/obscura-multihop-vpn/ ; https://privacytools.io/app/obscura-vpn ; https://blockdyor.com/obscura-vpn-review/ ; https://kycnot.me/service/obscura-vpn ; https://www.techradar.com/vpn/vpn-services/android-users-have-just-gained-access-to-a-new-vpn-app-as-obscura-vpn-lands-on-google-play-and-obtainum ; https://www.omgubuntu.co.uk/2026/09/obscura-vpn-linux-app
