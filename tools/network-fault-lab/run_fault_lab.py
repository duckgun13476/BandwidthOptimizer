"""Run already-built Linux TCP fault fixtures locally, with bounded resources and owned cleanup."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import uuid

REPO = pathlib.Path(__file__).resolve().parents[2]
DOCKER = pathlib.Path(r'C:\Program Files\Docker\Docker\resources\bin\docker.exe')
IMAGE = 'bo-network-fault-lab:20261025'
CASES = ['baseline', 'delay', 'jitter', 'loss-up', 'loss-down', 'burst-loss', 'corrupt', 'blackhole', 'rate-up', 'duplicate', 'reorder', 'reset', 'deadline']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', nargs='+', choices=CASES, default=CASES)
    args = parser.parse_args()
    if not DOCKER.is_file():
        raise RuntimeError(f'Docker CLI missing: {DOCKER}')
    runtime = (REPO / '.tmp/network-fault-lab/runtime').resolve(strict=True)
    if not runtime.is_relative_to(REPO):
        raise RuntimeError('Runtime outside repo')
    assert (runtime / 'classes/com/PinkCats/bandwidthoptimizer/networkprobe/NetworkProbeFaultRegressionMain.class').is_file()
    image = subprocess.check_output([str(DOCKER), 'image', 'inspect', IMAGE, '--format', '{{.Id}}'], text=True).strip()
    identity = uuid.uuid4().hex[:12]
    output = (REPO / '.tmp/network-fault-lab/runs' / (datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + identity)).resolve()
    if not output.is_relative_to(REPO / '.tmp/network-fault-lab/runs'):
        raise RuntimeError('Output outside owned run root')
    output.mkdir(parents=True, exist_ok=False)
    sources = {str(p.relative_to(runtime)): hashlib.sha256(p.read_bytes()).hexdigest() for p in runtime.rglob('*') if p.is_file()}
    name = 'bo-netfault-' + identity
    command = [str(DOCKER), 'run', '--rm', '--name', name, '--label', 'bo.fault.owner=' + identity,
               '--network', 'none', '--hostname', 'bo-network-lab', '--add-host', 'bo-network-lab:127.0.0.1',
               '--cap-drop', 'ALL', '--cap-add', 'NET_ADMIN', '--read-only',
               '--tmpfs', '/tmp:rw,exec,nosuid,nodev,size=64m', '--memory', '512m', '--cpus', '2', '--pids-limit', '128',
               '--security-opt', 'no-new-privileges', '--mount', f'type=bind,source={runtime},target=/runtime,readonly',
               '--mount', f'type=bind,source={pathlib.Path(__file__).parent.resolve()},target=/lab,readonly',
               '--mount', f'type=bind,source={output},target=/out', image, '--cases', *args.cases]
    manifest = {'image': image, 'container': name, 'cases': args.cases, 'runtime_sha256': sources,
                'namespace': 'network none; own loopback only', 'status': 'RUNNING'}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print('FAULT_LAB_OUTPUT=' + str(output), flush=True)
    proc = None
    try:
        with (output / 'container.log').open('w', encoding='utf-8') as log:
            proc = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
            for line in proc.stdout:
                log.write(line)
                log.flush()
                print(line, end='', flush=True)
            code = proc.wait(timeout=10)
        manifest['exit_code'] = code
        manifest['status'] = 'PASS' if code == 0 else 'FAIL'
        if code:
            raise RuntimeError('Fault matrix failed; inspect ' + str(output))
    finally:
        # UUID name plus label proves ownership before any stop; never touches other Docker workloads.
        found = subprocess.run([str(DOCKER), 'inspect', name, '--format', '{{index .Config.Labels "bo.fault.owner"}}'], capture_output=True, text=True, timeout=10)
        if found.returncode == 0:
            if found.stdout.strip() != identity:
                raise RuntimeError('Container identity changed; refusing stop')
            subprocess.run([str(DOCKER), 'stop', '--time', '5', name], stdin=subprocess.DEVNULL, capture_output=True, timeout=15, check=True)
        manifest['owned_container_removed'] = subprocess.run([str(DOCKER), 'inspect', name], capture_output=True, timeout=10).returncode != 0
        if manifest['status'] == 'RUNNING':
            manifest['status'] = 'INTERRUPTED'
        (output / 'manifest.json').write_text(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
