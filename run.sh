#!/usr/bin/env bash
# Build (Maven shade) then run TextSend.
#   ./run.sh
# Skip rebuild:
#   ./run.sh skip
# Needs: JDK 17+ and Maven on PATH
set -euo pipefail
cd "$(dirname "$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")")"

if ! command -v java >/dev/null 2>&1; then
  echo "Missing java. Add JDK 17+ to PATH." >&2
  exit 1
fi

JAR="$PWD/target/TextSend.jar"
arg="${1:-}"

if [[ "${arg,,}" != "skip" && "${arg,,}" != "fast" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Missing mvn. Add Maven to PATH." >&2
    exit 1
  fi
  echo "==> mvn package"
  mvn -q -DskipTests -f pom.xml package
fi

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR" >&2
  echo "Run without \"skip\" so Maven can build it." >&2
  exit 1
fi

echo "==> java -jar \"$JAR\""
exec java -jar "$JAR"
