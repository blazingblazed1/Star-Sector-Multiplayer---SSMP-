#!/bin/sh
# build-helper.sh : build the Rust network helper (smnet) for Linux and Windows
# Needs Rust (rustup) and, for the Windows build on Linux, the mingw-w64 toolchain + the x86_64-pc-windows-gnu target.
set -e
cd "$(dirname "$0")/../rust-smnet"
cargo build --release
cargo build --release --target x86_64-pc-windows-gnu
echo "rust-smnet/target/release/smnet and rust-smnet/target/x86_64-pc-windows-gnu/release/smnet.exe"
