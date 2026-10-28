"""Bounded state evolution: added constant delegates and isolated child slot changes."""
from framework_ui import UiFeatureCases

FIELDS = '''var addedText by mutableStateOf("new-state")
    var addedInt by mutableIntStateOf(7)
    var addedLong by mutableLongStateOf(9L)
    var addedFloat by mutableFloatStateOf(1.5f)
    var addedDouble by mutableDoubleStateOf(2.5)
    var addedNullable: String? by mutableStateOf(null)'''
REPORT = '"$addedText:$addedInt:$addedLong:$addedFloat:$addedDouble:$addedNullable"'


class EvolutionCases(UiFeatureCases):
    def delegate_state(self):
        self.r.new_case('delegate-prerequisite')
        self.enter('Delegates'); self.d.tap_text('Seed delegate')
        anchor = 'Delegate anchor: ' + self.get(self.r.snapshot(), 'Delegate anchor: ')
        expected = (anchor, 'Delegate original: user-draft:1', 'Delegate created: 1')
        self.wait(*expected, 'Delegate added: absent')
        self.r.edit('DelegateModel.kt', '// ADDED_STATE', FIELDS)
        self.r.edit('DelegateModel.kt', '"absent"', REPORT)
        self.r.edit('DelegateModel.kt', 'fun changeAdded() {}', 'fun changeAdded() { addedText = "user-state"; addedInt++ }')
        if getattr(self.r.args, 'delegate_phase', 'all') != 'addition':
            self.r.edit('DelegateGuard.kt', 'class DelegateGuard', 'abstract class DelegateGuard')
            self.r.reload('delegate-rejected-batch-keeps-original', success=False)
            self.wait(*expected, 'Delegate added: absent')
            self.r.passed('delegate-rejected-batch-keeps-original', rejectedReload=True)
            self.r.edit('DelegateGuard.kt', 'abstract class DelegateGuard', 'class DelegateGuard')
        self.r.reload('delegate-added-state-on-original-object')
        self.wait(*expected, 'Delegate added: new-state:7:9:1.5:2.5:null')
        self.d.tap_text('Change added')
        self.wait(*expected, 'Delegate added: user-state:8:9:1.5:2.5:null')
        self.r.passed('delegate-added-state-on-original-object', reload=True)
        self.edit('delegate-next-body-keeps-edited-state', 'DelegateModel.kt', REPORT, '"body-v2:$addedText:$addedInt:$addedLong:$addedFloat:$addedDouble:$addedNullable"')
        self.wait(*expected, 'Delegate added: body-v2:user-state:8:9:1.5:2.5:null')
        self.r.passed('delegate-next-body-keeps-edited-state', reload=True)
        self.r.new_case('delegate-fresh-object-and-cleanup')
        self.d.tap_text('Fresh delegate')
        self.wait(anchor, 'Delegate created: 2', 'Delegate fresh: body-v2:new-state:7:9:1.5:2.5:null', 'Delegate original: user-draft:1')
        self.resume_original(); self.d.tap_text('GC: ')
        self.wait(anchor, 'Delegate created: 2', 'Delegate added: body-v2:user-state:8:9:1.5:2.5:null')
        self.leave(); self.wait('Delegate lifetime: 1:1')
        self.r.passed('delegate-fresh-object-and-cleanup')

    def scoped_layout(self):
        self.r.new_case('layout-prerequisite')
        self.enter('Scoped layout'); self.d.tap_text('Seed layout'); self.d.tap_text('Click layout sibling'); self.d.tap_text('Click layout child')
        texts = self.r.snapshot()
        parent = 'Layout parent: ' + self.get(texts, 'Layout parent: ')
        sibling = 'Layout sibling: ' + self.get(texts, 'Layout sibling: ')
        child = self.get(texts, 'Layout child: ')
        self.edit('layout-add-child-slot-keeps-parent-and-sibling', 'ScopedLayoutScreen.kt', '// EXTRA_SLOT',
                  'val extra = remember { token() }; Text(layoutExtraLabel(extra), fontSize = 10.sp)')
        self.wait(parent, sibling)
        texts = self.r.snapshot(); current = self.get(texts, 'Layout child: ')
        assert current != child and current.endswith(':0'), 'Changed child must reset only its own slots'
        extra = 'Layout extra: ' + self.get(texts, 'Layout extra: ')
        self.r.passed('layout-add-child-slot-keeps-parent-and-sibling', reload=True)
        self.d.tap_text('Click layout child'); current = current.rsplit(':', 1)[0] + ':1'
        self.edit('layout-render-after-child-evolution', 'ScopedLayoutLogic.kt', '"Layout extra: $value"', '"Layout extra v2: $value"')
        self.wait(parent, sibling, 'Layout child: ' + current, extra.replace('Layout extra:', 'Layout extra v2:'))
        self.r.passed('layout-render-after-child-evolution', reload=True)
        self.r.new_case('layout-original-parent-cleanup')
        self.resume_original(); self.d.tap_text('GC: ')
        self.wait(parent, sibling, 'Layout child: ' + current)
        self.leave(); self.wait('Layout lifetime: 1:1')
        self.r.passed('layout-original-parent-cleanup')
