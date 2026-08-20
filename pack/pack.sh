#!/usr/bin/env bash
# 一键：fat JAR + 绿色目录（jpackage app-image）+ 本平台安装包
# Linux → 再打 .deb；macOS → 再打 .dmg；Windows 请用 pack.ps1
# 用法：./pack/pack.sh
set -euo pipefail
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

"$PACK_DIR/pack-jar.sh"
export SKIP_JAR=1
"$PACK_DIR/pack-appimage.sh"

OS="$(uname -s)"
case "$OS" in
  Linux)
    "$PACK_DIR/pack-deb.sh"
    ;;
  Darwin)
    "$PACK_DIR/pack-mac.sh"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    "$PACK_DIR/pack-win.sh"
    ;;
  *)
    echo "本平台没有系统安装包脚本（只出了 JAR 和绿色目录）"
    ;;
esac

echo
echo "==> 完成，产物在 $DIST"
ls -1 "$DIST" | sed 's/^/  /'
