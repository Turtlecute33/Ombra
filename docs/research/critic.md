# critic: fact check, gap resolution, feature parity matrix, risks

Inputs: all 9 notes in `/tmp/obscura-research/notes/` (rust-ffi, rust-features, android-shell, ui-features, build, android-platform, product, and also perf-datapath, perf-udp-mode).
Code checked by me: client `85ef7e2692dc811a023360f08865bfb90815649c` (2026-10-19), API crate `e94483e7331b521d4d29ee7411a83e4adeffcc9d` at `/tmp/obscura-research/obscuravpn-api`, `tracing-appender 0.2.3` in `~/.cargo/registry`.
Paths are relative to `/tmp/obscura-research/obscuravpn-client/`. Short forms: `rs/` = `rustlib/src/`, `kt/` = `android/app/src/main/java/net/obscura/vpnclientapp/`, `api/` = `/tmp/obscura-research/obscuravpn-api/src/`.
Legend: **[V]** = I read it in the code at the cited lines. **[I]** = inference from code, not run. **[TEST]** = needs a device or a live account.
No file in the upstream clone was changed (`git status --porcelain` is empty). No secret was read or printed.

---

## 0. Twelve findings to read first

1. **Licence.** The "linking exception" only allows combination with OpenSSL, BoringSSL, AWS-LC, ring and similar libraries (`LICENSE:14-22`). It is not an LGPL-style exception. Our Kotlin app loads rustlib into its own process through JNI, so a distributed APK is a GPL-3.0 combined work. Our code must use a GPL-3.0-compatible licence and we must ship the source. Personal use only has no distribution duty. [V text, I legal reading]
2. **Login also resets the config, not only logout.** `set_account_id` rebuilds `Config` from `Default` when the account id changes, and `None -> Some(id)` is a change (`rs/client_state.rs:205-239`). Only `api_url`, `account_id`, `cached_auth_token`, `old_account_ids`, `in_new_account_flow` survive. LAN access, DNS block, feature flags, `sni_relay`, `api_host_alternate`, pinned exits, tunnel args and the WG key are lost. The app must own its preferences and push them again after each successful `login`. [V]
3. **Feature flags and SNI do not trigger a reconnect.** `TargetState` holds only tunnel args, interface, DNS block, system DNS, LAN, and 2 Linux bypass values (`rs/tunnel_state.rs:24-33`, `rs/client_state.rs:112-125`). `tcpTlsTunnel`, `quicFramePadding`, `forceSmallMtu` and `sni_relay` are read only inside `select_relay` (`rs/client_state.rs:527,536-544`). A change applies at the next connect. Zero-patch, leak-free way to force it: section 2, Q11. [V]
4. **Entry relay choice needs a Rust patch.** `TunnelArgs` has only `exit` (`rs/manager.rs:137-141`). The relay is always a race over all cached relays (`rs/client_state.rs:512-608`). The server already honours the relay id that the client sends (`rs/client_state.rs:474-480,503-505`). Patch design in section 5.1 (about 60 lines). [V]
5. **Pinned server retry alternates with an error.** With `{"exit":{"id":..}}`, or a city or country that has one exit, a failed attempt marks that exit as used. The next attempt finds no candidate, resets the filter and fails with `NoExit`, which maps to `connectError:"other"` (`rs/exit_selection.rs:51-72,81-86`, `rs/errors.rs:91-100`). The attempt after that works again. The UI must not show a transient `other` as fatal. [V]
6. **No dummy TUN when there is no network.** In the branch "VPN wanted, interface `None`" Rust sets `noInternet` and waits. It does not install the dummy TUN (`rs/tunnel_state.rs:365-379`). If the tunnel was up before, the old TUN stays and drops packets. If it was never up, there is no TUN at all until a network appears. [V]
7. **Long polls cannot be cancelled.** `getStatus` and `getExitList` hold a tokio task and a JNI global ref until the value changes (`rs/manager_cmd.rs:313-321`, `rs/manager.rs:394-415`, `rs/android/ffi.rs:102-126`). `getExitList` can wait for hours. Run exactly one loop of each in the VPN process and fan out. [V]
8. **On-disk Rust logs can be switched off with no patch.** `flushAndStopPersistedLog()` drops the `WorkerGuard` (`rs/logging.rs:34-36`). The `NonBlocking` writer then drops every line (`try_send` fails, `tracing-appender-0.2.3/src/non_blocking.rs:245-250`). Call it right after `initialize`. Logcat output stays. [V code, I end-to-end]
9. **Config location is a free parameter.** `initialize(configDir, userAgent)` takes any directory (`rs/android/ffi.rs:47-60`). Pass `context.noBackupFilesDir` so `config.json` (account number, token, WG key) and `rust-log/` are never in cloud backup or device transfer. [V]
10. **WG secret key at rest.** Android uses `WgKeyStore::Plaintext` (`rs/android/ffi.rs:60-62`). A 1-line patch to `WgKeyStore::None` keeps the secret in memory only and makes a new key at each process start (`rs/config/persistence.rs:575-586,622-625`). A Keystore-backed `WgKeyStore::Keychain` needs a larger FFI patch (section 5.4). [V]
11. **The core never polls account info.** `Status.account` changes only on `apiGetAccountInfo` (`rs/manager.rs:262-266`). The WG key pre-registration task runs only when `account.account_info.active` and disconnected (`rs/manager.rs:307-334`; comment `rs/client_state.rs:801`). The app must call `{"apiGetAccountInfo":{}}` at start, after payment, and on a timer. [V]
12. **`killSwitch` flag has no Rust consumer.** Only Apple Swift reads it (`apple/client/app_state.swift:224`, `apple/Packet Tunnel Provider/PacketTunnelProvider.swift:288`). The Linux `kill_switch_chain` is unconditional under `TrafficPolicy::Engage`, not tied to the flag (`rs/bin/obscura/service/os/linux/netfilter.rs:552-575`). On Android the kill switch is only the system "Always-on VPN" + "Block connections without VPN". [V]

---

## 1. Contradictions between reports, with verdicts from the code

