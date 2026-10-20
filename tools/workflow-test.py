#!/usr/bin/env python3
"""Verify devtools, automatic reload, UI recovery and host lifetime on a real device.

Usage: python3 tools/workflow-test.py --serial DEVICE
Sample source is restored on exit. No manual reload command is invoked.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

REPOSITORY = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    args = parser.parse_args()
    if not args.serial:
        parser.error('Set --serial or ANDROID_SERIAL')

    spec = importlib.util.spec_from_file_location('device_test', Path(__file__).with_name('device-test.py'))
    regression = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(regression)
    device = regression.Device(args.serial)

    subprocess.run([
        str(REPOSITORY / 'gradlew'), ':hot-reload-android-host:prepareWorkflowProbe', '--console=plain'
    ], cwd=REPOSITORY, check=True)
    classpath = (REPOSITORY / 'modules/host/build/workflow-probe/classpath.txt').read_text()
    evidence = regression.SAMPLE / 'build/device-test-evidence' / f'workflow-{time.time_ns()}'
    evidence.mkdir(parents=True)
    print(f'Evidence: {evidence}', flush=True)
    original = regression.MAIN.read_text()
    environment = dict(os.environ, ANDROID_SERIAL=args.serial)
    results = []
    host_pid = None
    probe = None
    probe_log = None
    regression.HOST_LOG.unlink(missing_ok=True)
    run_log = (evidence / 'hotRun.log').open('w')
    run = subprocess.Popen([
        str(regression.SAMPLE / 'gradlew'), '-p', str(regression.SAMPLE), '--console=plain',
        'hotRunDebug', '--auto'
    ], env=environment, stdout=run_log, stderr=subprocess.STDOUT, start_new_session=True)
    orchestration_log = evidence / 'orchestration.log'

    def wait(condition, description, timeout=180):
        deadline = time.time() + timeout
        while time.time() < deadline:
            assert run.poll() is None, f'hotRun exited: {run.returncode}; see {evidence}'
            if condition():
                return
            time.sleep(1)
        raise AssertionError(f'Timed out: {description}')

    def passed(name, note):
        results.append(dict(name=name, result='PASS', note=note))
        print(f'PASS: {name} ({note})', flush=True)

    def send(command):
        probe.stdin.write(command + '\n')
        probe.stdin.flush()

    try:
        wait(lambda: regression.HOST_LOG.exists() and 'Connected to' in regression.HOST_LOG.read_text(),
             'host connection', timeout=600)
        properties = dict(line.split('=', 1) for line in regression.PID_FILE.read_text().splitlines() if '=' in line)
        host_pid = int(properties['pid'])
        device.wait_for(lambda texts: any(text.startswith('Count: ') for text in texts), 'initial UI', timeout=60)
        probe_log = orchestration_log.open('w')
        probe = subprocess.Popen([
            'java', '-cp', classpath, 'org.jetbrains.compose.reload.android.host.tooling.WorkflowProbeKt',
            properties['orchestration.port']
        ], stdin=subprocess.PIPE, stdout=probe_log, stderr=subprocess.STDOUT, text=True)
        wait(lambda: 'PROBE_READY' in orchestration_log.read_text(), 'probe connection')
        processes = subprocess.check_output(['ps', '-Ao', 'pid=,ppid=,command='], text=True)
        assert any('org.jetbrains.compose.devtools.Main' in line and line.split()[1] == str(host_pid)
                   for line in processes.splitlines()), 'This host has no devtools child process'
        passed('devtools orchestration', 'Upstream devtools is running; tooling client connected')

        for _ in range(3):
            device.tap_text('Count: ')
        before = device.count()
        regression.MAIN.write_text(original.replace('"Hello Android Hot Reload"', '"Auto architecture"'))
        device.wait_for(lambda texts: any('Auto architecture' in text for text in texts),
                        'automatic reload', timeout=180, fail_on_reload=True)
        assert device.count() == before, 'Automatic reload lost state'
        wait(lambda: 'ReloadClassesResult success=true' in orchestration_log.read_text()
             and 'MESSAGE UIRendered' in orchestration_log.read_text(), 'reload and UI messages')
        passed('automatic reload', 'Source save triggered compilation and reload; state preserved')

        send('clean')
        device.wait_for(lambda texts: 'Count: 0' in texts, 'CleanCompositionRequest', timeout=60)
        passed('clean composition', 'Upstream request resets the counter')

        regression.MAIN.write_text(original.replace('"Hello Android Hot Reload"',
                                                    'error("Workflow composition failure")'))
        wait(lambda: 'MESSAGE UIException' in orchestration_log.read_text(), 'UIException')
        assert device.adb('shell', 'pidof', regression.APP, check=False).strip(), 'UI error killed the application'
        passed('UI exception reporting', 'Composition failure reported; application remains alive')

        regression.MAIN.write_text(original.replace('"Hello Android Hot Reload"', '"Recovered architecture"'))
        device.wait_for(lambda texts: any('Recovered architecture' in text for text in texts),
                        'automatic UI recovery', timeout=180)
        passed('composition recovery', 'Fixing source automatically restores rendering')

        for _ in range(2):
            device.tap_text('Count: ')
        send('retry')
        device.wait_for(lambda texts: 'Count: 0' in texts, 'RetryFailedCompositionRequest', timeout=60)
        passed('retry composition request', 'Upstream request rebuilds the recovered composition')

        os.kill(host_pid, signal.SIGTERM)
        host_pid = None
        deadline = time.time() + 30
        while time.time() < deadline and device.adb('shell', 'pidof', regression.APP, check=False).strip():
            time.sleep(1)
        assert not device.adb('shell', 'pidof', regression.APP, check=False).strip(), 'App survived host shutdown'
        passed('host lifetime', 'Stopping the host terminates the application')
    except Exception as failure:
        results.append(dict(name='workflow', result='FAIL', note=str(failure)))
        raise
    finally:
        if host_pid is not None:
            try:
                os.kill(host_pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        if probe is not None:
            try:
                send('quit')
                probe.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                probe.terminate()
                probe.wait(timeout=10)
        if run.poll() is None:
            os.killpg(run.pid, signal.SIGTERM)
            try:
                run.wait(timeout=30)
            except subprocess.TimeoutExpired:
                run.kill()
                run.wait(timeout=10)
        regression.MAIN.write_text(original)
        run_log.close()
        if probe_log is not None:
            probe_log.close()
        if regression.HOST_LOG.exists():
            (evidence / 'host.log').write_text(regression.HOST_LOG.read_text())
        (evidence / 'logcat.txt').write_text(device.adb('logcat', '-d', '-s', 'ComposeHotReload:*', check=False))
        (evidence / 'summary.json').write_text(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
