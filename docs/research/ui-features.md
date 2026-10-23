# ui-features: upstream Obscura UI feature inventory and design language

Source: `/tmp/obscura-research/obscuravpn-client` (HEAD `85ef7e2692dc811a023360f08865bfb90815649c`, 2026-10-19). UI package `obscura-ui` v0.2.3, React 19 + Mantine 8.3.18 + i18next. Only English strings exist.

Path abbreviations used below:

- `UI` = `/tmp/obscura-research/obscuravpn-client/obscura-ui/src`
- `AND` = `/tmp/obscura-research/obscuravpn-client/android/app/src/main`
- `RL` = `/tmp/obscura-research/obscuravpn-client/rustlib/src`
- `API` = `~/.cargo/git/checkouts/obscuravpn-api-e8686c0572b4accd/e94483e/src` (the exact pinned rev e94483e)
- `MANTINE` = Mantine 8.3.18 defaults (fetched from GitHub tag `8.3.18`: `default-colors.ts`, `default-css-variables-resolver.ts`, `get-css-color-variables.ts`, `default-theme.ts`, `Card/Paper/Progress/Notification.module.css`)

Markers: **[V]** = verified in source. **[I]** = inference. **[S]** = suggestion for the new native client (not upstream behaviour).

---

## 0. Architecture facts that drive the UI (read first)

1. **[V] No in-web navigation chrome.** The web UI has no tab bar or sidebar. The native shell owns navigation and reports the current tab in `OsStatus.navigationView`. `App.tsx` renders `<Routes location={"/" + osStatus.navigationView}>` (`UI/App.tsx:382`). On Android the native `BottomNavigationView` has 5 items: Connection, Location, Account, Settings, About (`AND/res/menu/nav_menu.xml:3-22`, labels `AND/res/values/strings.xml:11-15`). The bottom nav is hidden when logged out or in the new-account flow, and when the IME is visible (`AND/java/net/obscura/vpnclientapp/ui/ObscuraUI.kt:71-74,102-110,156`).
2. **[V] Routes** (`UI/App.tsx:56-66`): `/` and `/connection` (Connection), `/developer`, `/location`, `/account`, `/help`, `/about`, `/settings`, `/apps`. `NavigationView` enum values: `developer|connection|location|account|help|about|settings|apps` (`UI/common/appContext.ts:66-75`). Android maps `help` to the About tab and has no `apps` (`AND/.../ui/ObscuraUI.kt:30-55`, `AND/.../ui/OsStatus.kt:45-56`).
3. **[V] Two long-poll loops feed the whole UI** (`UI/App.tsx:183-223`):
   - `getStatus` (rustlib `Status`, called `AppStatus` in TS) with `knownVersion` = last `version` UUID; blocks until a change.
   - `getOsStatus` (native-built `OsStatus`) with `knownVersion`; blocks until a change.
4. **[V] Other periodic loads:**
   - `apiServiceStatus` once at app start to decide "Start Trial" vs "Create an Account" (`UI/App.tsx:250-252`).
   - `apiGetAccountInfo` via `useLoadable` (`UI/App.tsx:304-314`). Skipped when offline or logged out. Period is 3 s while a payment is processing, 60 s in the new-account flow, otherwise 12 h. On error the backoff is `min(random*500*2^n ms, 60 s)` (`UI/common/useLoadable.ts:27-37`). It is also refreshed when the Account view mounts (`UI/views/AccountView.tsx:37-40`), when the payment sheet opens (`UI/components/PaymentManagementSheet.tsx:23-25`), and when connect fails with `accountExpired` (`UI/App.tsx:86-88`).
   - Exit list via a shared "watchable" (`UI/common/useExitList.ts`, `UI/common/useSharedWatchable.ts:40-104`). It long-polls `getExitList{knownVersion}` and calls `refreshExitList{freshness: periodS*1000}` every `periodS`. The Connection screen uses 3600 s, the combobox and the Location view use 60 s.
   - `getTrafficStats` every 1 s while the Location view shows a non-disconnected state (`UI/views/LocationView.tsx:405-421`) and in the Developer view.
5. **[V] Bridge transport on Android** (`UI/bridge/setupBridge.ts:14-23,29-90`). JS calls `window.obscuraAndroidCommandBridge.invoke(dataJson, id)`. Native replies with `postWebMessage("android/" + {"id":n,"data":"<json string>"} | {"id":n,"error":"<errorCode>"})`. Native dispatch is in `AND/.../ui/bridge/WebCmd.kt:35-264`, with the reply/reject envelope at `WebCmdBridge.kt:23-41`.
6. **[V] Envelope.** `invoke(cmd, args)` sends `{"<cmd>": args}` (`UI/bridge/commands.ts:13-25`). Rust commands are wrapped as `{"jsonFfiCmd":{"cmd":"<stringified ManagerCmd JSON>","timeoutMs":10000|null}}` (`UI/bridge/commands.ts:53-60`). Android ignores `timeoutMs` (`WebCmd.JsonFfiCmd` has only `cmd`, `WebCmd.kt:124-132`). **A native client calls the Rust JSON FFI directly with the inner ManagerCmd JSON.**

---

## 1. Bridge command catalogue: UI action -> bridge command -> ManagerCmd JSON

ManagerCmd naming rules **[V]** (`RL/manager_cmd.rs:129-218`): externally tagged enum, `rename_all = "camelCase"` for variant names, and `rename_all_fields = "camelCase"` for the variant's own fields. Nested structs keep their own serde rules:

- `PinnedLocation` fields are snake_case: `country_code`, `city_code`, `pinned_at` (unix seconds) (`RL/config/persistence.rs:411-419`).
- `ExitSelector` variants are camelCase and inner fields are snake_case (`RL/exit_selection.rs:9-23`). `City` flattens `CityCode{country_code, city_code}` (`API/types.rs:544-547`).
- `DnsContentBlock` is camelCase (`RL/network_config.rs:69-78`).

### 1a. Rust JSON-FFI commands used by the UI

| UI function (`UI/bridge/commands.ts` line) | Triggered by | Exact ManagerCmd JSON | Ok response |
|---|---|---|---|
| `status()` 80-86 | App loop | `{"getStatus":{"knownVersion":"<uuid>"\|null}}` | `Status` (see 1d) |
| `login(id, validate)` 96-98 | Log In (`validate=true`), Create account / Start Trial (`validate=true`) | `{"login":{"accountId":"<20 digits>","validate":true}}` | `null` |
| `logout()` 100-102 | Log out, Cancel sign-up, after Delete Account | `{"logout":{}}` | `null` |
| `getAccount()` 256-259 | Account polling | `{"apiGetAccountInfo":{}}` | `AccountInfo` (`API/types.rs:134-207`). Also refreshes `Status.account` (`RL/manager.rs:262-266`) |
| `getServiceStatus()` 261-264 | App start | `{"apiServiceStatus":{}}` | `{"free_trial":{"available":bool},"nnn":{"claimed_months_target":n,"claimed_months_total":n,"submissions_open":bool}}` (`UI/common/api.ts:59-72`) |
| `deleteAccount()` 252-254 | Delete Account confirm | `{"apiDeleteAccount":{}}` | `DeleteAccountOutput` |
| `setInNewAccountFlow(v)` 266-268 | Create account (true), Done/Cancel/payment success (false) | `{"setInNewAccountFlow":{"value":true}}` | `null` |
| `getExitList(v)` 238-244 | Exit watchable | `{"getExitList":{"knownVersion":"<base64>"\|null}}` | `{"version":"<base64>","last_updated":<unix s>,"value":{"exits":[OneExit…]}}` (`RL/cached_value.rs:7-16`) |
| `refreshExitList(s)` 246-250 | Watchable timer; "Fetch list of exits" button uses `0` | `{"refreshExitList":{"freshness":<ms>}}` | `null` |
| `setPinnedExits(list)` 270-272 | Pin/unpin on Location card | `{"setPinnedExits":{"exits":[{"country_code":"us","city_code":"nyc","pinned_at":1760000000}]}}` | `null` |
| `rotateWgKey()` 274-276 | Settings > Network > "Rotate WireGuard® key" | `{"rotateWgKey":{}}` | `null` |
| `setAutoConnect(b)` 278-280 | Settings > General switch | `{"setAutoConnect":{"enable":true}}` | `null` |
| `setUseSystemDns(b)` 282-284 | Settings > DNS radio (**not on Android/Windows**) | `{"setUseSystemDns":{"enable":false}}` | `null` |
| `setLocalNetworkAccess(b)` 286-288 | Settings > Network switch (**Android + Linux only**) | `{"setLocalNetworkAccess":{"enable":true}}` | `null` |
| `setTailscaleBypass(b)` 290-292 | Linux only | `{"setTailscaleBypass":{"enable":true}}` | `null` |
| `setWireGuardBypass(b)` 294-296 | Linux only | `{"setWireGuardBypass":{"enable":true}}` | `null` |
| `setFeatureFlag(k,b)` 298-300 | Settings > Experimental switches | `{"setFeatureFlag":{"flag":"tcpTlsTunnel","active":true}}`. `active:false` stores `None` (`RL/config/feature_flags.rs:27-29`) | `null` |
| `setDnsContentBlock(v)` 302-304 | Settings > DNS checkboxes (sends the full object each time) | `{"setDnsContentBlock":{"value":{"ad":true,"tracker":true,"malware":true,"adult":false,"gambling":false,"socialMedia":false}}}` | `null` |
| `getTrafficStats()` 224-226 | Location view timer, Developer view | `{"getTrafficStats":{}}` | `{"connectedMs":u64,"connId":"<uuid>","txBytes":u64,"rxBytes":u64,"latestLatencyMs":u16}` (`RL/manager.rs:423-431`) |
| `setApiUrl(u)` 104-106 | Developer view | `{"setApiUrl":{"url":"https://v1.api.prod.obscura.net/api"\|null}}` | `null` |
| `setApiHostAlternate(h)` 108-110 | Developer view | `{"setApiHostAlternate":{"host":"api.example.com"\|null}}` | `null` |
| `setSniRelay(h)` 112-114 | Developer view | `{"setSniRelay":{"host":"relay.example.com"\|null}}` | `null` |
| `jsonFfiCmd("terminateProcess")` (`UI/views/DeveloperView.tsx:144`) | Developer view | `{"terminateProcess":{}}` | `null` (the process exits after 3 s) |
| DevSendCommand (`UI/components/DevSendCommand.tsx:19-36`) | Developer raw console (default `{"getStatus": {}}`) | any | any |

