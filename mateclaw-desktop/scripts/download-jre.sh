#!/usr/bin/env bash
#
# Download Adoptium JRE 21 for the requested platform.
#
# Usage:
#   bash scripts/download-jre.sh [platform-arch]
#   bash scripts/download-jre.sh mac-arm64
#   bash scripts/download-jre.sh mac-x64
#   bash scripts/download-jre.sh win-x64
#   bash scripts/download-jre.sh win-arm64
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCES_DIR="$PROJECT_DIR/resources"

JRE_VERSION="21"
ADOPTIUM_API="https://api.adoptium.net/v3/binary/latest"

detect_platform() {
    local os arch

    case "$(uname -s)" in
        Darwin) os="mac" ;;
        Linux)  os="linux" ;;
        MINGW*|MSYS*|CYGWIN*) os="win32" ;;
        *) echo "Unsupported OS: $(uname -s)"; exit 1 ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64) arch="x64" ;;
        arm64|aarch64) arch="arm64" ;;
        *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
    esac

    echo "${os}-${arch}"
}

PLATFORM="${1:-$(detect_platform)}"
echo "🔧 Platform: $PLATFORM"

case "$PLATFORM" in
    mac-arm64)
        API_OS="mac"
        API_ARCH="aarch64"
        ARCHIVE_EXT="tar.gz"
        ;;
    mac-x64)
        API_OS="mac"
        API_ARCH="x64"
        ARCHIVE_EXT="tar.gz"
        ;;
    win-x64|win32-x64)
        API_OS="windows"
        API_ARCH="x64"
        ARCHIVE_EXT="zip"
        ;;
    win-arm64)
        API_OS="windows"
        API_ARCH="aarch64"
        ARCHIVE_EXT="zip"
        ;;
    linux-x64)
        API_OS="linux"
        API_ARCH="x64"
        ARCHIVE_EXT="tar.gz"
        ;;
    linux-arm64)
        API_OS="linux"
        API_ARCH="aarch64"
        ARCHIVE_EXT="tar.gz"
        ;;
    *)
        echo "❌ Unsupported platform: $PLATFORM"
        exit 1
        ;;
esac

TARGET_DIR="$RESOURCES_DIR/jre/$PLATFORM"

case "$PLATFORM" in
    mac-*)         JRE_NATIVE_LIB="Contents/Home/lib/server/libjvm.dylib"; JAVA_REL="Contents/Home/bin/java" ;;
    win-*|win32-*) JRE_NATIVE_LIB="bin/server/jvm.dll";                     JAVA_REL="bin/java.exe" ;;
    linux-*)       JRE_NATIVE_LIB="lib/server/libjvm.so";                   JAVA_REL="bin/java" ;;
esac

if [ -d "$TARGET_DIR" ] && [ -f "$TARGET_DIR/$JRE_NATIVE_LIB" ]; then
    echo "✅ JRE already exists and looks complete at $TARGET_DIR"
    chmod -R u+w "$TARGET_DIR"
    exit 0
elif [ -d "$TARGET_DIR" ] && [ -n "$(ls -A "$TARGET_DIR" 2>/dev/null)" ]; then
    echo "⚠️  Existing JRE at $TARGET_DIR is incomplete (missing $JRE_NATIVE_LIB) — re-downloading."
    rm -rf "$TARGET_DIR"
fi

mkdir -p "$TARGET_DIR"

DOWNLOAD_URL="${ADOPTIUM_API}/${JRE_VERSION}/ga/${API_OS}/${API_ARCH}/jre/hotspot/normal/eclipse"

echo "📥 Downloading JRE ${JRE_VERSION} from Adoptium..."
echo "   URL: $DOWNLOAD_URL"

TEMP_FILE="$(mktemp)"

if command -v curl &>/dev/null; then
    curl -fSL --progress-bar -o "$TEMP_FILE" "$DOWNLOAD_URL"
elif command -v wget &>/dev/null; then
    wget -q --show-progress -O "$TEMP_FILE" "$DOWNLOAD_URL"
else
    echo "❌ Neither curl nor wget found"
    exit 1
fi

echo "📦 Extracting JRE..."

if [ "$ARCHIVE_EXT" = "tar.gz" ]; then
    tar -xzf "$TEMP_FILE" -C "$TARGET_DIR" --strip-components=1
elif [ "$ARCHIVE_EXT" = "zip" ]; then
    TEMP_EXTRACT="$(mktemp -d)"
    unzip -q "$TEMP_FILE" -d "$TEMP_EXTRACT"
    EXTRACTED_DIR=$(ls "$TEMP_EXTRACT")
    mv "$TEMP_EXTRACT/$EXTRACTED_DIR"/* "$TARGET_DIR/"
    rm -rf "$TEMP_EXTRACT"
fi

rm -f "$TEMP_FILE"

# codesign --force needs write permission on upstream read-only files.
chmod -R u+w "$TARGET_DIR"

JAVA_BIN="$TARGET_DIR/$JAVA_REL"
chmod +x "$JAVA_BIN" 2>/dev/null || true

if [ ! -f "$JAVA_BIN" ] || [ ! -f "$TARGET_DIR/$JRE_NATIVE_LIB" ]; then
    echo "❌ JRE is incomplete — missing java launcher or native runtime ($JRE_NATIVE_LIB)"
    echo "   Removing $TARGET_DIR so the next run re-downloads."
    rm -rf "$TARGET_DIR"
    exit 1
fi

HOST_PLATFORM="$(detect_platform | sed 's/^win32-/win-/')"
NORM_PLATFORM="$(echo "$PLATFORM" | sed 's/^win32-/win-/')"
if [ "$NORM_PLATFORM" = "$HOST_PLATFORM" ]; then
    if ! "$JAVA_BIN" -version >/dev/null 2>&1; then
        echo "❌ JRE installed but 'java -version' failed — the runtime is broken"
        "$JAVA_BIN" -version 2>&1 | head -5 || true
        rm -rf "$TARGET_DIR"
        exit 1
    fi
fi

echo "✅ JRE installed successfully"
"$JAVA_BIN" -version 2>&1 | head -1 || true
echo "📁 JRE location: $TARGET_DIR"
