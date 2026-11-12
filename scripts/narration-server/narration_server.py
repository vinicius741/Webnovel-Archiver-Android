#!/usr/bin/env python3
"""Kokoro narration server: generates chapter audio for the Webnovel Archiver app over LAN or Tailscale.

It runs the same model the app runs on the phone (sherpa-onnx, kokoro-en-v0_19), so audio made here is
interchangeable with audio made on the phone. Several model copies run in parallel worker processes;
the app keeps that many sentences in flight.

Protocol (version 1), every request needs `Authorization: Bearer <token>`:
  GET  /v1/info        -> JSON {protocol, model, sampleRate, workers, threadsPerWorker, name}
  POST /v1/synthesize  -> body JSON {"text": str, "speakerId": int}; 200 audio/wav (mono 16-bit PCM)
Errors are JSON {"error": str} with 400 (bad request), 401 (bad token) or 500 (generation failed).

See docs/tts/remote-narration-servers.md.
"""

import argparse
import hmac
import json
import os
import platform
import socket
import struct
import subprocess
import sys
import threading
import time
from concurrent.futures import ProcessPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL = 1
MODEL_NAME = "kokoro-en-v0_19"
DEFAULT_PORT = 8790
MAX_BODY_BYTES = 64 * 1024
MAX_TEXT_CHARS = 5000
SPEAKERS = 11

_tts = None


def _load_worker(model_dir, threads):
    """Runs once in each worker process: loads its own model copy."""
    global _tts
    import sherpa_onnx

    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=os.path.join(model_dir, "model.onnx"),
                voices=os.path.join(model_dir, "voices.bin"),
                tokens=os.path.join(model_dir, "tokens.txt"),
                data_dir=os.path.join(model_dir, "espeak-ng-data"),
            ),
            num_threads=threads,
            debug=False,
            provider="cpu",
        ),
    )
    _tts = sherpa_onnx.OfflineTts(config)
    # Warm up, so the first real sentence is not slow.
    _tts.generate("The bell rang once, and the hall fell silent.", sid=0, speed=1.0)


def _generate(text, speaker_id):
    """Speaks [text] at 1x, like the phone; returns (sample_rate, 16-bit little-endian PCM)."""
    import numpy as np

    audio = _tts.generate(text, sid=speaker_id, speed=1.0)
    samples = np.asarray(audio.samples, dtype=np.float32)
    # Same conversion as the app's TtsWavWriter: clamp, scale by 32767, round half up.
    pcm = np.floor(np.clip(samples, -1.0, 1.0) * 32767.0 + 0.5).astype("<i2").tobytes()
    return audio.sample_rate, pcm


def wav_bytes(sample_rate, pcm):
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, sample_rate, sample_rate * 2, 2, 16)
    header += b"data" + struct.pack("<I", len(pcm))
    return header + pcm


