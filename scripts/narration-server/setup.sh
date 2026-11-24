#!/usr/bin/env bash
# Builds and installs the narration server for this user (macOS or Linux) and, with --service,
# starts it at login and keeps it running. Re-running rebuilds the server, adds models it lacks, and
# keeps the models and token it has. Models: Kokoro v0.19 and v1.0 (sherpa-onnx) and Phonon 31M.
# Where the processor allows it, it also sets up Kokoro v1.0's fast path (OpenVINO, see below).
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
SHERPA_MODELS="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
MODEL_V1=kokoro-multi-lang-v1_0
# Phonon 31M and its voices, at the revisions and SHA-256 sums the app pins
# (android/app/src/main/java/.../tts/kokoro/BuiltInModels.kt).
PHONON=phonon-31m-en
PHONON_REPO="https://huggingface.co/gradium/phonon-31m-en/resolve/92741c5b07d085ff840e26fea8ce43110b87852f"
PHONON_VOICES_REPO="https://huggingface.co/gradium/phonon-voices/resolve/da6c9ec0d47be8df5c87c730a3aa97187f3229c3/voices"
PHONON_FILES="config.json:b5d0f88353638370d94035b7d73d7d6577c0554d78f8d7ecfd40490aae39ae85
tokenizer.json:691a00068fa7b989f2f3e5ae5daa2d1a5bb319f2d5360deafcfb19a8bb6e7936
model.safetensors:8a04cf833cfe69b67c2ec968b2d31244655b9accc84ade9c0ebcc176f2074607"
# Every English voice in the catalog, as the app lists them (PHONON_VOICES in BuiltInModels.kt).
# Name (the catalog name without spaces):catalog id:SHA-256.
PHONON_VOICES="Harper:4SZHfMpw-p46Ywgs:6b3da6e2d796a20c51af892c482bd6509fb88267a221aff83c870843dee2a123
Everly:vjFyp2BcCyTlUmYb:958ea94da14f46dd48c7f64adac11ed2ad2aa45f7ccd165dfebab5272664402e
Bryony:gfP44RlHwr2kEwk7:bcc207a73b7885e45b49ab633d3377a10158292e3a7cef9cd6764ad107f1cb9b
Hadley:bXwrkHhURL1VFJdG:a879938aa9a2df0a8220bdadef88137929f71e887bd91e7db205bd5fa11661d2
Juniper:NIWIOv5vTi5eRMj8:444378eb06b7c492b86e8fc7d13e4db1a3c0cf3e68891a960bf6f0a41a13ef35
Zoey:NbpkqMVS3CJeq2j8:97c7f8c253413574406ab80dd23f49c3ebb2517163b00a9bf86b9c4cf43e7e23
Delia:nGzMHMPAWGaJFHOo:a0c3dcb91b2247eca56536be9ae8e8becc1c807b1136c5fe101552d6a155b24a
Easton:LMDIYE0Oa377Qh6Z:45c582673cb20edb04ddcca2b5a8865ddfb0a82599efadb269021e87d20fbdd4
Micah:40QrMVgAxFiUnP9f:0de1098d1051686a138d068e83ff09deb5053328bc74ec9541a1fdb90bfc24da
Miles:d9XCyhjo4JhkHF1d:eab20e4f159b48cfbc01b1885804e24f681f80e7a64f909182763a4e3361bee8
Preston:qIxlZ7Ei8KvWlyQH:6e04b84d3ae8751b22bca0a4f63936f9cb648f4f2182b2d0788071ea1c0b68a1
Russell:_6Aslh2DxfmnRLmP:beae9e4030efce0a935fcb0f807dc0784d598bcaa87ad4aacb047436b1eaa411
Trevor:0zLApsZfVLLNaEGl:dc1341c8937af5534e6bbcc5c0fc5e7c10312229b59061228074f93683fe2261
Elodie-Rose:y9bQqIDnyxwqK01t:21eade9d83b2e66b9364e1b6cd999a3a6704c000a71f27f34e05ace5cdfd58bb
Freya:GgfEkEJtxZR7gnpy:eb3cd47418349e618408c16bea7153988b3ad04b41a6033039dd474a9a4dcd23
Imogen:gDT1nz7Ie36ZhL-C:1980ab3c35fa98a25b5f3b5e643fd852e14ca8a12a4a26b06aaf1abf2a8c68b1
Lottie:m9MxpY6IUGXtWp76:a906ebf17e43fded12b3d6bd077abb2df33bc86eff51c1beeb82e5d416527db3
Maeve:6PWnV0Nq4wu7RVBT:56494ee67d533ad227ff0dfd0b1c13ff5aa91f1ad6e596e66c2986ba3005e844
Pippa:uem82D50GRv2Dwma:d41a8f01d6e33450f1ce062d332914da10beee99e585dab531750706f187c48a
Alfie:X6NceqF285Gr04gb:4de75497fff3030168e8aa6dca23ffeb332b1bc8c25d0bf3507ceadac953ae9c
Edwin:zUCxzrOrugTSis03:ff1d0735b8b45710462b1d1f846da040f970c6eb62c7c2f36dbfb8e5bde74f1e
Hugh:YyKcxPG7iIgS3jsK:3169d74b47a54d1eb15e4c243f56393abe9fe90c7e1c308ecf96ee67618b152a
Spencer:JOW0vchyswkQUMDS:cc9ac1107b0ffce282374a19beb7bfa95847724e6b7afbac991b4ae4277d5e6d
Teddy:Zn7g6NxLqXlJb7Qi:baea0ea9506f3237a5ebfc61afb7c72dbd189c5603b199ef42fbb00dbda379e0
Toby:dME3IWyZBvmh1n1q:09ab315c7f4b72b4d71e66cb756184cedfb1f14d222d096e115bf95bfe7b29dc
Azgorath:dGyGDKo2osj7h3s7:83900e020c14946426614c6553c5a138ba6277e1aaa69832223bd0f0f8b73893
CaptainSilasBlackwater:xPDpsV3F592srkjA:a09f615b0084980ebc4076d71550bf73ccd8d4df076bbbee4a9beb523a1379d9
FairyGodmother:QXNr0SvkukLB4wPq:f390c09f5e279c0f2171995a534b92f5ca5c83443906879da68fc27445f89d89
FennickTheYoungElf:cXoIXJ2llS8S0WOW:a05944e4c69d61d10d66f689175ba0b081930df2d77b22f04d00ac40d3d69ecb
GrubTheOgre:cYtsRSxV5q9IMehn:b30fa847686ec1eb6be8c5bea4b6a5c5076ea66c3ff053cf98210a155d8aad1e
MasterAldric:gSgG5Tor0GPEnqzC:a83430cc022b85c83753f859f9e44f406651646dab994fced8ef7a9a88005971
MorwennaOfTheDeep:nOykTmcH9ydsGGIc:2ff2db91e70648623df8d42c0dcd7c336b2f14cb4362d602a20f292a4d862154
OldMortlake:lX3LavMNdlIbgEHH:d3f9429205e5906dcd3c78edaf7c9d5148c7e389e34c82c86f72f154adb5d923
SirBramwell:y2eiKxtQo2B67deU:b3cea6789aeb58822af16ba08c4e5105d7febb840dcb67b47f426c19d1a8f209
TheSnowQueen:NIWoEurJHMJjyUTe:bc8a8fcb95bb4f32d2d4376aa69d81b186b3ba2f8e9b6cb6a403b2b194590e85
TheThingUnderTheStairs:GXaSqMEmOg12OIYb:bfa37bac98aefbb9de80f792c47d01a0f9e3b94e73776169040c249c8b722759
VyrmathraxTheAncient:xO8xQo3BrB8FuI6J:63ab4b15427844a804480a1603774262e8903dee33cb279e2749860dbfaedd10"
# The sherpa-onnx the app ships (android/app/build.gradle), so clips match the phone's.
SHERPA_VERSION=1.12.21
# Kokoro v1.0's fast path: OpenVINO's libraries, and the Python packages split_kokoro.py needs.
OPENVINO_VERSION=2026.4.1
ONNX_VERSION=1.23.2
NUMPY_VERSION=2.5.5
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
  trap 'rm -rf "${WORK:-}" "${FAST_WORK:-}"' EXIT
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

