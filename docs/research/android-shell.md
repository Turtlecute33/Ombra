# android-shell: upstream Android app shell of Obscura VPN

Source: `/tmp/obscura-research/obscuravpn-client` at commit `85ef7e2` (2026-10-19).
Paths are relative to `android/` unless they start with `rustlib/`, `obscura-ui/` or `flake.nix`.
Tags: **[V]** = verified by reading the code or config. **[I]** = inference (not verified at runtime, or not verified in library source).
No secrets are reproduced (the Firebase API key and the ADI token are omitted on purpose).

---

## 0. Summary

- Two processes [V]:
  - Main process `net.obscura.vpnclientapp`: `App` (Hilt), `MainActivity`, WebView UI (React app `obscura-ui`, loaded from APK assets), native Material `BottomNavigationView`, `OsStatusManager` + network observers, `PreferencesManager`, `JsonFfiBroadcastReceiver`, `DebugBundleFileProvider`, Play billing (play flavor only).
  - `:vpnservice` process: `ObscuraVpnService` + Rust core `libobscuravpn_client.so` (JNI) + notification.
- Command path [V]: JS `obscuraAndroidCommandBridge.invoke(json, id)` -> Kotlin `WebCmd` -> AIDL `IObscuraVpnService.jsonFfi(id, cmd)` -> JNI `ObscuraLibrary.jsonFfi` -> Rust `ManagerCmd::run` -> `CompletableFuture` -> explicit broadcast to `JsonFfiBroadcastReceiver` (main process) -> `WebView.postWebMessage("android/{...}")`.
- Rust owns all VPN logic (account, relay and exit selection, QUIC or TCP-TLS transport, WireGuard, DNS choice, route list). Kotlin only builds the TUN with `VpnService.Builder` when Rust calls the static JNI callback `ObscuraVpnService.ffiSetNetworkConfig(json)`, and returns a raw fd [V].
- No `protect()`. The app excludes its own UID from the VPN with `addDisallowedApplication(packageName)` [V].
- Always-on is supported (system default, no opt-out meta-data). No lockdown awareness, no Quick Settings tile, no boot receiver, no split tunnelling [V].
- FOSS flavor has no Google library in the app code. The play flavor adds Play Billing + Play Services. `google-services.json` is not used by the build [V].
- arm64-v8a only. 16 KB page linker flags. NDK 26.3.11579264. AGP 8.13.0. Kotlin 2.3.10. compileSdk/targetSdk 36. minSdk 31 [V].

---

## 1. VpnService implementation

### 1.1 Manifest declaration (`app/src/main/AndroidManifest.xml`) [V]

```xml
<service
    android:name=".services.ObscuraVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false"
    android:foregroundServiceType="systemExempted"
    android:process=":vpnservice"
    tools:ignore="ForegroundServicePermission,VpnServicePolicy">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```
Lines 61-72. No `<meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON">`: always-on is allowed by default [V absence; default behaviour is AOSP].

Other components: `MainActivity` exported, `launchMode="singleInstance"`, `configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize"`, `windowSoftInputMode="adjustNothing"` (29-49); deep link scheme `obscuravpn` with `BROWSABLE` (42-48); `DebugBundleFileProvider` authority `${applicationId}.debug_bundle_file_provider`, not exported, `grantUriPermissions=true` (51-59); `JsonFfiBroadcastReceiver` not exported (74-76); meta-data `android.webkit.WebView.MetricsOptOut=true` (78-80). Application: `allowBackup="true"` (16), `dataExtractionRules` (17), `memtagMode="async"` (20; debug overrides to `sync` in `app/src/debug/AndroidManifest.xml:5-7`).

### 1.2 Process layout and AIDL [V]

- `ObscuraLibrary.load()` refuses to run outside `:vpnservice`: `if (!Application.getProcessName().endsWith(":vpnservice")) throw IllegalStateException(...)` (`ObscuraLibrary.java:16-18`), then `System.loadLibrary("obscuravpn_client")` and `initialize(context.getFilesDir().getAbsolutePath(), userAgent)` (19-20).
- AIDL `app/src/main/aidl/net/obscura/vpnclientapp/services/IObscuraVpnService.aidl:4-12`:
  ```aidl
  interface IObscuraVpnService {
      void stopTunnel();
      void jsonFfi(long id, String command);
  }
  ```
  Not `oneway`: calls are synchronous binder transactions. The doc comment (7-10) is stale: it names "CommandBridge.Receiver" and "result"/"exception" extras, but the real receiver is `JsonFfiBroadcastReceiver` and the extras are `"id"`, `"value"`, `"errorCode"` (`services/IntentExtension.kt:10-12`).
- Binder impl `ObscuraVpnService.Binder` (`ObscuraVpnService.kt:71-97`): `stopTunnel()` -> `service.stopTunnel()`; `jsonFfi(id, command)` -> `rustFfi.jsonFfi(command, future)`, then `future.handle { value, exception -> sendBroadcast(Intent(service, JsonFfiBroadcastReceiver::class.java).apply { putJsonFfiExtras(id, value, exception) }) }`. The handle callback runs on a Rust tokio thread (attached to the JVM as daemon, `rustlib/src/android/ffi.rs:114`).
- Bind from UI: `bindService(intent, conn, BIND_ABOVE_CLIENT or BIND_AUTO_CREATE)` (`services/ContextExtension.kt:14-28`, flags at 18). Same-UID bind works despite `android:permission=BIND_VPN_SERVICE` [I: AMS grants same-app access first].
- `onBind` returns `super.onBind(intent) ?: Binder(this)` (`ObscuraVpnService.kt:296-304`): system bind (`SERVICE_INTERFACE`) gets the framework binder (needed for `onRevoke`), app bind gets the AIDL binder.
- Result extras (`IntentExtension.kt:17-54`): `id: Long`, `value: String?` (Rust result JSON), `errorCode: String?` (only if exception is `ErrorCodeException`). Receiver side: `value != null` -> success, else `ErrorCodeException(errorCode ?: "other")`.
- `JsonFfiBroadcastReceiver` (`ui/JsonFfiBroadcastReceiver.kt:16-49`): static `ConcurrentHashMap<Long, CompletableDeferred<String>>`, random `Long` ids, calls `binder.jsonFfi(id, cmd)`; on binder exception completes the deferred exceptionally.

### 1.3 Lifecycle [V]

`onCreate` (`ObscuraVpnService.kt:164-225`):
1. `rustFfi = RustFfi(this, "obscura.net/android/${BuildConfig.VERSION_NAME}")` (168): loads the .so and initializes Rust (user agent string is sent to the API).
2. `log = rustFfi.logger(tag)`: Kotlin logs in this process are also forwarded into Rust tracing (`client/RustFfi.kt:14-23`).
3. Static `instance` AtomicReference set (171) for the JNI static callbacks.
4. `logLastExitReason()` via `ActivityManager.getHistoricalProcessExitReasons` (227-239).
5. JVM shutdown hook that dumps all thread stacks and calls `flushAndStopPersistedLog()` (179-194).
6. `registerBestMatchingNetworkCallback(NetworkRequest[INTERNET, NOT_VPN], callback, mainHandler)` (198-220): `onAvailable` -> `updateInterface(network)`; `onLost` (if current) -> `updateInterface(null)`. Never unregistered.
7. `NotificationManager(this)` creates channel (222), then `loadStatus(null)` (224) starts a GetStatus long-poll chain for the notification (353-367).

