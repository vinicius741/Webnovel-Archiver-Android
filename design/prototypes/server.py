#!/usr/bin/env python3
"""LAN server for the UI prototypes: serves this folder and stores feedback notes.

    python3 design/prototypes/server.py [--port 8737]

GET  /api/feedback[?version=v2-modern]  -> JSON list of notes
POST /api/feedback  {version, scope, screen, path, rating, note}  -> appended to feedback/feedback.jsonl
"""
import argparse
import json
import os
import socket
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = os.path.dirname(os.path.abspath(__file__))
FEEDBACK = os.path.join(ROOT, "feedback", "feedback.jsonl")


def read_notes():
    if not os.path.exists(FEEDBACK):
        return []
    with open(FEEDBACK, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/api/feedback":
            v = parse_qs(u.query).get("version", [None])[0]
            notes = [n for n in read_notes() if not v or n.get("version") == v]
            return self._json(200, notes)
        if u.path == "/":
            self.path = "/index.html"
        return super().do_GET()

    def do_POST(self):
        if urlparse(self.path).path != "/api/feedback":
            return self._json(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(min(n, 64_000)) or b"{}")
        except (ValueError, json.JSONDecodeError):
            return self._json(400, {"error": "bad json"})
        note = {k: data.get(k) for k in ("version", "scope", "screen", "path", "rating", "note", "viewport", "ua")}
        note["at"] = int(time.time() * 1000)
        os.makedirs(os.path.dirname(FEEDBACK), exist_ok=True)
        with open(FEEDBACK, "a", encoding="utf-8") as f:
            f.write(json.dumps(note, ensure_ascii=False) + "\n")
        return self._json(200, {"ok": True})

    def log_message(self, fmt, *args):
        if args and "/api/" in str(args[0]):
            super().log_message(fmt, *args)


def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8737)
    ap.add_argument("--bind", default="0.0.0.0")
    a = ap.parse_args()
    ThreadingHTTPServer.allow_reuse_address = True
    srv = ThreadingHTTPServer((a.bind, a.port), Handler)
    print(f"Serving {ROOT}")
    for ip in lan_ips() + ["127.0.0.1"]:
        print(f"  http://{ip}:{a.port}/")
    srv.serve_forever()