### 1b. Native ("invoke") commands and how Android handles them

| UI function (`commands.ts` line) | Bridge JSON | Android handling (`AND/.../ui/bridge/WebCmd.kt`) | Rust it maps to |
|---|---|---|---|
| `osStatus()` 88-90 | `{"getOsStatus":{"knownVersion":…}}` | `GetOsStatus` 114-120. Long-poll on `OsStatusManager` (`AND/.../ui/OsStatusManager.kt:87-94`) | none |
| `connect(exit)` 150-157 | `{"startTunnel":{"tunnelArgs":"{\"exit\":{\"city\":{\"country_code\":\"us\",\"city_code\":\"nyc\"}}}"}}` (`tunnelArgs` is a **stringified** `TunnelArgs`) | `StartTunnel` 237-251, then `VpnPermissionRequestManager.requestVpnStart`, then the service | `{"setTunnelArgs":{"args":{"exit":{…}},"active":true}}` (`AND/.../services/ObscuraVpnService.kt:368-389`) |
| `disconnect()` 159-161 | `{"stopTunnel":{}}` | `StopTunnel` 253-263, then `binder.stopTunnel()` | `{"setTunnelArgs":{"args":null,"active":false}}` |
| `setNavigationView(v)` 92-94 | `{"setNavigationView":{"view":"developer"}}` | selects the bottom-nav tab, or sets `navigationView` if the view has no tab (`ObscuraUI.kt:220-227`) | none |
| `setColorScheme(v)` 120-122 | `{"setColorScheme":{"value":"light"\|"dark"\|"auto"}}` | stores pref and sets `AppCompatDelegate` night mode (`AND/.../activities/MainActivity.kt:68-79`) | none |
| `debugBundle(fb)` 163-165 | `{"debugBundle":{"userFeedback":"…"}}` | `DebugBundle` 58-97: sets `debugBundleStatus.inProgress`, runs ManagerCmd, stores `latestPath` | `{"createDebugBundle":{"userFeedback":"…","bundleInfo":{…},"androidCacheDir":"<cacheDir>"}}` (returns the path string) |
| `shareDebugBundle(p)` 176-178 | `{"shareDebugBundle":{"path":…}}` | Android share sheet via FileProvider | none |
| `emailDebugBundle(p,s,b)` 171-173 | `{"emailDebugBundle":{"path","subject","body"}}` | share intent with email extras | none |
| `playPurchaseSubscription(code)` 332-334 | `{"purchaseSubscription":{"promoCode":"…"\|null}}` | `apiGoogleBillingDetails`, then the Play Billing flow, then a token upload; returns `true\|false` (`WebCmd.kt:134-160`) | `{"apiGoogleBillingDetails":{"promoCode":…}}`, `{"apiGoogleAssociateAccount":{"purchaseToken","promoCode"}}` |
| `revealItemInDir` 167-169 | `{"revealItemInDir":{"path"}}` | throws `errorUnsupportedOnOS` | none |
| `checkForUpdates`, `installUpdate`, `refreshLoginItemStatus`, `registerAsLoginItem`, `unregisterAsLoginItem`, `setStrictLeakPrevention`, `getSubscriptionProduct`, `associateAccount`, `restorePurchases`, `showOfferCodeRedemption`, `resetOfferCodeRedemptionSuccess`, `resetUserDefaults`, `sendNotification`, `restartService`, `linuxAddOperator`, `restartApp`, `windowsFix` | n/a | **Not implemented on Android.** The UI avoids calling most of them on handheld; `resetUserDefaults` from the Developer view would fail | Apple, Windows or Linux only |

### 1c. Error code -> string mapping

- **[V] JSON-FFI errors** reject with `ManagerCmdErrorCode` (camelCase) (`RL/manager_cmd.rs:43-58`): `apiAssociateAccountConflict, apiError, apiInvalidAccountId, apiNoLongerSupported, apiRateLimitExceeded, apiSaleNotFound, apiSignupLimitExceeded, apiUnreachable, configSaveError, notLoggedIn, other`. The UI shows `t("ipcError-" + code)` (`UI/bridge/commands.ts:37-49`, strings `UI/translations/en.json:199-232`).
- **[V] Android native rejects** use `ErrorCodeException` codes (`AND/.../client/ErrorCodeException.kt:10-34`): `apiAssociateAccountConflict, apiRateLimitExceeded, other, playServicesDisabled, playServicesMissing, playServicesUpdateRequired, playServicesUpdating, purchaseFailed, purchaseFailedAlreadyOwned, errorLegacyAlwaysOn, errorOtherAppAlwaysOn, errorPermissionNotGranted, errorUnsupportedOnOS`.
- **[V] Connect errors.** The `startTunnel` rejection code and `vpnStatus.connecting.connectError` both render as `t("vpnError-" + code, {context: PLATFORM})` (`UI/components/VpnErrorFmt.tsx:4-10`). `ConnectErrorCode` values (`RL/errors.rs:25-37`): `accountExpired, apiError, apiRateLimitExceeded, apiUnreachable, invalidAccountId, noInternet, noLongerSupported, noRelay, noSlotsLeft, notLoggedIn, other`. Strings are at `en.json:389-409`. `vpnError-other_android` does not exist, so Android falls back to `vpnError-other`, which mentions the "status menu, Help tab, or About tab".
- **[V] Exceptions.** `tunnelNotDisconnected` is silently ignored (`UI/App.tsx:89`). `accountExpired` triggers an account refresh (`UI/App.tsx:86-88`).

### 1d. Status shapes the UI consumes

- **[V] `Status`** (`RL/manager.rs:56-74`, camelCase): `version, vpnStatus, accountId, inNewAccountFlow, pinnedLocations, lastChosenExit, lastExit, apiUrl, account{account_info,last_updated_sec}|null, autoConnect, featureFlags{quicFramePadding?,killSwitch?,forceSmallMtu?,tcpTlsTunnel?}, featureFlagKeys[], useSystemDns, localNetworkAccess, tailscaleBypass, wireguardBypass, dnsContentBlock`. The TS `AppStatus` omits `lastExit` (`UI/common/appContext.ts:195-212`).
- **[V] `vpnStatus`** (`RL/manager.rs:118-135`):
  - `{"connecting":{"tunnelArgs":{"exit":…},"connectError":"<ConnectErrorCode>"|null,"reconnecting":bool}}`
  - `{"connected":{"tunnelArgs","exit":OneExit,"relay":OneRelay,"clientPublicKey","exitPublicKey","transport":"quic"|"tcpTls"}}`
  - `{"disconnected":{}}`
- **[V] `ExitSelector`** (`RL/exit_selection.rs:11-23`): `{"any":{}}`, `{"exit":{"id":"…"}}`, `{"country":{"country_code":"us"}}`, `{"city":{"country_code":"us","city_code":"nyc"}}`. **The UI only ever sends `any` and `city`.**
- **[V] `OneExit`** (`API/types.rs:519-538`): `id, country_code, city_code, city_name, provider_id, provider_url, provider_name, provider_homepage_url, datacenter_id, tier`.
- **[V] `OsStatus` on Android** (`AND/.../ui/OsStatus.kt:6-73`, built in `OsStatusManager.kt:55-85`): `version, internetAvailable, osVpnStatus ("disconnected"|"connecting"|"connected" only), srcVersion, navigationView, colorScheme, updaterStatus{type:"uninitiated"}, debugBundleStatus{inProgress,latestPath,inProgressCounter}, canSendMail:true, loginItemStatus:null, playBilling (true only for the "play" flavor), privateDnsActive`. `osVpnStatus` is derived from rust `vpnStatus` (`ObscuraUI.kt:147-155`), so `reasserting/disconnecting/invalid` never occur on Android.

---

## 2. Feature inventory (complete, user-facing)