`onStartCommand` (268-294):
```kotlin
val prepareResult = this.prepareVpnService()
val isForeground = this.promoteToForeground(prepareResult).isSuccess
when (intent?.action) {
    ACTION_START_TUNNEL -> if (isForeground) this.startTunnel(intent.getStartTunnelExtras())
    ACTION_STOP_TUNNEL -> this.stopTunnel()
    SERVICE_INTERFACE -> { if (isForeground) this.startTunnel(null) }   // system-initiated (always-on)
}
if (!isForeground) { notificationManager.notify(vpnStatus, prepareResult); stopSelfResult(startId) }
return START_STICKY
```
- `ACTION_START_TUNNEL = "${BuildConfig.APPLICATION_ID}.actionStartTunnel"`, `ACTION_STOP_TUNNEL = "${BuildConfig.APPLICATION_ID}.actionStopTunnel"`, extra `"exitSelector"` (`IntentExtension.kt:13-15,56-58`).
- `startTunnel(exitSelector)` = `setTunnelArgs(exitSelector, true)`; `stopTunnel()` = `setTunnelArgs(null, false)` (369-389). `setTunnelArgs` sends `ManagerCmd.SetTunnelArgs(args = exit?.let { jsonConfig.decodeFromString(it) }, active)` to Rust and ignores the result.
- `promoteToForeground` (241-266): on API 34+ it calls `startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)` only if `prepare()` returned Ready, else throws "VPN not prepared". Below API 34 it calls `startForeground(1, notification)`.
- `onBind`/`onRebind` with `SERVICE_INTERFACE` -> `promoteToForeground(Ready)` (296-313). `onUnbind` with `SERVICE_INTERFACE` -> `stopForeground(STOP_FOREGROUND_DETACH)`; returns `true` (315-322).
- `onRevoke()` -> `stopTunnel()` then `super.onRevoke()` (338-342). Comment: only called if the system is bound, so only during an active connection.
- `onDestroy()` clears `instance`, calls `stopTunnel()` (344-351).
- The service never stops itself or leaves foreground when the tunnel goes to Disconnected; the notification stays with a "Quick Connect" action (324-333) [V]. The `:vpnservice` process stays alive while started [I].
- Rust initializes with `force_init_inactive = true` (`rustlib/src/android/ffi.rs:67`), which sets `config.tunnel_active = false` at every process start (`rustlib/src/client_state.rs:93-95`). After a process death, a `START_STICKY` restart has a null intent and hits no `when` branch, so the tunnel does not resume unless the system re-launches it as always-on (`SERVICE_INTERFACE`) [V code path, I runtime].

Start path from UI (`ui/VpnPermissionRequestManager.kt:86-111`):
- `prepareVpnService()` (`ContextExtension.kt:47-58`): `VpnService.prepare(this)?.let { CreateProfile(it) } ?: Ready`; catches `IllegalStateException` -> `LegacyAlwaysOn` (thrown when a legacy VPN profile is always-on).
- `CreateProfile` -> launch the system consent intent (ActivityResult). `RESULT_OK` -> start. `RESULT_CANCELED` within 150 ms -> `errorOtherAppAlwaysOn`, else `errorPermissionNotGranted` (heuristic, 33, 70-84). `LegacyAlwaysOn` -> `errorLegacyAlwaysOn`.
- On success: request `POST_NOTIFICATIONS` if missing (API 33+) (47-63), then `startForegroundService(Intent(ACTION_START_TUNNEL).putExtra("exitSelector", tunnelArgsJson))` (`ContextExtension.kt:60-76`).

### 1.4 `VpnService.Builder` configuration (`ObscuraVpnService.kt:391-431`) [V]

| Builder call | Value |
|---|---|
| `addDisallowedApplication(applicationInfo.packageName)` | always; own app bypasses the VPN (397-398) |
| `setMtu(networkConfig.mtu)` | from Rust; always `TUNNEL_MTU = 1280` (`rustlib/src/quicwg.rs:61`, `client_state.rs:396`) |
| `setMetered(false)` | VPN inherits meteredness from underlying networks (402-405) |
| `addDnsServer(it)` for each `dns` | only if `!useSystemDns`; on Android `useSystemDns` is never true because no UI writes it (407-414) |
| `addAddress(ipv4, 32)` | `ipv4` is a bare address string, default prefix 32 (416) |
| `addAddress(ipv6addr, prefix)` | `ipv6` is `"addr/prefix"`, default 128 (418) |
| `addRoute(route.address, route.prefix)` | each route from Rust (420) |
| `allowFamily(AF_INET)`, `allowFamily(AF_INET6)` | (422-423) |
| `.establish()` | returns `ParcelFileDescriptor?`; null is logged (425-430) |

Not called [V by grep, no hits in `android/` or `obscura-ui/src`]: `setBlocking` (default fd is non-blocking [I: AOSP default], which Rust `AsyncFd` needs), `Builder.setUnderlyingNetworks`, `allowBypass`, `setHttpProxy`, `excludeRoute`, `addAllowedApplication`, `setSession`, `setConfigureIntent`, `protect()`, `isAlwaysOn()`, `isLockdownEnabled()`.

`VpnService.setUnderlyingNetworks(...)` is called on the service (not the Builder) in `updateInterface` (457-466): `arrayOf(network)` or `emptyArray()`.

Route list from Rust (`rustlib/src/local_network.rs:40-60`): local network access off -> `[0.0.0.0/0, ::/0]`; on -> full space minus LAN ranges (`LAN_V4` 8-16, `LAN_V6` 18-26), with the tunnel DNS IPs kept inside, computed by prefix splitting (about 80 routes, see test 141-174).

### 1.5 FD handoff to Rust [V]

Sequence:
1. Rust `AndroidOsImpl::set_os_network_config(config, tunnel)` serializes `OsNetworkConfig` to JSON (`rustlib/src/android/os_impl.rs:35-58`).
2. `call_set_network_config` (`ffi.rs:232-259`) runs in `spawn_blocking`, attaches the thread as daemon, and calls the static Java method `ObscuraVpnService.ffiSetNetworkConfig` with JNI signature `"(Ljava/lang/String;)I"`.
3. Kotlin (`ObscuraVpnService.kt:103-126`): decode JSON to `OsNetworkConfig`, `applyNetworkConfig`, `return pfd?.detachFd() ?: -1`.
4. Rust: `j_fd >= 0` -> `OwnedFd::from_raw_fd(j_fd)`; else `Err(())`.
5. `Tun::spawn(fd, tunnel)` (`rustlib/src/android/tunnel.rs:18-54`): `AsyncFd` read loop with a 1280-byte buffer, one packet per `read`, forwarded with `tunnel.send(iter::once(&buf[..n]))`. Writes: one `unistd::write` per packet (56-69).
6. The new `Tun` replaces the old one in `Mutex<Option<Tun>>`; the old fd closes on drop (`os_impl.rs:56`). `unset_os_network_config` sets it to `None` (60-63), which closes the fd and removes the VPN interface.

