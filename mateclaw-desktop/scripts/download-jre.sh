#!/usr/bin/env bash
#
# scripts/download-jre.sh — Download Eclipse Temurin JRE 21 for the current
# macOS architecture (or both) and extract into resources/jre/<platform>/.
#
# Usage:
#   scripts/download-jre.sh            # auto-detect current arch
#   scripts/download-jre.sh arm64      # arm64 only
#   scripts/download-jre.sh x64        # x64 only
#   scripts/download-jre.sh all        # both arches
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JRE_DIR="$PROJECT_ROOT/resources/jre"

# Temurin 21 (LTS) JRE downloads via Adoptium API
ADOPTIUM_BASE="https://api.adoptium.net/v3/binary/latest/21/ga/mac"

download_and_extract() {
  local arch="$1"
  local folder="$2"
  local url="$ADOPTIUM_BASE/$arch/jre/hotspot/normal/eclipse?project=jdk"
  local tmpfile="$JRE_DIR/jre-mac-$arch.tar.gz"

  echo "==> Downloading Temurin 21 JRE for macOS $arch"
  mkdir -p "$JRE_DIR"
  curl -L --fail -o "$tmpfile" "$url"

  echo "==> Extracting to $JRE_DIR/$folder"
  rm -rf "$JRE_DIR/$folder"
  mkdir -p "$JRE_DIR/$folder"

  # Temurin macOS tar.gz extracts to: jdk-21.x.x+jre/Contents/Home/...
  # We want $folder/Contents/Home/... so move the inner Contents up.
  local extract_tmp="$JRE_DIR/.tmp-$arch"
  rm -rf "$extract_tmp"
  mkdir -p "$extract_tmp"
  tar -xzf "$tmpfile" -C "$extract_tmp"

  # Find the extracted top-level directory and move its Contents
  local extracted_dir
  extracted_dir=$(find "$extract_tmp" -maxdepth 1 -type d -name "jdk-*" | head -1)
  if [ -z "$extracted_dir" ]; then
    # Fallback: some tarballs extract Contents directly
    if [ -d "$extract_tmp/Contents" ]; then
      mv "$extract_tmp/Contents" "$JRE_DIR/$folder/Contents"
    else
      echo "ERROR: Could not find extracted JDK directory"
      exit 1
    fi
  else
    mv "$extracted_dir/Contents" "$JRE_DIR/$folder/Contents"
  fi

  rm -rf "$extract_tmp" "$tmpfile"

  # Verify java binary exists
  local java_bin="$JRE_DIR/$folder/Contents/Home/bin/java"
  if [ -f "$java_bin" ]; then
    echo "==> OK: $java_bin"
  else
    echo "ERROR: java binary not found at $java_bin"
    exit 1
  fi
}

TARGET="${1:-auto}"
if [ "$TARGET" = "auto" ]; then
  case "$(uname -m)" in
    arm64) TARGET="arm64" ;;
    x86_64) TARGET="x64" ;;
    *) echo "Unsupported arch: $(uname -m)"; exit 1 ;;
  esac
fi

case "$TARGET" in
  arm64) download_and_extract "aarch64" "mac-arm64" ;;
  x64)   download_and_extract "x64" "mac-x64" ;;
  all)   download_and_extract "aarch64" "mac-arm64"
         download_and_extract "x64" "mac-x64" ;;
  *) echo "Usage: $0 [arm64|x64|all]"; exit 1 ;;
esac

echo "==> JRE setup complete."
