#!/usr/bin/env python3
"""Verify live Android resources, state retention and reinstall guards. Restores all edited sample files.

python3 tools/resource-test.py --serial DEVICE [--auto] [--headless]
"""
import argparse
from ui_driver import UiDriver
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import struct
import time
import zlib


def png(width):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, 2, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress((b'\0' + b'\x33\x66\x99' * width) * 2)) + chunk(b'IEND', b''))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--auto', action='store_true')
    parser.add_argument('--headless', action='store_true')
    args = parser.parse_args()
    if not args.serial:
        parser.error('Set --serial or ANDROID_SERIAL')
    spec = importlib.util.spec_from_file_location('regression', Path(__file__).with_name('device-test.py'))
    regression = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(regression)
    sample = regression.SAMPLE
    device = regression.Device(args.serial)
    environment = dict(os.environ, ANDROID_SERIAL=args.serial)
    values = sample / 'src/main/res/values/hot_reload.xml'
    icon = sample / 'src/main/res/drawable/resource_icon.xml'
    raw = sample / 'src/main/res/raw/resource_text.txt'
    asset = sample / 'src/main/assets/resource_text.txt'
    manifest = sample / 'src/main/AndroidManifest.xml'
    bitmap = sample / 'src/main/res/drawable-nodpi/resource_bitmap.png'
    qualified = sample / 'src/main/res/values-zh/hot_reload.xml'
    library = sample / 'lib/src/main/res/values/hot_reload.xml'
    paths = [values, icon, bitmap, raw, asset, manifest, qualified, library, regression.MAIN]
    originals = {path: path.read_bytes() for path in paths}
    evidence = sample.parent / 'build/evidence' / f'resources-{time.time_ns()}'
    evidence.mkdir(parents=True)
    print(f'Evidence: {evidence.relative_to(sample.parent)}', flush=True)
    results = []
    ui_driver = UiDriver(device, evidence)
    host_pid = None
    regression.HOST_LOG.unlink(missing_ok=True)
    log = (evidence / 'hotRun.log').open('w')
    command = [str(sample / 'gradlew'), '-p', str(sample), '--console=plain', 'hotRunDebug']
    if args.headless:
        command.append('-Pcompose.reload.devToolsHeadless=true')
    if args.auto:
        command.append('--auto')
    run = subprocess.Popen(command, env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)

    def wait(condition, description, timeout=300):
        deadline = time.time() + timeout
        while time.time() < deadline:
            assert run.poll() is None, f'hotRun exited; see {evidence}'
            if condition():
                return
            time.sleep(1)
        raise AssertionError(f'Timed out: {description}; see {evidence}')

    def trigger():
        if not args.auto:
            regression.reload(environment)

    def passed(name):
        assert device.adb('shell', 'pidof', regression.APP).strip() == app_pid, 'Process restarted'
        assert device.count() == count, 'Resource update lost remember state'
        results.append(dict(name=name, result='PASS'))
        print(f'PASS: {name}', flush=True)

    def change(name, path, old, new, expected):
        regression.edit(path, old, new)
        trigger()
        device.wait_for(lambda texts: any(expected in text for text in texts), name, timeout=180)
        passed(name)

    def rejected(name, edit, reason, restore):
        position = len(regression.HOST_LOG.read_text())
        edit()
        trigger()
        wait(lambda: reason in regression.HOST_LOG.read_text()[position:], name, timeout=180)
        assert any('Resource text updated' in text for text in device.texts()), 'Rejected transaction changed resource UI'
        passed(name)
        restore()
        # Observe an actual resource update after reverting; debug log levels vary by host configuration.
        recovered = f'Resource text updated ({name} recovered)'
        values.write_text(values.read_text().replace('Resource text updated', recovered))
        trigger()
        device.wait_for(lambda texts: any(recovered in text for text in texts), 'recovery', timeout=180)
        passed(name + ' recovery')

    try:
        ui_driver.start()
        wait(lambda: regression.HOST_LOG.exists() and 'Connected to' in regression.HOST_LOG.read_text(),
             'host connection', timeout=600)
        host_pid = int(re.search(r'^pid=(\d+)$', regression.PID_FILE.read_text(), re.MULTILINE).group(1))
        device.wait_for(lambda texts: 'Resource text' in texts, 'initial resources', timeout=60)
        app_pid = device.adb('shell', 'pidof', regression.APP).strip()
        for _ in range(3):
            device.tap_text('Count: ')
        count = device.count()
        change('string', values, 'Resource text', 'Resource text updated', 'Resource text updated')
        change('color', values, '#336699', '#AA2244', 'Color: ffaa2244')
        change('vector drawable cache', icon, 'android:width="12dp"', 'android:width="24dp"', 'Drawable: 24.0')
        bitmap.write_bytes(png(4))
        trigger()
        device.wait_for(lambda texts: 'Bitmap: 4.0' in texts, 'bitmap painter cache', timeout=180)
        passed('bitmap painter cache')
        change('raw resource', raw, 'Raw text', 'Raw updated', 'Raw updated')
        device.wait_for(lambda texts: 'FD: Raw updated / Asset text' in texts, 'raw resource descriptor')
        change('asset', asset, 'Asset text', 'Asset updated', 'Asset updated')
        device.wait_for(lambda texts: 'FD: Raw updated / Asset updated' in texts, 'asset descriptor')
        change('library resource', library, 'Library resource', 'Library resource updated', 'Library resource updated')
        # Change both defaults and the installed locale variant, independent of the device locale.
        regression.edit(values, 'Qualified text', 'Qualified updated')
        change('qualified resource', qualified, 'Qualified text', 'Qualified updated', 'Qualified updated')
        # Both the resource and changed code must be visible in the same reload.
        regression.edit(values, 'Resource text updated', 'Resource and code updated')
        regression.edit(regression.MAIN, '"Hello Android Hot Reload"', '"Resource batch code"')
        trigger()
        device.wait_for(lambda texts: 'Resource and code updated' in texts and 'Resource batch code' in texts,
                        'mixed code/resources', timeout=180)
        passed('code and resources')
        change('restore content', values, 'Resource and code updated', 'Resource text updated', 'Resource text updated')
        if not args.auto:
            current_values = values.read_bytes()
            rejected('new resource guard',
                     lambda: regression.edit(values, '</resources>', '<string name="new_resource">New</string></resources>'),
                     'Resource names or IDs changed', lambda: values.write_bytes(current_values))
            rejected('manifest guard',
                     lambda: regression.edit(manifest, '<application', '<uses-permission android:name="android.permission.VIBRATE"/><application'),
                     'AndroidManifest.xml changed', lambda: manifest.write_bytes(originals[manifest]))
            current_asset = asset.read_bytes()
            rejected('asset removal guard', lambda: asset.unlink(), 'files were removed',
                     lambda: asset.write_bytes(current_asset))
            current_qualified = qualified.read_bytes()
            rejected('qualifier removal guard', lambda: qualified.unlink(), 'configurations were removed',
                     lambda: qualified.write_bytes(current_qualified))
    except Exception as failure:
        results.append(dict(name='resource regression', result='FAIL', note=str(failure)))
        raise
    finally:
        ui_driver.close()
        if host_pid is not None:
            try:
                os.kill(host_pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        if run.poll() is None:
            os.killpg(run.pid, signal.SIGTERM)
            try:
                run.wait(timeout=30)
            except subprocess.TimeoutExpired:
                run.kill()
                run.wait(timeout=10)
        for path, content in originals.items():
            path.write_bytes(content)
        log.close()
        if regression.HOST_LOG.exists():
            (evidence / 'host.log').write_text(regression.HOST_LOG.read_text())
        (evidence / 'logcat.txt').write_text(device.adb('logcat', '-d', '-s', 'ComposeHotReload:*', check=False))
        (evidence / 'summary.json').write_text(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