JSON contract (`services/OsNetworkConfig.kt:5-20`, `rustlib/src/network_config.rs:97-113`, `#[serde(rename_all = "camelCase")]`):
```kotlin
@Serializable data class OsNetworkConfig(val dns: List<String>, val ipv4: String, val ipv6: String,
    val routes: List<Route>, val mtu: Int, val useSystemDns: Boolean)
@Serializable data class Route(val address: String, val prefix: Int)
```
Example (dummy values from `network_config.rs:42-49`):
```json
{"dns":["10.64.0.99"],"ipv4":"10.75.76.77","ipv6":"fc00:bbbb:bbbb:bb01::c:4c4d/128",
 "routes":[{"address":"0.0.0.0","prefix":0},{"address":"::","prefix":0}],"mtu":1280,"useSystemDns":false}
```
DNS: if the exit provider is `"Mullvad VPN"` and any content-block flag is set, DNS = `100.64.0.<bitset>` with bits ad=1, tracker=2, malware=4, adult=8, gambling=16, socialMedia=32 (`network_config.rs:80-90,125-131`). Otherwise DNS = tunnel config DNS.

Two `establish()` calls per connect [V, `rustlib/src/tunnel_state.rs:245-330`]: first a dummy config with a packet sender of `None` (traffic is captured and dropped while connecting: leak protection), then the real config after the tunnel is up. The same happens on every reconnect, including network changes.

### 1.6 `protect()` and bypass [V]

- `protect()` is never called.
- The whole app UID is excluded (`addDisallowedApplication`), so all sockets from both processes go out on the physical network.
- On Android, Rust ignores the interface for socket binding: `new_socket` creates `Domain::IPV4` sockets and binds `0.0.0.0:0` (`rustlib/src/net.rs:28-51`, Android branch 45-48). The outer transport is IPv4-only [V]; on IPv6-only networks it depends on CLAT/464XLAT [I].
- The interface name and index are used only for (a) MTU lookup by `SIOCGIFMTU` ioctl (`net.rs:63-95`, `client_state.rs:321-352`), (b) clearing the cached API client on change (353-357), and (c) forcing a tunnel reconnect when it differs (`tunnel_state.rs:163-169,203-229`). With no interface, Rust stays "connecting" (`tunnel_state.rs:365-374`) behind the dummy TUN.

### 1.7 Always-on and lockdown [V unless tagged]

- Supported implicitly (no opt-out meta-data). System start arrives as `onStartCommand` with action `SERVICE_INTERFACE` -> `startTunnel(null)` -> `SetTunnelArgs(args=null, active=true)`: Rust keeps the persisted `tunnel_args` (last exit) (`client_state.rs:261-270`).
- Lockdown: nothing app-specific. The app does not read `isLockdownEnabled()`, does not show status, does not guide the user to `Settings.ACTION_VPN_SETTINGS`.
- `onRevoke` handled (1.3). The legacy always-on case and the other-app always-on case are mapped to error codes (1.3).

### 1.8 Foreground notification (`services/NotificationManager.kt`) [V]

- Channel `"vpn_channel"`, `IMPORTANCE_LOW`, name "Obscura VPN" (24, 31-40). Notification id `1` (25). `PendingIntent.FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` (26).
- Content: title app name, text `"Status: %1$s"` with Connected / Connecting… / Disconnected (`res/values/strings.xml:7-10`), small icon `ic_stat_name`, `FOREGROUND_SERVICE_IMMEDIATE`, `setOngoing(true)`, `setLocalOnly(true)`, `setOnlyAlertOnce(true)`, `CATEGORY_SERVICE` (110-135). No lock-screen visibility setting.
- Actions: Connected/Connecting -> "Disconnect" = `PendingIntent.getService(ACTION_STOP_TUNNEL)`; Disconnected/null -> "Quick Connect" = `getService(ACTION_START_TUNNEL)` if prepared, else opens `MainActivity` with path `/requestVpnStart` (84-102). Always a "Location" action that opens the Location view (104-108). Content intent opens `MainActivity` (114).
- `getService` (not `getForegroundService`) on purpose to avoid an ANR when the action is stale (58-62).
- `notify()` checks `POST_NOTIFICATIONS` first (137-152).
- Permissions: `FOREGROUND_SERVICE` (with `tools:ignore="ForegroundServicesPolicy"`), `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `POST_NOTIFICATIONS` (manifest 8-12). FGS type `systemExempted`.

### 1.9 Boot, autostart, Quick Settings [V]

- No `RECEIVE_BOOT_COMPLETED`, no boot receiver. Boot start only through system always-on.
- Rust `autoConnect` is applied only on a fresh `MainActivity` launch: first status with `autoConnect && Disconnected` -> `requestVpnStart(null)` (`ui/ObscuraUI.kt:144-167`).
- No `TileService`. No app shortcuts. No widget.

---

## 2. Network observation [V]

| Component | Process | Request | Output | Purpose |
|---|---|---|---|---|
| best-matching callback in `ObscuraVpnService` (198-220) | `:vpnservice` | `INTERNET + NOT_VPN`, `registerBestMatchingNetworkCallback` (API 31) | `setUnderlyingNetworks`, Rust `setNetworkInterface(name, index)` or `unsetNetworkInterface()` | outer network for Rust, MTU, reconnect on change, VPN capability/meteredness |
| `NetworkStatusObserver` (`ui/NetworkStatusObserver.kt:14-44`) | main | `INTERNET + NOT_VPN + VALIDATED`, `registerNetworkCallback` | counter `availableNetworks` -> `OsStatus.internetAvailable` | UI offline state |
| `PrivateDnsObserver` (`ui/PrivateDnsObserver.kt:13-54`) | main | `INTERNET + NOT_VPN` | `Set<Network>` where `linkProperties.privateDnsServerName != null` -> `OsStatus.privateDnsActive` | warn that Private DNS overrides Obscura DNS |

- Interface props: `connectivityManager.getLinkProperties(network).interfaceName` + `java.net.NetworkInterface.getByName(name).index` (`ObscuraVpnService.kt:433-455`).
- Private DNS: only strict mode (hostname set) is detected; opportunistic ("Automatic") mode is not. UI text: "Android's Private DNS is turned on, this overrides Obscura's DNS settings." (`obscura-ui/src/translations/en.json:46`, shown at `obscura-ui/src/views/Settings.tsx:87-91`). Rationale comment: system Private DNS overrides VPN DNS, no fix without `WRITE_SECURE_SETTINGS`, same as Mullvad issue 5009 (`ObscuraVpnService.kt:409-411`).
- `OsStatusManager` (`ui/OsStatusManager.kt:17-95`): `@Singleton`, mutable `State` (colorScheme, debugBundleStatus, internetAvailable, navigationView, playBilling, privateDnsActive, vpnStatus). Each `update {}` makes a new UUID version and completes all waiters. `waitForUpdate(knownVersion)` is a long-poll: it returns at once if the version differs.
- `OsStatus` JSON (`ui/OsStatus.kt:6-73`):
  ```json
  {"version":"<uuid>","internetAvailable":true,"osVpnStatus":"disconnected|connecting|connected",
   "srcVersion":"<VERSION_NAME>","navigationView":null,"colorScheme":"light|dark|auto",
   "updaterStatus":{"type":"uninitiated","appcast":null,"error":null,"errorCode":null},
   "debugBundleStatus":{"inProgress":false,"latestPath":null,"inProgressCounter":0},
   "canSendMail":true,"loginItemStatus":null,"playBilling":false,"privateDnsActive":false}
  ```
  `navigationView` values: `developer|connection|location|account|help|about|settings`. `inProgressCounter` is never incremented.

---

## 3. UI bridge

### 3.1 Transport [V]

- JS side (`obscura-ui/src/bridge/setupBridge.ts:14-23,29-61`): `window.obscuraAndroidCommandBridge.invoke(data, id)`; replies arrive as `message` events with prefix `"android/"` and body `{"id":N,"data":"<json string>"}` or `{"id":N,"error":"<code>"}` (63-89).
- Command JSON from JS: `{"<command>": {args}}` (`obscura-ui/src/bridge/commands.ts:13-25`).
- Kotlin side `WebCmdBridge` (`ui/bridge/WebCmdBridge.kt:17-66`): `@JavascriptInterface fun invoke(data: String, id: Long)`; coroutine scope `Dispatchers.Main.immediate + SupervisorJob()`; decodes `WebCmd`; replies `Accept(id, data)` or `Reject(id, errorCode)`; non-`ErrorCodeException` -> `"other"`.
- WebView (`ui/ObscuraWebView.kt`): origin `https://appassets.androidplatform.net`, home `.../assets/web/index.html` (31-33); `WebViewAssetLoader` for `/assets/` and `/res/` (60-63); JS + DOM storage on (39-40); `addJavascriptInterface(bridge, "obscuraAndroidCommandBridge")` (41); `RENDERER_PRIORITY_BOUND` (42); console -> Logger (43-59); off-origin main-frame navigation opens `ACTION_VIEW` with `http` upgraded to `https` (66-88); insets injected as CSS variables `--safe-area-inset-*` via `evaluateJavascript` (109-123).
- JSON config (`client/JsonConfig.kt:6-10`): `classDiscriminatorMode = NONE`, `encodeDefaults = true`, `ignoreUnknownKeys = true`. Serde-style externally tagged enums are done with `ExternallyTaggedEnumSerializer` / `ExternallyTaggedEnumVariantSerializer` (`lib/util/.../ExternallyTaggedEnumSerializer.kt:9-15`, `ExternallyTaggedEnumVariantSerializer.kt:9-16`): encode `{"tag": <fields>}`, decode by first matching key.

