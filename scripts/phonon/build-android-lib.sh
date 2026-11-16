#!/usr/bin/env bash
# Rebuilds Phonon's Android runtime, libptts_ffi.so, from Gradium's xn-ptts at the pinned release and
# copies it into the app (android/app/src/main/jniLibs/{arm64-v8a,x86_64}). The app's Kotlin wrapper
# (tts/phonon/PhononTTS.kt) and the narration server (scripts/narration-server, crate ptts) use the
# same release, so a phone and a computer speak with the same library.
#
# Needs Rust (rustup), cargo-ndk (`cargo install cargo-ndk`) and the Android NDK r28 or newer
# (`sdkmanager "ndk;28.2.13676358"`), found through ANDROID_NDK_HOME or the SDK's ndk folder.
# Only 64-bit libraries are built (Phonon has no 32-bit build); the app hides Phonon elsewhere.
set -euo pipefail

VERSION=v0.7.1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_LIBS="$HERE/../../android/app/src/main/jniLibs"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --quiet --depth 1 --branch "$VERSION" https://github.com/gradium-ai/xn-ptts.git "$WORK/xn-ptts"
# Gradium's own script: dotprod + fp16 kernels for arm64 (every phone since about 2019), 16 KB pages.
"$WORK/xn-ptts/android/build.sh"
for abi in arm64-v8a x86_64; do
  mkdir -p "$APP_LIBS/$abi"
  cp "$WORK/xn-ptts/android/jniLibs/$abi/libptts_ffi.so" "$APP_LIBS/$abi/"
done
sha256sum "$APP_LIBS"/*/libptts_ffi.so
