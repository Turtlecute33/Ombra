# rust-ffi: complete Android FFI surface of `rustlib` (obscuravpn-client)

Sources (read-only):

- Client repo: `/tmp/obscura-research/obscuravpn-client` at `85ef7e2692dc811a023360f08865bfb90815649c` (2026-10-19).
- API crate at the exact pinned rev `e94483e7331b521d4d29ee7411a83e4adeffcc9d`: cloned by me to `/tmp/obscura-research/obscuravpn-api` (the `~/.cargo/git/checkouts` copy is the older rev `2ca8fb7`, do not use it).
- Locked crate versions (rustlib `Cargo.lock`): serde 1.0.228, serde_json 1.0.140, serde_with 3.12.0, uuid 1.17.0, jni 0.21.1, tokio 1.45.1, quinn 0.11.12, quinn-udp 0.5.16, rustls 0.23.28, neptun (boringtun fork) 0.6.0.

Legend: **[V]** = verified by reading the cited code. **[T]** = verified by a test: I compiled a scratch crate with the same serde attributes and the same locked crate versions and printed the JSON (scratch crate deleted after). **[I]** = inference or recommendation, not verified.

Paths below are relative to `/tmp/obscura-research/obscuravpn-client/` unless they start with `/`. `rustlib/src/` is shortened to `rs/`. `android/app/src/main/java/net/obscura/vpnclientapp/` is shortened to `kt/`.

---

## 1. Architecture in one page

- One native library: `libobscuravpn_client.so` (crate `obscuravpn-client`, `crate-type = ["cdylib","lib","staticlib"]`, `rustlib/Cargo.toml:17-18`). Android module is compiled only for `target_os = "android"` (`rs/lib.rs:27-28`). **[V]**
- Upstream loads it ONLY in the `:vpnservice` process. The Java guard is in `kt/client/ObscuraLibrary.java:15-21` (throws `IllegalStateException` if `Application.getProcessName()` does not end with `":vpnservice"`). Rust itself has no process check. **[V]**
- The UI process never touches Rust. It talks to the service with AIDL `IObscuraVpnService` (`jsonFfi(long id, String command)`, `stopTunnel()`), and gets results back by an explicit broadcast to `JsonFfiBroadcastReceiver`. **[V]** (section 13)
- Rust holds one process-global `Global { manager, os_impl, class_cache, runtime }` in `static GLOBAL: OnceLock<Global>` (`rs/android/ffi.rs:23-30`). It is never dropped. **[V]**
- All features are driven by ONE generic JSON command entry point (`jsonFfi`), plus 2 network-interface setters, 2 log functions. Rust calls back into Java for 2 static methods on `ObscuraVpnService` and for `CompletableFuture.complete/completeExceptionally`. **[V]**

---

## 2. JNI exports (complete list)

All exports are in `rs/android/ffi.rs`. There are no other `#[no_mangle]` JNI symbols for Android anywhere in `rustlib` (grep for `android`, `jni`, `JNIEnv` outside `rs/android/`: only cfg branches, no exports). **[V]**

All Java methods are `static native` in class `net.obscura.vpnclientapp.client.ObscuraLibrary` (`kt/client/ObscuraLibrary.java:23-32`). Because they are static, the 2nd JNI parameter is `jclass`. **[V]**

