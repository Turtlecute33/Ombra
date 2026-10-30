#!/bin/zsh
# Builds the Obscura Rust core (rust/obscura-core) as libobscuravpn_client.so for Android, strips it,
# and puts it into app/src/main/jniLibs/<abi>/. No Nix. Needs rustup, cargo-ndk 4.x and the Android NDK.
# Never downloads: the toolchain, its targets and the crates (cargo fetch --locked) must be there already.
#
# Env overrides (all optional):
#   RUSTLIB_DIR         Rust crate                          (default: <repo>/rust/obscura-core)
#   JNILIBS_DIR         output, gets <abi>/lib*.so           (default: <repo>/app/src/main/jniLibs)
#   CARGO_TARGET_DIR    cargo build dir                      (default: <repo>/build/rust-target)
#   SYMBOLS_DIR         unstripped .so for ndk-stack         (default: <repo>/build/rust-symbols)
#   ABIS                space separated: arm64-v8a x86_64     (default: arm64-v8a)
#   ANDROID_API         cargo-ndk --platform                  (default: 35, the highest API in NDK r28)
#   NDK_VERSION         dir name under $ANDROID_HOME/ndk      (default: 28.0.13004108)
#   OMBRA_RUST_PROFILE  release, or dev-fast = thin LTO + 16 codegen units for local work only
#                                                             (default: release)
#   OBSCURA_VERSION     version in debug bundles              (default: ombra-core-<git rev of RUSTLIB_DIR>)
set -euo pipefail

readonly REPO_DIR=${0:A:h:h}
readonly RUSTLIB_DIR=${${RUSTLIB_DIR:-"$REPO_DIR/rust/obscura-core"}:A}
readonly JNILIBS_DIR=${${JNILIBS_DIR:-"$REPO_DIR/app/src/main/jniLibs"}:A}
readonly SYMBOLS_DIR=${${SYMBOLS_DIR:-"$REPO_DIR/build/rust-symbols"}:A}
# Outside RUSTLIB_DIR: build.rs has no rerun-if-changed, so any new file in the crate dir rebuilds it.
export CARGO_TARGET_DIR=${${CARGO_TARGET_DIR:-"$REPO_DIR/build/rust-target"}:A}
readonly -a ABIS=(${=ABIS:-arm64-v8a})
readonly ANDROID_API=${ANDROID_API:-35}
readonly NDK_VERSION=${NDK_VERSION:-28.0.13004108}
readonly PROFILE=${OMBRA_RUST_PROFILE:-release}
readonly SDK_DIR=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
readonly LIB=libobscuravpn_client.so
# The JNI contract (docs/DESIGN.md section 4). The .so must export exactly these.
readonly -a EXPECTED_EXPORTS=(
  JNI_OnLoad
  Java_app_ombra_core_ffi_Native_{flushAndStopPersistedLog,forwardLog,initialize,jsonFfi,setNetworkInterface,unsetNetworkInterface,wake}
)
typeset -rA TRIPLE=(arm64-v8a aarch64-linux-android x86_64 x86_64-linux-android)

die() { print -u2 -- "error: $*"; exit 1 }

# Replaces dst only when the content changed, so its mtime stays stable for Gradle up-to-date checks.
install_if_changed() {
  local src=$1 dst=$2
  if cmp -s $src $dst; then rm -f $src; else mv -f $src $dst; fi
}

case $PROFILE in
  release) profile_args=(--release) ;;
  dev-fast)
    profile_args=(--profile dev-fast
      --config 'profile.dev-fast.inherits="release"'
      --config 'profile.dev-fast.lto="thin"'
      --config 'profile.dev-fast.codegen-units=16') ;;
  *) die "OMBRA_RUST_PROFILE must be release or dev-fast, not '$PROFILE'" ;;
esac

