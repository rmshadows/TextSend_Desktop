#!/usr/bin/env bash
# 3/3  Linux .deb（jpackage，自带 JRE）
# 用法：./pack/pack-deb.sh
# 产物：dist/*.deb
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
# 先清掉旧 deb，避免混淆
rm -f "$DIST"/textsend_*.deb "$DIST"/TextSend-*.deb

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
  --linux-shortcut \
  --linux-menu-group Utility \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options '-Dtextsend.home=$ROOTDIR'

echo "OK  dist/ 下的 .deb"
ls -1 "$DIST"/*.deb
echo "安装：sudo dpkg -i dist/textsend_*.deb"
echo "配置：程序目录能写就写旁边；否则主目录 .textsend.properties"
