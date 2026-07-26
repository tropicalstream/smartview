#!/bin/bash
# Rebuild app/src/main/assets/blocklist.txt.gz from StevenBlack unified hosts (MIT).
#
# bash + pipefail, NOT sh: with `set -e` alone a failed curl inside a pipeline
# still exits 0, so a 404 would quietly write a valid 20-byte gzip containing
# zero domains — and the app would log "blocklist loaded" forever while blocking
# nothing. Download to a temp file, assert a floor, and only then move it in.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
OUT="app/src/main/assets/blocklist.txt.gz"
EXTRA="tools/blocklist-extra.txt"
TMP="$(mktemp)"; TMPD="$(mktemp)"
trap 'rm -f "$TMP" "$TMPD"' EXIT

echo "fetching $SRC"
curl -fsSL "$SRC" -o "$TMP"

# 0.0.0.0 <host> lines only; drop comments, localhost aliases and the apex 0.0.0.0
awk '$1=="0.0.0.0" && $2!="0.0.0.0" {print tolower($2)}' "$TMP" \
  | grep -vE '^(localhost|local|broadcasthost)$' | sort -u > "$TMPD"

[ -f "$EXTRA" ] && grep -vE '^\s*(#|$)' "$EXTRA" | tr 'A-Z' 'a-z' >> "$TMPD" || true
sort -u -o "$TMPD" "$TMPD"

N=$(wc -l < "$TMPD" | tr -d ' ')
if [ "$N" -lt 50000 ]; then
  echo "REFUSING: only $N domains — upstream format changed or download truncated" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
gzip -9 -c "$TMPD" > "$OUT"
echo "wrote $OUT — $N domains, $(du -h "$OUT" | cut -f1)"