| # | Topic | Report A | Report B | Verdict (source) |
|---|---|---|---|---|
| C1 | Number of `ManagerCmd` variants | rust-ffi: "All 31" | rust-ffi table lists 32 | **32** variants (`rs/manager_cmd.rs:132-218`). None is cfg-gated. [V] |
| C2 | Who uses flag `killSwitch` | rust-ffi: "ONLY the Linux service (netfilter.rs:564)"; rust-features: "Apple on-demand; Linux netfilter chain" | ui-features, product: Apple only | **Apple only.** No Rust code reads `feature_flags.kill_switch` (grep: only `rs/config/feature_flags.rs`). Linux chain is not gated by the flag. See finding 12. [V] |
| C3 | Liveness "dead" threshold | rust-ffi: "dead after 4 lost probes (1 after sleep)" | rust-features, perf-datapath: "> 4 (> 1 after wake)" | **Dead when lost > 4, so at the 5th lost probe; > 1 (2nd lost) after `wake`** (`rs/liveness.rs:10-11,108-118`). [V] |
| C4 | `featureFlags` nulls in Status | rust-ffi [T]: nulls are emitted | rust-features [I]: "may" | `#[skip_serializing_none]` is after `#[derive]` (`rs/config/feature_flags.rs:8-9`), and rust-ffi tested it: **nulls are emitted**. Decode absent and `null` as false. [V+T] |
| C5 | Country code case | rust-ffi, rust-features: API fixture `"US"` | ui-features examples `"us"`; UI comment "lowercase TCountryCode" (`obscura-ui/src/common/api.ts:8`) | Not decidable offline. UI upper-cases only for flag lookup (`api.ts:22-24`); matching is exact (`rs/exit_selection.rs:26-33`). **Copy codes verbatim from the exit list; never build them.** [V code, TEST live value] |
| C6 | Service class name and process | android-platform manifest: `.vpn.ObscuraVpnService`, `:vpn`, `.tile.VpnTileService` | rust-ffi, build, android-shell: Rust needs `net/obscura/vpnclientapp/services/ObscuraVpnService` | **FQN is fixed** (`rs/android/class_cache.rs:23`). Process name is free: only the upstream Java guard checks `:vpnservice` (`kt/client/ObscuraLibrary.java:16-18`), and we rewrite that file. The class with that FQN only needs 2 static methods; it does not have to be the `VpnService` itself, but keeping it as the service is simplest. [V] |
| C7 | `protect()` sockets | android-platform: `protect()` every outer socket, bind to Network | rust-ffi, android-shell: Rust never calls `protect()`; app UID excluded | Rust creates its sockets internally with no callback (`rs/net.rs:28-51`, Android branch 45-48). **`protect()` is impossible without a Rust patch.** Keep `addDisallowedApplication(ownPackage)` (`kt/services/ObscuraVpnService.kt:397-398`). In allow-list split mode the own package is outside the VPN by construction. [V] |
| C8 | `START_STICKY` vs `START_NOT_STICKY` | android-platform [I]: NOT_STICKY | rust-ffi: reconnect on sticky restart | Rust forces `tunnel_active=false` at every process start (`rs/android/ffi.rs:67`, `rs/client_state.rs:93-95`). **Use `START_STICKY` and a persisted "VPN wanted" flag**, and send `setTunnelArgs{args:null,active:true}` on a null-intent restart. [V+I] |
| C9 | NDK version | build: r28 `28.0.13004108` verified with `-P 35` | android-platform: pin r30 `30.0.16248370` | **Use r28 for the Rust build** (verified, 16 KB aligned). r30 is not tested with this crate graph. AGP `ndkVersion` can be r28 too. [V build log] |
| C10 | 16 KB flags | android-platform: add `.cargo/config.toml` rustflags | build: already present | Already in `rustlib/.cargo/config.toml:1-7` for `cfg(target_os="android")`. A `RUSTFLAGS` env var **replaces** them; use `CARGO_TARGET_<TRIPLE>_RUSTFLAGS` (build §2, §6). [V] |
| C11 | QUIC tuning | perf-udp-mode: send buffer 64-128 KiB, "compare Cubic with BBR" | perf-datapath: 256 KiB; Cubic collapses at 1% loss | **Keep BBR** (measured 39.6 vs 3.3-4.2 Mbit/s at 1% loss). Start at 256 KiB send / 512 KiB receive, then A/B. [V measurements in perf-datapath] |
| C12 | Licence of rustlib | task brief: "GPL-3.0 with linking exception" (implies permissive linking) | n/a | See finding 1. [V] |
| C13 | Tailscale/WG bypass fields | rust-features: "fields cfg-gated to Linux" | rust-ffi: present in Status | `Status` fields are **not** gated and are always emitted (`rs/manager.rs:71-72`). Only `OsNetworkConfig` fields are Linux-gated (`rs/network_config.rs:107-112`). [V] |
| C14 | Error mapping for client errors | rust-ffi lumps "protocol errors -> apiError" | (implicit elsewhere) | Two different enums. `ManagerCmdErrorCode`: `ClientError::{ResponseTooLarge,InvalidHeaderValue,Other,ProtocolError}` -> `apiError` (`rs/manager_cmd.rs:119-121`). `ConnectErrorCode`: same errors -> `other` (`rs/errors.rs:84-86`). [V] |
| C15 | Upstream Java callback is "total" | rust-ffi: upstream catches everything | n/a | It catches `Exception`, not `Throwable` (`kt/services/ObscuraVpnService.kt:112-124`). Ours must catch `Throwable`. [V] |
| C16 | Default network callback | android-platform 1.8: "our own UID is inside our VPN", so default callback reports the VPN | rust-ffi/android-shell: own UID is excluded | Own UID is excluded. Keep the explicit `INTERNET + NOT_VPN` best-matching request (`kt/services/ObscuraVpnService.kt:198-220`); it is correct either way. [V] |
| C17 | User-Agent | rust-ffi §15.7 [I]: distinct UA is a fingerprint | API doc asks third parties to identify | `api/../doc/user-agent.md`: "Your identity ... A version number that increases ... DO NOT put end-user info". UA is inside TLS to Obscura only; Obscura already knows the account. Fingerprint risk is low. Follow the API doc. [V] |

