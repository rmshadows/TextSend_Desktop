#!/usr/bin/env bash
# 3/3  Linux .deb（jpackage，自带 JRE）
# 用法：./pack/pack-deb.sh
# 产物：
#   dist/textsend_<ver>_<arch>.deb         — 原版：Depends 按本机扫库名
#   dist/textsend_<ver>_<arch>.compat.deb  — 宽松：libasound2 | libasound2t64（Debian 12/13、Ubuntu）
#
# compat 不二次 jpackage（JDK 17 + Actions 上易 dpkg-deb exit 2）：从原版解包改 Depends 再打。
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

# dpkg 解析 control 需要 UTF-8（描述里有中文）
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

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

if [[ ! -f "$DESKTOP_IN" ]]; then
  echo "缺少 $DESKTOP_IN" >&2
  exit 1
fi
if [[ ! -f "$JP_CONSOLE_PROPS" ]]; then
  echo "缺少 $JP_CONSOLE_PROPS" >&2
  exit 1
fi

# 宽松 Depends（与旧 jpackage-resources/control 一致）
COMPAT_DEPENDS='Depends: libc6, libasound2 | libasound2t64, libx11-6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils'

RES_ORIG="$(mktemp -d)"
cp -f "$DESKTOP_IN" "$RES_ORIG/"
echo "==> deb 原版（扫库名）"
jpackage \
  --type deb \
  --name TextSend \
  --linux-package-name textsend \
  --app-version "$APP_VERSION" \
  --vendor "TextSend" \
  --linux-deb-maintainer "noreply@users.noreply.github.com" \
  --description "局域网文字互传" \
  --dest "$DIST" \
  --input "$JPACKAGE_INPUT" \
  --main-jar TextSend.jar \
  --main-class "$MAIN_CLASS" \
  --add-modules "$JP_MODULES" \
  --add-launcher "TextSend-console=$JP_CONSOLE_PROPS" \
  --linux-shortcut \
  --linux-menu-group Utility \
  --resource-dir "$RES_ORIG" \
  "${ICON_JP[@]}" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED' \
  --java-options '--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED' \
  --java-options '-Dtextsend.home=$ROOTDIR'
rm -rf "$RES_ORIG"

ORIG_DEB="$(ls -1 "$DIST"/textsend_*.deb | head -1)"
if [[ -z "$ORIG_DEB" || ! -f "$ORIG_DEB" ]]; then
  echo "jpackage 未产出 .deb" >&2
  exit 1
fi
BASE="$(basename "$ORIG_DEB" .deb)"
echo "原版：$ORIG_DEB"

echo "==> deb 宽松 Depends（compat，从原版改 control）"
TMP_ROOT="$(mktemp -d)"
dpkg-deb -R "$ORIG_DEB" "$TMP_ROOT"
CONTROL="$TMP_ROOT/DEBIAN/control"
if [[ ! -f "$CONTROL" ]]; then
  echo "解包后缺少 DEBIAN/control" >&2
  rm -rf "$TMP_ROOT"
  exit 1
fi
if grep -q '^Depends:' "$CONTROL"; then
  # 分隔符用 #，Depends 里有 libasound2 | libasound2t64
  sed -i "s#^Depends:.*#${COMPAT_DEPENDS}#" "$CONTROL"
else
  printf '%s\n' "$COMPAT_DEPENDS" >> "$CONTROL"
fi
# 确保末尾换行（dpkg-deb 对 Installed-Size 等字段敏感）
if [[ -n "$(tail -c1 "$CONTROL" || true)" ]]; then
  printf '\n' >> "$CONTROL"
fi

COMPAT_OUT="$DIST/${BASE}.compat.deb"
if ! fakeroot dpkg-deb -b "$TMP_ROOT" "$COMPAT_OUT"; then
  echo "compat dpkg-deb 失败，control 内容：" >&2
  cat "$CONTROL" >&2 || true
  rm -rf "$TMP_ROOT"
  exit 1
fi
rm -rf "$TMP_ROOT"

# 原版保持 jpackage 文件名；compat 为 *.compat.deb
echo "OK  dist/ 下的 .deb"
ls -1 "$DIST"/*.deb
echo "原版（本机扫库名）：$ORIG_DEB"
echo "宽松（Debian 12/13、Ubuntu）：$COMPAT_OUT"
echo "装一份即可，不要两个一起装（同名包）。"
echo "日常：/opt/textsend/bin/TextSend"
echo "调试：/opt/textsend/bin/TextSend-console（终端跑可看 Log）"
echo "配置：程序目录能写就写旁边；否则主目录 .textsend.properties"
