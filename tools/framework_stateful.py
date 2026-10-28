"""Bounded session soak and real HTTP -> Room -> Paging acceptance."""
import json
import subprocess
import time

from framework_ui import UiFeatureCases, APP


class StatefulCases(UiFeatureCases):
    def remote_feed(self):
        phase = getattr(self.r.args, 'feed_phase', 'all')
        self.r.metadata['feedPhase'] = phase
        if phase in ('refresh', 'refresh-finish'):
            return self.remote_feed_refresh_only()
        self.r.new_case('feed-initial-http-room-paging')
        self.enter('Remote feed')
        self.wait('Feed network: 1:0:0')
        anchor = 'Feed anchor: ' + self.get(self.r.snapshot(), 'Feed anchor: ')
        self.d.tap_text('Feed release')
        # An initially empty Room source queues a boundary APPEND behind REFRESH.
        self.wait(anchor, 'Feed network: 2:1:20', 'Feed storage: 20:1:0:feed-v1:19')
        self.r.passed('feed-initial-http-room-paging')
        self.r.new_case('feed-pending-append-retains-cache')
        self.d.tap_text('Feed append')
        self.wait(anchor, 'Feed draft: edited-feed', 'Feed network: 2:1:20',
                  'Feed storage: 20:1:0:feed-v1:19')
        position = 'Feed position: ' + self.get(self.r.snapshot(), 'Feed position: ')
        visible = 'Feed visible: ' + self.get(self.r.snapshot(), 'Feed visible: ')
        first = int(visible.split(': ')[1].split(':')[0]); assert first >= 10
        row = next(t for t in self.r.snapshot() if t.startswith(f'Feed row: {first}:'))
        self.r.passed('feed-pending-append-retains-cache')
        self.edit('feed-edit-pending-response-mapper', 'RemoteFeedLogic.kt', 'feed-v1:', 'feed-v2:')
        self.wait(anchor, row, 'Feed draft: edited-feed', position, 'Feed network: 2:1:20')
        self.d.tap_text('Feed release')
        self.wait(anchor, row, visible, 'Feed network: 2:2:-1', 'Feed storage: 30:2:0:feed-v2:29')
        assert int(self.get(self.r.snapshot(), 'Feed sources: ')) >= 3, 'Room writes did not invalidate PagingSource'
        self.r.passed('feed-edit-pending-response-mapper', reload=True)
        self.r.new_case('feed-503-preserves-database-and-scroll')
        self.d.tap_text('Feed fail append')
        self.wait(anchor, 'Feed network: 3:2:30')
        position = 'Feed position: ' + self.get(self.r.snapshot(), 'Feed position: ')
        visible = 'Feed visible: ' + self.get(self.r.snapshot(), 'Feed visible: ')
        first = int(visible.split(': ')[1].split(':')[0]); assert first >= 20
        row = next(t for t in self.r.snapshot() if t.startswith(f'Feed row: {first}:'))
        self.d.tap_text('Feed release')
        self.wait(anchor, row, 'Feed network: 3:3:-1', 'Feed storage: 30:2:1:feed-v2:29', 'Feed status: NotLoading:Error')
        self.r.passed('feed-503-preserves-database-and-scroll')
        self.edit('feed-edit-error-and-retry-once', 'RemoteFeedLogic.kt', 'feed-v2:', 'feed-v3:')
        self.wait(anchor, row, position, 'Feed storage: 30:2:1:feed-v2:29')
        self.d.tap_text('Feed retry'); self.wait('Feed network: 4:3:30'); self.d.tap_text('Feed release')
        self.wait(anchor, row, visible, 'Feed network: 4:4:-1', 'Feed storage: 40:3:1:feed-v3:39')
        self.r.passed('feed-edit-error-and-retry-once', reload=True)
        self.remote_feed_refresh_checks(anchor, 'feed-v3')

    def remote_feed_refresh_only(self):
        # Recreate the necessary 40-row/error state; do not rerun accepted hot-edit cases.
        self.r.new_case('feed-refresh-prerequisite')
        self.enter('Remote feed'); self.wait('Feed network: 1:0:0')
        self.d.tap_text('Feed release'); self.wait('Feed storage: 20:1:0:feed-v1:19')
        self.d.tap_text('Feed append'); self.wait('Feed network: 2:1:20')
        self.d.tap_text('Feed release'); self.wait('Feed storage: 30:2:0:feed-v1:29')
        self.d.tap_text('Feed fail append'); self.wait('Feed network: 3:2:30')
        self.d.tap_text('Feed release'); self.wait('Feed storage: 30:2:1:feed-v1:29')
        self.d.tap_text('Feed retry'); self.wait('Feed network: 4:3:30')
        self.d.tap_text('Feed release'); self.wait('Feed storage: 40:3:1:feed-v1:39')
        anchor = 'Feed anchor: ' + self.get(self.r.snapshot(), 'Feed anchor: ')
        self.remote_feed_refresh_checks(anchor, 'feed-v1')

    def remote_feed_refresh_checks(self, anchor, old_label):
        finish_only = getattr(self.r.args, 'feed_phase', 'all') == 'refresh-finish'
        self.r.new_case('feed-refresh-error-prerequisite' if finish_only else 'feed-refresh-failure-preserves-existing-rows')
        self.d.tap_text('Feed fail refresh'); self.wait('Feed network: 5:4:0')
        visible = 'Feed visible: ' + self.get(self.r.snapshot(), 'Feed visible: ')
        self.d.tap_text('Feed release')
        self.wait(anchor, visible, 'Feed network: 5:5:-1', f'Feed storage: 40:3:2:{old_label}:39', 'Feed status: Error:NotLoading')
        if not finish_only:self.r.passed('feed-refresh-failure-preserves-existing-rows')
        self.d.tap_text('Feed retry'); self.wait('Feed network: 6:5:0')
        self.edit('feed-refresh-new-mapping-single-transaction', 'RemoteFeedLogic.kt', old_label + ':', 'feed-v4:')
        self.wait(anchor, 'Feed draft: edited-feed', 'Feed network: 6:5:0', f'Feed storage: 40:3:2:{old_label}:39')
        self.d.tap_text('Feed release')
        self.wait(anchor, 'Feed draft: edited-feed', visible, 'Feed network: 7:6:20', 'Feed storage: 20:4:2:feed-v4:19', 'Feed offsets: 0,20,30,30,0,0,20')
        self.r.passed('feed-refresh-new-mapping-single-transaction', reload=True)
        self.r.new_case('feed-home-resume-and-disposal')
        self.resume_original()
        self.wait(anchor, 'Feed draft: edited-feed', 'Feed network: 7:6:20', 'Feed storage: 20:4:2:feed-v4:19', 'Feed offsets: 0,20,30,30,0,0,20')
        self.d.tap_text('Feed release')
        self.wait(anchor, visible, 'Feed network: 7:7:-1', 'Feed storage: 30:5:2:feed-v4:29', 'Feed offsets: 0,20,30,30,0,0,20')
        self.leave(); self.wait('Feed lifetime: 1:1')
        self.r.passed('feed-home-resume-and-disposal')

    def session_metrics(self):
        self.d.tap_text('Session metrics')
        value = json.loads(self.get(self.r.snapshot(), 'Session metrics: '))
        value['hostRss'] = int(subprocess.check_output(['ps', '-o', 'rss=', '-p', str(self.r.host_pid)], text=True).strip()) * 1024
        assert value['workers'] == value['listeners'] == value['registrations'] == 1
        assert value['removals'] == 0
        assert value['gapMs'] <= 5000, 'Foreground heartbeat exceeded predeclared 5 seconds'
        return value

    def long_session(self):
        # Frozen before round 1. These bounds are a soak acceptance envelope, not a benchmark.
        plan = {'rounds': 30, 'minimumSeconds': 1800, 'minimumRoundSpacingSeconds': 60,
                'warmupRounds': 5, 'resourceCheckpoints': [5, 10, 20, 30], 'samplesPerCheckpoint': 3,
                'reloadLimitSeconds': 120, 'foregroundGapLimitMs': 5000,
                'growthLimits': {'heap': [32*1024*1024, .4], 'native': [16*1024*1024, .3],
                                 'threads': [8, 0], 'fds': [12, 0], 'hostRss': [512*1024*1024, .5]}}
        (self.r.evidence / 'session-plan.json').write_text(json.dumps(plan, indent=2))
        self.r.new_case('session-soak-original-state-and-worker')
        self.enter('Session'); self.d.tap_text('Seed session'); self.d.tap_text('Session event')
        self.wait('Session counts: 1:1', 'Session draft: edited-session', 'Session result: session-v1:edited-session')
        anchor = 'Session anchor: ' + self.get(self.r.snapshot(), 'Session anchor: ')
        before = self.session_metrics()
        assert before['lastVersion'] == 'session-v1'
        self.r.passed('session-soak-original-state-and-worker')
        started = time.monotonic(); total = 1; baseline = None; previous_round_start = started - 60
        def idle_until(deadline):
            while time.monotonic() < deadline:
                self.r.session.check()
                assert self.d.adb('shell', 'pidof', APP).strip() == self.r.pid
                time.sleep(min(2, max(0, deadline-time.monotonic())))
        for round_number in range(1, plan['rounds']+1):
            idle_until(previous_round_start + 60)
            previous_round_start = time.monotonic()
            previous_step = 1 + (round_number-1)//5
            step = 1 + round_number//5
            if step != previous_step:
                self.r.edit('LongSessionLogic.kt', f'Int = {previous_step}', f'Int = {step}')
            self.edit(f'session-soak-round-{round_number:02d}', 'LongSessionLogic.kt',
                      f'"session-v{round_number}"', f'"session-v{round_number+1}"')
            assert self.r.elapsed <= plan['reloadLimitSeconds'], 'Reload exceeded frozen 120 second limit'
            self.wait(anchor, f'Session counts: {round_number}:{total}', 'Session draft: edited-session')
            self.d.tap_text('Session event'); total += step
            self.wait(anchor, f'Session counts: {round_number+1}:{total}', f'Session result: session-v{round_number+1}:edited-session')
            if round_number % 5 == 0:
                self.resume_original()
                self.wait(anchor, f'Session counts: {round_number+1}:{total}')
            self.d.tap_text('GC: ')
            idle_until(time.monotonic()+1)
            samples = []
            for _ in range(3 if round_number in plan['resourceCheckpoints'] else 1):
                sample = self.session_metrics(); samples.append(sample)
                idle_until(time.monotonic()+1)
            (self.r.case / 'resource-samples.json').write_text(json.dumps(samples, indent=2))
            assert all(s['lastVersion'] == f'session-v{round_number+1}' and s['ticks'] > before['ticks'] for s in samples)
            expected = {f'session-v{n}' for n in range(1, round_number+2)}
            assert all(set(s['versions'].split(',')) == expected for s in samples), 'Worker missed a version or observed an invalid result'
            if round_number == 5:
                baseline = {k: max(s[k] for s in samples) for k in plan['growthLimits']}
                (self.r.evidence / 'session-resource-baseline.json').write_text(json.dumps(baseline, indent=2))
            elif round_number in plan['resourceCheckpoints']:
                for metric, (absolute, fraction) in plan['growthLimits'].items():
                    limit = baseline[metric] + max(absolute, baseline[metric]*fraction)
                    assert all(s[metric] <= limit for s in samples), 'Resource growth exceeded frozen limit: ' + metric
            before = samples[-1]
            self.r.passed(f'session-soak-round-{round_number:02d}', reload=True, sessionSeconds=round(time.monotonic()-started, 1))
        idle_until(started+plan['minimumSeconds'])
        self.r.new_case('session-soak-final-state-and-disposal')
        self.wait(anchor, f'Session counts: 31:{total}', 'Session draft: edited-session')
        final = self.session_metrics()
        assert final['ticks'] > before['ticks'] and final['lastVersion'] == 'session-v31'
        (self.r.case / 'final-metrics.json').write_text(json.dumps(final, indent=2))
        self.leave(); self.wait('Session lifetime: 1:1:0:0')
        self.r.passed('session-soak-final-state-and-disposal', sessionSeconds=round(time.monotonic()-started, 1))
