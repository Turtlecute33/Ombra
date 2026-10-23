# build: rustlib for Android on this Mac, without Nix

Upstream HEAD: `85ef7e2692dc811a023360f08865bfb90815649c` (2026-10-19). Work copy: `/tmp/obscura-research/build-test`. The upstream clone is not changed (`git status --porcelain` is empty).

## 1. Result

- VERIFIED: rustlib builds for Android on this Mac with rustup + cargo-ndk 4.1.2 + the Android NDK. Nix, cmake, bindgen/libclang, nasm, Go and perl are not necessary for the Android targets.
- VERIFIED: the upstream command (`cargo ndk -t arm64-v8a build --release --lib`, NDK 26.3.11579264, Rust 1.89.0) builds in about 64 s from clean. The output is `libobscuravpn_client.so`: 60.6 MB unstripped, 10.7 MB stripped.
- VERIFIED: all LOAD segments have `Align 0x4000` (16 KB) for arm64-v8a and x86_64, with NDK r26d and with NDK r28.
- VERIFIED: x86_64 also builds (57 s).
- VERIFIED: `-P 35` (API 35) builds with NDK 28.0.13004108. It fails with NDK 26.3 because r26d has no API 35 sysroot.
- VERIFIED: host `cargo test --locked` passes on macOS: 11 passed, 0 failed (63 s).
- VERIFIED: obscura-ui is not necessary for the Android build of rustlib.
- VERIFIED: the draft zsh script (section 9) works. It builds arm64-v8a + x86_64, strips, checks 16 KB alignment, removes build paths, and is bit-for-bit reproducible for the stripped `.so` across target dirs.

## 2. How upstream builds the Android .so (facts from the sources)

| Item | Value | Source |
|---|---|---|
| Rust toolchain | `channel = "1.89.0"`, `components = ["rustfmt"]`, `targets = ["aarch64-linux-android"]` | `rustlib/rust-toolchain.toml:1-4` |
| NDK | `ndkVersion = "26.3.11579264"` (r26d) | `flake.nix:64` |
| NDK env | `ANDROID_NDK_ROOT = "${android.ndk-bundle}/libexec/android-sdk/ndk-bundle"` | `flake.nix:76-78` |
| Other SDK pins | build-tools `37.0.0`, cmake `3.31.6`, platform `36`, platform-tools `37.0.1` | `flake.nix:49-70` |
| Build command | `cargoBuildCommand = "cargo ndk -t arm64-v8a build --release --lib";` (crane adds `--locked`) | `flake.nix:120` |
| Cargo target | `CARGO_BUILD_TARGET = "aarch64-linux-android";` `doCheck = false;` | `flake.nix:116-117` |
| Native build inputs | `pkgs.cmake`, `pkgs.pkg-config`, `pkgs.cargo-ndk` | `flake.nix:93-96,115` |
| Install + strip | `install -D -t "$out/lib" target/aarch64-linux-android/release/libobscuravpn_client.so` then `"$ANDROID_NDK_ROOT"/toolchains/llvm/prebuilt/*/bin/llvm-strip "$out/lib/libobscuravpn_client.so"` | `flake.nix:130-133` |
| Lib env vars | `OBSCURA_CLIENT_RUSTLIB_CBINDGEN_CONFIG_PATH = ./apple/cbindgen-apple.toml`, `OBSCURA_CLIENT_RUSTLIB_CBINDGEN_OUTPUT_HEADER_PATH = ".../include/libobscuravpn_client.h"`, `OBSCURA_VERSION = version` | `flake.nix:139-148` |
| Version format | `if isCleanBuild then "v${tag.version}" else "v${tag.version}.1-${commitShort}"`; `tag.json` = `{"version": "1.182", ...}` | `flake.nix:45`, `tag.json` |
| Gradle input (Nix) | `ln -sfv ${rust-android}/lib/libobscuravpn_client.so app/src/main/jniLibs/arm64-v8a/` | `flake.nix:374` |
| Gradle input (dev) | committed symlink `android/app/src/main/jniLibs/arm64-v8a/libobscuravpn_client.so -> ../../../../../../rustlib/target/aarch64-linux-android/release/libobscuravpn_client.so` (unstripped) | file system |
| Dev command | `nix develop '.#android' --command bash -c 'cd rustlib && cargo ndk -t arm64-v8a build --release'` | `README.md:126-129` |
| Java load | `System.loadLibrary("obscuravpn_client");` | `android/app/src/main/java/net/obscura/vpnclientapp/client/ObscuraLibrary.java:19` |
| ABIs | arm64-v8a only. No `ndkVersion`, `abiFilters`, `packaging` or `externalNativeBuild` in `android/app/build.gradle.kts` | `android/app/build.gradle.kts:11-70` |
| Upstream SDK levels | `buildToolsVersion = "37.0.0"`, `compileSdk = 36`, `minSdk = 31`, `targetSdk = 36`; AGP `8.13.0` | `android/app/build.gradle.kts:12-20`, `android/gradle/libs.versions.toml:3` |

