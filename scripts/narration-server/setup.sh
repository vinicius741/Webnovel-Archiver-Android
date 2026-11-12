#!/usr/bin/env bash
# Builds and installs the Kokoro narration server for this user (macOS or Linux) and, with --service,
# starts it at login and keeps it running. Re-running rebuilds the server and keeps the model and token.
#
#   scripts/narration-server/setup.sh             install, then print how to start it
#   scripts/narration-server/setup.sh --service   install and run it as a background service
#   scripts/narration-server/setup.sh --uninstall stop and remove the service (keeps the data folder)
#
# Everything lives in one folder: ~/Library/Application Support/WebnovelNarration on macOS,
# ~/.local/share/webnovel-narration on Linux. Needs a Rust toolchain (installed with rustup when
# missing) and, on macOS, the Xcode command line tools. See docs/tts/remote-narration-servers.md.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODEL=kokoro-en-v0_19
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL}.tar.bz2"
# The sherpa-onnx the app ships (android/app/build.gradle), so clips match the phone's.
SHERPA_VERSION=1.12.21
SHERPA_RELEASES="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}"
LABEL=com.webnovelarchiver.narration-server
UNIT=webnovel-narration.service
PORT="${NARRATION_PORT:-8790}"

case "${1:-}" in
  "" | --service | --uninstall) ;;
  *) echo "Unknown option $1. Use --service, --uninstall, or nothing." >&2; exit 2 ;;
esac

# SERVICE_FILE: what starts the server in the background (a launchd agent or a systemd user unit).
case "$(uname -s)" in
  Darwin)
    DATA="$HOME/Library/Application Support/WebnovelNarration"; OS=mac
    SERVICE_FILE="$HOME/Library/LaunchAgents/$LABEL.plist" ;;
  Linux)
    DATA="${XDG_DATA_HOME:-$HOME/.local/share}/webnovel-narration"; OS=linux
    SERVICE_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$UNIT" ;;
  *) echo "Unsupported system: $(uname -s)" >&2; exit 1 ;;
esac

uninstall() {
  if [ "$OS" = mac ]; then
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    rm -f "$SERVICE_FILE"
  else
    systemctl --user disable --now "$UNIT" 2>/dev/null || true
    rm -f "$SERVICE_FILE"
    systemctl --user daemon-reload 2>/dev/null || true
  fi
  echo "Service removed. Data (model, token, server) is still in: $DATA"
}

if [ "${1:-}" = "--uninstall" ]; then uninstall; exit 0; fi

# --- Toolchain ------------------------------------------------------------------------------------
if [ "$OS" = mac ] && ! xcode-select -p >/dev/null 2>&1; then
  echo "Needs the Xcode command line tools (for the linker). Install them with: xcode-select --install" >&2
  exit 1
fi
if [ "$OS" = linux ] && ! command -v cc >/dev/null 2>&1; then
  echo "Needs a C linker (cc). Install gcc (Fedora: sudo dnf install gcc; Debian/Ubuntu: sudo apt install build-essential)." >&2
  exit 1
fi
if ! command -v cargo >/dev/null 2>&1 && [ -x "$HOME/.cargo/bin/cargo" ]; then
  export PATH="$HOME/.cargo/bin:$PATH"
fi
if ! command -v cargo >/dev/null 2>&1; then
  echo "Installing Rust with rustup (into ~/.rustup and ~/.cargo)..."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path
  export PATH="$HOME/.cargo/bin:$PATH"
fi

# --- sherpa-onnx shared libraries -------------------------------------------------------------------
case "$OS-$(uname -m)" in
  mac-*) ARCHIVE="sherpa-onnx-v${SHERPA_VERSION}-osx-universal2-shared" ;;
  linux-x86_64) ARCHIVE="sherpa-onnx-v${SHERPA_VERSION}-linux-x64-shared" ;;
  linux-aarch64 | linux-arm64) ARCHIVE="sherpa-onnx-v${SHERPA_VERSION}-linux-aarch64-shared-cpu" ;;
  *) echo "No sherpa-onnx build for $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
mkdir -p "$DATA/models" "$DATA/lib" "$DATA/bin"
if [ "$(cat "$DATA/lib/VERSION" 2>/dev/null)" != "$ARCHIVE" ]; then
  echo "Downloading sherpa-onnx $SHERPA_VERSION..."
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  curl -fL --progress-bar -o "$WORK/sherpa.tar.bz2" "$SHERPA_RELEASES/$ARCHIVE.tar.bz2"
  tar -xjf "$WORK/sherpa.tar.bz2" -C "$WORK" "$ARCHIVE/lib"
  rm -rf "$DATA/lib"
  mkdir -p "$DATA/lib"
  # Only the C API and ONNX Runtime; the rest of the release is tools and other languages.
  cp -P "$WORK/$ARCHIVE/lib/"*sherpa-onnx-c-api* "$WORK/$ARCHIVE/lib/"*onnxruntime* "$DATA/lib/"
  echo "$ARCHIVE" > "$DATA/lib/VERSION"