| # | Area | Feature | Where | Command(s) | Platforms |
|---|---|---|---|---|---|
| 1 | Boot | Splash: app icon 64px, wordmark, "Loading initial app status" + bars loader. Debug-bundle button appears after 5 s, or at once when the service is degraded | `UI/views/SplashScreen.tsx:18-40`, gate `UI/App.tsx:342-345` | none | all |
| 2 | Boot | Splash is held until the trial flag resolves, so the login button label does not flash | `UI/App.tsx:341-342` | `apiServiceStatus` | all |
| 3 | Onboarding | Log in with a 20-digit account number. Live format `XXXX - XXXX - XXXX - XXXX - XXXX`, numeric keyboard, `autoComplete=username` (works with password managers), Verhoeff validation errors, 10 s timeout | `UI/views/LogInView.tsx:55-95,352-389`, `UI/common/accountUtils.ts:4-128` | `setInNewAccountFlow{false}`, `login{validate:true}` | all |
| 4 | Onboarding | Create account / Start Trial. The number is generated **client-side** (19 random digits via CSPRNG with rejection sampling + Verhoeff check digit), then `login` creates it server-side | `UI/views/LogInView.tsx:97-114`, `UI/common/accountUtils.ts:54-69` | `setInNewAccountFlow{true}`, `login{validate:true}` | all |
| 5 | Onboarding | New-account screen: "slot machine" rolling-digit animation, Copy Account Number, warning text, "Proceed to Payment" (disabled until copied, or screenshot on iOS), confirmation sheet, then the payment sheet or web pay link, then "Payment" + "Done". Also "Want to use an existing account instead? Go back" | `UI/views/LogInView.tsx:164-283` | `logout`, `setInNewAccountFlow{false}` | all |
| 6 | Onboarding | Legal notice link to `https://obscura.com/legal`. "Experiencing issues? Submit a debug bundle" label | `UI/views/LogInView.tsx:132-148`, `UI/components/DebugBundle.tsx:78-103` | `debugBundle` | all |
| 7 | Connection | Big status screen: mascot, title, subtitle, primary button (Quick Connect / Cancel Connecting / Manage Account), location combobox + Connect/Disconnect/Cancel, "Check my Connection" link (`https://obscura.com/check`), path diagram pill | `UI/views/ConnectionView.tsx:51-143` | `startTunnel{any}` / `{city}`, `stopTunnel` | all |
| 8 | Connection | Quick Connect (`{"any":{}}`). Server picks the exit | `ConnectionView.tsx:172-180` | `startTunnel` | all |
| 9 | Connection | City connect from the combobox: Pinned group first, then continent groups, "Last chosen" chip, "Connected" label, pin icon | `ConnectionView.tsx:434-581,641-737` | `startTunnel{city}` | all |
| 10 | Connection | Path diagram: Your device -> Obscura -> Blind Relay -> Internet, with pulsing progress segments while connecting, red "Obscura not connected" when off, red "No internet connection" when offline | `ConnectionView.tsx:183-300` | none | all |
| 11 | Connection | First-ever connection shows a celebration mascot (cookie `connected-before`) | `ConnectionView.tsx:369-397,425` | none | all |
| 12 | Connection | Lock-in promo banner + popup ("Break out of your VPN lock-in.", link `https://obscura.com/account/nnn/`). Shown when `accountInfo.nnn != null`. Dismissal is stored per account id in cookies | `UI/components/LockInPromo.tsx:22-150` | none | all |
| 13 | Location | Status card: shield icon + "Connected to {city}" / "Disconnected" / "Offline" / "{state}...", subtitle, Quick Connect / Disconnect / Cancel Connecting button | `UI/views/LocationView.tsx:307-403` | `startTunnel{any}`, `stopTunnel` | all |
| 14 | Location | Current Session timer `hh:mm:ss` from `trafficStats.connectedMs` (1 s poll) | `LocationView.tsx:405-421`, `UI/common/utils.ts:187-197` | `getTrafficStats` | all |
| 15 | Location | Server info: provider ("Secured by {provider}" / "VPN"), Node = `provider_id` + city/country, exit WireGuard public key (monospace), "Matches published key for {provider_id}" link to `provider_url`. Bottom drawer on handheld | `LocationView.tsx:423-539` | none | all |
| 16 | Location | Search (case- and diacritic-insensitive) over city name, city code, country code, country name | `LocationView.tsx:102-112`, `UI/common/utils.ts:204-217` | none | all |
| 17 | Location | Sections: Last chosen (green heading), Pinned, then by continent. Sort: continent rank NA, EU, SA ("Latin America", MX forced to SA), AS, AF, OC, AN; then country name, city name, id | `LocationView.tsx:114-174`, `UI/common/exitUtils.ts:6-31`, `UI/common/api.ts:17-20` | none | all |
| 18 | Location | Pin / unpin per city (orange gradient pin when pinned) | `LocationView.tsx:70-92,253-255` | `setPinnedExits` | all |
| 19 | Location | Tap a card to connect or switch city. While connected, a yellow toast "Now connecting to {city}" shows (15 s) | `LocationView.tsx:43-68` | `startTunnel{city}` | all |
| 20 | Location | Empty exit list: "No exit servers found" + "Fetch list of exits" | `LocationView.tsx:262-305` | `refreshExitList{freshness:0}` | all |
| 21 | Account | Status card, 7 variants: info unavailable, expired, top-up + subscription, subscription active (renews), subscription paused, expiring soon (<10 days; <5 is "very soon"; switches to hours/minutes), paid up | `UI/views/AccountView.tsx:108-294` | `apiGetAccountInfo` | all |
| 22 | Account | Refresh link (loader for at least 400 ms) | `AccountView.tsx:336-364` | `apiGetAccountInfo` | all |
| 23 | Account | Manage Payments: bottom sheet on handheld, web link on desktop | `AccountView.tsx:392-411`, `UI/components/PaymentManagementSheet.tsx` | see §5 | all |
| 24 | Account | Account Number card: masked `XXXX - XXXX - XXXX - XXXX - XXXX`, Reveal/Hide, Copy | `UI/components/AccountNumberSection.tsx:13-73` | none | all |
| 25 | Account | WireGuard® Configurations: "Manage Configurations" opens `https://obscura.com/account/tunnels#account_id=<id>` | `AccountView.tsx:366-378`, `UI/common/accountUtils.ts:146-148` | none | all |
| 26 | Account | Delete Account (confirmation text, credit-loss warning) | `AccountView.tsx:55-79,380-390` | `apiDeleteAccount`, then `stopTunnel` + `logout` | all |
| 27 | Account | Log out (confirmation; mentions disconnect + preference reset when connected) | `AccountView.tsx:42-53,80-93,413-423` | `stopTunnel`, `logout` | all |
| 28 | Settings | General: "Automatically connect at launch" (+ behaviour description). Login-item switch on desktop only | `UI/views/Settings.tsx:97-164` | `setAutoConnect` | all (login item: desktop) |
| 29 | Settings | DNS Settings: 6 blocklist checkboxes + help link `https://obscura.com/knowledge/dns-options`. Android: no system-DNS option, and a warning Alert when Android Private DNS (strict) is on | `Settings.tsx:38-95` | `setDnsContentBlock` (`setUseSystemDns` not on Android) | all |
| 30 | Settings | Experimental (accordion): one switch per `featureFlagKeys`. Android shows `quicFramePadding`, `forceSmallMtu`, `tcpTlsTunnel`; `killSwitch` is Apple-only | `Settings.tsx:215-249,369-415` | `setFeatureFlag` | all |
| 31 | Settings | Network: Rotate WireGuard® key (button turns teal "Rotated" ✓ for 2 s); Allow local network access (Android/Linux) | `Settings.tsx:166-213,351-367` | `rotateWgKey`, `setLocalNetworkAccess` | all / Android+Linux |
| 32 | Settings | Appearance: Light / Dark / System tiles (sun, moon, half-circle icons) | `Settings.tsx:251-279,417-421` | native `setColorScheme` | all |
| 33 | About | App icon 120px, wordmark, version (`srcVersion`). Tap version 5× to open Developer view. Debug bundle card + socials on handheld. Copyright, "visit obscura.com", Open Source Licenses modal, ToS & Privacy link, WireGuard trademark notice | `UI/views/About.tsx:22-104` | `setNavigationView{developer}` | all (updates: macOS/Windows) |
| 34 | Help | Thinking mascot, "Having trouble?", support mailto, debug bundle with feedback, socials (Discord, Matrix, X). On Android this route maps to the About tab | `UI/views/HelpView.tsx:11-26`, `UI/components/Socials.tsx` | `debugBundle` | desktop route |
| 35 | Debug | Debug bundle: feedback textarea, Continue, progress ("This will take a few minutes."), then Share Bundle / Email Bundle to `support@obscura.net`, subject "Help needed for Obscura VPN (Android, {version})" | `UI/components/DebugBundle.tsx:25-194`, `UI/common/useMailto.ts` | `debugBundle`, `shareDebugBundle`, `emailDebugBundle` | all |
| 36 | Developer | App/Os status JSON, Reset UserDefaults, Set Backend URL (prod `https://v1.api.prod.obscura.net/api`, staging `https://v1.api.staging.obscura.net/api`, `http://localhost:8080/api`, null, + custom), Alternate API host, Relay SNI, traffic stats JSON, raw JSON command console, `setInNewAccountFlow`, cookies, localStorage, kill tunnel manager process, simulate errors | `UI/views/DeveloperView.tsx:16-160`, `UI/components/DevSetApiUrl.tsx:8` | `setApiUrl`, `setApiHostAlternate`, `setSniRelay`, `terminateProcess`, any | all |
| 37 | Apps | QR codes + store badges for iOS/Android downloads | `UI/views/Apps.tsx` | none | Windows (desktop) only |
| 38 | Degraded service | Linux/Windows service-degraded screens with fix buttons | `UI/views/LinuxServiceDegraded.tsx`, `WindowsServiceDegraded.tsx` | `restartService`, `windowsFix`, … | desktop only |
| 39 | Error fallback | "Fatal Error While Rendering" with Refresh (`#ff5f25` button). The reset navigates to Help or Connection | `UI/views/render-fallbacks/FallbackAppRender.tsx:5-17`, `UI/App.tsx:277-290` | `setNavigationView` | all |
| 40 | Android native (outside the web UI) | Foreground notification "Status: Connected/Connecting…/Disconnected" with actions Disconnect or Quick Connect, and Location. Deep links `obscuravpn:///<view>` and `obscuravpn:///<PATH_REQUEST_VPN_START>`. Auto-connect on fresh launch if `autoConnect`. No Quick Settings tile, no boot receiver | `AND/.../services/NotificationManager.kt:84-130`, `ObscuraUI.kt:146-168,229-248`, `AND/AndroidManifest.xml:42-48` | `setTunnelArgs` | Android |

### Not present in the UI at all [V]

No referral UI, no Lightning, Bitcoin or Monero in-app payment, no speed/throughput display (except on the macOS status item), no entry-relay picker, no country-level connect, no specific-server connect, no transport indicator, no split tunnelling, no map/globe. `map.svg` and `map-marker.svg` exist in `UI/res` but nothing imports them (0 references).