### Cargo profile and crate type (`rustlib/Cargo.toml`)
- `[lib] crate-type = ["cdylib", "lib", "staticlib"]` (line 17). The build also writes a 272 MB `.a` and a `.rlib`. Only the `.so` is used.
- `[profile.release] codegen-units = 1`, `debug = "line-tables-only"`, `lto = true`, `panic = "abort"` (lines 19-23).
- Android-only deps: `cesu8 = "1.1.0"`, `jni = { version = "0.21.1", default-features = false }`, `nix = "0.30.1"`, `tracing-android = "0.2.0"` (lines 122-126).
- `aws-lc-rs` with feature `bindgen` is only for `cfg(any(target_os = "macos", target_os = "ios"))` (lines 101-104). On Android, aws-lc-rs comes in through rustls defaults (see section 4).
- `logroller = { version = "0.1.10", features = ["xz"] }` for `cfg(not(target_os = "macos"))` (lines 152-153). This pulls in `lzma-sys` (C, built with `cc`).
- Features: only `gui = ["dep:gtk4", "dep:libadwaita", "dep:webkit6"]` (line 162). No Android feature. Upstream uses default features for Android.
- Git deps (pinned by rev in `Cargo.lock`): `Sovereign-Engineering/boringtun` (package `neptun`) `ff3b5555...`, `Sovereign-Engineering/obscuravpn-api` `e94483e7...`, `Sovereign-Engineering/reqwest` `87cc6fc6...`, `NordSecurity/rust-dispatch` `13447cd7...`. Android normal-dependency tree: 377 unique crates.

### `rustlib/.cargo/config.toml` (lines 1-7): the 16 KB fix
```toml
[target.'cfg(target_os = "android")']
rustflags = [
    "-C",
    "link-arg=-Wl,-z,max-page-size=16384",
    "-C",
    "link-arg=-Wl,-z,common-page-size=16384",
]
```
This makes NDK r26d (default 4 KB) output 16 KB aligned. NDK r28 already defaults to 16 KB. IMPORTANT: a `RUSTFLAGS` or `CARGO_ENCODED_RUSTFLAGS` env var replaces these flags. `CARGO_TARGET_<TRIPLE>_RUSTFLAGS` is joined with them (VERIFIED, section 6).

### `rustlib/build.rs`
- Prints `OBSCURA_VERSION` (lines 26-29). It only runs cbindgen when both `OBSCURA_CLIENT_RUSTLIB_CBINDGEN_CONFIG_PATH` and `OBSCURA_CLIENT_RUSTLIB_CBINDGEN_OUTPUT_HEADER_PATH` are set (lines 43-57). If not set, it prints a warning and returns. The C header is for Apple only. Android uses JNI, so we do not set them.
- Windows-only parts (wintun DLL, package family name) are behind `#[cfg(target_os = "windows")]` (lines 34-40, 70-143).
- No `cargo:rerun-if-*` (lines 11-23, by design). Effect: a new or changed file anywhere in the package dir (git-ignored files excluded when in git) runs the build script again and recompiles the crate (about 26 s with LTO). VERIFIED: extra target dirs that I put inside `rustlib/` caused this. Keep `CARGO_TARGET_DIR` outside `rustlib/`.

### Compile-time env in Rust code
- `rustlib/src/version.rs:2`: `option_env!("OBSCURA_VERSION").unwrap_or("v0.0.0-dev")`. On Android it goes into the debug bundle (`rustlib/src/debug_bundle/bundle_info.rs:52`, `app_version`). Other uses are Linux/CLI/GUI only. The HTTP user agent comes from Java at run time (`initialize(..., j_user_agent, ...)`, `rustlib/src/android/ffi.rs:47-63`).
- `OBSCURA_GRESOURCES_DIR`: only in `rustlib/src/gui/gtk_gui.rs:62-63` (`gui` feature, Linux bin).

### xtask, justfile, about.toml
- `xtask/` is a lint helper (`check-duplicates`, `fix` of message ids). It is not part of the Android build.
- `justfile` has no Android or Rust build recipe. `justfile:82-84` pins `cargo-about@0.6.4` for license JSON on Windows.
- `rustlib/about.toml` is for `cargo-about` license output. It accepts `GPL-3.0 WITH GPL-3.0-linking-source-exception` for obscuravpn-api and obscuravpn-client, and lists permissive licenses. `[[obscuravpn-client.clarify.files]] path = "../LICENSE"` means cargo-about needs `LICENSE` one level above `rustlib/`. This is not needed to build the `.so`, but we need it for a license screen (section 11).

## 3. Local environment (checked)

