#!/usr/bin/env python3
"""Compile real Hilt/Room output and verify the production hot snapshot sees generated and transformed classes.

No device, synthetic bytecode or reimplementation of AGP. Mutated fixture source is always restored.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SAMPLE = ROOT / 'sample'
MODULE = SAMPLE / 'framework'
SOURCE = MODULE / 'src/main/java/dev/compose/hotreload/framework'
PACKAGE = 'dev/compose/hotreload/framework/'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--art-dcevm-version',required=True)
    args=parser.parse_args()
    evidence=ROOT/'build/evidence'/f'framework-classpath-{time.time_ns()}'
    evidence.mkdir(parents=True)
    env=dict(os.environ,ORG_GRADLE_PROJECT_artDcevmVersion=args.art_dcevm_version)
    bindings=SOURCE/'FrameworkBindings.kt';activity=SOURCE/'FrameworkActivity.kt';dao=SOURCE/'FrameworkDatabase.kt'
    originals={p:p.read_bytes() for p in [bindings,activity,dao]}
    init=evidence/'inspect.gradle'
    # The evidence directory is under build/; source/config files never record a personal path.
    init.write_text('''gradle.projectsEvaluated {
        if (gradle.rootProject.name == 'compose-hot-reload-android-sample') {
            def app = gradle.rootProject.project(':framework')
            app.tasks.register('inspectFrameworkHotInputs') {
                dependsOn('hotSnapshotDebug')
                doLast {
                    def target = new File(System.getenv('FRAMEWORK_CLASSPATH_REPORT'))
                    target.text = groovy.json.JsonOutput.toJson(app.tasks.named('hotSnapshotDebug').get().classpath.files.collect { it.absolutePath })
                }
            }
        }
    }
''')
    def compile(stage):
        out=evidence/stage;out.mkdir()
        environment=dict(env,FRAMEWORK_CLASSPATH_REPORT=str(out/'classpath.json'))
        with (out/'gradle.log').open('w') as log:
            subprocess.run([str(SAMPLE/'gradlew'),'-p',str(SAMPLE),'-I',str(init),':framework:inspectFrameworkHotInputs','--console=plain','-Pcompose.reload.frameworkFixture=true'],
                cwd=ROOT,env=environment,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
        classes={}
        for raw in json.loads((out/'classpath.json').read_text()):
            p=Path(raw)
            if p.is_dir():
                entries=((str(f.relative_to(p)),f.read_bytes()) for f in p.rglob('*.class'))
                for name,data in entries:
                    assert name not in classes or classes[name]==data,'Conflicting class definition'
                    classes[name]=data
            elif p.suffix=='.jar':
                with zipfile.ZipFile(p) as archive:
                    for name in archive.namelist():
                        if name.endswith('.class'):
                            data=archive.read(name)
                            assert name not in classes or classes[name]==data,'Conflicting class definition'
                            classes[name]=data
        (out/'class-hashes.json').write_text(json.dumps({n:hashlib.sha256(b).hexdigest() for n,b in classes.items()},indent=2))
        return classes
    result='FAIL'
    try:
        before=compile('baseline')
        component=PACKAGE+'DaggerFrameworkApplication_HiltComponents_SingletonC$SingletonCImpl$SwitchingProvider.class'
        assert component in before,'Generated Hilt component absent from production hot classpath'
        assert PACKAGE+'DraftDao_Impl.class' in before,'Generated Room DAO absent'
        # javap checks the actual superclass, not a string occurring elsewhere in the constant pool.
        probe=evidence/'FrameworkActivity.class';probe.write_bytes(before[PACKAGE+'FrameworkActivity.class'])
        declaration=subprocess.check_output(['javap',str(probe)],text=True)
        (evidence/'activity-declaration.txt').write_text(declaration)
        assert 'extends dev.compose.hotreload.framework.Hilt_FrameworkActivity' in declaration,'Raw pre-Hilt Activity reached reload'
        bindings.write_text(bindings.read_text().replace('formatter(implementation: OriginalFormatter)','formatter(implementation: AlternateFormatter)'))
        activity.write_text(activity.read_text().replace('Activity v1','Activity v2'))
        dao.write_text(dao.read_text().replace('@Query("SELECT text FROM drafts WHERE id = :id")\n    suspend', '@Query("SELECT \'host-v2:\' || text FROM drafts WHERE id = :id")\n    suspend'))
        after=compile('edited')
        for name in [component,PACKAGE+'FrameworkActivity.class',PACKAGE+'DraftDao_Impl.class']:
            assert before[name]!=after[name],'Edited/generated class did not change: '+name
            emitted=MODULE/'build/run/debug/classpath/hot'/name
            assert emitted.exists() and emitted.read_bytes()==after[name],'Incremental snapshot missed final class: '+name
        result='PASS'
        print('PASS: generated Hilt binding, transformed Activity and Room DAO reach incremental hot snapshot',flush=True)
    finally:
        for p,data in originals.items():p.write_bytes(data)
        restored=compile('restored')
        assert all(p.read_bytes()==data for p,data in originals.items())
        if 'before' in locals():
            assert restored==before,'Baseline class content not restored'
        (evidence/'summary.json').write_text(json.dumps({'result':result,'sourcesRestored':True,'baselineClassesRestored':True,
            'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()},indent=2))
        print('Evidence: '+str(evidence.relative_to(ROOT)),flush=True)


if __name__=='__main__':main()