---

## 3. Flow descriptions

### 3.1 App boot / gating (`UI/App.tsx:338-371`)

1. While `appStatus === null || osStatus === null`, or the service is degraded, or (`showLogin && trialAvailable === null`), the Splash shows.
2. `isLoggedIn = !!appStatus.accountId`. `showLogin = !isLoggedIn || appStatus.inNewAccountFlow`. When `showLogin`, the LogIn view renders and the native bottom nav is hidden.
3. Otherwise the AppShell renders the route from `osStatus.navigationView` (default `/` = Connection).

### 3.2 Login (`UI/views/LogInView.tsx:55-95`)

1. Parse and validate the input. Errors: `accountIdError-tooShort|tooLong|invalidChecksum` (`en.json:40-42`).
2. Call `setInNewAccountFlow(false)`, then `login(id, validate=true)`. Rust `login` calls `acquire_auth_token`, follows up to 10 URL overrides, then stores the id and token (`RL/manager.rs:215-236`).
3. Success has no explicit UI step: the next `getStatus` with an `accountId` swaps the view.
4. On failure a red toast "Error Logging In" shows with `ipcError-*`. If 10 s pass with no answer, a toast says `loginError-unknown`.

### 3.3 Create account / trial (`LogInView.tsx:97-283`)

1. The button reads "Start Trial" if `apiServiceStatus.free_trial.available`, else "Create an Account".
2. `generateAccountNumber()` (client-side), `setInNewAccountFlow(true)`, then `login(newId, true)`.
3. An API error shows in a red card. `apiSignupLimitExceeded` stays for 12 h, other errors for 9 s.
4. AccountGeneration screen: the digits roll twice (spring 900 ms each). After that the Copy button + "Please write down your account number or store it in an encrypted vault" fade up (600 ms).
5. "Proceed to Payment" is enabled after copy. A confirm sheet ("Please confirm that you have stored your account number somewhere secure") leads to "Continue to payment", which opens the PaymentManagementSheet on handheld.
6. Account is polled every 60 s in this flow. When `accountInfo.active` (trial or paid) or payment was pressed, "Payment" + "Done" show. "Done" calls `setInNewAccountFlow(false)` and the main app opens.
7. "Go back" calls `logout` + `setInNewAccountFlow(false)`.

### 3.4 Connect / disconnect state machine (UI-local `ConnectionInProgress` + rust `vpnStatus` + `osVpnStatus`)

`ConnectionInProgress = Connecting | Reconnecting | Disconnecting | "Changing Locations" | UNSET` (`UI/common/appContext.ts:233-240`).

- `tryConnect(exit)` (`UI/App.tsx:74-96`) sets `initiatingExitSelector`. The state is `ChangingLocations` if already connected, else `Connecting`. It then calls `startTunnel`. On error it shows the persistent red toast "Error Connecting" + `vpnError-<code>`, except for `tunnelNotDisconnected`.
- `disconnectFromVpn` (`UI/App.tsx:98-123`) sets `Disconnecting` and optimistically `vpnConnected=false`, then calls `stopTunnel`. On failure it re-reads `getStatus` and rolls back.
- `handleNewStatus` (`UI/App.tsx:140-180`):
  - `connected`: `vpnConnected=true`, state UNSET, hide the error toast. A stale `connected` that arrives during Disconnecting is ignored.
  - `connecting`: `vpnConnected=false`, state Reconnecting if `reconnecting`, else stays ChangingLocations or becomes Connecting. A `connectError` shows a red toast "Error" + `vpnError-<code>` (15 s, does not stop the attempt).
  - `osVpnStatus` (Android): `disconnected` resets everything (`UI/App.tsx:258-275`).
- `showOfflineUI = !internetAvailable && idle` (`UI/App.tsx:355`, `appContext.ts:272-279`).
- `CONNECT_REQUIRES_ONLINE` is false on Android, so connect buttons stay enabled offline (`UI/bridge/SystemProvider.tsx:37`).

**Connection screen text** (`UI/views/ConnectionView.tsx:86-114`):

| Condition (first match wins) | Title | Subtitle |
|---|---|---|
| account expired | "Your account has expired" | "Top-up or subscribe to continue using Obscura." |
| transitioning + Connecting/ChangingLocations | "Connecting to {city_name \| 'Obscura'}" | "Please wait a moment..." |
| transitioning + Reconnecting | "Reconnecting to Obscura" | "Please wait a moment..." |
| transitioning + Disconnecting | "Disconnecting" | "Please wait a moment..." |
| connected | "Connected to Obscura" | "Enjoy the free and open internet!" |
| offline | "Disconnected" | "Connect to the internet to enable Obscura" |
| account info not loaded | "Validating Account..." | (empty) |
| else | "Not connected to Obscura" | "Connect to enjoy seamless privacy protection" |

**Primary button** (`ConnectionView.tsx:148-181`):

- Connecting via Quick Connect: "Cancel Connecting" (disconnect style).
- Expired: "Manage Account" (deep link `obscuravpn:///account`).
- Disconnected with account loaded: "⚡ Quick Connect" (bolt-badge-automatic icon), width 320, size md.
- Otherwise the button is hidden. When connected, the disconnect button sits next to the city combobox: "Connect" / "Disconnect" / "Cancel" (`ConnectionView.tsx:600-626`).

**Divider caption above the combobox:** "Connected to" (green.8, weight 550) when connected, "or connect to" (gray) when idle (`ConnectionView.tsx:583-593`).

**Combobox preselection:** connected exit, else the city in transition, else `lastChosenExit` city, else the first pinned location (`ConnectionView.tsx:460-478`).

### 3.5 Location switching while connected

Tap another city: a yellow toast "Now connecting to {city}" (15 s, id `vpnDisconnectConnect`) and `startTunnel{city}` with state `ChangingLocations`. The status card shows a gray loader, "Changing Locations...", and "Traffic is suspended". The card being connected to animates with an orange "wave" gradient. On `connected` the toast is updated to green and closes after 1 s (`UI/App.tsx:151-156`).

### 3.6 Payments (handheld sheet, `UI/components/PaymentManagementSheet.tsx`)

- Title "Account Management". Rows:
  - **Account ID: full number formatted, NOT masked** (305-306).
  - Status Active/Inactive (green/red).
  - Top Up + Expiration Date.
  - Stripe sub: "Subscribed on Obscura.net", Status (`stripeStatus-*` colour-coded), Source (obscura.com host), Period Start/End, Cancel at Period End.
  - Apple sub (Apple only).
  - Google sub: status (`googleStatus-*`), Source "Play Store", Auto-Renewal, Renewal Date, plus "Subscribe In-app" / "Manage Subscription" (`https://play.google.com/store/account/subscriptions?sku=vpn_subscription_v1&package=net.obscura.vpnclientapp`) and a promo code field. Only when `osStatus.playBilling`.
- Web link: "Pay on obscura.com" (inactive) or "Manage on obscura.com" (active) opens `https://obscura.com/pay#account_id=<id>`. It shows only when there is no Apple/Google sub and (`externalPaymentsAllowed || active`). `externalPaymentsAllowed = storeKit?.externalPaymentsAllowed || osStatus.playBilling === false` (43), so it is always shown in the FOSS build.
- After a store purchase, a non-dismissable "Activating Subscription" sheet shows and the account is polled every 3 s until `active` (`UI/App.tsx:312,317-323`).
- **[V] All crypto/card payment methods are on the web page.** The UI has no Lightning or BTC invoice. `ManagerCmd` has no Lightning/Monero/Stripe commands (`RL/manager_cmd.rs:132-218`). The API crate has `cmd/lightning/{create,check}.rs`, `cmd/monero/{create,check}.rs`, `cmd/stripe/*`, `cmd/referrals.rs`, `cmd/prices.rs`, but rustlib does not wire them.

### 3.7 Debug bundle

The feedback modal ("Debug Bundle", label "Feedback", placeholder "Please describe the issue you are experiencing"), then "Continue", then `debugBundle(feedback)`. `osStatus.debugBundleStatus.inProgress` drives "This will take a few minutes." + a loader. When `latestPath` exists: "Share Bundle", "Email Bundle" (`emailBodyIntro` + feedback), and "Email isn't configured on your device." if `!canSendMail`.

### 3.8 Logout / delete

- Logout: `stopTunnel`, then `logout`, then `resetOfferCodeRedemptionSuccess` (iOS only). The prompt has a "connected" variant: "Logging out will **disconnect you** and **reset preferences**" (`en.json:274-275`).
- Delete: `apiDeleteAccount`, then the logout chain. Text: "Account deletion is permanent." / "You will lose your existing credit." / "If you log in again, a new account will be created with the same ID." (`en.json:115-118`).

---

## 4. Android-specific branches (`OBS_WEB_PLATFORM === 'android'`) [V]

- `UI/bridge/setupBridge.ts:14-23`: Android bridge (`obscuraAndroidCommandBridge.invoke`, `"android/"` message prefix).
- `UI/bridge/SystemProvider.tsx:29-38`: `IS_HANDHELD_DEVICE=true`, so:
  - bottom-sheet drawers replace modals (`UI/components/ConfirmationDialog.tsx:22-67`, top radius lg = 16px);
  - Switch size `lg`, Button size `md` (`UI/components/Mantine.tsx:34-56`);
  - server-info drawer;
  - debug bundle card with Share/Email;
  - About includes debug bundle + socials;
  - no update checks (`UI/App.tsx:332-336`);
  - scrollbar 2px.