| # | Exported symbol | Java declaration (exact) | JNI descriptor | Rust signature | Lines |
|---|---|---|---|---|---|
| 0 | `JNI_OnLoad` | (called by `System.loadLibrary`) | n/a | `extern "C" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint` returns `JNI_VERSION_1_6`. Caches nothing. | ffi.rs:41-45 |
| 1 | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_initialize` | `static native FfiHandle initialize(String configDir, String userAgent);` | `(Ljava/lang/String;Ljava/lang/String;)Lnet/obscura/vpnclientapp/client/ObscuraLibrary$FfiHandle;` | `(mut env: JNIEnv, _: JClass, j_config_dir: JString, j_user_agent: JString) -> jobject` | ffi.rs:72-96 |
| 2 | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_jsonFfi` | `static native void jsonFfi(FfiHandle handle, String json, CompletableFuture<String> future);` | `(Lnet/obscura/vpnclientapp/client/ObscuraLibrary$FfiHandle;Ljava/lang/String;Ljava/util/concurrent/CompletableFuture;)V` | `(mut env: JNIEnv, _: JClass, handle: JObject, j_json_cmd: JString, j_future: JObject)` | ffi.rs:130-144 |
| 3 | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_setNetworkInterface` | `static native void setNetworkInterface(FfiHandle handle, String name, int index);` | `(L...$FfiHandle;Ljava/lang/String;I)V` | `(mut env: JNIEnv, _: JClass, handle: JObject, j_name: JString, j_index: jint)` | ffi.rs:156-169 |
| 4 | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_unsetNetworkInterface` | `static native void unsetNetworkInterface(FfiHandle handle);` | `(L...$FfiHandle;)V` | `(_env: JNIEnv, _: JClass, handle: JObject)` | ffi.rs:171-176 |
| 5 | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_forwardLog` | `static native void forwardLog(int level, String tag, String message, String messageId, String throwableString);` | `(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V` | `(mut env: JNIEnv, _: JClass, j_level: jint, j_tag: JString, j_message: JString, j_message_id: JString, j_throwable_string: JString)` | ffi.rs:216-230 |
| 6 | `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_flushAndStopPersistedLog` | `static native void flushAndStopPersistedLog();` | `()V` | `(_env: JNIEnv, _: JClass)` | ffi.rs:178-183 |

Behaviour per export **[V]**:

1. `initialize(configDir, userAgent)`
   - `GLOBAL.get_or_init(...)`: first call builds `ClassCache` (needs app class loader, so "Must be called on a Java thread", ffi.rs:73) and calls `initialize()` (ffi.rs:47-70). Both use `.expect(...)`, and the crate is built with `panic = "abort"` (`rustlib/Cargo.toml:20-27`), so ANY init failure aborts the whole process. **[V]**
   - `initialize()` order (ffi.rs:47-70): `Runtime::new()` (multi-thread tokio) -> `runtime.enter()` -> decode strings -> `logging::init(tracing_android::layer("ObscuraNative")?, Some(<configDir>/rust-log))` -> install rustls `aws_lc_rs` default crypto provider -> `env.get_java_vm()` -> `AndroidOsImpl::new(jvm, class_cache)` -> `Manager::new(configDir, WgKeyStore::Plaintext, userAgent, os_impl, os_impl.network_interface(), log_persistence, force_init_inactive = true)`.
   - Later calls in the same process: return a NEW `FfiHandle` for the SAME `Global`, log `"ffi was already initialized"` (message_id `sxsEyRKH`). Args of later calls are ignored.
   - Returns `new FfiHandle()` built with `env.new_object(class_cache.ffi_handle(), "()V", &[])` (private ctor is fine for JNI).
   - The handle is only a token. `global_from_handle` ignores it and reads `GLOBAL` (ffi.rs:32-37). Calling any other export before `initialize` panics (`"global not initialized"`) and aborts.
2. `jsonFfi(handle, json, future)` (ffi.rs:98-128)
   - Synchronous part on the caller thread: decode the Java string, `serde_json::from_str::<ManagerCmd>`, `env.new_global_ref(future)`. If this fails, Rust throws `java.lang.RuntimeException` on the caller thread (`throw_runtime_exception`, `rs/android/util.rs:6-11`) and the `future` is NEVER completed. **[V]**
   - Async part: `tokio::spawn` on `global.runtime`: `cmd.run(&global.manager, None).await`, then `jvm.attach_current_thread_as_daemon()` and `signal_json_ffi_future(...)` (`rs/android/future.rs:9-48`).
   - Success: `future.complete(String)` with the JSON of `ManagerCmdOk` (descriptor `"(Ljava/lang/Object;)Z"`). Error: `future.completeExceptionally(new ErrorCodeException(code))` (descriptors `"(Ljava/lang/String;)V"` and `"(Ljava/lang/Throwable;)Z"`).
3. `setNetworkInterface(handle, name, index)` (ffi.rs:146-169): `index` must be `1..=i32::MAX` (`PositiveU31`, `rs/positive_u31.rs:5-19`); else only an error log, no exception. Then `os_impl.set_network_interface(Some(NetworkInterface { name, index }))` = `watch::Sender::send_replace` (non-blocking, cheap).
4. `unsetNetworkInterface(handle)`: `set_network_interface(None)`.
5. `forwardLog(level, tag, message, messageId, throwableString)` (ffi.rs:188-230): level mapping `0 TRACE, 1 DEBUG, 2 INFO, 3 WARN, 4 ERROR` (any other value: error log). `tag`, `message`, `messageId` must be non-null; `throwableString` may be null. Emitted with `target: "java"` and fields `message_id, tag, throwable_string, message`. Global filter is `EnvFilter::from_default_env().add_directive(LevelFilter::INFO)` (`rs/logging.rs:78-80`), so TRACE and DEBUG are dropped. Usable before `initialize`? It does not touch `GLOBAL`, but no subscriber exists yet, so events are lost. **[V]**
6. `flushAndStopPersistedLog()`: `crate::logging::flush_and_stop_persisted_log()` (drops all `WorkerGuard`s, `rs/logging.rs:34-36`). After this, persisted log writing stops for the process lifetime. Upstream calls it from a JVM shutdown hook (`kt/services/ObscuraVpnService.kt:179-194`). **[V]**

JNI name mangling rules (for a renamed class) **[V for current names, I for rules]**: symbol = `Java_` + FQN with `.`->`_` + `_` + method. Escape `_` in names as `_1`, `$` as `_00024`, non-ASCII as `_0xxxx`. Current names contain none of these, so no escapes. Example: class `dev.foo.core.NativeBridge`, method `jsonFfi` -> `Java_dev_foo_core_NativeBridge_jsonFfi`. Alternative: patch Rust to call `RegisterNatives` in `JNI_OnLoad`.

---

## 3. Java classes and members Rust REQUIRES (hard-coded)

`ClassCache::new` (`rs/android/class_cache.rs:18-26`) runs `FindClass` for exactly these 3 names and `.expect()`s them. If one is missing, `initialize` aborts the process. **[V]**

| JNI class name (exact) | Used for | Required member (exact) | Upstream file |
|---|---|---|---|
| `net/obscura/vpnclientapp/client/ObscuraLibrary$FfiHandle` | return value of `initialize` | no-arg constructor `()V` | `kt/client/ObscuraLibrary.java:10-13` (`@Keep public static class FfiHandle { private FfiHandle() {} }`) |
| `net/obscura/vpnclientapp/client/ErrorCodeException` | error path of `jsonFfi` | constructor `(Ljava/lang/String;)V`; must be a `Throwable` | `kt/client/ErrorCodeException.kt:7-8` (`@Keep data class ErrorCodeException(val errorCode: String) : Exception(errorCode)`) |
| `net/obscura/vpnclientapp/services/ObscuraVpnService` | Rust -> Java callbacks | `static int ffiSetNetworkConfig(String)` = `"(Ljava/lang/String;)I"`; `static String ffiGetProcessExitReasons()` = `"()Ljava/lang/String;"` | `kt/services/ObscuraVpnService.kt:103-147` (`companion object` + `@JvmStatic` + `@androidx.annotation.Keep`) |

Also used: `java/lang/RuntimeException` (throw), `java/util/concurrent/CompletableFuture` methods `complete` and `completeExceptionally` (via `call_method` on the instance, so any subclass works). **[V]**

Consequence for the new app **[I]**: the `applicationId` can be anything (JNI binds by class FQN, not by applicationId), but either (a) keep these exact Java/Kotlin FQNs and member signatures, or (b) patch `class_cache.rs` + the 6 symbol names in `ffi.rs`. R8 rules needed for a minified release (upstream relies on `@Keep` plus the default `proguard-android-optimize.txt`, `android/app/build.gradle.kts:46-52`):

```
-keep class net.obscura.vpnclientapp.client.ObscuraLibrary { native <methods>; }
-keep class net.obscura.vpnclientapp.client.ObscuraLibrary$FfiHandle { <init>(); }
-keep class net.obscura.vpnclientapp.client.ErrorCodeException { <init>(java.lang.String); }
-keep class net.obscura.vpnclientapp.services.ObscuraVpnService {
    public static int ffiSetNetworkConfig(java.lang.String);
    public static java.lang.String ffiGetProcessExitReasons();
}
```

---

## 4. Rust -> Java callbacks (complete list)

| Call | Class / object | Method + JNI signature | Thread | When | Lines |
|---|---|---|---|---|---|
| A | `ObscuraVpnService` (static) | `ffiSetNetworkConfig` `"(Ljava/lang/String;)I"` | tokio `spawn_blocking` thread, attached with `attach_current_thread_as_daemon` | every `Os::set_os_network_config` (connect start with dummy config, connect done with real config, every DNS / LAN / content-block change while connected) | `rs/android/ffi.rs:232-259`, called from `rs/android/os_impl.rs:35-58` |
| B | `ObscuraVpnService` (static) | `ffiGetProcessExitReasons` `"()Ljava/lang/String;"` | blocking thread of the debug bundle task | only in `CreateDebugBundle` | `rs/android/ffi.rs:261-293`, `rs/debug_bundle/mod.rs:168-171` |
| C | the `future` object passed to `jsonFfi` | `complete` `"(Ljava/lang/Object;)Z"` with a `java.lang.String` | tokio worker thread (daemon-attached for the thread lifetime) | on `Ok` of every json command | `rs/android/future.rs:21-28` |
| D | same `future` | `completeExceptionally` `"(Ljava/lang/Throwable;)Z"` with `new ErrorCodeException(String)` `"(Ljava/lang/String;)V"` | same | on `Err(ManagerCmdErrorCode)` | `rs/android/future.rs:29-45` |
| E | `ObscuraLibrary$FfiHandle` | `<init>` `"()V"` | caller of `initialize` | in `initialize` | `rs/android/ffi.rs:93-95` |

There is NO Rust call to `VpnService.protect()`, no status-push callback, no log callback, no `setUnderlyingNetworks` from Rust. Status is pulled by long-poll (`getStatus`). Logs go Java -> Rust only. **[V]** (grep of `call_method`/`call_static_method`/`new_object` in `rs/`: only the 5 rows above.)

Contract details **[V]**:

- A: Rust passes `serde_json::to_string(&OsNetworkConfig)` (section 7.3). Java must return a raw fd it gives up (`ParcelFileDescriptor.detachFd()`), or any negative int for failure. Rust wraps it as `OwnedFd` and closes it itself later (ffi.rs:250-255). If Java throws, Rust only logs (`oP7aSb3t`) and returns `Err(())`; jni-rs leaves the Java exception pending on that attached thread. **[I]** A pending exception on a reused blocking thread is unsafe, so the Java side MUST catch everything (upstream does: `kt/services/ObscuraVpnService.kt:105-126`).
- A runs `VpnService.Builder.establish()` off the main thread. Rust awaits it (`spawn_blocking(...).await`).
- B returns a JSON array string or `null`. Upstream builds it from `ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 0)` (`kt/services/ObscuraVpnService.kt:36-65`, `128-147`).
- C/D: `CompletableFuture` dependents registered with `handle`/`thenApply` (non-async) run synchronously INSIDE `complete()` on the tokio worker thread. **[I]** Do not block there; hop to your own dispatcher (`await()` from kotlinx-coroutines-jdk8 or `*Async` variants).
- Ok payload is a JSON string (for unit results the string is `"null"`, section 9.3). If `env.new_string` fails, Rust completes with Java `null` (future.rs:22-25).

---

## 5. Init order and lifecycle (upstream, verified) **[V]**

Upstream sequence in `ObscuraVpnService.onCreate()` (`kt/services/ObscuraVpnService.kt:164-225`):

1. `rustFfi = RustFfi(this, "obscura.net/android/${BuildConfig.VERSION_NAME}")` -> `ObscuraLibrary.load(context, userAgent)` -> process-name check -> `System.loadLibrary("obscuravpn_client")` -> `initialize(context.getFilesDir().getAbsolutePath(), userAgent)` (`ObscuraLibrary.java:15-21`, `RustFfi.kt:7-12`). Runs on the main thread (disk read: StrictMode exemption in `kt/App.kt:39-44`).
2. `log = rustFfi.logger(tag)` (forwards to `forwardLog`, `RustFfi.kt:14-23`, `messageId ?: "JavaNoID"`, `level.ordinal`).
3. `instance.getAndSet(this)`: the static `AtomicReference<ObscuraVpnService?>` that `ffiSetNetworkConfig` uses. Must be set before any connect.
4. Register shutdown hook -> `flushAndStopPersistedLog()`.
5. `connectivityManager.registerBestMatchingNetworkCallback(NetworkRequest{INTERNET, NOT_VPN}, cb, mainHandler)`: `onAvailable` -> `updateInterface(network)`, `onLost` (if current) -> `updateInterface(null)` (lines 198-220, 457-466).
6. `loadStatus(null)`: starts the service's own `getStatus` long-poll loop (for the notification).
7. `onStartCommand` (lines 268-294): `prepareVpnService()`, `startForeground(..., FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)` (API 34+, only if prepared). Actions: `"${APPLICATION_ID}.actionStartTunnel"` -> `startTunnel(extra "exitSelector")`; `"${APPLICATION_ID}.actionStopTunnel"` -> `stopTunnel()`; `VpnService.SERVICE_INTERFACE` (system / always-on) -> `startTunnel(null)`. Returns `START_STICKY`.
8. `onDestroy` -> clear `instance`, `stopTunnel()`. `onRevoke` -> `stopTunnel()`.

Rust side facts that shape the lifecycle **[V]**:

- `force_init_inactive = true` (ffi.rs:67) -> `ClientState::new` sets and PERSISTS `config.tunnel_active = false` on every process start (`rs/client_state.rs:93-95`). The VPN never auto-resumes inside Rust after a process restart. Java must send `setTunnelArgs{active:true}`.
- Upstream gap **[V+I]**: a `START_STICKY` restart delivers `intent == null`, which matches no action, so the tunnel is NOT restarted after an OOM kill / crash (unless always-on triggers `SERVICE_INTERFACE`). The new app should persist "user wants VPN on" and reconnect on a null-intent restart.
- Without a `setNetworkInterface(...)` call, a connect request never proceeds: state stays `connecting` with `connectError: "noInternet"` (`rs/tunnel_state.rs:365-379`).
- `Manager::new` spawns 3 long-lived tasks (`rs/manager.rs:197-199`): WireGuard key registration, status propagation, preferred-network-interface propagation. `TunnelState::new` spawns the `maintain` loop (`rs/tunnel_state.rs:68-72`). All live for the process lifetime.

---

## 6. Threading model and runtime ownership **[V unless noted]**

- Owner of the tokio runtime: `Global.runtime` (`tokio::runtime::Runtime::new()`, multi-thread scheduler, default worker count = CPU count **[I: tokio default]**), stored in the static `GLOBAL`, never shut down (ffi.rs:23-30, 48).
- `initialize`: any Java thread with the app class loader (main thread upstream). Blocking: config load from disk, log roller setup.
- `jsonFfi`: any thread. Returns immediately after spawn. Many commands may run in parallel.
- `setNetworkInterface` / `unsetNetworkInterface`: any thread, non-blocking (`watch::send_replace`). Upstream calls them on the main looper from `NetworkCallback`.
- `forwardLog`, `flushAndStopPersistedLog`: any thread.
- Rust -> Java threads: future completion on tokio workers; `ffiSetNetworkConfig` on the tokio blocking pool. Both attach as daemon threads permanently (comment ffi.rs:107-113).
- Packet path: TUN read loop = one tokio task per TUN (`rs/android/tunnel.rs:28-52`). Receive + TUN write = inside the single `TunnelState::maintain` task (`rs/tunnel_state.rs:332-343`). Encrypt (send) and decrypt (receive) both lock the same `std::sync::Mutex<WgState>` (`rs/quicwg.rs:383-395`, `441-520`), so WG crypto is effectively serialized. **[I]** Throughput is bounded by about one core of crypto plus syscalls.

---

## 7. TUN fd, packet flow, network config

### 7.1 How the fd gets in **[V]**

`AndroidOsImpl::set_os_network_config(network_config, tunnel)` (`rs/android/os_impl.rs:35-58`):

1. `json = serde_json::to_string(&OsNetworkConfig)`.
2. `fd = call_set_network_config(class_cache, jvm, json).await?` (Java returns `pfd.detachFd()`).
3. `Tun::spawn(fd, tunnel)`; then `*self.tun.lock() = Some(tun)`. Assigning drops the PREVIOUS `Tun` (aborts its read task, closes its fd) AFTER the new one exists. This matches the Android "seamless handover" contract of `establish()`.
4. If Java returns `< 0`: `Err(())`, old TUN is kept unchanged.

`unset_os_network_config()` = `*self.tun.lock() = None` (closes fd, Android removes the VPN interface) (os_impl.rs:60-63).

### 7.2 Packet flow **[V]**

Outbound (apps -> TUN -> relay), `rs/android/tunnel.rs:19-54`:
- `AsyncFd::new(Arc<OwnedFd>)` (requires non-blocking fd; `establish()` returns non-blocking by default, Android docs `VpnService.Builder.setBlocking`).
- Loop: `readable().await` -> `nix::unistd::read(fd, &mut buf[..1280])` -> on `n > 0`: `tunnel.send(std::iter::once(&buf[..n]))`. `EAGAIN` -> `clear_ready()`. Any other error ends the loop (logged `eagh6Noh`).
- ONE packet per `read` syscall, buffer = `TUNNEL_MTU` = 1280 bytes. No batching, no multi-queue, no TUN offload / GSO on the TUN side. (Linux and Windows use `PacketBuffer` batching of up to 100 packets, `rs/os/packet_buffer.rs`; Android does not.)
- `QuicWgConnPacketSender` holds a `Weak<QuicWgConn>` (`rs/quicwg.rs:216-229`). If the conn is gone (or `None` = dummy), packets are silently DROPPED.
- `QuicWgConn::send` -> neptun `encapsulate` -> if the WG message exceeds `conn.max_datagram_size()` it is split in 2 fragments (`WgMessageFragmenter`) -> `quinn::Connection::send_datagram` (QUIC DATAGRAM frames, unreliable) or, for TCP/TLS, a queue capped at 1000 messages (`rs/quicwg.rs:922-940`).

Inbound (relay -> TUN):
- `TunnelState::maintain` loops `conn.receive().await` -> `os_impl.packet_for_os(packet)` -> `Tun::write` -> `nix::unistd::write(fd, packet)`; errors (for example `EAGAIN` when the TUN queue is full) are rate-limited logged and the packet is dropped (`rs/android/tunnel.rs:56-69`). One write syscall per packet.

UDP side **[I]**: quinn 0.11 / quinn-udp 0.5.16 use `sendmmsg`/`recvmmsg` and UDP GSO/GRO on Linux-family kernels when available. `Cargo.toml:53-56` pins `quinn-udp >= 0.5.16` because 0.5.6 regressed Android upload speed.

### 7.3 `OsNetworkConfig` JSON (Rust -> Java, arg of `ffiSetNetworkConfig`)

Rust type `rs/network_config.rs:97-113` (`#[serde(rename_all = "camelCase")]`, Android fields only), Kotlin mirror `kt/services/OsNetworkConfig.kt:5-20`. **[T]** exact output:

```json
{"dns":["100.64.0.3"],"ipv4":"10.75.76.77","ipv6":"fc00:bbbb:bbbb:bb01::c:4c4d/128","routes":[{"address":"0.0.0.0","prefix":0},{"address":"::","prefix":0}],"mtu":1280,"useSystemDns":false}
```

- `dns`: tunnel DNS from the API (`ObfuscatedTunnelConfig.dns`), EXCEPT when `exit.provider_name == "Mullvad VPN"` AND any content-block flag is set: then exactly `["100.64.0.<bitset>"]`, bitset = `ad<<0 | tracker<<1 | malware<<2 | adult<<3 | gambling<<4 | socialMedia<<5` (`rs/network_config.rs:80-90`, `125-131`). **[V]**
- `ipv4`: plain address (no prefix). `ipv6`: `"addr/prefix"` string. Kotlin splits on `/` and defaults to `/32` and `/128` (`ObscuraVpnService.kt:416-418`).
- `routes`: `[{"address":IpAddr,"prefix":u8}]`. LAN access disabled -> `0.0.0.0/0` and `::/0`. LAN access enabled (DEFAULT) -> full space minus LAN ranges, with the DNS server IPs added back as host routes (`rs/local_network.rs:8-60`). LAN v4: `10/8, 172.16/12, 192.168/16, 169.254/16, 255.255.255.255/32, 224.0.0.0/24, 239/8`. LAN v6: `fe80::/10, fc00::/7, ff01::/16 .. ff05::/16`.
- `mtu`: always `1280` (`TUNNEL_MTU`, `rs/quicwg.rs:61`).
- `useSystemDns`: from config `dns == System`. Upstream Kotlin then skips `addDnsServer` (`ObscuraVpnService.kt:407-414`).
- Dummy config used while connecting (`rs/network_config.rs:42-49`, `151-170`): dns `10.64.0.99` (or Mullvad block IP), ipv4 `10.75.76.77`, ipv6 `fc00:bbbb:bbbb:bb01::c:4c4d/128`, mtu 1280.