| Tool | State |
|---|---|
| macOS | 27.0.1 arm64, 10 cores |
| rustup | 1.29.1. Default toolchain `stable` = rustc 1.98.1. `1.86.0` also installed. `stable` has no Android targets |
| cargo-ndk | 4.1.2 (`~/.cargo/bin/cargo-ndk`, plus `cargo-ndk-env`, `cargo-ndk-runner`, `cargo-ndk-test`) |
| NDK | `~/Library/Android/sdk/ndk/26.3.11579264` (r26d, API 21-34) and `28.0.13004108` (r28, API 21-35). Both present before this work |
| SDK | platforms `android-35`, `android-36`, `android-37.0`; build-tools 34.0.0, 35.0.0, 36.0.0, 37.0.0; cmdline-tools latest; SDK cmake 3.22.1 |
| Homebrew | cmake 4.4.4, ninja 1.13.2, go; no nasm |
| JDK | `/opt/homebrew/opt/openjdk@21` (21.0.12.1) |
| Env | `ANDROID_HOME` set. No `ANDROID_NDK_*`, `CC`, `AR`, `CFLAGS`, `RUSTFLAGS` set |

NDK choice in cargo-ndk 4.1.2 (VERIFIED with `cargo ndk-env`): `ANDROID_NDK_HOME` comes first, then `ANDROID_NDK_ROOT`, then the newest dir in `$ANDROID_HOME/ndk/` (here 28.0.13004108). Upstream only sets `ANDROID_NDK_ROOT`. If you do not set it, this Mac uses r28.

Env that cargo-ndk sets for arm64-v8a (from `cargo ndk-env -t arm64-v8a`, NDK path shortened):
```
AR_aarch64_linux_android=<ndk>/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar
CC_aarch64_linux_android=<ndk>/.../bin/clang
CXX_aarch64_linux_android=<ndk>/.../bin/clang++
RANLIB_aarch64_linux_android=<ndk>/.../bin/llvm-ranlib
CFLAGS_aarch64_linux_android=--target=aarch64-linux-android21     (21 = -P value)
CXXFLAGS_aarch64_linux_android=--target=aarch64-linux-android21
BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android=--sysroot=<ndk>/.../sysroot -I<ndk>/.../sysroot/usr/include/aarch64-linux-android
CLANG_PATH=<ndk>/.../bin/clang
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=~/.cargo/bin/cargo-ndk   (cargo-ndk wraps clang)
CARGO_TARGET_AARCH64_LINUX_ANDROID_AR=<ndk>/.../bin/llvm-ar
CARGO_TARGET_AARCH64_LINUX_ANDROID_RUNNER=~/.cargo/bin/cargo-ndk-runner
CARGO_NDK_SYSROOT_PATH / CARGO_NDK_SYSROOT_TARGET / CARGO_NDK_SYSROOT_LIBS_PATH
```
cargo-ndk does not set `RUSTFLAGS`, so `.cargo/config.toml` rustflags apply (VERIFIED: aws-lc-sys build output shows `CARGO_ENCODED_RUSTFLAGS` = the two 16 KB link args).

## 4. Native C deps on Android (VERIFIED from build-script output)

| Crate | Why | How it builds for Android |
|---|---|---|
| `aws-lc-sys 0.37.1` (via `aws-lc-rs 1.15.4`) | rustls default provider. `rustls::crypto::aws_lc_rs::default_provider().install_default()` in `rustlib/src/android/ffi.rs:54-56` (API HTTPS through obscuravpn-api / reqwest) | `cargo:warning=Building with: CC`, `Symbol Prefix: Some("aws_lc_0_37_1")`, `rustc-cfg=universal`. It uses the cc builder and pre-generated bindings. No cmake, bindgen, libclang, nasm, Go or perl. It selects cmake only if bindgen is necessary or `AWS_LC_SYS_CMAKE_BUILDER`/`AWS_LC_SYS_NO_ASM` is set (`aws-lc-sys-0.37.1/builder/main.rs:430-470`) |
| `ring 0.17.14` | `rustls::crypto::ring::default_provider()` for the QUIC relay TLS (`rustlib/src/quicwg.rs:713`), neptun (WireGuard), quinn-proto | `cc` with NDK clang |
| `lzma-sys 0.1.20` (via `xz2` via `logroller`) | rotated log compression | `cc` with NDK clang |
| `cmake 0.1.54` crate | build-dependency of aws-lc-sys only | compiled but not run on Android |

Both crypto libraries are in the `.so` (VERIFIED: 1004 `aws_lc_0_37_1` symbols and 72 `ring_core_0_17_14` symbols in the unstripped `.so`).

## 5. Builds run (exact commands, env, durations)

Common setup (once):
```zsh
rustup toolchain install 1.89.0 --profile minimal --component rustfmt \
  --target aarch64-linux-android --target x86_64-linux-android      # 28 s
mkdir -p /tmp/obscura-research/build-test
cp -R /tmp/obscura-research/obscuravpn-client/rustlib /tmp/obscura-research/build-test/
cp /tmp/obscura-research/obscuravpn-client/{LICENSE,tag.json} /tmp/obscura-research/build-test/   # not used by cargo
cd /tmp/obscura-research/build-test/rustlib    # rust-toolchain.toml selects 1.89.0
cargo fetch --locked                            # 47 s, cold cache
```
Only `rustlib/` is necessary to build. Nothing outside it is read on Android (`../windows` is read only on Windows, `build.rs:127-142`).

