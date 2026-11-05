"""Loopback-only Android protocol fixture. NOT the V2 production pipeline.

Uses real HTTP chunked multipart and real encoded media, but scripted job states.
Never logs request headers/bodies, connection URL or even the ephemeral token.
"""
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sys
import time

root = Path(sys.argv[1]).resolve()
token = secrets.token_urlsafe(32)
manifest = root / "connection.json"
fd = os.open(manifest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w") as stream:
    json.dump({"cloud_url": "https://qa.invalid", "token": token, "generation": time.time_ns()}, stream)

state = {"status": "idle", "projects": {}, "source_attempts": 0, "background_attempts": 0,
         "start_attempts": 0, "review_attempts": 0, "download_attempts": 0,
         "truncated_downloads": 0, "truncate_next_download": False}
project_id = "a" * 32


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, code, payload, kind="application/json"):
        content = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def authorized(self):
        supplied = self.headers.get("X-Music-Shorts-Token", "")
        if not hmac.compare_digest(supplied, token):
            self.reply(401, {"ok": False, "error": "auth_failed"})
            return False
        return True

    def body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            content = bytearray()
            while True:
                size = int(self.rfile.readline().split(b";", 1)[0], 16)
                if size == 0:
                    self.rfile.readline()
                    return bytes(content)
                if size > 5 * 1024 * 1024 or len(content) + size > 5 * 1024 * 1024:
                    raise ValueError("fixture limit")
                content.extend(self.rfile.read(size))
                if self.rfile.read(2) != b"\r\n":
                    raise ValueError("chunk framing")
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 <= size <= 5 * 1024 * 1024:
            raise ValueError("fixture limit")
        return self.rfile.read(size)

    def multipart(self):
        content = self.body()
        parsed = BytesParser(policy=default).parsebytes(
            b"Content-Type: " + self.headers["Content-Type"].encode() + b"\r\nMIME-Version: 1.0\r\n\r\n" + content)
        fields = {}
        for part in parsed.iter_parts():
            fields[part.get_param("name", header="content-disposition")] = part.get_payload(decode=True)
        media = fields["media"]
        assert int(fields["size"]) == len(media)
        assert fields["sha256"].decode() == hashlib.sha256(media).hexdigest()
        return fields, media

    def do_POST(self):
        if not self.authorized():
            return
        try:
            if self.path == "/mobile-source":
                fields, media = self.multipart()
                request_id = self.headers["X-Music-Shorts-Request-ID"]
                previous = state["projects"].get(request_id)
                digest = hashlib.sha256(media).hexdigest()
                assert previous is None or previous == digest
                state["projects"][request_id] = digest
                state["source_attempts"] += 1
                return self.reply(201, {"ok": True, "project_id": project_id, "uploaded_bytes": len(media),
                                        "source_type": "uploaded_file"})
            if self.path == "/mobile-background":
                fields, media = self.multipart()
                assert fields["project_id"].decode() == project_id
                state["background_attempts"] += 1
                return self.reply(201, {"ok": True, "project_id": project_id, "uploaded_bytes": len(media), "duration_ms": 2000})
            if self.path.endswith("/start"):
                state["start_attempts"] += 1
                if state["status"] == "idle":
                    state["status"] = "needs_review"
                return self.reply(200, self.status())
            if self.path.endswith("/review"):
                body = json.loads(self.body())
                assert body == {"approved": True, "revision": "b" * 64}
                state["review_attempts"] += 1
                state["status"] = "completed"
                return self.reply(200, self.status())
            if self.path == "/qa-truncate-next-download":
                assert state["status"] == "completed"
                state["truncate_next_download"] = True
                return self.reply(200, {"ok": True})
            self.reply(404, {})
        except Exception:
            self.reply(422, {"ok": False, "error": "fixture_validation"})

    def status(self):
        return {"ok": True, "project_id": project_id, "status": state["status"], "line_count": 1,
                "download_ready": state["status"] == "completed", "stage": "complete" if state["status"] == "completed" else "review"}

    def do_GET(self):
        if not self.authorized():
            return
        if self.path == "/qa-evidence":
            return self.reply(200, {"project_count": len(state["projects"]), "source_attempts": state["source_attempts"],
                "background_attempts": state["background_attempts"], "start_attempts": state["start_attempts"],
                "review_attempts": state["review_attempts"], "download_attempts": state["download_attempts"],
                "truncated_downloads": state["truncated_downloads"],
                "download_sha256": hashlib.sha256((root/"final.mp4").read_bytes()).hexdigest()})
        if "/" + "0" * 28 + "0530/" in self.path:
            return self.reply(530, b"error code: 1033", "text/html")
        if self.path.endswith("/status"):
            return self.reply(200, self.status())
        if self.path.endswith("/review"):
            return self.reply(200, {"ok": True, "revision": "b" * 64, "lines": [
                {"line_id": "fixture-line", "japanese": "テスト", "korean": "시험", "start_ms": 0, "end_ms": 2000}]})
        if self.path.endswith("/download") and state["status"] == "completed":
            content = (root / "final.mp4").read_bytes()
            state["download_attempts"] += 1
            if state["truncate_next_download"]:
                state["truncate_next_download"] = False
                state["truncated_downloads"] += 1
                # A real HTTP 200 with a broken body, not a mocked exception.
                # Only this loopback test server has a fault-injection route.
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                self.wfile.write(content[:max(1, len(content) // 2)])
                self.wfile.flush()
                self.close_connection = True
                return
            return self.reply(200, content, "video/mp4")
        return self.reply(404, {})


ThreadingHTTPServer(("127.0.0.1", 18765), Handler).serve_forever()
