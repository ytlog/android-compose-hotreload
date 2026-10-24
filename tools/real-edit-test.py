#!/usr/bin/env python3
"""Continuous Kotlin -> D8 -> live Compose edits, restoring sources on exit.

python3 tools/real-edit-test.py --serial DEVICE --device-id local-apiNN \
    --art-dcevm-version VERSION [--scenario-group business|rendering|callbacks|recovery|full]
Raw output stays in ignored build/evidence/. No reinstall or restart between edits.
"""
import argparse
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / 'sample'
SOURCE = SAMPLE / 'src/main/java/dev/compose/hotreload/sample'
LOGIC = SOURCE / 'EditLogic.kt'
SCREEN = SOURCE / 'RealEditScreen.kt'
MODEL = SOURCE / 'RealEditModel.kt'
ADDED = SOURCE / 'EditFormatter.kt'
APP = 'dev.compose.hotreload.sample'
ACTIVITY = APP + '/.RealEditActivity'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--device-id', required=True)
    parser.add_argument('--art-dcevm-version', required=True)
    parser.add_argument('--scenario-group', choices=['business', 'rendering', 'callbacks', 'recovery', 'full'], default='full')
    args = parser.parse_args()
    if not args.serial:
        parser.error('Set --serial or ANDROID_SERIAL')
    if not re.fullmatch(r'[a-z0-9-]+', args.device_id) or args.device_id == args.serial:
        parser.error('--device-id must be an anonymous lowercase label')
    if ADDED.exists():
        parser.error('Refusing to overwrite EditFormatter.kt')
    spec = importlib.util.spec_from_file_location('device_test', ROOT / 'tools/device-test.py')
    regression = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(regression)
    device = regression.Device(args.serial)
    environment = dict(os.environ, ANDROID_SERIAL=args.serial,
                       ORG_GRADLE_PROJECT_artDcevmVersion=args.art_dcevm_version)
    evidence = ROOT / 'build/evidence' / f'real-edits-{args.device_id}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    print('Evidence: ' + str(evidence.relative_to(ROOT)), flush=True)
    originals = {path: path.read_bytes() for path in [LOGIC, SCREEN, MODEL]}
    results = []
    metadata = {'device': args.device_id, 'engineVersion': args.art_dcevm_version,
                'group': args.scenario_group, 'sourceCommit': subprocess.check_output(
                    ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'sources': {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in [LOGIC, SCREEN, MODEL]}}
    candidates = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD'], cwd=ROOT, text=True).splitlines()
    candidates += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=ROOT, text=True).splitlines()
    metadata['changedSources'] = {p: sha((ROOT / p).read_bytes()) for p in sorted(set(candidates)) if (ROOT / p).is_file()}
    host_pid = None
    run = None
    app_pid = None
    probe = None
    probe_log = None
    orchestration = evidence / 'orchestration.log'
    expected = {'input': 'draft42', 'page': 'Item B', 'operations': 1, 'callbackCalls': 0}
    case_dir = evidence

    def wait(condition, description, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            assert run.poll() is None, 'hotRun exited; inspect hotRun.log'
            if condition():
                return
            time.sleep(1)
        raise AssertionError('Timed out: ' + description)

    def snapshot():
        texts = device.texts()
        (case_dir / 'ui.json').write_text(json.dumps(texts, indent=2))
        return texts

    def value(texts, prefix):
        return next(t[len(prefix):] for t in texts if t.startswith(prefix))

    def invariant(listener=True):
        nonlocal listener_id
        assert device.adb('shell', 'pidof', APP).strip() == app_pid, 'Process changed'
        texts = snapshot()
        assert value(texts, 'Model: ') == model_id, 'ViewModel replaced'
        assert value(texts, 'Remember: ') == remember_id, 'Unrelated remember state lost'
        if listener:
            assert 'Listener retained: true' in texts, 'Listener reference differs from registration anchor'
            current_identity = value(texts, 'Listener: ')
            if current_identity != listener_id:
                event = {'name': 'listener-identity-hash', 'result': 'FAIL', 'stage': case_dir.name,
                         'before': listener_id, 'after': current_identity, 'referenceEqualityRetained': True}
                results.append(event)
                (case_dir / 'identity-hash-change.json').write_text(json.dumps(event, indent=2))
                print('FAIL: listener-identity-hash (reference equality retained)', flush=True)
                listener_id = current_identity
        assert 'Draft value: ' + expected['input'] in texts, 'Input lost'
        assert 'Page: ' + expected['page'] in texts, 'Detail selection lost'
        assert 'Operations: ' + str(expected['operations']) in texts, 'Business object state lost'
        assert 'Callback calls: ' + str(expected['callbackCalls']) in texts, 'Callback duplicated or lost'
        return texts

    def common():
        # No concurrent load on this device. Request GC, background and resume this same Activity.
        texts = invariant()
        gc = int(value(texts, 'GC: '))
        device.tap_text('GC: ')
        device.wait_for(lambda ts: 'GC: ' + str(gc + 1) in ts, 'GC action')
        device.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        device.adb('shell', 'am', 'start', '-W', '-f', '0x20000000', '-n', ACTIVITY)
        invariant()
        with (case_dir / 'screen.png').open('wb') as image:
            subprocess.run(['adb', '-s', args.serial, 'exec-out', 'screencap', '-p'], stdout=image, check=True)

    def trigger(name):
        nonlocal case_dir
        case_dir = evidence / name
        case_dir.mkdir()
        diff = ''.join(''.join(difflib.unified_diff(
            original.decode().splitlines(True), path.read_text().splitlines(True),
            fromfile=str(path.relative_to(ROOT)), tofile=str(path.relative_to(ROOT))))
                       for path, original in originals.items())
        if ADDED.exists():
            diff += ADDED.read_text()
        (case_dir / 'source.patch').write_text(diff)
        offset = orchestration.stat().st_size
        host_offset = regression.HOST_LOG.stat().st_size
        start = time.monotonic()
        with (case_dir / 'gradle.log').open('w') as log:
            completed = subprocess.run([str(SAMPLE / 'gradlew'), '-p', str(SAMPLE), '--console=plain',
                                        'reload', '-Pcompose.reload.devToolsHeadless=true'],
                                       env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        assert completed.returncode == 0, 'Compilation failed; inspect gradle.log'
        classes = SAMPLE / 'build/tmp/kotlin-classes/debug'
        compiled = {}
        for path in classes.rglob('*.class'):
            relative = path.relative_to(classes)
            target = case_dir / 'classes' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            compiled[str(relative)] = sha(path.read_bytes())
        (case_dir / 'class-hashes.json').write_text(json.dumps(compiled, indent=2))
        wait(lambda: 'ReloadClassesResult' in orchestration.read_text()[offset:], 'reload response')
        host = orchestration.read_text()[offset:]
        (case_dir / 'orchestration.log').write_text(host)
        (case_dir / 'host.log').write_text(regression.HOST_LOG.read_text()[host_offset:])
        elapsed = time.monotonic() - start
        return host, elapsed

    def accepted(name, edits, action=None, visible=(), probe_callback=False):
        for path, old, new in edits:
            regression.edit(path, old, new)
        host, elapsed = trigger(name)
        assert 'ReloadClassesResult success=true' in host, 'Reload rejected; inspect host.log'
        if visible:
            device.wait_for(lambda ts: all(v in ts for v in visible), name, timeout=60)
        failure = None
        if action:
            try:
                action()
            except AssertionError as error:
                if not probe_callback:
                    raise
                failure = str(error)
                (case_dir / 'callback-failure.txt').write_text(failure)
        common()
        result = {'name': name, 'result': 'FAIL' if failure else 'PASS', 'reloadSeconds': round(elapsed, 3),
                  'reloadAccepted': True}
        if failure:
            result['error'] = failure
        results.append(result)
        print(result['result'] + ': ' + name, flush=True)

    def apply(expected_result):
        expected['operations'] += 1
        device.tap_text('Apply logic')
        device.wait_for(lambda ts: 'Result: ' + expected_result in ts, 'new business call')

    def callback(expected_result):
        expected['callbackCalls'] += 1
        device.tap_text('Call listener')
        texts = device.wait_for(lambda ts: 'Callback calls: ' + str(expected['callbackCalls']) in ts, 'callback completion')
        actual = value(texts, 'Callback: ')
        assert actual == expected_result, f'Expected {expected_result}; observed {actual}'

    def register_again(name, expected_result):
        nonlocal case_dir, listener_id
        case_dir = evidence / name
        case_dir.mkdir()
        previous = listener_id
        device.tap_text('Register again')
        texts = device.wait_for(lambda ts: value(ts, 'Listener: ') != previous, 'explicit listener registration')
        listener_id = value(texts, 'Listener: ')
        callback(expected_result)
        common()
        results.append({'name': name, 'result': 'PASS', 'explicitRegistration': True})
        print('PASS: ' + name, flush=True)

    run_log = (evidence / 'hotRun.log').open('w')
    try:
        device.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        device.adb('shell', 'wm', 'dismiss-keyguard')
        with (evidence / 'prepare-probe.log').open('w') as log:
            subprocess.run([str(ROOT / 'gradlew'), ':hot-reload-android-host:prepareWorkflowProbe', '--console=plain'],
                           cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        regression.HOST_LOG.unlink(missing_ok=True)
        run = subprocess.Popen([str(SAMPLE / 'gradlew'), '-p', str(SAMPLE), '--console=plain',
                                'hotRunDebug', '-Pcompose.reload.devToolsHeadless=true'],
                               env=environment, stdout=run_log, stderr=subprocess.STDOUT, start_new_session=True)
        wait(lambda: regression.HOST_LOG.exists() and 'Connected to' in regression.HOST_LOG.read_text(),
             'host connection', timeout=600)
        host_pid = int(re.search(r'^pid=(\d+)$', regression.PID_FILE.read_text(), re.MULTILINE).group(1))
        properties = dict(line.split('=', 1) for line in regression.PID_FILE.read_text().splitlines() if '=' in line)
        classpath = (ROOT / 'modules/host/build/workflow-probe/classpath.txt').read_text()
        probe_log = orchestration.open('w')
        probe = subprocess.Popen(['java', '-cp', classpath,
                                  'org.jetbrains.compose.reload.android.host.tooling.WorkflowProbeKt',
                                  properties['orchestration.port']], stdin=subprocess.PIPE,
                                 stdout=probe_log, stderr=subprocess.STDOUT, text=True)
        wait(lambda: 'PROBE_READY' in orchestration.read_text(), 'orchestration observer')
        app_pid = device.adb('shell', 'pidof', APP).strip()
        device.adb('shell', 'am', 'start', '-W', '-n', ACTIVITY)
        device.wait_for(lambda ts: 'Real edits v1' in ts, 'real edit page', timeout=60)
        apk_dir = SAMPLE / 'build/outputs/apk/debug'
        apk_info = json.loads((apk_dir / 'output-metadata.json').read_text())
        assert len(apk_info['elements']) == 1, 'Expected a single arm64 APK'
        apk = apk_dir / apk_info['elements'][0]['outputFile']
        aar = Path.home() / '.m2/repository/io/github/ytlog/artdcevm' / args.art_dcevm_version / f'artdcevm-{args.art_dcevm_version}.aar'
        with zipfile.ZipFile(apk) as archive:
            apk_engine = archive.read('lib/arm64-v8a/libartdcevm.so')
        with zipfile.ZipFile(aar) as archive:
            aar_engine = archive.read('jni/arm64-v8a/libartdcevm.so')
        assert sha(apk_engine) == sha(aar_engine), 'APK did not package the Maven Local engine'
        installed = device.adb('shell', 'pm', 'path', APP).strip().removeprefix('package:')
        installed_hash = device.adb('shell', 'sha256sum', installed).split()[0]
        assert installed_hash == sha(apk.read_bytes()), 'Installed APK hash differs'
        metadata.update(apkSha256=installed_hash, aarSha256=sha(aar.read_bytes()), engineSha256=sha(aar_engine),
                        api=device.adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
                        model=device.adb('shell', 'getprop', 'ro.product.model').strip(),
                        abi=device.adb('shell', 'getprop', 'ro.product.cpu.abi').strip(),
                        checkJni=device.adb('shell', 'getprop', 'debug.checkjni').strip())
        (evidence / 'startup.logcat').write_text(device.logcat())
        # Enter through the actual TextField; do not seed application state through a test API.
        xml = device.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        field = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert field, 'Missing editable input field'
        x1, y1, x2, y2 = map(int, field.groups())
        device.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        device.adb('shell', 'input', 'text', expected['input'])
        device.wait_for(lambda ts: 'Draft value: ' + expected['input'] in ts, 'typed input')
        device.tap_text('Item B')
        device.tap_text('Apply logic')
        callback('callback-v1:draft42')
        texts = snapshot()
        model_id, remember_id, listener_id = (value(texts, p) for p in ['Model: ', 'Remember: ', 'Listener: '])
        invariant()
        group = args.scenario_group
        if group in ['business', 'full']:
            accepted('business-body', [(LOGIC, '"business-v1:${input.trim()}"', '"business-v2:${input.uppercase()}"')],
                     action=lambda: apply('business-v2:DRAFT42'))
            accepted('branch-condition', [(LOGIC, 'input.isBlank()', 'input.startsWith("draft")')],
                     action=lambda: apply('empty-v1'))
            accepted('branch-and-helper', [(LOGIC, 'input.startsWith("draft")', 'input.length < 3'),
                                          (LOGIC, 'input.uppercase()', 'normalizeDraft(input)'),
                                          (LOGIC, None, '\nfun normalizeDraft(input: String): String = input.reversed()\n')],
                     action=lambda: apply('business-v2:24tfard'))
        if group in ['rendering', 'full']:
            accepted('render-and-add-component', [(SCREEN, 'Real edits v1', 'Real edits v2'),
                                                  (SCREEN, '// INSERT_COMPONENT',
                                                   'Button(onClick = { model.selected = "Added detail" }) { Text("Added action") }')],
                     visible=['Real edits v2', 'Added action'])
            device.tap_text('Added action')
            expected['page'] = 'Added detail'
            invariant()
            accepted('remove-component', [(SCREEN, 'Button(onClick = { model.selected = "Added detail" }) { Text("Added action") }',
                                           'Text("Component removed")')], visible=['Component removed'])
            assert 'Added action' not in snapshot(), 'Deleted component still visible'
        if group in ['callbacks', 'full']:
            accepted('registered-callback-body', [(LOGIC, 'callback-v1:', 'callback-v2:')],
                     action=lambda: callback('callback-v2:draft42'), probe_callback=True)
            accepted('insert-lambda-before-listener', [(LOGIC, '// INSERT_CALLBACK',
                                                       'val unrelated: () -> String = { "wrong-listener" }; check(unrelated().isNotEmpty())')],
                     action=lambda: callback('callback-v2:draft42'), probe_callback=True)
            register_again('register-after-insertion', 'callback-v2:draft42')
            # Capturing a newly calculated local is a deliberate strict test of an already registered closure.
            accepted('add-captured-local', [(LOGIC, 'return { "callback-v2:${model.input}" }',
                                            'val prefix = "captured:${model.input}"; return { "$prefix:${model.input}" }')],
                     action=lambda: callback('captured:draft42:draft42'), probe_callback=True)
            register_again('register-after-capture', 'captured:draft42:draft42')
        if group in ['recovery', 'full']:
            # The actual live ViewModel becomes abstract: expect engine rejection, unchanged old behavior,
            # and the pending companion edit to take effect only after the invalid edit is removed.
            business_before = next(t for t in snapshot() if t.startswith('Result: '))
            regression.edit(MODEL, 'class RealEditModel : ViewModel()', 'abstract class RealEditModel : ViewModel()')
            regression.edit(LOGIC, 'fun processEdit(input: String): String = ',
                            'fun processEdit(input: String): String = "recovered:" + ')
            host, elapsed = trigger('atomic-live-model-rejection')
            assert 'success=false' in host and 'class made abstract has live instances' in host, 'Missing native rejection'
            assert business_before in invariant(), 'Rejected batch changed result'
            apply(business_before.removeprefix('Result: '))
            common()
            results.append({'name': 'atomic-live-model-rejection', 'result': 'PASS', 'reloadSeconds': round(elapsed, 3)})
            MODEL.write_bytes(originals[MODEL])
            accepted('recover-pending-edit', [], action=lambda: apply(
                'recovered:' + ('business-v2:24tfard' if group == 'full' else 'business-v1:draft42')))
            accepted('new-class-and-caller', [(ADDED, None,
                     'package dev.compose.hotreload.sample\nclass EditFormatter { fun format(input: String) = "new-class:$input" }\n'),
                     (LOGIC, 'fun processEdit(input: String): String = ',
                      'fun processEdit(input: String): String = EditFormatter().format(input) + ":" + ')],
                     action=lambda: apply('new-class:draft42:recovered:' + (
                         'business-v2:24tfard' if group == 'full' else 'business-v1:draft42')))
    except BaseException as failure:
        results.append({'name': case_dir.name if case_dir != evidence else 'startup',
                        'result': 'FAIL', 'error': type(failure).__name__})
        (case_dir / 'failure.txt').write_text(str(failure))
        print('FAIL: ' + results[-1]['name'] + '; inspect ignored evidence', flush=True)
        raise
    finally:
        if app_pid:
            try:
                (evidence / 'logcat.txt').write_text(device.logcat())
            except Exception as error:
                (evidence / 'logcat-collection-failure.txt').write_text(str(error))
        if regression.HOST_LOG.exists():
            (evidence / 'host.log').write_text(regression.HOST_LOG.read_text())
        try:
            if probe is not None:
                try:
                    probe.stdin.write('quit\n')
                    probe.stdin.flush()
                    probe.wait(timeout=10)
                except (OSError, subprocess.TimeoutExpired):
                    probe.terminate()
                    probe.wait(timeout=10)
            if probe_log is not None:
                probe_log.close()
            if host_pid:
                try:
                    os.kill(host_pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            if run is not None and run.poll() is None:
                os.killpg(run.pid, signal.SIGTERM)
                try:
                    run.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    run.kill()
                    run.wait(timeout=10)
        finally:
            # Restore only after the host has stopped, preventing an automatic restore reload.
            for path, data in originals.items():
                path.write_bytes(data)
            ADDED.unlink(missing_ok=True)
            run_log.close()
            (evidence / 'summary.json').write_text(json.dumps(dict(metadata=metadata, results=results), indent=2))
    if any(result['result'] != 'PASS' for result in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