### 3.2 WebCmd: every command JS can send to Kotlin (`ui/bridge/WebCmd.kt:37-54`) [V]

`{}` below is `jsonConfig.encodeToString(Unit)`.

| JSON | Kotlin action | Result |
|---|---|---|
| `{"debugBundle":{"userFeedback":"..."}}` (59-97) | `debugBundleStatus.inProgress=true`; reads `context.cacheDir` on IO; runs `createDebugBundle` via JsonFfiCmd; sets `latestPath` | `"{}"` (the path is not returned; UI reads `osStatus.debugBundleStatus.latestPath`) |
| `{"emailDebugBundle":{"path":"...","subject":"...","body":"..."}}` (99-111) | `shareDebugBundle(email=true)` | `"{}"` |
| `{"getOsStatus":{"knownVersion":null|"<uuid>"}}` (113-120) | `osStatusManager.waitForUpdate` (long-poll) | OsStatus JSON |
| `{"jsonFfiCmd":{"cmd":"<ManagerCmd JSON>","timeoutMs":10000}}` (122-132) | forwards `cmd` to Rust; `timeoutMs` is ignored (no field) | Rust result JSON |
| `{"purchaseSubscription":{"promoCode":null|"..."}}` (134-160) | `apiGoogleBillingDetails` -> Play flow -> `apiGoogleAssociateAccount` per token | `"true"` / `"false"` |
| `{"revealItemInDir":{"path":"..."}}` (162-174) | none | reject `errorUnsupportedOnOS` |
| `{"setColorScheme":{"value":"light|dark|auto"}}` (176-191) | `PreferencesManager.write` | `"{}"` |
| `{"setFeatureFlag":{"flag":"...","active":true}}` (193-206) | none (UI uses the Rust `setFeatureFlag` via jsonFfiCmd instead) | reject `errorUnsupportedOnOS` |
| `{"setNavigationView":{"view":"connection"}}` (208-220) | selects bottom-nav item or sets `navigationView` | `"{}"` |
| `{"shareDebugBundle":{"path":"..."}}` (222-234) | `shareDebugBundle(email=false)` | `"{}"` |
| `{"startTunnel":{"tunnelArgs":"{\"exit\":{...}}"}}` (236-251) | `requestVpnStart(tunnelArgs)` | `"{}"` or reject `errorPermissionNotGranted` / `errorOtherAppAlwaysOn` / `errorLegacyAlwaysOn` / `other` |
| `{"stopTunnel":{}}` (253-263) | `binder.stopTunnel()` | `"{}"` |

UI commands with no Android handler (decode fails -> reject `"other"`) [V]: `restartService`, `linuxAddOperator`, `restartApp`, `windowsFix`, `setStrictLeakPrevention`, `refreshLoginItemStatus` (guarded in UI), `registerAsLoginItem`, `unregisterAsLoginItem`, `resetUserDefaults`, `sendNotification`, `checkForUpdates`, `installUpdate`, `getSubscriptionProduct`, `associateAccount`, `restorePurchases`, `showOfferCodeRedemption`.

Exit selector JSON (Rust `rustlib/src/exit_selection.rs:9-23`, `rename_all="camelCase"` on variants only, so fields stay snake_case):
`{"any":{}}`, `{"exit":{"id":"..."}}`, `{"country":{"country_code":"xx"}}`, `{"city":{"country_code":"xx","city_code":"yyy"}}`.
`TunnelArgs` = `{"exit": <ExitSelector>}` (`rustlib/src/manager.rs:137-141`). There is no entry-relay field: the relay is chosen in Rust (`client_state.rs:512`); the shell has no say.

### 3.3 Rust `ManagerCmd`: every command reachable through `jsonFfiCmd` (`rustlib/src/manager_cmd.rs:129-218`) [V]

`#[serde(rename_all = "camelCase", rename_all_fields = "camelCase")]`. Shape `{"<name>":{fields}}`.

| Command | Fields |
|---|---|
| `apiAppleAssociateAccount` | `appTransactionJws: String` |
| `apiDeleteAccount` | none |
| `apiGetAccountInfo` | none |
| `apiGoogleAssociateAccount` | `purchaseToken: String, promoCode: String?` |
| `apiGoogleBillingDetails` | `promoCode: String?` |
| `apiServiceStatus` | none |
| `createDebugBundle` | `userFeedback: String?, bundleInfo: BundleInfo, androidCacheDir: String?` |
| `createServiceDebugBundle` | none |
| `getDebugInfo` | none |
| `getExitList` | `knownVersion: base64 String?` |
| `getStatus` | `knownVersion: UUID String?` (long-poll until version differs) |
| `getTrafficStats` | none |
| `terminateProcess` | none (exit(1) after 3 s) |
| `login` | `accountId: String, validate: bool` |
| `logout` | none |
| `ping` | none |
| `refreshExitList` | `freshness: u64 ms` |
| `deleteServiceDebugBundle` | `token` |
| `rotateWgKey` | none |
| `setApiHostAlternate` | `host: String?` |
| `setApiUrl` | `url: String?` |
| `setAutoConnect` | `enable: bool` |
| `setDnsContentBlock` | `value: {ad, tracker, malware, adult, gambling, socialMedia}` (bools) |
| `setFeatureFlag` | `flag: String, active: bool` |
| `setInNewAccountFlow` | `value: bool` |
| `setPinnedExits` | `exits: [PinnedLocation]` |
| `setSniRelay` | `host: String?` |
| `setTunnelArgs` | `args: TunnelArgs?, active: bool?` |
| `setUseSystemDns` | `enable: bool` |
| `setLocalNetworkAccess` | `enable: bool` |
| `setTailscaleBypass` / `setWireGuardBypass` | `enable: bool` (Linux only in UI) |

