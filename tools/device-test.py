#!/usr/bin/env python3
"""
End-to-end hot reload test on a device (arm64, API 30+, debuggable).

Runs `hotRunDebug` for sample, applies source edits, runs `reload`, and checks the UI with uiautomator.
Sources are restored on exit.

    python3 tools/device-test.py [--serial SERIAL] [--only NAME ...]
"""
import argparse
from ui_driver import UiDriver
import difflib
import hashlib
import shutil
import zipfile
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path

SAMPLE = Path(__file__).resolve().parent.parent / "sample"
APP = "dev.compose.hotreload.sample"
MAIN = SAMPLE / "src/main/java/dev/compose/hotreload/sample/MainActivity.kt"
JAVA = SAMPLE / "src/main/java/dev/compose/hotreload/sample/JavaGreeting.java"
LABELS = SAMPLE / "src/main/java/dev/compose/hotreload/sample/Labels.kt"
LIBRARY = SAMPLE / "lib/src/main/java/dev/compose/hotreload/sample/lib/LibraryCard.kt"
ADDED = SAMPLE / "src/main/java/dev/compose/hotreload/sample/Added.kt"
HOST_LOG = SAMPLE / "build/run/debug/debug.chr.log"
PID_FILE = HOST_LOG.with_name("debug.pid")
LOADED_LABELS_CALL = ('LoadedLabels().also { '
                      'System.getProperties()["compose.hotreload.loadedLabels"] = it }.required() + ')


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.ui_driver = None

    def adb(self, *args, check=True):
        if self.ui_driver is not None and args[:3] == ('exec-out', 'uiautomator', 'dump'):
            return self.ui_driver.dump()
        command = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        output = subprocess.run(command, check=check, capture_output=True, text=True, timeout=90).stdout
        if self.ui_driver is not None and args[:3] == ('shell', 'am', 'start') and '-n' in args:
            self.ui_driver.wait_for_foreground(args[args.index('-n') + 1].split('/')[0])
        return output

    def nodes(self):
        xml = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        return [(m.group(1), tuple(map(int, m.group(2, 3, 4, 5))))
                for m in re.finditer(r'text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]

    def texts(self):
        return [text for text, _ in self.nodes()]

    def tap_text(self, prefix):
        for text, (x1, y1, x2, y2) in self.nodes():
            if text.startswith(prefix):
                self.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                time.sleep(0.5)
                return
        raise AssertionError(f"No node starting with '{prefix}': {self.texts()}")

    def count(self):
        for text in self.texts():
            if text.startswith("Count: "):
                return int(text.removeprefix("Count: "))
        raise AssertionError(f"No counter: {self.texts()}")

    def wait_for(self, predicate, description, timeout=30, fail_on_reload=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            texts = self.texts()
            if predicate(texts):
                return texts
            if fail_on_reload:
                errors = self.logcat_errors()
                if "Reload failed" in errors:
                    report = capabilities(errors, "ART DCEVM capabilities after rejection: ")
                    raise AssertionError("Reload rejected: " + report.get("lastRejection", errors[-2000:]))
            time.sleep(1)
        raise AssertionError(f"Timed out waiting for {description}: {self.texts()}")

    def logcat(self, *filters):
        pid = self.adb("shell", "pidof", APP, check=False).strip()
        if not pid:
            return "<application not running>"
        return self.adb("logcat", "-d", f"--pid={pid}", *filters, check=False)

    def logcat_errors(self):
        return self.logcat("-s", "ComposeHotReload:E")


def gradle(*args, env):
    return subprocess.run([str(SAMPLE / "gradlew"), "-p", str(SAMPLE), "--console=plain", *args],
                          env=env, capture_output=True, text=True, timeout=300)


def edit(path, old, new):
    """old=None appends [new] (creates the file if needed)"""
    text = path.read_text() if path.exists() else ""
    if old is None:
        path.write_text(text + new)
        return
    assert old in text, f"'{old}' not found in {path.name}"
    path.write_text(text.replace(old, new, 1))


def reload(env):
    result = gradle("reload", env=env)
    assert result.returncode == 0, result.stdout[-3000:] + result.stderr[-3000:]


def scenario(name, edits, expected, retained=True, failure=None, retry_keep=(), retry_expected=()):
    """
    retained: whether 'remember' state must survive (None: decided by the upstream analysis)
    failure: expected failure message; the edits are reverted afterward and the application must recover
    """
    return dict(name=name, edits=edits, expected=expected, retained=retained, failure=failure,
                retry_keep=retry_keep, retry_expected=retry_expected)


SCENARIOS = [
    scenario("method body", [(MAIN, '"Hello Android Hot Reload"', '"Hello, reloaded"')], ["Hello, reloaded"]),
    scenario("statics", [(MAIN, "\"Edit this file, then run './gradlew reload'\"", '"Statics re-initialized"')],
             ["Statics re-initialized"]),
    scenario("library module", [(LIBRARY, '"Library module"', '"Library module, reloaded"')],
             ["Library module, reloaded"]),
    scenario("java lambda", [(JAVA, '() -> "Java lambda"', '() -> "Java lambda reloaded"')], ["JAVA LAMBDA RELOADED"]),
    scenario("java inserted lambda",
             [(JAVA, 'Supplier<String> supplier', 'Supplier<String> prefix = () -> "inserted ";\n'
                                                  '        Supplier<String> supplier'),
              (JAVA, 'upper.apply(supplier.get())', 'upper.apply(prefix.get() + supplier.get())')],
             ["INSERTED JAVA LAMBDA RELOADED"]),
    scenario("interface lambda", [(LABELS, '{ "Interface lambda" }', '{ "Interface lambda reloaded" }')],
             ["Interface lambda reloaded"]),
    scenario("interface inserted lambda",
             [(LABELS, "val text: () -> String =", 'val prefix: () -> String = { "inserted " }\n'
                                                   '        val text: () -> String ='),
              (LABELS, "return text()", "return prefix() + text()")],
             ["inserted Interface lambda"]),
    scenario("interface and implementation batch",
             [(LABELS, '"Interface lambda', '"Batch interface lambda'),
              (LABELS, '"Implementation"', '"Implementation reloaded"')],
             ["Batch interface lambda", "Implementation reloaded"]),
    scenario("interface new default",
             [(LABELS, "interface Labels {", 'interface Labels {\n    fun extra(): String = "Default method: "'),
              (LABELS, "fun labelsText(): String = ", "fun labelsText(): String = DefaultLabels.extra() + ")],
             ["Default method: "]),
    # alpha.3 supports retiring interface methods; callers compiled in the same batch use the new name.
    scenario("interface method retirement",
             [(LABELS, "fun label(): String", "fun renamedLabel(): String"),
              (LABELS, "DefaultLabels.label()", "DefaultLabels.renamedLabel()"),
              (LIBRARY, 'fun libraryText(): String = "', 'fun libraryText(): String = "Retired method: ')],
             ["Retired method: "]),
    scenario("kotlin inserted lambda", [(MAIN, 'Button(onClick = { count++ })',
                                         'Button(onClick = { count += 100 }) { Text("Add 100") }\n'
                                         '            Button(onClick = { count++ })')],
             ["Add 100"], retained=None),
    # In a new file: changing the number of lines of a file shifts the (virtual) lines of inlined code (see below)
    scenario("new class", [(ADDED, None, "package dev.compose.hotreload.sample\n\n"
                                         "class Added {\n    fun text() = \"Added class \"\n}\n"),
                           (MAIN, "fun greeting(): String = ", "fun greeting(): String = Added().text() + ")],
             ["Added class Hello, reloaded"]),
    # Upstream: Kotlin 2.2+ appends line numbers to inline local variable names, which are part of the upstream
    # scope hash. Changing the number of lines makes composables using inline functions ('remember') dirty:
    # their state is reset (as on the desktop).
    scenario("shifted lines (upstream behavior)", [(MAIN, "fun greeting(): String = ", "\n\nfun greeting(): String = ")],
             ["Added class Hello, reloaded"], retained=None),
    scenario("interface abstract batch",
             [(LABELS, "interface Labels {", "interface Labels {\n    fun required(): String"),
              (LABELS, "object DefaultLabels : Labels {", 'object DefaultLabels : Labels {\n'
                                                       '    override fun required(): String = "Abstract batch: "'),
              (LABELS, "fun labelsText(): String = ", "fun labelsText(): String = DefaultLabels.required() + ")],
             ["Abstract batch: "]),
    scenario("interface abstract existing implementation",
             [(LABELS, "interface Labels {", "interface Labels {\n    fun implementationText(): String"),
              (LABELS, "fun implementationText(): String =", "override fun implementationText(): String ="),
              (LABELS, "DefaultLabels.implementationText()", "(DefaultLabels as Labels).implementationText()"),
              (LABELS, "fun labelsText(): String = ", 'fun labelsText(): String = "Existing implementation: " + ')],
             ["Existing implementation: "]),
    # A boot-owned property retains an instance independently of application statics reinitialization.
    scenario("interface loaded implementation setup",
             [(LABELS, None, '\nclass LoadedLabels : Labels {\n'
                             '    override fun required(): String = "Loaded implementation: "\n'
                             '    override fun implementationText(): String = "Loaded"\n}\n'),
              (LABELS, "fun labelsText(): String = ", "fun labelsText(): String = " + LOADED_LABELS_CALL)],
             ["Loaded implementation: "]),
    # Making a class with live direct instances abstract remains unsupported in alpha.3.
    # Verify atomic rejection, the native reason, pending changes and recovery with a valid Kotlin edit.
    scenario("live class abstract rejection and retry",
             [(LABELS, "class LoadedLabels : Labels", "abstract class LoadedLabels : Labels"),
              (LABELS, LOADED_LABELS_CALL, ""),
              (LIBRARY, 'fun libraryText(): String = "', 'fun libraryText(): String = "Atomic retry: ')],
             [], failure="class made abstract has live instances",
             retry_keep=(LIBRARY,), retry_expected=("Atomic retry: ",)),
    scenario("interface abstract missing implementation",
             [(LABELS, "interface Labels {", "interface Labels {\n    fun missingImplementation(): String"),
              (LABELS, "object DefaultLabels : Labels {", 'object DefaultLabels : Labels {\n'
                                                        '    override fun missingImplementation(): String = "provided"'),
              (LABELS, '\nclass LoadedLabels : Labels {\n'
                       '    override fun required(): String = "Loaded implementation: "\n'
                       '    override fun implementationText(): String = "Loaded"\n}\n', ""),
              (LABELS, LOADED_LABELS_CALL,
               '(try { (Class.forName("dev.compose.hotreload.sample.LoadedLabels")'
               '.getDeclaredConstructor().newInstance() as Labels).missingImplementation(); '
               '"Unexpected implementation: " } catch (_: AbstractMethodError) { '
               '"Missing implementation: AbstractMethodError " }) + '),
              (LIBRARY, 'fun libraryText(): String = "', 'fun libraryText(): String = "Abstract addition: ')],
             ["Missing implementation: AbstractMethodError", "Abstract addition: "]),
]


def capabilities(log, prefix):
    matches = re.findall(re.escape(prefix) + r"(\{[^\n]+\})", log)
    return json.loads(matches[-1]) if matches else {}


def source_diff(sources):
    return "".join("".join(difflib.unified_diff(
        original.splitlines(keepends=True), path.read_text().splitlines(keepends=True),
        fromfile="a/" + str(path.relative_to(SAMPLE.parent)),
        tofile="b/" + str(path.relative_to(SAMPLE.parent)))) for path, original in sources.items())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--device-id", default="local-device")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]+", args.device_id) or args.device_id == args.serial:
        parser.error("--device-id must be an anonymous lowercase label")
    unknown = set(args.only or ()) - {case["name"] for case in SCENARIOS}
    if unknown:
        parser.error(f"Unknown scenarios: {sorted(unknown)}")
    selected = set(args.only or (case["name"] for case in SCENARIOS))
    dependencies = {"java inserted lambda": "java lambda", "new class": "method body",
                    "shifted lines (upstream behavior)": "new class",
                    "interface abstract existing implementation": "interface abstract batch",
                    "interface loaded implementation setup": "interface abstract existing implementation",
                    "live class abstract rejection and retry": "interface loaded implementation setup",
                    "interface abstract missing implementation": "interface loaded implementation setup"}
    while any(case in selected and required not in selected for case, required in dependencies.items()):
        selected.update(required for case, required in dependencies.items() if case in selected)
    if args.only and selected - set(args.only):
        print(f"Including prerequisites: {sorted(selected - set(args.only))}", flush=True)
    if ADDED.exists():
        parser.error(f"Refusing to overwrite {ADDED}")

    env = dict(os.environ)
    if args.serial:
        env["ANDROID_SERIAL"] = args.serial
    device = Device(args.serial)
    originals = {path: path.read_text() for path in (MAIN, JAVA, LABELS, LIBRARY)}
    evidence = SAMPLE.parent / "build/evidence" / f"device-{args.device_id}-{time.time_ns()}"
    evidence.mkdir(parents=True)
    print(f"Evidence: {evidence.relative_to(SAMPLE.parent)}", flush=True)

    print("Starting hotRunDebug ...", flush=True)
    HOST_LOG.unlink(missing_ok=True)
    run_log = (evidence / "hotRun.log").open("w")
    run = subprocess.Popen([str(SAMPLE / "gradlew"), "-p", str(SAMPLE), "--console=plain", "hotRunDebug", "-Pcompose.reload.devToolsHeadless=true"],
                           env=env, stdout=run_log, stderr=subprocess.STDOUT, start_new_session=True)
    results = []
    host_pid = None
    ui_driver = UiDriver(device, evidence)
    try:
        ui_driver.start()
        deadline = time.time() + 600
        while not (HOST_LOG.exists() and "Connected to" in HOST_LOG.read_text()):
            assert run.poll() is None, "hotRunDebug exited, see ignored hotRun.log"
            assert time.time() < deadline, "Timed out waiting for the application"
            time.sleep(2)
        device.wait_for(lambda texts: any(t.startswith("Count: ") for t in texts), "the application UI")
        host_pid = int(re.search(r"^pid=(\d+)$", PID_FILE.read_text(), re.MULTILINE).group(1))
        initial_log = device.logcat()
        (evidence / "startup.logcat").write_text(initial_log)
        initial_capabilities = capabilities(initial_log, "ART DCEVM capabilities: ")
        print("ART DCEVM capabilities: " + json.dumps(initial_capabilities), flush=True)
        metadata = dict(device=args.device_id, abi=device.adb("shell", "getprop", "ro.product.cpu.abi").strip(),
                        api=int(device.adb("shell", "getprop", "ro.build.version.sdk").strip()),
                        capabilities=initial_capabilities)
        apk_dir = SAMPLE / "build/outputs/apk/debug"
        apk_info = json.loads((apk_dir / "output-metadata.json").read_text())
        assert len(apk_info["elements"]) == 1, "Expected one APK"
        apk = apk_dir / apk_info["elements"][0]["outputFile"]
        digest = lambda data: hashlib.sha256(data).hexdigest()
        installed = device.adb("shell", "pm", "path", APP).strip().removeprefix("package:")
        assert device.adb("shell", "sha256sum", installed).split()[0] == digest(apk.read_bytes()), "Installed APK differs"
        shutil.copyfile(apk, evidence / "installed-base.apk")
        with zipfile.ZipFile(apk) as archive:
            engine = digest(archive.read("lib/arm64-v8a/libartdcevm.so"))
        metadata.update(apkSha256=digest(apk.read_bytes()), engineSha256=engine,
                        model=device.adb("shell", "getprop", "ro.product.model").strip(),
                        checkJni=device.adb("shell", "getprop", "debug.checkjni").strip(),
                        sourceCommit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=SAMPLE.parent, text=True).strip(),
                        scenarios=[case["name"] for case in SCENARIOS if case["name"] in selected])
        (evidence / "device.json").write_text(json.dumps(metadata, indent=2))
        assert metadata["abi"] == "arm64-v8a" and metadata["api"] >= 30, metadata
        device.adb("shell", "run-as", APP, "true")  # verifies the installed app is debuggable

        for case in SCENARIOS:
            name, edits, expected, retained, failure = (case[k] for k in ("name", "edits", "expected", "retained", "failure"))
            if name not in selected:
                continue
            for _ in range(3):
                device.tap_text("Count: ")
            before = device.count()
            before_texts = device.texts()
            sources = {path: path.read_text() for path, _, _ in edits if path.exists()}
            sources.update({path: "" for path, _, _ in edits if not path.exists()})
            for path, old, new in edits:
                edit(path, old, new)
            case_dir = evidence / re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
            case_dir.mkdir()
            (case_dir / "source.patch").write_text(source_diff(sources))
            host_position = len(HOST_LOG.read_text())
            try:
                device.adb("logcat", "-c")
                reload(env)
                if failure:
                    deadline = time.time() + 30
                    while failure not in device.logcat_errors():
                        assert time.time() < deadline, f"expected failure '{failure}'"
                        time.sleep(1)
                    failure_log = device.logcat()
                    (case_dir / "rejection.logcat").write_text(failure_log)
                    report = capabilities(failure_log, "ART DCEVM capabilities after rejection: ")
                    reason = report.get("lastRejection", "")
                    assert failure in reason, f"Missing native reason in capabilities: {report}"
                    (case_dir / "capabilities.json").write_text(json.dumps(report, indent=2))
                    deadline = time.time() + 10
                    while reason not in HOST_LOG.read_text()[host_position:]:
                        assert time.time() < deadline, "native reason did not reach host ReloadClassesResult"
                        time.sleep(0.2)
                    assert device.texts() == before_texts, "rejected batch changed the UI"
                    for path, text in sources.items():
                        if path not in case["retry_keep"]:
                            path.write_text(text)
                    device.adb("logcat", "-c")
                    reload(env)
                    if case["retry_expected"]:
                        device.wait_for(lambda texts: all(any(e in t for t in texts)
                                                         for e in case["retry_expected"]), "pending change retry")
                    else:
                        time.sleep(3)
                    assert "Reload failed" not in device.logcat_errors(), "did not recover after reverting"
                else:
                    device.wait_for(lambda texts: all(any(e in t for t in texts) for e in expected), str(expected),
                                    fail_on_reload=True)
                    report = capabilities(device.logcat(), "ART DCEVM capabilities after interface reload: ")
                    if report:
                        (case_dir / "capabilities.json").write_text(json.dumps(report, indent=2))
                    if name == "interface abstract batch":
                        assert report.get("interfaceRedefinition") is True, report
                after = device.count()
                if retained is True:
                    assert after == before, f"state lost: {before} -> {after}"
                note = f"count {before} -> {after}" + (", failed as expected and recovered" if failure else "")
                results.append((name, "PASS", note))
            except AssertionError as e:
                results.append((name, "FAIL", str(e).splitlines()[0][:200]))
                errors = device.logcat_errors().strip().splitlines()
                print("\n".join(errors[-15:]))
                if "not running" in device.logcat_errors():
                    break
            finally:
                (case_dir / "logcat.txt").write_text(device.logcat())
                (case_dir / "host.log").write_text(HOST_LOG.read_text()[host_position:])
            print(f"{results[-1][1]}: {name} ({results[-1][2]})", flush=True)
    finally:
        ui_driver.close()
        for path, text in originals.items():
            path.write_text(text)
        ADDED.unlink(missing_ok=True)
        # Stop only this test's host. Its shutdown hook also stops the application.
        if host_pid is not None:
            try:
                os.kill(host_pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        if run.poll() is None:
            os.killpg(run.pid, signal.SIGTERM)
            run.wait(timeout=60)
        run_log.close()
        (evidence / "summary.json").write_text(json.dumps(results, indent=2))

    print("\nSummary")
    for name, status, note in results:
        print(f"  {status:4} {name}: {note}")
    sys.exit(0 if all(status == "PASS" for _, status, _ in results) else 1)


if __name__ == "__main__":
    main()
