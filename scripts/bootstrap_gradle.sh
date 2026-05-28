#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

GRADLE_VERSION="9.4.1"
WRAPPER_DIR="gradle/wrapper"
WRAPPER_PROP_FILE="$WRAPPER_DIR/gradle-wrapper.properties"
WRAPPER_MAIN_JAR_FILE="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_SHARED_JAR_FILE="$WRAPPER_DIR/gradle-wrapper-shared.jar"
WRAPPER_CLI_JAR_FILE="$WRAPPER_DIR/gradle-cli.jar"
GRADLE_DIST="gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_ZIP="/tmp/${GRADLE_DIST}"
GRADLE_SHA_FILE="/tmp/${GRADLE_DIST}.sha256"
GRADLE_DIST_URLS=(
  "https://services.gradle.org/distributions/${GRADLE_DIST}"
  "https://downloads.gradle.org/distributions/${GRADLE_DIST}"
)
MAX_ATTEMPTS=3

mkdir -p "$WRAPPER_DIR"

log() {
  echo "[bootstrap] $*"
}

download_with_retry() {
  local url="$1"
  local out_file="$2"
  local attempt=1

  while true; do
    if command -v curl >/dev/null 2>&1; then
      log "下载: ${url} (第 ${attempt}/${MAX_ATTEMPTS} 次)"
      curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 120 -o "$out_file" "$url" && return 0
    elif command -v wget >/dev/null 2>&1; then
      log "下载: ${url} (第 ${attempt}/${MAX_ATTEMPTS} 次)"
      wget --tries=3 --timeout=20 --waitretry=2 -O "$out_file" "$url" && return 0
    else
      log "需要 curl 或 wget 才能下载 gradle wrapper"
      return 1
    fi

    if (( attempt >= MAX_ATTEMPTS )); then
      return 1
    fi

    attempt=$((attempt + 1))
    rm -f "$out_file"
    sleep 2
  done
}

download_dist_with_fallback() {
  local out_file="$1"
  local -a url_list=("${GRADLE_DIST_URLS[@]}")

  for url in "${url_list[@]}"; do
    if download_with_retry "$url" "$out_file"; then
      return 0
    fi
  done

  return 1
}

download_sha_with_fallback() {
  local out_file="$1"
  local sha_failed=0

  for url in "${GRADLE_DIST_URLS[@]}"; do
    local sha_url="${url}.sha256"
    if download_with_retry "$sha_url" "$out_file"; then
      return 0
    fi
    sha_failed=1
  done

  if [[ "$sha_failed" -eq 1 ]]; then
    rm -f "$out_file"
    return 1
  fi
}

sha256_verify() {
  local file="$1"
  local sum_file="$2"

  if ! command -v sha256sum >/dev/null 2>&1; then
    log "未检测到 sha256sum，跳过 hash 校验"
    return 0
  fi

  if [[ ! -s "$sum_file" ]]; then
    log "未获取到 hash 文件，跳过 hash 校验"
    return 0
  fi

  local expected
  expected="$(awk '{print $1}' "$sum_file" | head -n 1)"
  if [[ -z "$expected" ]]; then
    log "hash 文件为空，跳过 hash 校验"
    return 0
  fi

  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  if [[ "$actual" == "$expected" ]]; then
    return 0
  fi

  log "SHA256 校验失败：expected=${expected}, actual=${actual}"
  return 1
}

if [[ ! -f "$WRAPPER_PROP_FILE" ]]; then
  cat > "$WRAPPER_PROP_FILE" <<EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/${GRADLE_DIST}
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
fi

