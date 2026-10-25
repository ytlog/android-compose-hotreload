"""A shell-controlled test IME. Restore the user's IME even when acceptance fails."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'dev.compose.hotreload.ime'
COMPONENT = PACKAGE + '/.VerificationIme'


def build(evidence):
    source = ROOT / 'tools/ime-driver'
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    bt = sorted((sdk / 'build-tools').glob('*'), key=lambda p: tuple(int(x) for x in p.name.split('.') if x.isdigit()))[-1]
    platform = sdk / 'platforms/android-36/android.jar'
    inputs = [source / n for n in ['AndroidManifest.xml', 'VerificationIme.java', 'res/xml/method.xml']]
    digest = hashlib.sha256(b''.join(p.read_bytes() for p in inputs)).hexdigest()
    out = source / 'build' / digest
    apk = out / 'ime.apk'
    if apk.exists(): return apk
    out.mkdir(parents=True, exist_ok=True)
    classes = out / 'classes'; classes.mkdir(exist_ok=True)
    dex = out / 'dex'; dex.mkdir(exist_ok=True)
    with (evidence / 'ime-build.log').open('w') as log:
        def run(*args): subprocess.run(list(map(str, args)), check=True, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        run('javac', '-source', '8', '-target', '8', '-classpath', platform, '-d', classes, source / 'VerificationIme.java')
        run(bt / 'd8', '--lib', platform, '--min-api', '33', '--output', dex, *classes.rglob('*.class'))
        resources = out / 'resources.zip'
        run(bt / 'aapt2', 'compile', '--dir', source / 'res', '-o', resources)
        unsigned = out / 'unsigned.apk'
        run(bt / 'aapt2', 'link', '-I', platform, '--manifest', source / 'AndroidManifest.xml', '--min-sdk-version', '33', '--target-sdk-version', '36', '-R', resources, '-o', unsigned)
        with zipfile.ZipFile(unsigned, 'a') as archive: archive.write(dex / 'classes.dex', 'classes.dex')
        run(bt / 'apksigner', 'sign', '--ks', Path.home() / '.android/debug.keystore', '--ks-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--key-pass', 'pass:android', '--out', apk, unsigned)
    return apk


class ImeDriver:
    def __init__(self, device, evidence):
        self.d, self.evidence = device, evidence
        self.original = None
        self.installed_here = self.enabled_here = False
        self.sequence = 0

    def start(self):
        assert int(self.d.adb('shell', 'getprop', 'ro.build.version.sdk')) >= 33
        self.original = self.d.adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip()
        assert self.original and self.original != 'null', 'No original IME to restore'
        enabled = self.d.adb('shell', 'ime', 'list', '-s')
        apk = build(self.evidence)
        installed = self.d.adb('shell', 'pm', 'path', PACKAGE, check=False).strip().removeprefix('package:')
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        if installed:
            assert self.d.adb('shell', 'sha256sum', installed).split()[0] == digest, 'Different existing test IME; do not overwrite it'
        else:
            with (self.evidence / 'ime-install.log').open('w') as log:
                subprocess.run(['adb', '-s', self.d.serial, 'install', '--no-incremental', str(apk)], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
            self.installed_here = True
        self.enabled_here = COMPONENT not in enabled.splitlines()
        self.d.adb('shell', 'ime', 'enable', COMPONENT)
        self.d.adb('shell', 'ime', 'set', COMPONENT)
        assert self.d.adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip() == COMPONENT
        (self.evidence / 'ime.json').write_text(json.dumps({'apkSha256': digest, 'transport': 'InputMethodService/InputConnection', 'originalIme': self.original}))

    def command(self, command, text=None, selection=None):
        args = ['shell', 'am', 'broadcast', '-a', PACKAGE + '.COMMAND', '-p', PACKAGE, '--es', 'command', command]
        if text is not None: args += ['--es', 'payload', base64.b64encode(text.encode()).decode()]
        if selection is not None: args += ['--ei', 'start', str(selection[0]), '--ei', 'end', str(selection[1])]
        result = self.d.adb(*args)
        self.sequence += 1
        with (self.evidence / 'ime-commands.jsonl').open('a') as log:
            log.write(json.dumps(dict(sequence=self.sequence, command=command, text=text, selection=selection, response=result)) + '\n')
        assert 'Broadcast completed: result=1' in result, 'IME command failed; inspect ime-commands.jsonl'
        match = re.search(r'starts=(\d+);restarts=(\d+);finishes=(\d+)', result)
        assert match, 'Missing IME connection lifetime'
        return tuple(map(int, match.groups()))

    def close(self):
        if self.original and self.original != 'null':
            self.d.adb('shell', 'ime', 'set', self.original)
            assert self.d.adb('shell', 'settings', 'get', 'secure', 'default_input_method').strip() == self.original
        if self.enabled_here: self.d.adb('shell', 'ime', 'disable', COMPONENT)
        # Keep the fixed-package helper installed to avoid repeated OEM install approvals.
        # It is no longer selected (and is disabled if this run enabled it).
        (self.evidence / 'ime-cleanup.json').write_text(json.dumps({'originalRestored': bool(self.original and self.original != 'null'), 'removedTaskInstallation': False, 'retainedTaskInstallation': self.installed_here}))
