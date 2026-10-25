"""Build/cache a tiny read-only instrumentation bridge; never retry a failed snapshot."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'tools/ui-driver'


def build(evidence):
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    versions = sorted((sdk / 'build-tools').glob('*'), key=lambda p: tuple(int(x) for x in p.name.split('.') if x.isdigit()))
    bt = versions[-1]
    platform = sdk / 'platforms/android-36/android.jar'
    files = [SOURCE / 'AndroidManifest.xml', SOURCE / 'Driver.java']
    digest = hashlib.sha256(b''.join(p.read_bytes() for p in files)).hexdigest()
    out = SOURCE / 'build' / digest
    apk = out / 'driver.apk'
    if apk.exists(): return apk
    out.mkdir(parents=True, exist_ok=True)
    classes = out / 'classes'; classes.mkdir(exist_ok=True)
    dex = out / 'dex'; dex.mkdir(exist_ok=True)
    with (evidence / 'ui-driver-build.log').open('w') as log:
        def run(*args): subprocess.run(list(map(str, args)), check=True, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        run('javac', '-source', '8', '-target', '8', '-classpath', platform, '-d', classes, SOURCE / 'Driver.java')
        run(bt / 'd8', '--lib', platform, '--min-api', '30', '--output', dex, *classes.rglob('*.class'))
        unsigned = out / 'unsigned.apk'
        run(bt / 'aapt2', 'link', '-I', platform, '--manifest', SOURCE / 'AndroidManifest.xml', '--min-sdk-version', '30', '--target-sdk-version', '36', '-o', unsigned)
        with zipfile.ZipFile(unsigned, 'a') as archive: archive.write(dex / 'classes.dex', 'classes.dex')
        run(bt / 'apksigner', 'sign', '--ks', Path.home() / '.android/debug.keystore', '--ks-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--key-pass', 'pass:android', '--out', apk, unsigned)
    return apk


class UiDriver:
    def __init__(self, device, evidence):
        self.device, self.evidence = device, evidence
        self.process = self.output = self.connection = self.stream = self.forward = None

    def start(self):
        if os.environ.get('HOT_RELOAD_SLOW_UI') == '1' or int(self.device.adb('shell', 'getprop', 'ro.build.version.sdk')) < 33:
            return
        apk = build(self.evidence)
        self.output = (self.evidence / 'ui-driver.log').open('w')
        installed = self.device.adb('shell', 'pm', 'path', 'dev.compose.hotreload.uidriver', check=False).strip().removeprefix('package:')
        installed_hash = self.device.adb('shell', 'sha256sum', installed, check=False).split() if installed else []
        if not installed_hash or installed_hash[0] != hashlib.sha256(apk.read_bytes()).hexdigest():
            subprocess.run(['adb', '-s', self.device.serial, 'install', '--no-incremental', '-r', str(apk)], stdout=self.output, stderr=subprocess.STDOUT, check=True, timeout=90)
        self.forward = self.device.adb('forward', 'tcp:0', 'tcp:19283').strip()
        self.process = subprocess.Popen(['adb', '-s', self.device.serial, 'shell', 'am', 'instrument', '-w',
                                         'dev.compose.hotreload.uidriver/.Driver'], stdout=self.output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 20
        while True:
            assert self.process.poll() is None, 'UI driver exited; inspect ui-driver.log'
            try:
                connection = socket.create_connection(('127.0.0.1', int(self.forward)), timeout=1)
                stream = connection.makefile('rwb')
                stream.write(b'DUMP\n'); stream.flush()
                data = stream.readline()
                if not data: raise ConnectionError('instrumentation not ready')
                assert data.startswith(b'<hierarchy>'), 'Malformed UI driver response'
                self.connection, self.stream = connection, stream
                connection.settimeout(15)
                break
            except (OSError, ConnectionError):
                if 'stream' in locals(): stream.close()
                if 'connection' in locals(): connection.close()
                if time.monotonic() >= deadline: raise TimeoutError('UI driver not ready')
                time.sleep(.1)
        self.device.ui_driver = self
        (self.evidence / 'ui-driver.json').write_text(json.dumps({'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'mode': 'read-only-instrumentation'}))

    def dump(self):
        assert self.process.poll() is None, 'UI driver stopped'
        self.stream.write(b'DUMP\n'); self.stream.flush()
        data = self.stream.readline().decode('utf-8').strip()
        assert data.startswith('<hierarchy>') and data.endswith('</hierarchy>'), 'UI driver lost its response'
        return data

    def wait_for_foreground(self, package):
        # am start -W can return before the new accessibility window is published.
        # Only explicit Activity transitions use this bounded readiness wait; no inputs are retried.
        deadline = time.monotonic() + 10
        pid = self.device.adb('shell', 'pidof', package).strip()
        assert pid.isdigit(), 'Application process unavailable during Activity transition'
        while True:
            assert self.device.adb('shell', 'pidof', package).strip() == pid, 'Process changed during Activity transition'
            xml = self.dump()
            with (self.evidence / 'foreground-readiness.jsonl').open('a') as log:
                log.write(json.dumps({'package': package, 'xml': xml}) + '\n')
            if 'package="' + package + '"' in xml: return
            if time.monotonic() >= deadline: raise AssertionError('Application window did not become foreground')
            time.sleep(.05)

    def close(self):
        self.device.ui_driver = None
        if self.stream:
            try: self.stream.write(b'QUIT\n'); self.stream.flush()
            except OSError: pass
            self.stream.close()
        if self.connection: self.connection.close()
        if self.process:
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired: self.process.terminate(); self.process.wait(timeout=5)
        if self.forward: self.device.adb('forward', '--remove', 'tcp:' + self.forward)
        if self.output: self.output.close()