| # | Command (in `build-test/rustlib`) | Env | Result | Time |
|---|---|---|---|---|
| A | `cargo ndk -t arm64-v8a build --release --lib --locked` (upstream exact) | `ANDROID_NDK_HOME=ANDROID_NDK_ROOT=~/Library/Android/sdk/ndk/26.3.11579264`, `OBSCURA_VERSION=v1.182.1-85ef7e2` | OK. `target/aarch64-linux-android/release/libobscuravpn_client.so` 60,576,624 B; stripped 10,692,592 B | 64 s clean (325 crates) |
| B | `cargo ndk -t x86_64 build --release --lib --locked` | same | OK. Unstripped 59,697,528 B; stripped 12,812,608 B | 57 s |
| C | `CARGO_TARGET_DIR=<separate> cargo ndk -t arm64-v8a -P 35 build --release --lib --locked` | NDK 28.0.13004108 | OK. 60,583,088 B; stripped 10,636,576 B; `.note.android.ident` = API 35 (0x23), "r28" | 64 s clean |
| D | same as C | NDK 26.3.11579264 | FAIL: `ld.lld: error: cannot open crtbegin_so.o`, `unable to find library -llog`, `cannot open crtend_so.o` (r26d sysroot stops at API 34) | 31 s |
| E | same as C + `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="--remap-path-prefix=..."` | NDK 28 | OK. 0 home-dir strings, still 16 KB | 62 s |
| F | `cargo ndk -t arm64-v8a -o <dir> build --release --lib --locked` | NDK 26 | OK, but `-o` also copied `libneptun-<hash>.so` (5.9 MB) and `libtun_rs-<hash>.so` (5.9 MB) dependency cdylibs, and did NOT strip. Do not use `-o` | 27 s |
| G | draft script (section 9), `ABIS="arm64-v8a x86_64"`, new target dir | NDK 28, API 35 | OK. arm64 10,624,288 B, x86_64 12,784,256 B, both 16 KB | 136 s total (69 s + 64 s) |
| H | draft script again, arm64 only, another new target dir | same | Stripped sha256 the same as G: `e68173cc91a08fdecee95c21336bee1766574aeabdaefc3007b2ee0d4c383699`. Unstripped differs (debug info has the target-dir path) | 65 s |
| I | draft script, nothing changed | same | no-op | 1 s |
| J | `cargo test --locked` (host aarch64-apple-darwin) | no special env | 11 passed, 0 failed (lib); 0 tests in bin and doc-tests | 63 s |

Notes on J (VERIFIED): on macOS, aws-lc-sys uses CMake + internal bindgen because of the `bindgen` feature for Apple (`Building with: CMake`, `Clang version: Apple clang version 21.0.0`). Homebrew cmake 4.4.4 and Xcode libclang worked. This applies to host builds only, not to Android. The tests run in temp dirs and do not use the network or the Keychain (`rustlib/src/config/persistence_test.rs`, `local_network.rs`, `liveness.rs`, `backoff_test.rs`, `config/feature_flags.rs`).

## 6. ELF checks of the output (VERIFIED, NDK `llvm-readelf`)

Upstream-exact arm64 build (A), stripped:
```
  Type   Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flg Align
  LOAD   0x000000 0x0000000000000000 0x0000000000000000 0x3565d4 0x3565d4 R   0x4000
  LOAD   0x356600 0x000000000035a600 0x000000000035a600 0x638a90 0x638a90 R E 0x4000
  LOAD   0x98f0a0 0x00000000009970a0 0x00000000009970a0 0x09f268 0x09f268 RW  0x4000
  LOAD   0xa2e308 0x0000000000a3a308 0x0000000000a3a308 0x003dc0 0x00a3a0 RW  0x4000
  GNU_RELRO ... R 0x1     GNU_STACK ... RW 0x0
```
x86_64 (B):
```
  LOAD 0x000000 ... R   0x4000
  LOAD 0x3410c0 ... R E 0x4000
  LOAD 0xb8f560 ... RW  0x4000
  LOAD 0xc33df8 ... RW  0x4000
```
Script build arm64 (G, NDK 28, API 35): four LOAD segments, all `0x4000`.