- `HAS_NE_VPN_STATUS=true`; `CONNECT_REQUIRES_ONLINE=false`.
- Settings (`UI/views/Settings.tsx`):
  - no "Use system DNS" radio (42-43); the Android VpnService ignores it (`AND/.../services/ObscuraVpnService.kt:407-414`);
  - `androidPrivateDnsAlert` shown when `osStatus.privateDnsActive` (87-91);
  - Local network access switch (196-201);
  - Kill switch flag hidden (215-217); Strict leak prevention hidden (226-228).
- Payments: `osStatus.playBilling` gates Google Play (`PaymentManagementSheet.tsx:43,353-372`).
- Strings used only on Android: `vpnError-errorLegacyAlwaysOn`, `vpnError-errorOtherAppAlwaysOn`, `vpnError-errorPermissionNotGranted` (`en.json:394-396`), `ipcError-playServices*` (`en.json:225-228`), `androidPrivateDnsAlert` (`en.json:46`).
- Flags are emoji on Android. The SVG flags are Windows only (`UI/components/CountryFlag.tsx:4-16`).
- Safe-area insets are injected as CSS vars `--safe-area-inset-*` by native (`AND/.../ui/ObscuraWebView.kt:109-123`).
- Native Android theme: `AND/res/values/themes.xml`, `values-night/themes.xml`, `colors.xml` (see §6).

## 5. Apple-only and desktop-only items (skip or replace on Android) [V]

- **Apple:**
  - StoreKit: `getSubscriptionProduct`, `associateAccount`, `purchaseSubscription` (no promo), `restorePurchases`, `showOfferCodeRedemption`, `resetOfferCodeRedemptionSuccess`, `osStatus.storeKit`, `offerCodeRedemptionSuccess` (`UI/bridge/commands.ts:306-330`; `PaymentManagementSheet.tsx:160-238`).
  - `paymentSucceeded` window event (`UI/App.tsx:298-302`) and `screenshotDetected` event (`LogInView.tsx:184-192`). Android dispatches neither.
  - Strict Leak Prevention (`Settings.tsx:282-313`).
  - Kill-switch feature flag ("On demand kill switch"). On Android the equivalent is the OS "Always-on VPN" + "Block connections without VPN" setting.
  - "Use installed custom system DNS profile" (`dnsModeSystemApple`).
  - `pleaseCopyAccountNumber_iphoneos`, `findDebugBundleInFinder_*`, `deleteAccountConfirmationAppleSubscription`, `vpnError-other_iphoneos/_macosx`, `deviceOffline`, `timeout`, `tunnelConnectFailed`, `tunnelLimitExceeded`, `noNetworkAvailable` (NE-specific).
  - `mod+J` theme hotkey (`UI/App.tsx:43`).
  - App icon assets imported from `apple/client/Assets.xcassets/AppIcon.appiconset/icon_128x128.png` and `icon_512x512@2x@2x.png` (`LogInView.tsx:9`, `SplashScreen.tsx:3`, `About.tsx:7`).
  - The macOS status item shows live Upload/Download bandwidth from `getTrafficStats` deltas (1 s, same `connId`) (`apple/client/StatusItem/StatusMenu.swift:228-270`, `BandwidthStatus.swift:53+`).
- **Desktop:** login item, updater (`checkForUpdates`/`installUpdate`), `revealItemInDir`, Apps view, Linux/Windows degraded screens, Tailscale/WireGuard bypass (Linux), Help route as a separate tab, `setUseSystemDns` (Apple/Linux), `sendNotification` (Windows).

## 6. Rustlib supports it, UI ignores it (opportunities) and what to skip

**Supported by rustlib or the API, not surfaced in the UI [V]:**

- `ExitSelector::Country{country_code}` and `ExitSelector::Exit{id}`. Country-level connect and a specific exit server work with no Rust change (`RL/exit_selection.rs:11-34`). Each `OneExit` has `id`, `datacenter_id`, `tier`.
- `vpnStatus.connected.relay` (`OneRelay`: `id`, `country_code`, `city_code`, `city_name`, `preferred_exits`, `ip_v4`, `ports`, cert; `API/types.rs:486-516`). The UI never shows the entry relay.
  - **There is no ManagerCmd to choose the entry relay.** `TunnelArgs` has only `exit` (`RL/manager.rs:137-141`). The relay is picked by a handshake RTT race (`RL/relay_selection.rs` `race_relay_handshakes`). Choosing the entry relay would need a rustlib change **[I]**.
- `vpnStatus.connected.transport` (`quic` | `tcpTls`) and `clientPublicKey`. Not shown.
- `TrafficStats.txBytes/rxBytes/latestLatencyMs`. Only `connectedMs` is shown on mobile.
- `Status.lastExit`.
- `setApiHostAlternate`, `setSniRelay` (censorship-circumvention knobs). Developer view only.
- `getDebugInfo`, `ping`.
- `AccountInfo` fields the UI ignores (`API/types.rs:134-207`): `referral_code` (share URL `https://obscura.com/refer#{code}`), `free_month_on_next_payment`, `has_referrer`, `received_free_months`, `monero_pending_payments`, `primary_funding`, `has_paid`.
- `AccountId::hint()`: the first 3 digits + "…" is the sanctioned non-secret display form (`API/types.rs:66-85`).
- DNS content blocking only works when the exit provider is Mullvad. Rust then uses DNS `100.64.0.<bitset>` (bits: ad=1, tracker=2, malware=4, adult=8, gambling=16, socialMedia=32). Otherwise it uses the tunnel DNS (`RL/network_config.rs:80-90,116-123`). The UI does not explain this.

**A minimal client can skip [I]:** Lock-in (NNN) promo, Apps view, socials row, first-time celebration mascot, rolling-digits animation (keep a simple reveal), Developer view (keep a hidden "raw command" console if wanted), desktop items, Apple StoreKit, Google Play billing (FOSS build: web payment link only).

## 7. Upstream quirks and bugs to avoid [V]

1. **Unpin bug.** `toggleExitPin` filter compares `loc.country_code == loc.country_code` (always true), so unpinning removes every pinned entry with the same `city_code` in any country (`UI/views/LocationView.tsx:76-79`). Correct: `loc.country_code == exit.country_code`.
2. **Full account number in the payment sheet.** The sheet shows the full number under "Account ID" (`PaymentManagementSheet.tsx:305-306`), while the Account view masks it by default.
3. **Account id in URL fragments.** `payUrl`, `tunnelsUrl`, `subscriptionUrl` put the raw account id in the URL fragment (`UI/common/accountUtils.ts:138-148`). The fragment is not sent in HTTP, but the web page and the browser history see it.
4. **Hard-coded label.** "Account Number" is not localised (`UI/components/AccountNumberSection.tsx:25`).
5. **Typo.** `account-ExpiresOn_0`: "Your account wil expire today" (`en.json:18`).
6. **Missing Android string.** `vpnError-other` text references "status menu, Help tab" (no Android variant).
7. **Unused font import.** `@fontsource/open-sans` is imported but no style references "Open Sans" (`UI/Providers.tsx:1`).
8. **Unused assets.** `map.svg` (1.4 MB), `map-marker.svg`, `arrow.up.right.circle.svg` are not used.

---

## 8. Design language

### 8.1 Brand summary

The brand is a warm **orange-red pixel-art identity** (`#FF6025`) on neutral greys. The mascot is a pixel-art TV/robot head. Decoration is pixel "bit" patterns. The orange is used for CTAs (gradient buttons), connected state decoration, the active nav tint, and links. The UI chrome is otherwise neutral (Mantine grey/dark scales, v6 dark palette). Screens are generous, centered, single-column on mobile.

Screenshots: `UI/res/app-light.png` (iOS, connected) and `UI/res/app-dark.png` (Android, M3 bottom nav with pill indicator, dark `#303030` surface). Both are 640×1096.

- **Connected screen layout:** mascot top, "Connected to Obscura" (h2/600), subtitle (h4/350), green "Connected to" divider, outlined city combobox with emoji flag, red-tinted "Disconnect" button, underlined "Check my Connection ↗", dark pill path diagram near the bottom, orange pixel deco band behind the bottom nav.

### 8.2 Design tokens (light | dark)

Mantine primary is `orange` with default `primaryShade {light:6, dark:8}`. Text colours by name (e.g. `c='gray'`) resolve to `--mantine-color-<c>-text` = shade 6 (light) / shade 4 (dark). Explicit shades (`green.7`) are the same in both modes (MANTINE `get-css-color-variables.ts`).

