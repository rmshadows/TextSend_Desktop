#!/usr/bin/env bash
# 被打包脚本 source。目录：TextSend_Desktop/
set -euo pipefail

PACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$PACK_DIR/.." && pwd)"
cd "$ROOT"

MAIN_JAVA="src/cn.rmshadows.TextSend/application/TextSendMain.java"
if [[ ! -f "$MAIN_JAVA" ]]; then
  echo "找不到 $MAIN_JAVA" >&2
  exit 1
fi

VERSION="$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' "$MAIN_JAVA" | head -1)"
if [[ -z "$VERSION" ]]; then
  echo "读不到 VERSION" >&2
  exit 1
fi
# jpackage / deb 只要数字版本
APP_VERSION="${VERSION%%-*}"

DIST="$ROOT/dist"
JPACKAGE_INPUT="$DIST/jpackage-input"
FAT_JAR="$DIST/TextSend.jar"
MAIN_CLASS="application.TextSendMain"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1" >&2
    exit 1
  fi
}

echo "TextSend $VERSION  (app-version $APP_VERSION)"
echo "项目目录 $ROOT"