Results are untagged (`manager_cmd.rs:220-240`); setters return `Empty`, which serializes as `null` [I: serde untagged unit variant]. Error codes (`manager_cmd.rs:43-58`, camelCase): `apiAssociateAccountConflict, apiError, apiInvalidAccountId, apiNoLongerSupported, apiRateLimitExceeded, apiSaleNotFound, apiSignupLimitExceeded, apiUnreachable, configSaveError, notLoggedIn, other`. Kotlin-only codes (`client/ErrorCodeException.kt:9-35`): `playServicesDisabled, playServicesMissing, playServicesUpdateRequired, playServicesUpdating, purchaseFailed, purchaseFailedAlreadyOwned, errorLegacyAlwaysOn, errorOtherAppAlwaysOn, errorPermissionNotGranted, errorUnsupportedOnOS`.

Kotlin typed models (`client/ManagerCmd.kt:9-81`, `client/ManagerCmdOk.kt:9-59`):
```kotlin
ManagerCmd.ApiGoogleAssociateAccount(purchaseToken: String, promoCode: String?)  // "apiGoogleAssociateAccount"
ManagerCmd.ApiGoogleBillingDetails(promoCode: String?)                            // "apiGoogleBillingDetails"
ManagerCmd.CreateDebugBundle(userFeedback: String?, bundleInfo: BundleInfo, androidCacheDir: String?)
ManagerCmd.GetStatus(knownVersion: String?)
ManagerCmd.SetTunnelArgs(args: Map<String, JsonObject>? = null, active: Boolean? = null)
ManagerCmdOk.ApiGoogleBillingDetails(@SerialName("product_id") productId, @SerialName("base_plan_id") basePlanId, @SerialName("offer_id") offerId: String?)
ManagerCmdOk.GetStatus(accountId: String?, autoConnect: Boolean, inNewAccountFlow: Boolean, version: String, vpnStatus: VpnStatus)
VpnStatus = {"connected":{...}} | {"connecting":{...}} | {"disconnected":{}}
```
Full Rust `Status` (`manager.rs:56-74`): `version, vpnStatus, accountId, inNewAccountFlow, pinnedLocations, lastChosenExit, lastExit, apiUrl, account, autoConnect, featureFlags, featureFlagKeys, useSystemDns, localNetworkAccess, tailscaleBypass, wireguardBypass, dnsContentBlock`. `VpnStatus` (`manager.rs:117-135`): `connecting{tunnelArgs, connectError, reconnecting}`, `connected{tunnelArgs, exit, relay, clientPublicKey, exitPublicKey, transport}` with `transport` = `"quic" | "tcpTls"` (`rustlib/src/quicwg.rs:204-209`), `disconnected{}`.

### 3.4 Deep links [V]

`ObscuraUI.handleObscuraUri` (`ui/ObscuraUI.kt:229-248`): path without leading `/`. `requestVpnStart` -> select Connection tab and `requestVpnStart(null)`. Any other value -> decode as `NavigationView` and navigate. Sources: `obscuravpn://...` (browsable) and notification intents with data `"/<path>"`.

### 3.5 DebugBundle [V]

- Device info (`ui/bridge/DebugBundle.kt:17-62`) -> `BundleInfo` with PascalCase keys (`client/ManagerCmd.kt:45-62`): `AndroidSDK, AppVersion, BootTimestamp, Brand, LowPowerMode, Model, OSVersionString, PID, ProcessName, ProcessorCountActive, ProcessorName (Build.SOC_MODEL), RAMAvailableGiB, RAMLogicalGiB, ThermalState, UptimeHours`.
- Rust (`rustlib/src/debug_bundle/mod.rs:136-173`) writes `<cacheDir>/debug-archives/Obscura Debug Bundle <RFC3339 with ':' -> '_'>.zip` (`builder.rs:13-29`, `zipper.rs:19-21`). Entries: `info.json`, `ne-debug-info.json` (`DebugInfo`: redacted `ConfigDebug` with `has_account_id` instead of the id (`config/persistence.rs:326-349`), DNS/HTTP probe results, interface + MTU), `user-feedback.txt`, the `rust-log` directory, `logcat.txt` from `logcat -d`, `process-exit-reasons.json` (JNI callback `ffiGetProcessExitReasons`, `ObscuraVpnService.kt:128-147`).
- Probes run while building `DebugInfo` (`client_state.rs:856-901`): DNS for `www.apple.com`, `google.com`, `v1.api.prod.obscura.net`; HTTPS GET `https://www.apple.com/robots.txt`, `https://google.com/robots.txt`, `https://v1.api.prod.obscura.net/api/ping` (with and without SNI), and the Obscura API IPs with SNI `apple.com` / `google.com`.
- Sharing (`ui/bridge/WebCmdHelpers.kt:8-40`): `FileProvider.getUriForFile(ctx, "${packageName}.debug_bundle_file_provider", File(path))`, `ACTION_SEND`, `application/zip`, `FLAG_GRANT_READ_URI_PERMISSION`. Email: `EXTRA_EMAIL=["support@obscura.net"]`, subject, body, no chooser. Share: `Intent.createChooser`. Provider path: `<cache-path name="debug-archives" path="debug-archives/"/>` (`res/xml/debug_bundle_file_provider_paths.xml:3`). Bundles are never deleted by the app.

---

## 4. Persistence [V]

| Where | Process | Content |
|---|---|---|
| `shared_prefs/preferences.xml` (`ui/PreferencesManager.kt:40`) | main | key `"color-scheme"`, value JSON string such as `"\"auto\""` (18, 34). Read on `MainActivity.onCreate` (main-thread disk read, allowed by StrictMode filter, `App.kt:31-38`). Written with `commit = true` on IO under a Mutex (47-55). |
| `files/config.json` (`rustlib/src/config/persistence.rs:29`) | `:vpnservice` | Rust config: account id, auth token cache, WireGuard secret key in plaintext (`WgKeyStore::Plaintext`, `ffi.rs:61-62`, TODO OBS-2699), exits/relays cache, DNS cache, settings. Atomic write through a temp file (persistence.rs:160-200). |
| `files/config-backup-<ts>.json` (persistence.rs:111) | `:vpnservice` | made when the config fails to load |
| `files/rust-log/` (`ffi.rs:39,52`) | `:vpnservice` | JSON logs at INFO+, rotation 10 MB x 24 files (`rustlib/src/logging.rs:47-48,56-57,78-79,86`) |
| `cache/debug-archives/*.zip` | `:vpnservice` writes, main shares | debug bundles |

Config dir = `context.getFilesDir()` of `:vpnservice` (same app data dir) (`ObscuraLibrary.java:20`). Backup (`res/xml/data_extraction_rules.xml:5-16`): cloud backup (`disableIfNoEncryptionCapabilities="true"`) and device transfer exclude only `file/rust-log`.

---

## 5. Billing, Google, third parties, permissions (privacy audit)

### 5.1 Flavors [V]