Other facts (all builds):
- `ELF64`, `DYN`, `AArch64` / `X86-64`.
- `NEEDED`: `liblog.so`, `libdl.so`, `libm.so`, `libc.so` only. No `libc++_shared.so`, so `--link-libcxx-shared` is not necessary.
- `FLAGS BIND_NOW`, `FLAGS_1 NOW` + `GNU_RELRO`: full RELRO. `GNU_STACK RW`: the stack is not executable.
- No `DT_SONAME`. No `.note.gnu.build-id`.
- Dynamic exports (7 symbols): `JNI_OnLoad`, `Java_net_obscura_vpnclientapp_client_ObscuraLibrary_{initialize,jsonFfi,setNetworkInterface,unsetNetworkInterface,flushAndStopPersistedLog,forwardLog}`.
- Build-path leak (privacy): the upstream-style build has 637 strings with `/Users/<user>/.cargo/...` (panic locations) in the STRIPPED `.so`, so the developer's macOS user name is in the APK. With `--remap-path-prefix` (E, G) the count is 0 and the `.so` stays 16 KB aligned. VERIFIED: `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS` is joined with the config-file flags (aws-lc-sys saw `--remap-path-prefix=...` followed by `-Clink-arg=-Wl,-z,max-page-size=16384 -Clink-arg=-Wl,-z,common-page-size=16384`).

Effect on our Java/Kotlin code (VERIFIED from the sources): JNI names and `FindClass` names are fixed in Rust. These classes must exist with these exact names, or `JNI_OnLoad`/`initialize` fails:
- `net/obscura/vpnclientapp/client/ObscuraLibrary` (static natives: `initialize(String configDir, String userAgent): FfiHandle`, `jsonFfi(FfiHandle, String, CompletableFuture<String>)`, `setNetworkInterface(FfiHandle, String, int)`, `unsetNetworkInterface(FfiHandle)`, `forwardLog(int, String, String, String, String)`, `flushAndStopPersistedLog()`; `ObscuraLibrary.java:23-32`)
- `net/obscura/vpnclientapp/client/ObscuraLibrary$FfiHandle` (`rustlib/src/android/class_cache.rs:19`)
- `net/obscura/vpnclientapp/client/ErrorCodeException` (`class_cache.rs:21`)
- `net/obscura/vpnclientapp/services/ObscuraVpnService` (`class_cache.rs:23`)

The applicationId can be different. Only these class names (FQN) matter. R8 must keep them (upstream uses `@Keep` on `FfiHandle`). Upstream only allows the library in the `:vpnservice` process (`ObscuraLibrary.java:16-18`).

## 7. obscura-ui at build time

- VERIFIED: rustlib does not need obscura-ui for Android. The Android build ran with no `obscura-ui/build` and no `OBSCURA_GRESOURCES_DIR`.
- The only `include_bytes!` of web assets is `rustlib/src/gui/gtk_gui.rs:62-63`: `include_bytes!(concat!(env!("OBSCURA_GRESOURCES_DIR"), "/webui.gresource"))`. It compiles only with feature `gui` (`Cargo.toml:162`). The bin `obscura-gui` has `required-features = ["gui"]` (`Cargo.toml:11-14`). `rustlib/src/lib.rs` has no `mod gui`. Other `include_str!` calls are Linux only (`src/linux/autostart.rs:8`, `src/linux/tray.rs:50-57`).
- The upstream Android app uses the web UI only from the APK assets: `android/app/src/main/assets/web -> ../../../../../obscura-ui/build` (symlink) and `flake.nix:375` `ln -sfv ${web-android} app/src/main/assets/web`. Our native Compose app does not need it.
- No feature is necessary to disable it. Default features (no `gui`) already exclude it.
- Upstream puts `licenses.json` (Rust + npm) into the web bundle (`flake.nix:229-239,293`). Our app must make its own license notices (section 11).

## 8. Blockers and solutions

| Problem | Solution |
|---|---|
| No Nix | Not necessary. rustup (pin 1.89.0) + cargo-ndk + NDK from the Android SDK is enough. |
| Pinned toolchain 1.89.0 was not installed | `rustup toolchain install 1.89.0 --profile minimal --component rustfmt --target aarch64-linux-android --target x86_64-linux-android` |
| NDK r26d cannot link with `-P 35` | Use NDK 28.0.13004108 (installed) for API 35. The `.so` API level must be <= minSdk, so API 35 is correct for minSdk 35 and 36. API 36 needs NDK r29+ (not tested, not installed). |
| `cargo ndk -o` copies dependency cdylibs and does not strip | Do not use `-o`. Copy only `libobscuravpn_client.so` and run `llvm-strip`. |
| Build machine paths (user name) in the shipped `.so` | `--remap-path-prefix` through `CARGO_TARGET_<TRIPLE>_RUSTFLAGS`. Never `RUSTFLAGS`, because it removes the 16 KB flags. |
| Full crate rebuild when other files are in `rustlib/` | `build.rs` has no rerun-if. Keep `CARGO_TARGET_DIR` and other output outside `rustlib/`. |
| cmake / bindgen / nasm / aws-lc cross-compile | No problem on Android: aws-lc-sys uses its cc builder and pre-generated bindings. Host tests use Homebrew cmake 4.4.4 + Xcode libclang (both present). |
| `rustup toolchain install` always asks the network | The script only calls it when a target is missing. |

There was no code problem. I changed no source file.

## 9. Build script draft (VERIFIED: G, H, I ran with it)

