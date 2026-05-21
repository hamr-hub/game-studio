#!/usr/bin/env bash

set -euo pipefail

WORKFLOW_FILE_DEBUG="android-debug.yml"
WORKFLOW_FILE_RELEASE="android-release.yml"
WORKFLOW_FILE="${WORKFLOW_FILE_DEBUG}"
ARTIFACT_NAME=""
OUT_DIR="${OUT_DIR:-/tmp/game-studio-ci-apk}"
RUN_ID=""
SERIAL=""
REPO="${REPO:-}"
BUILD_TYPE="debug"
TARGET_ABI="arm64-v8a"

usage() {
  cat <<'USAGE'
使用方式:
  ./scripts/fetch_ci_debug_apk.sh [--repo=<owner/repo>] [--run-id=<run-id>] [--type=<debug|release>] [--abi=<arm64-v8a|x86_64>] [--serial=<device_serial>]

说明:
  - 默认自动读取当前 git 仓库 origin 远端（如 github 上的 owner/repo）
  - 默认下载最近一次成功的 workflow 产物
  - 默认下载 debug + arm64-v8a
  - 下载后自动安装到 adb 授权设备，并启动 com.cocos.gamestudio/.GameListActivity
USAGE
  exit 1
}

validate_type() {
  case "$1" in
    debug|release)
      return 0 ;;
    *)
      echo "不支持的构建类型: ${1}，仅支持 debug|release"
      return 1 ;;
  esac
}

validate_abi() {
  case "$1" in
    arm64-v8a|x86_64)
      return 0 ;;
    *)
      echo "不支持的 ABI: ${1}，支持: arm64-v8a|x86_64"
      return 1 ;;
  esac
}

detect_repo() {
  if [[ -n "${REPO}" ]]; then
    return
  fi
  if ! command -v git >/dev/null 2>&1; then
    return
  fi
  local remote
  remote="$(git remote get-url origin 2>/dev/null || true)"
  remote="${remote#git@github.com:}"
  remote="${remote#https://github.com/}"
  remote="${remote%.git}"
  REPO="$remote"
}

for arg in "$@"; do
  case "$arg" in
    --repo=*)
      REPO="${arg#*=}"
      ;;
    --run-id=*)
      RUN_ID="${arg#*=}"
      ;;
    --type=*)
      BUILD_TYPE="${arg#*=}"
      ;;
    --abi=*)
      TARGET_ABI="${arg#*=}"
      ;;
    --serial=*)
      SERIAL="${arg#*=}"
      ;;
    --help|-h)
      usage
      ;;
    *)
      echo "未知参数: ${arg}"
      usage
      ;;
  esac
done

validate_type "${BUILD_TYPE}"
validate_abi "${TARGET_ABI}"

if [[ "${BUILD_TYPE}" == "release" ]]; then
  WORKFLOW_FILE="${WORKFLOW_FILE_RELEASE}"
else
  WORKFLOW_FILE="${WORKFLOW_FILE_DEBUG}"
fi

ARTIFACT_NAME="game-studio-${BUILD_TYPE}-${TARGET_ABI}-apk"

if ! command -v gh >/dev/null 2>&1; then
  echo "未检测到 GitHub CLI (gh)，请先安装并登录 gh"
  echo "https://cli.github.com/"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "GitHub CLI 未登录，请先执行: gh auth login"
  exit 1
fi

detect_repo
if [[ -z "${REPO}" ]]; then
  echo "无法解析仓库信息，请使用 --repo=<owner/repo> 手动指定"
  exit 1
fi

if [[ -z "${RUN_ID}" ]]; then
  RUN_ID="$(gh run list --repo "${REPO}" --workflow "${WORKFLOW_FILE}" --limit 20 --json databaseId,conclusion --jq 'map(select(.conclusion=="success"))[0].databaseId')"
fi

if [[ -z "${RUN_ID}" || "${RUN_ID}" == "null" ]]; then
  echo "未找到成功的 workflow 执行记录，请先在 GitHub Actions 成功打包一次"
  exit 1
fi

mkdir -p "${OUT_DIR}"
rm -rf "${OUT_DIR:?}/"*
if ! gh run download "${RUN_ID}" --repo "${REPO}" --name "${ARTIFACT_NAME}" --dir "${OUT_DIR}"; then
  echo "未找到目标产物: ${ARTIFACT_NAME}"
  echo "该 run 可用产物:"
  gh api "/repos/${REPO}/actions/runs/${RUN_ID}/artifacts" --jq '.artifacts[].name'
  exit 1
fi

APK_PATH="$(find "${OUT_DIR}" -type f -name "*.apk" | head -n 1 || true)"
if [[ -z "${APK_PATH}" ]]; then
  echo "下载完成，但未找到 APK 文件"
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "未检测到 adb，请先安装 Platform Tools"
  exit 1
fi

if [[ -z "${SERIAL}" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "${SERIAL}" ]]; then
  echo "未检测到已连接并授权的设备"
  adb devices -l
  exit 1
fi

echo "下载的 APK: ${APK_PATH}"
adb -s "${SERIAL}" install -r "${APK_PATH}"
adb -s "${SERIAL}" shell am start -n com.cocos.gamestudio/.GameListActivity
echo "已完成安装并启动到设备: ${SERIAL}"
