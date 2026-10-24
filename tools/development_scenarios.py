"""State ownership and retained-object refactoring through real source edits and UI input."""
import json
from pathlib import Path
import re
import time

SOURCE = Path(__file__).resolve().parent.parent / 'sample/src/main/java/dev/compose/hotreload/sample'
DEVELOPMENT_SOURCES = [SOURCE / name for name in (
    'StateEditActivity.kt', 'StateEditModel.kt', 'StateEditScreen.kt', 'StateEditLayout.kt',
    'StatefulEditComponents.kt', 'StateEditRowLabel.kt', 'RefactorLogic.kt', 'RefactorService.java', 'StateAdmissionGuard.java')]
GENERATED_SOURCES = [SOURCE / name for name in ('RefactorOutcome.kt', 'RefactorFormatting.kt', 'RefactorPolicy.java')]
PACKAGE = 'package dev.compose.hotreload.sample\n'


class DevelopmentCases:
    def __init__(self, context):
        self.c = context
        self.d = context.device
        self.identities = {}
        self.panels = {}
        self.rows = {}
        self.objects = {}
        self.calls = 0
        self.result = 'No refactor'
        self.return_type = 'None'
        self.label = 'Row'
        self.detail = None
        self.order = list('ABCDEFGH')
        self.anchor = None

    def tap(self, prefix):
        nodes = self.d.nodes()
        text, bounds = next((text, bounds) for text, bounds in nodes if text.startswith(prefix))
        x1, y1, x2, y2 = bounds
        assert x2 > x1 and y2 > y1, 'Target is clipped: ' + prefix
        with (self.c.case_dir() / 'taps.jsonl').open('a') as log:
            log.write(json.dumps(dict(target=text, bounds=bounds, nodes=nodes)) + '\n')
        self.d.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(.5)

    def value(self, texts, prefix):
        return self.c.value(texts, prefix)

    def observe(self, texts):
        for text in texts:
            match = re.fullmatch(r'Panel (Left|Right):([a-f0-9]+):(\d+)', text)
            if match:
                key, token, count = match.groups()
                actual = (token, int(count))
                if key in self.panels:
                    assert self.panels[key] == actual, f'Panel {key} state moved: {self.panels[key]} -> {actual}'
                else:
                    self.panels[key] = actual
            match = re.fullmatch(re.escape(self.label) + r' ([A-Z]):([a-f0-9]+):(\d+)', text)
            if match:
                key, token, count = match.groups()
                actual = (token, int(count))
                if key in self.rows:
                    assert self.rows[key] == actual, f'Row {key} state moved: {self.rows[key]} -> {actual}'
                else:
                    assert int(count) == 0, f'New row {key} inherited state'
                    self.rows[key] = actual
            match = re.fullmatch(r'Object ([A-Z]):([a-f0-9]+)', text)
            if match:
                key, token = match.groups()
                if key in self.objects:
                    assert self.objects[key] == token, f'Business object {key} replaced'
                self.objects[key] = token

    def invariant(self):
        assert self.d.adb('shell', 'pidof', 'dev.compose.hotreload.sample').strip() == self.c.app_pid(), 'Process changed'
        texts = self.c.snapshot()
        for prefix, token in self.identities.items():
            assert self.value(texts, prefix) == token, prefix + ' replaced'
        assert 'Service retained: true' in texts, 'Original service reference changed'
        assert 'Draft: state42' in texts, 'Typed input lost'
        if self.detail:
            assert 'Detail: ' + self.detail in texts, 'Selection or selected object changed'
        assert 'Calls: ' + str(self.calls) in texts, 'Invocation count changed'
        assert 'Refactor: ' + self.result in texts, 'Unexpected retained-object result'
        assert 'Return type: ' + self.return_type in texts, 'Unexpected actual return type'
        self.observe(texts)
        if self.anchor:
            key, index, offset = self.value(texts, 'Anchor: ').split(':')
            assert key == self.anchor[0], f'Anchor key changed: {key}, expected {self.anchor[0]}'
            assert int(index) == self.order.index(key), 'Anchor index does not follow item key'
            assert abs(int(offset) - self.anchor[1]) <= 2, 'Scroll offset changed'
        (self.c.case_dir() / 'state-observation.json').write_text(json.dumps(dict(
            identities=self.identities, panels=self.panels, rows=self.rows, objects=self.objects,
            detail=self.detail, anchor=self.anchor, order=self.order, calls=self.calls,
            result=self.result, returnType=self.return_type), indent=2))
        return texts

    def invoke(self, expected, return_type):
        self.calls += 1
        self.result, self.return_type = expected, return_type
        self.tap('Run refactor')
        self.d.wait_for(lambda ts: 'Calls: ' + str(self.calls) in ts, 'retained service invocation')
        self.invariant()

    def increment(self, kind, key):
        states = self.panels if kind == 'Panel' else self.rows
        token, count = states[key]
        states[key] = (token, count + 1)
        self.tap('Panel ' + key + ':' if kind == 'Panel' else key + ' +')
        self.d.wait_for(lambda ts: f'{kind if kind == "Panel" else self.label} {key}:{token}:{count + 1}' in ts,
                        'increment moved ' + kind + ' ' + key)
        self.invariant()

    def start(self):
        xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
        (self.c.case_dir() / 'input-ui.xml').write_text(xml)
        field = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        assert field, 'Missing list draft input'
        x1, y1, x2, y2 = map(int, field.groups())
        self.d.adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        self.d.adb('shell', 'input', 'text', 'state42')
        self.d.wait_for(lambda ts: 'Draft: state42' in ts, 'typed list draft')
        # Clear focus through a visible control above the IME. BACK may leave the page when
        # a hardware keyboard suppresses the input view; lower buttons can be covered by the IME.
        self.tap('Finish input')
        self.calls, self.result, self.return_type = 1, 'java-v1:state42', 'String'
        self.tap('Run refactor')
        texts = self.d.wait_for(lambda ts: 'Refactor: java-v1:state42' in ts, 'baseline retained service')
        self.identities = {prefix: self.value(texts, prefix) for prefix in ('State model: ', 'State remember: ', 'Service: ')}
        self.invariant()
        self.increment('Panel', 'Left')
        self.increment('Panel', 'Left')
        self.increment('Panel', 'Right')
        self.increment('Row', 'B')
        self.increment('Row', 'B')
        self.increment('Row', 'C')
        self.tap('Open B')
        self.detail = 'B:' + self.objects['B']
        self.invariant()

    def placement(self, panels, list_first):
        nodes = dict(self.d.nodes())
        panel_bounds = [next(bounds for text, bounds in nodes.items() if text.startswith('Panel ' + key + ':'))
                        for key in panels]
        assert panel_bounds[0][0] < panel_bounds[1][0], 'Panel order did not update on screen'
        row_y = min(bounds[1] for text, bounds in nodes.items() if text.startswith(self.label + ' '))
        assert (row_y < panel_bounds[0][1]) == list_first, 'Section order did not update on screen'
        self.invariant()

    def scroll_to(self, key):
        self.anchor = None
        for _ in range(20):
            texts = self.invariant()
            current, _, offset = self.value(texts, 'Anchor: ').split(':')
            if current == key:
                self.anchor = (key, int(offset))
                return
            xml = self.d.adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
            match = re.search(r'content-desc="Dynamic list"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
            assert match, 'Missing dynamic list bounds'
            x1, y1, x2, y2 = map(int, match.groups())
            center = (y1 + y2) // 2
            delta = max(20, (y2 - y1) // 5)
            forward = self.order.index(current) < self.order.index(key)
            self.d.adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str(center + (delta if forward else -delta)),
                       str((x1 + x2) // 2), str(center), '1400')
            time.sleep(.3)
        raise AssertionError('Could not scroll to key ' + key)

    def compose(self):
        layout = SOURCE / 'StateEditLayout.kt'
        self.c.accepted('panels-reorder', [(layout, 'listOf("Left", "Right")', 'listOf("Right", "Left")')],
                        action=lambda: self.placement(['Right', 'Left'], False))
        self.increment('Panel', 'Left')
        self.c.accepted('sections-reorder', [(layout, 'listOf("panels", "list")', 'listOf("list", "panels")')],
                        action=lambda: self.placement(['Right', 'Left'], True))
        self.scroll_to('D')
        for name, order in [('list-insert-before-anchor', list('XABCDEFGH')),
                            ('list-reorder', list('HGFEDCBAX')),
                            ('list-remove-and-add', list('HGFEDICBX'))]:
            old = 'listOf(' + ', '.join('"' + item + '"' for item in self.order) + ')'
            new = 'listOf(' + ', '.join('"' + item + '"' for item in order) + ')'
            self.order = order
            self.c.accepted(name, [(layout, old, new)], action=self.invariant)
        self.increment('Row', 'I')
        self.render_recovery()

    def render_recovery(self):
        label, guard = SOURCE / 'StateEditRowLabel.kt', SOURCE / 'StateAdmissionGuard.java'
        item = 'I' if 'I' in self.rows else 'B'
        token, count = self.rows[item]
        self.label = 'Entry'
        self.c.accepted('row-label-update', [(label, 'Text("Row ', 'Text("Entry ')],
                        visible=[f'Entry {item}:{token}:{count}'])
        self.c.edit(guard, 'public class StateAdmissionGuard', 'public abstract class StateAdmissionGuard')
        self.c.edit(label, 'Text("Entry ', 'Text("Recovered ')
        host, elapsed = self.c.trigger('state-render-rejection')
        assert 'success=false' in host and 'class made abstract has live instances' in host, 'Expected native live-object rejection'
        self.increment('Row', 'C')
        self.c.common()
        self.c.results.append(dict(name='state-render-rejection', result='PASS', reloadSeconds=round(elapsed, 3)))
        print('PASS: state-render-rejection', flush=True)
        self.label = 'Recovered'
        self.c.accepted('state-render-recovery', [(guard, 'public abstract class StateAdmissionGuard', 'public class StateAdmissionGuard')],
                        visible=[f'Recovered {item}:{token}:{count}'])
        self.increment('Row', 'C')

    def refactoring(self):
        logic, java, model = (SOURCE / name for name in ('RefactorLogic.kt', 'RefactorService.java', 'StateEditModel.kt'))
        guard = SOURCE / 'StateAdmissionGuard.java'
        outcome, formatting, policy = GENERATED_SOURCES
        original = {p: p.read_text() for p in (logic, java, model)}
        self.c.accepted('kotlin-add-parameter', [
            (logic, 'input: String): String = service.format(input)', 'input: String, count: Int): String = service.format(input) + "|n=$count"'),
            (model, 'refactorText(service, draft)', 'refactorText(service, draft, 2)')],
            action=lambda: self.invoke('java-v1:state42|n=2', 'String'))
        self.c.accepted('java-change-parameters', [
            (java, 'format(String value)', 'format(CharSequence value, int count)'),
            (java, '"java-v1:" + value', '"java-v2:" + value + ":n=" + count'),
            (logic, 'service.format(input) + "|n=$count"', 'service.format(input, count)')],
            action=lambda: self.invoke('java-v2:state42:n=2', 'String'))
        self.c.accepted('java-and-kotlin-return-type', [
            (java, 'public String format(', 'public CharSequence format('),
            (java, 'return "java-v2:" + value + ":n=" + count;', 'return new StringBuilder("java-v2:" + value + ":n=" + count);'),
            (logic, 'count: Int): String', 'count: Int): CharSequence')],
            action=lambda: self.invoke('java-v2:state42:n=2', 'StringBuilder'))
        helper = '\nfun finishRefactor(value: CharSequence, count: Int): RefactorOutcome = RefactorOutcome("wrapped:$value", count)\n'
        self.c.accepted('kotlin-new-return-class', [
            (outcome, None, PACKAGE + 'class RefactorOutcome(val text: String, val count: Int)\n'),
            (logic, 'count: Int): CharSequence = service.format(input, count)',
             'count: Int): RefactorOutcome = finishRefactor(service.format(input, count), count)'),
            (logic, None, helper), (model, 'result = value.toString()', 'result = value.text')],
            action=lambda: self.invoke('wrapped:java-v2:state42:n=2', 'RefactorOutcome'))
        self.c.accepted('move-helper-to-another-file', [
            (logic, helper, '\n'), (formatting, None, PACKAGE + helper.replace('wrapped:', 'moved:'))],
            action=lambda: self.invoke('moved:java-v2:state42:n=2', 'RefactorOutcome'))
        self.c.accepted('rename-java-method-and-reorder-parameters', [
            (java, 'format(CharSequence value, int count)', 'render(int count, CharSequence value)'),
            (java, 'java-v2:', 'java-v3:'), (logic, 'service.format(input, count)', 'service.render(count, input)')],
            action=lambda: self.invoke('moved:java-v3:state42:n=2', 'RefactorOutcome'))
        previous_result = self.result
        self.c.edit(guard, 'public class StateAdmissionGuard', 'public abstract class StateAdmissionGuard')
        self.c.edit(policy, None, PACKAGE.rstrip() + ';\npublic final class RefactorPolicy { public static String prefix() { return "policy-v1:"; } }\n')
        self.c.edit(java, '"java-v3:"', 'RefactorPolicy.prefix()')
        self.c.edit(formatting, 'moved:', 'committed:')
        host, elapsed = self.c.trigger('refactor-atomic-rejection')
        assert 'success=false' in host and 'class made abstract has live instances' in host, 'Expected native rejection'
        self.invoke(previous_result, 'RefactorOutcome')
        self.c.common()
        self.c.results.append(dict(name='refactor-atomic-rejection', result='PASS', reloadSeconds=round(elapsed, 3),
                                   scope='Existing callers and retained objects remain unchanged; class addition is append-only'))
        print('PASS: refactor-atomic-rejection', flush=True)
        self.c.accepted('refactor-recovery-with-appended-class', [
            (guard, 'public abstract class StateAdmissionGuard', 'public class StateAdmissionGuard'), (policy, 'policy-v1:', 'policy-v2:')],
            action=lambda: self.invoke('committed:policy-v2:state42:n=2', 'RefactorOutcome'))
        for path, text in original.items():
            path.write_text(text)
        for path in GENERATED_SOURCES:
            path.unlink()
        self.c.accepted('restore-original-signatures', [], action=lambda: self.invoke('java-v1:state42', 'String'))