File: `/tmp/obscura-research/build-test/scripts/build-rustlib.zsh`. Proposed path in our project: `scripts/build-rustlib.zsh`, with upstream rustlib at `third_party/obscuravpn-client/rustlib` (git submodule pinned to a commit). To test it here I set `RUSTLIB_DIR`, `JNILIBS_DIR`, `SYMBOLS_DIR` and `CARGO_TARGET_DIR` to paths under `build-test`.

```zsh
#!/usr/bin/env zsh
# Build the upstream Obscura Rust core (rustlib) as libobscuravpn_client.so for Android.
# No Nix. Needs: rustup, cargo-ndk 4.x, Android NDK (r28 recommended, r26d = upstream pin).
#
# Env overrides (all optional):
#   RUSTLIB_DIR   path to upstream rustlib            (default: <repo>/third_party/obscuravpn-client/rustlib)
#   JNILIBS_DIR   output dir, gets <abi>/lib*.so      (default: <repo>/app/src/main/jniLibs)
#   SYMBOLS_DIR   unstripped .so for symbolication    (default: <repo>/build/rust-symbols)
#   CARGO_TARGET_DIR                                   (default: <repo>/build/rust-target)
#   ABIS          space separated: arm64-v8a x86_64    (default: arm64-v8a)
#   ANDROID_API   cargo-ndk --platform                 (default: 35; NDK r26 max is 34)
#   NDK_VERSION   dir name under $ANDROID_HOME/ndk     (default: 28.0.13004108)
#   OBSCURA_VERSION  baked into the lib (debug bundle only); default derived from tag.json + git
emulate -L zsh
setopt err_exit no_unset pipe_fail

readonly SCRIPT_DIR=${0:A:h}
readonly REPO_DIR=${REPO_DIR:-${SCRIPT_DIR:h}}
readonly RUSTLIB_DIR=${${RUSTLIB_DIR:-$REPO_DIR/third_party/obscuravpn-client/rustlib}:A}
readonly JNILIBS_DIR=${JNILIBS_DIR:-$REPO_DIR/app/src/main/jniLibs}
readonly SYMBOLS_DIR=${SYMBOLS_DIR:-$REPO_DIR/build/rust-symbols}
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-$REPO_DIR/build/rust-target}
readonly -a ABIS=(${=ABIS:-arm64-v8a})
readonly ANDROID_API=${ANDROID_API:-35}
readonly NDK_VERSION=${NDK_VERSION:-28.0.13004108}
readonly SDK_DIR=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
readonly LIB=libobscuravpn_client.so

die() { print -u2 -- "error: $*"; exit 1 }

# --- NDK -------------------------------------------------------------------
export ANDROID_NDK_HOME=$SDK_DIR/ndk/$NDK_VERSION
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
[[ -d $ANDROID_NDK_HOME ]] || die "NDK $NDK_VERSION missing: sdkmanager --install 'ndk;$NDK_VERSION'"
llvm_bin=($ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin(N/))
(( $#llvm_bin == 1 )) || die "cannot find NDK llvm bin dir"
readonly LLVM_BIN=$llvm_bin[1]

# --- Rust toolchain pinned by rustlib/rust-toolchain.toml --------------------
[[ -f $RUSTLIB_DIR/Cargo.toml ]] || die "RUSTLIB_DIR=$RUSTLIB_DIR is not rustlib"
cd $RUSTLIB_DIR
readonly CHANNEL=$(sed -n 's/^channel *= *"\(.*\)"/\1/p' rust-toolchain.toml)
typeset -A TRIPLE=(arm64-v8a aarch64-linux-android x86_64 x86_64-linux-android)
triples=(); for abi in $ABIS; do triples+=(${TRIPLE[$abi]:?unsupported ABI $abi}); done
# Only touch the network when the pinned toolchain or a target is missing.
installed=(${(f)"$(rustup +$CHANNEL target list --installed 2>/dev/null || true)"})
missing=(${triples:|installed})
if (( $#missing )); then
  rustup toolchain install $CHANNEL --profile minimal --no-self-update \
    --component rustfmt --target ${(j:,:)triples}
fi
command -v cargo-ndk >/dev/null || die "cargo-ndk missing: cargo install cargo-ndk --locked"

# --- Version string (same format as upstream flake.nix:45, non-clean form) ---
if [[ -z ${OBSCURA_VERSION:-} ]]; then
  tag_json=${RUSTLIB_DIR:h}/tag.json
  tag=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' $tag_json 2>/dev/null || true)
  rev=$(git -C $RUSTLIB_DIR rev-parse --short=7 HEAD 2>/dev/null || print dev)
  export OBSCURA_VERSION="v${tag:-0.0}.1-$rev"
fi

# --- Privacy/reproducibility: strip local paths (home dir, user name) from panic strings.
# Env target rustflags are JOINED with rustlib/.cargo/config.toml cfg(android) rustflags,
# so the 16 KB page-size link args stay in effect. Do NOT set RUSTFLAGS: it replaces them.
readonly CARGO_HOME_DIR=${CARGO_HOME:-$HOME/.cargo}
# rustc gives the LAST matching --remap-path-prefix priority: generic first, specific last.
remap="--remap-path-prefix=$HOME=/home"
remap+=" --remap-path-prefix=$CARGO_HOME_DIR/registry/src=/cargo/registry"
remap+=" --remap-path-prefix=$CARGO_HOME_DIR/git/checkouts=/cargo/git"
remap+=" --remap-path-prefix=${RUSTLIB_DIR:A}=/obscura/rustlib"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS

# --- Build ---------------------------------------------------------------------
cargo +$CHANNEL fetch --locked
for abi in $ABIS; do
  triple=${TRIPLE[$abi]}
  export "CARGO_TARGET_${${triple//-/_}:u}_RUSTFLAGS=$remap"
  print -- "==> $abi ($triple) API $ANDROID_API, NDK $NDK_VERSION, $OBSCURA_VERSION"
  SECONDS=0
  # Same as upstream flake.nix:120 ("cargo ndk -t arm64-v8a build --release --lib") + --frozen.
  # Do NOT use cargo-ndk -o: it also copies dependency cdylibs (libneptun-*.so, libtun_rs-*.so).
  cargo +$CHANNEL ndk -t $abi -P $ANDROID_API build --release --lib --frozen
  print -- "    built in ${SECONDS}s"

  src=$CARGO_TARGET_DIR/$triple/release/$LIB
  mkdir -p $SYMBOLS_DIR/$abi $JNILIBS_DIR/$abi
  cp -f $src $SYMBOLS_DIR/$abi/$LIB
  $LLVM_BIN/llvm-strip --strip-all -o $JNILIBS_DIR/$abi/$LIB $src   # upstream: flake.nix:132

  # --- Checks ------------------------------------------------------------------
  out=$JNILIBS_DIR/$abi/$LIB
  bad=$($LLVM_BIN/llvm-readelf -lW $out | awk '$1=="LOAD" && $NF!="0x4000"')
  [[ -z $bad ]] || die "$abi: LOAD segment not 16 KB aligned:\n$bad"
  syms=$($LLVM_BIN/llvm-nm -D --defined-only $out)
  [[ $syms == *' T JNI_OnLoad'* ]] || die "$abi: JNI_OnLoad missing"
  if LC_ALL=C grep -a -q -F -- "$HOME" $out; then die "$abi: build path leaked into $out"; fi
  print -- "    $out  $(stat -f %z $out) bytes, 16 KB aligned, sha256 $(shasum -a 256 $out | cut -c1-16)"
done
```
VERIFIED: the alignment check finds 4 KB libraries (I tested it on NDK r26 `libc++_shared.so`, `Align 0x1000`).

