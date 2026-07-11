#!/usr/bin/env bash
#
# Publish MateClaw Desktop release artifacts to GitHub Releases
#
# Prerequisites:
#   - gh CLI installed and authenticated (gh auth login)
#   - Build artifacts exist in release/ directory (run build-all-platforms.sh first)
#
# Usage:
#   bash scripts/publish-github.sh                        # Create release (direct)
#   bash scripts/publish-github.sh --proxy                # Create release via proxy (127.0.0.1:7890)
#   bash scripts/publish-github.sh --proxy=host:port      # Create release via custom proxy
#   bash scripts/publish-github.sh --draft                # Create draft release
#   bash scripts/publish-github.sh --tag=v1.1.0           # Custom tag (default: v{version} from package.json)
#   bash scripts/publish-github.sh --retry                # Delete existing release and re-upload
#   bash scripts/publish-github.sh --proxy --retry --draft # Combine options
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$DESKTOP_DIR/release"

# ─── Parse arguments ───────────────────────────────────────────────────────

DRAFT=""
CUSTOM_TAG=""
USE_PROXY=""
PROXY_ADDR="127.0.0.1:7890"
RETRY=false
NOTES_FILE_OVERRIDE=""
NOTES_LANG="en"

for arg in "$@"; do
  case "$arg" in
    --draft) DRAFT="--draft" ;;
    --tag=*) CUSTOM_TAG="${arg#--tag=}" ;;
    --proxy) USE_PROXY=true ;;
    --proxy=*) USE_PROXY=true; PROXY_ADDR="${arg#--proxy=}" ;;
    --retry) RETRY=true ;;
    --notes-file=*) NOTES_FILE_OVERRIDE="${arg#--notes-file=}" ;;
    --notes-lang=*) NOTES_LANG="${arg#--notes-lang=}" ;;
    -h|--help)
      echo "Usage: $0 [--draft] [--tag=vX.Y.Z] [--proxy[=host:port]] [--retry] [--notes-file=path] [--notes-lang=en|zh]"
      echo ""
      echo "Options:"
      echo "  --draft              Create as draft release"
      echo "  --tag=vX.Y.Z         Custom tag (default: v{version} from package.json)"
      echo "  --proxy              Use proxy 127.0.0.1:7890 for uploading to GitHub"
      echo "  --proxy=host:port    Use custom proxy address"
      echo "  --retry              Delete existing release for this tag before uploading"
      echo "  --notes-file=path    Use this markdown file as release notes (highest priority)"
      echo "  --notes-lang=en|zh   Preferred language for auto-detected notes (default: en)"
      echo ""
      echo "Release notes resolution order:"
      echo "  1. --notes-file=path                                   (explicit override)"
      echo "  2. ../docs/{notes-lang}/releases/X.Y.Z.md              (curated, preferred lang)"
      echo "  3. ../docs/{other-lang}/releases/X.Y.Z.md              (curated, fallback lang)"
      echo "  4. Tag annotation (if multi-line)                      (legacy)"
      echo "  5. Tagged commit message body                          (legacy)"
      echo "  6. --generate-notes                                    (GitHub auto)"
      echo ""
      echo "Examples:"
      echo "  # Step 1: Build locally (no publish)"
      echo "  bash scripts/build-all-platforms.sh --all"
      echo ""
      echo "  # Step 2: Publish to GitHub"
      echo "  bash scripts/publish-github.sh --proxy                       # via proxy, en notes"
      echo "  bash scripts/publish-github.sh --proxy --notes-lang=zh       # zh notes"
      echo "  bash scripts/publish-github.sh --proxy --draft               # draft (preview)"
      echo "  bash scripts/publish-github.sh --proxy --retry               # delete + re-upload"
      echo "  bash scripts/publish-github.sh --notes-file=NOTES.md         # custom notes file"
      echo ""
      echo "Run build-all-platforms.sh first to generate artifacts."
      exit 0
      ;;
    *)
      echo "Unknown option: $arg"
      echo "Usage: $0 [--draft] [--tag=vX.Y.Z] [--proxy[=host:port]] [--retry] [--notes-file=path] [--notes-lang=en|zh]"
      exit 1
      ;;
  esac
done

if [ "$NOTES_LANG" != "en" ] && [ "$NOTES_LANG" != "zh" ]; then
  echo "❌ --notes-lang must be 'en' or 'zh' (got: $NOTES_LANG)"
  exit 1
fi

# ─── Setup proxy ───────────────────────────────────────────────────────────

if [ "$USE_PROXY" = true ]; then
  export https_proxy="http://${PROXY_ADDR}"
  export http_proxy="http://${PROXY_ADDR}"
  export HTTPS_PROXY="http://${PROXY_ADDR}"
  export HTTP_PROXY="http://${PROXY_ADDR}"
  echo "🌐 Proxy enabled: ${PROXY_ADDR}"
  echo ""
fi

# ─── Check prerequisites ──────────────────────────────────────────────────

if ! command -v gh &>/dev/null; then
  echo "❌ gh CLI not found. Install: https://cli.github.com/"
  exit 1
fi