- Dimension `billing`: `foss` (default) and `play` (`app/build.gradle.kts:60-69`). Only `play` depends on `:lib:billing` (`"playImplementation"(project(":lib:billing"))`, 87).
- `foss` `BillingImpl` (`app/src/foss/.../BillingImpl.kt:6`) uses the interface defaults (`ui/BillingFacade.kt:7-18`): `isPlayBilling()=false`, `fetchPurchaseTokens()=null`, `launchFlow` throws `errorUnsupportedOnOS`.
- `play` `BillingImpl` (`app/src/play/.../BillingImpl.kt:16-73`): `PlayServicesManager.checkAvailability`; `BillingManager` if Play Services is present; `launchFlow` tries `makeGooglePlayServicesAvailable` and maps failures to `playServices*` / `purchaseFailed*`.
- `lib/billing`: `BillingConnection` (`BillingClient` with `enableAutoServiceReconnection()`, `enablePendingPurchases(enableOneTimeProducts())`, 31-40); `BillingManager` (SUBS query, product/offer lookup by `productId`/`basePlanId`/`offerId`, `launchBillingFlow` on main thread, 22-188); `PlayServicesManager` (`GoogleApiAvailability`, 15-72).
- Provided via Hilt `@ActivityRetainedScoped` (`ui/BillingModule.kt:15-30`). On every `RESUMED`, the UI fetches all subscription purchase tokens and uploads each with `apiGoogleAssociateAccount` with backoff 1 s .. 64 s (`ui/ObscuraUI.kt:121-136`, `ui/PurchaseTokenUploader.kt:61-81`, `lib/util/.../BinaryExponentialBackoff.kt:7-21`). This links the Google Play purchase to the Obscura account by design.

### 5.2 `google-services.json` [V]

Firebase client config: project id `obscura-vpn`, package `net.obscura.vpnclientapp`, one API key, no OAuth clients, only `appinvite_service`. It is not referenced by any Gradle file (no `com.google.gms.google-services` plugin; repo grep hits only the file itself) and no Firebase SDK is a direct dependency. It has no effect on the APK. It is part of the Nix source set (`flake.nix:332`). Purpose unknown [I: leftover or Play Console linkage].

`app/src/main/assets/adi-registration.properties`: one-line token, shipped in the APK [V]. [I] Android developer verification package-ownership snippet. Static, no network use.

### 5.3 Runtime dependencies [V from `app/build.gradle.kts:77-94`, `lib/*/build.gradle.kts`, `gradle/libs.versions.toml`]

| Dependency | Flavor | Network / third party |
|---|---|---|
| androidx.appcompat 1.7.1, core-ktx 1.18.0, lifecycle-runtime-ktx 2.10.0, material 1.13.0 | all | none [I] |
| androidx.webkit 1.15.0 (+ system WebView) | all | WebView is Google Chromium; only local assets; metrics opt-out set |
| hilt-android 2.58 (+ ksp compiler) | all | none |
| kotlinx-serialization-json 1.10.0 | all | none |
| `:lib:util` | all | none |
| billing-ktx 8.3.0 | play | IPC to Play Store. Lockfile also has `com.google.android.datatransport` transport-api 3.0.0, transport-backend-cct 3.1.8, transport-runtime 3.1.8 and `com.google.firebase` firebase-encoders* (`gradle/mitm-cache/deps.json`) [V]; [I] these are pulled by billing and send Google event logs (Firelog/CCT) |
| play-services-base 18.10.0 (+ basement, tasks) | play | IPC to Google Play services |
| play-services-location 21.3.0 | play | only to override a transitive version (comment `lib/billing/build.gradle.kts:22-25`); not used in code |
| kotlinx-coroutines-play-services 1.11.0 | play | none |

grpc/protobuf entries in the lockfile come from Gradle plugin tooling [I].

### 5.4 Network-touching code paths

1. Rust core in `:vpnservice` [V]: Obscura API `https://v1.api.prod.obscura.net/api` (`rustlib/src/constants.rs:6-7`) with UA `obscura.net/android/<version>`; relays (QUIC or TCP-TLS); debug probes to Apple/Google (3.5). All of it leaves on the physical network, outside the tunnel, because the app UID is excluded [V config, I routing].
2. WebView [V]: no `fetch`/`XMLHttpRequest`/`WebSocket` in `obscura-ui/src`; external links go to the browser.
3. Play Billing / Play Services (play flavor only).
4. User-initiated share/email of debug bundles.

### 5.5 Permissions

Declared [V] (`app/src/main/AndroidManifest.xml:5-12`): `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `POST_NOTIFICATIONS`. Service requires `BIND_VPN_SERVICE`. `lib/billing` and `lib/util` manifests are empty. No location, storage, contacts, phone, or `QUERY_ALL_PACKAGES`.
`CHANGE_NETWORK_STATE` has no visible user (no `requestNetwork`, `bindProcessToNetwork`) [I: removable].

Merged from libraries [I, AAR manifests not read]:
- all flavors: `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature) from androidx.core; `androidx.startup.InitializationProvider` and `ProfileInstallReceiver` (guarded by `android.permission.DUMP`) from profileinstaller.
- play: `com.android.vending.BILLING`, `<queries>` for the Play billing service, `ProxyBillingActivity`/`ProxyBillingActivityV2`, `GoogleApiActivity`, meta-data `com.google.android.gms.version`, datatransport `JobInfoSchedulerService` (`BIND_JOB_SERVICE`), `AlarmManagerSchedulerBroadcastReceiver`, `TransportBackendDiscovery`.

---

## 6. Versions and build [V]

| Item | Value | Where |
|---|---|---|
| AGP | 8.13.0 (pinned by nixpkgs; lint check disabled) | `gradle/libs.versions.toml:3`, `lint.xml:3-4` |
| Gradle wrapper | 8.13 | `gradle/wrapper/gradle-wrapper.properties:4` |
| Kotlin | 2.3.10; KSP 2.3.6; JVM toolchain 21 | toml 13,16; `app/build.gradle.kts:72-75` |
| Dagger/Hilt | 2.58 ("Later versions require AGP 9+") | toml 8-9 |
| detekt / ktfmt | 1.23.8 (`maxIssues: 0`) / plugin 0.25.0 (indent 4, width 120) | toml 10,17; `detekt.yml:1-2`; `build.gradle.kts:20-37` |
| buildToolsVersion | 37.0.0 | `app/build.gradle.kts:12`, libs same |
| compileSdk / targetSdk / minSdk | 36 / 36 / 31 | `app/build.gradle.kts:15,19-20` |
| versionName / versionCode | env `OBSCURA_VERSION` or `v<tag.json version>.1-dev`; code = minor component | `buildSrc/src/main/kotlin/VersionName.kt:13-39` |
| NDK | 26.3.11579264 | `flake.nix:63-64` |
| Rust | 1.89.0, target `aarch64-linux-android` | `rustlib/rust-toolchain.toml:1-4` |
| ABI | `arm64-v8a` only (`cargo ndk -t arm64-v8a build --release --lib`) | `flake.nix:116,120` |
| .so packaging | `app/src/main/jniLibs/arm64-v8a/libobscuravpn_client.so` is a git symlink to `../../../../../../rustlib/target/aarch64-linux-android/release/libobscuravpn_client.so`; Nix replaces it with the built .so (`flake.nix:374`) and strips it with `llvm-strip` (132) | git index mode 120000 |
| Web assets | `app/src/main/assets/web` symlink to `obscura-ui/build` | `flake.nix:375` |
| 16 KB pages | `-Wl,-z,max-page-size=16384`, `-Wl,-z,common-page-size=16384` for Android targets | `rustlib/.cargo/config.toml:1-7` |
| APK alignment | no `packaging {}` / `useLegacyPackaging` / `extractNativeLibs`; [I] AGP default for minSdk >= 23 stores .so uncompressed and 16 KB aligned (AGP 8.5.1+) |
| Rust release profile | `codegen-units=1`, `lto=true`, `panic="abort"`, `debug="line-tables-only"`; crate-type `cdylib, lib, staticlib` | `rustlib/Cargo.toml:16-23` |
| Rust Android deps | `jni 0.21.1` (no default features), `cesu8`, `nix 0.30.1`, `tracing-android 0.2.0` | `rustlib/Cargo.toml:122-126` |
| R8 | release: `isMinifyEnabled=true`, `isShrinkResources=true`, `proguard-android-optimize.txt` + empty `proguard-rules.pro`; keeps rely on `@Keep` (`FfiHandle`, `ErrorCodeException`, the two `@JvmStatic` JNI callbacks) | `app/build.gradle.kts:46-57`; `proguard-rules.pro:1-21` |
| Reproducibility | `vcsInfo.include=false`; Nix gradle lockfile `gradle/mitm-cache/deps.json` | `app/build.gradle.kts:53-56` |
| Signing | none in Gradle. Nix passes `-Pobscura.unsigned=true` (debug `signingConfig = null`); release is unsigned; sign with `apksigner` | `app/build.gradle.kts:39-43`, `flake.nix:81-82`, `README.md:108-114` |
| Debug build | `applicationIdSuffix=".debug"`, app name "Obscura VPN (Debug)", no minify | `app/build.gradle.kts:33-44` |
| Outputs | `apks-foss`, `apks-play`, `aab-play-debug`, `aab-play-release` | `flake.nix:393-417` |

