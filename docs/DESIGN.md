# Ombra: design

Ombra is a native Android client for the Obscura VPN service (obscura.net). It uses the upstream
Obscura Rust core (`rustlib`, GPL-3.0) through JNI, with our own patches, and a new Jetpack Compose UI.
There is no WebView.

Goals, in this order:
1. VPN speed, privacy and security.
2. A fast, responsive app with a clean, minimal UI. Colours take inspiration from the official client.
3. All main Obscura features, plus the choice of entry relay and exit.

The official Android app is slow next to plain WireGuard on the same phone and network.

Research notes (read the ones your task needs; they have file:line citations into upstream code):
`docs/research/*.md`. The most important: `critic.md` (fact-checked summary, feature parity matrix,
patch designs, risks), `rust-ffi.md` (JNI + JSON protocol), `perf-plan.md` (speed plan, when present),
`perf-udp-mode.md`, `perf-datapath.md`, `perf-bench.md`, `android-platform.md`, `ui-features.md`,
`build.md`.

Upstream source (read-only reference): `/tmp/obscura-research/obscuravpn-client` at commit
`85ef7e2692dc811a023360f08865bfb90815649c`. The API crate at the pinned rev:
`/tmp/obscura-research/obscuravpn-api`.

---

## 1. Identity and versions

| Item | Value |
|---|---|
| App name | Ombra |
| applicationId / namespace | `app.ombra` (debug: suffix `.debug`) |
| Licence | GPL-3.0-only (the Rust core is GPL-3.0; the APK is a combined work) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 36 |
| JDK | 21 (`/opt/homebrew/opt/openjdk@21`) |
| Gradle / AGP / Kotlin | 9.8.2 (9.8.1 is ok) / 9.4.1 / 2.4.21 (AGP 9 built-in Kotlin; apply only the compose and serialization plugins) |
| Compose BOM / material3 | 2026.09.00 / 1.5.0-beta01 (Expressive) |
| navigation3 / lifecycle / activity / core-ktx | 1.2.0 / 2.11.0 / 1.13.0 / 1.19.1 |
| kotlinx-serialization-json / coroutines | 1.12.0 / 1.11.0 |
| profileinstaller | 1.4.1 |
| Rust toolchain | 1.89.0 (pinned by `rust/obscura-core/rust-toolchain.toml`) |
| NDK for Rust | 28.0.13004108, `cargo ndk -P 35` |
| ABI | arm64-v8a only (release and emulator). x86_64 is not built. |

Conventions are the same as the sibling project `~/Documents/Github/Varco` (read its
`app/build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`, `scripts/make-release-key.sh`):
release signing from `keystore.properties` + macOS Keychain, unsigned release without a key,
`dependenciesInfo` off, `vcsInfo` off, R8 + resource shrinking on release, `.debug` suffix.

No third-party SDKs. Only AndroidX, Kotlin and kotlinx libraries. No analytics, no crash reporting,
no Google Play Services, no Firebase, no billing. The app makes no network call from Kotlin. Only
the Rust core uses the network.

## 2. Repository layout

```
Ombra/
  app/                         Android app module (Compose UI + VPN process)
    src/main/java/app/ombra/
      core/ffi/                JNI bridge. Class names here are a contract with Rust (section 4).
      core/model/              @Serializable models of the Rust JSON (commands, Status, exits, relays ...)
      core/                    AccountNumber (format, Verhoeff, generate), shared helpers
      ipc/                     AppState and other models that go over AIDL
      vpn/                     Runs ONLY in the :vpn process: CoreHost, CoreService, OmbraVpnService,
                               NetworkMonitor, TunConfigurator, VpnNotifications, QuickTileService,
                               WgKeyVault, SettingsStore
      data/                    Runs in the main process: CoreClient (binds CoreService), UiPrefs
      ui/                      Compose: theme/, components/, screens (connect, locations, settings,
                               account, onboarding, about), navigation, MainActivity
    src/main/aidl/app/ombra/ipc/   IOmbraCore.aidl, IStateListener.aidl, IResultCallback.aidl
  rust/obscura-core/           Upstream rustlib, vendored, plus our patches (one git commit per patch)
  rust/UPSTREAM.md             Upstream commit, how to update, list of our patches
  scripts/build-rust.zsh       Builds libobscuravpn_client.so into app/src/main/jniLibs/arm64-v8a
  scripts/make-release-key.sh  Same pattern as Varco
  docs/DESIGN.md, docs/research/
  LICENSE (GPL-3.0 text), README.md
```

