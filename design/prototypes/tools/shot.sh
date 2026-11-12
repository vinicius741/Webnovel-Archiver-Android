#!/usr/bin/env bash
# Screenshot a prototype page headlessly and print JS console errors.
# Usage: tools/shot.sh <url-path e.g. v1-refined/index.html#/library> <out.png> [width=412] [height=915]
# Fold inner screen: width 884 height 1000. Server must be running on :8737.
set -euo pipefail
CHROME=$(ls -d ~/.cache/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell 2>/dev/null | tail -1)
[ -x "$CHROME" ] || CHROME=$(ls -d ~/.cache/ms-playwright/chromium_headless_shell-*/*/chrome-headless-shell | tail -1)
URL="http://127.0.0.1:8737/$1"; OUT="$2"; W="${3:-412}"; H="${4:-915}"
LOG=$(mktemp)
"$CHROME" --headless --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size="$W,$H" --virtual-time-budget=4000 --enable-logging=stderr --v=0 \
  --screenshot="$OUT" "$URL" 2>"$LOG" >/dev/null || true
grep -E "CONSOLE|Uncaught|ERROR:.*(js|script)" "$LOG" | grep -vE "dbus|gpu|viz|Fontconfig|sandbox" | head -20 || true
rm -f "$LOG"
echo "saved $OUT"