# --- NDK ----------------------------------------------------------------------------------------------
export ANDROID_NDK_HOME=$SDK_DIR/ndk/$NDK_VERSION
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
[[ -d $ANDROID_NDK_HOME ]] || die "NDK $NDK_VERSION missing: sdkmanager --install 'ndk;$NDK_VERSION'"
llvm_bin=($ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin(N/))
(( $#llvm_bin == 1 )) || die "cannot find the NDK llvm bin dir"
readonly LLVM_BIN=$llvm_bin[1]

# --- Rust toolchain pinned by rust-toolchain.toml ----------------------------------------------------
# rustup must not install a missing toolchain or component by itself.
export RUSTUP_AUTO_INSTALL=0
[[ -f $RUSTLIB_DIR/Cargo.toml ]] || die "RUSTLIB_DIR=$RUSTLIB_DIR has no Cargo.toml"
# cargo reads .cargo/config.toml (16 KB page link args) and rust-toolchain.toml from the working dir.
cd $RUSTLIB_DIR
readonly CHANNEL=$(sed -n 's/^channel *= *"\(.*\)"/\1/p' rust-toolchain.toml)
[[ -n $CHANNEL ]] || die "no channel in $RUSTLIB_DIR/rust-toolchain.toml"
triples=()
for abi in $ABIS; do triples+=(${TRIPLE[$abi]:?unsupported ABI $abi}); done
installed=(${(f)"$(rustup +$CHANNEL target list --installed 2>/dev/null || true)"})
missing=(${triples:|installed})
(( $#missing == 0 )) || die "Rust $CHANNEL or its targets ${missing} missing:" \
  "rustup toolchain install $CHANNEL --profile minimal --component rustfmt --target ${(j:,:)triples}"
command -v cargo-ndk >/dev/null || die "cargo-ndk missing: cargo install cargo-ndk --locked"

# --- Version string (debug bundles only) -------------------------------------------------------------
if [[ -z ${OBSCURA_VERSION:-} ]]; then
  rev=$(git -C $RUSTLIB_DIR log -1 --format=%h -- . 2>/dev/null || true)
  if [[ -n $rev && -n $(git -C $RUSTLIB_DIR --no-optional-locks status --porcelain -- . 2>/dev/null) ]]; then
    rev+=-dirty
  fi
  export OBSCURA_VERSION=ombra-core-${rev:-unknown}
fi

# --- Privacy and reproducibility: no local paths (home dir, user name) in panic strings ----------------
# Target rustflags from the env are JOINED with the cfg(android) rustflags of .cargo/config.toml, so the
# 16 KB page-size link args stay. RUSTFLAGS or CARGO_ENCODED_RUSTFLAGS would REPLACE them.
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS
readonly CARGO_HOME_DIR=${CARGO_HOME:-$HOME/.cargo}
# rustc gives the LAST matching --remap-path-prefix priority: generic first, specific last.
remap=(
  --remap-path-prefix=$HOME=/home
  --remap-path-prefix=$CARGO_HOME_DIR/registry/src=/cargo/registry
  --remap-path-prefix=$CARGO_HOME_DIR/git/checkouts=/cargo/git
  --remap-path-prefix=$RUSTLIB_DIR=/ombra/obscura-core
)

# --- Build -------------------------------------------------------------------------------------------
typeset -F 1 SECONDS
for abi in $ABIS; do
  triple=${TRIPLE[$abi]}
  # RELR packs the relative relocations (OMBRA-OPT-01). Bionic reads DT_RELR from API 30.
  export "CARGO_TARGET_${${triple//-/_}:u}_RUSTFLAGS=${(j: :)remap} -C link-arg=-Wl,--pack-dyn-relocs=relr"
  print -- "==> $abi ($triple), API $ANDROID_API, NDK $NDK_VERSION, profile $PROFILE, $OBSCURA_VERSION"
  SECONDS=0
  # Not cargo-ndk -o: it also copies dependency cdylibs (libneptun-*.so, libtun_rs-*.so) and does not strip.
  # --frozen: Cargo.lock as it is, and no network. A missing crate fails here instead of a download.
  cargo +$CHANNEL ndk -t $abi -P $ANDROID_API build --lib --frozen $profile_args
  build_time=$SECONDS

  src=$CARGO_TARGET_DIR/$triple/$PROFILE/$LIB
  out=$JNILIBS_DIR/$abi/$LIB
  mkdir -p $SYMBOLS_DIR/$abi $JNILIBS_DIR/$abi
  cp -f $src $SYMBOLS_DIR/$abi/$LIB.new
  install_if_changed $SYMBOLS_DIR/$abi/$LIB.new $SYMBOLS_DIR/$abi/$LIB
  $LLVM_BIN/llvm-strip --strip-all -o $SYMBOLS_DIR/$abi/$LIB.stripped $src

  # --- Checks on the stripped library, before it replaces the one in jniLibs ---
  stripped=$SYMBOLS_DIR/$abi/$LIB.stripped
  aligns=(${(f)"$($LLVM_BIN/llvm-readelf -lW $stripped | awk '$1 == "LOAD" { print $NF }')"})
  (( $#aligns )) || die "$abi: no LOAD segment in $stripped"
  for align in $aligns; do
    (( align >= 0x4000 )) || die "$abi: LOAD segment Align $align < 0x4000: the library does not load on 16 KB page devices"
  done
  [[ $($LLVM_BIN/llvm-readelf -d $stripped) == *'(RELR)'* ]] || die "$abi: no RELR tag in $stripped"
  exports=(${(f)"$($LLVM_BIN/llvm-nm -D --defined-only -j $stripped)"})
  if [[ ${(j: :)${(@o)exports}} != ${(j: :)${(@o)EXPECTED_EXPORTS}} ]]; then
    die "$abi: exported symbols differ from the JNI contract.\n  expected: ${(@o)EXPECTED_EXPORTS}\n  found:    ${(@o)exports}"
  fi
  if LC_ALL=C grep -a -q -F -- $HOME $stripped; then die "$abi: a build path ($HOME) leaked into $stripped"; fi

  install_if_changed $stripped $out
  size=$(stat -f %z $out)
  print -- "    built in ${build_time}s"
  printf '    %s: %d bytes (%.1f MiB), sha256 %s\n' ${out#$REPO_DIR/} $size $(( size / 1048576.0 )) \
    "$(shasum -a 256 $out | cut -c1-16)"
  print -- "    LOAD Align: ${(j: :)aligns} (16 KB ok)"
  print -- "    exports: ${(@o)exports}"
done