class KeepAwake:
    """While requests arrive, keeps a Mac from idle-sleeping mid-batch (closing the lid still sleeps it)."""

    def __init__(self):
        self.enabled = platform.system() == "Darwin"
        self.until = 0.0
        self.lock = threading.Lock()

    def touch(self):
        if not self.enabled:
            return
        with self.lock:
            now = time.monotonic()
            if now < self.until - 60:
                return
            self.until = now + 120
        try:
            subprocess.Popen(["caffeinate", "-i", "-t", "120"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            self.enabled = False


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, pool, token, info):
        super().__init__(address, Handler)
        self.pool = pool
        self.token = token
        self.info = info
        self.keep_awake = KeepAwake()


class Handler(BaseHTTPRequestHandler):
    server_version = "WebnovelNarration/1"

    def log_message(self, fmt, *args):
        if self.server.info.get("verbose"):
            super().log_message(fmt, *args)

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        # Case-insensitive: tokens are typed by hand on a phone keyboard that may capitalize them.
        header = self.headers.get("Authorization", "").lower()
        expected = ("Bearer " + self.server.token).lower()
        if hmac.compare_digest(header.encode(), expected.encode()):
            return True
        self._json(401, {"error": "Wrong or missing token"})
        return False

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True})
        if not self._authorized():
            return
        if self.path != "/v1/info":
            return self._json(404, {"error": "Not found"})
        public = {key: value for key, value in self.server.info.items() if key != "verbose"}
        self._json(200, public)

    def do_POST(self):
        if not self._authorized():
            return
        if self.path != "/v1/synthesize":
            return self._json(404, {"error": "Not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length <= 0 or length > MAX_BODY_BYTES:
            return self._json(400, {"error": "Missing or oversized body"})
        try:
            request = json.loads(self.rfile.read(length))
            text = request["text"]
            speaker = int(request["speakerId"])
        except (ValueError, KeyError, TypeError):
            return self._json(400, {"error": "Expected JSON {text, speakerId}"})
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
            return self._json(400, {"error": "Text is empty or too long"})
        if not 0 <= speaker < SPEAKERS:
            return self._json(400, {"error": f"No speaker {speaker}"})
        self.server.keep_awake.touch()
        started = time.monotonic()
        try:
            sample_rate, pcm = self.server.pool.submit(_generate, text, speaker).result()
        except Exception as error:  # A worker failure must not take the server down.
            return self._json(500, {"error": f"Generation failed: {error}"})
        body = wav_bytes(sample_rate, pcm)
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Generate-Ms", str(int((time.monotonic() - started) * 1000)))
        self.end_headers()
        self.wfile.write(body)


def default_layout():
    """About one thread per performance core in total; measured best as 4 copies x 2 threads on 8-10 cores."""
    cores = os.cpu_count() or 4
    physical = max(2, cores // 2) if platform.system() == "Linux" else cores
    if physical >= 8:
        return 4, 2
    if physical >= 4:
        return 2, 2
    return 1, max(1, physical)


def addresses():
    """Best-effort list of this machine's IPv4 addresses, to print for the app."""
    found = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("192.0.2.1", 9))  # No packet is sent; this only picks the outgoing interface.
        found.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        tailscale = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=3)
        found.update(line.strip() for line in tailscale.stdout.splitlines() if line.strip())
    except (OSError, subprocess.SubprocessError):
        pass
    return sorted(address for address in found if not address.startswith("127."))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    workers, threads = default_layout()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", default=os.path.join(here, "models", MODEL_NAME))
    parser.add_argument("--token-file", default=os.path.join(here, "token"))
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--workers", type=int, default=workers, help="model copies generating at once")
    parser.add_argument("--threads", type=int, default=threads, help="threads per model copy")
    parser.add_argument("--name", default=socket.gethostname().split(".")[0])
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if not os.path.isfile(os.path.join(args.model_dir, "model.onnx")):
        sys.exit(f"Model not found in {args.model_dir}. Run setup.sh first.")
    try:
        with open(args.token_file) as file:
            token = file.read().strip()
    except OSError:
        sys.exit(f"Token file {args.token_file} not found. Run setup.sh first.")
    if len(token) < 8:
        sys.exit("The token must be at least 8 characters.")

    print(f"Loading {args.workers} model copies x {args.threads} threads...", flush=True)
    pool = ProcessPoolExecutor(args.workers, initializer=_load_worker, initargs=(args.model_dir, args.threads))
    # Load every copy now rather than on the first requests.
    list(pool.map(time.sleep, [0.2] * args.workers))
    info = {
        "protocol": PROTOCOL,
        "model": MODEL_NAME,
        "sampleRate": 24000,
        "workers": args.workers,
        "threadsPerWorker": args.threads,
        "name": args.name,
        "verbose": args.verbose,
    }
    server = Server((args.host, args.port), pool, token, info)
    print(f"Narration server '{args.name}' ready on port {args.port}.", flush=True)
    for address in addresses():
        print(f"  App address: {address}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        pool.shutdown(cancel_futures=True)


if __name__ == "__main__":
    main()
