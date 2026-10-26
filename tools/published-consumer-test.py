#!/usr/bin/env python3
"""Build a standalone versioned consumer with an isolated Gradle cache and no composite build.

The optional staging repository contains only this project's publications. All upstream,
Android and engine artifacts must resolve from public repositories. Raw logs stay ignored.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, help='local staging repository; omit for public Maven Central')
    parser.add_argument('--output', type=Path, help='new evidence directory under build/evidence')
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    evidence = (args.output or ROOT / 'build/evidence' / f'published-consumer-{time.time_ns()}').resolve()
    evidence.relative_to((ROOT / 'build/evidence').resolve())
    evidence.mkdir(parents=True, exist_ok=False)
    project = evidence / 'project'
    shutil.copytree(ROOT / 'tools/fixtures/published-consumer', project)
    for name in ['gradlew', 'gradlew.bat']:
        shutil.copy2(ROOT / name, project / name)
    shutil.copytree(ROOT / 'gradle/wrapper', project / 'gradle/wrapper')
    version = re.search(r'^version=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M)[1]
    build = project / 'app/build.gradle.kts'
    build.write_text(build.read_text().replace('@VERSION@', version))
    settings = project / 'settings.gradle.kts'
    staged = ''
    if args.repository:
        staged = ('maven("' + args.repository.resolve().as_uri() + '") {\n'
                  '            content { includeGroupAndSubgroups("io.github.ytlog.compose.hot-reload") }\n'
                  '        }')
    settings.write_text(settings.read_text().replace('// STAGING_REPOSITORY', staged))
    assert 'includeBuild(' not in settings.read_text() and 'mavenLocal(' not in settings.read_text()
    env = dict(os.environ, GRADLE_USER_HOME=str(evidence / 'gradle-home'))
    sdk = env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT')
    if not sdk:
        local = ROOT / 'local.properties'
        if local.exists():
            match = re.search(r'^sdk.dir=(.+)$', local.read_text(), re.M)
            sdk = match[1] if match else None
    if sdk:
        env['ANDROID_HOME'] = sdk
    command = [str(project / 'gradlew'), '-p', str(project), '--no-daemon', '--console=plain',
               ':app:assembleDebug', ':app:hotResourcesDebug', ':app:verifyPublishedClasspath']
    print('Evidence:', evidence.relative_to(ROOT), flush=True)
    if args.prepare_only:
        return
    with (evidence / 'build.log').open('w') as log:
        result = subprocess.run(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
    report = {'version': version, 'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'sourceChanges': subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT, text=True).splitlines(),
              'staging': args.repository is not None, 'isolatedGradleCache': True,
              'compositeBuild': False, 'mavenLocal': False, 'returncode': result.returncode}
    if result.returncode == 0:
        apk = project / 'app/build/outputs/apk/debug/app-debug.apk'
        report['apkSha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        report['resolvedArtifacts'] = (project / 'app/build/published-classpath.txt').read_text().splitlines()
    (evidence / 'summary.json').write_text(json.dumps(report, indent=2))
    print('PASS published consumer' if result.returncode == 0 else 'FAIL published consumer; inspect ignored build.log')
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
