#!/usr/bin/env bash
#
# Build MateClaw Desktop packages.
# This script:
# 1. Builds backend JAR once
# 2. Downloads/verifies JREs for selected platforms
# 3. Clears stale frontend/package output
# 4. Builds the Electron/Vue frontend
# 5. Packages macOS and/or Windows installers
#
# Usage:
#   bash scripts/build-all-platforms.sh --all
#   bash scripts/build-all-platforms.sh --mac-only
#   bash scripts/build-all-platforms.sh --win-only
#   bash scripts/build-all-platforms.sh --all --publish=always
#   BUILD_MODE=remote bash scripts/build-all-platforms.sh --mac-only
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_TYPE="all"
BUILD_MODE="${BUILD_MODE:-local}"
PUBLISH_FLAG="${PUBLISH_FLAG:---publish=never}"

for arg in "$@"; do
  case "$arg" in
    --mac-only|mac) BUILD_TYPE="mac" ;;
    --win-only|win) BUILD_TYPE="win" ;;
    --all|all) BUILD_TYPE="all" ;;
    --local|local) BUILD_MODE="local" ;;
    --remote|remote) BUILD_MODE="remote" ;;
    --publish=*) PUBLISH_FLAG="$arg" ;;
    *)
      echo "Usage: $0 [--mac-only|--win-only|--all|--local|--remote] [--publish=never|always|onTagOrDraft]"
      exit 1
      ;;
  esac
done

case "$BUILD_TYPE" in
  mac) PLATFORMS=("mac-arm64" "mac-x64") ;;
  win) PLATFORMS=("win-x64" "win-arm64") ;;
  all) PLATFORMS=("mac-arm64" "mac-x64" "win-x64" "win-arm64") ;;
esac

echo "╔════════════════════════════════════════════════════════╗"
echo "║       MateClaw Desktop - Multi-Platform Build         ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""
echo "  Build mode: $BUILD_MODE"
echo "  Publish:    $PUBLISH_FLAG"
echo ""

cd "$DESKTOP_DIR"

if [ "$BUILD_MODE" = "local" ]; then
  echo "📦 [1/4] Building backend JAR..."
  bash "$SCRIPT_DIR/build.sh"
  echo ""

  echo "📥 [2/4] Downloading/verifying JREs..."
  for platform in "${PLATFORMS[@]}"; do
    echo "   → $platform"
    bash "$SCRIPT_DIR/download-jre.sh" "$platform"
  done
  echo ""
else
  echo "📦 [1/4] Skipping backend JAR for remote/lite build."
  echo "📥 [2/4] Skipping bundled JREs for remote/lite build."
  echo ""
fi

echo "🧹 [3/4] Clearing stale desktop build output..."
rm -rf "$DESKTOP_DIR/dist" "$DESKTOP_DIR/dist-electron" "$DESKTOP_DIR/release"
echo ""

echo "📦 [4/4] Building Electron frontend and packaging..."
npm run build
echo ""

echo "   Packaging for:"
if [[ " ${PLATFORMS[*]} " =~ " mac-arm64 " ]] || [[ " ${PLATFORMS[*]} " =~ " mac-x64 " ]]; then
  echo "     • macOS (arm64 + x64)"
  if [[ " ${PLATFORMS[*]} " =~ " mac-arm64 " ]]; then
    BUILD_MODE="$BUILD_MODE" BUILD_ARCH=arm64 npx electron-builder --mac $PUBLISH_FLAG
  fi
  if [[ " ${PLATFORMS[*]} " =~ " mac-x64 " ]]; then
    BUILD_MODE="$BUILD_MODE" BUILD_ARCH=x64 npx electron-builder --mac $PUBLISH_FLAG
  fi
fi

if [[ " ${PLATFORMS[*]} " =~ " win-x64 " ]] || [[ " ${PLATFORMS[*]} " =~ " win-arm64 " ]]; then
  echo "     • Windows (x64 + arm64)"
  if [[ " ${PLATFORMS[*]} " =~ " win-x64 " ]]; then
    BUILD_MODE="$BUILD_MODE" BUILD_ARCH=x64 npx electron-builder --win $PUBLISH_FLAG
  fi
  if [[ " ${PLATFORMS[*]} " =~ " win-arm64 " ]]; then
    BUILD_MODE="$BUILD_MODE" BUILD_ARCH=arm64 npx electron-builder --win $PUBLISH_FLAG
  fi
fi

echo ""
echo "╔════════════════════════════════════════════════════════╗"
echo "║                   Build Complete!                      ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""
echo "  📁 Output directory: $DESKTOP_DIR/release/"
echo ""
echo "  Files:"
if [[ " ${PLATFORMS[*]} " =~ " mac-" ]]; then
  ls -lh "$DESKTOP_DIR/release/"MateClaw*.dmg 2>/dev/null || true
  ls -lh "$DESKTOP_DIR/release/"MateClaw*.zip 2>/dev/null || true
  ls -lh "$DESKTOP_DIR/release/"latest-mac.yml 2>/dev/null || true
fi
if [[ " ${PLATFORMS[*]} " =~ " win-" ]]; then
  ls -lh "$DESKTOP_DIR/release/"MateClaw*Setup*.exe 2>/dev/null || true
  ls -lh "$DESKTOP_DIR/release/"latest.yml 2>/dev/null || true
fi
echo ""
