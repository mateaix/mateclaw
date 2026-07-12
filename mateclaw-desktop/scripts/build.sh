#!/usr/bin/env bash
#
# scripts/build.sh — Build the MateClaw Spring Boot backend JAR and place it
# at resources/app.jar so electron-builder can bundle it into the desktop app.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$(cd "$PROJECT_ROOT/../mateclaw-server" && pwd)"
RESOURCES_DIR="$PROJECT_ROOT/resources"

echo "==> Building mateclaw-server JAR from $SERVER_DIR"

# Build the Spring Boot fat JAR (skip tests for packaging speed)
cd "$SERVER_DIR"
mvn clean package -DskipTests -Dmaven.test.skip=true -q

# Locate the built JAR
JAR_FILE=$(ls "$SERVER_DIR"/target/mateclaw-server-*.jar 2>/dev/null | head -1)
if [ -z "$JAR_FILE" ]; then
  echo "ERROR: Could not find built JAR in $SERVER_DIR/target/"
  exit 1
fi

echo "==> Copying $JAR_FILE → $RESOURCES_DIR/app.jar"
mkdir -p "$RESOURCES_DIR"
cp "$JAR_FILE" "$RESOURCES_DIR/app.jar"

echo "==> Done. JAR size: $(du -h "$RESOURCES_DIR/app.jar" | cut -f1)"