`app/src/main/jniLibs/` is build output: it is in `.gitignore`. A Gradle task (or a documented manual
step) runs `scripts/build-rust.zsh` before `preBuild` when the `.so` is missing or older than the Rust
sources. Keep `CARGO_TARGET_DIR` outside `rust/obscura-core` (use `build/rust-target`).

## 3. Process model

Two processes in one APK:

- **Main process** (`app.ombra`): Compose UI only. It never loads the native library.
- **`:vpn` process**: loads `libobscuravpn_client.so` and owns all state. Components in this process:
  - `CoreHost` (singleton object): initializes Rust once per process (idempotent), runs the ONE
    status long-poll loop, the ONE exit-list loop and the ONE relay-list loop, refreshes account info
    (at start, after login, every 12 h while the process lives, and when a connect error is
    `accountExpired`), owns `SettingsStore` and re-applies settings to Rust after every login and at
    init. Exposes `StateFlow`s.
  - `CoreService` (plain bound `Service`, not exported): returns the `IOmbraCore` AIDL binder.
  - `OmbraVpnService` (`VpnService`, foreground, type `systemExempted`): started only to connect, by
    the UI (`startForegroundService` with action CONNECT), by the Quick Settings tile, by a shortcut,
    or by the system for always-on (`VpnService.SERVICE_INTERFACE`). It holds the static instance used
    by the Rust callback `ffiSetNetworkConfig`, runs `NetworkMonitor`, owns the notification, and stops
    itself when the tunnel is disconnected and not wanted.
  - `QuickTileService` (`TileService`).
- A process-name guard in `core/ffi` throws if the native library is loaded outside `:vpn`.
  Two cores in two processes would corrupt `config.json` (no file lock).

`OmbraVpnService` rules (from research, see `critic.md` sections 4.2 and 7):
- Return `START_STICKY`. Persist a "VPN wanted" flag in `noBackupFilesDir`. On a null-intent restart
  with the flag set, send `{"setTunnelArgs":{"args":null,"active":true}}`.
- System always-on start (`SERVICE_INTERFACE` action): same command.
- `onRevoke()`: disconnect, clear the wanted flag, stop.
- Call `startForeground` at once in `onStartCommand`.
- Never call `VpnService.prepare()` in the service. The UI asks for consent.
- Unregister every callback in `onDestroy`.

## 4. JNI contract (our fork renames the Java side)

Our Rust fork binds to our own package. Patch `rust/obscura-core/src/android/class_cache.rs` and the
export names in `src/android/ffi.rs`. The exact Java side:

```java
// app/src/main/java/app/ombra/core/ffi/Native.java
package app.ombra.core.ffi;
public final class Native {
    @androidx.annotation.Keep public static final class FfiHandle { private FfiHandle() {} }
    static native FfiHandle initialize(String configDir, String userAgent, byte[] wgSecretKey, byte[] configKey, boolean persistLogs);
    static native void jsonFfi(FfiHandle handle, String json, java.util.concurrent.CompletableFuture<String> future);
    // index >= 1; networkHandle = Network.getNetworkHandle() (0 = do not bind); mtuHint = CLAT-aware MTU (0 = unknown)
    static native void setNetworkInterface(FfiHandle handle, String name, int index, long networkHandle, int mtuHint);
    static native void unsetNetworkInterface(FfiHandle handle);
    static native void wake(FfiHandle handle);
    static native void forwardLog(int level, String tag, String message, String messageId, String throwableString);
    static native void flushAndStopPersistedLog();
}
// app/src/main/java/app/ombra/core/ffi/ErrorCodeException.kt
// @Keep class ErrorCodeException(val errorCode: String) : Exception(errorCode)   ctor (Ljava/lang/String;)V
// app/src/main/java/app/ombra/core/ffi/Callbacks.kt   (object with @JvmStatic @Keep members)
//   fun ffiSetNetworkConfig(json: String): Int          "(Ljava/lang/String;)I"  detached fd or -1, never throws
//   fun ffiGetProcessExitReasons(): String?             "()Ljava/lang/String;"
//   fun ffiStoreWgSecretKey(secret: ByteArray): Boolean "([B)Z"  encrypt + store, never throws
```