No contradiction found on: JNI symbol names, JSON shapes of `ExitSelector`, `TunnelArgs`, `DnsContentBlock`, `PinnedLocation`, `OsNetworkConfig`, `VpnStatus`, error code lists, relay race limits, MTU constants, LAN route lists. I re-checked each against the code.

---

## 2. Gaps and open questions that I resolved

| Q | Question | Answer | Evidence |
|---|---|---|---|
| Q1 | Can the entry relay be chosen without a patch? | No. Patch in 5.1. | `rs/manager.rs:137-141`, `rs/client_state.rs:521-553` [V] |
| Q2 | Can the relay list be shown without a patch? | Only the current relay in `vpnStatus.connected.relay`. The full list is in `config.json` `cached_relays` but no command returns it. Reading `config.json` from Kotlin is possible but fragile and touches a file with secrets. Patch in 5.2. | `rs/client_state.rs:245-247`, `rs/manager_cmd.rs:132-218` [V] |
| Q3 | Country and exact-server exits? | Yes, no patch: `{"country":{"country_code":C}}`, `{"exit":{"id":ID}}`. Caveat: finding 5. Unknown id or empty match -> `NoExit` -> `other`. Pre-validate against the exit list. | `rs/exit_selection.rs:11-33,51-72` [V] |
| Q4 | Does the exit list need login? | Yes. `refreshExitList` uses the authenticated client and fails with `notLoggedIn`. Logout clears `cached_exits`. | `rs/client_state.rs:618-622,657-662,672-726` [V] |
| Q5 | Disable on-disk Rust logs? | Zero patch: `flushAndStopPersistedLog()` after `initialize`. The dir `rust-log/` is still created. Clean patch: pass `None` to `logging::init`. | finding 8 [V/I] |
| Q6 | Lower Rust log volume (battery)? | `filter()` = `EnvFilter::from_default_env().add_directive(INFO)` (`rs/logging.rs:78-80`). A global level below INFO is impossible, but target directives win over the default. Set `RUST_LOG=obscuravpn_client=warn,obscuravpn_api=warn,quinn=warn,quinn_proto=warn` with `android.system.Os.setenv("RUST_LOG", v, true)` before `initialize`. | [V filter code, I effect, TEST] |
| Q7 | Keep secrets out of backup? | `initialize(noBackupFilesDir, ua)` plus `android:allowBackup="false"` plus full-exclude `dataExtractionRules`. | finding 9 [V] |
| Q8 | Which thread may call `initialize`? | Any Java thread: `FindClass` in a native method uses the class loader of the declaring class (`ObscuraLibrary`). Upstream uses the main thread. Running it on a background thread avoids main-thread disk I/O; gate all other FFI calls on its completion. | `rs/android/class_cache.rs:15-18`, `rs/android/ffi.rs:73-96` [V code, I JNI rule, TEST] |
| Q9 | Can two processes load Rust? | Never. No file lock on `config.json` (grep of `rs/config/` finds none); two `Manager`s would overwrite each other. Load only in the VPN process; keep a process-name guard. | `rs/config/persistence.rs:136-212` [V] |
| Q10 | What happens on account change while connected? | Login/logout reset `tunnel_active` to false, so the tunnel goes down and the TUN closes (leak window if no lockdown). | `rs/client_state.rs:222-229`, `rs/tunnel_state.rs:347-363` [V] |
| Q11 | How to apply a flag/SNI change now without a leak? | `unsetNetworkInterface()`, wait until the status loop shows `connecting` with `connectError:"noInternet"`, then `setNetworkInterface(name, index)`. The old TUN stays up and drops packets, then dummy TUN, then reconnect. Do not call both at once: the `watch` channel coalesces and nothing happens. | `rs/tunnel_state.rs:163-171,204-229,245-268,365-379` [I from code, TEST] |
| Q12 | `setTunnelArgs` with a new exit while connected? | Full reconnect, no leak: state goes `connecting`, dummy TUN, new relay race, new API tunnel. Same args while connected: no-op. | `rs/tunnel_state.rs:163-171,204-268` [V] |
| Q13 | DNS block / system DNS / LAN change while connected? | No reconnect; Rust calls `ffiSetNetworkConfig` again (new fd, seamless handover). | `rs/tunnel_state.rs:245-247,307-330` [V] |
| Q14 | Custom DNS without a patch? | Possible on the Kotlin side: in `ffiSetNetworkConfig` replace `dns` with user IPs before `addDnsServer`. Must also handle the dummy config (DNS `10.64.0.99`). With LAN access on, Rust only re-adds its own DNS IPs as host routes (`rs/local_network.rs:40-60`), so a custom resolver in a LAN range goes outside the tunnel. Content-block IPs are then ignored. | [V routes, I override] |
| Q15 | What does `useSystemDns:true` do on Android? | Kotlin skips `addDnsServer`; Android then uses the underlying network's resolvers. Queries go to ISP resolver IPs: through the tunnel if routed, outside if they are LAN IPs and LAN access is on. Treat as an expert-only option with a warning. | `kt/services/ObscuraVpnService.kt:407-414`, Builder doc in android-platform §1.2 [V+I] |
| Q16 | Is `autoConnect` acted on by Rust? | No. Stored and echoed in Status only. App logic must do it. | grep `auto_connect` in `rs/` (only setter, Status, config) [V] |
| Q17 | Is "notices" (TS `Notice`) available? | No. Only a TS type (`obscura-ui/src/bridge/commands.ts:180`); no Rust code. | grep [V] |
| Q18 | Does `wake` exist for Android? | Core yes (`rs/manager.rs:388-392`, `rs/quicwg.rs:397-401`), JNI no. Apple calls it without entering the runtime (`rs/apple/ffi.rs:179-183`). Patch 5.3. | [V] |
| Q19 | Does Android get API-level "notices" or relay RTTs? | Relay RTTs from the race are only logged (`Xdbn2PYb`, `rs/client_state.rs:606`). `latestLatencyMs` is the in-tunnel ICMP RTT to the exit gateway through the relay. | `rs/tunnel_state.rs:74-92` [V] |
| Q20 | Account creation path? | Client-side id: 19 digits from CSPRNG with rejection sampling `< 10^19`, zero-padded, plus Verhoeff check digit (tables in `obscura-ui/src/common/accountUtils.ts:4-46,48-69`). Then `setInNewAccountFlow{value:true}`, `login{validate:true}`. | [V] |
| Q21 | Is the class `ObscuraVpnService` required to be the real service? | No. Rust only calls 2 static methods on it (`rs/android/ffi.rs:241-245,272`). | [V] |
| Q22 | Can the exit WG key be checked automatically? | Only by an HTTP call from our UID, which bypasses the tunnel (app is excluded) and tells the local network that we fetch Mullvad data. Offer a link to `exit.provider_url` (opens in the browser, which is inside the tunnel) instead. | [V exclusion, I] |
| Q23 | Does every connect cost an API tunnel and a slot? | Yes, a new `CreateTunnel::Obfuscated` with a new UUID per (re)connect; slot used while connected; `TunnelLimitExceeded` -> `noSlotsLeft`. | `rs/client_state.rs:461-480`, `rs/errors.rs:57` [V] |
| Q24 | API DNS privacy | Every API resolve spawns a system lookup even on a cache hit; the lookup runs on the physical network (app excluded). The ISP resolver sees `v1.api.prod.obscura.net` often. | `rs/dns.rs:18-40` [V] |

