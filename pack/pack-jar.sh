#!/usr/bin/env bash
# 1/3  fat JAR：对方机器需要 Java 17+
# 用法：./pack/pack-jar.sh
# 产物：dist/Textsend_<版本>.jar
set -euo pipefail
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

need mvn
mkdir -p "$DIST"

if [[ "${SKIP_JAR:-}" == 1 && -f "$FAT_JAR" ]]; then
  echo "跳过 Maven（已有 $FAT_JAR）"
  exit 0
fi

echo "==> Maven shade"
mvn -q -DskipTests package

if [[ ! -f "$ROOT/target/TextSend.jar" ]]; then
  echo "Maven 没有打出 target/TextSend.jar" >&2
  exit 1
fi
cp -f "$ROOT/target/TextSend.jar" "$FAT_JAR"
rm -f "$DIST/TextSend.jar"
echo "OK  $FAT_JAR"
echo "运行：java -jar \"$FAT_JAR\""
echo "配置文件会写在 jar 同一目录的 textsend.properties"