JNI symbols become `Java_app_ombra_core_ffi_Native_<method>`. Class cache names:
`app/ombra/core/ffi/Native$FfiHandle`, `app/ombra/core/ffi/ErrorCodeException`,
`app/ombra/core/ffi/Callbacks`. R8 keep rules for all of these are mandatory (AGP 9 full mode).

Kotlin wrapper: `suspend fun call(json: String): String` that awaits the `CompletableFuture` with
`kotlinx.coroutines.future.await()` and resumes on `Dispatchers.Default`. Never block in a
`CompletableFuture` callback (it runs on a tokio worker). Catch the synchronous `RuntimeException`
for bad JSON. Use a timeout for commands that are not long-polls.

`initialize` arguments:
- `configDir` = `context.noBackupFilesDir/core` (create it first). Never in backups.
- `userAgent` = `Ombra-Android/<versionName>`; no user or device data (see
  `obscuravpn-api/doc/user-agent.md`).
- `wgSecretKey` = the WireGuard secret key decrypted by `WgKeyVault` (Android Keystore AES-256-GCM key,
  StrongBox when available, no user-auth requirement), or `null` when none is stored yet.
- `persistLogs` = false by default (Diagnostics setting). When false, Rust writes no log files.
- Before `initialize`, set `RUST_LOG` with `android.system.Os.setenv` to
  `obscuravpn_client=warn,obscuravpn_api=warn,quinn=warn,quinn_proto=warn,neptun=warn` unless
  diagnostics are on.

## 5. Rust fork: patches (in `rust/obscura-core`, one commit each)

JSON stays upstream-compatible: every new field has a serde default.

P0. Vendor upstream `rustlib/` at `85ef7e2` unchanged (first commit), plus upstream `LICENSE` and
    `rustlib/../LICENSE` path expected by about.toml. Then the JNI rename (section 4), the new
    `initialize` arguments (`wgSecretKey`, `persistLogs`), `wake`, and `WgKeyStore::Keychain` for
    Android with the `ffiStoreWgSecretKey` callback.

P1. Tunnel arguments:
```
TunnelArgs {
  exit: ExitSelector,                      // upstream
  #[serde(default)] relay: RelaySelector,  // {"any":{}} | {"relay":{"id":..}} | {"country":{"country_code":..}} | {"city":{"country_code":..,"city_code":..}}
  #[serde(default)] mode: TunnelMode,      // "quic" (default) | "tcpTls" | "wireguardUdp"
  #[serde(default)] udp_mtu: Option<u16>,  // JSON "udpMtu"; only for wireguardUdp; default 1280; clamp 1280..=1420
}
```
    - `relay` filters the relay candidates before the race (empty -> `noRelay`).
    - `mode: tcpTls` has the same effect as the `tcpTlsTunnel` feature flag (the flag still works).
    - `TunnelArgs` is part of the target state, so a change while connected reconnects at once.
    - Also make the feature flags `quicFramePadding`, `forceSmallMtu` and the SNI relay part of the
      target state (critic.md 5.6), so changes apply at once.
P2. New commands:
    - `{"getRelayList":{"knownVersion":null|"<b64>"}}` long-poll -> `{"version","last_updated","value":[OneRelay...]}`
    - `{"refreshRelayList":{"freshness":<ms>}}` -> `null`
    - `{"refreshOsNetworkConfig":{}}` -> `null`: Rust calls `ffiSetNetworkConfig` again with the current
      config while connected or connecting (no reconnect). Kotlin uses it after split-tunnel or
      custom-DNS changes. Since P0.1 the Android `set_os_network_config` keeps the TUN when the config
      is unchanged, so this command must clear the stored config first (`AndroidOsImpl.tun`).
    - `Status.vpnStatus.connected.transport` gains `"wireguardUdp"`.
P3. Privacy: no background system DNS lookup on an API DNS cache hit (`dns.rs`); clear
    `old_account_ids` on logout; never log account ids or tokens.
