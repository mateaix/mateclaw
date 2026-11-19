#!/usr/bin/env bash
#
# Build the MateClaw backend JAR and place it at resources/app.jar so
# electron-builder can bundle it into the desktop app.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$DESKTOP_DIR/.." && pwd)"
RESOURCES_DIR="$DESKTOP_DIR/resources"
UI_DIR="$PROJECT_ROOT/mateclaw-ui"

echo "╔══════════════════════════════════════════════════╗"
echo "║          MateClaw Desktop - Build Script         ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

echo "📦 Building MateClaw web UI..."

if [ ! -d "$UI_DIR" ]; then
    echo "❌ Frontend directory not found: $UI_DIR"
    exit 1
fi

cd "$UI_DIR"
if [ ! -d "node_modules" ]; then
    echo "   Installing frontend dependencies..."
    if command -v pnpm &>/dev/null; then
        pnpm install
    elif command -v npm &>/dev/null; then
        npm install
    else
        echo "❌ Neither pnpm nor npm found"
        exit 1
    fi
fi

if command -v pnpm &>/dev/null; then
    pnpm build
else
    npm run build
fi

echo ""
echo "📦 Building Spring Boot JAR with Maven reactor..."

cd "$PROJECT_ROOT"

if command -v mvn &>/dev/null; then
    MVN="mvn"
elif [ -f "./mvnw" ]; then
    MVN="./mvnw"
    chmod +x ./mvnw
else
    echo "❌ Maven not found. Install Maven or use Maven wrapper."
    exit 1
fi

$MVN -pl mateclaw-server -am clean package -DskipTests -Dmaven.test.skip=true -q

JAR_FILE=$(find mateclaw-server/target -maxdepth 1 -type f -name 'mateclaw-server-*.jar' ! -name '*sources*' ! -name '*.original' | sort | tail -1)
if [ -z "$JAR_FILE" ]; then
    echo "❌ JAR file not found in mateclaw-server/target/"
    exit 1
fi

echo "✅ JAR built: $JAR_FILE"
echo ""

echo "📦 Copying JAR to desktop resources..."
mkdir -p "$RESOURCES_DIR"
cp "$JAR_FILE" "$RESOURCES_DIR/app.jar"

JAR_SIZE=$(du -h "$RESOURCES_DIR/app.jar" | cut -f1)
echo "✅ JAR copied to: $RESOURCES_DIR/app.jar ($JAR_SIZE)"
echo ""
