#!/usr/bin/env bash
# 3/3  Linux .deb（jpackage，自带 JRE）
# 用法：./pack/pack-deb.sh
# 产物：
#   dist/textsend_<ver>_<arch>.deb         — 原版：Depends 按本机扫库名
#   dist/textsend_<ver>_<arch>.compat.deb  — 宽松：libasound2 | libasound2t64（Debian 12/13、Ubuntu）
#
# 缩放等配置：程序目录能写就写旁边；/opt 不能写则退回用户主目录 .textsend.properties。
set -euo pipefail
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo ".deb 只能在 Linux 上打" >&2
  exit 1
fi

need jpackage
need fakeroot
need dpkg-deb

if [[ "${SKIP_JAR:-}" != 1 ]]; then
  "$PACK_DIR/pack-jar.sh"
elif [[ ! -f "$FAT_JAR" ]]; then
  "$PACK_DIR/pack-jar.sh"
fi

rm -rf "$JPACKAGE_INPUT"
mkdir -p "$JPACKAGE_INPUT"
cp -f "$FAT_JAR" "$JPACKAGE_INPUT/TextSend.jar"

echo "==> jpackage deb"
rm -f "$DIST"/textsend_*.deb "$DIST"/TextSend-*.deb

ICON_JP=()
if [[ -f "$ICON_PNG" ]]; then
  ICON_JP=(--icon "$ICON_PNG")
else
  echo "警告：找不到 $ICON_PNG，.deb 将用默认 Java 图标" >&2
fi

JPACKAGE_RESOURCES="$PACK_DIR/jpackage-resources"
DESKTOP_IN="$JPACKAGE_RESOURCES/TextSend.desktop"
CONTROL_COMPAT="$JPACKAGE_RESOURCES/control"

if [[ ! -f "$DESKTOP_IN" ]]; then
  echo "缺少 $DESKTOP_IN" >&2
  exit 1
fi
if [[ ! -f "$CONTROL_COMPAT" ]]; then
  echo "缺少 $CONTROL_COMPAT" >&2
  exit 1
fi
if [[ ! -f "$JP_CONSOLE_PROPS" ]]; then
  echo "缺少 $JP_CONSOLE_PROPS" >&2
  exit 1
fi

build_deb() {
  local resdir="$1"
  jpackage \
    --type deb \
    --name TextSend \
    --linux-package-name textsend \
    --app-version "$APP_VERSION" \
    --vendor "TextSend" \
    --description "局域网文字互传" \
    --dest "$DIST" \
    --input "$JPACKAGE_INPUT" \
    --main-jar TextSend.jar \
    --main-class "$MAIN_CLASS" \
    --add-modules "$JP_MODULES" \
    --add-launcher "TextSend-console=$JP_CONSOLE_PROPS" \
    --linux-shortcut \
    --linux-menu-group Utility \
    --resource-dir "$resdir" \
    "${ICON_JP[@]}" \
    --java-options "-Dfile.encoding=UTF-8" \
    --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED' \
    --java-options '--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED' \
    --java-options '-Dtextsend.home=$ROOTDIR'
}

# 原版：只要 desktop，让 jpackage 按本机扫 Depends
RES_ORIG="$(mktemp -d)"
cp -f "$DESKTOP_IN" "$RES_ORIG/"
echo "==> deb 原版（扫库名）"
build_deb "$RES_ORIG"
ORIG_DEB="$(ls -1 "$DIST"/textsend_*.deb | head -1)"
ORIG_SAVED="$(mktemp)"
cp -f "$ORIG_DEB" "$ORIG_SAVED"
rm -f "$ORIG_DEB"
rm -rf "$RES_ORIG"

# 宽松：desktop + control
RES_COMPAT="$(mktemp -d)"
cp -f "$DESKTOP_IN" "$RES_COMPAT/"
cp -f "$CONTROL_COMPAT" "$RES_COMPAT/control"
echo "==> deb 宽松 Depends（compat）"
build_deb "$RES_COMPAT"
COMPAT_DEB="$(ls -1 "$DIST"/textsend_*.deb | head -1)"
BASE="$(basename "$COMPAT_DEB" .deb)"
mv -f "$COMPAT_DEB" "$DIST/${BASE}.compat.deb"
cp -f "$ORIG_SAVED" "$DIST/${BASE}.deb"
rm -f "$ORIG_SAVED"
rm -rf "$RES_COMPAT"

echo "OK  dist/ 下的 .deb"
ls -1 "$DIST"/*.deb
echo "原版（本机扫库名）：$DIST/${BASE}.deb"
echo "宽松（Debian 12/13、Ubuntu）：$DIST/${BASE}.compat.deb"
echo "装一份即可，不要两个一起装（同名包）。"
echo "日常：/opt/textsend/bin/TextSend"
echo "调试：/opt/textsend/bin/TextSend-console（终端跑可看 Log）"
echo "配置：程序目录能写就写旁边；否则主目录 .textsend.properties"
