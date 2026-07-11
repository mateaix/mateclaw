#!/usr/bin/env bash
# Helper for the obsidian-vault wizard template — lists recently-modified
# .md files in a vault. Copied into the new skill's workspace at
# ${workspace_root}/${slug}/scripts/helper.sh by SkillBundleMaterializer.

set -euo pipefail

VAULT="${1:-}"
if [[ -z "$VAULT" ]]; then
    echo "usage: helper.sh <vault-path>"
    echo "lists *.md files under the vault, sorted by mtime (newest first)"
    exit 1
fi

if [[ ! -d "$VAULT" ]]; then
    echo "vault not found: $VAULT" >&2
    exit 1
fi

find "$VAULT" -type f -name '*.md' -not -path '*/.*' \
    -exec stat -f '%m %N' {} + 2>/dev/null \
    | sort -rn \
    | head -20 \
    | awk '{ $1=""; sub(/^ /, ""); print }'
