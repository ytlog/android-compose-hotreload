#!/usr/bin/env python3
"""Check the complete Maven publication set before staging or skipping a release."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
GROUP = 'io.github.ytlog.compose.hot-reload'
PLUGIN = 'io.github.ytlog.compose.hot-reload.android'
MODULES = ['hot-reload-android-core', 'hot-reload-android-host',
           'hot-reload-android-runtime', 'hot-reload-android-gradle-plugin']
COORDINATES = [(GROUP, name) for name in MODULES] + [(PLUGIN, PLUGIN + '.gradle.plugin')]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def project_version():
    return re.search(r'^version=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M)[1]


def artifact_path(group, artifact, version):
    return f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}'


def check_staging(repository, version):
    for group, artifact in COORDINATES:
        base = repository / artifact_path(group, artifact, version)
        pom = ET.parse(str(base) + '.pom').getroot()
        for key, expected in [('groupId', group), ('artifactId', artifact), ('version', version)]:
            assert pom.findtext('m:' + key, namespaces=NS) == expected, (artifact, key)
        for field in ['name', 'description', 'url', 'licenses/m:license/m:name',
                      'developers/m:developer/m:id', 'scm/m:connection']:
            assert pom.findtext('m:' + field, namespaces=NS), (artifact, field)
        dependencies = [(d.findtext('m:groupId', namespaces=NS),
                         d.findtext('m:artifactId', namespaces=NS),
                         d.findtext('m:version', namespaces=NS))
                        for d in pom.findall('m:dependencies/m:dependency', NS)]
        if artifact.endswith('.gradle.plugin'):
            assert dependencies == [(GROUP, 'hot-reload-android-gradle-plugin', version)]
        else:
            extension = 'aar' if artifact.endswith('-runtime') else 'jar'
            for suffix in ['.' + extension, '-sources.jar', '-javadoc.jar', '.module']:
                assert Path(str(base) + suffix).is_file(), (artifact, suffix)
            metadata = json.loads(Path(str(base) + '.module').read_text())
            assert metadata['component']['version'] == version
        managed = {(d.findtext('m:groupId', namespaces=NS), d.findtext('m:artifactId', namespaces=NS)):
                   d.findtext('m:version', namespaces=NS)
                   for d in pom.findall('m:dependencyManagement/m:dependencies/m:dependency', NS)}
        for dependency_group, name, dependency_version in dependencies:
            dependency_version = dependency_version or managed.get((dependency_group, name))
            assert dependency_version and 'SNAPSHOT' not in dependency_version.upper(), (artifact, name)
            if dependency_group == GROUP:
                assert name in MODULES and dependency_version == version
            if dependency_group == 'io.github.ytlog' and name == 'artdcevm':
                assert dependency_version == '0.1.0-alpha.4'
        print('PASS publication:', artifact)


def central_availability(version):
    states = []
    for group, artifact in COORDINATES:
        url = 'https://repo.maven.apache.org/maven2/' + artifact_path(group, artifact, version) + '.pom'
        result = subprocess.run(['curl', '--silent', '--show-error', '--location', '--retry', '3',
                                 '--max-time', '30', '--output', os.devnull, '--write-out', '%{http_code}', url],
                                capture_output=True, text=True, check=True)
        if result.stdout not in ['200', '404']:
            raise RuntimeError(f'Central lookup failed for {artifact}: HTTP {result.stdout}')
        states.append(result.stdout == '200')
    if any(states) and not all(states):
        raise RuntimeError('Partial publication: investigate the existing deployment before retrying; do not overwrite coordinates')
    available = not all(states)
    if 'GITHUB_OUTPUT' in os.environ:
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
            output.write(f'available={str(available).lower()}\n')
    print(f'Publication needed: {available}; checked all five coordinates')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--central-availability', metavar='VERSION')
    parser.add_argument('--repository', type=Path, default=ROOT / 'build/staging-repository')
    args = parser.parse_args()
    if args.central_availability:
        central_availability(args.central_availability)
    else:
        check_staging(args.repository, project_version())