if ! gh auth status &>/dev/null; then
  echo "❌ gh not authenticated. Run: gh auth login"
  exit 1
fi

# ─── Read version from package.json ───────────────────────────────────────

VERSION=$(node -p "require('$DESKTOP_DIR/package.json').version")
TAG="${CUSTOM_TAG:-v${VERSION}}"

echo "╔════════════════════════════════════════════════════════╗"
echo "║       MateClaw Desktop - Publish to GitHub            ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""
echo "  Version:  $VERSION"
echo "  Tag:      $TAG"
echo "  Mode:     ${DRAFT:-release}"
echo "  Proxy:    ${USE_PROXY:+${PROXY_ADDR}}${USE_PROXY:-off}"
echo "  Retry:    $RETRY"
echo ""

# ─── Retry: delete existing release ───────────────────────────────────────

if [ "$RETRY" = true ]; then
  echo "🔄 Checking for existing release $TAG ..."
  if gh release view "$TAG" &>/dev/null; then
    echo "   Found existing release, deleting..."
    gh release delete "$TAG" --yes --cleanup-tag 2>/dev/null || gh release delete "$TAG" --yes 2>/dev/null
    echo "   ✅ Old release deleted."
  else
    echo "   No existing release found, proceeding."
  fi
  echo ""
fi

# ─── Collect release artifacts ─────────────────────────────────────────────

if [ ! -d "$RELEASE_DIR" ]; then
  echo "❌ Release directory not found: $RELEASE_DIR"
  echo "   Run build-all-platforms.sh first."
  exit 1
fi

ARTIFACTS=()

# macOS artifacts
for pattern in "MateClaw*.dmg" "MateClaw*.zip"; do
  while IFS= read -r -d '' f; do
    ARTIFACTS+=("$f")
  done < <(find "$RELEASE_DIR" -maxdepth 1 -name "$pattern" -print0 2>/dev/null)
done

# Windows artifacts (skip the architecture-merged installer — only ship per-arch builds)
while IFS= read -r -d '' f; do
  ARTIFACTS+=("$f")
done < <(find "$RELEASE_DIR" -maxdepth 1 -name "MateClaw*Setup*.exe" \
  ! -name "MateClaw_${VERSION}_Setup.exe" -print0 2>/dev/null)

# Linux artifacts
while IFS= read -r -d '' f; do
  ARTIFACTS+=("$f")
done < <(find "$RELEASE_DIR" -maxdepth 1 -name "MateClaw*.AppImage" -print0 2>/dev/null)

# Auto-update metadata files
for pattern in "latest-mac.yml" "latest.yml" "latest-linux.yml"; do
  if [ -f "$RELEASE_DIR/$pattern" ]; then
    ARTIFACTS+=("$RELEASE_DIR/$pattern")
  fi
done

# blockmap files (for differential updates) — also skip merged installer's blockmap
while IFS= read -r -d '' f; do
  ARTIFACTS+=("$f")
done < <(find "$RELEASE_DIR" -maxdepth 1 -name "*.blockmap" \
  ! -name "MateClaw_${VERSION}_Setup.exe.blockmap" -print0 2>/dev/null)

