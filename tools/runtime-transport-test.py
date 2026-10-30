#!/usr/bin/env python3
"""Targeted device socket acceptance. Run with no hotRun host attached; never uninstalls/restarts an app."""
import argparse
import json
import re
from pathlib import Path
import socket
import struct
import subprocess
import time


class EnvironmentChanged(AssertionError):
    """The foreground precondition changed; this is not a transport acceptance result."""


def exact(connection, count):
    data = bytearray()
    while len(data) < count:
        part = connection.recv(count - len(data))
        if not part:
            raise AssertionError('Unexpected socket EOF')
        data.extend(part)
    return bytes(data)


def hello(connection, package, pid):
    connection.sendall(struct.pack('>ii', 0x43485241, 3))
    assert exact(connection, 8) == struct.pack('>ii', 0x43485241, 3), 'Handshake mismatch'
    kind, size = struct.unpack('>ii', exact(connection, 8))
    assert kind == 100 and 0 <= size <= 65536, 'Invalid Hello frame'
    data = exact(connection, size)
    version, actual_pid, length = struct.unpack('>iii', data[:12])
    assert version == 3 and actual_pid == pid, 'Runtime process changed'
    assert data[12:12 + length].decode() == package, 'Wrong runtime'
    assert data[12 + length:] == b'\0', 'Runtime not available'


def await_close(connection, seconds, check_environment=None):
    deadline = time.monotonic() + seconds
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, 'Peer did not close within its deadline'
        if check_environment is not None:
            check_environment()
        connection.settimeout(min(remaining, 2) if check_environment else remaining)
        try:
            data = connection.recv(65536)
        except socket.timeout:
            if check_environment is not None:
                continue
            raise
        except ConnectionResetError:
            return
        if not data:
            return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--device-id', required=True)
    parser.add_argument('--package', default='dev.compose.hotreload.framework')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', choices=['all', 'handshake', 'partial-frame', 'payload-limit'], default='all')
    args = parser.parse_args()
    try:
        args.output.resolve().relative_to(Path(__file__).resolve().parents[1] / 'build/evidence')
    except ValueError:
        parser.error('--output must be inside build/evidence')
    if not re.fullmatch('[a-z0-9-]+', args.device_id) or args.device_id == args.serial:
        parser.error('Supply a separate anonymous --device-id')
    # Raw ADB output stays in ignored evidence; reports use only the supplied anonymous label.
    try:
        args.output.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        parser.error('--output already exists; choose a new directory to preserve earlier evidence')

    def adb(*command, check=True):
        result = subprocess.run(['adb', '-s', args.serial, *command], capture_output=True, text=True, timeout=60)
        with (args.output / 'adb.log').open('a') as log:
            log.write(result.stdout + result.stderr)
        if check:
            assert result.returncode == 0, 'ADB command failed; see local evidence'
        return result.stdout.strip()

    report = {'device': args.device_id, 'checks': [], 'sameProcess': False}
    port = None
    stage = 'setup'
    try:
        pid = int(adb('shell', 'pidof', args.package))
        report.update(api=adb('shell', 'getprop', 'ro.build.version.sdk'),
                      model=adb('shell', 'getprop', 'ro.product.model'))
        apk = adb('shell', 'pm', 'path', args.package).removeprefix('package:')
        report['apkSha256'] = adb('shell', 'sha256sum', apk).split()[0]
        port = int(adb('forward', 'tcp:0', 'localabstract:' + args.package + '.compose-hot-reload'))

        last_environment_check = 0.0

        def check_environment():
            nonlocal last_environment_check
            now = time.monotonic()
            if now - last_environment_check < 5:
                return
            last_environment_check = now
            activities = adb('shell', 'dumpsys', 'activity', 'activities')
            foreground = re.findall(r'topResumedActivity=ActivityRecord[^\n]+', activities)
            if not any(args.package + '/' in line for line in foreground):
                raise EnvironmentChanged('Fixture is no longer the foreground Activity')
            if adb('shell', 'pidof', args.package, check=False) != str(pid):
                raise EnvironmentChanged('Original process was replaced')

        def connect():
            check_environment()
            return socket.create_connection(('127.0.0.1', port), timeout=10)

        def passed(name):
            assert int(adb('shell', 'pidof', args.package)) == pid, 'Original process was replaced'
            report['checks'].append({'name': name, 'result': 'PASS'})
            print('PASS ' + args.device_id + ' ' + name, flush=True)

        if args.case in ('all', 'handshake'):
            stage = 'silent-handshake-released'
            with connect() as connection:
                # An authorized but silent peer must not monopolize the sole runtime accept loop.
                await_close(connection, 10, check_environment)
            passed(stage)
            stage = 'partial-handshake-released'
            with connect() as connection:
                connection.sendall(b'\x43')
                await_close(connection, 10, check_environment)
            passed(stage)
        if args.case in ('all', 'partial-frame'):
            stage = 'idle-preserved-partial-frame-released'
            with connect() as connection:
                hello(connection, args.package, pid)
                # Empty idle time must not expire; partial frames have a separate absolute deadline.
                time.sleep(6)
                connection.sendall(b'\x00')
                started = time.monotonic()
                try:
                    await_close(connection, 135, check_environment)
                finally:
                    report['partialFrameSeconds'] = round(time.monotonic() - started, 3)
                assert report['partialFrameSeconds'] >= 110, 'Idle or a stale handshake timer closed the session prematurely'
            passed(stage)
        if args.case in ('all', 'payload-limit'):
            # Only headers are sent: rejection must precede body allocation or any reset/reload.
            for kind, size, name in [(1, 64 * 1024 * 1024 + 1, 'oversized-reload-header-rejected'),
                                     (2, 2, 'oversized-reset-header-rejected')]:
                stage = name
                with connect() as connection:
                    hello(connection, args.package, pid)
                    connection.sendall(struct.pack('>ii', kind, size))
                    await_close(connection, 10, check_environment)
                passed(stage)
        stage = 'subsequent-handshake-same-process'
        with connect() as connection:
            hello(connection, args.package, pid)
        passed('subsequent-handshake-same-process')
        assert adb('shell', 'sha256sum', apk).split()[0] == report['apkSha256'], 'APK replaced'
        report['sameProcess'] = True
    except Exception as error:
        result = 'INVALID' if isinstance(error, EnvironmentChanged) else 'FAIL'
        report['checks'].append({'name': stage, 'result': result, 'errorType': type(error).__name__})
        print(result + ' ' + args.device_id + ' transport; see local evidence', flush=True)
    finally:
        if port is not None:
            try:
                adb('forward', '--remove', 'tcp:' + str(port))
            except Exception as error:
                report['cleanupErrorType'] = type(error).__name__
                report['sameProcess'] = False
        (args.output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['sameProcess'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
