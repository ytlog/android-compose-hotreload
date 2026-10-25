"""Real interface dispatch, isolated Koin graphs and retained Java Proxy invocations."""
import json
import re
import subprocess
from pathlib import Path

SOURCE = Path(__file__).resolve().parent.parent / 'sample/src/main/java/dev/compose/hotreload/sample'
REPOSITORY_SOURCES = [SOURCE / name for name in (
    'EditRepository.kt', 'EditRepositoryImpl.kt', 'AlternateRepository.kt', 'RepositoryBindings.kt',
    'RepositoryClient.kt', 'RepositoryHandler.kt', 'RepositoryLedger.kt', 'RepositoryProbe.kt',
    'RepositoryState.kt', 'RepositoryEditActivity.kt')]


class RepositoryCases:
    def __init__(self, context):
        self.c, self.d = context, context.device
        self.calls, self.identities, self.expected = 0, {}, []
        self.impl, self.default, self.detail = 'impl-v1', 'default-v1', 'detail-v1'
        self.handler, self.client = 'handler-v1', 'client-v1'
        self.version, self.badge, self.binding = None, None, 'di-v1'
        self.exception = 'IllegalArgumentException'
        self.counts = [2, 1, 0, 0]

    def read(self, value, alternate=False):
        return f'{"alternate-v1" if alternate else self.impl}/{value}/repo42'

    def values(self, value, proxy=False, alternate=False):
        read = self.read(value, alternate)
        values = [read, self.default + '/' + read, self.detail]
        values = [(self.handler + '/' if proxy else '') + v for v in values]
        if self.version:
            values.append('AbstractMethodError' if proxy else 'version-v1/repo42' if self.version == 'string' else 'version-v2/7')
        if self.badge:
            values.append(self.badge)
        return '|'.join(values)

    def observations(self):
        return [
            'Direct old: ' + self.values('repo42'), 'Direct new: ' + self.values('direct-v1'),
            'DI old: ' + self.client + '/' + self.values('repo42'),
            'DI factory: ' + self.client + '/' + self.values('repo42'),
            'DI new graph: ' + self.client + '/' + self.values(self.binding, alternate=self.binding != 'di-v1'),
            'Proxy old: ' + self.values('repo42', proxy=True),
            'Proxy new: ' + self.values('direct-v1', proxy=True),
            'Cached read: ' + self.read('repo42') + '|' + self.handler + '/' + self.read('repo42'),
            'Errors: ' + ','.join([self.exception] * 7),
            'Cached version: ' + ('absent' if not self.version else 'AbstractMethodError' if self.version == 'string' else 'NoSuchMethodError'),
            'Ledger: ' + '/'.join(map(str, self.counts)), 'DI retained: true', 'Proxy class reused: true']

    def invariant(self):
        assert self.d.adb('shell', 'pidof', 'dev.compose.hotreload.sample').strip() == self.c.app_pid(), 'Process changed'
        texts = self.c.snapshot()
        for prefix, value in self.identities.items():
            assert self.c.value(texts, prefix) == value, prefix + ' replaced'
        for expected in ['Objects retained: true', 'Repository draft: repo42', 'Repository calls: ' + str(self.calls), *self.expected]:
            assert expected in texts, f'Missing {expected}; actual={texts}'
        assert not any(t.startswith('Probe failure:') for t in texts), 'Repository probe failed'
        (self.c.case_dir() / 'repository-observation.json').write_text(json.dumps(dict(
            identities=self.identities, calls=self.calls, counts=self.counts,
            expected=self.expected, actual=texts), indent=2))
        return texts

    def inspect(self):
        self.calls += 1
        increments = [2, 2, 23, 9]
        self.counts = [a+b for a,b in zip(self.counts, increments)]
        self.expected = self.observations()
        self.d.tap_text('Inspect repositories')
        self.d.wait_for(lambda ts: 'Repository calls: ' + str(self.calls) in ts, 'repository probe call')
        self.invariant()

    def start(self):
        xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        field = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert field, 'Missing repository draft input'
        x1,y1,x2,y2 = map(int, field.groups())
        self.d.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        self.d.adb('shell', 'input', 'text', 'repo42')
        self.d.tap_text('Seed repositories')
        texts = self.d.wait_for(lambda ts: 'Repository draft: repo42' in ts, 'repository input')
        self.identities = {p: self.c.value(texts, p) for p in ['Repository state: ', 'Repository remember: ']}
        self.inspect()

    def abi(self, declarations):
        path = self.c.case_dir() / 'classes/dev/compose/hotreload/sample/EditRepository.class'
        output = subprocess.check_output(['javap', '-p', '-s', str(path)], text=True)
        (self.c.case_dir() / 'EditRepository-abi.txt').write_text(output)
        for declaration in declarations:
            assert declaration in output, 'Missing compiled interface ABI: ' + declaration

    def add_abstract(self):
        interface, impl, alternate, probe = (SOURCE / p for p in (
            'EditRepository.kt', 'EditRepositoryImpl.kt', 'AlternateRepository.kt', 'RepositoryProbe.kt'))
        def accept(name, edits):
            self.c.accepted(name, edits, action=self.inspect)
        def implementations(old, new):
            return [(p, old, new) for p in (impl, alternate)]
        self.version = 'string'
        accept('repository-abstract-batch', [
            (interface, '// REPOSITORY_METHODS', 'fun version(query: String): String\n    // REPOSITORY_METHODS'),
            (probe, '${repository.detail()}"', '${repository.detail()}|${repositoryAttempt { repository.version(query) }}"'),
            *implementations('// IMPLEMENTATION_METHODS', 'override fun version(query: String): String = "version-v1/$query"\n    // IMPLEMENTATION_METHODS')])
        self.abi(['public abstract java.lang.String version(java.lang.String);'])

    def run(self):
        interface, impl, alternate, probe, handler, binding, client = (SOURCE / p for p in (
            'EditRepository.kt', 'EditRepositoryImpl.kt', 'AlternateRepository.kt', 'RepositoryProbe.kt',
            'RepositoryHandler.kt', 'RepositoryBindings.kt', 'RepositoryClient.kt'))
        def accept(name, edits):
            self.c.accepted(name, edits, action=self.inspect)
        def implementations(old, new):
            return [(p, old, new) for p in (impl, alternate)]
        self.impl = 'impl-v2'
        accept('repository-implementation-body', [(impl, 'impl-v1/', 'impl-v2/')])
        self.default = 'default-v2'
        accept('repository-default-body', [(interface, 'default-v1/', 'default-v2/')])
        self.abi(['public default java.lang.String summary(java.lang.String);'])
        self.add_abstract()
        self.badge = 'badge-v1'
        accept('repository-add-default', [
            (interface, '// REPOSITORY_METHODS', 'fun badge(): String = "badge-v1"\n    // REPOSITORY_METHODS'),
            (probe, '${repositoryAttempt { repository.version(query) }}"', '${repositoryAttempt { repository.version(query) }}|${repository.badge()}"')])
        self.abi(['public default java.lang.String badge();'])
        self.detail = 'detail-impl'
        detail_impl = 'override fun detail(): String = "detail-impl"\n    '
        accept('repository-default-to-abstract', [
            (interface, 'fun detail(): String = "detail-v1"', 'fun detail(): String'),
            *implementations('// IMPLEMENTATION_METHODS', detail_impl + '// IMPLEMENTATION_METHODS')])
        self.abi(['public abstract java.lang.String detail();'])
        self.detail = 'detail-v2'
        accept('repository-abstract-to-default', [
            (interface, 'fun detail(): String\n', 'fun detail(): String = "detail-v2"\n'),
            *implementations(detail_impl, '')])
        self.abi(['public default java.lang.String detail();'])
        self.version = 'int'
        accept('repository-change-signature', [
            (interface, 'version(query: String)', 'version(query: Int)'),
            (probe, 'repository.version(query)', 'repository.version(7)'),
            *implementations('version(query: String): String = "version-v1/$query"', 'version(query: Int): String = "version-v2/$query"')])
        self.abi(['public abstract java.lang.String version(int);', 'descriptor: (I)Ljava/lang/String;'])
        self.handler = 'handler-v2'
        accept('repository-handler-body', [(handler, 'handler-v1/', 'handler-v2/')])
        self.binding = 'di-v2'
        accept('repository-di-binding', [(binding, 'EditRepositoryImpl("di-v1")', 'AlternateRepository("di-v2")')])
        self.client = 'client-v2'
        accept('repository-client-body', [(client, 'client-v1/', 'client-v2/')])
        self.exception = 'IllegalStateException'
        accept('repository-exception-body', implementations('require(query != "fail")', 'check(query != "fail")'))
        guard = SOURCE / 'StateAdmissionGuard.java'
        self.c.edit(guard, 'public class StateAdmissionGuard', 'public abstract class StateAdmissionGuard')
        for path, old, new in [(interface, 'default-v2/', 'default-v3/'), (impl, 'impl-v2/', 'impl-v3/'), (binding, '"di-v2"', '"di-v3"')]:
            self.c.edit(path, old, new)
        host, elapsed = self.c.trigger('repository-atomic-rejection')
        assert 'success=false' in host and 'class made abstract has live instances' in host, 'Missing native rejection'
        self.inspect()
        self.c.common()
        self.c.results.append(dict(name='repository-atomic-rejection', result='PASS', reloadSeconds=round(elapsed, 3), nativeRejection=True))
        self.impl, self.default, self.binding = 'impl-v3', 'default-v3', 'di-v3'
        accept('repository-recovery', [(guard, 'public abstract class StateAdmissionGuard', 'public class StateAdmissionGuard')])
