"""Owned-container-only kernel fault matrix. No host networking, published ports or system-wide rules."""
import argparse
import datetime
import json
import pathlib
import subprocess
import time

ROOT = pathlib.Path('/out')
PORT = 25991
PROFILES = {
    'baseline': ([], []),
    'delay': (['delay', '40ms'], ['delay', '40ms']),
    'jitter': (['delay', '30ms', '15ms', 'distribution', 'normal'], ['delay', '30ms', '15ms', 'distribution', 'normal']),
    'loss-up': (['delay', '5ms', 'loss', 'random', '3%'], []),
    'loss-down': ([], ['delay', '5ms', 'loss', 'random', '3%']),
    'burst-loss': (['delay', '5ms', 'loss', 'gemodel', '1%', '25%', '100%', '0%'], []),
    'corrupt': (['corrupt', '1%'], ['corrupt', '1%']),
    'blackhole': ([], []),
    'rate-up': (['rate', '2mbit'], []),
    'duplicate': (['duplicate', '5%'], ['duplicate', '5%']),
    'reorder': (['delay', '20ms', 'reorder', '25%', '50%'], ['delay', '20ms', 'reorder', '25%', '50%']),
    'reset': ([], []),
    'deadline': ([], []),
}


def command(args):
    result = subprocess.run(args, stdin=subprocess.DEVNULL, text=True, capture_output=True, timeout=10)
    if result.returncode:
        raise RuntimeError(f'{args!r}: {result.stderr}')
    return result.stdout


def stats():
    return json.loads(command(['tc', '-s', '-j', 'qdisc', 'show', 'dev', 'lo']))


def shape(direction, options, replace=False):
    band = 1 if direction == 'up' else 2
    command(['tc', 'qdisc', 'change' if replace else 'add', 'dev', 'lo', 'parent', f'1:{band}',
             'handle', f'{band}0:', 'netem', 'limit', '2048', *options])


def wait_for(path, proc, timeout=10):
    end = time.monotonic() + timeout
    while not path.exists():
        if proc.poll() is not None:
            raise RuntimeError(f'Java exited {proc.returncode} before {path.name}')
        if time.monotonic() > end:
            raise TimeoutError(str(path))
        time.sleep(.01)


def verify(name, data, snapshots):
    server, client = data['server'], data['client']
    assert server['test_id'] == client['test_id']
    for report in (server, client):
        assert report['packet_loss_rate'] == 'unknown'
        assert not report['missing_replies_are_packet_loss']
        assert report['tcp_start']['status'] == 'supported', report['tcp_start']
        assert len(report['windows']) <= 350
        assert report['outcome'] != 'local_event_loop_stall', report['outcome']
    if name in ('reset', 'deadline'):
        assert server['outcome'] != 'completed' and client['outcome'] != 'completed'
        if name == 'reset':
            assert client['last_error_class'], 'RST has no exception evidence'
            assert client['disconnect_category'] == 'CONNECTION_RESET', client['disconnect_category']
            for report in (server, client):
                assert report['tcp_end']['status'] == 'closed'
                assert report['tcp_last_observed']['status'] == 'supported'
                assert report['analysis']['tcp_delta_excludes_unsampled_close_tail']
                assert report['tcp_last_observed_elapsed_ns'] < report['elapsed_ns']
        else:
            assert 'deadline' in server['outcome'] or 'deadline' in client['outcome']
    else:
        assert server['outcome'] == client['outcome'] == 'completed', (server['outcome'], client['outcome'])
        assert server['phases']['upload_received']['verified_bytes'] == 2_000_000
        assert client['phases']['download_received']['verified_bytes'] == 2_000_000
        assert server['phases']['download_peer_receipt']['peer_verified_bytes'] == 2_000_000
    up = client['analysis'].get('local_tcp_retransmitted_segments_delta')
    down = server['analysis'].get('local_tcp_retransmitted_segments_delta')
    dropped = max((q.get('drops', 0) for snap in snapshots for q in snap), default=0)
    if name in ('loss-up', 'blackhole', 'burst-loss'):
        assert dropped > 0 and up > 0, (dropped, up, down)
    if name == 'loss-down':
        assert dropped > 0 and down > 0, (dropped, up, down)
    latency = server['analysis']['application_rtt_by_phase'].get('BASELINE', {}).get('p50_ns')
    if name == 'delay':
        assert latency is not None and latency >= 60_000_000, latency
    if name == 'rate-up':
        assert 0 < server['phases']['upload_received']['receiver_goodput_mbit_s'] <= 2.5
        assert server['analysis']['loaded_application_p95_increased_over_20ms']
    if name == 'corrupt':
        assert up + down > 0, 'Corruption profile produced no observed TCP recovery evidence'
    return {'case': name, 'status': 'PASS', 'server_outcome': server['outcome'], 'client_outcome': client['outcome'],
            'up_retransmitted_segments': up, 'down_retransmitted_segments': down, 'qdisc_max_drops': dropped,
            'baseline_app_rtt_p50_ms': None if latency is None else latency / 1e6,
            'client_disconnect_category': client['disconnect_category'],
            'loaded_app_rtt_increased': server['analysis'].get('loaded_application_p95_increased_over_20ms'),
            'upload_mbit_s': server['phases'].get('upload_received', {}).get('receiver_goodput_mbit_s')}


