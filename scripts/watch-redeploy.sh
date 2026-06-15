#!/usr/bin/env bash
# Watch Kotlin/XML source and redeploy on save.
# Coalesces a burst of saves (debounce) so editing several files at once
# triggers one rebuild, not one per keystroke/save.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v fswatch >/dev/null 2>&1; then
  echo "✗ fswatch not installed. Run: brew install fswatch" >&2
  exit 1
fi

DEBOUNCE="${DEBOUNCE:-1.5}"  # seconds of quiet time before a build fires
REDEPLOY="$(cd "$(dirname "$0")" && pwd)/redeploy.sh"
# Only react to source files. Kept in a var so the fswatch command line stays
# simple and parser-friendly (no inline-quoted regex next to a while-read).
INCLUDE='\\.(kt|kts|xml)$'

# Invoke redeploy through bash so we don't depend on the file's exec bit.
run_redeploy() { bash "$REDEPLOY" || echo "  (deploy skipped due to error)"; }

echo "▸ Watching android/app/src for .kt/.kts/.xml changes (debounce ${DEBOUNCE}s)"
echo "▸ Ctrl-C to stop. First run on startup:"
run_redeploy

# -r:            watch the whole tree recursively
# -l <sec>:      pool events for this many seconds, then emit once (debounce)
# -i REGEX:      only emit events whose path matches the regex
# -o:            print one line per batch (the event count), so we don't have
#                to parse NUL-delimited event records.
while read -r _count; do
  echo "↻ Change detected at $(date +%H:%M:%S) — rebuilding…"
  run_redeploy
done < <(fswatch -r -l "$DEBOUNCE" -i "$INCLUDE" -o android/app/src)
