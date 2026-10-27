#!/usr/bin/env python3
"""Targeted framework, native interop and reactive-state edits in a fixed sample APK.

Run --scenario-group all for this new suite only; it does not run the original sample matrix.
Raw evidence, generated sources and failed runs remain under ignored build/evidence/.
"""
import argparse
from collections import Counter
import difflib
import hashlib
import html
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import zipfile

from device_session import DeviceSession
from ime_driver import ImeDriver
from ui_driver import UiDriver
from framework_reactive import ReactiveCases, REACTIVE_GROUPS
from framework_interop import InteropCases, INTEROP_GROUPS
from framework_ui import UiFeatureCases, UI_GROUPS

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / 'sample'
MODULE = SAMPLE / 'framework'
SOURCE = MODULE / 'src/main/java/dev/compose/hotreload/framework'
APP = 'dev.compose.hotreload.framework'
ACTIVITY = APP + '/.FrameworkActivity'
FRAMEWORK_GROUPS = ['navigation', 'hilt', 'room', 'input']
GROUPS = FRAMEWORK_GROUPS + INTEROP_GROUPS + REACTIVE_GROUPS + UI_GROUPS
sha = lambda data: hashlib.sha256(data).hexdigest()


class Cases:
    def __init__(self, runner):
        self.r = runner
        self.d = runner.device
        self.text = 'draft42'
        self.row = 'empty'
        self.counts = [0, 0, 0, 0]
        self.business_version = self.callback_version = 'v1'
        self.identities = {}
        self.events = Counter()
        self.model = None

    def values(self):
        return self.r.snapshot()

    def get(self, texts, prefix):
        return next(x[len(prefix):] for x in texts if x.startswith(prefix))

    def event(self, key):
        self.events[key + ':' + self.model] += 1

    def check(self, texts=None):
        texts = texts or self.values()
        assert self.d.adb('shell', 'pidof', APP).strip() == self.r.pid, 'Application process changed'
        for prefix, value in self.identities.items():
            assert self.get(texts, prefix) == value, prefix + ' replaced'
        for value in ['Anchors: true', 'Text: ' + self.text, 'Row: ' + self.row,
                      'Counts: ' + ':'.join(map(str, self.counts))]:
            assert value in texts, 'Missing ' + value
        actual = Counter(self.get(texts, 'Ledger: ').split(';'))
        assert actual == self.events, f'Ledger delta: {actual-self.events} / {self.events-actual}'
        (self.r.case / 'observation.json').write_text(json.dumps({'identities': self.identities,
            'text': self.text, 'row': self.row, 'counts': self.counts, 'events': dict(actual)}, indent=2))
        return texts

    def settle(self):
        def ready(texts):
            try:
                self.check(texts); return True
            except (AssertionError, StopIteration): return False
        self.d.wait_for(ready, 'framework state and exact ledger')
        return self.check()

    def field(self):
        xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        m = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert m, 'Missing actual editor'
        a,b,c,d = map(int,m.groups())
        self.d.adb('shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))
        self.d.wait_for(lambda t: 'Focused: true' in t, 'editor focus')

    def start(self):
        self.d.wait_for(lambda t: 'Draft list' in t, 'Navigation start destination')
        root = self.get(self.values(), 'Root: ')
        self.d.tap_text('Open 42')
        texts = self.d.wait_for(lambda t: 'Id: 42' in t and 'Row: empty' in t, 'Hilt detail ViewModel and initial Room value')
        self.identities = {p: self.get(texts, p) for p in ['Root: ', 'Entry: ', 'Model: ', 'Local: ']}
        assert self.identities['Root: '] == root
        self.model = self.identities['Model: ']
        self.events = Counter({f'create:{self.model}:42': 1, f'enter:{self.model}': 1,
                               f'subscribe:{self.model}': 1, f'row:{self.model}:empty': 1})
        self.field()
        self.r.seed_text(self.text)
        self.d.tap_text('Ready')
        if not self.r.ime:
            # A user's keyboard may convert injected Latin keys into candidate text.
            # Framework groups retain that actual entered draft; exact IME edits have their own group.
            self.text = self.get(self.values(), 'Text: ')
            assert self.text, 'System keyboard produced no draft'
        self.settle()

    def common(self):
        self.check()
        gc = int(self.get(self.values(), 'GC: '))
        self.d.tap_text('GC: ')
        self.d.wait_for(lambda t: 'GC: ' + str(gc+1) in t, 'GC complete')
        self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        # HOME animations may keep the Activity STARTED for longer than a fixed delay.
        # Observe the actual collector cancellation without querying a background window.
        expected = self.events['unsubscribe:' + self.model] + 1
        # Read the session's continuous capture: logcat -d can lose early events when
        # a small device ring buffer rolls over during a multi-group session.
        cancelled = re.compile(r'^\S+\s+\S+\s+' + re.escape(self.r.pid) +
            r'\s+\d+\s+I\s+FrameworkLedger\s*:\s+unsubscribe:' + re.escape(self.model) + r'\s*$', re.MULTILINE)
        def stopped():
            events = (self.r.evidence / 'system.logcat').read_text(errors='replace')
            return len(cancelled.findall(events)) == expected
        self.r.wait(stopped, 'lifecycle collector cancellation after HOME', timeout=30)
        self.event('unsubscribe')
        self.event('subscribe')
        self.events[f'row:{self.model}:{self.row}'] += 1
        self.d.adb('shell', 'am', 'start', '-W', '-f', '0x20000000', '-n', ACTIVITY)
        self.settle()

    def callback(self):
        self.counts[1] += 1
        self.d.tap_text('Callback')
        wanted = f'Probe: callback-{self.callback_version}:business-{self.business_version}:{self.text}'
        self.d.wait_for(lambda ts: wanted in ts, 'retained callback result')
        self.settle()

    def save(self):
        self.counts[0] += 1
        self.row = self.text
        self.events[f'row:{self.model}:{self.row}'] += 1
        self.d.tap_text('Save')
        self.settle()

    def binding(self, fresh):
        self.counts[3] += 1
        self.d.tap_text('Binding')
        wanted = f'Probe: binding-v1:{self.text}|binding-{fresh}:{self.text}'
        self.d.wait_for(lambda ts: wanted in ts, 'retained and newly injected formatter')
        self.settle()

    def query(self, prefix=''):
        self.counts[2] += 1
        self.d.tap_text('Query')
        wanted = f'Probe: old:{prefix}{self.row}|fresh:{prefix}{self.row}'
        self.d.wait_for(lambda ts: wanted in ts, 'generated DAO query on old and cached DAO')
        self.settle()

    def step(self, name, action, background=True):
        self.r.new_case(name)
        action()
        if background: self.common()
        self.r.passed(name)

    def edit(self, name, filename, old, new, action=None, background=True):
        self.r.edit(filename, old, new)
        self.r.reload(name)
        self.settle()  # Reload itself must not create a ViewModel, callback or subscription.
        if action: action()
        if background: self.common()
        self.r.passed(name, reload=True)

    def navigation(self):
        self.step('navigation-room-save', self.save)
        self.edit('navigation-hilt-activity-body', 'FrameworkActivity.kt', 'Activity v1', 'Activity v2',
                  lambda: self.d.wait_for(lambda t: 'Activity v2' in t, 'transformed Hilt Activity method'))
        self.business_version = 'v2'
        self.edit('navigation-retained-business', 'FrameworkLogic.kt', 'business-v1:', 'business-v2:', self.callback)
        self.callback_version = 'v2'
        self.edit('navigation-retained-lambda', 'FrameworkLogic.kt', 'callback-v1:', 'callback-v2:', self.callback)
        def review():
            self.d.tap_text('Review')
            self.d.wait_for(lambda t: 'Review page' in t, 'real navigation destination')
            self.event('leave'); self.event('unsubscribe')
            actual = Counter(self.get(self.values(), 'Ledger: ').split(';'))
            assert actual == self.events, 'Navigation disposal mismatch'
            self.d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            self.event('enter'); self.event('subscribe')
            self.events[f'row:{self.model}:{self.row}'] += 1
            self.settle()
            self.callback()
        self.step('navigation-review-pop-retains-entry', review)
        def new_entry():
            old = dict(self.identities)
            persisted = self.row
            self.d.tap_text('Back')
            self.d.wait_for(lambda t: 'Draft list' in t, 'pop detail')
            self.event('leave'); self.event('unsubscribe'); self.event('clear')
            assert Counter(self.get(self.values(), 'Ledger: ').split(';')) == self.events
            self.d.tap_text('Open 43')
            texts = self.d.wait_for(lambda t: 'Id: 43' in t and 'Row: empty' in t, 'distinct navigation argument')
            self.model = self.get(texts, 'Model: ')
            for prefix in ['Entry: ', 'Model: ', 'Local: ']:
                self.identities[prefix] = self.get(texts, prefix)
                assert self.identities[prefix] != old[prefix], prefix + ' reused across popped entries'
            self.text = ''; self.row = 'empty'; self.counts = [0,0,0,0]
            self.events[f'create:{self.model}:43'] += 1
            self.event('enter'); self.event('subscribe'); self.events[f'row:{self.model}:empty'] += 1
            self.settle()
            # Finish on 42 with a fresh navigation-scoped VM, but the same singleton database row.
            self.d.tap_text('Back'); self.d.wait_for(lambda t: 'Draft list' in t, 'pop 43')
            self.event('leave'); self.event('unsubscribe'); self.event('clear')
            self.d.tap_text('Open 42')
            texts = self.d.wait_for(lambda t: 'Id: 42' in t and 'Row: ' + persisted in t, 'persisted singleton database')
            self.model = self.get(texts, 'Model: ')
            self.identities.update({p:self.get(texts,p) for p in ['Entry: ', 'Model: ', 'Local: ']})
            assert self.model != old['Model: ']
            self.row = persisted
            self.events[f'create:{self.model}:42'] += 1
            self.event('enter'); self.event('subscribe'); self.events[f'row:{self.model}:{self.row}'] += 1
            self.field(); self.r.seed_text('draft42'); self.text = 'draft42'
            self.d.tap_text('Ready')
            if not self.r.ime:
                self.text = self.get(self.values(), 'Text: ')
                assert self.text, 'System keyboard produced no draft'
            self.settle()
        self.step('navigation-new-entry-and-cleared-viewmodel', new_entry)

    def hilt(self):
        self.step('hilt-original-provider', lambda: self.binding('v1'))
        self.edit('hilt-generated-binding', 'FrameworkBindings.kt',
                  'formatter(implementation: OriginalFormatter)', 'formatter(implementation: AlternateFormatter)',
                  lambda: (self.binding('v2'), self.r.require_generated('hilt-generated-binding',
                      'DaggerFrameworkApplication_HiltComponents_SingletonC', 'AlternateFormatter')))
        self.edit('hilt-binding-restore', 'FrameworkBindings.kt',
                  'formatter(implementation: AlternateFormatter)', 'formatter(implementation: OriginalFormatter)',
                  lambda: self.binding('v1'))

    def room(self):
        if self.row == 'empty': self.step('room-seed', self.save)
        self.step('room-original-query', self.query)
        self.edit('room-generated-query', 'FrameworkDatabase.kt',
                  '@Query("SELECT text FROM drafts WHERE id = :id")\n    suspend',
                  '@Query("SELECT \'sql-v2:\' || text FROM drafts WHERE id = :id")\n    suspend',
                  lambda: (self.query('sql-v2:'), self.r.require_generated('room-generated-query', 'DraftDao_Impl', 'sql-v2:')))
        # Reject a transaction containing generated DAO and business updates, then retry without restart.
        self.r.edit('AdmissionGuard.java', 'class AdmissionGuard', 'abstract class AdmissionGuard')
        self.r.edit('FrameworkDatabase.kt', 'sql-v2:', 'sql-v3:')
        self.r.edit('FrameworkLogic.kt', 'business-' + self.business_version + ':', 'business-recovered:')
        self.r.reload('room-atomic-rejection', success=False)
        self.settle(); self.query('sql-v2:'); self.callback(); self.common()
        self.r.passed('room-atomic-rejection', rejection=True)
        self.r.edit('AdmissionGuard.java', 'abstract class AdmissionGuard', 'class AdmissionGuard')
        self.r.reload('room-recovery')
        self.business_version = 'recovered'
        self.settle(); self.query('sql-v3:'); self.callback(); self.common(); self.r.passed('room-recovery', reload=True)
        self.edit('room-query-restore', 'FrameworkDatabase.kt', "SELECT 'sql-v3:' || text", 'SELECT text', self.query)

    def input(self):
        self.r.new_case('ime-chinese-composition')
        self.field()
        self.r.ime.command('select', selection=(0,len(self.text)))
        self.r.ime.command('compose', '中文草稿')
        self.text = '中文草稿'
        self.d.wait_for(lambda t: 'Text: 中文草稿' in t and 'Composition: 0:4' in t, 'real IME composition')
        self.settle(); self.r.passed('ime-chinese-composition')
        def editor(selection, composition, version):
            texts=self.check()
            for expected in [f'Selection: {selection}',f'Composition: {composition}','Focused: true', f'Validation: valid-{version}:4']:
                assert expected in texts, 'Editor lost '+expected
        self.r.ime.command('select', selection=(1,1))
        self.d.wait_for(lambda t: 'Selection: 1:1' in t, 'middle cursor')
        before=self.r.ime.command('status')
        self.edit('ime-composition-and-cursor-reload', 'FrameworkLogic.kt', 'valid-v1:', 'valid-v2:',
                  lambda: editor('1:1','0:4','v2'), background=False)
        assert self.r.ime.command('status')==before, 'Reload restarted the IME connection'
        self.r.new_case('ime-commit-after-reload')
        self.r.ime.command('commit','中文草稿')
        self.d.wait_for(lambda t:'Composition: none' in t and 'Selection: 4:4' in t,'IME commit')
        editor('4:4','none','v2');self.r.passed('ime-commit-after-reload')
        self.r.ime.command('select',selection=(1,3))
        self.d.wait_for(lambda t:'Selection: 1:3' in t,'selection range')
        before=self.r.ime.command('status')
        self.edit('ime-selection-reload','FrameworkLogic.kt','valid-v2:','valid-v3:',
                  lambda:editor('1:3','none','v3'),background=False)
        assert self.r.ime.command('status')==before,'Reload restarted selected editor'
        def replacement():
            self.r.ime.command('commit','编辑')
            self.text='中编辑稿'
            self.d.wait_for(lambda t:'Text: 中编辑稿' in t,'replace actual selection')
            editor('3:3','none','v3')
            self.d.tap_text('Ready');self.settle();self.callback()
        self.step('ime-replace-selection-and-call',replacement)


class Runner:
    def __init__(self,args):
        self.args=args
        self.evidence=ROOT/'build/evidence'/f'framework-{args.device_id}-{time.time_ns()}'
        self.evidence.mkdir(parents=True)
        print('Evidence: '+str(self.evidence.relative_to(ROOT)),flush=True)
        spec=importlib.util.spec_from_file_location('device_test',ROOT/'tools/device-test.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        module.APP=APP
        self.device=module.Device(args.serial)
        self.ui=UiDriver(self.device,self.evidence)
        self.ime=ImeDriver(self.device,self.evidence) if any(g in args.scenario_group for g in ['all','input']) else None
        self.session=DeviceSession(self.device,self.evidence,APP)
        self.environment=dict(os.environ,ANDROID_SERIAL=args.serial,ORG_GRADLE_PROJECT_artDcevmVersion=args.art_dcevm_version)
        self.originals={p:p.read_bytes() for p in SOURCE.glob('*') if p.is_file()}
        self.results=[];self.case=self.evidence;self.pid=None;self.host_pid=None;self.run=None;self.probe=None
        self.files=[];self.started=time.monotonic();self.elapsed=None
        self.host_log=MODULE/'build/run/debug/debug.chr.log'
        self.orchestration=self.evidence/'orchestration.log'
        self.metadata={'device':args.device_id,'group':args.scenario_group,'engineVersion':args.art_dcevm_version,
            'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            'sources':{str(p.relative_to(ROOT)):sha(b) for p,b in self.originals.items()}}
        changed=subprocess.check_output(['git','diff','--name-only','HEAD'],cwd=ROOT,text=True).splitlines()
        changed+=subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines()
        self.metadata['changedSources']={p:sha((ROOT/p).read_bytes()) for p in sorted(set(changed)) if (ROOT/p).is_file()}

    def log(self,name):
        f=(self.evidence/name).open('w');self.files.append(f);return f

    def gradle(self,*tasks,log,timeout=600):
        return subprocess.run([str(SAMPLE/'gradlew'),'-p',str(SAMPLE),'--console=plain',*tasks,
            '-Pcompose.reload.devToolsHeadless=true','-Pcompose.reload.frameworkFixture=true'],cwd=ROOT,env=self.environment,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)

    def wait(self,predicate,description,timeout=180):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            self.session.check()
            assert self.run is None or self.run.poll() is None,'hotRun exited'
            if predicate():return
            time.sleep(.2)
        raise AssertionError('Timed out: '+description)

    def snapshot(self):
        texts=[html.unescape(t) for t in self.device.texts()]
        (self.case/'ui.json').write_text(json.dumps(texts,ensure_ascii=False,indent=2))
        return texts

    def new_case(self,name):
        self.case=self.evidence/name;self.case.mkdir()
        self.elapsed=None

    def edit(self,filename,old,new):
        p=SOURCE/filename;s=p.read_text();assert old in s,filename+': edit not found'
        p.write_text(s.replace(old,new,1))

    def capture_generated(self):
        manifest={}
        for folder in ['generated','intermediates/hilt_component_sources']:
            directory=MODULE/'build'/folder
            for p in directory.rglob('*'):
                if p.is_file() and p.suffix in ['.kt','.java']:
                    rel=p.relative_to(MODULE/'build');dest=self.case/'generated'/rel
                    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest);manifest[str(rel)]=sha(p.read_bytes())
        (self.case/'generated-hashes.json').write_text(json.dumps(manifest,indent=2))

    def require_generated(self,name,filename,needle):
        files=list((self.evidence/name/'generated').rglob('*'+filename+'*'))
        assert any(p.is_file() and needle in p.read_text() for p in files), 'Missing regenerated '+filename

    def reload(self,name,success=True):
        self.new_case(name)
        patch=''.join(''.join(difflib.unified_diff(b.decode().splitlines(True),p.read_text().splitlines(True),
            fromfile=str(p.relative_to(ROOT)),tofile=str(p.relative_to(ROOT)))) for p,b in self.originals.items())
        (self.case/'source.patch').write_text(patch)
        offset=self.orchestration.stat().st_size;host_offset=self.host_log.stat().st_size
        start=time.monotonic()
        with (self.case/'gradle.log').open('w') as log:
            result=self.gradle(':framework:reload',log=log)
        assert result.returncode==0,'Compilation failed; inspect gradle.log'
        self.wait(lambda:'ReloadClassesResult' in self.orchestration.read_text()[offset:],'device reload response')
        response=self.orchestration.read_text()[offset:]
        (self.case/'orchestration.log').write_text(response)
        (self.case/'host.log').write_text(self.host_log.read_text()[host_offset:])
        self.capture_generated()
        self.elapsed=round(time.monotonic()-start,3)
        assert 'ReloadClassesResult success='+str(success).lower() in response,'Unexpected reload result'
        if not success:assert 'class made abstract has live instances' in response,'Wrong rejection reason'

    def passed(self,name,**attributes):
        result=dict(name=name,result='PASS',**attributes)
        if self.elapsed is not None:result['reloadSeconds']=self.elapsed
        self.results.append(result);print('PASS: '+name,flush=True)

    def seed_text(self, text):
        if self.ime:
            self.ime.command('commit', text)
        else:
            assert re.fullmatch('[a-zA-Z0-9]+', text), 'Only ASCII fixture seed uses shell key input'
            self.device.adb('shell', 'input', 'text', text)

    def start(self):
        self.device.adb('shell','input','keyevent','KEYCODE_WAKEUP')
        self.device.adb('shell','wm','dismiss-keyguard')
        # Helper install/IME setup precede input-monitored acceptance.
        self.ui.start()
        if self.ime:self.ime.start()
        # Reuse the exact installed baseline when possible; never reinstall on reload.
        # OEM first-install confirmations belong to setup, before physical-input monitoring.
        with (self.evidence/'build-framework.log').open('w') as log:
            assert self.gradle(':framework:assembleDebug',log=log).returncode == 0, 'Framework APK build failed'
        apk_dir=MODULE/'build/outputs/apk/debug'
        info=json.loads((apk_dir/'output-metadata.json').read_text());assert len(info['elements'])==1
        apk=apk_dir/info['elements'][0]['outputFile']
        installed=self.device.adb('shell','pm','path',APP,check=False).strip().removeprefix('package:')
        reused=bool(installed and self.device.adb('shell','sha256sum',installed).split()[0]==sha(apk.read_bytes()))
        if not reused:
            with (self.evidence/'install-framework.log').open('w') as log:
                subprocess.run(['adb','-s',self.args.serial,'install','--no-incremental','-r',str(apk)],
                    stdout=log,stderr=subprocess.STDOUT,check=True,timeout=90)
        (self.evidence/'installation.json').write_text(json.dumps({'package':APP,'reusedInstalledApk':reused,
            'apkSha256':sha(apk.read_bytes())}))
        self.session.start()
        with (self.evidence/'prepare-probe.log').open('w') as log:
            subprocess.run([str(ROOT/'gradlew'),':hot-reload-android-host:prepareWorkflowProbe','--console=plain'],cwd=ROOT,
                env=self.environment,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=300)
        self.host_log.unlink(missing_ok=True)
        self.run=subprocess.Popen([str(SAMPLE/'gradlew'),'-p',str(SAMPLE),'--console=plain',':framework:hotRunDebug',
            '-Pcompose.reload.devToolsHeadless=true','-Pcompose.reload.frameworkFixture=true','-x',':framework:installDebug'],cwd=ROOT,env=self.environment,stdout=self.log('hotRun.log'),stderr=subprocess.STDOUT,start_new_session=True)
        self.wait(lambda:self.host_log.exists() and 'Connected to' in self.host_log.read_text(),'host connection',600)
        props=dict(l.split('=',1) for l in (MODULE/'build/run/debug/debug.pid').read_text().splitlines() if '=' in l)
        self.host_pid=int(props['pid'])
        self.pid=self.device.adb('shell','pidof',APP).strip();self.session.bind_process(self.pid)
        self.ui.wait_for_foreground(APP)
        cp=(ROOT/'modules/host/build/workflow-probe/classpath.txt').read_text()
        self.probe=subprocess.Popen(['java','-cp',cp,'org.jetbrains.compose.reload.android.host.tooling.WorkflowProbeKt',props['orchestration.port']],
            stdin=subprocess.PIPE,stdout=self.log('orchestration.log'),stderr=subprocess.STDOUT,text=True)
        self.wait(lambda:'PROBE_READY' in self.orchestration.read_text(),'orchestration observer')
        shutil.copyfile(apk,self.evidence/'installed-base.apk')
        installed=self.device.adb('shell','pm','path',APP).strip().removeprefix('package:')
        apk_hash=self.device.adb('shell','sha256sum',installed).split()[0];assert apk_hash==sha(apk.read_bytes())
        aar=Path.home()/'.m2/repository/io/github/ytlog/artdcevm'/self.args.art_dcevm_version/f'artdcevm-{self.args.art_dcevm_version}.aar'
        with zipfile.ZipFile(aar) as z:engine=sha(z.read('jni/arm64-v8a/libartdcevm.so'))
        with zipfile.ZipFile(apk) as z:assert sha(z.read('lib/arm64-v8a/libartdcevm.so'))==engine
        self.metadata.update(apkSha256=apk_hash,aarSha256=sha(aar.read_bytes()),engineSha256=engine,
            model=self.device.adb('shell','getprop','ro.product.model').strip(),api=self.device.adb('shell','getprop','ro.build.version.sdk').strip(),
            abi=self.device.adb('shell','getprop','ro.product.cpu.abi').strip())
        self.capture_generated()

    def close(self):
        def cleanup(name, action):
            try:
                action()
            except Exception as error:
                self.results.append(dict(name=name+'-cleanup', result='FAIL', error=type(error).__name__))
                (self.evidence/(name+'-cleanup-error.txt')).write_text(str(error))

        def stop_host():
            if self.probe:
                self.probe.terminate()
                try:
                    self.probe.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.probe.kill(); self.probe.wait(timeout=5)
            if self.host_pid:
                try:
                    os.kill(self.host_pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            if self.run and self.run.poll() is None:
                os.killpg(self.run.pid, signal.SIGTERM)
                try:
                    self.run.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(self.run.pid, signal.SIGKILL); self.run.wait(timeout=5)

        try:
            # End monitoring before restoration so cleanup works after a rejected session too.
            cleanup('session', self.session.close)
            if self.ime:cleanup('ime', self.ime.close)
            cleanup('ui', self.ui.close)
            if self.host_log.exists():
                cleanup('host-log', lambda: shutil.copyfile(self.host_log, self.evidence/'host.log'))
            cleanup('host', stop_host)
        finally:
            # Source restoration must not be skipped because a device or host cleanup failed.
            for path, data in self.originals.items():
                path.write_bytes(data)
            for output in self.files:
                output.close()
            restored = all(p.read_bytes()==data for p,data in self.originals.items())
            (self.evidence/'summary.json').write_text(json.dumps(dict(metadata=self.metadata,
                results=self.results, restored=restored, seconds=round(time.monotonic()-self.started,3)), indent=2))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default=os.getenv('ANDROID_SERIAL'))
    parser.add_argument('--device-id',required=True)
    parser.add_argument('--art-dcevm-version',required=True)
    parser.add_argument('--scenario-group',choices=GROUPS+['all'],nargs='+',default=['all'])
    parser.add_argument('--motion-phase', choices=['all', 'animation', 'gestures'], default='all',
                        help='Retry only the pending portion of animation-gestures')
    args=parser.parse_args()
    if not args.serial or not re.fullmatch('[a-z0-9-]+',args.device_id) or args.device_id==args.serial:
        parser.error('Select a device and supply a separate anonymous --device-id')
    if 'all' in args.scenario_group and len(args.scenario_group) != 1:
        parser.error('Use all alone, or list only the groups still requiring verification')
    groups = GROUPS if args.scenario_group == ['all'] else list(dict.fromkeys(args.scenario_group))
    runner=Runner(args)
    try:
        runner.start()
        framework_groups = [g for g in groups if g in FRAMEWORK_GROUPS]
        if framework_groups:
            cases=Cases(runner);cases.start()
            runner.passed('framework-startup')
            for group in framework_groups:getattr(cases,group)()
        interop_groups = [g for g in groups if g in INTEROP_GROUPS]
        if interop_groups:
            if framework_groups:
                runner.device.tap_text('Back')
            interop=InteropCases(runner)
            interop.start()
            for group in interop_groups:getattr(interop,group.replace('-', '_'))()
            interop.fresh_and_cleanup(interop_groups)
        reactive_groups = [g for g in groups if g in REACTIVE_GROUPS]
        if reactive_groups:
            if framework_groups and not interop_groups:
                runner.device.tap_text('Back')
            reactive = ReactiveCases(runner)
            reactive.start()
            for group in reactive_groups:getattr(reactive, group.replace('-', '_'))()
            reactive.fresh_and_cleanup()
        ui_groups = [g for g in groups if g in UI_GROUPS]
        if ui_groups:
            if framework_groups and not interop_groups and not reactive_groups:
                runner.device.tap_text('Back')
            ui_cases = UiFeatureCases(runner)
            for group in ui_groups:getattr(ui_cases, group.replace('-', '_'))()
        runner.session.check()
    except BaseException as error:
        runner.results.append(dict(name=runner.case.name if runner.case!=runner.evidence else 'startup',result='FAIL',error=type(error).__name__))
        (runner.case/'failure.txt').write_text(str(error))
        print('FAIL: '+runner.results[-1]['name']+'; inspect ignored evidence',flush=True)
        raise
    finally:runner.close()
    if any(r['result']!='PASS' for r in runner.results):raise SystemExit(1)


if __name__=='__main__':main()
