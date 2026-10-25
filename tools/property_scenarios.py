"""Old/new Kotlin property semantics through actual constructors, getters and page actions."""
import json
import re
import subprocess
from pathlib import Path

SOURCE = Path(__file__).resolve().parent.parent / 'sample/src/main/java/dev/compose/hotreload/sample'
PROPERTY_SOURCES = [SOURCE / name for name in (
    'PropertyViewModel.kt', 'PropertyBusiness.kt', 'PropertyDelegates.kt', 'PropertyData.kt',
    'PropertyProbe.kt', 'PropertyLedger.kt', 'PropertyState.kt', 'PropertyEditActivity.kt')]


class PropertyCases:
    def __init__(self, context):
        self.c, self.d = context, context.device
        self.calls = 0
        self.identities = {}
        self.old = dict(seed='property42', count=41, initialized=11, block=31, getter='getter-v1', reference='retained-ref')
        self.fresh = dict(count=5, initialized=11, block=31, getter='getter-v1', reference='reference-v1')
        self.old_suffix = self.new_suffix = ''
        self.old_delegate, self.new_delegate = 'delegate-edited', 'delegate-v1'
        self.old_added = self.new_added = 'absent'
        self.old_lazy = self.new_lazy = 'warm-v1|pending|1/0'
        self.data_old = dict(title='property42', amount=41)
        self.data_new = dict(title='data-v1', amount=3)
        self.expected = []

    def values(self, values):
        return '{seed}|{count}|{initialized}|{block}|{seed}/{count}/{getter}|{reference}'.format(**values)

    def data_values(self, values):
        return '|'.join(map(str, values.values())) + '|PropertyData(' + ', '.join(f'{k}={v}' for k, v in values.items()) + ')'

    def observations(self):
        return [
            'VM old: ' + self.values(self.old) + self.old_suffix,
            'VM new: ' + self.values(dict(self.fresh, seed='vm-v1')) + self.new_suffix,
            'Biz old: ' + self.values(self.old) + self.old_suffix,
            'Biz new: ' + self.values(dict(self.fresh, seed='business-v1')) + self.new_suffix,
            'Delegate old: ' + self.old_delegate + '|' + self.old_added,
            'Delegate new: ' + self.new_delegate + '|' + self.new_added,
            'Lazy old: ' + self.old_lazy, 'Lazy new: ' + self.new_lazy,
            'Data old: ' + self.data_values(self.data_old), 'Data new: ' + self.data_values(self.data_new),
            'Data copy: ' + self.data_values(self.data_old), 'Data equality: true']

    def invariant(self):
        assert self.d.adb('shell', 'pidof', 'dev.compose.hotreload.sample').strip() == self.c.app_pid(), 'Process changed'
        texts = self.c.snapshot()
        for prefix, value in self.identities.items():
            assert self.c.value(texts, prefix) == value, prefix + ' replaced'
        for expected in ['Objects retained: true', 'Property draft: property42', 'Property calls: ' + str(self.calls), *self.expected]:
            assert expected in texts, f'Missing {expected}; actual={texts}'
        assert not any(t.startswith('Probe failure:') for t in texts), 'Constructor/getter probe failed'
        (self.c.case_dir() / 'property-observation.json').write_text(json.dumps(dict(
            identities=self.identities, calls=self.calls, expected=self.expected, actual=texts), indent=2))
        return texts

    def inspect(self):
        self.calls += 1
        self.expected = self.observations()
        self.d.tap_text('Inspect models')
        self.d.wait_for(lambda ts: 'Property calls: ' + str(self.calls) in ts, 'property probe call')
        self.invariant()

    def start(self):
        xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        field = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert field, 'Missing property draft input'
        x1, y1, x2, y2 = map(int, field.groups())
        self.d.adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        self.d.adb('shell', 'input', 'text', 'property42')
        self.d.tap_text('Seed models')
        texts = self.d.wait_for(lambda ts: 'Property draft: property42' in ts, 'property input')
        self.identities = {p: self.c.value(texts, p) for p in ['Property state: ', 'Property remember: ']}
        self.inspect()

    def abi(self, class_name, declarations):
        target = self.c.case_dir() / 'classes/dev/compose/hotreload/sample' / (class_name + '.class')
        result = subprocess.run(['javap', '-p', '-s', str(target)], capture_output=True, text=True, check=True)
        (self.c.case_dir() / (class_name + '-abi.txt')).write_text(result.stdout)
        for declaration in declarations:
            assert declaration in result.stdout, 'Missing compiled ABI: ' + declaration

    def run_lazy(self):
        delegates = SOURCE / 'PropertyDelegates.kt'
        self.new_lazy = 'warm-v2|pending|1/0'
        self.c.accepted('property-lazy-cached', [(delegates, 'warmRuns++; "warm-v1"', 'warmRuns++; "warm-v2"')], action=self.inspect)
        self.old_lazy, self.new_lazy = 'warm-v1|cold-v2|1/1', 'warm-v2|cold-v2|1/1'
        self.c.accepted('property-lazy-deferred', [
            (delegates, 'coldRuns++; "cold-v1"', 'coldRuns++; "cold-v2"'),
            (delegates, '$warm|pending|', '$warm|$cold|')], action=self.inspect)

    def run(self):
        models = [SOURCE / 'PropertyViewModel.kt', SOURCE / 'PropertyBusiness.kt']
        delegates, data, probe = (SOURCE / p for p in ['PropertyDelegates.kt', 'PropertyData.kt', 'PropertyProbe.kt'])
        def both(old, new):
            return [(p, old, new) for p in models]
        def accept(name, edits):
            self.c.accepted(name, edits, action=self.inspect)

        self.fresh.update(initialized=22, block=47)
        accept('property-initializers', both(', 11)', ', 22)') + both('blockValue = 31', 'blockValue = 47'))
        self.old['getter'] = self.fresh['getter'] = 'getter-v2'
        accept('property-getters', both('/getter-v1', '/getter-v2'))
        self.old_suffix, self.new_suffix = '|null/0/false/NullPointerException', '|added-v1/7/true/8'
        additions = '''val addedText: String = "added-v1"
    val addedCount: Int = 7
    val addedFlag: Boolean = true
    fun addedLength(): String = try { addedText.length.toString() } catch (failure: NullPointerException) { "NullPointerException" }
    // ADDED_PROPERTIES'''
        accept('property-additions', both('// ADDED_PROPERTIES', additions) +
               both('$computed|$reference"', '$computed|$reference|$addedText/$addedCount/$addedFlag/${addedLength()}"'))
        self.old['count'], self.fresh['count'] = 0, 13
        accept('property-primitive-retype', both('var count: Int = 5', 'var count: Long = 13L'))
        self.abi('PropertyViewModel', ['long count;', 'descriptor: J', 'java.lang.String addedText;'])
        self.fresh['reference'] = 'reference-v2'
        accept('property-compatible-reference', both('var reference: Any? = "reference-v1"', 'var reference: CharSequence? = "reference-v2"'))
        self.old['reference'], self.fresh['reference'] = 'null', '99'
        accept('property-incompatible-reference', both('var reference: CharSequence? = "reference-v2"', 'var reference: Number? = 99') +
               both('reference = StringBuilder("retained-ref")', 'reference = 89'))
        self.new_delegate = 'delegate-v2'
        accept('property-delegate-initializer', [(delegates, 'mutableStateOf("delegate-v1")', 'mutableStateOf("delegate-v2")')])
        self.run_lazy()
        self.old_added, self.new_added = 'NullPointerException', 'added-delegate'
        accept('property-add-delegate', [(delegates, '// ADDED_DELEGATE', 'var added: String by mutableStateOf("added-delegate")'),
            (delegates, 'fun addedValue(): String = "absent"',
             'fun addedValue(): String = try { added } catch (failure: NullPointerException) { "NullPointerException" }')])
        self.old_delegate, self.new_delegate = 'ClassCastException', '17'
        accept('property-delegate-generic-retype', [
            (delegates, 'var value: String by mutableStateOf("delegate-v2")', 'var value: Int by mutableStateOf(17)'),
            (delegates, 'value = "delegate-edited"', 'value = 42'),
            (delegates, 'value = "migrated"', 'value = 29')])
        self.abi('PropertyDelegates', ['androidx.compose.runtime.MutableState value$delegate;', 'int getValue();'])
        self.c.new_case('property-explicit-delegate-migration')
        self.d.tap_text('Migrate delegate')
        self.old_delegate = '29'
        self.inspect()
        self.c.common()
        self.c.results.append(dict(name='property-explicit-delegate-migration', result='PASS', applicationMigration=True))
        self.data_old.update(tag='null', rank=0)
        self.data_new.update(tag='tag-v2', rank=9)
        accept('property-data-additions', [
            (data, 'var amount: Int = 3)', 'var amount: Int = 3, val tag: String? = "tag-v2", val rank: Int = 9)'),
            (probe, 'val (title, amount) = value', 'val (title, amount, tag, rank) = value'),
            (probe, '"$title|$amount|$value"', '"$title|$amount|$tag|$rank|$value"')])
        self.abi('PropertyData', ['java.lang.String component3();', 'int component4();', 'copy(java.lang.String, int, java.lang.String, int)'])
        self.data_old['amount'], self.data_new['amount'] = 0, 6
        accept('property-data-retype', [(data, 'var amount: Int = 3', 'var amount: Long = 6L')])
        self.abi('PropertyData', ['long component2();', 'copy(java.lang.String, long, java.lang.String, int)'])
        self.data_new.update(title='data-v2', tag='tag-v3', rank=12)
        accept('property-data-defaults', [(data, '"data-v1"', '"data-v2"'), (data, '"tag-v2"', '"tag-v3"'), (data, 'rank: Int = 9', 'rank: Int = 12')])
        guard = SOURCE / 'StateAdmissionGuard.java'
        self.c.edit(guard, 'public class StateAdmissionGuard', 'public abstract class StateAdmissionGuard')
        for path, old, new in both('// ADDED_PROPERTIES', 'val pending: String? = "committed"\n    // ADDED_PROPERTIES') + both('${addedLength()}"', '${addedLength()}|$pending"'):
            self.c.edit(path, old, new)
        host, elapsed = self.c.trigger('property-atomic-rejection')
        assert 'success=false' in host and 'class made abstract has live instances' in host, 'Missing native rejection'
        self.inspect()
        self.c.common()
        self.c.results.append(dict(name='property-atomic-rejection', result='PASS', reloadSeconds=round(elapsed, 3), nativeRejection=True))
        self.old_suffix += '|null'
        self.new_suffix += '|committed'
        accept('property-recovery', [(guard, 'public abstract class StateAdmissionGuard', 'public class StateAdmissionGuard')])
