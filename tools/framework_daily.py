"""Search cancellation, state-based forms and nested-scroll acceptance."""
import time
from framework_ui import UiFeatureCases


class DailyCases(UiFeatureCases):
    def search_races(self):
        self.r.new_case('search-debounce-starts-only-final-query')
        self.enter('Search'); self.d.tap_text('Search burst')
        self.wait('Search query: abc', 'Search requests: abc;', 'Search deliveries: ')
        anchor = 'Search anchor: ' + self.get(self.r.snapshot(), 'Search anchor: ')
        self.r.passed('search-debounce-starts-only-final-query')
        self.edit('search-edit-while-original-request-pending', 'SearchLogic.kt', 'search-v1:', 'search-v2:')
        self.wait(anchor, 'Search requests: abc;', 'Search result: none')
        self.d.tap_text('Search latest')
        self.wait(anchor, 'Search query: latest', 'Search requests: abc;latest;', 'Search cancelled: abc;')
        self.d.tap_text('Release latest')
        self.wait(anchor, 'Search responses: 1', 'Search result: search-v2:latest', 'Search deliveries: search-v2:latest;')
        self.r.passed('search-edit-while-original-request-pending', reload=True)
        self.r.new_case('search-late-cancelled-response-never-overwrites')
        self.d.tap_text('Release stale')
        self.wait(anchor, 'Search responses: 2', 'Search result: search-v2:latest', 'Search deliveries: search-v2:latest;')
        time.sleep(.6)
        self.wait(anchor, 'Search deliveries: search-v2:latest;')
        self.r.passed('search-late-cancelled-response-never-overwrites')
        self.d.tap_text('Search final'); self.wait('Search requests: abc;latest;final;')
        self.edit('search-retained-mapper-after-cancellation', 'SearchScreen.kt', 'searchResult(it)', 'searchResult(it) + ":mapped"')
        self.wait(anchor, 'Search result: search-v2:latest')
        self.d.tap_text('Release final')
        self.wait(anchor, 'Search responses: 3', 'Search result: search-v2:final:mapped',
                  'Search deliveries: search-v2:latest;search-v2:final:mapped;')
        self.r.passed('search-retained-mapper-after-cancellation', reload=True)
        self.r.new_case('search-home-gc-and-disposal')
        self.d.tap_text('GC: '); self.resume_original()
        self.wait(anchor, 'Search query: final', 'Search requests: abc;latest;final;', 'Search cancelled: abc;',
                  'Search deliveries: search-v2:latest;search-v2:final:mapped;')
        self.leave(); self.wait('Search lifetime: 1:1:0')
        self.r.passed('search-home-gc-and-disposal')

    def state_form(self):
        partial = getattr(self.r.args, 'form_phase', 'all') == 'transforms'
        validation = 'v1' if partial else 'v2'
        self.r.new_case('form-transform-prerequisite' if partial else 'form-real-composition-and-state-objects')
        self.enter('Form'); self.d.tap_text('Focus form'); self.wait('Form focus: name')
        self.r.ime.command('select', selection=(0,4)); self.r.ime.command('compose', '中文草稿')
        self.r.ime.command('select', selection=(1,1))
        self.wait('Form name: 中文草稿:1:1:0:4')
        anchor = 'Form anchor: ' + self.get(self.r.snapshot(), 'Form anchor: ')
        connection = self.r.ime.command('status')
        if not partial:
            self.r.passed('form-real-composition-and-state-objects')
            self.edit('form-validation-keeps-composition-and-connection', 'FormLogic.kt', 'valid-v1:', 'valid-v2:')
            self.wait(anchor, 'Form focus: name', 'Form name: 中文草稿:1:1:0:4', 'Form validation: valid-v2:中文草稿')
            assert self.r.ime.command('status') == connection, 'IME connection restarted during reload'
            self.r.passed('form-validation-keeps-composition-and-connection', reload=True)
            self.r.new_case('form-ime-next-moves-focus-once')
        self.r.ime.command('commit', '中文草稿'); self.r.ime.command('next')
        self.wait(anchor, 'Form name: 中文草稿:4:4:none', 'Form focus: number', 'Form actions: 1:0:none')
        # InputConnection offsets include the output-only prefix; raw text offsets do not.
        self.r.ime.command('select', selection=(3,3))
        self.wait('Form number: 12:2:2')
        if not partial:self.r.passed('form-ime-next-moves-focus-once')
        connection = self.r.ime.command('status')
        self.r.edit('FormLogic.kt', 'value.length <= 4', 'value.length <= 2')
        self.edit('form-retained-input-and-output-transformations', 'FormLogic.kt', 'String = "#"', 'String = "@"')
        self.wait(anchor, 'Form number: 12:2:2', 'Form focus: number')
        assert self.r.ime.command('status') == connection, 'Transform edit restarted connection'
        self.r.ime.command('commit', '3')
        self.wait(anchor, 'Form number: 12:2:2')
        self.r.ime.command('select', selection=(0,3)); self.r.ime.command('commit', '45')
        self.wait(anchor, 'Form number: 45:2:2')
        # Read the actual accessibility editor text, not an app-generated formatting label.
        assert any('@45' in t for t in self.r.snapshot()), 'OutputTransformation did not render new prefix'
        self.r.passed('form-retained-input-and-output-transformations', reload=True)
        self.edit('form-retained-done-action-delivers-once', 'FormScreen.kt', 'page.submits += 1', 'page.submits += 3')
        self.wait(anchor, 'Form focus: number', 'Form number: 45:2:2')
        self.r.ime.command('done')
        self.wait(anchor, f'Form actions: 1:3:valid-{validation}:中文草稿:45')
        self.r.passed('form-retained-done-action-delivers-once', reload=True)
        self.r.new_case('form-home-resume-and-disposal')
        self.resume_original(); self.d.tap_text('GC: ')
        self.wait(anchor, 'Form name: 中文草稿:4:4:none', 'Form number: 45:2:2', f'Form actions: 1:3:valid-{validation}:中文草稿:45')
        self.leave(); self.wait('Form lifetime: 1:1')
        self.r.passed('form-home-resume-and-disposal')

    def scroll_swipe(self, down=False):
        rows = [b for t,b in self.d.nodes() if t.startswith('Scroll row ')]
        assert len(rows) >= 3, 'Expected visible list rows'
        left=min(b[0] for b in rows); right=max(b[2] for b in rows)
        top=min(b[1] for b in rows); bottom=max(b[3] for b in rows)
        x=(left+right)//2
        a,b = (top+20,bottom-20) if down else (bottom-20,top+20)
        self.d.adb('shell','input','swipe',str(x),str(a),str(x),str(b),'700')

    def nested_scroll(self):
        self.r.new_case('scroll-real-drag-collapses-header-and-list')
        self.enter('Scroll'); self.d.tap_text('Seed scroll'); self.scroll_swipe()
        self.wait('Scroll event: scroll-v1', 'Scroll draft: edited-scroll')
        time.sleep(1)
        texts=self.r.snapshot(); position='Scroll position: '+self.get(texts,'Scroll position: ')
        parts=position.split(': ')[1].split(':'); assert int(parts[0])>0 and int(parts[2])==100
        anchor='Scroll anchor: '+self.get(texts,'Scroll anchor: ')
        self.r.passed('scroll-real-drag-collapses-header-and-list')
        self.edit('scroll-retained-nested-connection-body', 'ScrollLogic.kt', 'scroll-v1', 'scroll-v2')
        self.wait(anchor, position, 'Scroll draft: edited-scroll')
        self.scroll_swipe(); self.wait(anchor,'Scroll event: scroll-v2')
        self.r.passed('scroll-retained-nested-connection-body',reload=True)
        self.r.new_case('scroll-real-pull-starts-refresh-once')
        self.d.tap_text('Scroll top'); self.wait('Scroll position: 0:0:0')
        self.scroll_swipe(down=True)
        self.wait(anchor,'Scroll refresh: true:1:0:none')
        job='Scroll job: '+self.get(self.r.snapshot(),'Scroll job: ')
        self.r.passed('scroll-real-pull-starts-refresh-once')
        self.edit('scroll-edit-pending-refresh-keeps-job', 'ScrollLogic.kt', 'refresh-v1', 'refresh-v2')
        self.wait(anchor,job,'Scroll draft: edited-scroll','Scroll refresh: true:1:0:none')
        self.scroll_swipe(down=True)
        self.wait(anchor,job,'Scroll refresh: true:1:0:none')
        self.d.tap_text('Finish refresh')
        self.wait(anchor,'Scroll refresh: false:1:1:refresh-v2')
        self.r.passed('scroll-edit-pending-refresh-keeps-job',reload=True)
        self.r.new_case('scroll-home-gc-and-disposal')
        self.resume_original(); self.d.tap_text('GC: ')
        self.wait(anchor,'Scroll draft: edited-scroll','Scroll refresh: false:1:1:refresh-v2')
        self.leave(); self.wait('Scroll lifetime: 1:1')
        self.r.passed('scroll-home-gc-and-disposal')
