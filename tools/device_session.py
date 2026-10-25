"""Fail closed on physical input or process replacement during device acceptance.

ADB's injected taps do not appear in the kernel input stream. All raw device data
stays in the caller's ignored evidence directory. No action is retried here.
"""
import json
import re
import subprocess
import time


class DeviceSessionInterrupted(AssertionError):
    pass


class DeviceSession:
    def __init__(self, device, evidence, package):
        self.device = device
        self.evidence = evidence
        self.package = package
        self.original_adb = device.adb
        self.pid = None
        self.failure = None
        self.processes = []
        self.files = []
        self.events = None
        self.reader = None
        self.partial = ''
        self.sequence = 0

    def start(self):
        for name, arguments in [
            ('input-events.txt', ['shell', 'getevent', '-lt']),
            ('system.logcat', ['logcat', '-v', 'threadtime', '-T', '1']),
        ]:
            output = (self.evidence / name).open('w')
            self.files.append(output)
            process = subprocess.Popen(['adb', '-s', self.device.serial, *arguments],
                                       stdout=output, stderr=subprocess.STDOUT)
            self.processes.append(process)
        self.events = self.processes[0]
        self.reader = (self.evidence / 'input-events.txt').open()
        deadline = time.monotonic() + 5
        while 'add device ' not in (self.evidence / 'input-events.txt').read_text():
            self.check()
            if time.monotonic() >= deadline:
                self.reject('input-monitor-unavailable')
            time.sleep(0.05)
        self.check()
        self.device.adb = self.adb

    def bind_process(self, pid):
        if not re.fullmatch(r'\d+', pid):
            self.reject('application-process-unavailable')
        self.pid = pid
        (self.evidence / 'session.json').write_text(json.dumps({'pid': pid, 'package': self.package}))

    def reject(self, reason):
        self.failure = reason
        (self.evidence / 'session-interruption.json').write_text(json.dumps({'reason': reason}))
        raise DeviceSessionInterrupted(reason + '; inspect preserved session evidence')

    def check(self):
        if self.failure:
            raise DeviceSessionInterrupted(self.failure + '; inspect preserved session evidence')
        if self.events is not None and self.events.poll() is not None:
            self.reject('input-monitor-stopped')
        if len(self.processes) > 1 and self.processes[1].poll() is not None:
            self.reject('logcat-monitor-stopped')
        if self.reader is None:
            return
        lines = (self.partial + self.reader.read()).split('\n')
        self.partial = lines.pop()
        for line in lines:
            if re.search(r'\bBTN_TOUCH\s+DOWN\b', line):
                self.reject('external-touch')
            contact = re.search(r'\bABS_MT_TRACKING_ID\s+([0-9a-f]+)\s*$', line)
            if contact and contact[1] != 'ffffffff':
                self.reject('external-touch')

    def adb(self, *args, **kwargs):
        self.check()
        ui_read = args[:3] == ('exec-out', 'uiautomator', 'dump')
        if ui_read and self.pid:
            if self.original_adb('shell', 'pidof', self.package, check=False).strip() != self.pid:
                self.reject('application-process-changed')
        started = time.monotonic()
        self.sequence += 1
        result = self.original_adb(*args, **kwargs)
        with (self.evidence / 'adb-trace.jsonl').open('a') as trace:
            trace.write(json.dumps({'sequence': self.sequence, 'arguments': args,
                                    'seconds': time.monotonic() - started, 'stdout': result}) + '\n')
        self.check()
        if ui_read and self.pid:
            if self.original_adb('shell', 'pidof', self.package, check=False).strip() != self.pid:
                self.reject('application-process-changed')
            packages = set(re.findall(r'package="([^"]+)"', result)) - {''}
            if packages and self.package not in packages:
                self.reject('application-window-covered')
        return result

    def close(self):
        self.device.adb = self.original_adb
        # Keep the original process's messages even if Android already restarted it.
        try:
            if self.pid:
                (self.evidence / 'original-process.logcat').write_text(
                    self.original_adb('logcat', '-d', '--pid=' + self.pid, check=False))
        except Exception as error:
            (self.evidence / 'original-process-logcat-error.txt').write_text(str(error))
        finally:
            for process in self.processes:
                process.terminate()
            for process in self.processes:
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if self.reader:
                self.reader.close()
            for output in self.files:
                output.close()