Upstream Java `applyNetworkConfig` (`kt/services/ObscuraVpnService.kt:391-431`): `Builder().addDisallowedApplication(own package)`, `setMtu`, `setMetered(false)`, `addDnsServer` each (unless `useSystemDns`), `addAddress` v4 + v6, `addRoute` each, `allowFamily(AF_INET)`, `allowFamily(AF_INET6)`, `establish()`. Not used: `setBlocking`, `setSession`, `setConfigureIntent`, `excludeRoute`, `setUnderlyingNetworks` on the Builder, `allowBypass`. **[V]**

### 7.4 Leak behaviour (kill-switch semantics) **[V]**

- While `connecting`, Rust installs the dummy config with `QuicWgConnPacketSender::new(None)` (`rs/tunnel_state.rs:250-263`): the TUN is up and captures all traffic, packets are dropped. So there is no leak during connect or reconnect.
- On network loss while connected: the old `QuicWgConn` is dropped, TUN stays, the weak sender fails, packets are dropped (no leak).
- On user disconnect (`tunnel_args == None`): `unset_os_network_config` closes the TUN, traffic goes outside the VPN. A system "Always-on VPN + Block connections without VPN" is the only OS-level kill switch. The `killSwitch` feature flag is used ONLY by the Linux service (`rs/bin/obscura/service/os/linux/netfilter.rs:564`), no effect on Android.
- IPv6 is routed into the tunnel (`::/0` or its LAN-subtracted equivalent) and the tunnel has an IPv6 address, so no IPv6 leak by routing.

---

## 8. Socket protection and network changes **[V]**

- Rust never calls `VpnService.protect()`. Upstream makes the WHOLE app UID bypass the VPN with `addDisallowedApplication(applicationInfo.packageName)` (`ObscuraVpnService.kt:397-398`). All Rust sockets (QUIC UDP to relays, TCP/TLS fallback, HTTPS API, DNS lookups) and all UI-process traffic go over the physical network.
- `rs/net.rs:28-51`: on Android sockets are `bind(0.0.0.0:0)` only; the `NetworkInterface` value is ignored (no `SO_BINDTODEVICE`, no `Network.bindSocket`). The API client also gets `None` for the interface (`rs/client_state.rs:146-161`).
- What `setNetworkInterface(name, index)` is used for: (1) MTU lookup by `SIOCGIFMTU` ioctl on `name` (`rs/net.rs:72-95`, `rs/client_state.rs:321-359`); if MTU < 1378 (`1350 + 28`) or flag `forceSmallMtu`, QUIC `max_udp_payload_size = 1200` (`rs/net.rs:102-130`); (2) target-state change detection: a different interface forces a FULL reconnect (new relay race) (`rs/tunnel_state.rs:163-171`); (3) `None` while active -> `connectError: "noInternet"`; (4) drops the cached API client.
- Upstream Java side (`ObscuraVpnService.kt:433-466`): name from `LinkProperties.interfaceName`, index from `java.net.NetworkInterface.getByName(name).index`; also calls `VpnService.setUnderlyingNetworks([network])` or `[]`. Only `onAvailable`/`onLost` are handled (no `onLinkPropertiesChanged`).
- There is no `wake()` export on Android. Apple has `wake(global)` -> `Manager::wake()` -> liveness probe (`rs/apple/ffi.rs:179-183`, `rs/manager.rs:388-392`). **[V]** Android liveness uses `CLOCK_BOOTTIME` (`rs/wake_instant.rs:47-55`).

---

## 9. JSON command protocol (`jsonFfi`)

### 9.1 Envelope **[V]+[T]**

- Request = serde externally tagged enum `ManagerCmd` (`rs/manager_cmd.rs:128-218`), `#[serde(rename_all = "camelCase", rename_all_fields = "camelCase")]`: `{"<variantCamel>": { <fieldsCamel> }}`.
- Every variant is a STRUCT variant. Zero-field variants MUST be sent as `{"ping":{}}`. `{"ping":null}` and `"ping"` are rejected. **[T]**
- `Option<..>` fields may be omitted or `null`. Unknown extra fields are ignored. **[T]**
- Ok response = `ManagerCmdOk` (`rs/manager_cmd.rs:220-240`), `#[serde(untagged)]`: the bare payload. Unit results serialize to `null`, so Java receives the 4-char string `"null"`. Empty API structs serialize to `{}`. **[T]**
- Error = `ErrorCodeException(errorCode)` with one `ManagerCmdErrorCode` string (camelCase, `rs/manager_cmd.rs:43-58`):
  `apiAssociateAccountConflict`, `apiError`, `apiInvalidAccountId`, `apiNoLongerSupported`, `apiRateLimitExceeded`, `apiSaleNotFound`, `apiSignupLimitExceeded`, `apiUnreachable`, `configSaveError`, `notLoggedIn`, `other`.
- Mapping from API errors (`rs/manager_cmd.rs:83-126`): `AssociateAccountConflict` -> apiAssociateAccountConflict; `NoLongerSupported` -> apiNoLongerSupported; `RateLimitExceeded` and proof-of-work errors -> apiRateLimitExceeded; `SaleNotFound` -> apiSaleNotFound; `SignupLimitExceeded` -> apiSignupLimitExceeded; `InvalidAccountId` -> apiInvalidAccountId; `RequestExecError` (network) -> apiUnreachable; `NotLoggedIn` -> notLoggedIn; all other API kinds and protocol errors -> apiError. Config not persisted -> configSaveError.
- Malformed JSON: NOT an `ErrorCodeException`; a synchronous `RuntimeException` from `jsonFfi` and the future never completes (section 2).

### 9.2 All 31 `ManagerCmd` variants **[V]** (JSON shapes of representative variants **[T]**)

Dispatcher: `rs/manager_cmd.rs:282-351`. "Set*" helpers go through `Manager::run_on_client_state` which ALWAYS returns `Ok(null)` (`rs/manager.rs:417-420`); their internal errors are only logged.