P4. Speed (QUIC path, shared data path, handover): see section 9 and `docs/research/perf-plan.md`.
P5. WireGuard-over-UDP mode (`mode: wireguardUdp`) with `CreateTunnel::UdpPort`, see
    `docs/research/perf-udp-mode.md` section 6.2. Same two-hop model (relay + Mullvad exit).
    Delete the API tunnel on disconnect (after the data path stops). Persist owned tunnel ids and
    delete leftovers at the next start. Never fall back from QUIC to UDP. Optional fallback from UDP
    to QUIC after a failed handshake is NOT automatic; show an error instead.

Each patch needs Rust unit tests for new serde shapes and selection logic; `cargo test` on the host
must pass, and `cargo clippy` must have no new warnings.

## 6. IPC (AIDL, main <-> :vpn)

```aidl
// app/src/main/aidl/app/ombra/ipc/IOmbraCore.aidl
interface IOmbraCore {
    oneway void call(String requestJson, IResultCallback cb);   // app-level request, see below
    oneway void addListener(IStateListener l);                  // pushes current values at once
    oneway void removeListener(IStateListener l);
}
interface IStateListener {                                      // kind: STATUS, EXITS, RELAYS, SETTINGS, SYSTEM
    oneway void onState(int kind, String json);
    oneway void onStateGz(int kind, in byte[] gzJson);          // the same JSON, gzip-compressed
}
interface IResultCallback { oneway void onResult(String okJson, String errorCode); }
```

- `call` takes app-level requests (a `@Serializable sealed interface Request` in `ipc/`), not raw
  Rust JSON: `Login(accountNumber)`, `CreateAccount`, `Logout`, `DeleteAccount`, `RefreshAccount`,
  `ServiceStatus`, `EndNewAccountFlow`, `RevealAccountNumber`, `UpdateSettings(patch)`, `TrafficStats`, `RotateWgKey`,
  `RefreshLists`, `CreateDebugBundle(feedback)`. Connect and disconnect go through
  `OmbraVpnService` intents (so the foreground service exists). In debug builds only, `RawCommand(json)`.
- `STATUS` pushes a sanitized Status: `accountId` replaced by `null` and a boolean `loggedIn`;
  `account.account_info.id` removed. The full account number is returned only by
  `RevealAccountNumber`.
- Exit and relay lists are pushed only when their version changes. Traffic stats are pulled by the UI
  at 1 Hz only while the connect screen is visible.
- Size of a push (`ipc/StatePush.kt`). All oneway calls into the UI process share its async binder
  buffer (about 512 KiB), and a String takes 2 bytes per char in a Parcel. The exit list budget is
  2000 exits (Mullvad exits): about 560 K chars of JSON, 1.1 MB as a String. Binder refuses that
  with `TransactionTooLargeException` (measured on the emulator). So:
  - JSON of up to 16 Ki chars (32 KiB in the Parcel) goes as a String through `onState`.
  - Longer JSON goes gzip-compressed through `onStateGz`. 2000 exits compress to about 41 KiB, 300
    relays (without certificates) to about 7 KiB.
  - Compressed JSON above 128 KiB (about 6000 exits) is not sent: `:vpn` logs the kind and the JSON
    size and drops the push, and the UI keeps its previous value. If the lists grow near that size,
    send them in parts or through `SharedMemory`.
  - `:vpn` encodes each push once, on the `ombra-ipc` thread. The UI decompresses and decodes on the
    binder thread that delivers the push, never on the main thread. Binder delivers the oneway calls
    to one object one at a time and in order, so a compressed push cannot overtake a plain one.
  - A failed push does not unregister the listener: binder also reports a full async buffer of a
    live process as `DeadObjectException`, and `RemoteCallbackList` drops a dead listener by itself.
  - Debug builds only: `StatePushProbe` (process `:probe`) sends 2000 exits and 300 relays to
    `StatePushProbeTest` across processes. Release builds do not contain it.
- `SYSTEM` pushes `ipc/SystemState.kt` (always-on, lockdown, Private DNS, background restriction,
  cellular, CLAT-aware underlying MTU). Only the :vpn process can read these.
- New connect error code `udpHandshakeFailed` (P5): Speed mode got no WireGuard handshake in about
  5 s. The UI offers "Switch to Stealth".
