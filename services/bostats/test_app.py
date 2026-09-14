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
        with self.lock:
            type(self).entered += 1
        self.release.wait(2)
        self.send_response(204)
        self.end_headers()

    def log_message(self, message: str, *args: object) -> None:
        pass


class QuietServer(app.BoundedThreadingHTTPServer):
    def handle_error(self, request, client_address) -> None:
        pass


class BostatsServerRegression(unittest.TestCase):
    def setUp(self) -> None:
        BlockingHandler.release.clear()
        BlockingHandler.entered = 0
        self.server = QuietServer(("127.0.0.1", 0), BlockingHandler, 2, 0.2)
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
        self.assertEqual(b"", self.read_rejection(rejected))
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
        self.assertEqual(b"", self.read_rejection(rejected))
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
                server = QuietServer(("127.0.0.1", 0), app.Handler, 4, 1)
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
            finally:
                if server is not None:
                    server.shutdown()
                    self.wait_for_idle(server, 4)
                    server.server_close()
                if thread is not None:
                    thread.join(2)
                app.DATA_DIR, app.REPORT_DIR, app.DATABASE = old_data_dir, old_report_dir, old_database

    def request_socket(self) -> socket.socket:
        connection = socket.create_connection(self.server.server_address, timeout=1)
        connection.sendall(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
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


if __name__ == "__main__":
    unittest.main()
