#!/usr/bin/env bash
# Install the wrapper and stage the archive on a connected device.
#
# This is the whole on-device setup: build, install, push the arm64 split and
# the base archive, and print the sha256 of both so the wrapper's refusal (or
# acceptance) can be checked against what was actually pushed.
#
# Usage: scripts/device-setup.sh [--rebuild]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || adb="$(command -v adb || true)"
[ -n "$adb" ] || { echo "adb not found; set ANDROID_HOME" >&2; exit 1; }

apk="$here/wrapper/app/build/outputs/apk/debug/app-debug.apk"
xapk="$here/apk/roblox.xapk"
split="$here/apk/x/config.arm64_v8a.apk"
base="$here/apk/roblox.apk"

if [ "${1:-}" = "--rebuild" ] || [ ! -f "$apk" ]; then
    ( cd "$here/wrapper" && ANDROID_HOME="$sdk" ./gradlew :app:assembleDebug --console=plain )
fi
[ -f "$apk" ] || { echo "no APK at $apk" >&2; exit 1; }
[ -f "$xapk" ] || { echo "no archive at $xapk; download it first (see README)" >&2; exit 1; }
[ -f "$base" ] || { echo "no base APK at $base" >&2; exit 1; }

if [ ! -f "$split" ]; then
    echo "extracting the arm64 split from the XAPK…"
    mkdir -p "$(dirname "$split")"
    ( cd "$(dirname "$split")" && unzip -o -q "$xapk" config.arm64_v8a.apk )
fi

echo "== device =="
"$adb" devices -l
"$adb" shell getprop ro.product.cpu.abi
"$adb" shell getprop ro.build.version.sdk

echo "== install wrapper =="
"$adb" install -r "$apk"

echo "== stage the archive =="
# The wrapper unpacks the split's natives itself, but it needs the split's
# lib/arm64-v8a payload. Both files go where RobloxStore looks for them.
# The wrapper needs BOTH halves: the base archive carries the dex and the
# engine's content, the split carries libroblox.so. RobloxStore verifies and
# unpacks whichever half each file carries.
stage=/sdcard/Android/data/app.boxblox/files
"$adb" shell mkdir -p "$stage"
"$adb" push "$base"   "$stage/roblox.apk"
"$adb" push "$split"  "$stage/config.arm64_v8a.apk"
echo "pushed base  ($(stat -c%s "$base") bytes)"
echo "pushed split ($(stat -c%s "$split") bytes)"
echo "sha256 (base):  $(sha256sum "$base" | cut -d' ' -f1)"
echo "sha256 (split): $(sha256sum "$split" | cut -d' ' -f1)"
echo
echo "Launch 'Roblox wrapper' on the device, then:"
echo "  adb logcat -s RobloxWrapper:V | grep -E 'build|guest|pointer capture|engine hooks'"