### 6.1 JNI contract (what a new client must keep to reuse `rustlib` unchanged) [V]

| Direction | Java/Kotlin side | JNI symbol / signature | Rust |
|---|---|---|---|
| K->R | `static native FfiHandle initialize(String configDir, String userAgent)` | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_initialize` | `ffi.rs:74-96` |
| K->R | `static native void jsonFfi(FfiHandle, String json, CompletableFuture<String>)` | `..._jsonFfi` | `ffi.rs:131-144` |
| K->R | `static native void setNetworkInterface(FfiHandle, String name, int index)` | `..._setNetworkInterface` | `ffi.rs:157-169` |
| K->R | `static native void unsetNetworkInterface(FfiHandle)` | `..._unsetNetworkInterface` | `ffi.rs:172-176` |
| K->R | `static native void forwardLog(int level, String tag, String message, String messageId, String throwableString)` (level 0..4 = TRACE..ERROR) | `..._forwardLog` | `ffi.rs:188-230` |
| K->R | `static native void flushAndStopPersistedLog()` | `..._flushAndStopPersistedLog` | `ffi.rs:179-183` |
| R->K | `ObscuraVpnService.ffiSetNetworkConfig(String): Int` (static) | `"(Ljava/lang/String;)I"` | `ffi.rs:241-245` |
| R->K | `ObscuraVpnService.ffiGetProcessExitReasons(): String?` (static) | `"()Ljava/lang/String;"` | `ffi.rs:272` |
| R->K | `new ObscuraLibrary$FfiHandle()` | `"()V"` | `ffi.rs:93` |
| R->K | `new ErrorCodeException(String)` | `"(Ljava/lang/String;)V"` | `future.rs:31-36` |
| R->K | `CompletableFuture.complete(Object)` / `completeExceptionally(Throwable)` | `"(Ljava/lang/Object;)Z"` / `"(Ljava/lang/Throwable;)Z"` | `future.rs:26,38-43` |

Classes looked up once in `ClassCache::new` (must run on a Java thread, it does, inside `initialize`) (`rustlib/src/android/class_cache.rs:18-26`):
`net/obscura/vpnclientapp/client/ObscuraLibrary$FfiHandle`, `net/obscura/vpnclientapp/client/ErrorCodeException`, `net/obscura/vpnclientapp/services/ObscuraVpnService`.

Consequences: keep these FQNs (or patch `class_cache.rs` and the `Java_...` names); R8 must not rename them. `applicationId` can differ, since JNI names come from class names [V JNI rule]. `initialize` is a `OnceLock`; a second call logs an error and reuses the first state (`ffi.rs:83-92`). Init failure panics, and with `panic = "abort"` the process dies (`ffi.rs:85-86`). Malformed JSON in `jsonFfi` throws `java.lang.RuntimeException` from JNI (`ffi.rs:140-143`). Rust installs `rustls` `aws_lc_rs` as crypto provider (`ffi.rs:54-56`) and logs to logcat tag `ObscuraNative` (`ffi.rs:53`).

---

## 7. Weak spots (what a better native client should improve)

### Performance
- **W1 Broadcast IPC per command** [V path, I cost]: every UI request = JS bridge thread -> main thread -> sync binder -> Rust -> JNI attach -> `CompletableFuture` -> `sendBroadcast` -> ActivityManager -> receiver on main thread (`ObscuraVpnService.kt:79-96`, `JsonFfiBroadcastReceiver.kt:20-48`). Costs: broadcast queue latency, the 1 MB binder limit for big results (`getExitList`, `getDebugInfo`), and a cold start of the main process if it is dead. Better: a `oneway` AIDL callback interface (or `Messenger`) registered at bind, mapped to Kotlin `Flow`.
- **W2 UI blocked on service bind** [V]: the WebView is created only in `onServiceConnected` (`MainActivity.kt:117-128`, `ObscuraUI.kt:113-189`). Cold start waits for the `:vpnservice` process, `.so` load, tokio runtime, config read, Chromium, and the React bundle. Better: Compose renders at once from a cached last status and binds in parallel.
- **W3 WebView cost** [V]: Chromium renderer, JS bundle, insets via `evaluateJavascript` (`ObscuraWebView.kt:109-123`), IME jank TODO (`ObscuraUI.kt:83-84`), WebView-driven `configChanges` (manifest 25-31).
- **W4 Sync binder on main thread** [V]: `WebCmdBridge` runs on `Dispatchers.Main.immediate` (`WebCmdBridge.kt:21`) and calls `binder.jsonFfi` there (`JsonFfiBroadcastReceiver.kt:31`).
- **W5 Data plane (Rust, outside the shell)** [V]: one syscall per packet, 1280-byte buffer (`tunnel.rs:29-35,56-69`), fixed MTU 1280, IPv4-only outer sockets (`net.rs:29`), two `establish()` per connect (`tunnel_state.rs:250-263,323-325`). The shell cannot change these without rustlib work.
- **W6 Log volume** [V]: INFO+ JSON logs up to 240 MB (`logging.rs:47-48`); Kotlin INFO logs from `:vpnservice` go there too; Kotlin logs also go to logcat twice (Kotlin `Log.*` plus `ObscuraNative`).
- **W7 Process kept alive while disconnected** [V code, I battery]: the foreground service and Rust runtime stay up after disconnect (`ObscuraVpnService.kt:324-333`).

### Robustness
- **R1 Observer leak and 100 Hz error loop** [V code, I runtime]: `ObscuraUI.onCreate` adds a new `VpnStatusObserver` (140-171) but `onDestroy` (199-208) never removes the old one. After a service reconnect, the old observer calls a dead binder, gets `DeadObjectException`, logs, waits 10 ms, and loops (`VpnStatusObserver.kt:30-55`).
- **R2 No timeouts** [V]: `timeoutMs` from the UI is dropped (`WebCmd.kt:124-132`); deferreds in the static `waiting` map never complete if `:vpnservice` dies.
- **R3 Notification updates can stop** [V]: `loadStatus` only re-arms on success (`ObscuraVpnService.kt:353-367`).
- **R4 No resume after process death without always-on** [V code, I runtime]: see 1.3 (`ffi.rs:67`, `client_state.rs:93-95`, `ObscuraVpnService.kt:276-284`).
- **R5 RuntimeException across AIDL** [I]: a JSON parse error throws `RuntimeException` inside the binder thread (`ffi.rs:140-143`); it is not a parcelable exception type, so it can crash `:vpnservice`.
- **R6 Network callback** [V]: never unregistered (`ObscuraVpnService.kt:204-220` vs 344-351); no `onLinkPropertiesChanged`/`onCapabilitiesChanged` handling (interface rename, MTU change).
- **R7 Underlying networks after re-establish** [I]: set only on network change (457-466), not on the Builder; each `establish()` starts with default tracking. Better: `Builder.setUnderlyingNetworks(arrayOf(current))` in `applyNetworkConfig`.
- **R8 Counter-based reachability** [V]: `NetworkStatusObserver` counts events instead of keeping a `Set<Network>` (20, 33-43).
- **R9 Small bugs** [V]: `debugBundle` returns `{}` not the path (`WebCmd.kt:69-96`); `inProgressCounter` never changes; debug bundles never deleted; stale AIDL comment.
- **R10 Init crash loop** [V code, I runtime]: config load failure -> panic -> abort -> `START_STICKY` restart.

### UX
- **U1** No Quick Settings tile, shortcuts, or widget [V].
- **U2** No always-on/lockdown status or guide; `isAlwaysOn()`/`isLockdownEnabled()` (API 29) unused [V]. Add a status row and a link to `Settings.ACTION_VPN_SETTINGS`.
- **U3** Notification shows only status text; no exit city, relay, transport (`quic`/`tcpTls`), traffic, or exit choice; stays visible when disconnected; no lock-screen visibility rule [V].
- **U4** Auto-connect only on app launch (`ObscuraUI.kt:144-167`) [V].
- **U5** DNS: "use system DNS" hidden on Android (`Settings.tsx:42-43`); Private DNS check covers only strict mode (`PrivateDnsObserver.kt:32-39`). Add `LinkProperties.isPrivateDnsActive()` and a link to the Private DNS setting [V for gaps].
- **U6** Local network access uses route splitting (`local_network.rs:40-60`) instead of `Builder.excludeRoute(IpPrefix)` (API 33). Only final routes cross the FFI, so a cleaner path needs either a rustlib change or Kotlin-side LAN handling [I].
- **U7** No split tunnelling (only own package excluded) [V].
- **U8** `launchMode="singleInstance"` (manifest 34) gives odd task/recents behaviour [I].
- **U9** Shell strings are English only (`res/values/strings.xml`) [V].
- **U10** Exit choice is country/city/exit id only; no entry relay choice at the shell level (`manager.rs:137-141`) [V].

### Privacy and security
- **P1 Backup** [V]: `allowBackup="true"` and only `rust-log` excluded, so `files/config.json` (account number, plaintext WG secret key) and shared prefs can go to cloud backup (E2E-capable devices only) and device transfer (`AndroidManifest.xml:16-17`, `data_extraction_rules.xml:5-16`). Use `allowBackup="false"` or exclude everything.
- **P2 Plaintext WG key** [V]: `WgKeyStore::Plaintext` (`ffi.rs:61-62`). Rust already has a `Keychain { secret_key, set_secret_key }` variant (`rustlib/src/wg_key_store/mod.rs:85-95`); wiring it to Android Keystore (AES-GCM wrap, StrongBox when present) needs an FFI change.
- **P3 Account number in logs** [V]: `log.info("status updated $status")` and `log.info("getStatus completed $data")` (`ObscuraVpnService.kt:325,363`) include `accountId` (`ManagerCmdOk.kt:19`, `manager.rs:59`). In `:vpnservice` these go to logcat and, through `forwardLog`, to the persisted `rust-log`, which the debug bundle includes together with `logcat -d` (`debug_bundle/mod.rs:160-167`). Rust itself redacts the id in `ConfigDebug` (`persistence.rs:346`). Main process traces every command incl. `login{accountId}` (`JsonFfiBroadcastReceiver.kt:29`, `VpnStatusObserver.kt:44`, `IntentExtension.kt:47`). The API crate says the account number must "almost never be displayed".
- **P4 Purchase token in logs** [V]: `PurchaseTokenUploader.kt:30`, `BillingConnection.kt:24`, `BillingManager.kt:59`.
- **P5 Debug probes** [V]: contact Apple and Google from the excluded UID (`client_state.rs:856-901`).
- **P6 API traffic outside the tunnel** [V config, I routing]: Obscura API sees the real IP even while connected.
- **P7 JS bridge surface** [V exposure, I risk]: `addJavascriptInterface` exposes the full `ManagerCmd` set (incl. `apiDeleteAccount`, `setApiUrl`, `terminateProcess`) to every frame. Only local assets load today. A Compose UI removes this surface.
- **P8 Browsable deep link** [V]: any web page can open `obscuravpn:///requestVpnStart` (manifest 42-48, `ObscuraUI.kt:233-240`).
- **P9** No `FLAG_SECURE` / `setRecentsScreenshotEnabled(false)` for account screens [V absent].
- **P10** Release keeps `Log.v/d` (no `-assumenosideeffects`) [V].
- **Keep (good)** [V]: MTE `memtagMode` async/sync; WebView metrics opt-out; non-exported service/receiver/provider; FileProvider scoped to `cache/debug-archives`; immutable PendingIntents; `setLocalOnly(true)`; rustls + aws-lc-rs; dummy TUN while connecting (no leak during reconnect); reproducible Nix builds; FOSS flavor free of Google libraries.