| Token (name) | Light hex | Dark hex | Usage | Source |
|---|---|---|---|---|
| brand.orange (ObscuraOrange) | `#FF6025` | `#FF6025` | Brand, mascot body, deco, app icon bg, lock-in CTA text, gradient end | `apple/client/Assets.xcassets/ObscuraOrange.colorset/Contents.json` (255,96,37); deco/mascot SVGs |
| brand.gradient.start | `#FF7A49` | `#FF7A49` | Default Button `variant='gradient'`, 180° (top to bottom) | `UI/components/Mantine.tsx:97` |
| brand.gradient.end | `#FF6025` | `#FF6025` | same | `Mantine.tsx:97` |
| brand.launcherBg | `#F55D24` | `#F55D24` | Android adaptive icon background | `AND/res/values/ic_launcher_background.xml:3` |
| android.primary | `#DC4D26` (orange_600) | `#F56B39` (orange_400) | M3 `colorPrimary` (bottom nav etc.) | `AND/res/values/colors.xml:5-6`, `themes.xml:3`, `values-night/themes.xml:3` |
| android.onPrimary | `#FFFFFF` | `#000000` | M3 `colorOnPrimary` | themes.xml |
| android.primaryVariant | `#9F2D00` | `#9F2D00` | `colorPrimaryVariant` | colors.xml:7 |
| android.secondary | `#45556C` (slate_600) | `#90A1B9` (slate_400) | `colorSecondary` | colors.xml:8-9 |
| android.windowBg | `#FFFFFF` | `#303030` | window background | colors.xml:11-12 |
| mantine.primary.filled | `#FD7E14` (orange.6) | `#E8590C` (orange.8) | Progress sections, Switch on-track, Loader, connected card 2px border | MANTINE |
| mantine.primary.anchor/text | `#FD7E14` | `#FFA94D` (orange.4) | Links (Anchor), Refresh link, `c='orange'` | MANTINE |
| mantine.primary.light bg | rgba(253,126,20,0.10) | rgba(253,126,20,0.15) | `variant='light'` buttons (Rotate key etc.), selected appearance tile | MANTINE |
| mantine.primary.light text | `#FD7E14` | `#FFC078` (orange.3) | same | MANTINE |
| bg.body | `#FFFFFF` | `#313131` (custom dark.7) | page background, Paper, Drawer | MANTINE `--mantine-color-body`; custom dark scale `Mantine.tsx:101` |
| bg.body (alt rule) | `#F8F9FA` (gray.0) | `#303030` (dark.8) | `body` rule in `UI/App.module.css:33-35`. Probably overridden by Mantine global CSS, load-order dependent **[I]**; screenshots show white / ≈#303030 | App.module.css |
| bg.preRender | `#313131` | `#313131` | body before React mounts | `UI/index.css:3` |
| meta theme-color | `#1A1B1E` | `#1A1B1E` | HTML meta | `obscura-ui/index.html:5` |
| bg.login | `#FFFFFF` | `#303030` (dark.8) | login container and its section boxes | `UI/views/LoginView.module.css:32-34,53-56` |
| surface.card | `#FFFFFF` | `#353535` (dark.6) | Mantine Card | MANTINE Card.module.css |
| surface.elevated | `#FFFFFF` | `#353535` | `.elevatedSurface` (account cards, experimental accordion) | `UI/common/common.module.css:30-32` |
| surface.debugCard | `#F8F9FA` | `#353535` | debug bundle card | `UI/components/DebugBundle.module.css:1-3` |
| surface.notification | `#FFFFFF` | `#353535` | toasts (left accent bar = toast colour) | MANTINE Notification.module.css |
| surface.pathPill | `#303030` (dark.8) | `#353535` (dark.6) | path diagram pill. **Dark in both modes**, text and icons white | `UI/views/ConnectionView.module.css:31-36` |
| pathPill.track | `#4F5156` (dark.4) | `#5C5F66` (dark.3) | inactive progress segment | ConnectionView.module.css:38-41 |
| surface.lockIn | `#222528` | `#222528` | lock-in banner/popup bg (also dark.9) | `UI/components/LockInPromo.module.css:11,74` |
| lockIn.cta | bg `#FFFFFF`, text `#FF6025`, hover `#F2F2F2`, border rgba(255,255,255,.85) | same | promo CTA (6px radius, 590 weight) | LockInPromo.module.css:56-71 |
| text.primary | `#000000` | `#C1C2C5` (dark.0) | body text | MANTINE |
| text.dimmed | `#868E96` (gray.6) | `#909296` (dark.2) | secondary text | MANTINE |
| text.subtitle | `#4F5156` (dark.4) | `#909296` (dark.2) | connection subtitle | ConnectionView.module.css:63-65 |
| text.checkConnection | `#868E96` (gray.6) | `#ADB5BD` (gray.5) | "Check my Connection" | ConnectionView.module.css:67-69 |
| text.secondaryColor | `#495057` (gray.7) | `#CED4DA` (gray.4) | SecondaryButton, drawer meta | common.module.css:42-44 |
| text.gray (c='gray') | `#868E96` | `#CED4DA` | section headings, captions | MANTINE |
| wordmark.fill | `#000000` | `#CED4DA` (gray.4) | Obscura wordmark | common.module.css:38-40 |
| border.default | `#CED4DA` (gray.4) | `#4F5156` (dark.4) | inputs, default buttons | MANTINE |
| border.card | `#DEE2E6` (gray.3) | `#4F5156` | Card / Paper `withBorder` | MANTINE |
| border.switchTrack | `#DEE2E6` | `#4F5156` | Switch track border | `UI/components/Mantine.module.css:1-3` |
| button.default bg | `#FFFFFF` | `#353535` | `variant='default'` (city combobox target) | MANTINE |
| button.disabled | bg `#868E96`, border `#DEE2E6`, text `#FFFFFF` | bg `#5C5F66`, border `#4F5156`, text `#FFFFFF` | `.button` disabled override (Quick Connect etc.) | common.module.css:8-16 |
| button.disconnect | bg `#FFE3E3` (red.1), text `#F03E3E` (red.7) | same | `buttonDisconnectProps = {variant:'light', c:'red.7', bg:'red.1'}` for Disconnect / Cancel | `Mantine.tsx:105` |
| status.connected | `#37B24D` (green.7) | `#37B24D` | Location status title + shield, "Last chosen" heading, key-match text | `UI/views/LocationView.tsx:129,349,532` |
| status.connected.strong | `#2F9E44` (green.8) | `#2F9E44` | "Connected to" caption, "Connected" list label, About ✓ | ConnectionView.tsx:587,653 |
| status.notConnected | `#F03E3E` (red.7) | `#F03E3E` | Location status title + exclamation shield, logout/delete buttons | LocationView.tsx:350 |
| status.notConnected.line | `#FA5252` (red.6) | `#FA5252` | "Obscura not connected" line + label, offline wifi-off icon | ConnectionView.tsx:209-222 |
| status.changing | `#868E96` (gray) / loader `#ADB5BD` (gray.5) | `#CED4DA` / `#ADB5BD` | Changing Locations | LocationView.tsx:349-360 |
| status.chip (ObscuraChip "Last chosen"/"Connected") | text `#12B886` (teal.6), 1px border, radius 5px | text `#38D9A9` (teal.4) | non-clickable chip | `UI/components/ObscuraChip.tsx:9`, common.module.css:1-6 (border var has a typo, `var(-mantine-color-teal-1)`, so the border falls back to currentColor **[I]**) |
| success.teal | `#12B886` (teal.6) | `#099268` (teal.8) | "Copied" / "Rotated" button bg | MANTINE filled |
| hover.locationCard | `#F5F5F5` + teal.5 `#20C997` border | `#4F5156` (dark.4) | card hover | `UI/views/Location.module.css:6-15` |
| connecting.wave | gradient rgba(246,152,85,.487) → `#FFFFFF` → rgba(246,152,85,.487) | rgba(181,75,0,.75) → transparent → rgba(181,75,0,.75) | city card being connected to, `wave` 2s linear infinite | Location.module.css:63-85 |
| toast.error | red (`#FA5252` light / `#E03131` red.8 dark accent) | | `color:'red'` notifications | MANTINE filled |
| toast.switching | `#FFD43B` (yellow.4) | `#FFD43B` | "Now connecting to {city}" | LocationView.tsx:58 |
| alert.warning | orange light-variant | | Alerts (feature-flag warnings, Private DNS) | Settings.tsx |
| alert.danger | red light-variant | | strict-leak reliability warning | Settings.tsx:308 |
| alert.info | blue light-variant | | strict-leak tooltip | Settings.tsx:301 |
| link.blue | `#228BE6` (blue.6) | `#4DABF7` (blue.4) | "Go back", "Redeem Code" | MANTINE |
| scrollbar.thumb | `#5C5F66` | `#909296` | scrollbars | `UI/components/ScrollableView.module.css:1-5,19-21` |
| deco.disconnected | `#C7CCCE` | `#454545` | bottom pixel band, idle | `UI/res/deco/deco-disconnected-{light,dark}.svg` |
| deco.connecting | `#DEE1E2` + `#FF6025` | `#454545` + `#FF6025` | 3-frame animated band | `deco-connecting-{light,dark}-{1,2,3}.svg` |
| deco.connected | `#FF6025` | `#FF6025` | band when connected | `deco-connected.svg` |
| deco.offline | `#C7CCCE` | `#575757` | band when offline (heavier pattern) | `deco-offline-{light,dark}.svg` |
| mascot.body | `#FF6025` | same | TV/robot head | `UI/res/mascots/*.svg` |
| mascot.shade | `#FFC7B2` | same | peach shading | mascots |
| mascot.highlight | `#FFEBE3` | same | top-left highlight | mascots |
| mascot.screen | `#F6F8F7` | same | face screen | mascots |
| mascot.outline/eyes | `#000000` | same | outline, eyes, mouth | mascots |
| mascot.darkShade | `#D54D1A` | same | thinking mascot, hat, jail deco | thinking-mascot.svg, hat.svg |
| mascot.validating | `#CCCCCC` | same | "…" dots on validating mascot | validating-mascot.svg |
| celebration confetti | `#FF678E` `#F9D952` `#5279F8` `#A04AD3` `#D081F4` | same | first-time connected mascot | connected-first-time-mascot.svg |
| badge.paidUp / subscriptionActive | `#2FA73B` | same | account status badges (36px, radius 8) | paid-up.svg, subscription-active.svg |
| badge.expiringSoon | `#F5BF00` | same | <10 days | paid-up-expiring-soon.svg |
| badge.expired / expiringVerySoon | `#D80027` | same | expired, <5 days | account-expired.svg, paid-up-expiring-very-soon.svg |
| badge.paused | `#FF6025` | same | subscription paused | subscription-paused.svg |
| badge.topupPlusSub | `#C4C4C4` + `#2FA73B` | same | paid up + subscription queued | paid-up-subscription-active.svg |
| icon.happy (path diagram) | white rect + `#222528` features | same | 18×16 "Obscura" node | obscura-icon-happy.svg |
| social.discord | `#5865F2` | same | Discord icon | `UI/components/Socials.tsx:16` |
| fatal.refresh | `#FF5F25` | same | error-fallback button | FallbackAppRender.tsx:13 |

