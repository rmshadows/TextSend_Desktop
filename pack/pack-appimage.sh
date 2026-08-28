#!/usr/bin/env bash
# 2/3  绿色目录：自带精简 JRE，解压即用（不装系统 Java）
# 注意：这是 jpackage app-image，不是 Linux .AppImage 单文件
# 用法：./pack/pack-appimage.sh
# 产物：dist/TextSend/（或 macOS 的 TextSend.app）以及 dist/TextSend-<version>-<os>.tar.gz
set -euo pipefail
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

need jpackage
if [[ "${SKIP_JAR:-}" != 1 ]]; then
  "$PACK_DIR/pack-jar.sh"
elif [[ ! -f "$FAT_JAR" ]]; then
  "$PACK_DIR/pack-jar.sh"
fi

rm -rf "$JPACKAGE_INPUT" "$DIST/TextSend" "$DIST/TextSend.app"
mkdir -p "$JPACKAGE_INPUT"
cp -f "$FAT_JAR" "$JPACKAGE_INPUT/TextSend.jar"

OS="$(uname -s)"
case "$OS" in
  Linux*)             TAG="linux-x64" ;;
  Darwin*)            TAG="mac" ;;
  MINGW*|MSYS*|CYGWIN*) TAG="win-x64" ;;
  *)                  TAG="unknown" ;;
esac

echo "==> jpackage app-image"
# $ROOTDIR 必须单引号，交给 jpackage 运行时展开（安装/解压目录根）
ICON_JP=()
if [[ -f "$ICON_PNG" ]]; then
  ICON_JP=(--icon "$ICON_PNG")
else
  echo "警告：找不到 $ICON_PNG，绿色目录将用默认 Java 图标" >&2
fi

# 与 Windows 一样打出日常 + 调试两个启动器
if [[ ! -f "$JP_CONSOLE_PROPS" ]]; then
  echo "缺少 $JP_CONSOLE_PROPS" >&2
  exit 1
fi
ADD_LAUNCHER=(--add-launcher "TextSend-console=$JP_CONSOLE_PROPS")

jpackage \
  --type app-image \
  --name TextSend \
  --app-version "$APP_VERSION" \
  --vendor "TextSend" \
  --description "局域网文字互传" \
  --dest "$DIST" \
  --input "$JPACKAGE_INPUT" \
  --main-jar TextSend.jar \
  --main-class "$MAIN_CLASS" \
  --add-modules "$JP_MODULES" \
  "${ADD_LAUNCHER[@]}" \
  "${ICON_JP[@]}" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED' \
  --java-options '--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED' \
  --java-options '-Dtextsend.home=$ROOTDIR'

ARCHIVE="$DIST/TextSend-${VERSION}-${TAG}.tar.gz"
rm -f "$ARCHIVE"
if [[ -d "$DIST/TextSend.app" ]]; then
  tar -C "$DIST" -czf "$ARCHIVE" TextSend.app
  echo "OK  $DIST/TextSend.app"
  echo "日常：$DIST/TextSend.app"
  if [[ -x "$DIST/TextSend.app/Contents/MacOS/TextSend-console" ]]; then
    echo "调试：$DIST/TextSend.app/Contents/MacOS/TextSend-console"
  fi
else
  tar -C "$DIST" -czf "$ARCHIVE" TextSend
  echo "OK  $DIST/TextSend/"
  echo "日常：$DIST/TextSend/bin/TextSend"
  if [[ -x "$DIST/TextSend/bin/TextSend-console" ]]; then
    echo "调试：$DIST/TextSend/bin/TextSend-console"
  fi
fi
echo "OK  $ARCHIVE"
echo "配置：安装/解压目录下的 textsend.properties（不写用户主目录）"