# A sherpa-onnx Kokoro export, unpacked into models/<name>.
sherpa_model() {
  local name="$1" size="$2"
  if [ ! -f "$DATA/models/$name/model.onnx" ]; then
    echo "Downloading $name (about $size)..."
    curl -fL --progress-bar -o "$DATA/models/$name.tar.bz2" "$SHERPA_MODELS/$name.tar.bz2"
    tar -xjf "$DATA/models/$name.tar.bz2" -C "$DATA/models"
    rm -f "$DATA/models/$name.tar.bz2"
  fi
}
sherpa_model "$MODEL" "330 MB"
sherpa_model "$MODEL_V1" "350 MB"

# --- Kokoro v1.0's fast path (optional) -------------------------------------------------------------
# Where the processor has a fast reduced precision (src/fast.rs: bf16 with AVX-512 BF16 on x86-64,
# f16 on Apple silicon), the server runs Kokoro v1.0's generator through OpenVINO, 30 to 45%
# faster. That needs OpenVINO's libraries in lib/openvino and the model cut in three
# (split_kokoro.py) in the model's fast/ folder, about 420 MB together. Both come from Python
# packages, installed with uv into a temporary folder (uv itself too, when the machine has none).
# If a step fails, the server keeps the normal path.
fast_path() {
  local model="$DATA/models/$MODEL_V1" libs="$DATA/lib/openvino" stamp uv site link
  stamp="$(sed -n 's/^FORMAT = "\(.*\)"$/\1/p' "$HERE/split_kokoro.py") $FAST_PATH $(wc -c < "$model/model.onnx" | tr -d ' ')"
  if [ "$(cat "$libs/VERSION" 2>/dev/null)" = "$OPENVINO_VERSION" ] && [ "$(cat "$model/fast/format" 2>/dev/null)" = "$stamp" ]; then
    return 0
  fi
  echo "Setting up Kokoro v1.0's fast path (OpenVINO $OPENVINO_VERSION, about 420 MB)..."
  FAST_WORK="$(mktemp -d)"
  trap 'rm -rf "${WORK:-}" "${FAST_WORK:-}"' EXIT
  uv="$(command -v uv || true)"
  if [ -z "$uv" ]; then
    # A private uv, with its cache and its Python, all gone when this script ends.
    curl -LsSf https://astral.sh/uv/install.sh | env UV_UNMANAGED_INSTALL="$FAST_WORK/uv" sh >/dev/null 2>&1 || return 1
    uv="$FAST_WORK/uv/uv"
    export UV_CACHE_DIR="$FAST_WORK/cache" UV_PYTHON_INSTALL_DIR="$FAST_WORK/python"
  fi
  "$uv" venv --quiet --python 3.12 "$FAST_WORK/venv" || return 1
  "$uv" pip install --quiet --python "$FAST_WORK/venv/bin/python" \
    "openvino==$OPENVINO_VERSION" "onnx==$ONNX_VERSION" "numpy==$NUMPY_VERSION" || return 1
  # Only the C API, the runtime, its CPU plugin, the ONNX reader and the threading library.
  site="$(echo "$FAST_WORK"/venv/lib/python3.12/site-packages/openvino/libs)"
  rm -rf "$libs.new"
  mkdir -p "$libs.new" || return 1
  cp -P "$site"/libopenvino.* "$site"/libopenvino_c.* "$site"/libopenvino_*cpu_plugin* \
    "$site"/libopenvino_onnx_frontend* "$site"/libtbb* "$libs.new/" || return 1
  cp -P "$site"/libhwloc* "$libs.new/" 2>/dev/null || true
  # The name the server opens, whatever version suffix the release carries.
  if [ "$OS" = mac ]; then link=libopenvino_c.dylib; else link=libopenvino_c.so; fi
  (cd "$libs.new" && ln -sf "$(ls libopenvino_c.* | head -1)" "$link") || return 1
  rm -rf "$libs"
  mv "$libs.new" "$libs" || return 1
  "$FAST_WORK/venv/bin/python" "$HERE/split_kokoro.py" "$model/model.onnx" "$model/fast" "$FAST_PATH" || return 1
  echo "$OPENVINO_VERSION" > "$libs/VERSION"
}
# The precision the trunk is split for, as fast::layout picks it; empty where there is no fast path.
FAST_PATH=""
case "$OS-$(uname -m)" in
  linux-x86_64) if grep -qw avx512_bf16 /proc/cpuinfo 2>/dev/null; then FAST_PATH=bf16; fi ;;
  mac-arm64) FAST_PATH=f16 ;;