Custom dark scale replacing Mantine v8 dark (`Mantine.tsx:101`): `dark.0 #C1C2C5, dark.1 #A6A7AB, dark.2 #909296, dark.3 #5C5F66, dark.4 #4F5156, dark.5 #393939, dark.6 #353535, dark.7 #313131, dark.8 #303030, dark.9 #222528`.

Mantine v8 default scales used by name (MANTINE `default-colors.ts`):

- `orange`: `#fff4e6 #ffe8cc #ffd8a8 #ffc078 #ffa94d #ff922b #fd7e14 #f76707 #e8590c #d9480f`
- `red`: `#fff5f5 #ffe3e3 #ffc9c9 #ffa8a8 #ff8787 #ff6b6b #fa5252 #f03e3e #e03131 #c92a2a`
- `green`: `#ebfbee #d3f9d8 #b2f2bb #8ce99a #69db7c #51cf66 #40c057 #37b24d #2f9e44 #2b8a3e`
- `teal`: `#e6fcf5 #c3fae8 #96f2d7 #63e6be #38d9a9 #20c997 #12b886 #0ca678 #099268 #087f5b`
- `gray`: `#f8f9fa #f1f3f5 #e9ecef #dee2e6 #ced4da #adb5bd #868e96 #495057 #343a40 #212529`
- `yellow`: `#fff9db #fff3bf #ffec99 #ffe066 #ffd43b #fcc419 #fab005 #f59f00 #f08c00 #e67700`
- `blue`: `#e7f5ff #d0ebff #a5d8ff #74c0fc #4dabf7 #339af0 #228be6 #1c7ed6 #1971c2 #1864ab`

### 8.3 Typography [V]

- Body: system stack `-apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, Roboto, Helvetica, Arial, sans-serif, Apple Color Emoji, Segoe UI Emoji` (`UI/components/Mantine.tsx:22`). **This is Roboto on Android.**
- Monospace: `source-code-pro, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, Liberation Mono, Courier New, monospace` (`Mantine.tsx:23`). Used for the account number and exit public key.
- Display/pixel font: **Press Start 2P** (`@fontsource/press-start-2p`), only for lock-in promo titles (`LockInPromo.module.css:36,165`): 12–14px banner, 17–24px popup, weight 400, line-height 1.55–1.75.
- Scale (Mantine defaults): `xs 12, sm 14, md 16, lg 18, xl 20` px. Headings: h1 34/1.3, h2 26/1.35, h3 22/1.4, h4 18/1.45, all weight 700 by default.
- Connection screen overrides: title h2 weight 600; subtitle h4 weight 350. Other weights used: 500, 550, 590, 600, 700.
- Tabular numerals for the rolling account digits (`LoginView.module.css:1-12`).

### 8.4 Spacing, radius, elevation [V]

- Spacing (Mantine): `xs 10, sm 12, md 16, lg 20, xl 32` px. `<Space>` default height `sm` (12).
- Page padding: Settings 20px top, 40px bottom, 60px sides (20px under 36em) (`UI/views/Settings.module.css`). Account 20px/60px (20px mobile). Location 20px/75px (20px sides + 70px bottom on mobile). Help 60px left, 40px top.
- Breakpoints: `xs 36em (576px)`, `sm 48em`, `md 62em`, `lg 75em`, `xl 88em` (`obscura-ui/postcss.config.js`).
- Radius: Mantine `xs 2, sm 4 (default), md 8, lg 16, xl 32` px.
  - Buttons, Modals, Accordions default to `md` (8px) (`Mantine.tsx:52,59,86`).
  - Cards `md`. Path pill `lg` (16px). Drawers top `lg`. Chip 5px. Lock-in CTA 6px. Lock-in modal 32px (desktop) / 24px top (mobile). Badges ≈8–9.5px on 32–35px squares.
- Shadows: Mantine `xs`, `sm`, `md`, `xl` (soft rgba(0,0,0,.05/.04) multi-layer). The path pill uses `shadow='xl'`. Cards `xs`–`sm`.
- Fixed widths: primary button 320px (`ConnectionView.tsx:49`), city right button min 130px, status-card button min 190px, mascot max-width 90px, path pill 80% (85% under xs) max 600px.

### 8.5 Iconography [V]

- `react-icons`, mostly Bootstrap (`Bs*`), Ionicons 5 (`Io*`/`Io5`), Material (`Md*`), FontAwesome 6, Simple Icons.
- Key glyphs:
  - shield-check / shield-exclamation (status): `BsShieldFillCheck`, `BsShieldFillExclamation`
  - pin / pin-fill: `BsPin`, `BsPinFill`
  - search: `BsSearch`
  - chevron: `BsChevronDown`, rotated 200ms
  - laptop "Your device": `MdLaptopMac`
  - globe "Internet": `MdLanguage`
  - eye-off "Blind Relay": `IoIosEyeOff`
  - wifi-off: `MdOutlineWifiOff`
  - logout: `IoLogOutOutline`
  - delete: `IoNuclear`
  - copy: `IoCopy`, `FaCopy`
  - external link: `FaArrowUpRightFromSquare`
  - refresh: `FaRotateRight`
  - appearance: `IoSunnySharp`, `IoMoon`, `BsCircleHalf` (rotated 180°)
  - DNS: `MdBlock`
  - help: `IoHelpCircleOutline`
  - warning: `MdWarning`
  - share / mail: `IoIosShare`, `IoIosMail`
  - promo tag: `IoMdPricetag`
  - back to top: `IoArrowUp`
- SF-Symbol-derived SVGs: `bolt.badge.automatic.fill.svg` (Quick Connect, white), `eye.fill.svg`, `eye.slash.fill.svg`, `checkmark.circle.fill.svg`, `person.badge.key.svg` (account number).
- Android bottom-nav icons are Material Symbols vectors (viewport 960, fill `#000`, tinted by M3): `AND/res/drawable/icon_{connection,location,account,settings,about}.xml`.

### 8.6 Logo / assets [V]

- **App icon**: `#FF6025` rounded square with a pixel-art smiling TV-robot (antennae, peach shading). Files: `apple/client/Assets.xcassets/AppIcon.appiconset/icon_*.png`, plus `Icon 04 1024w.png`. Android uses an adaptive icon with background `#F55D24` and a `@mipmap/ic_launcher_foreground` foreground (`AND/res/drawable/ic_launcher.xml`).
- **Wordmark** `UI/res/obscura-wordmark.svg`: 127×27, blocky geometric "obscura", fill inherits (black / gray.4). Rendered at width 150.

```svg
<svg width="127" height="27" viewBox="0 0 127 27" fill="none" xmlns="http://www.w3.org/2000/svg">
<path fill-rule="evenodd" clip-rule="evenodd" d="M109.827 13.6818V8.66624L126.108 8.68357V27.0006H109.899V15.8486H120.936V13.6818H109.827ZM120.936 19.7778H115.071V22.0602H120.936V19.7778Z"/>
<path d="M100.336 26.9718H95.0489V8.68357H107.126V13.6818H100.336V26.9718Z"/>
<path d="M91.8708 27.0006H75.6625V8.68357H80.8341V22.0602H86.6992V8.68357H91.8708V27.0006Z"/>
<path d="M72.9756 26.9718H59.5698V8.68357H72.9756V13.6818H64.7414V22.0602H72.9756V26.9718Z"/>
<path d="M56.3628 27.0006H41.9747V22.0602H51.1045V19.72H41.9747V8.71246H56.3628V13.6818H47.233V15.9064H56.3628V27.0006Z"/>
<path fill-rule="evenodd" clip-rule="evenodd" d="M39.0855 27.0006H21.6927V0.333984H26.951V8.71246H39.0855V27.0006ZM26.951 13.6818V22.0602H33.9139V13.6818H26.951Z"/>
<path fill-rule="evenodd" clip-rule="evenodd" d="M0.890625 27.0006V8.71246H18.2835V27.0006H0.890625ZM6.14893 13.6818V22.0602H13.1118V13.6818H6.14893Z"/>
</svg>
```

- **Glyph** `UI/res/obscura-icon-happy.svg` (18×16): white rounded rect (rx 2.14) on y 2.34–15.19, two white antennae, `#222528` eyes (2 tall bars), cheeks and a smile bar. This is the small "Obscura" node icon in the path pill.

```svg
<svg width="18" height="16" viewBox="0 0 18 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect x="0.582031" y="2.33594" width="17.1429" height="12.8574" rx="2.14288" fill="white"/>
<path d="M3.80469 4.47852H5.41183V7.69287H3.80469Z" fill="#222528"/>
<path d="M3.80469 0.193359H5.41183V3.40772H3.80469Z" fill="white"/>
<path d="M12.9043 0.193359H14.5114V3.40772H12.9043Z" fill="white"/>
<path d="M12.9043 4.47852H14.5114V7.69287H12.9043Z" fill="#222528"/>
<path d="M4.85938 9.83887H6.46652V11.446H4.85938Z" fill="#222528"/>
<path d="M11.832 9.83887H13.4392V11.446H11.832Z" fill="#222528"/>
<path d="M6.48242 11.4414H11.8396V13.0486H6.48242Z" fill="#222528"/>
</svg>
```

(The paths are simplified to equivalent rectangles. The original uses many collinear points; geometry is identical **[V by inspection]**.)

- **Mascots** (`UI/res/mascots/`): `not-connected` (65×65, straight mouth), `connecting-1..4` (dots "…" animation frames), `connected` (200×200, smiling ^ ^ eyes), `connected-first-time` (confetti), `validating` (grey dots), `dead` (× × eyes; offline/expired), `thinking` (Help), `hat` (Apps view).
- **Decorations** (`UI/res/deco/`): pixel "bit" bands 660×132 anchored at the bottom (`preserveAspectRatio="xMidYMax meet"`, `position:fixed; inset:0; z-index:-1`, `ConnectionView.tsx:357-359`, `ConnectionView.module.css:15-22`):
  - `deco-orange-top.svg` (649×91) is the top of the desktop login.
  - `deco-signup-mobile.svg` (320×141) is the bottom of the mobile login.
  - `deco-bit-accent.svg` (462×208) is the accent for Apps and the lock-in popup.
