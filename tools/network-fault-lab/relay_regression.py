"""Tests the existing Java relay's byte integrity, half-close, rate, bounded pending and RST."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import socket
import subprocess
import threading
import time

REPO = pathlib.Path(__file__).resolve().parents[2]
JAVA = pathlib.Path(r'C:\Program Files\Java\jdk-21\bin\java.exe')
RUNTIME = REPO / '.tmp/network-fault-lab/runtime'


def run_case(root, name, options, size=131072, reset=False, minimum=0):
    expected = bytes(range(256)) * (size // 256)
    server = socket.socket()
    server.bind(('127.0.0.1', 0))
    server.listen(1)
    server.settimeout(8)
    target = server.getsockname()[1]
    errors = []
    received = []

    def echo():
        try:
            with server.accept()[0] as peer:
                peer.settimeout(8)
                data = bytearray()
                while True:
                    block = peer.recv(16384)
                    if not block:
                        break
                    data.extend(block)
                    assert len(data) <= size, 'Unexpected extra bytes'
                received.append(bytes(data))
                peer.sendall(data)
                peer.shutdown(socket.SHUT_WR)
        except Exception as error:
            errors.append(repr(error))
        finally:
            server.close()

    thread = threading.Thread(target=echo, daemon=True)
    thread.start()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    logpath = root / (name + '.log')
    proc = None
    started = time.monotonic()
    try:
        with logpath.open('w', encoding='utf-8') as log:
            proc = subprocess.Popen([str(JAVA), '-cp', str(RUNTIME / 'classes') + os.pathsep + str(RUNTIME / 'lib/*'),
                                     'com.PinkCats.bandwidthoptimizer.test.LatencyTcpProxyMain',
                                     '--listen-port', str(port), '--target-port', str(target), *options],
                                    stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            end = time.monotonic() + 5
            while 'listening ' not in logpath.read_text(encoding='utf-8'):
                if proc.poll() is not None or time.monotonic() > end:
                    raise RuntimeError('Relay did not become ready')
                time.sleep(.02)
            with socket.create_connection(('127.0.0.1', port), timeout=7) as client:
                client.settimeout(7)
                started = time.monotonic()
                client.sendall(expected)
                if reset:
                    try:
                        data = client.recv(1)
                    except ConnectionResetError:
                        data = None
                    assert data is None, 'Expected real socket RST'
                else:
                    client.shutdown(socket.SHUT_WR)
                    actual = bytearray()
                    while True:
                        block = client.recv(16384)
                        if not block:
                            break
                        actual.extend(block)
                        assert len(actual) <= size
                    assert bytes(actual) == expected, (len(actual), size)
                    assert time.monotonic() - started >= minimum, 'Rate limit did not delay transfer'
            thread.join(timeout=2)
            if not reset:
                assert not thread.is_alive() and not errors, errors
                assert received == [expected], 'Upstream half-close/integrity mismatch'
            # Let both relay pumps drain and release their reservations before reading queue evidence.
            end = time.monotonic() + 2
            while logpath.read_text(encoding='utf-8').count('peakPendingBytes=') < 2 and time.monotonic() < end:
                time.sleep(.02)
            peaks = [int(x) for x in re.findall(r'peakPendingBytes=(\d+)', logpath.read_text(encoding='utf-8'))]
            if not reset:
                assert len(peaks) == 2 and max(peaks) <= 512 * 1024, peaks
            return {'case': name, 'status': 'PASS', 'bytes_each_way': 0 if reset else size,
                    'sha256': hashlib.sha256(expected).hexdigest(), 'elapsed_s': time.monotonic() - started,
                    'queue_peak_bytes': peaks, 'mode': 'TCP terminating relay, not kernel loss'}
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=5)
        server.close()
        thread.join(timeout=9)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--expect-legacy-failure', action='store_true')
    args = parser.parse_args()
    root = REPO / '.tmp/network-fault-lab/relay' / str(time.time_ns())
    root.mkdir(parents=True, exist_ok=False)
    rows = []
    try:
        rows.append(run_case(root, 'half-close', ['--delay-ms', '40']))
        if args.expect_legacy_failure:
            raise AssertionError('Legacy regression did not reproduce')
        rows.append(run_case(root, 'jitter-rate', ['--delay-ms', '20', '--jitter-ms', '10', '--rate-bytes-per-second', '65536'], minimum=3))
        rows.append(run_case(root, 'bounded-pressure', ['--delay-ms', '200'], size=2*1024*1024))
        rows.append(run_case(root, 'reset', ['--delay-ms', '0', '--reset-first-connection-after-ms', '250'], reset=True))
    except Exception as error:
        if args.expect_legacy_failure and not isinstance(error, TimeoutError):
            raise
        rows.append({'status': 'EXPECTED_LEGACY_FAILURE' if args.expect_legacy_failure else 'FAIL', 'error': repr(error)})
        if not args.expect_legacy_failure:
            raise
    finally:
        (root / 'result.json').write_text(json.dumps(rows, indent=2))
        print('RELAY_OUTPUT=' + str(root), flush=True)
        print(json.dumps(rows), flush=True)


if __name__ == '__main__':
    main()