if [[ ! -f "$WRAPPER_MAIN_JAR_FILE" || ! -f "$WRAPPER_SHARED_JAR_FILE" || ! -f "$WRAPPER_CLI_JAR_FILE" ]]; then
  if ! command -v unzip >/dev/null 2>&1; then
    log "未检测到 unzip，请先安装 unzip"
    exit 1
  fi

  download_dist_with_fallback "$GRADLE_ZIP" || {
    log "Gradle 分发包下载失败（达最大重试）"
    exit 1
  }

  if ! download_sha_with_fallback "$GRADLE_SHA_FILE"; then
    log "Hash 文件下载失败，后续将尝试解压并安装（无校验）"
  fi

  if ! sha256_verify "$GRADLE_ZIP" "$GRADLE_SHA_FILE"; then
    log "下载内容校验失败，重试获取分发包..."
    rm -f "$GRADLE_ZIP" "$GRADLE_SHA_FILE"
    download_with_retry "$GRADLE_URL" "$GRADLE_ZIP" || {
      log "Gradle 分发包重试下载失败（达最大重试）"
      exit 1
    }
    if ! sha256_verify "$GRADLE_ZIP" "$GRADLE_SHA_FILE"; then
      log "校验仍失败，退出（请检查网络或手动执行 ./scripts/bootstrap_gradle.sh 后重试）"
      rm -f "$GRADLE_ZIP" "$GRADLE_SHA_FILE"
      exit 1
    fi
  fi

  TMP_DIR="$(mktemp -d)"
  if ! unzip -q "$GRADLE_ZIP" -d "$TMP_DIR"; then
    log "解压分发包失败，文件可能损坏"
    rm -rf "$TMP_DIR" "$GRADLE_ZIP" "$GRADLE_SHA_FILE"
    exit 1
  fi

  WRAPPER_MAIN_JAR_SRC="$(find "$TMP_DIR/gradle-${GRADLE_VERSION}" -path '*/lib/plugins/gradle-wrapper-main-*.jar' | head -n 1)"
  WRAPPER_SHARED_JAR_SRC="$(find "$TMP_DIR/gradle-${GRADLE_VERSION}" -path '*/lib/gradle-wrapper-shared-*.jar' | head -n 1)"
  WRAPPER_CLI_JAR_SRC="$(find "$TMP_DIR/gradle-${GRADLE_VERSION}" -path '*/lib/gradle-cli-*.jar' | head -n 1)"
  if [[ -z "$WRAPPER_MAIN_JAR_SRC" || -z "$WRAPPER_SHARED_JAR_SRC" || -z "$WRAPPER_CLI_JAR_SRC" ]]; then
    log "未找到 gradle wrapper main/shared/cli jar，请检查下载文件完整性"
    rm -rf "$TMP_DIR" "$GRADLE_ZIP" "$GRADLE_SHA_FILE"
    exit 1
  fi

  cp "$WRAPPER_MAIN_JAR_SRC" "$WRAPPER_MAIN_JAR_FILE"
  cp "$WRAPPER_SHARED_JAR_SRC" "$WRAPPER_SHARED_JAR_FILE"
  cp "$WRAPPER_CLI_JAR_SRC" "$WRAPPER_CLI_JAR_FILE"
  rm -rf "$TMP_DIR" "$GRADLE_ZIP" "$GRADLE_SHA_FILE"
fi

if [[ ! -f gradlew ]]; then
  cat > gradlew <<'EOF'
#!/usr/bin/env sh
##############################################################################
##
## Copyright by the original Gradle source (generated helper script)
##
##############################################################################

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="$(basename "$0")"
APP_BASE_NAME=${APP_NAME%.sh}

DEFAULT_JVM_OPTS=""
CLASS_PATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar:$APP_HOME/gradle/wrapper/gradle-wrapper-shared.jar:$APP_HOME/gradle/wrapper/gradle-cli.jar"
WILDFLY_JAVA_OPTS=""

while true; do
  case "$1" in
    --jvmargs)
      shift
      WILDFLY_JAVA_OPTS="$1"
      shift
      ;;
    --no-daemon)
      shift
      ;;
    *)
      break
      ;;
  esac
done

if [ -n "$WILDFLY_JAVA_OPTS" ]; then
  DEFAULT_JVM_OPTS="$DEFAULT_JVM_OPTS $WILDFLY_JAVA_OPTS"
fi

exec java $DEFAULT_JVM_OPTS -classpath "$CLASS_PATH" org.gradle.wrapper.GradleWrapperMain "$@"
EOF
  chmod +x gradlew
fi