fi

# --- The server ---------------------------------------------------------------------------------------
echo "Building the server (the first build takes a minute)..."
SHERPA_ONNX_LIB_DIR="$DATA/lib" CARGO_TARGET_DIR="$DATA/build" \
  cargo build --release --locked --quiet --manifest-path "$HERE/Cargo.toml"
install -m 755 "$DATA/build/release/narration-server" "$DATA/bin/narration-server.new"
mv -f "$DATA/bin/narration-server.new" "$DATA/bin/narration-server"
BIN="$DATA/bin/narration-server"
# The Python server this replaces (protocol 1 is unchanged, so the app keeps working). A service
# installed for it keeps running it until --service rewrites that service, so it stays until then.
if [ "${1:-}" != "--service" ] && grep -q narration_server.py "$SERVICE_FILE" 2>/dev/null; then
  echo "The background service still runs the old Python server. Run this script with --service to switch it."
else
  rm -rf "$DATA/venv" "$DATA/narration_server.py" "$DATA/requirements.txt"
fi

if [ ! -f "$DATA/models/$MODEL/model.onnx" ]; then
  echo "Downloading $MODEL (about 330 MB)..."
  curl -fL --progress-bar -o "$DATA/models/$MODEL.tar.bz2" "$MODEL_URL"
  tar -xjf "$DATA/models/$MODEL.tar.bz2" -C "$DATA/models"
  rm -f "$DATA/models/$MODEL.tar.bz2"
fi

if [ ! -s "$DATA/token" ]; then
  # 12 characters from an alphabet without look-alikes (no 0/O, 1/I/L), grouped for typing on a phone.
  # Filter a fixed amount of randomness (no endless tr | head pipe, which pipefail would fail on).
  raw=""
  while [ "${#raw}" -lt 12 ]; do
    raw+="$(head -c 512 /dev/urandom | LC_ALL=C tr -dc '23456789abcdefghjkmnpqrstuvwxyz')"
  done
  (umask 077; echo "${raw:0:4}-${raw:4:4}-${raw:8:4}" > "$DATA/token")
fi

RUN=("$BIN" --model-dir "$DATA/models/$MODEL" --token-file "$DATA/token" --port "$PORT")

if [ "${1:-}" = "--service" ]; then
  if [ "$OS" = mac ]; then
    PLIST="$SERVICE_FILE"
    mkdir -p "$(dirname "$PLIST")"
    # The server watches memory itself (see the docs); macOS has no per-service memory limit to add
    # as a backstop. Interactive keeps the work on the performance cores (as a background process
    # it is held to the efficiency cores: 0.22x real time against 5.1x, measured on an M1 Pro). Nice
    # lets the apps in use go first when the processor is contended, at no cost otherwise (measured).
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
      echo '  <key>Nice</key><integer>10</integer>'
      echo "  <key>StandardOutPath</key><string>$DATA/server.log</string>"
      echo "  <key>StandardErrorPath</key><string>$DATA/server.log</string>"
      echo '</dict></plist>'
    } > "$PLIST"
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
    # bootout returns before the old instance is gone; bootstrapping too early fails with error 5.
    for _ in $(seq 1 50); do
      launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break
      sleep 0.2
    done
    launchctl bootstrap "gui/$(id -u)" "$PLIST"
    echo "Service installed: it starts at login and restarts if it stops. Log: $DATA/server.log"
  else
    mkdir -p "$(dirname "$SERVICE_FILE")"
    EXEC=""
    for arg in "${RUN[@]}"; do EXEC+="\"$arg\" "; done
    # The server watches memory itself (see the docs). These are the kernel's backstops: if memory
    # still runs out, the OOM killer takes this server before anything else (it restarts in 5 s),
    # and it can never grow past 35% of RAM. Nice keeps games and the desktop responsive.
    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Webnovel Archiver Kokoro narration server
After=network-online.target

[Service]
ExecStart=$EXEC
Restart=always
RestartSec=5
Nice=10
OOMScoreAdjust=500
MemoryMax=35%

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
"$BIN" --addresses --port "$PORT"
echo "Status (memory, model, work): curl -H \"Authorization: Bearer \$(cat '$DATA/token')\" http://localhost:$PORT/v1/status"
