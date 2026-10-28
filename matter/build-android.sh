#!/bin/sh
# Builds busy-matter for the phone (arm64) and puts it where the APK picks it up:
# app/src/main/jniLibs/arm64-v8a/libbusymatter.so. Needs Rust with the aarch64-linux-android
# target and the Android NDK (ANDROID_HOME or ~/Library/Android/sdk).
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_BIN="$(ls -d "$SDK"/ndk/* | sort | tail -1)/toolchains/llvm/prebuilt/$(uname -s | tr A-Z a-z)-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android28-clang"
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android28-clang"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
cargo build --release --locked --target aarch64-linux-android
TARGET_DIR="${CARGO_TARGET_DIR:-target}"
mkdir -p ../app/src/main/jniLibs/arm64-v8a
cp "$TARGET_DIR/aarch64-linux-android/release/busy-matter" ../app/src/main/jniLibs/arm64-v8a/libbusymatter.so
echo "-> app/src/main/jniLibs/arm64-v8a/libbusymatter.so"
