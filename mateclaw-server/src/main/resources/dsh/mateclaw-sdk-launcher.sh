#!/bin/sh
runtime_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
exec "$runtime_root/node_modules/.bin/dsh" --patch "$runtime_root/mateclaw-sdk.patch.yml" "$@"
