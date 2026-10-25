#!/usr/bin/env python3
"""Continuous Kotlin -> D8 -> live Compose edits, restoring sources on exit.

python3 tools/real-edit-test.py --serial DEVICE --device-id local-apiNN \
    --art-dcevm-version VERSION [--scenario-group business|rendering|callbacks|recovery|compose-state|compose-recovery|refactoring|properties|properties-lazy|coroutines|coroutines-recovery|full]
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
from types import SimpleNamespace
from development_scenarios import DevelopmentCases, DEVELOPMENT_SOURCES, GENERATED_SOURCES
from property_scenarios import PropertyCases, PROPERTY_SOURCES
from coroutine_scenarios import CoroutineCases, COROUTINE_SOURCES

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
    parser.add_argument('--scenario-group', choices=['business', 'rendering', 'callbacks', 'recovery', 'compose-state', 'compose-recovery', 'refactoring', 'properties', 'properties-lazy', 'coroutines', 'coroutines-recovery', 'full'], default='full')
    args = parser.parse_args()
    if not args.serial:
        parser.error('Set --serial or ANDROID_SERIAL')
    if not re.fullmatch(r'[a-z0-9-]+', args.device_id) or args.device_id == args.serial:
        parser.error('--device-id must be an anonymous lowercase label')
    for path in [ADDED, *GENERATED_SOURCES]:
        if path.exists():
            parser.error('Refusing to overwrite ' + path.name)
    spec = importlib.util.spec_from_file_location('device_test', ROOT / 'tools/device-test.py')
    regression = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(regression)
    device = regression.Device(args.serial)
    environment = dict(os.environ, ANDROID_SERIAL=args.serial,
                       ORG_GRADLE_PROJECT_artDcevmVersion=args.art_dcevm_version)
    evidence = ROOT / 'build/evidence' / f'real-edits-{args.device_id}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    print('Evidence: ' + str(evidence.relative_to(ROOT)), flush=True)
    paths = [LOGIC, SCREEN, MODEL, *DEVELOPMENT_SOURCES, *PROPERTY_SOURCES, *COROUTINE_SOURCES, ADDED, *GENERATED_SOURCES]
    originals = {path: path.read_bytes() if path.exists() else None for path in paths}
    results = []
    metadata = {'device': args.device_id, 'engineVersion': args.art_dcevm_version,
                'group': args.scenario_group, 'sourceCommit': subprocess.check_output(
                    ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                'sources': {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in paths if p.exists()}}
    candidates = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD'], cwd=ROOT, text=True).splitlines()
    candidates += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=ROOT, text=True).splitlines()
    metadata['changedSources'] = {p: sha((ROOT / p).read_bytes()) for p in sorted(set(candidates)) if (ROOT / p).is_file()}
    host_pid = None
    run = None
    app_pid = None
    probe = None
    probe_log = None
    orchestration = evidence / 'orchestration.log'
    expected = {'input': 'draft42', 'page': 'Item B', 'operations': 1, 'callbackCalls': 0,
                'registrations': 1, 'controlCalls': 0, 'callback': 'callback-v1:draft42', 'control': None}
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
        assert 'Registrations: ' + str(expected['registrations']) in texts, 'Registration factory replayed'
        assert 'Control calls: ' + str(expected['controlCalls']) in texts, 'Control duplicated or lost'
        return texts

    def common():
        # No concurrent load on this device. Request GC, background and resume this same Activity.
        texts = active_invariant()
        gc = int(value(texts, 'GC: '))
        device.tap_text('GC: ')
        device.wait_for(lambda ts: 'GC: ' + str(gc + 1) in ts, 'GC action')
        device.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        device.adb('shell', 'am', 'start', '-W', '-f', '0x20000000', '-n', current_activity)
        active_invariant()
        with (case_dir / 'screen.png').open('wb') as image:
            subprocess.run(['adb', '-s', args.serial, 'exec-out', 'screencap', '-p'], stdout=image, check=True)

    def new_case(name):
        nonlocal case_dir
        case_dir = evidence / name
        case_dir.mkdir()

    def trigger(name):
        new_case(name)
        diff = ''.join(''.join(difflib.unified_diff(
            (original or b'').decode().splitlines(True), path.read_text().splitlines(True) if path.exists() else [],
            fromfile=str(path.relative_to(ROOT)), tofile=str(path.relative_to(ROOT))))
                       for path, original in originals.items())
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
        java_classes = SAMPLE / 'build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
        for path in java_classes.rglob('*.class'):
            relative = path.relative_to(java_classes)
            target = case_dir / 'java-classes' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            compiled['java/' + str(relative)] = sha(path.read_bytes())
        (case_dir / 'class-hashes.json').write_text(json.dumps(compiled, indent=2))
        wait(lambda: 'ReloadClassesResult' in orchestration.read_text()[offset:], 'reload response')
        host = orchestration.read_text()[offset:]
        (case_dir / 'orchestration.log').write_text(host)
        (case_dir / 'host.log').write_text(regression.HOST_LOG.read_text()[host_offset:])
        elapsed = time.monotonic() - start
        return host, elapsed

    def accepted(name, edits, action=None, visible=()):
        for path, old, new in edits:
            regression.edit(path, old, new)
        host, elapsed = trigger(name)
        assert 'ReloadClassesResult success=true' in host, 'Reload rejected; inspect host.log'
        if visible:
            device.wait_for(lambda ts: all(v in ts for v in visible), name, timeout=60)
        if action:
            action()
        common()
        results.append({'name': name, 'result': 'PASS', 'reloadSeconds': round(elapsed, 3), 'reloadAccepted': True})
        print('PASS: ' + name, flush=True)

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

    def control(expected_result):
        expected['controlCalls'] += 1
        device.tap_text('Call control')
        texts = device.wait_for(lambda ts: 'Control calls: ' + str(expected['controlCalls']) in ts, 'control completion')
        actual = value(texts, 'Control: ')
        assert actual == expected_result, f'Expected control {expected_result}; observed {actual}'

    def callbacks(old, fresh=None):
        callback(old)
        expected['callback'] = old
        if fresh is not None:
            control(fresh)
            expected['control'] = fresh

    active_invariant = invariant
    development_only = args.scenario_group in ['compose-state', 'compose-recovery', 'refactoring']
    state_activity = APP + '/.StateEditActivity'
    property_only = args.scenario_group in ['properties', 'properties-lazy']
    property_activity = APP + '/.PropertyEditActivity'
    coroutine_only = args.scenario_group in ['coroutines', 'coroutines-recovery']
    coroutine_activity = APP + '/.CoroutineEditActivity'
    current_activity = coroutine_activity if coroutine_only else property_activity if property_only else state_activity if development_only else ACTIVITY
    context = SimpleNamespace(device=device, evidence=evidence, source=SOURCE, snapshot=snapshot,
                              accepted=accepted, trigger=trigger, common=common, new_case=new_case,
                              case_dir=lambda: case_dir, results=results, app_pid=lambda: app_pid,
                              value=value, edit=regression.edit)
    development = None
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
        # The runtime connects before Application/launcher onCreate completes. Wait for its first UI
        # before launching another Activity, otherwise the pending launcher can cover the test page.
        device.wait_for(lambda ts: 'Count: 0' in ts, 'launcher ready', timeout=60)
        device.adb('shell', 'am', 'start', '-W', '-n', current_activity)
        device.wait_for(lambda ts: ('Coroutine edits' if coroutine_only else 'Property edits' if property_only else 'Development edits' if development_only else 'Real edits v1') in ts, 'edit page', timeout=60)
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
        shutil.copyfile(apk, evidence / 'installed-base.apk')
        installed_hash = device.adb('shell', 'sha256sum', installed).split()[0]
        assert installed_hash == sha(apk.read_bytes()), 'Installed APK hash differs'
        metadata.update(apkSha256=installed_hash, aarSha256=sha(aar.read_bytes()), engineSha256=sha(aar_engine),
                        api=device.adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
                        model=device.adb('shell', 'getprop', 'ro.product.model').strip(),
                        abi=device.adb('shell', 'getprop', 'ro.product.cpu.abi').strip(),
                        checkJni=device.adb('shell', 'getprop', 'debug.checkjni').strip())
        (evidence / 'startup.logcat').write_text(device.logcat())
        if coroutine_only:
            coroutines = CoroutineCases(context)
            active_invariant = coroutines.invariant
            coroutines.start()
        elif property_only:
            properties = PropertyCases(context)
            active_invariant = properties.invariant
            properties.start()
        elif development_only:
            development = DevelopmentCases(context)
            active_invariant = development.invariant
            development.start()
        else:
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
                     action=lambda: callbacks('callback-v2:draft42'))
            inserted = 'val unrelated: () -> String = { "wrong-listener" }; check(unrelated().isNotEmpty())'
            accepted('insert-lambda-before-listener', [(LOGIC, '// INSERT_CALLBACK', inserted)],
                     action=lambda: callbacks('callback-v2:draft42'))
            same_capture = 'val decoy: () -> String = { "wrong-captured:${model.input}" }; check(decoy().isNotEmpty())'
            accepted('insert-same-capture-lambda', [(LOGIC, inserted, inserted + '\n    ' + same_capture)],
                     action=lambda: callbacks('callback-v2:draft42'))
            accepted('body-after-insertion', [(LOGIC, 'callback-v2:', 'callback-v3:')],
                     action=lambda: callbacks('callback-v3:draft42'))
            # Never reconstruct new capture initializers for an existing closure: null/0 are the contract.
            plain_body = 'return { "callback-v3:${model.input}" }'
            reference_body = 'val prefix = "captured:${model.input}"; return { "$prefix:${model.input}" }'
            captured_body = 'val prefix = "captured:${model.input}"; val bonus = model.input.length + 0; return { "$prefix:$bonus:${model.input}" }'
            accepted('add-captured-local', [(LOGIC, plain_body, reference_body)],
                     action=lambda: callbacks('null:draft42'))
            accepted('add-primitive-capture', [(LOGIC, reference_body, captured_body)],
                     action=lambda: callbacks('null:0:draft42'))
            # This second closure is a control, not a replacement for the original immutable listener.
            case_dir = evidence / 'parallel-control'
            case_dir.mkdir()
            device.tap_text('Make control')
            expected['registrations'] = 2
            device.wait_for(lambda ts: 'Registrations: 2' in ts, 'parallel control registration')
            callbacks('null:0:draft42', 'captured:draft42:7:draft42')
            common()
            results.append({'name': 'parallel-control', 'result': 'PASS', 'originalListenerUnchanged': True})
            reordered_body = captured_body.replace('$prefix:$bonus:', '$bonus:$prefix:')
            accepted('reorder-captures', [(LOGIC, captured_body, reordered_body)],
                     action=lambda: callbacks('0:null:draft42', '7:captured:draft42:draft42'))
            removed_body = 'return { "callback-v4:${model.input}" }'
            accepted('remove-captures', [(LOGIC, reordered_body, removed_body)],
                     action=lambda: callbacks('callback-v4:draft42', 'callback-v4:draft42'))
            accepted('restore-captures', [(LOGIC, removed_body, reordered_body)],
                     action=lambda: callbacks('0:null:draft42', '7:captured:draft42:draft42'))
            # An inserted old capture schema plus an evolved original body cannot prove identity.
            ambiguous = 'val ambiguous: () -> String = { "ambiguous:$bonus:$prefix:${model.input}" }; check(ambiguous().isNotEmpty()); '
            evolved = 'val enabled = model.input.isNotEmpty(); '
            before_logcat = device.logcat()
            regression.edit(LOGIC, 'return { "$bonus:$prefix:', evolved + ambiguous + 'return { "pending:$enabled:$bonus:$prefix:')
            host, elapsed = trigger('ambiguous-lambda-rejection')
            assert 'success=false' in host and 'Ambiguous lambda identity' in host, 'Missing host identity rejection'
            after_logcat = device.logcat()
            (case_dir / 'before-rejection.logcat').write_text(before_logcat)
            (case_dir / 'after-rejection.logcat').write_text(after_logcat)
            request_ids = lambda log: set(re.findall(r'RedefineClasses request ([^:]+):', log))
            assert request_ids(before_logcat), 'Missing positive control for device request logging'
            assert not (request_ids(after_logcat) - request_ids(before_logcat)), 'Ambiguous plan reached device redefinition'
            assert 'matchLambdas' in (case_dir / 'host.log').read_text(), 'Missing planner rejection stack'
            callbacks(expected['callback'], expected['control'])
            common()
            results.append({'name': 'ambiguous-lambda-rejection', 'result': 'PASS', 'reloadSeconds': round(elapsed, 3),
                            'hostRejection': True})
            accepted('recover-lambda-mapping', [(LOGIC, evolved + ambiguous, ''), (LOGIC, 'pending:$enabled:', 'callback-recovered:')],
                     action=lambda: callbacks('callback-recovered:0:null:draft42',
                                              'callback-recovered:7:captured:draft42:draft42'))
        if group in ['recovery', 'full']:
            # The actual live ViewModel becomes abstract: expect engine rejection, unchanged old behavior,
            # and the pending companion edit to take effect only after the invalid edit is removed.
            business_before = next(t for t in snapshot() if t.startswith('Result: '))
            regression.edit(MODEL, 'class RealEditModel : ViewModel()', 'abstract class RealEditModel : ViewModel()')
            regression.edit(LOGIC, 'fun processEdit(input: String): String = ',
                            'fun processEdit(input: String): String = "recovered:" + ')
            callback_before, control_before = expected['callback'], expected['control']
            callback_marker = 'callback-recovered:' if group == 'full' else 'callback-v1:'
            regression.edit(LOGIC, 'return { "' + callback_marker,
                            'val allow = model.input.startsWith("draft"); return { "callback-committed:$allow:')
            host, elapsed = trigger('atomic-live-model-rejection')
            assert 'success=false' in host and 'class made abstract has live instances' in host, 'Missing native rejection'
            assert business_before in invariant(), 'Rejected batch changed result'
            apply(business_before.removeprefix('Result: '))
            callbacks(callback_before, control_before)
            common()
            results.append({'name': 'atomic-live-model-rejection', 'result': 'PASS', 'reloadSeconds': round(elapsed, 3)})
            MODEL.write_bytes(originals[MODEL])
            def recovered():
                apply('recovered:' + ('business-v2:24tfard' if group == 'full' else 'business-v1:draft42'))
                callbacks(callback_before.replace(callback_marker, 'callback-committed:false:'),
                          control_before.replace(callback_marker, 'callback-committed:false:') if control_before else None)
            accepted('recover-pending-edit', [], action=recovered)
            accepted('new-class-and-caller', [(ADDED, None,
                     'package dev.compose.hotreload.sample\nclass EditFormatter { fun format(input: String) = "new-class:$input" }\n'),
                     (LOGIC, 'fun processEdit(input: String): String = ',
                      'fun processEdit(input: String): String = EditFormatter().format(input) + ":" + ')],
                     action=lambda: apply('new-class:draft42:recovered:' + (
                         'business-v2:24tfard' if group == 'full' else 'business-v1:draft42')))
        if group in ['properties', 'properties-lazy', 'full']:
            if not property_only:
                current_activity = property_activity
                device.adb('shell', 'am', 'start', '-W', '-n', current_activity)
                device.wait_for(lambda ts: 'Property edits' in ts, 'property page')
                properties = PropertyCases(context)
                active_invariant = properties.invariant
                properties.start()
            if group == 'properties-lazy':
                properties.run_lazy()
            else:
                properties.run()
        if group in ['coroutines', 'coroutines-recovery', 'full']:
            if not coroutine_only:
                current_activity = coroutine_activity
                device.adb('shell', 'am', 'start', '-W', '-n', current_activity)
                device.wait_for(lambda ts: 'Coroutine edits' in ts, 'coroutine page')
                coroutines = CoroutineCases(context)
                active_invariant = coroutines.invariant
                coroutines.start()
            if group == 'coroutines-recovery':
                coroutines.run_recovery()
            else:
                coroutines.run()
        if group in ['compose-state', 'compose-recovery', 'refactoring', 'full']:
            if development is None:
                current_activity = state_activity
                device.adb('shell', 'am', 'start', '-W', '-n', current_activity)
                device.wait_for(lambda ts: 'Development edits' in ts, 'state edit page')
                development = DevelopmentCases(context)
                active_invariant = development.invariant
                development.start()
            if group in ['compose-state', 'full']:
                development.compose()
            if group == 'compose-recovery':
                development.render_recovery()
            if group in ['refactoring', 'full']:
                development.refactoring()
            if group == 'full':
                new_case('return-to-original-page')
                current_activity = ACTIVITY
                device.adb('shell', 'am', 'start', '-W', '-f', '0x20000', '-n', ACTIVITY)
                active_invariant = invariant
                device.wait_for(lambda ts: 'Model: ' + model_id in ts, 'original page retained')
                callbacks(expected['callback'], expected['control'])
                common()
                results.append({'name': 'return-to-original-page', 'result': 'PASS'})
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
                if data is None:
                    path.unlink(missing_ok=True)
                else:
                    path.write_bytes(data)
            run_log.close()
            (evidence / 'summary.json').write_text(json.dumps(dict(metadata=metadata, results=results), indent=2))
    if any(result['result'] != 'PASS' for result in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
