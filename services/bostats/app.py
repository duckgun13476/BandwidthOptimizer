#!/usr/bin/env python3
import gzip
import hashlib
import io
import json
import os
import secrets
import sqlite3
import tempfile
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HOST = os.environ.get("BOSTATS_HOST", "127.0.0.1")
PORT = int(os.environ.get("BOSTATS_PORT", "8824"))
DATA_DIR = Path(os.environ.get("BOSTATS_DATA_DIR", "/srv/bostats/data"))
STATIC_DIR = Path(__file__).resolve().parent / "static"
REPORT_DIR = DATA_DIR / "reports"
DATABASE = DATA_DIR / "metadata.db"
MAX_REPORT_BYTES = 4 * 1024 * 1024
MAX_COMPRESSED_BYTES = 4 * 1024 * 1024
REPORT_MEDIA = "application/vnd.bandwidthoptimizer.report-bundle+json"


def initialize() -> None:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DATABASE) as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS reports (
                report_key TEXT PRIMARY KEY,
                report_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                byte_length INTEGER NOT NULL,
                sha256 TEXT NOT NULL
            )
            """
        )


def decode_body(handler: BaseHTTPRequestHandler) -> bytes:
    try:
        length = int(handler.headers.get("Content-Length", ""))
    except ValueError as exception:
        raise ValueError("invalid Content-Length") from exception
    if length <= 0 or length > MAX_COMPRESSED_BYTES:
        raise ValueError("compressed request exceeds 4 MiB")
    encoded = handler.rfile.read(length)
    if len(encoded) != length:
        raise ValueError("incomplete request body")
    if handler.headers.get("Content-Encoding", "").lower() != "gzip":
        if len(encoded) > MAX_REPORT_BYTES:
            raise ValueError("report exceeds 4 MiB")
        return encoded
    with gzip.GzipFile(fileobj=io.BytesIO(encoded)) as stream:
        decoded = stream.read(MAX_REPORT_BYTES + 1)
    if len(decoded) > MAX_REPORT_BYTES:
        raise ValueError("decompressed report exceeds 4 MiB")
    return decoded


def validate_report(payload: object) -> dict:
    if not isinstance(payload, dict):
        raise ValueError("report root must be an object")
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported report schema")
    report_id = payload.get("reportId")
    if not isinstance(report_id, str) or not 8 <= len(report_id) <= 128:
        raise ValueError("invalid reportId")
    if not isinstance(payload.get("summary"), dict):
        raise ValueError("missing summary report")
    if not isinstance(payload.get("playerTraffic"), dict):
        raise ValueError("missing player traffic report")
    return payload


def store_report(raw: bytes, payload: dict) -> str:
    report_key = secrets.token_urlsafe(18)
    target = REPORT_DIR / f"{report_key}.json"
    fd, temporary_name = tempfile.mkstemp(prefix=".upload-", suffix=".tmp", dir=REPORT_DIR)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_name, target)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise
    try:
        with sqlite3.connect(DATABASE) as connection:
            connection.execute(
                "INSERT INTO reports VALUES (?, ?, ?, ?, ?)",
                (report_key, payload["reportId"], int(time.time()), len(raw), hashlib.sha256(raw).hexdigest()),
            )
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    return report_key


class Handler(BaseHTTPRequestHandler):
    server_version = "BOStats/1"

    def do_HEAD(self) -> None:
        path = urlparse(self.path).path
        if path == "/healthz" or path == "/" or path.startswith("/report/") or path.startswith("/static/"):
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            return
        self.send_response(HTTPStatus.NOT_FOUND)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/healthz":
            self.send_json({"status": "ok", "service": "bostats", "schema": 1})
            return
        if path == "/" or path.startswith("/report/"):
            self.send_static("index.html", "text/html; charset=utf-8")
            return
        if path == "/static/app.css":
            self.send_static("app.css", "text/css; charset=utf-8")
            return
        if path == "/static/app.js":
            self.send_static("app.js", "application/javascript; charset=utf-8")
            return
        prefix = "/api/v1/reports/"
        if path.startswith(prefix):
            key = path[len(prefix):]
            if not key or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for character in key):
                self.send_error_json(HTTPStatus.NOT_FOUND, "report not found")
                return
            report = REPORT_DIR / f"{key}.json"
            if not report.is_file():
                self.send_error_json(HTTPStatus.NOT_FOUND, "report not found")
                return
            self.send_bytes(HTTPStatus.OK, report.read_bytes(), "application/json; charset=utf-8", cache="private, max-age=60")
            return
        self.send_error_json(HTTPStatus.NOT_FOUND, "not found")

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/api/v1/reports":
            self.send_error_json(HTTPStatus.NOT_FOUND, "not found")
            return
        media_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        if media_type != REPORT_MEDIA:
            self.send_error_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported report media type")
            return
        try:
            raw = decode_body(self)
            payload = validate_report(json.loads(raw.decode("utf-8")))
            key = store_report(raw, payload)
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError, gzip.BadGzipFile) as exception:
            self.send_error_json(HTTPStatus.BAD_REQUEST, str(exception))
            return
        except Exception:
            self.log_error("report storage failed")
            self.send_error_json(HTTPStatus.INTERNAL_SERVER_ERROR, "report storage failed")
            return
        proto = self.headers.get("X-Forwarded-Proto", "http")
        host = self.headers.get("X-Forwarded-Host", self.headers.get("Host", "bostats.torqueflux.com"))
        url = f"{proto}://{host}/report/{key}"
        self.send_json({"key": key, "url": url}, status=HTTPStatus.CREATED, location=url)

    def send_static(self, name: str, content_type: str) -> None:
        target = STATIC_DIR / name
        if not target.is_file():
            self.send_error_json(HTTPStatus.NOT_FOUND, "asset not found")
            return
        self.send_bytes(HTTPStatus.OK, target.read_bytes(), content_type, cache="public, max-age=300")

    def send_json(self, value: object, status: HTTPStatus = HTTPStatus.OK, location: str | None = None) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(body)

    def send_error_json(self, status: HTTPStatus, message: str) -> None:
        self.send_json({"error": message}, status=status)

    def send_bytes(self, status: HTTPStatus, body: bytes, content_type: str, cache: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
        )
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message: str, *args: object) -> None:
        print(f"{self.address_string()} {message % args}", flush=True)


if __name__ == "__main__":
    initialize()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"BO Stats listening on {HOST}:{PORT}", flush=True)
    server.serve_forever()