- Every binder entry point checks `Binder.getCallingUid() == Process.myUid()`.

## 7. Settings (owned by `:vpn`)

`SettingsStore`: a JSON file in `noBackupFilesDir` (atomic write), `StateFlow<Settings>`.
App-level settings are the source of truth. Rust config is reset by login and logout, so
`CoreHost.applySettings()` pushes everything after each successful login and at init.

```
Settings(
  exit: ExitSelector = any, relay: RelaySelector = any,
  mode: TunnelMode = quic, udpMtu: Int = 1280,
  padding: Boolean = false, forceSmallMtu: Boolean = false,
  dnsBlock: DnsContentBlock = all false,
  customDns: List<String> = [],          // when not empty, Kotlin replaces the tunnel DNS in ffiSetNetworkConfig
  useSystemDns: Boolean = false,         // expert option with a leak warning
  lanAccess: Boolean = false,            // upstream default is true; we default to false
  splitMode: SplitMode = off,            // off | exclude | include
  splitApps: Set<String> = [],
  pinnedExits: List<PinnedLocation> = [], pinnedRelays: List<String> = [],
  autoConnectOnLaunch: Boolean = false,
  sniRelay: String? = null, apiHostAlternate: String? = null,
  keyRotation: KeyRotation = days30,     // days30 | day1 | eachConnect -> setWgKeyMaxAge (P14)
  diagnostics: Boolean = false,          // persisted Rust logs + verbose RUST_LOG
  hideNotificationDetails: Boolean = true
)
```

`TunConfigurator` (inside `ffiSetNetworkConfig`) builds the `VpnService.Builder` from Rust's
`OsNetworkConfig` (`{"dns":[..],"ipv4":"..","ipv6":"addr/prefix","routes":[..],"mtu":..,"useSystemDns":..}`):
addresses, routes, MTU, DNS (custom DNS overrides; skip when `useSystemDns`), `allowFamily` for both
families, `setMetered(false)`, `setUnderlyingNetworks(current)`, `setBlocking(false)` unless Rust needs
blocking (check upstream), split tunnel (`addDisallowedApplication(ownPackage)` always in off/exclude
mode; allow-list mode never contains our package), `setConfigureIntent` to open the app.
It must handle the dummy config that Rust uses while connecting. It catches `Throwable` and returns -1.

## 8. UI

Compose, Material 3 Expressive, Navigation3, edge-to-edge, predictive back. Dark theme first, light
theme too, follow system by default. Optional dynamic colour (off by default). Brand palette from
`docs/research/ui-features.md` 8.2 and `product.md` 3.1: brand orange `#FF6025` (gradient
`#FF7A49 -> #FF6025` on the main action only), dark surfaces `#222528` / `#303030` / `#353535`, light
surface `#F6F8F7`, success green `#2FA73B`, error red `#F4352F`, peach containers `#FFC7B2` /
`#FFEDE6`. Use the brand colours as inspiration; do not copy Obscura mascots, wordmark or icons.
Ombra has its own simple icon (an abstract shadow/half-moon shape on brand orange; adaptive icon
with monochrome layer).

Fonts: bundled, OFL (no downloads at runtime): a clean sans for UI (Manrope variable or the system
font) and a mono for account numbers, keys and IPs (JetBrains Mono). Tabular numbers for stats.

Screens (bottom navigation with 3 destinations: Connect, Locations, Settings):
1. **Onboarding / login**: enter account number (20 digits, grouped by 4, local Verhoeff check
   before any network call), or create a new account (show free-trial availability from
   `apiServiceStatus`; then show the new number with a clear "write it down" step). `FLAG_SECURE`.
2. **Connect**: one large connect control with shape morph and state colour (idle / connecting /
   connected / error). State text ("Not protected", "Connecting", "Protected"). Route card that shows
   the path: This device -> Entry relay (city, flag) -> Exit (city, flag, provider) -> Internet. Tap
   entry or exit to choose. Live row: download and upload rate, latency, protocol, session time.
   Transient errors (`other` during retries, `noInternet` during a reconnect) show only if they last
   more than about 5 s. Clear actionable text for every `connectError`.
