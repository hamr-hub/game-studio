#!/usr/bin/env bash

set -euo pipefail

ADB_BIN="${HOME}/codespace/android-sdk/platform-tools/adb"
ANDROID_SDK_DIR="${HOME}/codespace/android-sdk"

resolve_adb() {
  local candidates=(
    "$ANDROID_SDK_DIR/platform-tools/adb"
    "/usr/bin/adb"
    "/usr/local/bin/adb"
    "$(command -v adb || true)"
  )

  for candidate in "${candidates[@]}"; do
    [[ -z "$candidate" ]] && continue
    if [[ -x "$candidate" ]] && "$candidate" version >/dev/null 2>&1; then
      ADB_BIN="$candidate"
      break
    fi
  done
}
resolve_adb

export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_DIR}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "未找到 adb，请先安装 Android SDK Platform-Tools（>= 37.0.0）"
  exit 1
fi

# This script copies the game demos to the Android assets folder or pushes to SD card
# Run this from the root of game-studio

DEST_ASSETS="app/src/main/assets/games"
DEST_SDCARD="/sdcard/game-demo"

mkdir -p "$DEST_ASSETS"

if [[ "${1:-}" == "--sdcard" ]]; then
    echo "Pushing demos to SD card via ADB..."
    "$ADB_BIN" shell mkdir -p "$DEST_SDCARD"
    "$ADB_BIN" push ../game-demo/*.zip "$DEST_SDCARD/"
    echo "Demos pushed to $DEST_SDCARD"
else
    echo "Copying demos to assets..."
    cp ../game-demo/*.zip "$DEST_ASSETS/"
    echo "Demos copied to $DEST_ASSETS"
fi

echo "Done."