### Build
- **B1** AGP held at 8.13 by nixpkgs; Hilt held at 2.58 [V].
- **B2** arm64-v8a only; no x86_64 for x86 emulators [V].
- **B3** jniLibs and web assets are symlinks to build outputs [V].
- **B4** NDK r26 needs the explicit 16 KB flags; keep them or use a newer NDK [V flags, I NDK default].

### Suggested baseline for the new client (recommendations, not upstream facts)
- Keep the two-process split (tunnel survives UI crashes) but replace broadcasts with a `oneway` AIDL callback; render Compose UI at once from cached state.
- Keep the exact JNI class names from 6.1 (or patch rustlib once) and the `@Keep`/`@JvmStatic` contract.
- Builder: add `setUnderlyingNetworks`, `setSession`, `setConfigureIntent`, optional per-app `addDisallowedApplication` for split tunnelling; never `allowBypass`.
- Add `TileService`, lockdown/always-on status, Private DNS (strict + opportunistic) detection, a rich notification (exit, transport, data) that is removed when disconnected, and `VISIBILITY_PRIVATE`.
- `allowBackup="false"`, Keystore-wrapped WG key, no account id or tokens in logs, `FLAG_SECURE` on account screens, a debug-bundle consent screen that lists the probes and contents, and auto-delete of old bundles.
- FOSS-only build (no `lib/billing`, no `google-services.json`, drop `CHANGE_NETWORK_STATE` if unused).
