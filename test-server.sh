#!/usr/bin/env bash
# Builds the plugin and starts a local Paper test server in ./run.
# Usage: ./test-server.sh [paper-version]   (default: 26.3)
set -euo pipefail

PAPER_VERSION="${1:-26.3}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
RUN_DIR="$ROOT/run"

# Paper 26.x needs Java 25 or newer.
JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
if [ "$JAVA_MAJOR" -lt 25 ]; then
  echo "Java 25+ benötigt, gefunden: Java $JAVA_MAJOR" >&2
  exit 1
fi

echo "==> Plugin bauen"
"$ROOT/mvnw" -B -q package

mkdir -p "$RUN_DIR/plugins"

echo "==> Neuesten Paper-Build für $PAPER_VERSION suchen"
BUILD_JSON=$(curl -fsS "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest")
PAPER_URL=$(echo "$BUILD_JSON" | grep -oE '"url":"[^"]+' | head -1 | cut -d'"' -f4)
PAPER_SHA=$(echo "$BUILD_JSON" | grep -oE '"sha256":"[^"]+' | head -1 | cut -d'"' -f4)
PAPER_JAR="$RUN_DIR/${PAPER_URL##*/}"

if [ ! -f "$PAPER_JAR" ]; then
  echo "==> Lade ${PAPER_JAR##*/}"
  curl -fsS -o "$PAPER_JAR.part" "$PAPER_URL"
  echo "$PAPER_SHA  $PAPER_JAR.part" | shasum -a 256 -c - >/dev/null
  mv "$PAPER_JAR.part" "$PAPER_JAR"
fi

# Running the server implies accepting the Minecraft EULA: https://aka.ms/MinecraftEULA
echo "eula=true" > "$RUN_DIR/eula.txt"

rm -f "$RUN_DIR"/plugins/SEssentials-*.jar
cp "$ROOT"/target/SEssentials-*.jar "$RUN_DIR/plugins/"

echo "==> Starte Paper $PAPER_VERSION (Konsole: 'stop' zum Beenden)"
cd "$RUN_DIR"
exec java -Xms1G -Xmx2G -jar "$PAPER_JAR" --nogui
