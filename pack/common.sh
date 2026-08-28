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
FAT_JAR="$DIST/Textsend_${VERSION}.jar"
MAIN_CLASS="application.TextSendMain"
ICON_PNG="$ROOT/other/icon.png"
ICON_ICO="$ROOT/other/icon.ico"
ICON_ICNS="$ROOT/other/icon.icns"
# 与 Windows pack-appimage.bat / pack-win.bat 同一份；必须含 jdk.charsets（ZXing 要 EUC_JP）
JP_MODULES="java.base,java.desktop,java.datatransfer,java.sql,java.xml,jdk.charsets"
# 调试用第二启动器（对应 Windows win-console.properties）
case "$(uname -s)" in
  Darwin*) JP_CONSOLE_PROPS="$PACK_DIR/mac-console.properties" ;;
  *)       JP_CONSOLE_PROPS="$PACK_DIR/linux-console.properties" ;;
esac

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令：$1" >&2
    exit 1
  fi
}

echo "TextSend $VERSION  (app-version $APP_VERSION)"
echo "项目目录 $ROOT"