3. **Locations**: tabs or a switch for Exit / Entry. Search, pinned on top, "Fastest" (any), countries
   that expand to cities, and cities that expand to single servers (show `datacenter_id`, provider).
   Pin by long press or a trailing icon. Flags from regional-indicator emoji.
4. **Settings** (grouped, short descriptions under each item):
   - Account card (status, days left, add time -> browser link, account number reveal/copy with
     `ClipDescription.EXTRA_IS_SENSITIVE`, log out, delete account with strong confirmation).
   - Connection: protocol (Stealth QUIC / Speed WireGuard UDP / TCP-TLS for restrictive networks),
     packet padding (state the real cost), small-MTU compatibility, UDP MTU, auto-connect on launch.
   - Kill switch: show `isAlwaysOn` / `isLockdownEnabled` state; button to system VPN settings.
   - DNS: content blocking (6 categories, only on Mullvad exits), custom DNS servers, use system DNS
     (expert, with warning), Private DNS warning when strict mode is active.
   - Split tunnelling: off / exclude apps / only selected apps; app list with search (needs
     `QUERY_ALL_PACKAGES` or a `<queries>` intent filter for launcher apps; prefer `<queries>`).
   - Local network access.
   - Advanced: WireGuard key (rotate, show public key), SNI relay host, API alternate host,
     diagnostics (logs, debug bundle with consent screen), connection details (relay IP, exit key
     check link to the provider URL).
   - About: version, upstream credit, licences (GPL-3.0 + third-party), source link.
5. Quick Settings tile, static app shortcuts (Connect / Disconnect), notification with a Disconnect
   action. Notification text shows the exit city only when `hideNotificationDetails` is false.

Motion: short, physics-based (Expressive motion scheme). No heavy animations at idle. Respect
"remove animations". Haptic feedback on connect and disconnect.

Performance: baseline profile is optional (later); keep the composition stable (immutable models,
`@Immutable`/`@Stable`), lazy lists with keys, no work on the main thread, cold start target < 300 ms
to first frame on a mid-range phone.

## 9. Speed plan (authoritative source: `docs/research/perf-plan.md`; item ids below refer to it)

Rust P4 (Stealth/QUIC path and shared data path), in this order:
- A2: 1 Hz quinn path stats (rtt, cwnd, current_mtu, lost, black holes, probes, ack counts,
  datagram send buffer space) at DEBUG level, logcat only, only with Diagnostics on.
- B1: MTU floor 1350 (`initial_mtu` + `min_mtu`) when the interface MTU (CLAT-aware) is >= 1378 and
  `forceSmallMtu` is off, with guard 1 (second relay race without the floor after 2 s) and guard 2
  (large liveness probe every 10th busy probe; 3 large lost while small ones return -> reconnect
  without the floor). Without the floor: `initial_mtu(1350)` if allowed, black-hole cooldown 2 s,
  MTU discovery interval 60 s.
- B2: ACK frequency request (threshold 10, max ack delay 5 ms). On by default; if a connection
  with it closes with a QUIC protocol/transport error, turn it off for the rest of the process life.
- B3: `datagram_send_buffer_size(256 KiB)`. B4: keep BBR (do NOT switch to Cubic). B5: padding stays
  opt-in. B6: UDP sockets only: SO_RCVBUF 4 MiB, SO_SNDBUF 2 MiB, log the effective values.
- C1: batch TUN reads (until EAGAIN or 100 packets). C2: do not hold `wg_state` across quinn calls.
  C3: no per-packet heap copy (buffer pool). C4: a decrypted packet from the exit counts as proof
  of life for the liveness checker. C5: the 1 Hz tick log goes to DEBUG and is never persisted.
- D1: on a network change, rebind the QUIC endpoint to a new UDP socket bound to the new Android
  Network (`android_setsocknetwork`), keep the TUN fd and the tunnel, send a liveness probe at once
  (after-sleep strictness); fall back to a full reconnect if liveness is dead within about 3 s or
  the MTU floor decision changed. D2: same for Speed mode (new bound socket + WG keepalive; new
  tunnel if no reply in 3 s).
- E1: with padding on, coalesce WG messages < 600 B for at most 2 ms or until about 1250 B. E2:
  TCP/TLS mode: one buffer per message batch, one flush.