- **Lock-in**: `UI/res/lockin/wordmark.svg` (white blocky wordmark), `jail-deco.svg`.
- **Map/globe**: none used. `map.svg` is a 650×213 dotted world map (5382 dots `#E4E4E4`) and `map-marker.svg` uses `#17B26A`. Both are unused, but they could inspire a minimal dotted-map visual **[S]**.

### 8.7 Animations and timings [V]

| Element | Behaviour | Timing | Source |
|---|---|---|---|
| Deco band while transitioning | cycles frames [0,1,2,2] of 3 connecting SVGs; reversed when disconnecting | 500 ms/step (750 ms when disconnecting) | `ConnectionView.tsx:308-360` |
| Mascot while connecting | frames [0,1,2,3,3,3,3] over 4 "connecting" SVGs | 120 ms/step | `ConnectionView.tsx:375-432` |
| Path pill pulsing | 2 progress bars of 4 sections each; an active window sweeps, then a 4-tick pause | 40 ms tick, 50 ms transition | `ConnectionView.tsx:260-300` |
| City card connecting | horizontal gradient "wave" | 2 s linear infinite | `Location.module.css:63-85` |
| Rolling account digits | spring per digit (bounce 0), 2 rolls, then fade-up of the copy section | 900 ms / 600 ms | `LogInView.tsx:155-350` |
| Combobox chevron | rotate 180° | 200 ms ease-in-out | `ConnectionView.tsx:523-528` |
| Card press | translateY(2px) on active | instant | `Location.module.css:18-20` |
| Scroll-to-top FAB | slide-up when scrollY > 50 (gradient ActionIcon) | Mantine default | `ScrollableView.tsx:19-31` |
| Accordions | transitions disabled | none | `Mantine.tsx:84-93` |
| Loading throttles | `MIN_LOAD_MS` 400 ms; `useCommand` loading UI after 200 ms | | `UI/common/utils.ts:14`, `UI/bridge/commands.ts:356` |
| Copy feedback | CopyButton timeout | 1100 ms | `Mantine.tsx:33` |
| Toasts | connect error: persistent; status-stream error: 15 s; switching city: 15 s; connected confirmation: 1 s green | | `UI/App.tsx:91,129-136,151-156`; `LocationView.tsx:54-60` |

---

## 9. User-visible strings that matter for parity (summary; all in `UI/translations/en.json`)

- **Status labels:**
  - "Connected to Obscura" (75), "Not connected to Obscura" (292), "Validating Account..." (384), "Connecting to {{ location }}" (78), "Reconnecting to Obscura" (323), "Disconnecting" (126), "Disconnected" (123/124), "Offline" (295), "Connected to {{ location }}" (74)
  - "Connecting", "Reconnecting", "Changing Locations", each with "..." appended
  - "Traffic is protected" (378), "Traffic is suspended" (379), "Obscura not connected" (294)
  - "Enjoy the free and open internet!" (147), "Connect to enjoy seamless privacy protection" (81), "Connect to the internet to enable Obscura" (82), "Please wait a moment..." (314)
  - "Quick Connect" (321), "Cancel Connecting" (63), "Connect", "Disconnect", "Cancel"
  - "Last chosen" (233), "Pinned" (308), "Select Location" (338), "Click to connect" (69), "Check my Connection" (68)
  - "Your device", "Internet", "Obscura" and "Blind Relay" (the last two are hard-coded, `ConnectionView.tsx:233,240`)
  - "Current Session" (105), "Secured by {{provider}}" (337), "Node", "Public key", "Matches published key for" (154), "Network Information" (285), "View server info" (386), "Reveal server info"/"Hide server info" (339-340)
- **Continents** (85-91): Africa, Antarctica, Asia, Europe, North America, Oceania, **Latin America** (SA, including Mexico).
- **Connect errors** (`vpnError-*`, 389-409), the ones that matter on Android:
  - accountExpired "Your account has expired"
  - apiError "API request failed. Please make sure you are connected to the internet and no other VPN or firewall is running."
  - apiRateLimitExceeded "API rate-limit exceeded. Please try again later."
  - apiUnreachable "Unable to connect to the Obscura API. You may be offline or something is interfering with your connection."
  - invalidAccountId "Account Number invalid (checksum failed)"
  - noInternet "Not connected to the internet"
  - noLongerSupported "This client is too old, please update to the latest version to connect"
  - noRelay "Unable to connect to relay servers. You may be offline or something is interfering with your connection."
  - noSlotsLeft "You cannot have more than five active devices. Disconnect a device or delete a WireGuard config in your user portal"
  - notLoggedIn "Not logged in. Please log in to connect."
  - other (generic + debug bundle hint)
  - errorLegacyAlwaysOn / errorOtherAppAlwaysOn (another Always-On VPN blocks the permission prompt)
  - errorPermissionNotGranted "Permission must be granted to run as a VPN on your device. Make another connection attempt to try again."
- **Command errors** (`ipcError-*`, 199-232): same API family + "Sign up limit exceeded. Please try again in a few hours.", "Invalid promo code.", "Could not save config", "A debug bundle is already being created…", Play Services messages, purchase failures.
- **Account:** "Paid Up", "Subscription Active", "Subscription Paused", "Your account has expired", "{{count}} days until expiry" (with hours/minutes variants), "Your account will expire in {{ count }} days on {{ endDate }}.", "Your subscription will renew automatically in …", "Your subscription will start automatically after your top-up credit is used up on {{ endDate }}.", "Top-up or subscribe to continue using Obscura.", "Account Info Unavailable" / "Please check again" (7-39, 94).
- **Settings:**
  - "Automatically connect at launch" + "If you last chose "Quick Connect", Obscura will "Quick Connect" at next launch. If you last chose a specific location, Obscura will connect to that location at next launch." (58-59)
  - DNS: "DNS Settings", "Block ads/trackers/malware/gambling/adult content/social media" (128-138)
  - Private DNS alert: "Android's Private DNS is turned on, this overrides Obscura's DNS settings." (46)
  - Feature flags (162-172):
    - "Enable packet padding" / "Make network packets a uniform size to mitigate against size-based packet correlation by global network observers." / warning "Enabling this feature may slightly increase bandwidth usage"
    - "Small MTU compatibility" / "Assume the underlying network has a small MTU. This may help on networks with MTU issues." / "This may slightly reduce speed and reliability"
    - "Use TCP/TLS tunnel" / "Use TCP/TLS instead of QUIC (which is UDP-based) for the tunnel to Obscura servers. This may allow you to use Obscura on networks that restrict UDP traffic." / "This may reduce performance and slightly increase bandwidth usage"
  - "Rotate WireGuard® key"/"Rotated", "Allow local network access", "Appearance": Light/Dark/System.
- **Onboarding:** "Obscura Account Number", placeholder "XXXX - XXXX - XXXX - XXXX - XXXX", "Log In", "Start Trial", "Create an Account", "By creating an account, you agree to our Terms of Service and Privacy Policy.", "Copy Account Number"/"Copied Account Number", "Please write down your account number or store it in an encrypted vault", "Proceed to Payment", "Please confirm that you have stored your account number somewhere secure", "Continue to payment", "Payment", "Done", "Want to use an existing account instead? Go back".
- **About:** "© 2026 Sovereign Engineering Inc. All rights reserved." (101), "WireGuard® is a registered trademark of Jason A. Donenfeld." (416).

---

## 10. Implications for the native Compose client [S]

1. **Navigation.** Use the same 5 destinations as Android upstream (Connection, Location, Account, Settings, About; Help merged into About). Hide the nav when logged out or in the new-account flow. Use Material 3 `NavigationBar` with an orange indicator.
2. **State.** Mirror upstream exactly. Use one `getStatus` long-poll coroutine (`knownVersion`) into a `StateFlow`, plus `getExitList` long-poll + `refreshExitList` timer. Derive UI state from `vpnStatus` + a local `ConnectionInProgress` (optimistic Disconnecting/ChangingLocations), as in §3.4. Do not replicate the dual Os/App status split: Android `osVpnStatus` is derived from `vpnStatus` anyway.
3. **Palette.** Brand `#FF6025` (seed). Gradient `#FF7A49`→`#FF6025` for the primary CTA. Neutral surfaces: light `#FFFFFF`/`#F8F9FA`, dark `#303030`/`#313131`/`#353535`, with `#222528` as the deepest tone. Status: green `#37B24D`/`#2F9E44`, red `#F03E3E`/`#FA5252`, teal chip `#12B886`/`#38D9A9`. Prefer a fixed brand scheme over dynamic colour so the identity stays recognisable. Use Roboto / Roboto Mono (system), and Press Start 2P only as an optional accent.
4. **Advanced privacy extras** that rustlib already supports without Rust changes:
   - entry relay + transport + latency + live throughput display (from `connected.relay`, `.transport`, `getTrafficStats` deltas, as the macOS StatusMenu does);
   - country-level and specific-server connect (`ExitSelector.country` / `.exit`);
   - exit key verification link;
   - an Advanced section for `setSniRelay` / `setApiHostAlternate`;
   - a DNS note that blocklists apply only on Mullvad-operated exits;
   - a deep link to Android "Always-on VPN / Block connections without VPN" as the kill switch;
   - a Quick Settings tile and a boot auto-connect, which upstream Android lacks.
5. **Fix upstream issues** from §7: the unpin bug, masking in the payment sheet, and an Android-specific error text. Add `FLAG_SECURE` on the account number reveal and copy screens.