---

## 3. Open items that need a device or a live account

| # | Item | Why it matters | Test |
|---|---|---|---|
| T1 | Live country code case (`us` vs `US`) | Display, search, pinned keys | Print one `getExitList` value in a debug build. |
| T2 | Pinned relay + arbitrary exit accepted by the server | Entry/exit choice | With patch 5.1, connect relay=DE, exit=US; expect `connected`. |
| T3 | `initialize` on a background Java thread | Cold start | Instrumented test. |
| T4 | Q11 reconnect trick | Apply flags without leak | Toggle `tcpTlsTunnel`, watch `transport` change from `quic` to `tcpTls` with a packet capture on the device. |
| T5 | Lockdown + split tunnel / LAN access | Excluded apps may lose network | android-platform §10 item 1. |
| T6 | `RUST_LOG` via `Os.setenv` | Battery | Check logcat volume. |
| T7 | `WgKeyStore::None` server behaviour | New key per process start; unknown rate limits on `cache_wg_key` / `WgKeyRotationRequired` | Kill the process 10 times, connect each time. |
| T8 | IPv6-only + CLAT MTU | `interface_mtu` reads the base interface (`rs/client_state.rs:321-359`) | Test on an IPv6-only carrier. |
| T9 | `CreateTunnel::UdpPort` relay semantics | Optional "Speed" mode | perf-udp-mode §1.3. |

---

## 4. Feature parity matrix for the native client

Columns: core = rustlib, UI = upstream Android app. All JSON goes through `ObscuraLibrary.jsonFfi(handle, json, future)`. Ok results for setters are the 4-char string `null`.

### 4.1 Account and onboarding

| Feature | Upstream core / UI | How we drive it | Priority | Notes |
|---|---|---|---|---|
| Log in with account number | Yes / Yes | `{"login":{"accountId":"<20 digits>","validate":true}}` | must | Validate length + Verhoeff locally first. After Ok, push all app preferences again (finding 2). `FLAG_SECURE` on this screen. |
| Create account / Start trial | Indirect / Yes | `{"apiServiceStatus":{}}` -> `free_trial.available`; generate id (Q20); `{"setInNewAccountFlow":{"value":true}}`; `login{validate:true}` | must | Errors `apiSignupLimitExceeded`, `apiRateLimitExceeded` (PoW solved in core). |
| New-account flow end | Yes / Yes | `{"setInNewAccountFlow":{"value":false}}` | should | Status field `inNewAccountFlow`. |
| Log out | Yes / Yes | `{"logout":{}}` | must | Disconnects and resets config (Q10). Keeps `old_account_ids` in plaintext config; consider a patch to clear it. |
| Delete account | Yes / Yes | `{"apiDeleteAccount":{}}` then `{"logout":{}}` | should | Strong confirm dialog. |
| Account status, expiry | Yes / Yes | `{"apiGetAccountInfo":{}}` at start, every 12 h, after payment, on `accountExpired` | must | Also feeds key pre-registration (finding 11). `active` is the funded flag. |
| Account number reveal / copy | n/a / Yes | Local only; `Status.accountId` | must | Service sends only a hint (`AccountId::hint`, first 3 chars, `api/types.rs:55,81-90`) to the UI; full value on explicit "reveal". Clip with `ClipDescription.EXTRA_IS_SENSITIVE`. |
| Payments (FOSS) | Web / Yes | `Intent.ACTION_VIEW` `https://obscura.com/pay#account_id=<id>` | should | Fragment is not sent in HTTP, but it is in browser history. |
| Google Play billing | Yes / Yes (play flavor) | `apiGoogleBillingDetails`, `apiGoogleAssociateAccount` | could | Pulls GMS; separate flavor only. |
| Lightning / Monero in app | API crate only / No | Not in `ManagerCmd`; patch or web | could | `api/cmd/lightning/*`, `api/cmd/monero/*`. |

### 4.2 Connection and location