def run_case(name):
    root = ROOT / name
    root.mkdir(exist_ok=False)
    snapshots, events = [], []
    proc = None
    try:
        command(['tc', 'qdisc', 'add', 'dev', 'lo', 'root', 'handle', '1:', 'prio', 'bands', '3'])
        for side, field in (('up', 'dport'), ('down', 'sport')):
            band = 1 if side == 'up' else 2
            shape(side, [])
            command(['tc', 'filter', 'add', 'dev', 'lo', 'parent', '1:', 'protocol', 'ip', 'prio', str(band),
                     'u32', 'match', 'ip', field, str(PORT), '0xffff', 'flowid', f'1:{band}'])
        with (root / 'java.log').open('w') as log:
            proc = subprocess.Popen(['java', '-Xmx256m', '-cp', '/runtime/classes:/runtime/lib/*',
                                     'com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeFaultRegressionMain',
                                     str(root), name], stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            wait_for(root / 'ready', proc)
            for side, options in zip(('up', 'down'), PROFILES[name]):
                shape(side, options, replace=True)
            events.append({'monotonic_s': time.monotonic(), 'event': 'armed', 'profile': PROFILES[name]})
            (root / 'go').touch(exist_ok=False)
            if name in ('blackhole', 'deadline'):
                wait_for(root / 'upload', proc)
                shape('up', ['loss', '100%'], replace=True)
                events.append({'monotonic_s': time.monotonic(), 'event': 'blackhole_begin'})
                until = time.monotonic() + (1.2 if name == 'blackhole' else 13.0)
                while time.monotonic() < until:
                    snapshots.append(stats())
                    time.sleep(.1)
                shape('up', [], replace=True)
                events.append({'monotonic_s': time.monotonic(), 'event': 'blackhole_end'})
            end = time.monotonic() + 42
            while proc.poll() is None and time.monotonic() < end:
                snapshots.append(stats())
                time.sleep(.1)
            if proc.poll() is None:
                raise TimeoutError('Java lifecycle timeout')
            assert proc.returncode == 0, f'Java exit {proc.returncode}, inspect {root}/java.log'
        data = json.loads((root / 'result.json').read_text())
        return verify(name, data, snapshots)
    finally:
        (root / 'injector.json').write_text(json.dumps({'events': events, 'qdisc_snapshots': snapshots}, indent=2))
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        command(['tc', 'qdisc', 'del', 'dev', 'lo', 'root'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', nargs='+', choices=list(PROFILES), default=list(PROFILES))
    args = parser.parse_args()
    # Refuse execution on a general-purpose Linux host; launcher uses an owned --network none container.
    assert pathlib.Path('/.dockerenv').exists(), 'Dedicated Docker container required'
    assert set(command(['ls', '/sys/class/net']).split()) == {'lo'}, 'Isolated loopback namespace required'
    (ROOT / 'environment.json').write_text(json.dumps({
        'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'kernel': command(['uname', '-r']).strip(), 'java': command(['java', '--version']).strip(),
        'tc': command(['tc', '-V']).strip(), 'links': json.loads(command(['ip', '-j', 'link', 'show'])),
        'scope': 'owned namespace; loopback skb drops are not physical NIC packet-loss ratios'}, indent=2))
    results = []
    for name in args.cases:
        try:
            row = run_case(name)
            results.append(row)
            print(json.dumps(row), flush=True)
        except Exception as error:
            results.append({'case': name, 'status': 'FAIL', 'error': repr(error)})
            (ROOT / 'summary.json').write_text(json.dumps(results, indent=2))
            raise
        (ROOT / 'summary.json').write_text(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