if [ ${#ARTIFACTS[@]} -eq 0 ]; then
  echo "❌ No release artifacts found in $RELEASE_DIR"
  echo "   Run build-all-platforms.sh first."
  exit 1
fi

echo "  Artifacts to upload:"
TOTAL_SIZE=0
for f in "${ARTIFACTS[@]}"; do
  SIZE_BYTES=$(stat -f%z "$f" 2>/dev/null || stat -c%s "$f" 2>/dev/null || echo 0)
  SIZE_HUMAN=$(du -h "$f" | cut -f1)
  TOTAL_SIZE=$((TOTAL_SIZE + SIZE_BYTES))
  echo "    • $(basename "$f") ($SIZE_HUMAN)"
done
TOTAL_HUMAN=$(echo "$TOTAL_SIZE" | awk '{
  if ($1 >= 1073741824) printf "%.1f GB", $1/1073741824;
  else if ($1 >= 1048576) printf "%.0f MB", $1/1048576;
  else printf "%.0f KB", $1/1024;
}')
echo ""
echo "  Total: $TOTAL_HUMAN"
echo ""

# ─── Resolve release notes (priority chain) ───────────────────────────────
#   1. --notes-file=path                              (explicit override)
#   2. ../docs/{notes-lang}/releases/X.Y.Z.md         (curated, preferred lang)
#   3. ../docs/{other-lang}/releases/X.Y.Z.md         (curated, fallback lang)
#   4. Tag annotation (if multi-line)                 (legacy fallback)
#   5. Tagged commit message body                     (legacy fallback)
#   6. --generate-notes                               (GitHub auto)

NOTES_FILE=$(mktemp)
trap "rm -f '$NOTES_FILE'" EXIT

REPO_ROOT="$(cd "$DESKTOP_DIR/.." && pwd)"
NOTES_LANG_OTHER=$([ "$NOTES_LANG" = "en" ] && echo "zh" || echo "en")
NOTES_PRIMARY="$REPO_ROOT/docs/${NOTES_LANG}/releases/${VERSION}.md"
NOTES_FALLBACK="$REPO_ROOT/docs/${NOTES_LANG_OTHER}/releases/${VERSION}.md"

if [ -n "$NOTES_FILE_OVERRIDE" ]; then
  if [ ! -f "$NOTES_FILE_OVERRIDE" ]; then
    echo "❌ --notes-file path not found: $NOTES_FILE_OVERRIDE"
    exit 1
  fi
  cat "$NOTES_FILE_OVERRIDE" > "$NOTES_FILE"
  echo "📝 Using --notes-file: $NOTES_FILE_OVERRIDE"
elif [ -f "$NOTES_PRIMARY" ]; then
  cat "$NOTES_PRIMARY" > "$NOTES_FILE"
  echo "📝 Using docs/${NOTES_LANG}/releases/${VERSION}.md as release notes."
elif [ -f "$NOTES_FALLBACK" ]; then
  cat "$NOTES_FALLBACK" > "$NOTES_FILE"
  echo "📝 Using docs/${NOTES_LANG_OTHER}/releases/${VERSION}.md as release notes (preferred '$NOTES_LANG' not found)."
else
  # Legacy fallback: tag annotation, then commit body
  TAG_MSG=$(git tag -l --format='%(contents)' "$TAG" 2>/dev/null | sed '/^$/d')
  if [ -n "$TAG_MSG" ] && [ "$(echo "$TAG_MSG" | wc -l)" -gt 2 ]; then
    echo "$TAG_MSG" > "$NOTES_FILE"
    echo "📝 Using tag annotation as release notes."
  else
    COMMIT_MSG=$(git log -1 --format='%B' "$TAG" 2>/dev/null | tail -n +2 | sed '/^$/d')
    if [ -n "$COMMIT_MSG" ]; then
      echo "$COMMIT_MSG" > "$NOTES_FILE"
      echo "📝 Using commit message as release notes."
    else
      echo "⚠️  No release notes found (no docs/X.Y.Z.md, tag annotation, or commit body), using --generate-notes."
    fi
  fi
fi
echo ""

# ─── Rewrite latest.yml so it does not reference the merged installer ─────
#
# electron-builder generates a multi-arch merged Windows installer
# (MateClaw_X.Y.Z_Setup.exe, ~600 MB) alongside the per-arch installers
# (MateClaw_X.Y.Z_x64_Setup.exe, MateClaw_X.Y.Z_arm64_Setup.exe). The
# merged build is too large to be worth uploading, but the default
# latest.yml top-level `path:` and `files[0]` both point at it. If we
# upload latest.yml as-is while skipping the merged exe, electron-updater
# on Windows hits 404 on every check and the whole update path breaks.
#
# Fix: rewrite latest.yml in place so `path` / `sha512` / `files[]` only
# reference assets that are actually being uploaded. Use x64 as the
# default top-level target (arm64 clients still match via files[]).

LATEST_YML="$RELEASE_DIR/latest.yml"
MERGED_EXE_NAME="MateClaw_${VERSION}_Setup.exe"
X64_EXE_NAME="MateClaw_${VERSION}_x64_Setup.exe"
ARM64_EXE_NAME="MateClaw_${VERSION}_arm64_Setup.exe"

if [ -f "$LATEST_YML" ]; then
  echo "🧹 Rewriting latest.yml to drop the merged installer ..."
  (cd "$DESKTOP_DIR" && node -e "
    const fs = require('fs');
    const yaml = require('js-yaml');
    const file = '$LATEST_YML';
    const merged = '$MERGED_EXE_NAME';
    const x64 = '$X64_EXE_NAME';
    const arm64 = '$ARM64_EXE_NAME';
    const doc = yaml.load(fs.readFileSync(file, 'utf8'));
    const before = (doc.files || []).length;
    doc.files = (doc.files || []).filter(f => f.url !== merged);
    const x64Entry = doc.files.find(f => f.url === x64);
    const arm64Entry = doc.files.find(f => f.url === arm64);
    if (!x64Entry || !arm64Entry) {
      console.error('FATAL: per-arch installer entries missing from latest.yml');
      console.error('  files:', doc.files.map(f => f.url));
      process.exit(1);
    }
    doc.path = x64Entry.url;
    doc.sha512 = x64Entry.sha512;
    fs.writeFileSync(file, yaml.dump(doc, { lineWidth: -1 }));
    console.log('   files[] entries: ' + before + ' → ' + doc.files.length);
    console.log('   top-level path:  ' + doc.path);
  ")
  echo ""
fi

# ─── Create GitHub Release ─────────────────────────────────────────────────

echo "🚀 Creating GitHub Release $TAG ..."
echo ""

if [ -s "$NOTES_FILE" ]; then
  gh release create "$TAG" \
    --title "MateClaw $VERSION" \
    --notes-file "$NOTES_FILE" \
    $DRAFT \
    "${ARTIFACTS[@]}"
else
  gh release create "$TAG" \
    --title "MateClaw $VERSION" \
    --generate-notes \
    $DRAFT \
    "${ARTIFACTS[@]}"
fi

echo ""
echo "✅ Release published successfully!"
echo ""
gh release view "$TAG" --json url -q '.url'
