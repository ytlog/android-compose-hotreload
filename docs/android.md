# Compose Hot Reload for Android

**English** | [简体中文](android.zh-CN.md)

This project implements Android Gradle integration, a host service, and a device runtime, reusing the analyzer, reload tasks, orchestration, and developer tools from JetBrains Compose Hot Reload. This guide covers Android usage and the change semantics of art-dcevm alpha.3. See [architecture](architecture.md) for module responsibilities and dependency boundaries.

## Android implementation and reused functionality

| Upstream (desktop JVM) | Android | Details |
|---|---|---|
| `hot-reload-agent` `premain`: devtools, orchestration, PID file, and logs | **`hot-reload-android-host`** (host JVM process) | Directly calls the upstream agent's `startDevTools` / `startOrchestration` / `createPidfile` / `startWritingLogs` (see `UpstreamAgent.java`). The upstream orchestration client depends on `java.lang.ProcessHandle`, which is unavailable on ART, so this part of the agent runs on the host. |
| `runtimeTracking.kt`: track `ClassInfo` for loaded classes | `ApplicationIndex` | ART does not provide class file bytes, so the index is built from the class files used to create the installed APK. The semantics of `currentApplicationInfo` / `pendingRedefinitions` match upstream. |
| `reloadRequestHandler.kt` / `reload.kt` | Host-side `reload.kt` | Changes from failed requests remain pending for the next retry. Both `verifyRedefinitions` and `resolveDirtyScopes` use the upstream analyzer. |
| `staticsInitialization.kt` | Host: calls upstream `transformForStaticsInitialization`; device: executes `$chr$clinit` | `AllDirty` / `ChangedOnly`, topological sorting, and processing only loaded classes all match upstream. |
| `Instrumentation.redefineClasses` + Swing EDT | **`hot-reload-android-runtime`**: JVMTI `RedefineClasses` + [ART DCEVM 0.1.0-alpha.3](https://github.com/ytlog/art-dcevm), executed on the main thread | Existing classes and interfaces are redefined in one atomic batch, followed by static reinitialization and group invalidation. The engine provides interface capabilities on API 30–37 arm64; each change is checked by the transaction. |
| `compose.kt`: hot reload mode, `invalidateGroupsWithKey`, and hard reset | `compose.kt` | Also calls `Recomposer.Companion` through reflection. |
| `WindowInstrumentation`: replace `ComposeWindow.setContent` with `DevelopmentEntryPoint` | AGP ASM instrumentation of `ComposeView.setContent` inside `androidx.activity.compose.ComponentActivityKt` | As upstream does, instruments just this one internal library call site without changing user code. |
| `hot-reload-runtime-jvm`: `DevelopmentEntryPoint`, `HotReloadState`, `recomposerErrors`, and `resetComposition` | Kotlin files with the same names | Sends `UIRendered` / `UIException` and handles `CleanCompositionRequest` / `RetryFailedCompositionRequest`. Android has no `WindowId`, so it is `null`. |
| Gradle: `hotSnapshot*` / `hotReload*` / `reload` / `hotRun*` | Registers upstream `ComposeHotSnapshotTask` / `ComposeHotReloadTask` directly; `hotRunDebug` passes all parameters through upstream `withComposeHotReload {}` | The upstream devtools window and Recompiler (`--auto`) work directly. |

The host and device communicate through a binary frame protocol with size limits (`hot-reload-android-core`). `adb forward` connects to an abstract Unix socket inside the app. The device accepts connections only from the app's own UID, shell, and root. To orchestration, this Android app behaves like the upstream desktop app: `ReloadClassesRequest` → `ReloadClassesResult` → `InvalidatedComposeGroupMessage` → `UIRendered`.

### Platform differences

1. **Lambda isolation** (`LambdaIsolation.kt`). The JVM creates anonymous lambda classes when linking call sites, while D8 desugars lambdas into named classes numbered by occurrence (`Foo$$ExternalSyntheticLambda0`). Inserting a lambda shifts these numbers, so redefining the synthetic classes would become an inheritance hierarchy change that ART rejects. Before DEX conversion, lambda call sites in redefined classes or interfaces are moved into helper classes unique to each reload. Existing D8-generated `$r8$lambda$*` bridge methods are preserved so lambda instances created before reload keep working and execute the new code, as on the JVM. Interface bridges must be valid public static methods, and static calls use interface method references. With minSdk 30, default methods use ART's native support, with no `$-CC` companion class. The upstream analyzer still sees the original bytecode.
2. **New classes** are appended to the app's ClassLoader through ART's `add_to_dex_class_loader`. **Classes in the APK that have not been loaded yet** must also be redefined. Upstream skips these because the JVM will later load the new version from the hot classpath, whereas ART would load the old version from the APK. Appending DEX cannot be undone. After a failed redefinition, appended classes remain available for retry, while the atomic batch of existing class definitions is left unchanged.
3. Devtools uses detached mode by default because there is no app window to attach to. Override this with `compose.reload.devToolsDetached`.
4. The host runs on standard JDK 21 and does not require JetBrains Runtime. The app runs on ART, with DCEVM provided by art-dcevm.
5. The Android host finishes analysis before the device executes the transaction. Each attempt therefore stages `ClassInfo` and updates the index only after the device confirms successful redefinition. Failed source changes remain pending for the next request; reverted classes do not retain analysis results from the failed attempt.

Interfaces and implementations are not submitted separately or ordered by file traversal. They join the other existing classes in a single `RedefineClasses` call, which art-dcevm validates and executes. Rejection details are taken first from `capabilities().lastRejection` and passed unchanged through the device protocol into upstream `ReloadClassesResult.errorMessage`, which devtools displays. If ART explains the failure only after `FAILURE TO REDEFINE` in logcat, the host retrieves that text using the process and current request markers, keeping errors from earlier requests out of the current result.

Static initialization follows upstream behavior: `transformForStaticsInitialization` explicitly skips interfaces, keeps interface fields final, and does not generate `$chr$clinit` for them. Initializers of already initialized interfaces do not run again, and new static fields receive default values. Kotlin companion storage classes are ordinary classes and still use upstream reinitialization. Callers of `const val` use compile-time inlined values; callers that are not recompiled do not automatically receive new values.

## Usage

Dependencies:

- art-dcevm is fetched from Maven Central: `io.github.ytlog:artdcevm:0.1.0-alpha.3`. This project uses compileSdk 37 and retains `android.suppressUnsupportedCompileSdk=37`.
- JetBrains artifacts are published to a local Maven repository by this repository's `prepareUpstream` task. That source project is built separately to avoid a Gradle plugin bootstrap cycle. The Android plugin and sample still use a composite build.

Building the device runtime from source also requires NDK `27.2.12479018` and CMake `3.22.1` to compile this project's [agent.cc](../modules/runtime/src/main/cpp/agent.cc) into `libcompose_hot_reload_android.so`. art-dcevm uses a prebuilt AAR from Maven; its own source build tool versions do not affect this configuration.

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew prepareUpstream
```

Run the sample on an arm64 device with API 30+. The app must be debuggable from process start. Set `ANDROID_SERIAL` if multiple devices are connected:

```bash
./sample/gradlew -p sample hotRunDebug
```

After editing `sample/src/main/java/dev/compose/hotreload/sample/MainActivity.kt`, run:

```bash
./sample/gradlew -p sample reload
```

For automatic reload on save, use the upstream Recompiler running in devtools:

```bash
./sample/gradlew -p sample hotRunDebug --auto
```

`hotRunDebug` installs the APK, then starts the host process. The host launches the app, and their lifecycles are linked: the host exits when the app exits, and stopping the host or sending it `ShutdownRequest` also closes the app. This matches upstream `hotRun` behavior.

For complete integration in another project, see [the README setup steps](../README.md#use-in-an-existing-project). Include this repository's root build in both `pluginManagement` and the settings top level, and add the prepared local Maven repository to both plugin and dependency repositories. Then add the hot reload plugin to the app module's existing `plugins` block:

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("io.github.ytlog.compose.hot-reload.android")
}
```

The plugin adds the runtime only to debuggable build types (`<buildType>Implementation`). The runtime starts through a non-exported ContentProvider before `Application.onCreate`.

### alpha.3 code changes

Save source changes and run `reload`, or use `hotRunDebug --auto` to compile and reload automatically. Changes in the app and local library modules can be submitted together. The table below covers the main changes supported by the alpha.3 engine and behaviors to handle when using them. Actual support depends on the compiler, analyzer, and ART transaction. See [validation](#validation) for this project's device coverage.

| Change | Usage and behavior |
|---|---|
| Function bodies, Compose UI, top-level properties, and lambdas | Recompile and reload. This project handles ordinary class static reinitialization and Compose refresh; state retention still depends on scope analysis. |
| New classes or source files | The host generates DEX and appends it to the app class loader for callers to use. Deleting a source file does not unload a class already loaded in the process. |
| Add, retire, or change the signature or type of ordinary class fields, methods, and constructors | The engine supports the corresponding structural evolution. Update callers when changing signatures. Existing objects retain compatible fields; new fields receive zero or `null`, and constructors do not run again. |
| Add, remove, or change signatures of interface methods | Supports evolution of default / abstract / static members and switching between public abstract and default methods. Interface and implementation changes can be submitted in the same batch. |
| Interface static fields, superinterfaces, and interfaces implemented by ordinary classes | The engine supports the corresponding field and interface relationship changes. Handle raw field offsets, old type references, and old calls according to the new semantics. |
| Superclasses and type modifiers | Supports constrained superclass widening and modifier changes. Superclass narrowing and making a class abstract while it has direct instances are still rejected. |

For example, when renaming an interface's `label(): String` to `renamedLabel(): String`, update callers at the same time. After the old signature is retired, code or cached method objects that still reference it throw `NoSuchMethodError`. When adding an abstract method, you can supply implementations in the same batch or redefine the interface first. In the latter case, calling the missing method on an old implementation throws `AbstractMethodError`. A successful transaction does not mean all old calls remain valid.

A transaction may accept default method conflicts; invoking a conflicting method throws `IncompatibleClassChangeError`. Switching between static and instance methods, or changing a virtual method to private, can cause old calls to throw `IncompatibleClassChangeError` or `IllegalAccessError`, respectively, and depends on runtime services. Restoring a member with the same name after deletion creates a new member. Old reflection, JNI, and MethodHandle references retain their retired-member semantics.

### Failure handling

After successful initialization, submit `RedefineClasses` directly. Do not gate submission on boolean values or status strings from `capabilities()`. A nonzero return indicates failure; the host reports the error code and `lastRejection` to devtools. Revert an unsupported change or fix the source and run `reload` again. Previously unapplied source changes are retried together, with batch atomicity for existing class definitions.

Added DEX, class loading, and user side effects cannot be fully rolled back. If engine initialization fails or the runtime reports that it is unavailable, inspect diagnostics, fix the environment, and restart the app instead of repeatedly submitting the same batch. Already initialized interfaces do not rerun initializers, and callers with inlined `const val` values still need recompilation.

### Versions

The toolchain matches upstream: Gradle 8.14.4, AGP 8.11.1, Kotlin 2.3.21, and Compose 1.11 (BOM `2026.06.01`). This project uses compileSdk 37, while AGP 8.11.1 officially supports up to 36, so `android.suppressUnsupportedCompileSdk=37` is set.

The runtime prints `DcevmRuntime.capabilities()` at startup. These fields are diagnostic only and are not admission checks before submission. After successful initialization, the device submits batches directly through standard `RedefineClasses`. Its return value determines success or failure; read `lastRejection` on failure. The removed `interfaceAbstractMethodAddition` / `interfaceAbstractDispatch` fields are no longer used.

alpha.3 supports retiring interface methods, changing their signatures, and adding abstract methods. Redefinition can succeed with missing abstract implementations; invoking a missing method throws `AbstractMethodError`. Old calls to retired methods throw `NoSuchMethodError`. The engine's interface capabilities cover API 30–37 arm64. The engine has physical-device validation on API 33–36; other versions still need device coverage. See the [pinned engine limitations](https://github.com/ytlog/art-dcevm/blob/a61e98e29c2ef795127ee7654ef23848fb478646/docs/LIMITATIONS.md) for complete semantics and conditions.

## Validation

Unit tests:

```bash
./gradlew :hot-reload-android-core:test :hot-reload-android-host:test
```

On 2026-10-20, the alpha.3 upgrade passed 17 unit tests (protocol 6, host 11), Gradle plugin validation, and the runtime Debug AAR build. The unit tests cover D8 bridges for interface lambdas, upstream static initialization transforms for interfaces and companions, reload transaction commits, isolation of failed analysis, and logcat request correlation.

End-to-end device tests require arm64, API 30+, and a debuggable app. The complete interface regression also requires available interface capabilities. The script starts `hotRunDebug`, edits source files and runs `reload` in sequence, and uses uiautomator to assert the UI and `remember` counter. Source files are restored at the end:

```bash
python3 tools/device-test.py --serial DEVICE
```

On 2026-10-20, the full alpha.3 script passed **18/18 with exit code 0** on an Android 16 / API 36 arm64 physical device. It checks the following:

| Scenario | Checks |
|---|---|
| Change a function body | UI updates; state is preserved. |
| Change a top-level `val` (static reinitialization) | UI updates; state is preserved. |
| Change the library module (`:lib`) | UI updates; state is preserved. |
| Change a Java lambda; insert a new Java lambda | UI updates; state is preserved. |
| Add a class in a new file | UI updates; state is preserved. |
| Insert a lambda before an existing Kotlin lambda | UI updates; old and new lambdas behave correctly; state resets (explained below). |
| Change a lambda in an interface default method | UI updates; state is preserved; the interface capability becomes verified after the first transaction. |
| Insert a lambda in an interface default method | UI updates; state is preserved. |
| Change an interface and its implementation in one batch | UI updates; state is preserved. |
| Add an interface default method and use it from a caller | UI updates; state is preserved. |
| Rename an interface default method and update callers and the library in the same batch | The old signature is retired; new calls and library updates take effect; state is preserved. |
| Add an abstract interface method, provide an implementation, and call it in one batch | UI updates; actual interface dispatch is correct; state is preserved. |
| Add an abstract interface declaration, reuse an existing implementation method, and call it through the interface | UI updates; state is preserved. |
| Dynamically append and use another concrete interface implementation | UI updates; state is preserved; a system property retains an instance for later scenarios. |
| Make a class with direct instances abstract while also changing the library | Returns 70; the native reason reaches `ReloadClassesResult`; the entire UI remains unchanged. After reverting the unsupported change, pending library changes retry successfully and state is preserved. |
| Add an abstract method while another loaded concrete class outside the batch lacks an implementation | The transaction succeeds; invoking the missing method through that old implementation throws a caught `AbstractMethodError`. Other UI and library updates take effect. |
| Change the file's line count | State in composables using `remember` resets (upstream behavior, explained below). |

The current alpha.3 script first dynamically loads a `LoadedLabels` class, then removes its declaration from the current source, adds an abstract method to the interface, and completes the main implementation. After a successful transaction, it calls the missing method on the old implementation through the interface and asserts `AbstractMethodError`. A separate scenario retains a `LoadedLabels` instance in a system property, then makes the class abstract to check return code 70, an unchanged UI for the entire batch, native error reporting, and successful retry of pending library changes after reverting the unsupported change. The test uses valid, compilable Kotlin source and restores all source files at the end.

Run individual regressions with these commands. The last two automatically include their prerequisite scenarios:

```bash
python3 tools/device-test.py --serial DEVICE --only "interface abstract batch"
python3 tools/device-test.py --serial DEVICE --only "interface abstract missing implementation"
python3 tools/device-test.py --serial DEVICE --only "live class abstract rejection and retry"
```

[`tools/repro-artdcevm-alpha1`](../tools/repro-artdcevm-alpha1) preserves evidence of an older failure and does not describe current alpha.3 limitations.

The engine shared library in the Maven Central alpha.3 AAR has been checked against the one packaged in the APK. The AAR SHA-256 is `ed1de8df09aa0bc5f6af00b389acf3b6dfcb77cb8bb579deb35555f5600b6554`.

Each script run saves a source patch, capabilities, device and host logs, and a summary under `sample/build/device-test-evidence/<run>/`. Source regressions cover common workflows in this project, not every possible combination of engine structural changes.

Development workflows are checked separately:

```bash
python3 tools/workflow-test.py --serial DEVICE
```

This script checks seven workflows: the devtools child process and tooling connection; automatic compilation and reload on save with `--auto`, preserving state; clearing the counter with `CleanCompositionRequest`; reporting composition errors through `UIException` while keeping the app alive; automatically rendering again after fixing source; rebuilding the composition with `RetryFailedCompositionRequest`; and closing the app when the host stops. It does not manually invoke `reload`. Error and reload messages come from the actual app.

On 2026-10-20, alpha.3 passed **7/7** on the API 36 device above, covering automatic reload, composition error recovery, and host lifecycle.

### State resets: upstream behavior

Kotlin 2.2+ appends line numbers to local variable names in inline functions (for example, `$i$a$-cache-...$count$2\3\68\0`). Inlined code uses virtual line numbers beyond the end of the file, so **any change to the total number of lines** changes these names. Upstream `ScopeHash` already ignores line-number-bearing source information and trace strings, but includes local variable names in its hash. A composable using `remember` is therefore marked dirty and its state resets. Desktop uses the same Kotlin compiler and has the same behavior. To preserve upstream semantics, the Android implementation does not patch this; the fix belongs in upstream `ScopeHash`.

## Limitations

The following summarizes the [pinned limitations](https://github.com/ytlog/art-dcevm/blob/a61e98e29c2ef795127ee7654ef23848fb478646/docs/LIMITATIONS.md) of art-dcevm `0.1.0-alpha.3`. Each change is still checked by the actual transaction. Capabilities listed by the engine do not imply that every case has been validated in this project.

| Change or runtime condition | Limitation or behavior |
|---|---|
| Required named runtime services are missing, or declaration probes fail | Initialization fails or the transaction is rejected. Diagnostic fields do not replace the actual submission result. |
| Add a native interface method | Still unsupported; returns 63. |
| Boot interfaces, historical pointer-ID markers, or pointer-based JNI IDs in an interface transaction | May return 79. Switching ID mode afterward does not repair cached pointers. |
| Member mapping or method selection services are unavailable | The corresponding interface method retirement, field changes, or hierarchy changes may still be rejected. |
| Make a class abstract while it has direct instances | Returns 70. This project's source regression retains an instance to verify rejection of the entire batch and recovery. |
| Change a superclass to one that does not extend the original superclass | Still rejected as superclass narrowing; returns 66. |
| Change the type kind, or make a type final when loaded subtypes conflict | Separate admission rules apply; arbitrary structural transformations are not accepted. |
| Add an abstract method while an old implementation lacks it | Redefinition can succeed; invoking the missing method throws `AbstractMethodError`. |
| Retire an interface method or change its signature | The old signature is retired. Old calls or cached references throw `NoSuchMethodError`; restoring a member with the same name does not revive old references. |
| Introduce default method conflicts | The transaction can be accepted; invoking the conflicting method throws `IncompatibleClassChangeError`. |
| Switch static / instance or virtual / private invocation modes | Requires the corresponding services. Old calls may throw `IncompatibleClassChangeError` / `IllegalAccessError`; missing services may still cause return code 71. |
| New fields, fields from a new superclass, or changes to interface initialization | Constructors do not rerun for existing objects; new fields receive zero or `null`. Already initialized interfaces do not rerun initializers. |
| Raw field offsets | Caches in `Unsafe`, `Atomic*FieldUpdater`, and similar APIs are not automatically repaired. Layout changes may invalidate them. |
| ART requirements | The app must be debuggable from process start. Arrays, primitives, String, mirror types, proxy classes themselves, Thread and its supertypes, and other types remain restricted. |

Process side effects such as appending DEX for new classes, class loader caches, and the fact that already initialized interfaces do not rerun initialization cannot be fully undone by a rejected transaction. Atomicity applies to existing class definitions, not restoration of added DEX, arbitrary user side effects, or raw offset caches.

- Manifest changes, resource name/ID changes, and deletion of installed resource files or qualifier configurations require reinstallation. See below for resource content updates.
- Multi-process apps and minified variants are not supported.
- Window-dependent devtools features are not connected: screenshots, semantics trees, UI actions, and window resizing.
- The `ReloadEffects` overlay is not connected.
- Upstream `hot-reload-runtime-api` publishes an Android variant, but this runtime does not yet connect its `AfterHotReloadEffect` / `invokeAfterHotReload` hooks.
- IDE plugin run configurations for Android are not connected yet.

## Resource updates

No extra configuration is needed. Use the same tasks as for code:

```bash
# Install on first launch, or reinstall after unsupported changes.
./gradlew :app:hotRunDebug --auto
# Reload manually after editing resources if automatic mode is not running.
./gradlew :app:reload
```

You can change existing values such as strings, colors, and dimensions, and replace the contents of existing drawable, raw, and assets files. AGP merges resources from the app and local libraries. Compose's `stringResource`, `colorResource`, `painterResource`, and resources read again through the current Context refresh after an update. Code and resources can change together without reinstalling or restarting the process, and ordinary `remember` state is preserved. `--auto` also watches resource and assets inputs.

Current scope and limitations:

- Resource names and IDs must match the APK installed by the current `hotRun` session. Adding, deleting, or renaming resources, or a build that changes IDs, rejects the reload and requests reinstallation.
- Installed resource files, assets files, and values qualifier configurations cannot be deleted. Otherwise, Android's overlay mechanism may fall back to old content in the installed APK. Editing the contents of an existing qualified resource supports hot updates.
- Manifest changes require reinstallation. A failed check aborts before code submission. Revert the change and run `reload` again, or stop the current session and rerun `hotRunDebug` to install.
- The development variant must output a single universal APK. Resource updates for APK splits / dynamic features are not supported yet. Each reload extracts resources after an incremental APK build, so it takes longer than compiling source alone.
- Automatic Compose refresh targets development entry points installed through `androidx.activity.compose.setContent`. For a custom ComposeView without that entry point, the resource layer is replaced but its composition is not automatically refreshed.
- Already inflated traditional View layouts, configuration read at Activity / Application startup, system-managed icons and labels, and resource values cached by the app in `remember`, singletons, or image libraries are not automatically re-executed or cleared. Refresh them in the app or reinstall/restart. Already open resource streams are not replaced either.
- After an update, `LocalContext` inside the development entry point is a `ContextWrapper` delegating to the original Context, used to refresh resources and painter caches. If you need an Activity, follow `ContextWrapper.baseContext` rather than casting `LocalContext.current` directly to Activity.
- Resource overlays last only for the current process. After the development session ends, the installed APK determines the content. The runtime/host protocol has been upgraded; after updating the plugin, rerun `hotRun` to install a matching runtime.

Local physical-device regression commands (sample files are restored afterward):

```bash
python3 tools/resource-test.py --serial DEVICE
python3 tools/resource-test.py --serial DEVICE --auto --headless
```

On 2026-10-20, an Android 16 / API 36 arm64 physical device passed **18/18** manual resource checks (including recovery after rejection) and **10/10** headless automatic checks. Coverage includes vector/PNG caches, raw/assets file descriptor access, and state retention. Existing source regressions also passed **18/18**, and development workflows passed **7/7**. `androidCheck` passed **23** unit tests (protocol 7, host 16), plugin validation, and the runtime build. The resource snapshot passed 4-byte `zipalign` validation.
