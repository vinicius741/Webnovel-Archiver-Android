#!/usr/bin/env bash
# Installs the Kokoro narration server for this user (macOS or Linux) and, with --service, starts it
# at login and keeps it running. Re-running updates the server script and keeps the model and token.
#
#   scripts/narration-server/setup.sh             install, then print how to start it
#   scripts/narration-server/setup.sh --service   install and run it as a background service
#   scripts/narration-server/setup.sh --uninstall stop and remove the service (keeps the data folder)
#
# Everything lives in one folder: ~/Library/Application Support/WebnovelNarration on macOS,
# ~/.local/share/webnovel-narration on Linux. See docs/tts/remote-narration-servers.md.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODEL=kokoro-en-v0_19
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL}.tar.bz2"
LABEL=com.webnovelarchiver.narration-server
UNIT=webnovel-narration.service
PORT="${NARRATION_PORT:-8790}"

case "$(uname -s)" in
  Darwin) DATA="$HOME/Library/Application Support/WebnovelNarration"; OS=mac ;;
  Linux) DATA="${XDG_DATA_HOME:-$HOME/.local/share}/webnovel-narration"; OS=linux ;;
  *) echo "Unsupported system: $(uname -s)" >&2; exit 1 ;;
esac

uninstall() {
  if [ "$OS" = mac ]; then
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
  else
    systemctl --user disable --now "$UNIT" 2>/dev/null || true
    rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$UNIT"
    systemctl --user daemon-reload 2>/dev/null || true
  fi
  echo "Service removed. Data (model, token, venv) is still in: $DATA"
}

if [ "${1:-}" = "--uninstall" ]; then uninstall; exit 0; fi

# sherpa-onnx publishes wheels for these Python versions; newer ones may have none.
PYTHON=""
for candidate in python3.12 python3.11 python3.13 python3.10; do
  if command -v "$candidate" >/dev/null 2>&1; then PYTHON="$(command -v "$candidate")"; break; fi
done
if [ -z "$PYTHON" ]; then
  echo "Needs Python 3.10-3.13 (sherpa-onnx has no wheels for other versions)." >&2
  exit 1
fi

mkdir -p "$DATA/models"
cp "$HERE/narration_server.py" "$HERE/requirements.txt" "$DATA/"

if [ ! -x "$DATA/venv/bin/python" ]; then
  echo "Creating the Python environment with $PYTHON..."
  "$PYTHON" -m venv "$DATA/venv"
fi
"$DATA/venv/bin/python" -m pip install --quiet --disable-pip-version-check -r "$DATA/requirements.txt"

if [ ! -f "$DATA/models/$MODEL/model.onnx" ]; then
  echo "Downloading $MODEL (about 330 MB)..."
  curl -fL --progress-bar -o "$DATA/models/$MODEL.tar.bz2" "$MODEL_URL"
  tar -xjf "$DATA/models/$MODEL.tar.bz2" -C "$DATA/models"
  rm -f "$DATA/models/$MODEL.tar.bz2"
fi

if [ ! -s "$DATA/token" ]; then
  # 12 characters from an alphabet without look-alikes (no 0/O, 1/I/L), grouped for typing on a phone.
  "$DATA/venv/bin/python" -c '
import secrets
alphabet = "23456789abcdefghjkmnpqrstuvwxyz"
raw = "".join(secrets.choice(alphabet) for _ in range(12))
print("-".join(raw[i:i + 4] for i in range(0, 12, 4)))
' > "$DATA/token"
  chmod 600 "$DATA/token"
fi

RUN=("$DATA/venv/bin/python" "$DATA/narration_server.py" --model-dir "$DATA/models/$MODEL" --token-file "$DATA/token" --port "$PORT")

if [ "${1:-}" = "--service" ]; then
  if [ "$OS" = mac ]; then
    PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
    mkdir -p "$(dirname "$PLIST")"
    {
      echo '<?xml version="1.0" encoding="UTF-8"?>'
      echo '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
      echo '<plist version="1.0"><dict>'
      echo "  <key>Label</key><string>$LABEL</string>"
      echo '  <key>ProgramArguments</key><array>'
      for arg in "${RUN[@]}"; do echo "    <string>$arg</string>"; done
      echo '  </array>'
      echo '  <key>RunAtLoad</key><true/>'
      echo '  <key>KeepAlive</key><true/>'
      echo '  <key>ProcessType</key><string>Interactive</string>'
      echo "  <key>StandardOutPath</key><string>$DATA/server.log</string>"
      echo "  <key>StandardErrorPath</key><string>$DATA/server.log</string>"
      echo '</dict></plist>'
    } > "$PLIST"
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    launchctl bootstrap "gui/$(id -u)" "$PLIST"
    echo "Service installed: it starts at login and restarts if it stops. Log: $DATA/server.log"
  else
    UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
    mkdir -p "$UNIT_DIR"
    EXEC=""
    for arg in "${RUN[@]}"; do EXEC+="\"$arg\" "; done
    cat > "$UNIT_DIR/$UNIT" <<EOF
[Unit]
Description=Webnovel Archiver Kokoro narration server
After=network-online.target

[Service]
ExecStart=$EXEC
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
    systemctl --user daemon-reload
    systemctl --user enable --now "$UNIT"
    systemctl --user restart "$UNIT"
    echo "Service installed: it starts when you log in. Log: journalctl --user -u $UNIT"
    echo "To keep it running while logged out: loginctl enable-linger $USER"
  fi
else
  echo "Start it with:"
  printf '  %q' "${RUN[@]}"; echo
  echo "Or run this script with --service to keep it running in the background."
fi

echo
echo "Add this computer in the app (Audio generation -> Generate on -> Add a computer):"
echo "  Token:   $(cat "$DATA/token")"
echo "  Port:    $PORT"
"$DATA/venv/bin/python" - "$PORT" <<'EOF'
import socket, subprocess, sys
port = sys.argv[1]
found = set()
try:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.connect(("192.0.2.1", 9))
    found.add(("LAN", probe.getsockname()[0]))
except OSError:
    pass
try:
    out = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=3).stdout
    found.update(("Tailscale", line.strip()) for line in out.splitlines() if line.strip())
except (OSError, subprocess.SubprocessError):
    pass
for kind, address in sorted(found):
    print(f"  Address ({kind}): {address}:{port}")
EOF