| Variant | Request JSON (exact) | Ok result | Possible errors | Notes |
|---|---|---|---|---|
| ApiAppleAssociateAccount | `{"apiAppleAssociateAccount":{"appTransactionJws":"<jws>"}}` | `{}` | api*, notLoggedIn | iOS only, irrelevant. |
| ApiDeleteAccount | `{"apiDeleteAccount":{}}` | `{}` | api*, notLoggedIn | Deletes the account on the server. Local config is NOT cleared (send `logout` after). |
| ApiGetAccountInfo | `{"apiGetAccountInfo":{}}` | `AccountInfo` (9.4) | api*, notLoggedIn | Side effect: stores `cached_account_status` -> `status.account` updates (`rs/manager.rs:262-266`). Network call to API. |
| ApiGoogleAssociateAccount | `{"apiGoogleAssociateAccount":{"purchaseToken":"..","promoCode":null}}` | `null` | api*, notLoggedIn | Play billing only. |
| ApiGoogleBillingDetails | `{"apiGoogleBillingDetails":{"promoCode":null}}` | `{"product_id":"..","base_plan_id":"..","offer_id":null}` | api*, notLoggedIn | snake_case output (`obscuravpn-api/src/cmd/google/billing_details.rs:17-22`). |
| ApiServiceStatus | `{"apiServiceStatus":{}}` | `{"free_trial":{"available":true},"nnn":{"claimed_months_target":100000,"claimed_months_total":1234,"submissions_open":true}}` | api* (no auth needed) | `obscuravpn-api/src/cmd/status.rs:7-23`. |
| CreateDebugBundle | `{"createDebugBundle":{"userFeedback":null,"bundleInfo":{"AppVersion":"x", ...},"androidCacheDir":"/data/user/0/<pkg>/cache"}}` | `"<abs path of .zip>"` (JSON string) | other | Writes into `<androidCacheDir>/debug-archives/` (`rs/debug_bundle/builder.rs:13-17`). Includes rust logs, `logcat -d`, process exit reasons, `GetDebugInfo` output. `BundleInfo` is PascalCase, only `AppVersion` is required (`rs/debug_bundle/bundle_info.rs:4-46`). Privacy: see section 15. |
| CreateServiceDebugBundle | `{"createServiceDebugBundle":{}}` | `{"path":"..","token":"<uuid>"}` | other | Desktop service design (uses `std::env::temp_dir()`), not used on Android. |
| DeleteServiceDebugBundle | `{"deleteServiceDebugBundle":{"token":"<uuid>"}}` | `null` | other | Pair of the above. |
| GetDebugInfo | `{"getDebugInfo":{}}` | `DebugInfo` (snake_case, redacted config + DNS/HTTP probe results) | never | Side effect: DNS + HTTPS requests to `www.apple.com`, `google.com`, `v1.api.prod.obscura.net`, `apple.com`, `google.com` (`rs/client_state.rs:845-917`). Avoid in a privacy app. |
| GetExitList | `{"getExitList":{"knownVersion":null}}` or `{"getExitList":{"knownVersion":"<base64>"}}` | `{"version":"<base64>","last_updated":<unix s>,"value":{"exits":[OneExit...]}}` | other | LONG-POLL: returns when a cached list exists AND its version differs from `knownVersion` (`rs/manager.rs:394-415`). If no list was ever fetched it waits until one is (send `refreshExitList`). Field names here are snake_case (`rs/cached_value.rs:7-16`). |
| GetStatus | `{"getStatus":{"knownVersion":null}}` or `{"getStatus":{"knownVersion":"<uuid>"}}` | `Status` (section 10) | other | LONG-POLL: `wait_for(|s| Some(s.version) != known_version)` (`rs/manager_cmd.rs:313-321`). `null` -> immediate. No server-side timeout. |
| GetTrafficStats | `{"getTrafficStats":{}}` | `{"connectedMs":0,"connId":"<uuid>","txBytes":0,"rxBytes":0,"latestLatencyMs":0}` | never | Not a long-poll. Poll it (for example 1 Hz while visible). See 10.5. |
| TerminateProcess | `{"terminateProcess":{}}` | `null` | never | `std::process::exit(1)` after 3 s. |
| Login | `{"login":{"accountId":"<digits>","validate":true}}` | `null` | apiInvalidAccountId, apiUnreachable, apiRateLimitExceeded, apiError, configSaveError | `validate:true` acquires an auth token (network, up to 10 URL overrides). `validate:false` stores the id only (`rs/manager.rs:215-236`). Changing account resets most config (see `logout`). |
| Logout | `{"logout":{}}` | `null` | configSaveError | Resets config to `Default` except `api_url`, `old_account_ids`, `in_new_account_flow` (`rs/client_state.rs:205-239`). Clears pinned locations, DNS options, flags, tunnel args, WG key, caches. `tunnel_active` -> false, so it disconnects. |
| Ping | `{"ping":{}}` | `null` | never | |
| RefreshExitList | `{"refreshExitList":{"freshness":60000}}` | `null` | api*, notLoggedIn | `freshness` = integer MILLISECONDS (`serde_with::DurationMilliSeconds`, u64; float rejected **[T]**). Fetches only if cached list is older than `freshness`; uses ETag (`rs/client_state.rs:672-726`). |
| RotateWgKey | `{"rotateWgKey":{}}` | `null` | never | New X25519 key now; registered with API later by the background task when disconnected and account active (`rs/manager.rs:307-334`). |
| SetApiHostAlternate | `{"setApiHostAlternate":{"host":null}}` | `null` | never | Backup API host; default `crimsonlance.net` (`rs/constants.rs:11`). Not shown in `Status`. |
| SetApiUrl | `{"setApiUrl":{"url":null}}` | `null` | never | `null` = `https://v1.api.prod.obscura.net/api`. ALSO rotates the WG key (`rs/client_state.rs:306-315`). |
| SetAutoConnect | `{"setAutoConnect":{"enable":true}}` | `null` | never | Stored only. Rust does not act on it; upstream UI connects on app open (`kt/ui/ObscuraUI.kt:157-167`). |
| SetDnsContentBlock | `{"setDnsContentBlock":{"value":{"ad":false,"tracker":false,"malware":false,"adult":false,"gambling":false,"socialMedia":false}}}` | `null` | never | ALL 6 bools required (missing field -> parse error **[T]**). Effective only for Mullvad exits. |
| SetFeatureFlag | `{"setFeatureFlag":{"flag":"tcpTlsTunnel","active":true}}` | `null` | never | Flags: `quicFramePadding`, `killSwitch`, `forceSmallMtu`, `tcpTlsTunnel`. `active:false` stores `null` (unset). Unknown flag: logged, ignored (`rs/config/feature_flags.rs:27-52`). |
| SetInNewAccountFlow | `{"setInNewAccountFlow":{"value":false}}` | `null` | never | UI onboarding marker. |
| SetPinnedExits | `{"setPinnedExits":{"exits":[{"country_code":"US","city_code":"nyc","pinned_at":1700000000}]}}` | `null` | never | Replaces the whole list. snake_case fields; `pinned_at` = integer unix seconds. Favourites only; not used for selection. |
| SetSniRelay | `{"setSniRelay":{"host":null}}` | `null` | never | TLS SNI sent to relays; default `example.com` (`rs/constants.rs:12`). Not shown in `Status`. |
| SetTunnelArgs | `{"setTunnelArgs":{"args":{"exit":<ExitSelector>},"active":true}}` | `null` | never | THE connect/disconnect command. `args:null` keeps persisted args; `active:null` keeps state. Connect = `active:true`; disconnect = `{"args":null,"active":false}` (`rs/client_state.rs:261-270`). Result returns immediately; observe progress via `getStatus`. |
| SetUseSystemDns | `{"setUseSystemDns":{"enable":false}}` | `null` | never | `true` -> Java should not add VPN DNS. Applied live (TUN rebuild). |
| SetLocalNetworkAccess | `{"setLocalNetworkAccess":{"enable":false}}` | `null` | never | Default is ENABLED. Applied live (TUN rebuild with new routes). |
| SetTailscaleBypass | `{"setTailscaleBypass":{"enable":true}}` | `null` | never | Linux only. Stored and shown in status; no effect on Android. |
| SetWireGuardBypass | `{"setWireGuardBypass":{"enable":true}}` | `null` | never | Linux only. Same. |

### 9.3 Exact Ok payload types **[V]**

`ManagerCmdOk` variants (untagged): `ApiAppleAssociateAccount(AppleAssociateAccountOutput = {})`, `ApiDeleteAccount(DeleteAccountOutput = {})`, `ApiGetAccountInfo(AccountInfo)`, `ApiGoogleBillingDetails(GoogleBillingDetailsOutput)`, `ApiServiceStatus(ServiceStatusOutput)`, `CreateDebugBundle(String)`, `CreateServiceDebugBundle(ServiceDebugBundleHandle)`, `Empty` (= `null`), `GetDebugInfo(DebugInfo)`, `GetExitList(CachedValue<Arc<ExitList>>)`, `GetStatus(Status)`, `GetTrafficStats(ManagerTrafficStats)`. Decode by the command you sent, not by shape.

### 9.4 Shared nested JSON types

- `ExitSelector` (`rs/exit_selection.rs:9-23`), variant names camelCase, inner fields NOT renamed (snake_case) **[T]**:
  - `{"any":{}}`
  - `{"exit":{"id":"<exit id>"}}`
  - `{"country":{"country_code":"US"}}`
  - `{"city":{"country_code":"US","city_code":"nyc"}}` (flattened `CityCode`)
  - Matching is exact string equality against the exit list values (`exit_selection.rs:26-33`). Copy codes verbatim from `OneExit`.
