#!/usr/bin/env bash
# macOS .dmg（jpackage，自带 JRE）。必须在 Mac 上跑。
# 用法：./pack/pack-mac.sh
# 产物：dist/*.dmg
set -euo pipefail
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo ".dmg 只能在 macOS 上打（jpackage 不能跨平台打包）" >&2
  exit 1
fi

need jpackage
if [[ "${SKIP_JAR:-}" != 1 ]]; then
  "$PACK_DIR/pack-jar.sh"
elif [[ ! -f "$FAT_JAR" ]]; then
  "$PACK_DIR/pack-jar.sh"
fi

rm -rf "$JPACKAGE_INPUT"
mkdir -p "$JPACKAGE_INPUT"
cp -f "$FAT_JAR" "$JPACKAGE_INPUT/TextSend.jar"

echo "==> jpackage dmg"
rm -f "$DIST"/*.dmg

jpackage \
  --type dmg \
  --name TextSend \
  --app-version "$APP_VERSION" \
  --vendor "TextSend" \
  --description "局域网文字互传" \
  --dest "$DIST" \
  --input "$JPACKAGE_INPUT" \
  --main-jar TextSend.jar \
  --main-class "$MAIN_CLASS" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options '-Dtextsend.home=$ROOTDIR'

echo "OK  dist/ 下的 .dmg"
ls -1 "$DIST"/*.dmg
echo "未签名。本机打开若被拦，可右键打开或去系统设置放行。"
echo "配置：程序目录能写就写旁边；否则主目录 .textsend.properties"