esac
if [ -n "$FAST_PATH" ] && ! fast_path; then
  echo "Could not set up Kokoro v1.0's fast path; the server runs it the normal way. Run this script again to retry."
fi

# Phonon: a ptts checkpoint folder, voices named as the app asks for them (voices/<Name>.safetensors).
# Each file is checked against its pinned SHA-256; a file that does not match is fetched again, and
# goes in under a temporary name, so an interrupted download is never taken for a complete one.
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
fetch() {
  local url="$1" target="$2" sum="$3"
  [ -f "$target" ] && [ "$(sha256 "$target")" = "$sum" ] && return 0
  curl -fL --progress-bar -o "$target.part" "$url"
  if [ "$(sha256 "$target.part")" != "$sum" ]; then
    rm -f "$target.part"
    echo "The download of $(basename "$target") was damaged. Run setup.sh again." >&2
    exit 1
  fi
  mv -f "$target.part" "$target"
}
mkdir -p "$DATA/models/$PHONON/voices"
if [ ! -s "$DATA/models/$PHONON/model.safetensors" ]; then echo "Downloading $PHONON (about 100 MB)..."; fi
for entry in $PHONON_FILES; do
  fetch "$PHONON_REPO/${entry%%:*}" "$DATA/models/$PHONON/${entry%%:*}" "${entry#*:}"