- `TunnelArgs` (`rs/manager.rs:137-141`): `{"exit": ExitSelector}`. Default `{"exit":{"any":{}}}`.
- `PinnedLocation` (`rs/config/persistence.rs:411-419`): `{"country_code":String,"city_code":String,"pinned_at":i64 seconds}`.
- `DnsContentBlock` (`rs/network_config.rs:69-78`): `{"ad","tracker","malware","adult","gambling","socialMedia"}` all bool.
- `OneExit` (`obscuravpn-api/src/types.rs:518-538`): `{"id":String,"country_code":String,"city_code":String,"city_name":String,"provider_id":String,"provider_url":String,"provider_name":String,"provider_homepage_url":String,"datacenter_id":u32,"tier":u8}`. Example from the API test: `{"id":"NYC-001","country_code":"US","city_code":"nyc","city_name":"New York","datacenter_id":42,"provider_id":"us-nyc-wg-301","provider_url":"https://mullvad.net/servers","provider_name":"Mullvad VPN","provider_homepage_url":"https://mullvad.net","tier":0}` (`obscuravpn-api/src/cmd/exit2.rs:26-45`).
- `OneRelay` (`obscuravpn-api/src/types.rs:484-511`): `{"id":String,"country_code":String,"city_code":String,"city_name":String,"preferred_exits":[{"id":String}],"ip_v4":"a.b.c.d","ports":[u16],"tls_cert":"<base64 DER>"}` (no `ip_v6` in client builds).
- `WgPubkey`: base64 string of 32 bytes.
- `AccountStatus` (`rs/client_state.rs:70-74`): `{"account_info":AccountInfo,"last_updated_sec":u64}`.
- `AccountInfo` (`obscuravpn-api/src/types.rs:133-208`), snake_case: `active:bool`, `apple_subscription:{active,auto_renew_status,renewal_date:i64,status:u8}|null`, `auto_renews:i64|null`, `current_expiry:i64|null`, `free_month_on_next_payment:bool`, `free_trial:{active:bool,expires_at:i64}|null`, `google_subscription:{active,auto_renew_status,expires_at:i64|null,status:String}|null`, `has_paid:bool`, `has_referrer:bool`, `id:String` (the ACCOUNT NUMBER, secret), `monero_pending_payments:[{id:String,status:"confirming"|"expired"|"failed"|"paid"|"partially_paid"|"unpaid"}]`, `nnn:{applied_months:u16|null,claimed_months:u16,share_id:String}|null`, `primary_funding:"apple_subscription"|"free_trial"|"google_subscription"|"stripe_subscription"|"top_up"|unknown|null`, `received_free_months:u32`, `referral_code:String`, `stripe_subscription:{active,status:String,current_period_start:i64,current_period_end:i64,cancel_at_period_end:bool}|null`, `top_up:{active:bool,credit_expires_at:i64}|null`. Timestamps are unix seconds (`obscuravpn-api/src/time.rs:2`). `active` is THE funded indicator.

---

## 10. Status object (what the UI observes)

### 10.1 Shape **[V]** (field names from `rs/manager.rs:54-74`, enum encodings **[T]**)

`Status` is `#[serde(rename_all = "camelCase")]`, but nested structs keep their own naming (several are snake_case):

```json
{
  "version": "<uuid v4>",
  "vpnStatus": <VpnStatus>,
  "accountId": "<account number>" | null,
  "inNewAccountFlow": false,
  "pinnedLocations": [{"country_code":"US","city_code":"nyc","pinned_at":1700000000}],
  "lastChosenExit": <ExitSelector>,
  "lastExit": <ExitSelector>,
  "apiUrl": "https://v1.api.prod.obscura.net/api",
  "account": {"account_info": <AccountInfo>, "last_updated_sec": 1700000000} | null,
  "autoConnect": false,
  "featureFlags": {"quicFramePadding":null,"killSwitch":null,"forceSmallMtu":null,"tcpTlsTunnel":null},
  "featureFlagKeys": ["quicFramePadding","killSwitch","forceSmallMtu","tcpTlsTunnel"],
  "useSystemDns": false,
  "localNetworkAccess": true,
  "tailscaleBypass": true,
  "wireguardBypass": true,
  "dnsContentBlock": {"ad":false,"tracker":false,"malware":false,"adult":false,"gambling":false,"socialMedia":false}
}
```

- `featureFlags`: values are `true` or `null` (unset). `#[skip_serializing_none]` is placed AFTER `#[derive]` in `rs/config/feature_flags.rs:8-10`, so it has no effect and nulls ARE emitted **[T]**. Unknown keys from config are also flattened in. Treat absent or `null` as false.
- `lastChosenExit` = last non-`any` selector that connected; `lastExit` = last selector that connected (`rs/client_state.rs:409-415`).
- Status does NOT contain: SNI relay, API alternate host, relay list, traffic stats, LAN/DNS effective values in use, config `tunnel_active`. **[V]**

### 10.2 `VpnStatus` (`rs/manager.rs:117-135`, `rename_all` + `rename_all_fields` camelCase) **[T]**

```json
{"disconnected":{}}
{"connecting":{"tunnelArgs":{"exit":{"any":{}}},"connectError":null,"reconnecting":false}}
{"connected":{"tunnelArgs":{"exit":{"city":{"country_code":"US","city_code":"nyc"}}},
              "exit":<OneExit>,"relay":<OneRelay>,
              "clientPublicKey":"<b64>","exitPublicKey":"<b64>","transport":"quic"}}
```

- `transport`: `"quic"` or `"tcpTls"` (`rs/quicwg.rs:204-209`).
- `reconnecting`: true if the connecting state was entered because a live tunnel failed (`disconnect_reason.is_some()`, `rs/manager.rs:147-153`).
- `connectError` (`ConnectErrorCode`, `rs/errors.rs:22-37`): `accountExpired`, `apiError`, `apiRateLimitExceeded`, `apiUnreachable`, `invalidAccountId`, `noInternet`, `noLongerSupported`, `noRelay`, `noSlotsLeft`, `notLoggedIn`, `other`. Mapping in `rs/errors.rs:45-103` (`TunnelLimitExceeded` -> noSlotsLeft, all relays failed -> noRelay, `SetOsNetworkConfig` failure (for example `establish()` returned null) -> other).
- While connecting, Rust retries forever with a 1 s debounce (`DEBOUNCE_PERIOD`, `rs/tunnel_state.rs:177`); exit choice rotates via `ExitSelectionState` on failures.

### 10.3 Version / long-poll mechanism **[V]**

- `status_watch: watch::Sender<Status>`; `propagate_updates_to_status_task` recomputes on every tunnel-state or client-state change and bumps `version = Uuid::new_v4()` ONLY if the new Status differs (`rs/manager.rs:280-305`). `AccountStatus` equality compares only `last_updated_sec` (`rs/client_state.rs:76-82`).
- Client loop: send `getStatus{knownVersion:null}`, store `version`, send `getStatus{knownVersion:<version>}`, repeat. Upstream adds a 10 ms delay between polls (`kt/ui/VpnStatusObserver.kt:30-55`).
- Each pending call holds a JNI global ref and a tokio task until the next change. Keep exactly ONE outstanding `getStatus` per observer. Cancelling on the Java side does not cancel the Rust task. **[V+I]**

### 10.4 What triggers what (tunnel state machine, `rs/tunnel_state.rs:173-383`) **[V]**

`TargetState = {tunnel_args (Some iff config.tunnel_active), network_interface, dns_content_block, use_system_dns, local_network_access, tailscale_bypass, wireguard_bypass}` (`rs/client_state.rs:112-125`).

| Change | Effect |
|---|---|
| `setTunnelArgs` with new `args` (exit) while connected | full reconnect (new relay race + new tunnel) |
| network interface changes (any field) | full reconnect |
| `active:false` | disconnect, TUN closed, key rotation check (30-day max age, `rs/config/persistence.rs:638-645`) |
| DNS content block / system DNS / LAN access while connected | NO reconnect; Rust calls `ffiSetNetworkConfig` again with new config (new TUN fd, seamless handover) |
| tailscale / wireguard bypass | target-state change -> same live re-apply path, Android ignores the values |
| tunnel failure (liveness dead or QUIC error) | `connecting` with `reconnecting:true`, reconnect |

### 10.5 Traffic stats (`rs/manager.rs:423-431`, `rs/tunnel_state.rs:74-92`) **[V]**

`{"connectedMs":u64,"connId":"<uuid>","txBytes":u64,"rxBytes":u64,"latestLatencyMs":u16}`. Bytes are inner IP bytes. Counters accumulate across reconnects of one session (`offset_traffic_stats`); `connId` is stable for the session and a fresh random UUID on every call while disconnected. `latestLatencyMs` = RTT of in-tunnel liveness probes to the gateway (`gateway_ip_v4`, default `10.64.0.1`).

---

## 11. Connection internals relevant to features **[V]**