| Feature | Upstream core / UI | How we drive it | Priority | Notes |
|---|---|---|---|---|
| Connect | Yes / Yes | `VpnService.prepare()`; `startForegroundService(ACTION_CONNECT)`; in service `{"setTunnelArgs":{"args":{"exit":SEL},"active":true}}` | must | Always go through a started foreground service, never a bind-only call (a bind-only service dies on unbind and `onDestroy` disconnects). |
| Disconnect | Yes / Yes | `{"setTunnelArgs":{"args":null,"active":false}}` | must | Closes TUN. Hide when `isAlwaysOn()`. |
| Quick connect | Yes / Yes | `SEL = {"any":{}}` | must | Exit ranked near the fastest relay. |
| Exit by city | Yes / Yes | `{"city":{"country_code":C,"city_code":X}}` | must | Codes verbatim from list. |
| Exit by country | Yes / No | `{"country":{"country_code":C}}` | should | New vs upstream UI. |
| Exit by server | Yes / No | `{"exit":{"id":ID}}` | should | Finding 5. Show `datacenter_id`, `tier`, `provider_id`. |
| Entry relay choice | **No** / No | Patch 5.1: `{"setTunnelArgs":{"args":{"exit":SEL,"relay":RSEL},"active":true}}` | should | User asked "if possible": yes with a fork. Pinning also stops the 100-relay race fingerprint (Cure53 OBS-01-005, product §1.4). |
| Relay list | No / No | Patch 5.2: `{"refreshRelayList":{"freshness":60000}}`, `{"getRelayList":{"knownVersion":null}}` | should | Needed for the relay picker. |
| Exit list | Yes / Yes | `{"refreshExitList":{"freshness":60000}}` (integer ms) and one long-poll `{"getExitList":{"knownVersion":null\|"<b64>"}}` | must | One loop per process (finding 7). Send `knownVersion` explicitly. |
| Pinned locations | Storage / Yes | `{"setPinnedExits":{"exits":[{"country_code":C,"city_code":X,"pinned_at":<unix s>}]}}` | should | Replace whole list. Fix the upstream unpin bug (`obscura-ui/src/views/LocationView.tsx:76-79` compares `loc.country_code == loc.country_code`). Reset on login/logout. |
| Last chosen / last exit | Yes / Yes | `Status.lastChosenExit`, `Status.lastExit` | should | |
| Status stream | Yes / Yes | One loop: `{"getStatus":{"knownVersion":null}}`, then `knownVersion = version` | must | Status changes only when content changes (`rs/manager.rs:292-302`). |
| Connect errors | Yes / Yes | `vpnStatus.connecting.connectError` in `accountExpired, apiError, apiRateLimitExceeded, apiUnreachable, invalidAccountId, noInternet, noLongerSupported, noRelay, noSlotsLeft, notLoggedIn, other` | must | Debounce `other` during retries (finding 5). Write Android-specific text. |
| Connected details | Yes / Partial | `vpnStatus.connected`: `relay` (`id, city_name, country_code, ip_v4, ports`), `exit`, `transport` (`quic`/`tcpTls`), `clientPublicKey`, `exitPublicKey` | must | Expert panel. |
| Exit key check | Data yes / Link | Link to `exit.provider_url` | should | No auto-fetch from our UID (Q22). |
| Traffic, latency, session | Yes / timer only | `{"getTrafficStats":{}}` at 1 Hz while visible; throughput = byte deltas with the same `connId` | should | `latestLatencyMs` = full-path RTT to exit gateway. |
| Auto-connect at app start | Storage / Yes | App logic; mirror with `{"setAutoConnect":{"enable":B}}` | should | |
| Always-on, boot | System / implicit | `onStartCommand` action `VpnService.SERVICE_INTERFACE` -> `{"setTunnelArgs":{"args":null,"active":true}}` | must | Uses persisted `tunnel_args`. |
| Kill switch | System only / KB text | Read `isAlwaysOn()`, `isLockdownEnabled()` in the service (API 29); deep link `Settings.ACTION_VPN_SETTINGS` | must | Show captive-portal caveat (product §2.4). |
| Leak block while (re)connecting | Yes / n/a | Automatic dummy TUN | must | Not when no network at start (finding 6). |
| Restart after process death | No / No | `START_STICKY`, persisted "wanted", reconnect on null intent | must | C8. |
| Roaming | Yes (full reconnect) / Yes | `registerBestMatchingNetworkCallback(INTERNET+NOT_VPN)` -> `setNetworkInterface(name, index)` / `unsetNetworkInterface()`; `setUnderlyingNetworks` | must | Also handle `onLinkPropertiesChanged` (interface name change). Unregister in `onDestroy` (upstream does not). |
| Wake after Doze | Core only | Patch 5.3; call on `ACTION_SCREEN_ON` / idle-mode exit | could | Faster dead-tunnel detection (1 lost probe). |
| Quick Settings tile | No / No | `TileService` in the VPN process | should | |
| Notification | n/a / Yes | FGS `systemExempted`, actions Disconnect / Change location, `VISIBILITY_PRIVATE` | must | |
| Widget, shortcuts | No / No | Glance text-only, static shortcuts | could | |

### 4.3 Transport and QUIC

| Feature | Upstream core / UI | How we drive it | Priority | Notes |
|---|---|---|---|---|
| QUIC transport | Yes / Yes | Default | must | ALPN `h3`, pinned relay cert, BBR, PMTUD to 1350. |
| TCP/TLS transport | Flag / Yes | `{"setFeatureFlag":{"flag":"tcpTlsTunnel","active":B}}` | should | Applies at next connect (finding 3). Slow by design (perf-datapath F8). |
| Packet padding | Flag / Yes | `{"setFeatureFlag":{"flag":"quicFramePadding","active":B}}` | should | Uplink pads ACKs: up to ~10x uplink cost (perf-datapath F1). Say this in the UI. |
| Small MTU | Flag / Yes | `{"setFeatureFlag":{"flag":"forceSmallMtu","active":B}}` | could | Auto when interface MTU < 1378 (`rs/net.rs:102-127`). |
| Relay SNI | Yes / Dev view | `{"setSniRelay":{"host":H\|null}}` | could | Write-only (not in Status); reset on login. App stores and re-applies. Default `example.com`. |
| API alternate host | Yes / Dev view | `{"setApiHostAlternate":{"host":H\|null}}` | could | Same as SNI. Default `crimsonlance.net`. |
| API URL | Yes / Dev view | `{"setApiUrl":{"url":U\|null}}` | could | Also rotates the WG key. Debug builds only. |
| Plain WireGuard UDP mode | API only / No | Patch (perf-udp-mode §6.2) | could | Experimental, unsupported API path, fingerprintable. |
| Data path speed patches | No / No | Fork patches (perf-datapath §6) | should | TUN batching, socket buffers, `initial_mtu(1350)`, queue sizes. |