`.gitignore` additions: `/app/src/main/jniLibs/`, `/build/`.

## 10. Gradle integration draft (NOT TESTED)

Option 1 (simple): run `scripts/build-rustlib.zsh` before `./gradlew assemble...`. AGP takes `app/src/main/jniLibs/<abi>/` automatically.

Option 2: a Gradle task that writes to a generated jniLibs dir, connected with the AGP Variant API. Put it in `buildSrc`/`build-logic` for configuration-cache use.
```kotlin
// app/build.gradle.kts (excerpt). DRAFT, not executed.
import javax.inject.Inject
import org.gradle.process.ExecOperations

abstract class CargoNdkTask : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val rustSources: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val script: RegularFileProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Input abstract val ndkVersion: Property<String>
    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty
    @get:OutputDirectory abstract val symbolsDir: DirectoryProperty

    @TaskAction
    fun build() {
        execOps.exec {
            commandLine("zsh", script.get().asFile.absolutePath)
            environment("JNILIBS_DIR", jniLibsDir.get().asFile.absolutePath)
            environment("SYMBOLS_DIR", symbolsDir.get().asFile.absolutePath)
            environment("ABIS", abis.get().joinToString(" "))
            environment("ANDROID_API", apiLevel.get().toString())
            environment("NDK_VERSION", ndkVersion.get())
        }
    }
}

val rustlibDir = rootProject.layout.projectDirectory.dir("third_party/obscuravpn-client/rustlib")
val cargoNdk = tasks.register<CargoNdkTask>("cargoNdkBuild") {
    script.set(rootProject.layout.projectDirectory.file("scripts/build-rustlib.zsh"))
    rustSources.from(rustlibDir.asFileTree.matching {
        include("src/**", "build.rs", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/config.toml")
    })
    abis.set(listOf("arm64-v8a"))
    apiLevel.set(35)
    ndkVersion.set("28.0.13004108")
    jniLibsDir.set(layout.buildDirectory.dir("generated/rustJniLibs"))
    symbolsDir.set(layout.buildDirectory.dir("outputs/rust-symbols"))
}

android {
    ndkVersion = "28.0.13004108"   // so that AGP strip/symbol tasks find an installed NDK
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::jniLibsDir)
    }
}
```
Notes (inference, not tested): the script already strips, so the AGP strip step does nothing. With minSdk >= 23 and AGP >= 8.5.1, AGP stores `.so` files uncompressed and 16 KB zip-aligned by default (`useLegacyPackaging` false). Leave that default as it is. Debug and release variants both use the release (LTO) Rust build, like upstream ("the Rust core and UI are still release builds", `README.md:116`).

