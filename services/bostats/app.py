#!/usr/bin/env python3
import gzip
import hashlib
import io
import json
import os
import secrets
import shutil
import sqlite3
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
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
MAX_ACTIVE_REQUESTS = int(os.environ.get("BOSTATS_MAX_ACTIVE_REQUESTS", "16"))
MAX_WAITING_REQUESTS = int(os.environ.get("BOSTATS_MAX_WAITING_REQUESTS", "64"))
SOCKET_TIMEOUT_SECONDS = float(os.environ.get("BOSTATS_SOCKET_TIMEOUT_SECONDS", "15"))
BUSY_RETRY_AFTER_SECONDS = int(os.environ.get("BOSTATS_BUSY_RETRY_AFTER_SECONDS", "5"))
WAIT_TIMEOUT_SECONDS = float(os.environ.get("BOSTATS_WAIT_TIMEOUT_SECONDS", "2"))
REPORT_MEDIA = "application/vnd.bandwidthoptimizer.report-bundle+json"
FILE_CHUNK_BYTES = 64 * 1024


class ReportConflictError(ValueError):
    pass


def initialize() -> None:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(DATABASE)) as connection:
        with connection:
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


def store_report(raw: bytes, payload: dict) -> tuple[str, bool]:
    digest = hashlib.sha256(raw).hexdigest()
    with closing(sqlite3.connect(DATABASE)) as connection:
        connection.execute("BEGIN IMMEDIATE")
        existing = connection.execute(
            "SELECT report_key, sha256 FROM reports WHERE report_id = ? ORDER BY created_at DESC LIMIT 1",
            (payload["reportId"],),
        ).fetchone()
        if existing is not None:
            connection.rollback()
            if existing[1] != digest:
                raise ReportConflictError("reportId already exists with different content")
            return existing[0], False

        report_key = secrets.token_urlsafe(18)
        target = REPORT_DIR / f"{report_key}.json"
        fd, temporary_name = tempfile.mkstemp(prefix=".upload-", suffix=".tmp", dir=REPORT_DIR)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(raw)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary_name, target)
            try:
                connection.execute(
                    "INSERT INTO reports VALUES (?, ?, ?, ?, ?)",
                    (report_key, payload["reportId"], int(time.time()), len(raw), digest),
                )
                connection.commit()
            except BaseException:
                target.unlink(missing_ok=True)
                raise
        except BaseException:
            connection.rollback()
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise
    return report_key, True


