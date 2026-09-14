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
MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_COMPRESSED_BYTES = 16 * 1024 * 1024
MAX_STORED_REPORTS = int(os.environ.get("BOSTATS_MAX_STORED_REPORTS", "10000"))
MAX_STORED_BYTES = int(os.environ.get("BOSTATS_MAX_STORED_BYTES", str(16 * 1024 * 1024 * 1024)))
MAX_REPORT_AGE_DAYS = int(os.environ.get("BOSTATS_MAX_REPORT_AGE_DAYS", "400"))
MAX_ACTIVE_UPLOADS = int(os.environ.get("BOSTATS_MAX_ACTIVE_UPLOADS", "2"))
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
            columns = {row[1] for row in connection.execute("PRAGMA table_info(reports)")}
            if "source_fingerprint" not in columns:
                connection.execute("ALTER TABLE reports ADD COLUMN source_fingerprint TEXT")
            if "updated_at" not in columns:
                connection.execute("ALTER TABLE reports ADD COLUMN updated_at INTEGER")
                connection.execute("UPDATE reports SET updated_at = created_at WHERE updated_at IS NULL")
            connection.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS reports_source_fingerprint "
                "ON reports(source_fingerprint) WHERE source_fingerprint IS NOT NULL"
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS report_submissions (
                    report_id TEXT PRIMARY KEY,
                    report_key TEXT NOT NULL,
                    source_fingerprint TEXT,
                    created_at INTEGER NOT NULL,
                    sha256 TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                INSERT OR IGNORE INTO report_submissions
                    (report_id, report_key, source_fingerprint, created_at, sha256)
                SELECT report_id, report_key, source_fingerprint, created_at, sha256 FROM reports
                """
            )


def decode_body(handler: BaseHTTPRequestHandler) -> bytes:
    try:
        length = int(handler.headers.get("Content-Length", ""))
    except ValueError as exception:
        raise ValueError("invalid Content-Length") from exception
    if length <= 0 or length > MAX_COMPRESSED_BYTES:
        raise ValueError("compressed request exceeds 16 MiB")
    encoded = handler.rfile.read(length)
    if len(encoded) != length:
        raise ValueError("incomplete request body")
    if handler.headers.get("Content-Encoding", "").lower() != "gzip":
        if len(encoded) > MAX_REPORT_BYTES:
            raise ValueError("report exceeds 16 MiB")
        return encoded
    with gzip.GzipFile(fileobj=io.BytesIO(encoded)) as stream:
        decoded = stream.read(MAX_REPORT_BYTES + 1)
    if len(decoded) > MAX_REPORT_BYTES:
        raise ValueError("decompressed report exceeds 16 MiB")
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
    source_fingerprint = payload.get("sourceFingerprint")
    if source_fingerprint is not None and (
            not isinstance(source_fingerprint, str)
            or len(source_fingerprint) != 64
            or any(character not in "0123456789abcdef" for character in source_fingerprint)
    ):
        raise ValueError("invalid sourceFingerprint")
    return payload


def store_report(raw: bytes, payload: dict) -> tuple[str, bool]:
    digest = hashlib.sha256(raw).hexdigest()
    with closing(sqlite3.connect(DATABASE)) as connection:
        connection.execute("BEGIN IMMEDIATE")
        existing = connection.execute(
            "SELECT report_key, sha256 FROM report_submissions WHERE report_id = ?",
            (payload["reportId"],),
        ).fetchone()
        if existing is not None:
            connection.rollback()
            if existing[1] != digest:
                raise ReportConflictError("reportId already exists with different content")
            return existing[0], False

        source_fingerprint = payload.get("sourceFingerprint")
        source_row = None
        if source_fingerprint is not None:
            source_row = connection.execute(
                "SELECT report_key FROM reports WHERE source_fingerprint = ?",
                (source_fingerprint,),
            ).fetchone()
        previous_key = source_row[0] if source_row is not None else None
        report_key = secrets.token_urlsafe(18)
        target = REPORT_DIR / f"{report_key}.json"
        stored_payload = payload
        previous_target = REPORT_DIR / f"{previous_key}.json" if previous_key is not None else None
        if previous_target is not None and previous_target.is_file():
            try:
                existing_payload = validate_report(json.loads(previous_target.read_text(encoding="utf-8")))
                stored_payload = merge_report_history(existing_payload, payload)
            except (OSError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
                stored_payload = payload
        stored_raw = raw if source_row is None else encode_bounded_report(stored_payload)
        stored_digest = hashlib.sha256(stored_raw).hexdigest()
        fd, temporary_name = tempfile.mkstemp(prefix=".upload-", suffix=".tmp", dir=REPORT_DIR)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(stored_raw)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary_name, target)
            try:
                now = int(time.time())
                if source_row is None:
                    connection.execute(
                        """
                        INSERT INTO reports
                            (report_key, report_id, created_at, byte_length, sha256, source_fingerprint, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (report_key, payload["reportId"], now, len(stored_raw), stored_digest,
                         source_fingerprint, now),
                    )
                else:
                    connection.execute(
                        """
                        UPDATE reports SET report_key = ?, report_id = ?, byte_length = ?, sha256 = ?, updated_at = ?
                        WHERE report_key = ?
                        """,
                        (report_key, payload["reportId"], len(stored_raw), stored_digest, now, previous_key),
                    )
                    connection.execute(
                        "UPDATE report_submissions SET report_key = ? WHERE report_key = ?",
                        (report_key, previous_key),
                    )
                connection.execute(
                    """
                    INSERT INTO report_submissions
                        (report_id, report_key, source_fingerprint, created_at, sha256)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (payload["reportId"], report_key, source_fingerprint, now, digest),
                )
                removed_keys = prune_reports(connection, report_key, payload["reportId"], now)
                connection.commit()
                if previous_target is not None:
                    previous_target.unlink(missing_ok=True)
                for removed_key in removed_keys:
                    (REPORT_DIR / f"{removed_key}.json").unlink(missing_ok=True)
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


def encode_bounded_report(payload: dict) -> bytes:
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    history = payload.get("trafficHistory")
    hours = history.get("hours") if isinstance(history, dict) else None
    while len(encoded) > MAX_REPORT_BYTES and isinstance(hours, list) and hours:
        del hours[:max(1, len(hours) // 8)]
        rebuild_history_aggregates(history)
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_REPORT_BYTES:
        raise ValueError("merged report exceeds 16 MiB")
    return encoded


def merge_report_history(previous: dict, current: dict) -> dict:
    merged = dict(current)
    prior_history = previous.get("trafficHistory")
    current_history = current.get("trafficHistory")
    if not isinstance(prior_history, dict) or not isinstance(current_history, dict):
        return merged
    by_start = {}
    for history in (prior_history, current_history):
        for hour in history.get("hours", []):
            if isinstance(hour, dict) and isinstance(hour.get("periodStartMillis"), int):
                by_start[hour["periodStartMillis"]] = hour
    history = dict(current_history)
    history["hours"] = [by_start[key] for key in sorted(by_start)]
    rebuild_history_aggregates(history)
    merged["trafficHistory"] = history
    return merged


def rebuild_history_aggregates(history: dict) -> None:
    hours = history.get("hours", [])
    if not hours:
        history["totals"] = {}
        history["players"] = []
        return
    totals = {}
    players = {}
    for hour in hours:
        add_numeric_fields(totals, hour.get("totals"))
        for player in hour.get("players", []):
            if not isinstance(player, dict) or not isinstance(player.get("playerUuid"), str):
                continue
            entry = players.setdefault(player["playerUuid"], {
                "playerUuid": player["playerUuid"],
                "playerName": player.get("playerName", "<unknown-player>"),
                "traffic": {},
            })
            if isinstance(player.get("playerName"), str) and player["playerName"]:
                entry["playerName"] = player["playerName"]
            traffic = entry["traffic"]
            for field in ("outboundRawBytes", "outboundWireBytes", "inboundRawBytes", "inboundWireBytes"):
                value = player.get(field)
                if isinstance(value, int) and value >= 0:
                    traffic[field] = traffic.get(field, 0) + value
    history["periodStartMillis"] = min(hour.get("periodStartMillis", 0) for hour in hours)
    history["periodEndMillis"] = max(hour.get("periodEndMillis", 0) for hour in hours)
    history["complete"] = all(bool(hour.get("complete")) for hour in hours)
    history["totals"] = totals
    history["players"] = list(players.values())


def add_numeric_fields(target: dict, source: object) -> None:
    if not isinstance(source, dict):
        return
    for name, value in source.items():
        if isinstance(value, int) and value >= 0:
            target[name] = target.get(name, 0) + value


def prune_reports(connection: sqlite3.Connection, protected_key: str, protected_report_id: str, now: int) -> list[str]:
    cutoff = now - max(1, MAX_REPORT_AGE_DAYS) * 86400
    rows = connection.execute(
        "SELECT report_key, byte_length, COALESCE(updated_at, created_at) FROM reports "
        "ORDER BY (report_key = ?) DESC, COALESCE(updated_at, created_at) DESC, report_key DESC",
        (protected_key,),
    ).fetchall()
    retained_count = 0
    retained_bytes = 0
    removed = []
    for report_key, byte_length, updated_at in rows:
        keep = report_key == protected_key or (
            updated_at >= cutoff
            and retained_count < max(1, MAX_STORED_REPORTS)
            and retained_bytes + byte_length <= max(MAX_REPORT_BYTES, MAX_STORED_BYTES)
        )
        if keep:
            retained_count += 1
            retained_bytes += byte_length
        else:
            removed.append(report_key)
    for report_key in removed:
        connection.execute("DELETE FROM reports WHERE report_key = ?", (report_key,))
        connection.execute("DELETE FROM report_submissions WHERE report_key = ?", (report_key,))
    connection.execute("DELETE FROM report_submissions WHERE created_at < ?", (cutoff,))
    connection.execute(
        """
        DELETE FROM report_submissions WHERE report_id IN (
            SELECT report_id FROM report_submissions
            ORDER BY (report_id = ?) DESC, created_at DESC, report_id DESC
            LIMIT -1 OFFSET ?
        )
        """,
        (protected_report_id, max(1, MAX_STORED_REPORTS * 16)),
    )
    return removed


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
        if not self.server.upload_slots.acquire(timeout=self.server.wait_timeout_seconds):
            self.reject_upload_busy()
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
        finally:
            self.server.upload_slots.release()
        proto = self.headers.get("X-Forwarded-Proto", "http")
        host = self.headers.get("X-Forwarded-Host", self.headers.get("Host", "bostats.torqueflux.com"))
        url = f"{proto}://{host}/report/{key}"
        status = HTTPStatus.CREATED if created else HTTPStatus.OK
        self.send_json({"key": key, "url": url}, status=status, location=url)

    def reject_upload_busy(self) -> None:
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
        body = {
            "error": "report processing capacity is full",
            "retryable": True,
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
        self.upload_slots = threading.BoundedSemaphore(MAX_ACTIVE_UPLOADS)
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