## 11. Recommendations for our project

1. Use Rust 1.89.0 from `rust-toolchain.toml` (VERIFIED). Newer stable (1.98.1 here) is not tested. It has no Android targets installed.
2. Use NDK 28.0.13004108 with `-P 35` (VERIFIED). It gives 16 KB pages by default, and the config flags stay as a second guard. If minSdk is 36, still use API 35. Use NDK r29/r30 (available: `ndk;29.0.14206865`, `ndk;30.0.16248370`) only if we need API 36 libc symbols (not tested).
3. Build only arm64-v8a for release. All API 35+ phones are arm64. Emulators on Apple Silicon use arm64 images. x86_64 builds (VERIFIED) but adds 12.8 MB, so add it only for x86 test targets.
4. Always use `--remap-path-prefix` (VERIFIED: removes the user name; stripped output is reproducible).
5. Use `cargo fetch --locked` then `--frozen` (no network during the build). Pin the upstream rustlib by commit (submodule). Audit the 4 git deps listed in section 2.
6. Keep the unstripped `.so` (`SYMBOLS_DIR`, about 60 MB per ABI) outside the APK for `ndk-stack`/`llvm-symbolizer`. Optional (not tested): add `-C link-arg=-Wl,--build-id=sha1` to the target rustflags to get a build-id for symbol matching. The current output has no build-id.
7. Optional, faster dev builds (not tested, do not ship): `CARGO_PROFILE_RELEASE_LTO=thin CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16`. Release LTO costs about 26 s per crate change on this Mac.
8. Licenses: GPL-3.0 with linking exception for rustlib/obscuravpn-api. Make a Rust license list with `cargo-about` 0.6.4 (`justfile:82-84`, `rustlib/about.toml`; needs `LICENSE` at `rustlib/../LICENSE`). Not installed, not run.
9. Security note from the sources (not a build item): Android uses `WgKeyStore::Plaintext` (`rustlib/src/android/ffi.rs:61-62`, TODO OBS-2699). The WireGuard secret key is then stored as base64 in the config JSON in the app files dir (`rustlib/src/config/persistence.rs:439-440`, variant `Plaintext { secret_key }`). A privacy-first client must think about this (Android Keystore wrap), but it needs a code change.

## 12. Installs and disk use (complete list)

Installed:
1. Rust toolchain `1.89.0-aarch64-apple-darwin` (rustup, profile minimal + `rustfmt` + `rust-std` for `aarch64-linux-android` and `x86_64-linux-android`). Location `~/.rustup/toolchains/1.89.0-aarch64-apple-darwin`, 759 MB. rustup did not update itself (it stays at 1.29.1).

Downloaded to caches (not installs): crates and git deps for `rustlib/Cargo.lock` into `~/.cargo/registry` and `~/.cargo/git` (`cargo fetch --locked`).

Not installed: no NDK (26.3.11579264 and 28.0.13004108 were already present), no SDK packages, no cmake/nasm/Go/Homebrew packages, no cargo tools. `sdkmanager --list` ran once as a read-only query.

Disk: `/tmp/obscura-research/build-test` is 8.9 GB (`rustlib/target` 5.4 GB with host test build, `target-script` 3.3 GB). You can delete it. I removed the extra test dirs (`target-remap`, `target-repro`, the `cargo ndk -o` output and the extra `.so` copies).

## 13. Not tested

- Loading the `.so` on a device or emulator (`System.loadLibrary` + `JNI_OnLoad`) and any run-time VPN function.
- The Gradle integration (section 10), AGP with compileSdk/targetSdk 37.
- NDK r29/r30, API 36, Rust stable 1.98, thin-LTO dev profile, `--build-id`.
- `cargo-about` license generation.

## 14. Files

- Draft script: `/tmp/obscura-research/build-test/scripts/build-rustlib.zsh`
- Script outputs (NDK 28, API 35, remapped, stripped): `/tmp/obscura-research/build-test/app/src/main/jniLibs/{arm64-v8a,x86_64}/libobscuravpn_client.so`
- Unstripped symbols: `/tmp/obscura-research/build-test/app-symbols/{arm64-v8a,x86_64}/libobscuravpn_client.so`
- Upstream-exact outputs (NDK 26.3, API 21): `/tmp/obscura-research/build-test/rustlib/target/{aarch64,x86_64}-linux-android/release/libobscuravpn_client.so`; stripped copies in `/tmp/obscura-research/build-test/out/jniLibs/{arm64-v8a,x86_64}/`
- Logs: `/tmp/obscura-research/build-test/{build-arm64,build-x86_64,build-ndk28-p35,build-ndk26-p35,build-remap,script-run,test-host}.log`
