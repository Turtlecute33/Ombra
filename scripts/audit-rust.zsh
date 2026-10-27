#!/bin/zsh
# Checks the crates of the Rust core (rust/obscura-core) against the RustSec advisory database with
# cargo-deny, only for the Android target (OMBRA-SEC-005). Run it before each release tag and after each
# change of Cargo.lock. It downloads the advisory database and reads the crates.io index (yanked
# crates). It does not change Cargo.lock. Exit status 0: no advisory applies.
# For each advisory that applies: update the crate (rust/UPSTREAM.md, "Crate updates in Cargo.lock"),
# or put its ID with a reason in [advisories] ignore of rust/obscura-core/deny.toml.
set -euo pipefail

readonly REPO_DIR=${0:A:h:h}
readonly RUSTLIB_DIR=$REPO_DIR/rust/obscura-core
readonly TARGET=aarch64-linux-android

die() { print -u2 -- "error: $*"; exit 1 }

command -v cargo-deny >/dev/null || die "cargo-deny missing. Install it once: cargo +stable install cargo-deny --locked"
# rustup must not install a missing toolchain by itself.
export RUSTUP_AUTO_INSTALL=0
# cargo reads rust-toolchain.toml from the working dir.
cd $RUSTLIB_DIR
# --target: only the crates of the Android build. --locked: Cargo.lock as it is.
cargo deny --locked --target $TARGET check advisories