- JNI change for D1/E3: `setNetworkInterface(FfiHandle h, String name, int index, long networkHandle,
  int mtuHint)`. `networkHandle` = `Network.getNetworkHandle()`; `mtuHint` = CLAT-aware MTU computed
  in Kotlin (`min(base MTU, MTU of "v4-" + iface)` when the CLAT interface exists; 0 = unknown, Rust
  reads the MTU itself).

Rust P5 (Speed mode, perf-plan section 3.2): `CreateTunnel::UdpPort` with the relay won by the race
and the chosen exit; neptun over a connected, network-bound UDP socket with B6 buffers; `try_send`,
drop on WouldBlock; no fragmentation; TUN MTU = `udpMtu` (default 1280, max 1420); no keepalive by
default; owned tunnel ids persisted and deleted on disconnect and at start; `label` null. Speed may
NOT silently fall back to Stealth; it reports an error after about 5 s without a handshake and the UI
offers "Switch to Stealth".

Android side:
- `setMetered(false)`, `setUnderlyingNetworks` on each change, keep the fd non-blocking, never call
  `establish()` again when the config did not change.
- `NetworkMonitor`: `registerBestMatchingNetworkCallback(INTERNET + NOT_VPN)` with `onAvailable`,
  `onLost`, `onLinkPropertiesChanged`; pass name, index, network handle and CLAT-aware MTU to Rust.
- No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. If `ActivityManager.isBackgroundRestricted()` is true,
  show a one-time hint to set battery use to "Unrestricted".
- No logging on hot paths; RUST_LOG at warn unless Diagnostics is on.
- A user-facing "Speed test tips" text in About or Diagnostics with the A/B protocol summary from
  perf-plan section 5 (short).

## 10. Security and privacy checklist

- `android:allowBackup="false"`, `dataExtractionRules` that exclude everything, `fullBackupContent`
  false. Secrets only in `noBackupFilesDir`.
- WireGuard secret key encrypted with an Android Keystore key (P0). Rust `config.json` (account number,
  token) is encrypted with a key that Android Keystore keeps (`ConfigKeyVault`, P13), in
  `noBackupFilesDir` (credential-encrypted storage).
- `FLAG_SECURE` on onboarding and account screens, and an option for all screens.
  `setHideOverlayWindows(true)` (permission `HIDE_OVERLAY_WINDOWS`) on the activity.
- No exported components except: the launcher activity, `OmbraVpnService` (protected by
  `BIND_VPN_SERVICE`), `QuickTileService` (protected by `BIND_QUICK_SETTINGS_TILE`).
- Network security config: cleartext off, system CAs only (Kotlin makes no network calls anyway).
- R8: strip `android.util.Log` calls below WARN in release (`-assumenosideeffects`); never log
  Status, AccountInfo, account numbers, tokens, keys or the `login` command.
- Clipboard: sensitive flag; clear after 60 s when possible.
- The debug bundle makes network probes and includes logcat: consent screen; delete old zips.
- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`,
  `POST_NOTIFICATIONS`, `ACCESS_NETWORK_STATE`, `HIDE_OVERLAY_WINDOWS`. Nothing else unless a task
  justifies it in its report.

## 11. Testing

- Rust: `cargo test` on the host for all patches (serde shapes, relay filter, UDP mode config).
- Kotlin JVM unit tests: JSON models against the sample JSON in `docs/research/rust-ffi.md`,
  `AccountNumber` (Verhoeff), settings serialization, sanitizer, state reducers.
- Contract: the Rust test `kotlin_contract_test.rs` writes `app/src/test/resources/core-contract.json`
  from Rust values; `CoreContractTest` checks that Kotlin encodes the same commands and decodes every
  result without losing a field. A change of a shape on either side fails one of the two tests.
- Instrumented smoke test on the emulator AVD (API 37, 16 KB pages): the `:vpn` process loads the
  `.so`, `initialize` works, `getStatus` returns a not-logged-in status.
- Instrumented IPC test: `StatePushProbeTest` receives 2000 exits and 300 relays from another
  process (section 6).
- UI review by screenshots (`adb exec-out screencap -p`).
- NEVER create a real Obscura account or log in with a real or random account number in automated
  tests. Network calls to the Obscura API only through user action. The user does the live test.
