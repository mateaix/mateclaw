#!/usr/bin/env bash
#
# scripts/build-all-platforms.sh — Build MateClaw desktop packages for all
# platforms (macOS + Windows) in the specified build mode.
#
# Usage:
#   scripts/build-all-platforms.sh --all      # local mode (default), both platforms
#   scripts/build-all-platforms.sh --local    # local mode, both platforms
#   scripts/build-all-platforms.sh --remote   # remote mode, both platforms
#
set -euo pipefail

MODE="--all"
case "${1:-}" in
  --all)    MODE="local"  ;;
  --local)  MODE="local"  ;;
  --remote) MODE="remote" ;;
  *) echo "Usage: $0 [--all|--local|--remote]"; exit 1 ;;
esac

echo "==> Building all platforms, BUILD_MODE=$MODE"

if [ "$MODE" = "remote" ]; then
  echo "==> macOS (remote/lite)"
  BUILD_MODE=remote npx electron-builder --mac
  echo "==> Windows (remote/lite)"
  BUILD_MODE=remote npx electron-builder --win
else
  echo "==> macOS (local/full)"
  BUILD_MODE=local npx electron-builder --mac
  echo "==> Windows (local/full)"
  BUILD_MODE=local npx electron-builder --win
fi

echo "==> All builds complete."