class Handler(BaseHTTPRequestHandler):
    server_version = "BOStats/1"

    def do_HEAD(self) -> None:
        if self.reject_if_busy():
            return
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
        if self.reject_if_busy():
            return
        path = urlparse(self.path).path
        if path == "/healthz":
            self.send_json({"status": "ok", "service": "bostats", "schema": 1})
            return
        if path == "/" or path.startswith("/report/"):
            self.send_static("index.html", "text/html; charset=utf-8", cache="no-store")
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
            self.send_file(HTTPStatus.OK, report, "application/json; charset=utf-8", cache="private, max-age=60")
            return
        self.send_error_json(HTTPStatus.NOT_FOUND, "not found")

    def do_POST(self) -> None:
        if self.reject_if_busy():
            return
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
            key, created = store_report(raw, payload)
        except ReportConflictError as exception:
            self.send_error_json(HTTPStatus.CONFLICT, str(exception))
            return
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
        status = HTTPStatus.CREATED if created else HTTPStatus.OK
        self.send_json({"key": key, "url": url}, status=status, location=url)

    def reject_if_busy(self) -> bool:
        snapshot = self.server.busy_snapshot()
        if snapshot is None:
            return False
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if 0 < length <= MAX_COMPRESSED_BYTES:
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(FILE_CHUNK_BYTES, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
        active, waiting, position = snapshot
        body = {
            "error": "server processing queue is full",
            "retryable": True,
            "active": active,
            "waiting": waiting,
            "queuePosition": position,
            "waitingCapacity": self.server.max_waiting_requests,
            "retryAfterSeconds": self.server.busy_retry_after_seconds,
        }
        encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(HTTPStatus.SERVICE_UNAVAILABLE)
        self.send_header("Retry-After", str(self.server.busy_retry_after_seconds))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(encoded)
        return True

    def send_static(self, name: str, content_type: str, cache: str = "public, max-age=300") -> None:
        target = STATIC_DIR / name
        if not target.is_file():
            self.send_error_json(HTTPStatus.NOT_FOUND, "asset not found")
            return
        self.send_bytes(HTTPStatus.OK, target.read_bytes(), content_type, cache=cache)

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
        self.send_content_headers(status, len(body), content_type, cache)
        self.wfile.write(body)

    def send_file(self, status: HTTPStatus, path: Path, content_type: str, cache: str) -> None:
        with path.open("rb") as source:
            self.send_content_headers(status, os.fstat(source.fileno()).st_size, content_type, cache)
            shutil.copyfileobj(source, self.wfile, length=FILE_CHUNK_BYTES)

    def send_content_headers(self, status: HTTPStatus, length: int, content_type: str, cache: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
        )
        self.end_headers()

    def log_message(self, message: str, *args: object) -> None:
        print(f"{self.address_string()} {message % args}", flush=True)


class BoundedThreadingHTTPServer(HTTPServer):
    request_queue_size = 32

    def __init__(
        self,
        server_address: tuple[str, int],
        handler_class: type[BaseHTTPRequestHandler],
        max_active_requests: int = MAX_ACTIVE_REQUESTS,
        socket_timeout_seconds: float = SOCKET_TIMEOUT_SECONDS,
        max_waiting_requests: int = MAX_WAITING_REQUESTS,
        busy_retry_after_seconds: int = BUSY_RETRY_AFTER_SECONDS,
        wait_timeout_seconds: float = WAIT_TIMEOUT_SECONDS,
    ) -> None:
        if min(max_active_requests, max_waiting_requests, busy_retry_after_seconds) < 0 \
                or min(socket_timeout_seconds, wait_timeout_seconds) <= 0:
            raise ValueError("request limits and socket timeout must be non-negative")
        if max_active_requests == 0:
            raise ValueError("active request limit must be positive")
        self.socket_timeout_seconds = socket_timeout_seconds
        self.max_active_requests = max_active_requests
        self.max_waiting_requests = max_waiting_requests
        self.busy_retry_after_seconds = busy_retry_after_seconds
        self.wait_timeout_seconds = wait_timeout_seconds
        self.request_slots = threading.BoundedSemaphore(max_active_requests + max_waiting_requests)
        self.processing_slots = threading.BoundedSemaphore(max_active_requests)
        self.state_lock = threading.Lock()
        self.active_requests = 0
        self.waiting_requests = 0
        self.request_state = threading.local()
        self.executor = ThreadPoolExecutor(
            max_workers=max_active_requests + max_waiting_requests,
            thread_name_prefix="bostats-request",
        )
        super().__init__(server_address, handler_class)

    def get_request(self):
        request, client_address = super().get_request()
        request.settimeout(self.socket_timeout_seconds)
        return request, client_address

    def process_request(self, request, client_address) -> None:
        if not self.request_slots.acquire(blocking=False):
            self.reject_busy(request)
            return
        try:
            self.executor.submit(self.process_request_thread, request, client_address)
        except BaseException:
            self.request_slots.release()
            self.close_request(request)
            raise

    def process_request_thread(self, request, client_address) -> None:
        processing = self.processing_slots.acquire(blocking=False)
        position = 0
        if not processing:
            with self.state_lock:
                self.waiting_requests += 1
                position = self.waiting_requests
            processing = self.processing_slots.acquire(timeout=self.wait_timeout_seconds)
            with self.state_lock:
                queued_at_timeout = self.waiting_requests
                self.waiting_requests -= 1
                active = self.active_requests
            if not processing:
                self.request_state.busy = (active, queued_at_timeout, position)
        if processing:
            with self.state_lock:
                self.active_requests += 1
        try:
            try:
                self.finish_request(request, client_address)
            except Exception:
                self.handle_error(request, client_address)
            finally:
                self.shutdown_request(request)
        finally:
            self.request_state.busy = None
            if processing:
                with self.state_lock:
                    self.active_requests -= 1
                self.processing_slots.release()
            self.request_slots.release()

    def busy_snapshot(self):
        return getattr(self.request_state, "busy", None)

    def reject_busy(self, request) -> None:
        with self.state_lock:
            active = self.active_requests
            waiting = self.waiting_requests
        body = json.dumps({
            "error": "server processing queue is full",
            "retryable": True,
            "active": active,
            "waiting": waiting,
            "waitingCapacity": self.max_waiting_requests,
            "retryAfterSeconds": self.busy_retry_after_seconds,
        }, separators=(",", ":")).encode("utf-8")
        response = (
            "HTTP/1.1 503 Service Unavailable\r\n"
            "Content-Type: application/json; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"Retry-After: {self.busy_retry_after_seconds}\r\n"
            "Cache-Control: no-store\r\n"
            "Connection: close\r\n\r\n"
        ).encode("ascii") + body
        try:
            request.sendall(response)
        except OSError:
            pass
        finally:
            self.close_request(request)

    def server_close(self) -> None:
        super().server_close()
        self.executor.shutdown(wait=True, cancel_futures=True)


if __name__ == "__main__":
    initialize()
    server = BoundedThreadingHTTPServer((HOST, PORT), Handler)
    print(f"BO Stats listening on {HOST}:{PORT}", flush=True)
    server.serve_forever()