### 4.4 DNS

| Feature | Upstream core / UI | How we drive it | Priority | Notes |
|---|---|---|---|---|
| Tunnel DNS (Mullvad) | Yes / Yes | Automatic | must | |
| Content blocking (6) | Yes / Yes | `{"setDnsContentBlock":{"value":{"ad":B,"tracker":B,"malware":B,"adult":B,"gambling":B,"socialMedia":B}}}` (all 6 required) | must | Only when `exit.provider_name == "Mullvad VPN"` (`rs/network_config.rs:14,125-131`). Applied live. |
| System DNS | Yes / Hidden on Android | `{"setUseSystemDns":{"enable":B}}` + Kotlin skips DNS | could | Q15 leak risk. |
| Custom DNS | No / No | Kotlin override (Q14) or patch `DnsConfig::Custom` | should | |
| Private DNS detection | n/a / strict only | `LinkProperties.getPrivateDnsServerName()`, `isPrivateDnsActive()` | should | Strict mode overrides VPN DNS. |

### 4.5 Network, keys, privacy

| Feature | Upstream core / UI | How we drive it | Priority | Notes |
|---|---|---|---|---|
| LAN access | Yes / Yes | `{"setLocalNetworkAccess":{"enable":B}}` | must | Core default **Enabled** (`rs/config/persistence.rs:303-308`). We default to false: send after first start and after every login. |
| Split tunnelling per app | No / No | Kotlin `Builder.addDisallowedApplication` (always plus own pkg) or `addAllowedApplication` (never own pkg) inside `ffiSetNetworkConfig` | should | Lists are mutually exclusive. Warn under lockdown (T5). Android 17 `ACTION_VPN_APP_EXCLUSION_SETTINGS` does nothing for VpnService (android-platform). |
| IPv6 | Yes / n/a | Automatic, routes `::/0` | must | Outer transport IPv4 only. |
| Meteredness, underlying nets | n/a / Yes | `setMetered(false)`; `Builder.setUnderlyingNetworks([current])` and `VpnService.setUnderlyingNetworks` on change | must | |
| WG key rotation | Yes / Yes | `{"rotateWgKey":{}}`; auto 30 days, checked at disconnect | should | New key used at next connect. |
| WG key at rest | Plaintext / n/a | Patch 5.4 | should | |
| Secrets off backup | n/a / No | Q7 | must | |
| Persisted logs off | No / No | Q5, Q6 | must | Never log Status, AccountInfo, `login` commands. |
| Tailscale / WG bypass | Linux only | Skip | n/a | No effect on Android. |
| Debug bundle | Yes / Yes | `{"createDebugBundle":{"userFeedback":S\|null,"bundleInfo":{"AppVersion":V},"androidCacheDir":"<cacheDir>"}}` -> path string | could | Makes probes to apple.com and google.com, includes `logcat -d` (`rs/client_state.rs:845-917`). Consent screen; delete old zips. |
| Dev raw console | Yes / Yes | Any `ManagerCmd` JSON | could | Debug builds only. Never `terminateProcess` in release. |
| Theme | n/a / Yes | App | must | Brand `#FF6025`, see ui-features §8. |
| NNN promo, Apps view | n/a / Yes | Skip | won't | |

---

## 5. Minimal Rust patch set for our fork (ranked)

All patches are on `rs/`. JNI symbol names follow `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_<method>`.

### 5.1 Entry relay selector (should)

```rust
// rs/exit_selection.rs (new type, next to ExitSelector)
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone)]
#[serde(rename_all = "camelCase")]
pub enum RelaySelector {
    Any {},
    Relay { id: String },
    Country { country_code: CountryCode },
    City { #[serde(flatten)] city_code: CityCode },
}
impl Default for RelaySelector { fn default() -> Self { RelaySelector::Any {} } } // #[default] needs a unit variant; copy ExitSelector's manual impl (exit_selection.rs:36-40)
impl RelaySelector {
    pub fn matches(&self, r: &OneRelay) -> bool {
        match self {
            Self::Any {} => true,
            Self::Relay { id } => r.id == *id,
            Self::Country { country_code } => r.city_code.country_code == *country_code,
            Self::City { city_code } => r.city_code == *city_code,
        }
    }
}

// rs/manager.rs:137-141
pub struct TunnelArgs {
    pub exit: ExitSelector,
    #[serde(default)]
    pub relay: RelaySelector,
}

// rs/tunnel_state.rs:273: pass &target_args (not &target_args.exit) to client_state.connect(...)
// rs/client_state.rs:389-395, 419-434: thread `relay_selector` into select_relay(...)
// rs/client_state.rs:545-553: before race_relay_handshakes:
let candidates: Vec<OneRelay> = relays.value.iter().filter(|r| relay_selector.matches(r)).cloned().collect();
if candidates.is_empty() { return Err(RelaySelectionError::NoSuccess.into()); } // -> connectError "noRelay"
let mut racing_handshakes = race_relay_handshakes(network_interface, &candidates, sni, use_tcp_tls, quic_frame_padding, force_small_mtu, mtu)?;
```

JSON after the patch: `{"setTunnelArgs":{"args":{"exit":{"any":{}},"relay":{"city":{"country_code":"de","city_code":"fra"}}},"active":true}}`. Old configs and old JSON without `relay` decode to `Any`. `TunnelArgs` is in `matches_target`, so a relay change while connected reconnects. Exit ranking already prefers the chosen relay's city and `preferred_exits` (`rs/exit_selection.rs:74-79`).

### 5.2 Relay list commands (should)

