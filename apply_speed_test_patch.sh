#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_FILE="$SCRIPT_DIR/patches/speed-test.patch"

if [[ ! -f "$PATCH_FILE" ]]; then
  echo "Patch file not found: $PATCH_FILE" >&2
  exit 1
fi

cd "$SCRIPT_DIR"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "This script must be run inside the v2rayNG git checkout." >&2
  exit 1
fi

git apply --check "$PATCH_FILE" 2>/dev/null || {
  echo "Patch does not apply cleanly; trying a 3-way apply..." >&2
}

git apply --3way "$PATCH_FILE"

echo "Speed test patch applied."
