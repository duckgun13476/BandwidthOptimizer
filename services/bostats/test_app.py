#!/usr/bin/env python3
import gzip
import http.client
import json
import socket
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler
from pathlib import Path

import app


class BlockingHandler(BaseHTTPRequestHandler):
    release = threading.Event()
    entered = 0
    lock = threading.Lock()

    def do_GET(self) -> None:
        if app.Handler.reject_if_busy(self):
            return
        with self.lock:
            type(self).entered += 1
        self.release.wait(2)
        self.send_response(204)
        self.end_headers()

    def do_POST(self) -> None:
        if app.Handler.reject_if_busy(self):
            return
        self.do_GET()

    def log_message(self, message: str, *args: object) -> None:
        pass


class QuietServer(app.BoundedThreadingHTTPServer):
    def handle_error(self, request, client_address) -> None:
        pass


class BostatsServerRegression(unittest.TestCase):
    def setUp(self) -> None:
        BlockingHandler.release.clear()
        BlockingHandler.entered = 0
        self.server = QuietServer(("127.0.0.1", 0), BlockingHandler, 2, 0.2, 1, 1, 0.1)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        BlockingHandler.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def test_concurrency_limit_rejects_and_recovers(self) -> None:
        first = self.request_socket()
        second = self.request_socket()
        self.wait_for_entered(2)

        rejected = self.request_socket()
        response = self.read_response(rejected)
        self.assertIn(b" 503 ", response.split(b"\r\n", 1)[0])
        self.assertIn(b'"waiting":1', response)
        self.assertIn(b'"queuePosition":1', response)
        self.assertEqual(2, BlockingHandler.entered)

        BlockingHandler.release.set()
        self.assertIn(b" 204 ", self.read_response(first).split(b"\r\n", 1)[0])
        self.assertIn(b" 204 ", self.read_response(second).split(b"\r\n", 1)[0])
        time.sleep(0.05)

        recovered = self.request_socket()
        self.assertIn(b" 204 ", self.read_response(recovered).split(b"\r\n", 1)[0])

    def test_partial_request_times_out_and_releases_slot(self) -> None:
        partial = socket.create_connection(self.server.server_address, timeout=1)
        partial.sendall(b"GET /")
        active = self.request_socket()
        self.wait_for_entered(1)

        rejected = self.request_socket()
        self.assertIn(b" 503 ", self.read_response(rejected).split(b"\r\n", 1)[0])
        self.assertEqual(1, BlockingHandler.entered)

        time.sleep(0.35)
        recovered = self.request_socket()
        self.wait_for_entered(2)

        BlockingHandler.release.set()
        self.assertIn(b" 204 ", self.read_response(active).split(b"\r\n", 1)[0])
        self.assertIn(b" 204 ", self.read_response(recovered).split(b"\r\n", 1)[0])
        partial.close()

    def test_report_file_is_streamed_exactly(self) -> None:
        payload = bytes(range(256)) * 400
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_bytes(payload)
            output = RecordingOutput()
            handler = object.__new__(app.Handler)
            handler.wfile = output
            handler.request_version = "HTTP/1.1"
            handler.command = "GET"
            handler.requestline = "GET /api/v1/reports/test HTTP/1.1"
            handler.client_address = ("127.0.0.1", 0)
            handler.server_version = "BOStats/1"
            handler.sys_version = ""
            handler.send_file(200, path, "application/json", "private")
            self.assertEqual(payload, output.body())
            self.assertGreater(len(output.writes), 2)
            self.assertLessEqual(max(map(len, output.writes[1:])), app.FILE_CHUNK_BYTES)

    def test_waiting_pool_is_bounded_and_drains(self) -> None:
        server = QuietServer(("127.0.0.1", 0), BlockingHandler, 1, 1, 1, 1, 1)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        first = second = None
        try:
            first = self.request_socket_for(server)
            self.wait_for_entered(1)
            second = self.request_socket_for(server)
            self.wait_for_waiting(server, 1)

            BlockingHandler.release.set()
            self.assertIn(b" 204 ", self.read_response(first).split(b"\r\n", 1)[0])
            self.assertIn(b" 204 ", self.read_response(second).split(b"\r\n", 1)[0])
        finally:
            BlockingHandler.release.set()
            for connection in (first, second):
                if connection is not None:
                    connection.close()
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_busy_upload_response_drains_request_body(self) -> None:
        server = QuietServer(("127.0.0.1", 0), BlockingHandler, 1, 1, 1, 1, 0.1)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        active = overloaded = None
        try:
            active = self.request_socket_for(server)
            self.wait_for_entered(1)
            overloaded = self.post_socket_for(server, bytes(range(256)) * 512)
            response = self.read_response(overloaded)
            self.assertIn(b" 503 ", response.split(b"\r\n", 1)[0])
            self.assertIn(b"Retry-After: 1", response)
            self.assertIn(b'"active":1', response)
            self.assertIn(b'"waiting":1', response)
            self.assertIn(b'"queuePosition":1', response)
        finally:
            BlockingHandler.release.set()
            for connection in (active, overloaded):
                if connection is not None:
                    connection.close()
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_real_handler_upload_and_download_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            old_data_dir, old_report_dir, old_database = app.DATA_DIR, app.REPORT_DIR, app.DATABASE
            app.DATA_DIR = Path(directory)
            app.REPORT_DIR = app.DATA_DIR / "reports"
            app.DATABASE = app.DATA_DIR / "metadata.db"
            server = None
            thread = None
            try:
                app.initialize()
                server = QuietServer(("127.0.0.1", 0), app.Handler, 4, 1, 0)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                report = json.dumps({
                    "schemaVersion": 1,
                    "reportId": "bounded-server-regression",
                    "summary": {},
                    "playerTraffic": {},
                }, separators=(",", ":")).encode()
                compressed = gzip.compress(report)
                connection = http.client.HTTPConnection(*server.server_address, timeout=2)
                connection.request("POST", "/api/v1/reports", compressed, {
                    "Content-Type": app.REPORT_MEDIA,
                    "Content-Encoding": "gzip",
                })
                upload = connection.getresponse()
                response = json.loads(upload.read())
                self.assertEqual(201, upload.status)
                self.assertLessEqual(len(json.dumps(response, separators=(",", ":")).encode()), 1024)
                connection.close()

                connection = http.client.HTTPConnection(*server.server_address, timeout=2)
                connection.request("GET", f"/api/v1/reports/{response['key']}")
                download = connection.getresponse()
                self.assertEqual(200, download.status)
                self.assertEqual(report, download.read())
                connection.close()

                connection = http.client.HTTPConnection(*server.server_address, timeout=2)
                connection.request("POST", "/api/v1/reports", compressed, {
                    "Content-Type": app.REPORT_MEDIA,
                    "Content-Encoding": "gzip",
                })
                duplicate = connection.getresponse()
                duplicate_response = json.loads(duplicate.read())
                self.assertEqual(200, duplicate.status)
                self.assertEqual(response["key"], duplicate_response["key"])
                connection.close()

                database = app.sqlite3.connect(app.DATABASE)
                try:
                    self.assertEqual(1, database.execute("SELECT COUNT(*) FROM reports").fetchone()[0])
                finally:
                    database.close()
                self.assertEqual(1, len(list(app.REPORT_DIR.glob("*.json"))))
            finally:
                if server is not None:
                    server.shutdown()
                    self.wait_for_idle(server, 4)
                    server.server_close()
                if thread is not None:
                    thread.join(2)
                app.DATA_DIR, app.REPORT_DIR, app.DATABASE = old_data_dir, old_report_dir, old_database

    def test_source_reports_merge_hours_and_rotate_link(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.temporary_storage(directory):
                first = self.report_payload("merge-0001", "a" * 64, 1000, 11, "Alice")
                second = self.report_payload("merge-0002", "a" * 64, 2000, 17, "Alice2")
                first_raw = self.encode(first)
                second_raw = self.encode(second)

                first_key, first_created = app.store_report(first_raw, first)
                second_key, second_created = app.store_report(second_raw, second)
                duplicate_key, duplicate_created = app.store_report(second_raw, second)

                self.assertTrue(first_created)
                self.assertTrue(second_created)
                self.assertFalse(duplicate_created)
                self.assertNotEqual(first_key, second_key)
                self.assertEqual(second_key, duplicate_key)
                self.assertFalse((app.REPORT_DIR / f"{first_key}.json").exists())
                stored = json.loads((app.REPORT_DIR / f"{second_key}.json").read_text(encoding="utf-8"))
                self.assertEqual([1000, 2000], [hour["periodStartMillis"] for hour in stored["trafficHistory"]["hours"]])
                self.assertEqual(28, stored["trafficHistory"]["totals"]["outboundWireBytes"])
                self.assertEqual("Alice2", stored["trafficHistory"]["players"][0]["playerName"])
                database = app.sqlite3.connect(app.DATABASE)
                try:
                    self.assertEqual(1, database.execute("SELECT COUNT(*) FROM reports").fetchone()[0])
                    self.assertEqual(2, database.execute("SELECT COUNT(*) FROM report_submissions").fetchone()[0])
                finally:
                    database.close()

    def test_merged_history_drops_oldest_hours_at_size_limit(self) -> None:
        old_limit = app.MAX_REPORT_BYTES
        try:
            payload = self.report_payload("trim-history", "b" * 64, 1000, 1, "Player")
            template = payload["trafficHistory"]["hours"][0]
            payload["trafficHistory"]["hours"] = []
            for index in range(8):
                hour = json.loads(json.dumps(template))
                hour["periodStartMillis"] = 1000 + index * 1000
                hour["periodEndMillis"] = 2000 + index * 1000
                hour["padding"] = "x" * 300
                payload["trafficHistory"]["hours"].append(hour)
            app.MAX_REPORT_BYTES = 1400
            encoded = app.encode_bounded_report(payload)
            retained = json.loads(encoded)["trafficHistory"]["hours"]
            self.assertLessEqual(len(encoded), 1400)
            self.assertGreater(retained[0]["periodStartMillis"], 1000)
            self.assertEqual(8000, retained[-1]["periodStartMillis"])
        finally:
            app.MAX_REPORT_BYTES = old_limit

    def test_initialize_migrates_legacy_report_table(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "metadata.db"
            connection = app.sqlite3.connect(database)
            try:
                connection.execute(
                    "CREATE TABLE reports (report_key TEXT PRIMARY KEY, report_id TEXT NOT NULL, "
                    "created_at INTEGER NOT NULL, byte_length INTEGER NOT NULL, sha256 TEXT NOT NULL)"
                )
                connection.execute(
                    "INSERT INTO reports VALUES (?, ?, ?, ?, ?)",
                    ("legacy-key", "legacy-report", 1, 2, "a" * 64),
                )
                connection.commit()
            finally:
                connection.close()
            with self.temporary_storage(directory):
                connection = app.sqlite3.connect(app.DATABASE)
                try:
                    columns = {row[1] for row in connection.execute("PRAGMA table_info(reports)")}
                    self.assertIn("source_fingerprint", columns)
                    self.assertIn("updated_at", columns)
                    self.assertEqual(1, connection.execute("SELECT COUNT(*) FROM report_submissions").fetchone()[0])
                finally:
                    connection.close()

    def test_report_retention_bounds_count_and_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.temporary_storage(directory):
                old_count = app.MAX_STORED_REPORTS
                app.MAX_STORED_REPORTS = 2
                try:
                    keys = []
                    for index in range(3):
                        payload = self.report_payload(f"retention-{index:04d}", None, index * 1000, index + 1, "Player")
                        key, _ = app.store_report(self.encode(payload), payload)
                        keys.append(key)
                    database = app.sqlite3.connect(app.DATABASE)
                    try:
                        self.assertEqual(2, database.execute("SELECT COUNT(*) FROM reports").fetchone()[0])
                    finally:
                        database.close()
                    self.assertTrue((app.REPORT_DIR / f"{keys[2]}.json").exists())
                    self.assertEqual(2, sum((app.REPORT_DIR / f"{key}.json").exists() for key in keys))
                finally:
                    app.MAX_STORED_REPORTS = old_count

    def test_upload_parser_capacity_returns_retryable_response(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.temporary_storage(directory):
                server = QuietServer(("127.0.0.1", 0), app.Handler, 4, 0.2, 1, 3, 0.05)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                acquired = 0
                try:
                    for _ in range(app.MAX_ACTIVE_UPLOADS):
                        self.assertTrue(server.upload_slots.acquire(blocking=False))
                        acquired += 1
                    payload = self.report_payload("upload-capacity", None, 1000, 1, "Player")
                    compressed = gzip.compress(self.encode(payload))
                    connection = http.client.HTTPConnection(*server.server_address, timeout=2)
                    connection.request("POST", "/api/v1/reports", compressed, {
                        "Content-Type": app.REPORT_MEDIA,
                        "Content-Encoding": "gzip",
                    })
                    response = connection.getresponse()
                    self.assertEqual(503, response.status)
                    self.assertEqual("3", response.getheader("Retry-After"))
                    self.assertTrue(json.loads(response.read())["retryable"])
                    connection.close()
                finally:
                    for _ in range(acquired):
                        server.upload_slots.release()
                    server.shutdown()
                    server.server_close()
                    thread.join(2)

    @staticmethod
    def report_payload(report_id: str, source: str | None, start: int, wire_bytes: int, name: str) -> dict:
        payload = {
            "schemaVersion": 1,
            "reportId": report_id,
            "summary": {},
            "playerTraffic": {},
            "trafficHistory": {
                "schemaVersion": 1,
                "periodStartMillis": start,
                "periodEndMillis": start + 1000,
                "generatedAtMillis": start + 1000,
                "zoneId": "UTC",
                "complete": True,
                "totals": {"outboundWireBytes": wire_bytes},
                "players": [],
                "hours": [{
                    "periodStartMillis": start,
                    "periodEndMillis": start + 1000,
                    "complete": True,
                    "totals": {"outboundWireBytes": wire_bytes},
                    "players": [{
                        "playerUuid": "00000000-0000-0000-0000-000000000001",
                        "playerName": name,
                        "outboundRawBytes": wire_bytes * 2,
                        "outboundWireBytes": wire_bytes,
                        "inboundRawBytes": 0,
                        "inboundWireBytes": 0,
                    }],
                }],
            },
        }
        if source is not None:
            payload["sourceFingerprint"] = source
        return payload

    @staticmethod
    def encode(payload: dict) -> bytes:
        return json.dumps(payload, separators=(",", ":")).encode("utf-8")

    @staticmethod
    def temporary_storage(directory: str):
        return TemporaryStorage(Path(directory))

    def request_socket(self) -> socket.socket:
        return self.request_socket_for(self.server)

    @staticmethod
    def request_socket_for(server: app.BoundedThreadingHTTPServer) -> socket.socket:
        connection = socket.create_connection(server.server_address, timeout=1)
        connection.sendall(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        return connection

    @staticmethod
    def post_socket_for(server: app.BoundedThreadingHTTPServer, body: bytes) -> socket.socket:
        connection = socket.create_connection(server.server_address, timeout=2)
        headers = (
            "POST /api/v1/reports HTTP/1.1\r\n"
            "Host: localhost\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n\r\n"
        ).encode("ascii")
        connection.sendall(headers + body)
        return connection

    @staticmethod
    def read_response(connection: socket.socket) -> bytes:
        response = bytearray()
        while True:
            chunk = connection.recv(65536)
            if not chunk:
                connection.close()
                return bytes(response)
            response.extend(chunk)

    @staticmethod
    def read_rejection(connection: socket.socket) -> bytes:
        try:
            return BostatsServerRegression.read_response(connection)
        except (ConnectionAbortedError, ConnectionResetError):
            connection.close()
            return b""

    @staticmethod
    def wait_for_entered(count: int, delay: float = 0) -> None:
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            with BlockingHandler.lock:
                if BlockingHandler.entered >= count:
                    if delay:
                        time.sleep(delay)
                    return
            time.sleep(0.01)
        raise AssertionError(f"handler did not reach {count} active requests")

    @staticmethod
    def wait_for_idle(server: app.BoundedThreadingHTTPServer, slots: int) -> None:
        acquired = 0
        try:
            for _ in range(slots):
                if not server.request_slots.acquire(timeout=2):
                    raise AssertionError("server workers did not become idle")
                acquired += 1
        finally:
            for _ in range(acquired):
                server.request_slots.release()

    @staticmethod
    def wait_for_waiting(server: app.BoundedThreadingHTTPServer, count: int) -> None:
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            with server.state_lock:
                if server.waiting_requests == count:
                    return
            time.sleep(0.01)
        raise AssertionError(f"server did not reach {count} waiting requests")


class RecordingOutput:
    def __init__(self) -> None:
        self.writes: list[bytes] = []

    def write(self, value: bytes) -> int:
        self.writes.append(bytes(value))
        return len(value)

    def flush(self) -> None:
        pass

    def body(self) -> bytes:
        separator = b"\r\n\r\n"
        joined = b"".join(self.writes)
        return joined.split(separator, 1)[1]


class TemporaryStorage:
    def __init__(self, root: Path) -> None:
        self.root = root

    def __enter__(self):
        self.previous = app.DATA_DIR, app.REPORT_DIR, app.DATABASE
        app.DATA_DIR = self.root
        app.REPORT_DIR = self.root / "reports"
        app.DATABASE = self.root / "metadata.db"
        app.initialize()
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        app.DATA_DIR, app.REPORT_DIR, app.DATABASE = self.previous


if __name__ == "__main__":
    unittest.main()