```rust
// rs/manager_cmd.rs ManagerCmd
GetRelayList {
    #[serde_as(as = "Option<serde_with::base64::Base64>")]
    known_version: Option<Vec<u8>>,
},
RefreshRelayList {
    #[serde_as(as = "serde_with::DurationMilliSeconds")]
    freshness: Duration,
},
// rs/manager_cmd.rs ManagerCmdOk (untagged)
GetRelayList(CachedValue<Arc<Vec<OneRelay>>>),
// rs/manager.rs: copy get_exit_list (394-415) using config().cached_relays;
// RefreshRelayList -> self.client_state.maybe_update_relays(freshness) (rs/client_state.rs:728, already pub)
```
JSON: `{"getRelayList":{"knownVersion":null}}` -> `{"version":"<b64>","last_updated":<unix s>,"value":[OneRelay...]}`.

### 5.3 Wake (could)

```rust
// rs/android/ffi.rs
#[unsafe(no_mangle)]
pub extern "C" fn Java_net_obscura_vpnclientapp_client_ObscuraLibrary_wake(_env: JNIEnv, _: JClass, handle: JObject) {
    let global = global_from_handle(&handle);
    let _guard = global.runtime.enter();
    global.manager.wake();
}
```
Java: `static native void wake(FfiHandle handle);`

### 5.4 WG key at rest (should)

- Simple: `WgKeyStore::None` at `rs/android/ffi.rs:62`. Secret only in memory; new key at each process start; public key stays in config. Test T7.
- Strong: `WgKeyStore::Keychain { secret_key: Option<Vec<u8>>, set_secret_key: KeychainSetSecretKeyFn }` (`rs/wg_key_store/mod.rs:81-93`). Add a `byte[] wgSecret` argument to `initialize` (decrypted by Kotlin with an AES-GCM Android Keystore key, no `setUnlockedDeviceRequired`) and a static Java callback `ffiStoreWgSecret([B)Z` that encrypts and writes it. Load path checks it against the stored public key (`rs/config/persistence.rs:535-560`).

### 5.5 Privacy and logging (should)

- `logging::init(..., None)` when a Java flag says "no persisted logs" (`rs/android/ffi.rs:53`).
- Skip the background system DNS lookup on a cache hit (`rs/dns.rs:20`), or make it opt-in.
- Clear `old_account_ids` on logout (`rs/client_state.rs:215-221`).

### 5.6 Apply transport settings live (could)

Add flags and `sni_relay` to `TargetState` and to the `Connected` comparison in `matches_target` (`rs/tunnel_state.rs:24-33,163-171`, `rs/client_state.rs:112-125`). Until then use Q11.

### 5.7 Speed (should, after A/B)

perf-datapath §6 items 1-5: padding coalescing, queue sizes + `initial_mtu(1350)` + MTUD interval 60 s, UDP socket buffers, Android TUN batching. Keep BBR.

---

## 6. Kotlin/Java contract that must stay exact

```java
// file: net/obscura/vpnclientapp/client/ObscuraLibrary.java
package net.obscura.vpnclientapp.client;
public class ObscuraLibrary {
    @androidx.annotation.Keep public static class FfiHandle { private FfiHandle() {} }
    static native FfiHandle initialize(String configDir, String userAgent);
    static native void jsonFfi(FfiHandle handle, String json, java.util.concurrent.CompletableFuture<String> future);
    static native void setNetworkInterface(FfiHandle handle, String name, int index); // index >= 1
    static native void unsetNetworkInterface(FfiHandle handle);
    static native void forwardLog(int level, String tag, String message, String messageId, String throwableString); // 0..4 = TRACE..ERROR
    static native void flushAndStopPersistedLog();
}
```
```kotlin
// net/obscura/vpnclientapp/client/ErrorCodeException.kt
@androidx.annotation.Keep class ErrorCodeException(val errorCode: String) : Exception(errorCode)  // ctor (Ljava/lang/String;)V

// net/obscura/vpnclientapp/services/ObscuraVpnService.kt
class ObscuraVpnService : android.net.VpnService() {
    companion object {
        @androidx.annotation.Keep @JvmStatic fun ffiSetNetworkConfig(json: String): Int      // "(Ljava/lang/String;)I", returns pfd.detachFd() or -1, never throws
        @androidx.annotation.Keep @JvmStatic fun ffiGetProcessExitReasons(): String?        // "()Ljava/lang/String;", JSON array or null
    }
}
```
R8 (AGP 9 strict full mode needs explicit `<init>`):
```
-keep class net.obscura.vpnclientapp.client.ObscuraLibrary { static native <methods>; }
-keep class net.obscura.vpnclientapp.client.ObscuraLibrary$FfiHandle { <init>(); }
-keep class net.obscura.vpnclientapp.client.ErrorCodeException { <init>(java.lang.String); }
-keep class net.obscura.vpnclientapp.services.ObscuraVpnService {
    public static int ffiSetNetworkConfig(java.lang.String);
    public static java.lang.String ffiGetProcessExitReasons();
}
```
The `applicationId` and the Gradle `namespace` can be anything; declare the service in the manifest by its full name `net.obscura.vpnclientapp.services.ObscuraVpnService`. Native exports in the shipped `.so` today: `JNI_OnLoad` + 6 `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_*` (build §6).

`OsNetworkConfig` passed to `ffiSetNetworkConfig` (Android fields, `rs/network_config.rs:97-113`):
`{"dns":["<ip>"],"ipv4":"<a.b.c.d>","ipv6":"<addr>/<prefix>","routes":[{"address":"<ip>","prefix":<u8>}],"mtu":1280,"useSystemDns":false}`.

---

## 7. Top risks for a Compose client on rustlib, with mitigations

