# Android platform research for a top-tier VPN client (key: android-platform)

Date of research: 2026-10-20. Target: compileSdk/targetSdk 37 (Android 17), minSdk 35 or 36.

Method: official pages from developer.android.com, android-developers.googleblog.com, kotlinlang.org, maven.google.com / Maven Central metadata, and AOSP source at tag `android-17.0.0_r1` (gitiles, append `?format=TEXT` and base64-decode to reproduce the line numbers).

Legend used in this report:

- **[V]** verified in an official Google doc page (URL given).
- **[V-AOSP]** verified in AOSP source at tag `android-17.0.0_r1` (path and line numbers given).
- **[V-MVN]** verified in Maven metadata (maven.google.com or repo1.maven.org), fetched 2026-10-20.
- **[3P]** third-party source only (press, forum, vendor). Treat as a hint.
- **[I]** my inference or recommendation. Not a documented fact.

Facts about Android 17: stable release 2026-06-16, API level 37, source pushed to AOSP the same day [V] (https://android-developers.googleblog.com/2026/06/Android-17.html). SDK repository lists `platforms;android-37.0`, `android-37.1`, `android-37.2` and `build-tools;37.0.0` [V] (https://dl.google.com/android/repository/repository2-3.xml). 37.1 = QPR1 minor SDK, 37.2 = QPR2 minor SDK (QPR2 Beta 7.1 in Oct 2026) [3P summary of https://developer.android.com/about/versions/17/qpr2/release-notes].

---

## 0. Key findings (read this first)

1. **No VpnService / VpnService.Builder API changed in API 35, 36, 37, 37.1, 37.2.** [V] API diffs for `android.net` list only IpSecTransformState, NetworkCapabilities, NetworkRequest (35); L2capNetworkSpecifier, TetheringInterface, ConnectivityManager.reserveNetwork (36); DnsResolver HTTPS queries, NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS (37). (https://developer.android.com/sdk/api_diff/35/changes/pkg_android.net.html, .../36/..., .../37/...). The Android 15, 16, 17 behavior-change pages have zero VPN-specific entries [V].
2. **Android 17 `Settings.ACTION_VPN_APP_EXCLUSION_SETTINGS` does NOT work for a VpnService VPN.** [V] Javadoc says it configures exclusions for "the calling package's **VpnManager** VPN" (https://developer.android.com/reference/android/provider/Settings#ACTION_VPN_APP_EXCLUSION_SETTINGS). [V-AOSP] `Vpn.java` applies the exclusion list only to the platform IKEv2 VPN: line 4482 (`config.disallowedApplications = getAppExclusionList(mPackage);` inside the platform-profile start path) and lines 4637-4652 (`updateAppExclusionList` only acts `if (isIkev2VpnRunner())`). `VpnManagerService.establishVpn` (lines 273-278) passes the VpnService config through unchanged. The Settings screen (`packages/apps/Settings` `AndroidManifest.xml` lines 1030-1041, `vpn2/AppExclusionActivity`) opens for any caller and stores a list through `VpnManager`, so a VpnService app would show the user a screen that has **no effect**. The press (Android Authority, PhoneArena, Gadget Hacks) describe it as a general split-tunnel feature [3P]; that is wrong for VpnService apps. **Our app must keep its own split-tunnel UI using `addDisallowedApplication` / `addAllowedApplication`.**
3. **Foreground service type: use `systemExempted`.** [V-AOSP] `core/java/android/app/ForegroundServiceTypePolicy.java` lines 555-571: `systemExempted` needs `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` plus any-of {`SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, app-op `OP_ACTIVATE_VPN`}. `OP_ACTIVATE_VPN` is the user's VPN consent from `VpnService.prepare()`. [V] doc lists "VPN apps (configured using Settings > Network & Internet > VPN)" as eligible (https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted). Upstream Obscura already does this (`/tmp/obscura-research/obscuravpn-client/android/app/src/main/AndroidManifest.xml` lines 11, 61-72).
4. **The system binds an established VpnService with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE`** [V-AOSP] `Vpn.java` lines 1813-1814. The process gets BOUND_FOREGROUND_SERVICE state, and [V-AOSP] `NetworkPolicyManager.java` lines 192-193 and 836-843 allow network for procState <= `PROCESS_STATE_BOUND_FOREGROUND_SERVICE` in Doze / battery saver. So the tunnel keeps network in Doze once established. Still call `startForeground` yourself (docs require a notification; covers the connecting phase).
5. **io_uring is blocked for apps by seccomp: probing it kills the process with SIGSYS.** [V] Google Security Blog 2023-06: "Our seccomp-bpf filter ensures that io_uring is unreachable to apps" (https://security.googleblog.com/2023/06/learnings-from-kctf-vrps-42-linux.html). The Rust core must use epoll (tokio default) and must not compile in any io_uring backend for Android.
6. **Material 3 Expressive needs `material3` 1.5.0-beta01.** [V] Release notes: in 1.4.0-beta01 "All public APIs tagged with ExperimentalMaterial3ExpressiveApi ... have been removed, please switch to 1.5.0-alpha" (https://developer.android.com/jetpack/androidx/releases/compose-material3). Compose BOM 2026.09.00 maps material3 to **1.4.0** [V-MVN], so you must pin 1.5.0-beta01 explicitly.
7. **16 KB pages:** Play blocks updates that lack 16 KB support starting **2027-02-01** for apps targeting 35+ [V] (https://developer.android.com/guide/practices/page-sizes). NDK r28+ aligns to 16 KB by default [V]; Rust's `aarch64-linux-android` target spec sets no page-size flag (checked with local rustc 1.98.1), so alignment comes from the NDK clang linker [V+I]. Verify with `llvm-readelf`.
8. **Android 17 (target 37) changes that touch us:** CT on by default, ECH on by default (both Java/Conscrypt stack only), `ACCESS_LOCAL_NETWORK` runtime permission (does not apply to VPN interfaces), System.load() of writable native files fails, JNI writes to `static final` fields crash, lock-free MessageQueue, app memory limits, `setContentCaptureEnabled(false)` no longer works (use FLAG_SECURE), RemoteViews bitmap memory cap for widgets [V] (https://developer.android.com/about/versions/17/behavior-changes-17, https://developer.android.com/about/versions/17/behavior-changes-all).

---

## 1. VpnService and VpnService.Builder

### 1.1 VpnService public API (all verified [V] at https://developer.android.com/reference/android/net/VpnService, page updated 2026-09-16)

| Member | Exact signature | API |
|---|---|---|
| `SERVICE_INTERFACE` | `public static final String SERVICE_INTERFACE = "android.net.VpnService"` | 14 |
| `SERVICE_META_DATA_SUPPORTS_ALWAYS_ON` | `public static final String SERVICE_META_DATA_SUPPORTS_ALWAYS_ON = "android.net.VpnService.SUPPORTS_ALWAYS_ON"` (defaults to true if absent; effect on O_MR1+) | 27 |
| `prepare` | `public static Intent prepare(Context context)` | 14 |
| `protect` | `public boolean protect(int socket)` / `protect(Socket)` / `protect(DatagramSocket)` | 14 |
| `setUnderlyingNetworks` | `public boolean setUnderlyingNetworks(Network[] networks)` (only succeeds while established) | 22 |
| `isAlwaysOn` | `public final boolean isAlwaysOn()` | **29** |
| `isLockdownEnabled` | `public final boolean isLockdownEnabled()` | **29** |
| `onRevoke` | `public void onRevoke()` (may run off main thread; default calls `stopSelf()`) | 14 |
| `onBind` | `public IBinder onBind(Intent intent)` (returns null for non-SERVICE_INTERFACE intents) | 14 |

Note: the VPN guide (https://developer.android.com/develop/connectivity/vpn) still says "Android doesn't include APIs to confirm whether the system started your VPN service". That text is stale; `isAlwaysOn()` / `isLockdownEnabled()` exist since API 29 [V].

`setUnderlyingNetworks` semantics [V]: non-empty array = networks in preference order; empty array = no underlying network (no traffic); `null` (default) = system default network. "This method only needs to be called if the VPN has explicitly bound its underlying communications channels ... to a Network using APIs such as Network.bindSocket ... The VPN should call this method every time the set of Networks it is using changes."

### 1.2 VpnService.Builder public API ([V] https://developer.android.com/reference/android/net/VpnService.Builder, updated 2026-08-03; cross-checked with AOSP `core/java/android/net/VpnService.java` at android-17.0.0_r1)

| Method | Exact signature | API | Notes [V] |
|---|---|---|---|
| ctor | `public Builder()` | 14 | |
| addAddress | `public VpnService.Builder addAddress(InetAddress address, int prefixLength)` and `(String address, int prefixLength)` | 14 | at least one required; adding implicitly allows that family |
| addRoute | `public VpnService.Builder addRoute(IpPrefix prefix)` | 33 | overrides earlier `excludeRoute` for same destination; longest prefix wins |
| addRoute | `addRoute(InetAddress address, int prefixLength)`, `addRoute(String address, int prefixLength)` | 14 | |
| excludeRoute | `public VpnService.Builder excludeRoute(IpPrefix prefix)` | 33 | only `IpPrefix` overload exists |
| addDnsServer | `public VpnService.Builder addDnsServer(InetAddress address)` and `(String address)` | 14 | "If none is set, the DNS servers of the default network will be used" |
| addSearchDomain | `public VpnService.Builder addSearchDomain(String domain)` | 14 | |
| allowFamily | `public VpnService.Builder allowFamily(int family)` (`AF_INET` / `AF_INET6`) | 21 | "By default, if no address, route or DNS server of a specific family ... is added ... all outgoing traffic of that family is blocked." |
| allowBypass | `public VpnService.Builder allowBypass()` | 21 | lets apps use `bindProcessToNetwork` to escape the VPN |
| setBlocking | `public VpnService.Builder setBlocking(boolean blocking)` | 21 | fd is non-blocking by default |
| setConfigureIntent | `public VpnService.Builder setConfigureIntent(PendingIntent intent)` | 14 | shows "configure" button in system VPN dialog |
| setHttpProxy | `public VpnService.Builder setHttpProxy(ProxyInfo proxyInfo)` | 29 | recommendation only; PAC unsupported |
| setMetered | `public VpnService.Builder setMetered(boolean isMetered)` | 29 | "VPN apps targeting Q or above will be considered metered by default." `false` = inherit from underlying networks |
| setMtu | `public VpnService.Builder setMtu(int mtu)` | 14 | |
| setSession | `public VpnService.Builder setSession(String session)` | 14 | shown in system dialogs/notifications |
| setUnderlyingNetworks | `public VpnService.Builder setUnderlyingNetworks(Network[] networks)` | 22 | |
| addAllowedApplication | `public VpnService.Builder addAllowedApplication(String packageName) throws PackageManager.NameNotFoundException` | 21 | allow-list and deny-list are mutually exclusive (`UnsupportedOperationException`) |
| addDisallowedApplication | `public VpnService.Builder addDisallowedApplication(String packageName) throws PackageManager.NameNotFoundException` | 21 | |
| establish | `public ParcelFileDescriptor establish()` | 14 | returns null if not prepared/revoked; throws IAE/ISE/SecurityException |

The task brief said `excludeRoute` is API 33, `setHttpProxy` 29: confirmed. No new Builder methods in 35-37.2 [V].

Hidden/system-only knobs (not usable by us) [V-AOSP] `core/java/com/android/internal/net/VpnConfig.java` lines 106-109: `isMetered = true`, `requiresInternetValidation = false`, `excludeLocalRoutes = false`. So a VpnService cannot set "exclude local routes"; use `excludeRoute(IpPrefix)` for LAN access.

AOSP detail [V-AOSP] `VpnService.java` lines 781-789: `verifyApp()` calls `IPackageManager.getApplicationInfo(...)` and does not check for a null result, so package-visibility filtering does not make `addDisallowedApplication` throw. [I] You still need package visibility to list apps in the UI (see 6.10).

### 1.3 System behaviour verified in AOSP (android-17.0.0_r1, `services/core/java/com/android/server/connectivity/Vpn.java`)

- Always-on start: system adds the VPN app to the power-save temp allowlist for `VPN_LAUNCH_IDLE_ALLOWLIST_DURATION_MS = 60 * 1000` (line 206) with `REASON_VPN` (lines 1188-1193), then calls `startService(new Intent(VpnConfig.SERVICE_INTERFACE).setPackage(pkg))` (lines 1196-1199). [I] So in `onStartCommand`, `intent?.action == VpnService.SERVICE_INTERFACE` means "started by system (always-on)". Combine with `isAlwaysOn()`.
- Always-on support check: app must target >= 24 and not opt out via meta-data (lines 830-870).
- `establish()`: checks caller is prepared and consent not revoked (`isVpnServicePreConsented`), resolves the service by class name and requires `android:permission="android.permission.BIND_VPN_SERVICE"` else `SecurityException` (lines 1757-1786), then binds with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE` (lines 1813-1814).
- Lockdown and bypass: `final boolean bypassable = mConfig.allowBypass && !mLockdown;` (line 1643): `allowBypass()` is ignored in lockdown.
- Lockdown exemptions: only the VPN app itself and the DPC `lockdownAllowlist` are exempt (`setVpnForcedLocked`, lines 2118-2125). [I] Consequence: apps excluded through `addDisallowedApplication` get **no network** while "Block connections without VPN" is on, because their traffic would leave outside the VPN. Same likely for `excludeRoute` LAN ranges. Malwarebytes documents "Block connections without VPN must be off to use Split Tunneling" [3P] (https://help.malwarebytes.com/hc/en-us/articles/31589640441883). Test on device and warn the user when `isLockdownEnabled()` and split tunnelling / LAN access are both on.
- Exclusion list from Android 17 Settings: platform VPN only (see finding 2).

### 1.4 Always-on VPN and "Block connections without VPN" (lockdown)

[V] https://developer.android.com/develop/connectivity/vpn:
- Always-on available since Android 7.0 (API 24); lockdown switch in Settings > Network & Internet > VPN.
- When always-on, "Disable any UI that disconnects the connection because the system and Settings app control the connection" and "Save any config between each app start".
- Opt-out (we should NOT opt out): `<meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="false"/>`. Default is supported.
- Deep link for users to enable it: `Settings.ACTION_VPN_SETTINGS` = `"android.settings.VPN_SETTINGS"` (API 24) [V] (https://developer.android.com/reference/android/provider/Settings#ACTION_VPN_SETTINGS). Apps cannot enable always-on/lockdown themselves (only DPC via `DevicePolicyManager.setAlwaysOnVpnPackage`).
- Captive portals: with lockdown on, portal sign-in pages usually fail; Android's own connectivity checks go outside the VPN even in lockdown [3P] (https://www.bigiron.cc/guides/android-always-on-vpn-and-block-connections-without-vpn-what-still-leaks, https://mullvad.net/en/help/configure-connectivity-checks-on-android). [I] Detect `NET_CAPABILITY_CAPTIVE_PORTAL` on the underlying Wi-Fi and show guidance.

### 1.5 Android 15 / 16 / 17 changes that matter for a VPN app

| Version | Change | Scope | Source |
|---|---|---|---|
| 15 (35) | Edge-to-edge enforced for target 35 | target | [V] https://developer.android.com/about/versions/15/behavior-changes-15 |
| 15 | BOOT_COMPLETED receivers cannot start FGS of types dataSync, camera, mediaPlayback, phoneCall, mediaProjection, microphone (systemExempted not listed) | target 35 | [V] same page |
| 15 | SYSTEM_ALERT_WINDOW FGS-start exemption needs a visible overlay | target 35 | [V] same page |
| 15 | TLS 1.0/1.1 disallowed | target 35 | [V] same page |
| 15 | Background network access outside a valid process lifecycle fails (UnknownHostException etc.) | all apps | [V] https://developer.android.com/about/versions/15/behavior-changes-all |
| 15 | Force-stop cancels all PendingIntents and disables widgets until next launch; `ApplicationStartInfo.wasForceStopped()` | all apps | [V] same page |
| 15 | 16 KB page-size devices | all apps | [V] same page |
| 15 | `NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK`, `TRANSPORT_SATELLITE` | API | [V] api_diff 35 |
| 16 (36) | `windowOptOutEdgeToEdgeEnforcement` disabled | target 36 | [V] https://developer.android.com/about/versions/16/behavior-changes-16 |
| 16 | Predictive back on by default; `onBackPressed` not called, `KEYCODE_BACK` not dispatched | target 36 | [V] same page |
| 16 | Safer Intents opt-in `android:intentMatchingFlags` | API 36 | [V] same page |
| 16 | Orientation/resizability ignored on sw>=600dp (opt-out removed at 37) | target 36 | [V] same page |
| 16 | Intent redirection hardening, `removeLaunchSecurityProtection()` | all apps | [V] https://developer.android.com/about/versions/16/behavior-changes-all |
| 16 | Local network protection opt-in (adb `RESTRICT_LOCAL_NETWORK`) | opt-in | [V] https://developer.android.com/privacy-and-security/local-network-permission |
| 16 | CT available, opt-in | API 36 | [V] https://developer.android.com/privacy-and-security/security-config |
| 16 | `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED` (satellite) | API | [V] api_diff 36 |
| 16 | Advanced Protection Mode API (`AdvancedProtectionManager`, API 36) | API | [V] https://developer.android.com/privacy-and-security/advanced-protection-mode |
| 16 QPR2 | Auto-themed app icons if no monochrome layer | all apps | [V] behavior-changes-all (16) |
| 16 | Known bug: VPN app stuck "connecting" after Play update while VPN active / always-on, Google acknowledged, no fix as of Mar 2026 | platform bug | [3P] https://www.androidauthority.com/android-16-vpn-bug-3650394/ |
| 16 | `registerQuicConnectionClosePayload` leak outside VPN even in lockdown, Google "won't fix" | platform | [3P] https://www.forbes.com/sites/daveywinder/2026/05/15/wont-fix-all-vpn-apps-affected-as-google-android-16-leaks-info/ |
| 17 (37) | `ACCESS_LOCAL_NETWORK` dangerous runtime permission, enforced for target 37 | target 37 | [V] behavior-changes-17 |
| 17 | CT enabled by default | target 37 | [V] behavior-changes-17, security-config |
| 17 | ECH enabled by default (`<domainEncryption>`) | target 37 | [V] same |
| 17 | `System.load()` native files must be read-only, else `UnsatisfiedLinkError` | target 37 | [V] behavior-changes-17 |
| 17 | Static final fields unmodifiable; JNI `SetStatic*Field` on them crashes | target 37 | [V] behavior-changes-17 |
| 17 | Lock-free `MessageQueue` (breaks reflection on internals) | target 37 | [V] behavior-changes-17 |
| 17 | `setContentCaptureEnabled(false)` no longer disables Content Capture; use FLAG_SECURE | target 37 | [V] behavior-changes-17 |
| 17 | Widget RemoteViews bitmap memory cap (1.5 x w x h x 4) else crash | target 37 | [V] behavior-changes-17 |
| 17 | Background audio hardening | target 37 | [V] behavior-changes-17 |
| 17 | App memory limits (`MemoryLimiter:AnonSwap` in `ApplicationExitInfo`) | all apps | [V] behavior-changes-all (17) |
| 17 | Per-app Keystore key limit 50,000 (target 37) | target 37 | [V] behavior-changes-all (17) |
| 17 | Cross-profile loopback traffic blocked | all apps | [V] behavior-changes-all (17) |
| 17 | `usesCleartextTraffic` deprecation plan ("ignored for apps targeting API levels 38 and above") | future | [V] https://developer.android.com/guide/topics/manifest/application-element |
| 17 | `Settings.ACTION_VPN_APP_EXCLUSION_SETTINGS` (VpnManager VPNs only) | API 37 | [V] + [V-AOSP], see finding 2 |
| 17 | `Notification.MetricStyle`, `Notification.Metric`, semantic colour annotations | API 37 | [V] https://developer.android.com/reference/android/app/Notification.MetricStyle |
| 17 | AAPM gains more features; ML-DSA keys in Keystore; v3.2 hybrid PQC APK signing | API 37 | [V] https://developer.android.com/about/versions/17/features |
| 17 | "Compose-first": View-based UI libraries in maintenance mode | ecosystem | [V] Android 17 blog |
| 17.2 | `NetworkSecurityPolicy.DOMAIN_ENCRYPTION_MODE_REQUIRED` added, `..._OPPORTUNISTIC` deprecated | API 37.2 | [V] https://developer.android.com/sdk/api_diff/37.2/changes/android.security.NetworkSecurityPolicy.html |

### 1.6 Local network permission (Android 16 opt-in, Android 17 enforced)

[V] `Manifest.permission.ACCESS_LOCAL_NETWORK` (API 37, protection level dangerous): "A local network refers to an IP network that utilizes a broadcast-capable network interface, such as Wi-Fi or Ethernet, but excludes cellular (WWAN) or VPN connections." (https://developer.android.com/reference/android/Manifest.permission#ACCESS_LOCAL_NETWORK). Port-53 traffic to a LAN DNS server is exempt [V].
[I] Our tunnel sockets go to public relay IPs, so we do not need `ACCESS_LOCAL_NETWORK`. Do not declare it unless a feature talks to LAN hosts from our own UID (for example a LAN DNS fallback outside the tunnel). Other apps' LAN access under our "allow LAN" option is governed by their own permission, not ours.
Android 16 only used `NEARBY_WIFI_DEVICES` for the opt-in test mode; do not request it.

### 1.7 Private DNS interaction

[V] `LinkProperties.isPrivateDnsActive()` (API 28), `LinkProperties.getPrivateDnsServerName()` (API 28) (https://developer.android.com/reference/android/net/LinkProperties).
[3P] With Private DNS in strict (hostname) mode, Android sends DoT to that hostname (through the tunnel) and ignores the VPN's `addDnsServer` resolver; opportunistic mode tries DoT on port 853 of the VPN DNS IP and falls back to port 53 (https://github.com/celzero/brave-android-app/issues/25, https://github.com/akane599/blockads-android/pull/2).
[I] Recommendations: read `LinkProperties` of the VPN network (`ConnectivityManager.getLinkProperties(vpnNetwork)`) after establish; if `privateDnsServerName != null`, show a warning "Private DNS hostname overrides Obscura DNS"; never block TCP/853 inside the tunnel; make the in-tunnel resolver answer DoT on 853 if the Rust core supports it, so "Automatic" mode stays encrypted end-to-end.

### 1.8 Underlying network handling and leak prevention

- [V] `ConnectivityManager.registerBestMatchingNetworkCallback(NetworkRequest, NetworkCallback, Handler)` API 31; `registerDefaultNetworkCallback` API 24 "may be ... a VPN that applies to the application" (https://developer.android.com/reference/android/net/ConnectivityManager).
- [V] `Network.bindSocket(FileDescriptor)` API 23; NDK `android_setsocknetwork(net_handle_t network, int fd)` API 23, `android_getaddrinfofornetwork` API 23, `android_res_nquery` API 29, `android_getnetworkblockedreason(int sockfd)` (https://developer.android.com/ndk/reference/group/networking).
- [I] Because our own UID is inside our VPN, a default-network callback in the VPN process will report the VPN itself. Track the physical network with a request that has `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_VPN` and `registerBestMatchingNetworkCallback`. On change: rebind/rehandshake the tunnel socket and call `setUnderlyingNetworks(arrayOf(net))`.
- [I] Call `protect(fd)` on every outer socket (Rust can call back into Kotlin via JNI, or Kotlin creates sockets and passes fds). Optionally also bind to the chosen `Network` (`android_setsocknetwork` with `Network.getNetworkHandle()`), then call `setUnderlyingNetworks` so the status bar icon and meteredness are correct.
- [V] IPv6 leak protection is the default: if the tunnel has no IPv6, do not call `allowFamily(AF_INET6)`; v6 is then blocked. [I] Better: always add an IPv6 address (ULA if needed) and `::/0` so v6 goes into the tunnel and is answered/blocked there.
- [I] Never call `allowBypass()`, `addSearchDomain()`, `setHttpProxy()` in the privacy profile.
- [I] Call `setMetered(false)` so the VPN inherits meteredness (default is metered=true for target >= 29 [V]), otherwise Play, backups, and sync on Wi-Fi treat the VPN as metered.
- [I] Set `setSession()` to a neutral string ("Obscura") and `setConfigureIntent()` to a `FLAG_IMMUTABLE` PendingIntent of the main activity.
- [V] Satellite/constrained networks: apps must opt in to use them; doc does not mention VPNs (https://developer.android.com/develop/connectivity/satellite/constrained-networks). [I] Not a target.
- Network slicing (`NET_CAPABILITY_PRIORITIZE_LATENCY/BANDWIDTH`, API 33) is carrier-gated; [I] not applicable to a VPN.

### 1.9 Battery and background

- [V-AOSP] VPN apps with consent get a background-restriction exemption: `AppRestrictionController.java` lines 2973-2981 return `REASON_OP_ACTIVATE_VPN` when `OP_ACTIVATE_VPN` is allowed.
- [V-AOSP] The Task Manager (Active apps) still shows a normal **Stop** button for VPN apps: `packages/SystemUI/src/com/android/systemui/qs/FgsManagerController.kt` lines 738-765 (only system/DPC/dialer etc. hide the button). [V] Stopping kills the whole app with no callback; check `ApplicationExitInfo.REASON_USER_REQUESTED` on next start (https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).
- [I] Do not ask for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the exemptions above make it unnecessary. Do not declare `RECEIVE_BOOT_COMPLETED`; "connect on boot" = always-on VPN.

---

## 2. Foreground service and notification

### 2.1 Type and manifest

[V] Target 34+: every FGS must declare a type, else `MissingForegroundServiceTypeException`; must hold the type permission, else `SecurityException` (https://developer.android.com/develop/background-work/services/fgs/changes). `systemExempted` rules: see finding 3.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".vpn.ObscuraVpnService"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="systemExempted"
    android:process=":vpn"
    tools:ignore="ForegroundServicePermission,VpnServicePolicy">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

- `exported="false"` works because system_server binds/starts it [I + upstream precedent at `/tmp/obscura-research/obscuravpn-client/android/app/src/main/AndroidManifest.xml:61-72`]. `BIND_VPN_SERVICE` is protection level `signature` [V].
- Start: `startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)` (platform overload API 29). Call it only after `VpnService.prepare(ctx) == null`; otherwise the policy check fails [V-AOSP]. If consent was revoked while the app was closed, catch the exception, post a "Tap to re-authorise" notification, `stopSelf()`.
- [V] `Notification.Builder.setForegroundServiceBehavior(int)` API 31: use `Notification.FOREGROUND_SERVICE_IMMEDIATE` so "Connecting..." appears without the default deferral.
- [V] Android 14+: users can swipe away FGS notifications set with `setOngoing(true)` (still not dismissible on lock screen / "Clear all") (https://developer.android.com/about/versions/14/behavior-changes-all). [I] Re-post on state change; do not fight dismissal.
- [V] `POST_NOTIFICATIONS` is not needed to start an FGS; if denied the FGS notice shows only in Task Manager (https://developer.android.com/develop/ui/views/notifications/notification-permission). [I] Ask for it in context (first connect), not at launch.
- [V] Docs still require "A non-dismissible notification when the service is active ... Remove the notification after the service becomes inactive" (VPN guide).
- [I] Privacy: `setVisibility(Notification.VISIBILITY_PRIVATE)` + `setPublicVersion(...)` that says only "VPN connected" (no exit city/IP) on the lock screen; `setCategory(Notification.CATEGORY_SERVICE)`; channel importance LOW (silent), never MIN.
- [I] `onStartCommand`: return `START_NOT_STICKY` for user-started sessions unless "auto-reconnect after crash" is a feature; always-on restarts are done by the system.
- [I] Separate process `:vpn` (as upstream): the VPN survives UI crashes and the UI process can be reclaimed. IPC via a bound service (AIDL or Messenger) or a small Unix socket. Watch Android 17 memory limits in both processes.

### 2.2 FGS start from background

[V] Exemptions include "user performs an action on a UI element related to your app (bubble, notification, widget, activity)" (https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
[V-AOSP] QS tile clicks: SystemUI adds the tile's package to the temp allowlist for 15 s with `REASON_TILE_ONCLICK` before `onClick` (`packages/SystemUI/src/com/android/systemui/qs/external/TileLifecycleManager.java` lines 99, 637-641). So starting the VPN FGS from `TileService.onClick()` is allowed.
[V-AOSP] Always-on: 60 s allowlist (section 1.3).

### 2.3 Live Updates / progress-centric notifications

[V] https://developer.android.com/develop/ui/views/notifications/live-update requirements: style must be Standard, BigTextStyle, CallStyle, ProgressStyle or MetricStyle; manifest `android.permission.POST_PROMOTED_NOTIFICATIONS` (added in 36.1, protection `normal|appops`); request via `EXTRA_REQUEST_PROMOTED_ONGOING` or `setRequestPromotedOngoing(true)` (added 36.1); must be ongoing; must have contentTitle; no custom RemoteViews; not a group summary; not `setColorized(true)`; channel not `IMPORTANCE_MIN`. Check `NotificationManager.canPostPromotedNotifications()` (API 36), `Notification.hasPromotableCharacteristics()`, `Notification.FLAG_PROMOTED_ONGOING`; settings intent `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`. OEMs may add criteria.
Usage criteria [V]: ongoing, user-initiated, time-sensitive; "Don't use Live Updates to offer accelerated access to app functionality ... use an app widget or a custom Quick Settings tile." Inappropriate: ads, alerts, "quick access to app features".
Other APIs [V]: `Notification.ProgressStyle` (API 36), `Notification.Builder.setShortCriticalText` (API 36, status chip text, max chip width 96 dp, text shown if < 7 chars), `Notification.MetricStyle` (API 37, up to 3 metrics, e.g. `Metric.TimeDifference.forStopwatch(startTime, FORMAT_CHRONOMETER_AUTOMATIC)`), semantic colours `Notification.createSemanticStyleAnnotation(SEMANTIC_STYLE_SAFE | _CAUTION | _DANGER | _INFO)` (API 37).
[I] Recommendation: a steady "Connected" VPN is ambient, which the guidelines discourage, and the system already shows the VPN key icon. Do not request promotion by default. Offer an opt-in setting "Show VPN status chip". On API 37 use `MetricStyle` for the expanded notification (elapsed time stopwatch, exit location `FixedText`, optional throughput), with SAFE colour when connected, CAUTION when reconnecting, DANGER when traffic is blocked. Fallback on 35/36: BigTextStyle.

---

## 3. System UI integration and design

### 3.1 Quick Settings tile ([V] https://developer.android.com/develop/ui/views/quicksettings-tiles, https://developer.android.com/reference/android/service/quicksettings/TileService)

```xml
<service
    android:name=".tile.VpnTileService"
    android:exported="true"
    android:icon="@drawable/ic_tile_shield"
    android:label="@string/tile_label"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
    <meta-data android:name="android.service.quicksettings.ACTIVE_TILE" android:value="true" />
    <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true" />
    <meta-data android:name="android.service.quicksettings.TILE_CATEGORY"
        android:value="android.service.quicksettings.CATEGORY_CONNECTIVITY" />
</service>
```

- `META_DATA_ACTIVE_TILE` (API 24), `META_DATA_TOGGLEABLE_TILE` (API 30), `META_DATA_TILE_CATEGORY` + `CATEGORY_CONNECTIVITY/PRIVACY/...` ("Added in version 36.1"; string meta-data is harmless on older versions [I]). Label max 18 chars; icon 24 dp white vector [V].
- Active mode: push updates with `TileService.requestListeningState(Context, ComponentName)` (API 24; throws on bad component for target 33+). In active mode you can update the tile exactly once per listening window [V].
- `startActivityAndCollapse(Intent)` deprecated in 34 and throws `UnsupportedOperationException` on 34+; use `startActivityAndCollapse(PendingIntent)` (API 34) [V]. [I] When `VpnService.prepare()` != null, launch the consent activity this way with `FLAG_IMMUTABLE`.
- `isSecure()`, `isLocked()`, `unlockAndRun(Runnable)`, `showDialog(Dialog)` (API 24) [V]. [I] Privacy-expert option: require unlock (`unlockAndRun`) to **disconnect** from a locked device; connecting can be allowed while locked.
- `StatusBarManager.requestAddTileService(...)` (API 33) to prompt adding the tile, in context only [V].
- `ACTION_QS_TILE_PREFERENCES` activity for long-press settings [V].
- [I] Put the TileService in the same process as the VPN state holder to avoid waking the UI process.

### 3.2 App shortcuts ([V] https://developer.android.com/develop/ui/views/launch/shortcuts)

Static shortcuts in `res/xml/shortcuts.xml` attached to the launcher activity ("Connect", "Disconnect", "Change location"). Launchers show about 4. "The launcher itself can access this data. Therefore, conceal sensitive user information in this metadata" [V]. [I] Do not use the Google Shortcuts Integration Library (GMS). Do not put the last exit location into dynamic shortcut labels.

### 3.3 Widgets (Glance)

Glance `glance-appwidget` 1.2.0 stable [V-MVN]; 1.2.0 adds generated previews (`GlanceAppWidget.providePreview` + `GlanceAppWidgetManager.setWidgetPreview`) [V] (https://developer.android.com/jetpack/androidx/releases/glance). Target 37 caps RemoteViews bitmap memory [V]. Force-stop on 15+ greys out widgets until next launch [V]. [I] Keep the widget text-only (state + toggle), no bitmaps; offer "hide location on widget".

### 3.4 Material 3 Expressive, dynamic colour, theming

- [V] "Jetpack Compose offers an implementation of Material You and Material 3 Expressive ... M3 Expressive complements the Android 16 visual style" (https://developer.android.com/develop/ui/compose/designsystems/material3).
- [V] Expressive APIs live only in `material3` 1.5.0-alpha/beta (removed from 1.4.0-beta01). 1.5.0 graduated many from experimental: "Promote materialExpressTheme, expressiveLightColorScheme", FAB/FAB menu, buttons, menus, FloatingToolbar, flexible top/bottom app bars, list items; `MotionScheme.standard()` / `MotionScheme.expressive()`; new `Material3ExpressiveApi` annotation without opt-in (release notes). 1.5.0-beta01 released 2026-10-07 and depends on Compose 1.12.0 [V-MVN], so it works with BOM 2026.09.00 (Compose 1.12.1).
- Dynamic colour: `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` (API 31+, always available on minSdk 35) [V] (https://developer.android.com/develop/ui/views/theming/dynamic-colors). [I] Default to the Obscura brand scheme; offer "Use wallpaper colours" toggle.
- Fonts [I]: bundle fonts in `res/font`. Do not use `ui-text-google-fonts` downloadable fonts (Compose 1.12 promotes zero-config GMS certificates for them [V]); that is a GMS/network dependency.
- `material-icons-core` is frozen at 1.7.8 [V-MVN]; [I] import only the Material Symbols you need as vector drawables.

### 3.5 Edge-to-edge, predictive back, insets

- [V] Target 35: edge-to-edge enforced; target 36: opt-out attribute disabled. In Compose: call `enableEdgeToEdge()` in `onCreate`, set `android:windowSoftInputMode="adjustResize"`, handle insets (Scaffold / `WindowInsets.safeDrawing`) (https://developer.android.com/develop/ui/compose/system/setup-e2e).
- [V] Predictive back is on by default for target 36 on 16+; 3-button nav long-press back also animates (Android 16). `android:enableOnBackInvokedCallback` (API 33) only needed to opt out (https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture). [I] Use Navigation3 + Compose back handlers; never override `onBackPressed`.

### 3.6 Per-app language, icon

- [V] `androidResources { generateLocaleConfig = true }` generates `LocaleConfig` (needs API 33+ devices, compileSdk 33+) (https://developer.android.com/guide/topics/resources/app-languages). `LocaleManager` API 33 [V]. [I] No AppCompat needed.
- [V] Adaptive icon: all layers 108x108 dp, logo 48-66 dp inside 66 dp safe zone, add `<monochrome android:drawable="..."/>` for themed icons (https://developer.android.com/develop/ui/views/launch/icon_design_adaptive). Android 16 QPR2 auto-themes icons that lack it [V].

---

## 4. Native code (Rust core)

### 4.1 16 KB page size ([V] https://developer.android.com/guide/practices/page-sizes)

- Play: apps targeting 35+ must support 16 KB on 64-bit devices; "Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates." Earlier dates (2025-11-01, extension 2026-05-31) are superseded [3P confirms move].
- AGP >= 8.5.1 zip-aligns uncompressed `.so` at 16 KB. NDK r28+ "compile 16 KB-aligned by default". For r27 and lower: `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`.
- Verify: `llvm-objdump -p lib.so | grep LOAD` (all `align 2**14` or more); `zipalign -v -c -P 16 4 app.apk` ("Verification successful"); `bundletool dump config --bundle=app.aab | grep alignment` (`PAGE_ALIGNMENT_16K`); RELRO check `llvm-readelf -Wl lib.so | grep 'RELRO\|Type'`; runtime `adb shell getconf PAGE_SIZE` on a 16 KB emulator/Pixel dev option.
- Code: never assume 4096; use `sysconf(_SC_PAGESIZE)`; `PAGE_SIZE` macro is undefined in 16 KB mode on NDK r27+.
- Rust: `rustc 1.98.1` target spec for `aarch64-linux-android` has `relro-level: full`, `tls-model: emulated`, `supported-sanitizers` incl. `memtag`, and **no** max-page-size flag (checked locally with `RUSTC_BOOTSTRAP=1 rustc -Z unstable-options --print target-spec-json --target aarch64-linux-android`). [I] Linking through NDK r28+/r30 clang gives 16 KB; add belt-and-braces flags in `.cargo/config.toml`:

```toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

[target.x86_64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

- `android:pageSizeCompat` (API 36) exists to force compat mode [V]; [I] do not use it.

### 4.2 NDK and packaging

- Latest NDK: **r30 LTS, `ndkVersion = "30.0.16248370"`** [V] (https://developer.android.com/ndk/downloads). r29 (Oct 2025), r28 (Feb 2025) [V]. AGP 9.0 default NDK r28c; AGP 9.3/9.4 default `28.2.13676358` [V]. [I] Pin r30 explicitly.
- `useLegacyPackaging`: "If null, .so files will be uncompressed and page-aligned when minSdk >= 23." [V] (https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/JniLibsPackaging). [I] Leave null or set `false`; do not set `android:extractNativeLibs` in the manifest (AGP derives it).
- Android 17: `System.load()` paths must be read-only [V]. [I] Use `System.loadLibrary("...")` from the APK only; never load code downloaded at runtime.
- Android 17: JNI writes to `static final` fields crash [V]. [I] Audit the Rust `jni` code for `SetStatic*Field`.
- [V] io_uring blocked by seccomp (finding 5). [I] Keep tokio default epoll; no `tokio-uring`, no `io-uring` crate on Android.
- ABIs [I]: ship `arm64-v8a` only for release (all Android 15+ phones); add `x86_64` only for emulator/debug.

---

## 5. Performance

### 5.1 Build and runtime optimisation

- R8: AGP 9.3+ new DSL [V] (https://developer.android.com/topic/performance/app-optimization/enable-app-optimization):

```kotlin
android {
    buildTypes {
        release {
            optimization {
                enable = true // code + optimized resource shrinking, default Android keep rules included
            }
        }
    }
}
```

  Keep rules go in `src/<variant>/keepRules/*.keep` [V]. Legacy: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt`.
- AGP 9.0 defaults [V] (https://developer.android.com/build/releases/agp-9-0-0-release-notes): `android.r8.optimizedResourceShrinking` false->true; `android.r8.strictFullModeForKeepRules` false->true (`-keep class A` no longer keeps `<init>()`); resource shrinking always integrated in R8; `getDefaultProguardFile()` only supports `proguard-android-optimize.txt`; R8 default `-processkotlinnullchecks remove_message`. AGP 9.1: R8 repackages to the unnamed package by default [V]. AGP 9.3: `:app:analyzeReleaseR8Config` task [V].
- [I] JNI: keep every Kotlin class/method/field that Rust looks up by name (`FindClass`, `GetMethodID`, `GetFieldID`). With strict full mode, write explicit member rules. Strip `android.util.Log` in release with `-assumenosideeffects`.
- Baseline Profiles: about 30% faster code paths from first launch [V]; Startup Profiles (DEX layout, enabled by default since AGP 8.3) give 15-30% faster startup on top [V] (https://developer.android.com/topic/performance/baselineprofiles/overview, .../dex-layout-optimizations). Use plugin `androidx.baselineprofile` 1.5.0 and `androidx.profileinstaller:profileinstaller` 1.4.1 [V-MVN]. Generate from a `:baselineprofile` module with `BaselineProfileRule.collect(..., includeInStartupProfile = true)`; cover cold start, connect tap, location list scroll.
- Compose: strong skipping is default since Kotlin 2.0.20 [V] (https://developer.android.com/develop/ui/compose/performance/stability/strongskipping). Stability config: `composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("stability_config.conf")) }` (property `stabilityConfigurationFiles: ListProperty<RegularFile>`; older `stabilityConfigurationFile` also exists) [V] (https://kotlinlang.org/api/kotlin-gradle-plugin/compose-compiler-gradle-plugin/...). Feature flags available: `IntrinsicRemember`, `OptimizeNonSkippingGroups`, `PausableComposition`, `StrongSkipping` [V]. [I] Use `kotlinx-collections-immutable` 0.5.2 [V-MVN] for list state; `collectAsStateWithLifecycle`; lambda-based modifiers (`graphicsLayer {}`, `offset {}`, `drawBehind {}`) for animated values; stable `key`s in Lazy lists; `ReportDrawnWhen` for TTFD.
- Android 17: lock-free MessageQueue "reducing missed frames, improving app startup time" for target 37 [V].
- [I] Startup: no WebView (the upstream app is a WebView shell, `AndroidManifest.xml` lines 25-35, 78-80); no Hilt reflection-heavy graphs at startup (manual DI or Hilt with lazy); no AppCompat; load the Rust `.so` lazily; no ContentProvider-based auto-init libraries; system SplashScreen API (no `core-splashscreen` needed on minSdk 35).
- [I] VPN data path: keep packets entirely in Rust. `ParcelFileDescriptor.detachFd()` and hand the int fd to Rust (doc: "To use the file descriptor completely in native space, see ParcelFileDescriptor.detachFd()" [V]). Keep the fd non-blocking (default). Batch syscalls (`recvmmsg`/`sendmmsg`), try UDP GSO/GRO (`UDP_SEGMENT`, `UDP_GRO`) on the outer socket with fallback, measure on device. No JVM copies, no per-packet JNI.

### 5.2 Memory (Android 17)

[V] App memory limits for all apps on 17; diagnose via `ApplicationExitInfo.getDescription()` containing `"MemoryLimiter:AnonSwap"`; `adb shell am memory-limiter status|ignore|manual`. [I] Keep the `:vpn` process small (no Compose, no image loading); use the system allocator (scudo) in Rust for MTE and zero-init support.

---

## 6. Security and privacy hardening

### 6.1 Backup and device transfer

- [V] For target 31+, `android:allowBackup="false"` may not disable device-to-device transfer on some OEMs; use `android:dataExtractionRules` (API 31) (https://developer.android.com/identity/data/autobackup). Missing `<device-transfer>` section = fully enabled. `getNoBackupFilesDir()` and cache dirs are always excluded.
- Recommended `res/xml/data_extraction_rules.xml` [I based on [V] syntax]:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

  Plus `android:allowBackup="false"`. [I] Store all state under `noBackupFilesDir` anyway. Keystore keys never migrate, so restored ciphertext would be useless.
- `android:backupAgentProcess` (API 37) exists [V]; [I] not needed.

### 6.2 Screen privacy

- `WindowManager.LayoutParams.FLAG_SECURE`: blocks screenshots, recording, non-secure displays; on target 37 it is the only way to opt out of Content Capture [V].
- `Activity.setRecentsScreenshotEnabled(false)` (API 33) [V]: hides Recents thumbnail without blocking screenshots.
- `View.setContentSensitivity(int)` (API 35) [V] / Compose `Modifier.sensitiveContent()` ("content should be protected during screen share") [V] (https://developer.android.com/reference/kotlin/androidx/compose/ui/package-summary).
- `Activity.registerScreenCaptureCallback` (API 34, `DETECT_SCREEN_CAPTURE` normal) and `WindowManager.addScreenRecordingCallback(Executor, Consumer<Integer>)` (API 35, `DETECT_SCREEN_RECORDING` normal) [V].
- [I] Apply FLAG_SECURE on screens that show the account number / login, and a global "Secure screen" toggle (default on) for the rest.

### 6.3 Tapjacking and accessibility abuse

- [V] Android 12+ blocks touches from untrusted overlays of other UIDs (opacity rule for SAW). Partial occlusion needs manual `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` handling. `Window.setHideOverlayWindows(true)` (API 31) requires `android.permission.HIDE_OVERLAY_WINDOWS` (https://developer.android.com/privacy-and-security/risks/tapjacking, https://developer.android.com/reference/android/view/Window#setHideOverlayWindows(boolean)).
- [V] `View.setAccessibilityDataSensitive` (API 34) / Compose `semantics { isSensitiveData = true }`: only `isAccessibilityTool=true` services can read/interact.
- [I] Call `setHideOverlayWindows(true)` in the activity (all screens; a VPN app has no reason to allow overlays). Mark account number, logout, delete-account and kill-switch controls `isSensitiveData`.

### 6.4 Network security config (Java/Conscrypt stack only)

[V] https://developer.android.com/privacy-and-security/security-config: default for target 28+ is `cleartextTrafficPermitted="false"` + system trust anchors only (no user CAs). CT: on by default at target 37, opt-in on 36, unavailable on 35 and lower. ECH (`<domainEncryption mode="enabled|disabled"/>`): default enabled at target 37 when the library supports it.
[V-AOSP] Android 15's parser already reads `<certificateTransparency>` and skips unknown elements with `XmlUtils.skipCurrentTag` (`core/java/android/security/net/config/XmlConfigSource.java` at android-15.0.0_r1, lines 145-149, 214-218, 293-297), so the file below is safe on minSdk 35.

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
        <certificateTransparency enabled="true" />
        <domainEncryption mode="enabled" />
    </base-config>
</network-security-config>
```

- Do not set `android:usesCleartextTraffic` (to be ignored from target 38) [V].
- [I] **Important:** the Rust core does its own TLS (rustls or similar). NSC, CT, ECH and pinning in this file do not apply to Rust sockets. Pinning / CT / ECH for API calls must be done in Rust. NSC still protects any Kotlin-side HTTP (ideally none).

### 6.5 Secret storage (account token)

- [V] Jetpack `security-crypto` 1.1.0 (2025-07-30): "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore." (https://developer.android.com/jetpack/androidx/releases/security).
- [V] Keystore: `KeyGenParameterSpec.Builder.setIsStrongBoxBacked(true)` (API 28); StrongBox supports AES 128/256, RSA 2048, ECDSA/ECDH P-256, HMAC-SHA256; throws `StrongBoxUnavailableException` if unsupported; "For most apps, StrongBox is not necessary ... slower" (https://developer.android.com/privacy-and-security/keystore). Check `KeyInfo.getSecurityLevel()`.
- [V] `setUnlockedDeviceRequired(true)` (API 28): key usable only while device unlocked; symmetric encrypt also restricted on 12+.
- [I] Pattern: AES-256-GCM key in `AndroidKeyStore` (TEE; try StrongBox first, fall back), non-exportable; encrypt the account token / WireGuard private key blob with a random 12-byte IV; store ciphertext in a file under `noBackupFilesDir` (or DataStore 1.2.1 there). Do **not** set `setUnlockedDeviceRequired(true)` on the key the VPN process needs, because reconnects and token refresh happen while the screen is locked. Optional second key with `setUnlockedDeviceRequired(true)` + `setUserAuthenticationParameters` for UI-only secrets (showing/copying the account number behind biometric). `androidx.biometric` latest stable is 1.1.0 (1.4.0 in alpha) [V-MVN]; on minSdk 35 the platform `BiometricPrompt` is enough.
- [I] Do not use `AccountManager` (no benefit, extra exposure), Credential Manager (no passwords/passkeys here), or Block Store (GMS).
- [V] Android 17 adds ML-DSA keys in secure hardware (supported devices) [V] Android 17 blog; [I] not needed now.

### 6.6 Memory safety

- [V] `android:memtagMode="off|default|sync|async"` on `<application>` or `<process>` (API 31). MTE is off unless the app opts in. Use `sync` in debug, `async` in production (https://developer.android.com/ndk/guides/arm-mte). MTE devices: Pixel 8/8 Pro/8a, 9 family, 9a, 10 family, 10a (dev option or Advanced Protection).
- [3P] Whether Advanced Protection forces MTE on apps that did not opt in is not documented; Google Help says "For supported apps". [I] Opt in with `async` ourselves; test on a Pixel 8+ with MTE on.
- [V] `android:nativeHeapZeroInitialized="true"` (API 31): zero-init all native heap allocations. [I] Enable for the `:vpn` process too; measure CPU cost.
- [V] Scudo is the standard allocator; custom allocators lose these protections. [I] Do not ship mimalloc/jemalloc in Rust for Android.
- `android:allowNativeHeapPointerTagging` (API 30): keep default true [V attribute exists].

### 6.7 Intents, components, activities

- [V] `android:intentMatchingFlags="enforceIntentFilter"` on `<application>` (API 36): explicit intents must match filters; action-less intents match nothing (https://developer.android.com/about/versions/16/behavior-changes-16).
- [V] `android:allowCrossUidActivitySwitchFromBelow="false"` on `<application>` (API 35): blocks other apps' activities from replacing ours in the same task.
- [V] `android:requireContentUriPermissionFromCaller` on `<activity>` (API 35): values `none|read|write|readOrWrite|readAndWrite`. [I] Set `read` on any activity that accepts content URIs (for example import config); otherwise not needed.
- [V] Android 17 BAL: migrate from `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` to `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` for PendingIntents/IntentSenders.
- [V] Android 15 StrictMode `detectUnsafeIntentLaunch()`; Android 17 `detectImplicitUriPermissionGrant()`. [I] Enable both in debug.
- Exported audit [I]: exported = launcher activity, TileService (must, protected by `BIND_QUICK_SETTINGS_TILE`), Glance widget receiver (must, `APPWIDGET_UPDATE`). Not exported: VpnService, FileProvider (`grantUriPermissions="true"`), all receivers/internal services. Custom scheme `obscuravpn://` (upstream line 42-48) can be fired by any app: validate input, never auto-act (no connect/logout from a link without user confirmation); prefer verified App Links for payment return URLs.
- [V] Android 17 PQC v3.2 hybrid signing; self-managed keys need a **new** classical key paired with ML-DSA. [I] Optional for F-Droid/GitHub releases; check `apksigner` support before adopting.

### 6.8 Advanced Protection Mode (AAPM)

- [V] API 36: `getSystemService(AdvancedProtectionManager::class.java)`, `isAdvancedProtectionEnabled()`, `registerAdvancedProtectionCallback(Executor, Callback)`, `unregisterAdvancedProtectionCallback(Callback)`; all require `android.permission.QUERY_ADVANCED_PROTECTION_MODE` (https://developer.android.com/reference/android/security/advancedprotection/AdvancedProtectionManager). "If your app caters to security-conscious users, you must adapt to these restrictions" [V] (https://developer.android.com/privacy-and-security/advanced-protection-mode). AAPM blocks 2G/WEP, sideloading, restricts USB, enables forensic logging.
- [I] When AAPM is on: suggest enabling always-on + lockdown, default LAN access off, default split tunnelling off, force FLAG_SECURE everywhere, hide location in notification/widget, prefer the most robust transport. Guard calls with `Build.VERSION.SDK_INT >= 36` if minSdk is 35.

### 6.9 Telemetry and dependencies

[I] No Firebase, no Play Services (except an isolated `play` flavor for Billing, as upstream does), no analytics, no crash reporter, no Google Fonts provider, no WebView (so `WebView.MetricsOptOut` is moot). `profileinstaller` works locally and makes no network calls [I].

### 6.10 Package visibility for split tunnelling

- [V] Play allows `QUERY_ALL_PACKAGES` only for "device search, antivirus apps, file managers, and browsers"; VPN is not listed (https://support.google.com/googleplay/android-developer/answer/10158779).
- [I] Use `<queries>` with the launcher intent (shows all user-launchable apps). Offer `QUERY_ALL_PACKAGES` only in a non-Play (FOSS) flavor if needed.

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

### 6.11 Google Play VPN policy

[V] VpnService must be core functionality; must encrypt device-to-endpoint traffic; must document VpnService use in the listing; Play Console VpnService declaration; no data collection without prominent disclosure (https://support.google.com/googleplay/android-developer/answer/12564964). [V] Play target API: from 2026-08-31 new apps/updates must target API 36+ (https://developer.android.com/google/play/requirements/target-sdk). Targeting 37 exceeds it.

---

## 7. Library and tool versions (latest as of 2026-10-20)

All [V-MVN] from `https://dl.google.com/android/maven2/<group>/<artifact>/maven-metadata.xml` or `https://repo1.maven.org/maven2/...`, unless marked.

| Component | Latest stable | Latest pre-release | Sibling project | Verdict |
|---|---|---|---|---|
| AGP `com.android.tools.build:gradle` | **9.4.1** | 9.5.0-alpha10 | 9.4.1 | confirmed |
| Gradle (needed by AGP 9.4: min 9.6.0 [V]) | **9.8.2** (services.gradle.org, built 2026-10-19) | | | use 9.8.2 |
| Kotlin Gradle plugin / Compose compiler plugin | **2.4.21** | 2.5.0-Beta1 | 2.4.21 | confirmed |
| KSP | 2.3.12 | | | if needed |
| Compose BOM | **2026.09.00** (maps ui/runtime/foundation/animation 1.12.1, material3 **1.4.0**, adaptive 1.3.0, material-icons-core 1.7.8) | Compose 1.13.0-beta01 | 2026.09.00 | confirmed |
| `androidx.compose.material3:material3` | 1.4.0 (no Expressive) | **1.5.0-beta01** (2026-10-07) | 1.5.0-beta01 | confirmed; required for Expressive |
| `material3-adaptive` | 1.3.0 | 1.4.0-alpha03 | | |
| `androidx.navigation3:navigation3-runtime` / `-ui` | **1.2.0** | 1.3.0-alpha02 | 1.2.0 | confirmed |
| `androidx.lifecycle:*` (incl. `lifecycle-viewmodel-navigation3`, `lifecycle-runtime-compose`, `lifecycle-service`) | **2.11.0** | 2.12.0-alpha04 | 2.11.0 | confirmed |
| `androidx.activity:activity-compose` | **1.13.0** | 1.14.0-alpha03 | 1.13.0 | confirmed |
| `androidx.core:core-ktx` | **1.19.1** | | | |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.12.0** (2026-10-19) | | | |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | **1.11.0** | | | |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | 0.5.2 | | | |
| `androidx.baselineprofile` Gradle plugin | **1.5.0** | 1.6.0-alpha01 | | |
| `androidx.benchmark:benchmark-macro-junit4` | 1.5.0 | 1.6.0-alpha01 | | |
| `androidx.profileinstaller:profileinstaller` | **1.4.1** | | | |
| `androidx.glance:glance-appwidget` / `glance-material3` | **1.2.0** | 1.3.0-alpha02 | | |
| `androidx.datastore:datastore` | 1.2.1 | 1.3.0-rc01 | | |
| `androidx.tracing:tracing` | 2.0.4 | | | |
| `androidx.window:window` | 1.5.1 | 1.6.0-alpha05 | | |
| `androidx.security:security-crypto` | 1.1.0 (all APIs deprecated) | | | do not use |
| `com.google.crypto.tink:tink-android` | 1.24.0 | | | [I] not needed |
| R8 standalone | 9.5.28 | 9.6.0-alpha06 | | bundled with AGP |
| NDK | **r30 LTS `30.0.16248370`** [V] | | | pin it |
| Build tools | **37.0.0** [V repo] (AGP 9.4 min/default 36.0.0) | | | |
| SDK platforms | android-37.0, 37.1, 37.2 [V repo] | | | |
| Rust (local) | 1.98.1 (2026-09-01) | | | |

AGP 9 notes [V]: built-in Kotlin is on by default (do not apply `org.jetbrains.kotlin.android`); AGP has a runtime dependency on KGP 2.2.10 and auto-upgrades lower versions; to use 2.4.21 add `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") } }` (or the equivalent plugin version in the version catalog/settings). Still apply `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` at 2.4.21. `android.sdk.defaultTargetSdkToCompileSdkIfUnset` now defaults targetSdk to compileSdk. AGP 9.4: Gradle min 9.6.0, JDK 17, max API 37 [V]. Minor SDK DSL [V] (16 QPR2 page): `compileSdk { version = release(36) { minorApiLevel = 1 } }`; [I] same pattern `release(37) { minorApiLevel = 2 }` if you need 37.2 APIs (none required by us).

---

## 8. Concrete checklist (with API levels and URLs)

### Build
- [ ] compileSdk 37 (optionally `release(37){minorApiLevel=2}`), targetSdk 37, minSdk **35** (wider reach, Android 15+) or **36** (drops `SDK_INT` checks for the AAPM API, `ProgressStyle`, `setShortCriticalText`, `canPostPromotedNotifications`; `intentMatchingFlags` and CT then always apply). [I] Recommend 35 with `SDK_INT` guards; 36 if "only very recent devices" is a hard rule.
- [ ] AGP 9.4.1, Gradle 9.8.2, Kotlin 2.4.21 (built-in Kotlin), JDK 17+ toolchain. (https://developer.android.com/build/releases/gradle-plugin)
- [ ] `optimization { enable = true }` in release (AGP 9.3+); JNI keep rules in `src/main/keepRules/jni.keep`. (https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
- [ ] Baseline + Startup Profiles via `androidx.baselineprofile` 1.5.0, `profileinstaller` 1.4.1. (https://developer.android.com/topic/performance/baselineprofiles/overview)
- [ ] NDK r30 `30.0.16248370`; Rust linked with NDK clang; extra `-z max-page-size=16384`; CI check with `llvm-readelf` + `zipalign -c -P 16`. (https://developer.android.com/guide/practices/page-sizes)
- [ ] arm64-v8a only in release; `useLegacyPackaging` unset/false.
- [ ] No io_uring code paths in Rust for Android (seccomp SIGSYS). (https://security.googleblog.com/2023/06/learnings-from-kctf-vrps-42-linux.html)
- [ ] `generateLocaleConfig = true`. (https://developer.android.com/guide/topics/resources/app-languages)
- [ ] Compose BOM 2026.09.00 + explicit `material3:1.5.0-beta01`; Navigation3 1.2.0; lifecycle 2.11.0; activity-compose 1.13.0.

### VPN service
- [ ] `<service ... android:permission="android.permission.BIND_VPN_SERVICE" android:foregroundServiceType="systemExempted" android:exported="false" android:process=":vpn">` + `android.net.VpnService` intent filter; keep always-on supported (no opt-out meta-data). (https://developer.android.com/develop/connectivity/vpn)
- [ ] Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` (API 34), `POST_NOTIFICATIONS` (API 33). Not: `ACCESS_LOCAL_NETWORK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `QUERY_ALL_PACKAGES` (Play flavor).
- [ ] Always call `VpnService.prepare()` before connect; launch the consent intent from an activity (or `startActivityAndCollapse(PendingIntent)` from the tile, API 34).
- [ ] `startForeground(id, n, FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)` only after consent; `FOREGROUND_SERVICE_IMMEDIATE` (API 31).
- [ ] Detect system start: `intent?.action == VpnService.SERVICE_INTERFACE` and `isAlwaysOn()` (API 29); when always-on, hide "Disconnect" and show "Managed by system settings" with deep link `Settings.ACTION_VPN_SETTINGS`.
- [ ] Show lockdown state via `isLockdownEnabled()` (API 29); warn that split tunnelling / LAN access cut those apps off under lockdown [I + 3P].
- [ ] Builder: v4 + v6 addresses, `0.0.0.0/0` + `::/0`, in-tunnel DNS (`addDnsServer`), `setMtu` from Rust core, `setMetered(false)` (API 29), `setUnderlyingNetworks` (API 22), `setConfigureIntent`, `setSession("Obscura")`; LAN access via `excludeRoute(IpPrefix)` (API 33) for 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7 (multicast as needed) [I list]; split tunnel via `addDisallowedApplication` (API 21). Never `allowBypass()`.
- [ ] Do not expose `Settings.ACTION_VPN_APP_EXCLUSION_SETTINGS` (API 37); it does nothing for VpnService [V-AOSP].
- [ ] `detachFd()` the TUN fd into Rust; non-blocking; epoll.
- [ ] Track physical network with `NET_CAPABILITY_NOT_VPN` request + `registerBestMatchingNetworkCallback` (API 31); `protect()` every outer socket; rehandshake on network change; update `setUnderlyingNetworks`.
- [ ] Private DNS check through `LinkProperties.getPrivateDnsServerName()` (API 28); warn; never block TCP/853.
- [ ] `onRevoke()`: close fd + sockets, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`; may run off main thread.
- [ ] Handle the Android 16 "stuck after update" case: detect no-progress handshake after `ACTION_MY_PACKAGE_REPLACED` and offer "reconnect" / re-establish [3P + I].

### Notification and system UI
- [ ] Notification channel LOW; ongoing; `VISIBILITY_PRIVATE` + neutral public version; actions: Disconnect (hidden when always-on), Change location.
- [ ] API 37: `MetricStyle` + semantic colours; promotion (`POST_PROMOTED_NOTIFICATIONS`, 36.1) only as user opt-in. (https://developer.android.com/develop/ui/views/notifications/live-update)
- [ ] QS tile: active + toggleable + `CATEGORY_CONNECTIVITY`; `requestListeningState` on state change; `unlockAndRun` for disconnect when `isSecure()` [I]; `requestAddTileService` (API 33) in context. (https://developer.android.com/develop/ui/views/quicksettings-tiles)
- [ ] Static shortcuts (no sensitive labels). Glance 1.2.0 widget, text-only, generated preview.
- [ ] `enableEdgeToEdge()`, `adjustResize`, predictive back with Navigation3; adaptive icon with monochrome layer; brand colour scheme + optional dynamic colour; bundled fonts.

### Security / privacy manifest
- [x] `<application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules" android:networkSecurityConfig="@xml/network_security_config" android:memtagMode="async" android:nativeHeapZeroInitialized="true" android:allowCrossUidActivitySwitchFromBelow="false" android:intentMatchingFlags="enforceIntentFilter">`; debug buildType manifest overrides `memtagMode="sync"` (`tools:replace`). Done (OMBRA-SEC-008), with two exceptions: `nativeHeapZeroInitialized` only after a Speed mode CPU and throughput test (`perf-bench.md`), and no `intentMatchingFlags`, because `VpnNotifications.openApp()` starts MainActivity with an explicit intent that has no action.
- [ ] FLAG_SECURE on account/login screens + global toggle; `setRecentsScreenshotEnabled(false)` (API 33); `Modifier.sensitiveContent()`; `semantics { isSensitiveData = true }`; `window.setHideOverlayWindows(true)` + `HIDE_OVERLAY_WINDOWS` (API 31).
- [ ] Keystore AES-GCM (StrongBox if available), ciphertext in `noBackupFilesDir`; no security-crypto.
- [ ] AAPM listener (API 36, `QUERY_ADVANCED_PROTECTION_MODE`) raises defaults.
- [ ] Rust-side TLS: pin Obscura API certs / keys; NSC does not cover Rust.
- [ ] StrictMode in debug: `detectUnsafeIntentLaunch()` (API 35), `detectImplicitUriPermissionGrant()` (API 37).
- [ ] No GMS/Firebase/analytics in the FOSS flavor; Billing only in `play` flavor.

---

## 9. Draft AndroidManifest.xml skeleton [I, assembled from the [V] items above]

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />
    <uses-permission android:name="android.permission.QUERY_ADVANCED_PROTECTION_MODE" />
    <!-- Optional, only if the user opts in to the status chip: -->
    <!-- <uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" /> -->

    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <application
        android:name=".ObscuraApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:memtagMode="async"
        android:nativeHeapZeroInitialized="true"
        android:allowCrossUidActivitySwitchFromBelow="false"
        android:intentMatchingFlags="enforceIntentFilter"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Obscura"
        tools:targetApi="37">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES" />
            </intent-filter>
            <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />
        </activity>

        <service
            android:name=".vpn.ObscuraVpnService"
            android:exported="false"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:foregroundServiceType="systemExempted"
            android:process=":vpn"
            tools:ignore="ForegroundServicePermission,VpnServicePolicy">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>

        <service
            android:name=".tile.VpnTileService"
            android:exported="true"
            android:icon="@drawable/ic_tile_shield"
            android:label="@string/tile_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
            android:process=":vpn">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
            <meta-data android:name="android.service.quicksettings.ACTIVE_TILE" android:value="true" />
            <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true" />
            <meta-data android:name="android.service.quicksettings.TILE_CATEGORY"
                android:value="android.service.quicksettings.CATEGORY_CONNECTIVITY" />
        </service>
    </application>
</manifest>
```

Notes [I]: `intentMatchingFlags` (API 36) and `allowCrossUidActivitySwitchFromBelow` (API 35) are ignored by older platforms; keep `tools:targetApi` to silence lint. If the tile lives in `:vpn`, `requestListeningState` must be called from that process. `launchMode="singleTask"` replaces upstream's `singleInstance` (upstream needed it for WebView).

---

## 10. Open items to test on device [I]

1. Under lockdown: do apps from `addDisallowedApplication` and `excludeRoute` LAN ranges lose network (AOSP reading says yes)? Pixel on 17 and 16.
2. `startForeground(..., SYSTEM_EXEMPTED)` before first `establish()` but after consent: confirm no exception on 35, 36, 37.
3. `ACTION_VPN_APP_EXCLUSION_SETTINGS` on a Pixel with a VpnService app: confirm the list has no routing effect (AOSP says none).
4. MTE async + nativeHeapZeroInitialized throughput cost on Pixel 8+/10.
5. UDP GSO/GRO and `sendmmsg` availability for app UIDs on GKI kernels (seccomp allows the syscalls; check setsockopt results).
6. Private DNS strict mode with the in-tunnel resolver.
7. Live Update promotion acceptance by Pixel SystemUI for a VPN notification (OEM rules may reject).

---

## Sources (all fetched 2026-10-20)

Official docs:
- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/reference/android/net/VpnService.Builder
- https://developer.android.com/develop/connectivity/vpn
- https://developer.android.com/reference/android/provider/Settings
- https://developer.android.com/reference/android/net/VpnManager
- https://developer.android.com/reference/android/net/LinkProperties
- https://developer.android.com/reference/android/net/ConnectivityManager
- https://developer.android.com/reference/android/net/Network
- https://developer.android.com/ndk/reference/group/networking
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/develop/background-work/services/fgs/declare
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/about/versions/14/behavior-changes-all
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/about/versions/15/behavior-changes-all
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/16/features
- https://developer.android.com/about/versions/16/qpr2/setup-sdk
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/about/versions/17/features
- https://developer.android.com/sdk/api_diff/35/changes/pkg_android.net.html (and 36, 37, 37.2 equivalents)
- https://developer.android.com/develop/ui/views/notifications/live-update
- https://developer.android.com/develop/ui/views/notifications/progress-centric
- https://developer.android.com/reference/android/app/Notification.MetricStyle
- https://developer.android.com/reference/android/app/Notification.Builder
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://developer.android.com/develop/ui/views/quicksettings-tiles
- https://developer.android.com/reference/android/service/quicksettings/TileService
- https://developer.android.com/develop/ui/views/launch/shortcuts
- https://developer.android.com/jetpack/androidx/releases/glance
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://developer.android.com/develop/ui/views/theming/dynamic-colors
- https://developer.android.com/develop/ui/compose/system/setup-e2e
- https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
- https://developer.android.com/develop/ui/views/launch/icon_design_adaptive
- https://developer.android.com/guide/topics/resources/app-languages
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/ndk/downloads
- https://developer.android.com/ndk/downloads/revision_history
- https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/JniLibsPackaging
- https://developer.android.com/build/releases/gradle-plugin
- https://developer.android.com/build/releases/agp-9-0-0-release-notes (and 9-1-0, 9-3-0)
- https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations
- https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- https://developer.android.com/develop/ui/compose/performance/stability/fix
- https://kotlinlang.org/docs/compose-compiler-options.html
- https://kotlinlang.org/api/kotlin-gradle-plugin/compose-compiler-gradle-plugin/org.jetbrains.kotlin.compose.compiler.gradle/-compose-compiler-gradle-plugin-extension/
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/privacy-and-security/security-config
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder
- https://developer.android.com/jetpack/androidx/releases/security
- https://developer.android.com/privacy-and-security/risks/tapjacking
- https://developer.android.com/reference/android/view/Window
- https://developer.android.com/reference/android/view/View
- https://developer.android.com/reference/android/app/Activity
- https://developer.android.com/reference/android/view/WindowManager
- https://developer.android.com/reference/kotlin/androidx/compose/ui/package-summary
- https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/package-summary
- https://developer.android.com/ndk/guides/arm-mte
- https://developer.android.com/privacy-and-security/advanced-protection-mode
- https://developer.android.com/reference/android/security/advancedprotection/AdvancedProtectionManager
- https://developer.android.com/privacy-and-security/local-network-permission
- https://developer.android.com/reference/android/Manifest.permission
- https://developer.android.com/reference/android/R.attr
- https://developer.android.com/guide/topics/manifest/application-element
- https://developer.android.com/guide/topics/manifest/activity-element
- https://developer.android.com/develop/connectivity/satellite/constrained-networks
- https://developer.android.com/google/play/requirements/target-sdk
- https://support.google.com/googleplay/android-developer/answer/12564964 (VpnService policy)
- https://support.google.com/googleplay/android-developer/answer/10158779 (QUERY_ALL_PACKAGES policy)
- https://android-developers.googleblog.com/2026/06/Android-17.html
- https://android-developers.googleblog.com/2026/03/the-third-beta-of-android-17.html
- https://security.googleblog.com/2023/06/learnings-from-kctf-vrps-42-linux.html
- https://dl.google.com/android/repository/repository2-3.xml
- https://services.gradle.org/versions/current

AOSP (tag android-17.0.0_r1 unless noted, https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/...):
- services/core/java/com/android/server/connectivity/Vpn.java (lines 206, 830-870, 1188-1199, 1643, 1757-1814, 2118-2125, 4482, 4637-4652)
- services/core/java/com/android/server/VpnManagerService.java (lines 273-278)
- core/java/android/net/VpnService.java (lines 781-789)
- core/java/com/android/internal/net/VpnConfig.java (lines 106-109)
- core/java/android/app/ForegroundServiceTypePolicy.java (lines 555-571)
- core/java/android/net/NetworkPolicyManager.java (lines 192-193, 836-843)
- apex/jobscheduler/framework/java/android/os/PowerExemptionManager.java (lines 325, 434)
- packages/SystemUI/src/com/android/systemui/qs/external/TileLifecycleManager.java (lines 99, 637-641)
- packages/SystemUI/src/com/android/systemui/qs/FgsManagerController.kt (lines 738-765)
- services/core/java/com/android/server/am/AppRestrictionController.java (lines 2973-2981)
- core/java/android/security/net/config/XmlConfigSource.java at android-15.0.0_r1 (lines 145-149, 214-218, 293-297)
- platform/packages/apps/Settings AndroidManifest.xml (lines 1030-1041), src/com/android/settings/vpn2/AppExclusionActivity.java, AppExclusionUtils.java

Third-party (hints only):
- https://www.androidauthority.com/android-17-vpn-split-tunneling-3652497/
- https://www.androidauthority.com/android-16-vpn-bug-3650394/
- https://www.forbes.com/sites/daveywinder/2026/05/15/wont-fix-all-vpn-apps-affected-as-google-android-16-leaks-info/
- https://github.com/celzero/brave-android-app/issues/25
- https://github.com/akane599/blockads-android/pull/2
- https://www.bigiron.cc/guides/android-always-on-vpn-and-block-connections-without-vpn-what-still-leaks
- https://mullvad.net/en/help/configure-connectivity-checks-on-android
- https://help.malwarebytes.com/hc/en-us/articles/31589640441883-Manage-Mobile-Security-VPN-settings-on-Android-devices
- https://support.google.com/android/answer/16339980 (AAPM help, MTE "for supported apps")