- Two-hop design: relay (QUIC/TLS endpoint, `OneRelay`) + exit (WireGuard peer, `OneExit`, currently Mullvad servers). WireGuard is end-to-end client <-> exit; the relay forwards WG messages. Tunnel created by API `CreateTunnel::Obfuscated { id, label: None, wg_pubkey, relay: Some(relay_id), exit: Some(exit_id) }` (`rs/client_state.rs:474-480`).
- ENTRY RELAY IS NOT USER-SELECTABLE via the FFI. `select_relay` (`rs/client_state.rs:512-608`) races handshakes to up to 100 relays x 5 ports (`rs/relay_selection.rs:88-96`), measures min RTT of 3 pings each, deadline 30 s, then shrinks to `min(3 x time-to-first-success, 5 s)`, stops after 5 successful relays, picks the lowest RTT. No ManagerCmd lists relays.
- Exit choice: `ExitSelector` filter, then rank `(relay preferred exit, same city as relay, same country, tier, random)` and exclude repeats after failures (per datacenter 2, city 4, country 6) (`rs/exit_selection.rs:51-86`).
- QUIC transport (`rs/quicwg.rs:712-747`): rustls with ring provider, cert PINNED to `relay.tls_cert` (exact DER match, no CA, `VerifyVpnServerCert` lines 1046-1066), ALPN `h3`, SNI from `sni_relay` (default `example.com`), no streams except one control bidi stream, BBR congestion control, MTU discovery up to 1350, black-hole cooldown 10 s, idle timeout 600 s, `pad_to_mtu(quicFramePadding)`.
- TCP/TLS fallback: ONLY when flag `tcpTlsTunnel` is set (no automatic fallback), ALPN `h2`, `TCP_NODELAY` (`rs/quicwg.rs:587-617`, `rs/client_state.rs:536-553`).
- Fragmentation: WG messages larger than the QUIC datagram limit split in 2 (`WG_FRAGMENT_MAX_SIZE` 1540, reassembly buffer 10 000 fragments) (`rs/quicwg.rs:70-83`).
- Liveness: ICMP-style probes with a fixed 32-byte prefix to the gateway; 1 s period when busy, 55 s when idle; dead after 4 lost probes (1 after sleep) (`rs/liveness.rs:11-27`).
- API client: unauthenticated/authenticated `reqwest` client with alternate host and a no-SNI retry path; custom DNS resolver with an on-disk cache seeded with `v1.api.prod.obscura.net -> <API IP>` (`rs/constants.rs:6-9`, `rs/dns.rs:18-41`). All API traffic leaves outside the tunnel (app is excluded from the VPN).

---

## 12. Config file format and persistence

- Path: `<configDir>/config.json`, `configDir = context.getFilesDir().getAbsolutePath()` (`ObscuraLibrary.java:20`), so `/data/user/<u>/<applicationId>/files/config.json`. Shared by all processes of the app. **[V]**
- Write: on every config change (`ConfigHandle::change`, `rs/config/mod.rs:26-39`): `serde_json::to_vec_pretty` -> `NamedTempFile` in the same dir -> `write_all` + `flush` + `sync_data` -> atomic `persist` rename (`rs/config/persistence.rs:136-212`). Save errors are logged, retried on the next change; `login`/`logout` report `configSaveError`.
- Load: missing file -> defaults. Unparsable file -> renamed to `config-backup-<YYYY-MM-DDTHH-MM-SS.mmm>.json`, defaults written (`persistence.rs:75-134`). Every field uses `serde_safe::deserialize`: an invalid field resets to its default, not the whole file (`rs/serde_safe.rs`). **[V]**
- Logs: `<configDir>/rust-log/rust-log.ndjson` (JSON lines), size rotation 10 MB, keep 24 files, xz compressed (`rs/logging.rs:43-70`; `RUST_LOG_DIR_NAME`, ffi.rs:39). Logcat tag `ObscuraNative`.
- Debug bundles: `<cacheDir>/debug-archives/`.

`Config` JSON keys (field names are snake_case, no rename; `#[serde(default)]`) from `rs/config/persistence.rs:226-291` **[V]**:

| Key | Type / JSON | Default | Meaning |
|---|---|---|---|
| `api_host_alternate` | string or null | null (-> `crimsonlance.net`) | API backup host |
| `api_url` | string or null | null (-> `https://v1.api.prod.obscura.net/api`) | API base URL |
| `account_id` | string or null | null | ACCOUNT NUMBER (secret) |
| `auto_connect` | bool | false | UI hint |
| `dns_content_block` | DnsContentBlock | all false | |
| `dns_cache` | `{"entries":{"<host>":["ip:port",...]}}` | seeded API IP | API DNS cache |
| `old_account_ids` | [string] | [] | previous account numbers (secret) |
| `feature_flags` | FeatureFlags | all null | |
| `in_new_account_flow` | bool | false | |
| `cached_auth_token` | string or null | null | API bearer token (secret) |
| `cached_exits` | `{"version":{"ETag":[bytes]}|{"Artificial":"uuid"},"last_updated":{"secs_since_epoch":..,"nanos_since_epoch":..},"value":{"exits":[...]}}` or null | null | exit list cache |
| `cached_relays` | same wrapper with `value:[OneRelay]` | null | relay list cache |
| `pinned_locations` | [PinnedLocation] | [] | favourites |
| `last_chosen_exit` | string or null | null | deprecated, migration only |
| `last_chosen_exit_selector` | ExitSelector | `{"any":{}}` | |
| `last_exit_selector` | ExitSelector | `{"any":{}}` | |
| `tunnel_active` | bool | false | forced false at each process start on Android |
| `tunnel_args` | TunnelArgs | `{"exit":{"any":{}}}` | persisted target exit |
| `sni_relay` | string or null | null (-> `example.com`) | relay SNI |
| `wireguard_key_cache` | `{"type":"plaintext","secret_key":"<b64 32B>","first_use":<s>|null,"registered_at":<s>|null,"old_public_keys":["<b64>"]}` | none until first use | WG key (PLAINTEXT secret on Android) |
| `dns` | `"Default"` or `"System"` | `"Default"` | system DNS toggle |
| `local_network_access` | `"Enabled"` or `"Disabled"` | `"Enabled"` | |
| `tailscale_bypass` | `"Enabled"` or `"Disabled"` | `"Enabled"` | Linux only |
| `wireguard_bypass` | `"Enabled"` or `"Disabled"` | `"Enabled"` | Linux only |
| `cached_account_status` | AccountStatus or null | null | |

`SystemTime` encodes as `{"secs_since_epoch":N,"nanos_since_epoch":N}` **[T]**. The key cache serialization: `WireGuardKeyCacheKeyPair` is internally tagged `"type"` snake_case and flattened into `wireguard_key_cache` (`persistence.rs:421-455`); Android uses `WgKeyStore::Plaintext` (ffi.rs:60-62, TODO OBS-2699).

---

## 13. Kotlin side cross-check: how Kotlin calls Rust and decodes **[V]**

- `kt/client/RustFfi.kt:7-40`: thin wrapper holding the `FfiHandle`; `jsonFfi(json, CompletableFuture<String>)`, `setNetworkInterface`, `unsetNetworkInterface`, `flushAndStopPersistedLog`, `logger(tag)`.
- `kt/client/JsonConfig.kt:6-10`: `Json { classDiscriminatorMode = ClassDiscriminatorMode.NONE; encodeDefaults = true; ignoreUnknownKeys = true }`.
- Externally tagged enums: `ExternallyTaggedEnumVariantSerializer(tag, serializer)` wraps on encode as `{tag: <obj>}` and unwraps `element.jsonObject[tag]` on decode; `ExternallyTaggedEnumSerializer` picks the variant whose tag key is present (`android/lib/util/src/main/java/net/obscura/lib/util/ExternallyTagged*.kt`).
- `kt/client/ManagerCmd.kt`: only 5 commands are typed in Kotlin: `ApiGoogleAssociateAccount(purchaseToken, promoCode)`, `ApiGoogleBillingDetails(promoCode)`, `CreateDebugBundle(userFeedback, bundleInfo, androidCacheDir)` with `@SerialName("AndroidSDK")`, `"AppVersion"`, ... PascalCase `BundleInfo`, `GetStatus(knownVersion: String?)`, `SetTunnelArgs(args: Map<String, JsonObject>? = null, active: Boolean? = null)`. All other commands come as raw JSON strings from the WebView (`WebCmd.JsonFfiCmd(cmd)`, `kt/ui/bridge/WebCmd.kt:122-132`).
- `kt/client/ManagerCmdOk.kt`: decodes `ApiGoogleBillingDetails` (snake_case `@SerialName`) and a PARTIAL `GetStatus(accountId, autoConnect, inNewAccountFlow, version, vpnStatus)` where `VpnStatus` variants `connected`/`connecting`/`disconnected` are empty marker classes.
- Service-internal calls: `loadStatus(knownVersion)` encodes `ManagerCmd.GetStatus` and long-polls (`ObscuraVpnService.kt:353-367`); `setTunnelArgs(exit: String?, active)` decodes the intent extra string into `Map<String, JsonObject>` (`ObscuraVpnService.kt:369-381`). Note: the intent extra is named `"exitSelector"` but carries the full `TunnelArgs` JSON, for example `{"exit":{"city":{"country_code":"US","city_code":"nyc"}}}` (web UI builds it: `obscura-ui/src/bridge/commands.ts:150-157`).
- UI process -> service: AIDL `android/app/src/main/aidl/net/obscura/vpnclientapp/services/IObscuraVpnService.aidl` = `void stopTunnel(); void jsonFfi(long id, String command);` (not `oneway`). Service `Binder.jsonFfi` creates a `CompletableFuture`, calls Rust, and in `future.handle{}` sends an explicit broadcast to `JsonFfiBroadcastReceiver` with extras `"id"` (long), `"value"` (String?), `"errorCode"` (String?) (`ObscuraVpnService.kt:71-97`, `kt/services/IntentExtension.kt:10-54`). The UI keeps `ConcurrentHashMap<Long, CompletableDeferred<String>>` keyed by a random id (`kt/ui/JsonFfiBroadcastReceiver.kt:16-49`). Missing value and missing error code -> `ErrorCodeException("other")`.
- Bind flags: `BIND_ABOVE_CLIENT or BIND_AUTO_CREATE` (`kt/services/ContextExtension.kt:14-28`). Service is `android:process=":vpnservice"`, `exported=false`, `permission=BIND_VPN_SERVICE`, `foregroundServiceType="systemExempted"` (`android/app/src/main/AndroidManifest.xml:61-72`).
- Extra Java-only error codes (not from Rust) used by the UI: `playServicesDisabled`, `playServicesMissing`, `playServicesUpdateRequired`, `playServicesUpdating`, `purchaseFailed`, `purchaseFailedAlreadyOwned`, `errorLegacyAlwaysOn`, `errorOtherAppAlwaysOn`, `errorPermissionNotGranted`, `errorUnsupportedOnOS` (`kt/client/ErrorCodeException.kt:9-35`).

