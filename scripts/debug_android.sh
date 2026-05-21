#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

PUSH_TO_SDCARD=false
ENABLE_LOGCAT=false
LOGCAT_FILTER="com.cocos.gamestudio"
SERIAL=""

DEFAULT_JDK_DIR="${HOME}/codespace/jdk/jdk-26.0.1+8"
ANDROID_SDK_DIR="${HOME}/codespace/android-sdk"
JAVA_BIN=""
ADB_BIN="${HOME}/codespace/android-sdk/platform-tools/adb"

export JAVA_HOME="${JAVA_HOME:-$DEFAULT_JDK_DIR}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_DIR}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ -x "${DEFAULT_JDK_DIR}/bin/java" ]]; then
  JAVA_BIN="${DEFAULT_JDK_DIR}/bin/java"
fi
if [[ -z "$JAVA_BIN" ]] && [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi
if [[ -z "$JAVA_BIN" ]] && command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi

if [[ -z "$JAVA_BIN" ]]; then
  echo "未找到 java，请先安装 JDK 26+"
  exit 1
fi

JAVA_BIN_DIR="$(cd "$(dirname "$JAVA_BIN")" && pwd)"
export JAVA_HOME="$(cd "${JAVA_BIN_DIR}/.." && pwd)"
if [[ ":$PATH:" != *":$JAVA_BIN_DIR:"* ]]; then
  export PATH="$JAVA_BIN_DIR:$PATH"
fi

resolve_adb() {
  local candidates=(
    "$HOME/codespace/android-sdk/platform-tools/adb"
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

resolve_aapt2() {
  local candidates=(
    "$HOME/.cache/android-arm-tools/aapt2"
    "$HOME/codespace/android-sdk/build-tools/36.0.0/aapt2"
    "$HOME/codespace/android-sdk/build-tools/35.0.2/aapt2"
    "$HOME/codespace/android-sdk/build-tools/37.0.0/aapt2"
    "$(command -v aapt2 || true)"
  )

  AAPT2_BIN=""
  for candidate in "${candidates[@]}"; do
    [[ -z "$candidate" ]] && continue
    if [[ -x "$candidate" ]]; then
      AAPT2_BIN="$candidate"
      break
    fi
  done
}

resolve_aapt2
GRADLE_AAPT2_ARG=()
if [[ -n "${AAPT2_BIN:-}" && -x "$AAPT2_BIN" ]]; then
  GRADLE_AAPT2_ARG=("-P" "android.aapt2FromMavenOverride=$AAPT2_BIN")
fi

if [[ ! -x "$ADB_BIN" ]]; then
  echo "未找到 adb，请先安装 Android SDK Platform-Tools（>= 37.0.0）"
  exit 1
fi

for arg in "$@"; do
  case "$arg" in
    --sdcard)
      PUSH_TO_SDCARD=true
      ;;
    --logcat)
      ENABLE_LOGCAT=true
      ;;
    --logcat=*)
      ENABLE_LOGCAT=true
      LOGCAT_FILTER="${arg#--logcat=}"
      ;;
    --serial=*)
      SERIAL="${arg#--serial=}"
      ;;
    *)
      if [[ -z "$SERIAL" ]]; then
        SERIAL="$arg"
      else
        echo "未知参数: $arg"
        echo "用法: ./scripts/debug_android.sh [--serial=<序列号>] [--sdcard] [--logcat|--logcat=<过滤关键字>]"
        exit 1
      fi
      ;;
  esac
done

java_version_line="$("$JAVA_BIN" -version 2>&1 | awk -F '\"' '/version/ {print $2; exit}')"
java_major="$(echo "$java_version_line" | awk -F. '{print $1}')"
if [[ "$java_major" == "1" ]]; then
  java_major="$(echo "$java_version_line" | awk -F. '{print $2}')"
fi
if [[ -z "$java_major" ]] || ! [[ "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 26 )); then
  echo "检测到 JDK 版本 ${java_version_line}，请安装 JDK 26+ 后重试"
  exit 1
fi

if [[ ! -x ./gradlew ]]; then
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
  else
    echo "未找到 gradlew，自动启动 Gradle Wrapper 自举..."
    ./scripts/bootstrap_gradle.sh
    if [[ -x ./gradlew ]]; then
      GRADLE_CMD="./gradlew"
    else
      echo "自举失败：未生成可执行 ./gradlew，请安装 Gradle 9.4.1 后重试"
      exit 1
    fi
  fi
else
  GRADLE_CMD="./gradlew"
fi

echo "开始构建 Debug APK..."
"$GRADLE_CMD" app:assembleDebug -x lint "${GRADLE_AAPT2_ARG[@]}"

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "未生成 APK：$APK_PATH"
  exit 1
fi

echo "启动 adb 并检测设备..."
"$ADB_BIN" start-server >/dev/null
if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "未检测到已授权设备，请确认 USB 调试已开启且已授信"
  "$ADB_BIN" devices -l
  exit 1
fi

if [[ "$PUSH_TO_SDCARD" == true ]]; then
  ./scripts/setup_demo.sh --sdcard
fi

echo "安装并启动应用（设备: $SERIAL）..."
  "$ADB_BIN" -s "$SERIAL" install -r "$APK_PATH"
  "$ADB_BIN" -s "$SERIAL" shell am start -n com.cocos.gamestudio/.GameListActivity

echo "完成：${APK_PATH} 已安装并启动"

if [[ "$ENABLE_LOGCAT" == true ]]; then
  echo "开始监控日志：${LOGCAT_FILTER}"
  "$ADB_BIN" -s "$SERIAL" logcat | grep --line-buffered -E "$LOGCAT_FILTER|AndroidRuntime|GameStudio|cocos"
fi