done
for entry in $PHONON_VOICES; do
  name="${entry%%:*}"; rest="${entry#*:}"
  fetch "$PHONON_VOICES_REPO/${rest%%:*}.safetensors" "$DATA/models/$PHONON/voices/$name.safetensors" "${rest#*:}"
done

if [ ! -s "$DATA/token" ]; then
  # 12 characters from an alphabet without look-alikes (no 0/O, 1/I/L), grouped for typing on a phone.
  # Filter a fixed amount of randomness (no endless tr | head pipe, which pipefail would fail on).
  raw=""
  while [ "${#raw}" -lt 12 ]; do
    raw+="$(head -c 512 /dev/urandom | LC_ALL=C tr -dc '23456789abcdefghjkmnpqrstuvwxyz')"
  done
  (umask 077; echo "${raw:0:4}-${raw:4:4}-${raw:8:4}" > "$DATA/token")
fi

RUN=("$BIN" --models-dir "$DATA/models" --token-file "$DATA/token" --port "$PORT")

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
    # still runs out, the OOM killer takes a model's worker process first (each raises its own score
    # to 1000), and OOMPolicy=continue keeps the server answering when it does (the default would
    # stop the whole service); the server itself scores 500 and restarts in 5 s. It can never grow
    # past 35% of RAM. Nice keeps games and the desktop responsive.
    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Webnovel Archiver narration server
After=network-online.target

[Service]
ExecStart=$EXEC
Restart=always
RestartSec=5
Nice=10
OOMScoreAdjust=500
OOMPolicy=continue
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