---

## 14. Feature coverage vs. the requested app **[V for "exposed", I for patches]**

| Feature | Exposed by current FFI? | How |
|---|---|---|
| Connect / disconnect | Yes | `setTunnelArgs` + `getStatus` |
| Exit choice (any / country / city / exact server) | Yes | `ExitSelector`; list via `refreshExitList` + `getExitList` |
| Entry relay choice | NO | Patch needed: add e.g. `relay_selector` to `Config`, a `SetRelaySelector` cmd, filter `relays.value` in `select_relay` before `race_relay_handshakes` (`rs/client_state.rs:521-553`), plus a `GetRelayList` cmd exposing `config.cached_relays`. |
| Show current entry relay + exit + keys + transport | Yes | `vpnStatus.connected` |
| QUIC (default) | Yes | always on |
| TCP/TLS fallback | Yes (manual) | flag `tcpTlsTunnel` |
| QUIC padding (anti traffic analysis) | Yes | flag `quicFramePadding` |
| Small MTU mode | Yes | flag `forceSmallMtu` |
| Custom relay SNI | Yes (write-only) | `setSniRelay` (not readable from Status) |
| DNS: tunnel DNS (default) | Yes | automatic |
| DNS: content blocking (ads, trackers, malware, adult, gambling, social) | Yes, Mullvad exits only | `setDnsContentBlock` |
| DNS: system DNS | Yes | `setUseSystemDns` (upstream UI never sets it) |
| LAN access toggle | Yes | `setLocalNetworkAccess` (default ON) |
| Kill switch | OS-level only | Always-on + lockdown in Android settings; Rust blocks traffic during connecting/reconnecting |
| WG key rotation | Yes | `rotateWgKey`; auto every 30 days |
| Traffic stats / latency | Yes | `getTrafficStats` |
| Account info / login / logout / delete | Yes | `apiGetAccountInfo`, `login`, `logout`, `apiDeleteAccount` |
| Service status (free trial) | Yes | `apiServiceStatus` |
| Favourites | Yes | `setPinnedExits` |
| Wake hint after Doze | NO | Apple-only `wake`; add a JNI export calling `Manager::wake()` |
| Hardware-backed WG key | NO | `WgKeyStore::Keychain { secret_key: Option<Vec<u8>>, set_secret_key: Box<dyn Fn(&[u8;32]) -> bool> }` exists (`rs/wg_key_store/mod.rs:81-93`); could be wired to Android Keystore-encrypted storage via a new init parameter |
| Disable on-disk Rust logs | NO | `initialize` always passes `Some(<configDir>/rust-log)`; patch to make it optional |
| TUN read batching | NO | Port Linux `PacketBuffer` loop (`rs/bin/obscura/service/os/linux/tun.rs:72-166`) to `rs/android/tunnel.rs` |

---

## 15. Privacy and security findings in upstream to NOT copy **[V]**

1. Account number in logs: `ObscuraVpnService.kt:325` logs `"status updated $status"` and `:363` logs `"getStatus completed $data"` (raw JSON with `accountId` and `account.account_info.id`) through the forwarding logger, so it lands in logcat AND in persisted `rust-log` files. UI process logs commands and results at TRACE via `Log.v` (`kt/ui/JsonFfiBroadcastReceiver.kt:29`, `kt/services/IntentExtension.kt:47`), which includes `login` commands with the account number.
2. Backups: `android:allowBackup="true"`; `data_extraction_rules.xml` excludes only `rust-log`, NOT `config.json`, which holds the account number, auth token and the PLAINTEXT WireGuard private key. Set `allowBackup="false"` (and exclude `files/` in extraction rules).
3. WG private key in plaintext JSON on disk (`WgKeyStore::Plaintext`).
4. `getDebugInfo` and `createDebugBundle` make network probes to apple.com and google.com and collect `logcat -d`.
5. LAN access default ENABLED: LAN-range traffic bypasses the tunnel by default.
6. Whole app UID excluded from the VPN (`addDisallowedApplication`): anything the app itself sends (API, any SDK) is visible to the local network. Keep the app free of third-party network SDKs.
7. User-Agent `obscura.net/android/<version>` is sent to the API; a distinct UA for a third-party client is a fingerprint. **[I]**
8. Upstream Android Private DNS (DoT) overrides VPN DNS; upstream only warns (`kt/ui/PrivateDnsObserver.kt`).

---

## 16. Implementation checklist for the new app **[I, based on V facts above]**

1. Keep (or patch) the 3 class FQNs + member signatures of section 3; add R8 keep rules.
2. Load and `initialize` once per process, on a thread with the app class loader, BEFORE anything else; any failure aborts the process (panic=abort).
3. Set the static service instance used by `ffiSetNetworkConfig` before the first connect; make `ffiSetNetworkConfig` and `ffiGetProcessExitReasons` total (catch `Throwable`, return -1 / null).
4. Register a best-matching `NetworkCallback` (`INTERNET` + `NOT_VPN`) and forward `setNetworkInterface(name, index)` / `unsetNetworkInterface()`; also call `setUnderlyingNetworks`. Consider handling `onLinkPropertiesChanged`.
5. Wrap `jsonFfi` in a suspend function: catch the synchronous `RuntimeException`; on completion, resume on your dispatcher; map `ErrorCodeException.errorCode`.
6. One `getStatus` long-poll loop per consumer, `knownVersion` = last `version`.
7. Exit list: `refreshExitList{freshness:60000}` on screen open, plus one `getExitList` long-poll with the last `version`.
8. Connect: `{"setTunnelArgs":{"args":{"exit":SEL},"active":true}}`; disconnect: `{"setTunnelArgs":{"args":null,"active":false}}`.
9. Persist "VPN wanted" yourself; re-send `active:true` after a sticky restart, always-on start, or process restart.
10. Builder: `addDisallowedApplication(own pkg)` (required, Rust does not `protect()`), `setMtu(1280)`, `setMetered(false)`, DNS unless `useSystemDns`, v4+v6 addresses, routes, `allowFamily` both; return `detachFd()`; never close that fd in Java.
11. Do not log Status JSON, AccountInfo, or commands.
12. Build: `cargo ndk -t arm64-v8a build --release --lib` with Rust 1.89.0 (`rustlib/rust-toolchain.toml`), output `target/aarch64-linux-android/release/libobscuravpn_client.so` -> `app/src/main/jniLibs/arm64-v8a/` (`flake.nix:110-133`, `374`). Upstream NDK 26.3.11579264 with 16 KB page alignment forced by `rustlib/.cargo/config.toml:1-7` (`-z max-page-size=16384`, `-z common-page-size=16384`). Release profile: `lto = true`, `codegen-units = 1`, `panic = "abort"`. License: `GPL-3.0-only WITH GPL-3.0-linking-source-exception` (`rustlib/Cargo.toml:5`).