| # | Risk | Cause (source) | Mitigation |
|---|---|---|---|
| R1 | App cannot start the core after a rename or R8 change | 3 `FindClass` names and 6 symbol names are hard-coded (`rs/android/class_cache.rs:19-23`, `rs/android/ffi.rs:75,132,158,173,180,218`); AGP 9.1 repackages classes by default | Put the 3 classes in a small `:core-ffi` module with the exact package; keep rules from section 6; CI check with `apkanalyzer dex packages` and `llvm-nm -D`; an instrumented smoke test that calls `initialize` in the VPN process. |
| R2 | Process abort on any init problem | `panic = "abort"` and `.expect` in `initialize` (`rustlib/Cargo.toml:19-23`, `rs/android/ffi.rs:83-95`); bad config dir, missing class, storage not unlocked | Never mark the VPN process `directBootAware`; create `configDir` first; record a crash counter from `ApplicationExitInfo` and fall back to a "safe mode" screen after repeated `CRASH_NATIVE`. |
| R3 | Two cores write one `config.json` | No file lock (Q9); `initialize` is per process | Load the `.so` only in the VPN process; process-name guard in `ObscuraLibrary` wrapper; UI talks over AIDL only. |
| R4 | Traffic leaks after a crash or OOM kill | Rust forces inactive at start (`rs/client_state.rs:93-95`); sticky restart has a null intent | `START_STICKY`, persisted "wanted" flag in `noBackupFilesDir`, reconnect on null intent; promote always-on + lockdown in the UI and show its state. |
| R5 | Data path stalls from Java callbacks | `CompletableFuture.complete()` runs on a tokio worker; non-async dependents run inline on that thread (`rs/android/future.rs:21-45`); the TUN loop and `maintain` task share this runtime | Only `kotlinx.coroutines.future.await()` with `Dispatchers.Default`; no `thenApply`/`get()`/`join()` in callbacks; AIDL replies are `oneway` from our own executor. |
| R6 | `ffiSetNetworkConfig` failure or pending JNI exception | Called on the tokio blocking pool; a thrown exception stays pending on a reused thread (`rs/android/ffi.rs:232-259`) | Catch `Throwable`, return -1; set the static service reference in `onCreate` before any connect; clear it in `onDestroy`; build split tunnel, DNS override and `setUnderlyingNetworks` in this one function. |
| R7 | Leaked long polls | Not cancellable (finding 7) | One status loop and one exit-list loop (and one relay-list loop after 5.2) in the service; UI gets a `StateFlow` copy over AIDL callbacks. |
| R8 | Lost requests | Bad JSON throws `RuntimeException` synchronously and the future never completes (`rs/android/ffi.rs:98-104,140-143`) | Typed `kotlinx.serialization` builders with `encodeDefaults = true`; wrap each call; timeouts on non-long-poll commands. |
| R9 | Account number leak | Full id in `Status.accountId` and `AccountInfo.id`; upstream logs Status (`kt/services/ObscuraVpnService.kt:325,363`) | Service strips the id before IPC; no Status/AccountInfo logging; R8 `-assumenosideeffects` for `android.util.Log`; `FLAG_SECURE` and `Modifier.sensitiveContent()` on account screens. |
| R10 | Settings silently lost | Login/logout reset (finding 2) | App DataStore is the source of truth; `applyPreferences()` after login and at service start; compare with Status after each push. |
| R11 | Settings that look applied but are not | Flags/SNI only at next connect (finding 3); SNI and alt host not in Status | Label "applies on next connection" or run Q11; keep our own copy of SNI and alt host. |
| R12 | Lifecycle bugs in the service | Rust `Global` survives service re-creation in the same process (`rs/android/ffi.rs:83-92`); upstream never unregisters the network callback (`kt/services/ObscuraVpnService.kt:204-220,344-351`) | Make init idempotent; unregister callbacks in `onDestroy`; one `NetworkCallback` per service instance. |
| R13 | Split tunnel or LAN apps lose network under lockdown | AOSP lockdown exempts only the VPN app (android-platform §1.3) | Detect `isLockdownEnabled()` and warn; test T5. |
| R14 | Low throughput | 1 packet per syscall, one `Mutex<WgState>` for both directions, padding uplink cost (perf-datapath F1, F5) | Fork patches 5.7; A/B against WireGuard on one device. |
| R15 | Licence breach on release | finding 1 | GPL-3.0 for the app; ship source; licence screen from `cargo-about` (`rustlib/about.toml`). Use brand colours only as inspiration; do not ship Obscura mascots or name without permission (trademark is separate from the code licence). |
| R16 | Build drift | NDK r26 has no API 35 sysroot; `RUSTFLAGS` drops 16 KB flags; user name in panic paths (build §8) | build §9 script: NDK r28, `-P 35`, `--remap-path-prefix` via `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS`, `llvm-strip`, alignment check; pin rustlib by commit. |
| R17 | Android 17 target changes | `System.load()` of writable files fails; JNI writes to `static final` crash (android-platform §1.5) | Use `System.loadLibrary` from the APK; Rust has no field writes (grep: only `new_object`, `call_method`, `call_static_method`, `find_class` in `rs/android/`). CT/ECH in the network security config do not cover Rust TLS. |
| R18 | Metadata outside the tunnel | App UID excluded: API calls, API DNS lookups, debug probes go on the physical network (`rs/dns.rs:18-40`, `rs/client_state.rs:845-917`) | No third-party SDKs; no Kotlin network calls; debug bundle behind consent; patch 5.5. |
| R19 | Confusing transient errors | Finding 5 and `noInternet` during Q11 | Show errors only if they persist for more than about 5 s or 2 attempts. |
| R20 | Plaintext secrets on disk | `config.json` has account id, token, WG key (`rs/config/persistence.rs:226-291`) | Q7 now; patch 5.4; optional full-disk reliance on file-based encryption (credential-encrypted storage). |

---

## 8. Short design consequences

1. Two processes: Compose UI (main) and VPN (`:vpn`, any name) with Rust, `ObscuraVpnService`, notification and `TileService`. AIDL with `oneway` callbacks replaces upstream broadcasts.
2. The VPN process owns: one status loop, one exit-list loop, account refresh timer, network callback, preference re-apply after login, reconnect-on-restart, Q11 helper.
3. Initialize with `noBackupFilesDir`, a UA per `doc/user-agent.md`, then `flushAndStopPersistedLog()` unless the user enables diagnostics.
4. Ship zero-patch first (all "must" rows work with upstream rustlib). Add the fork for entry relay (5.1, 5.2), key storage (5.4) and speed (5.7) after T2 and the A/B test.
