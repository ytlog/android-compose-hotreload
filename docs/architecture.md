# Architecture

**English** | [简体中文](architecture.zh-CN.md)

Android is the root project. The upstream Compose Hot Reload source is a separate project pinned to a specific version. The protocol, device runtime, host service, and Gradle integration each have a distinct responsibility.

```text
android-compose-hotreload/
├── modules/
│   ├── protocol/        # Java wire protocol
│   ├── runtime/         # Android + JVMTI + Compose
│   ├── host/            # JDK 21 host service
│   └── gradle-plugin/   # Android variants and build integration
├── sample/             # Standalone composite build sample
├── upstream/
│   ├── lock.properties
│   └── compose-hot-reload/ # Complete upstream source, samples, tests, and maintenance tools
├── gradle/libs.versions.toml
├── tools/
└── docs/
```

The standalone [sample](../sample) contains a Jetpack Compose app and a local library module. The complete upstream source is preserved in [upstream/compose-hot-reload](../upstream/compose-hot-reload).

## Modules and dependency direction

| Module | Responsibility | Dependencies |
|---|---|---|
| [protocol](../modules/protocol) | Bounded frames, handshake, reload messages, UI results, and errors | Java standard library |
| [runtime](../modules/runtime) | Receive changes, append DEX, perform atomic class redefinition, replace resources, reinitialize statics, and refresh Compose | protocol, art-dcevm, Compose provided by the app |
| [host](../modules/host) | Manage device sessions, class and resource indexes, reload attempts, lambda isolation, and DEX conversion | protocol, upstream analysis and tooling artifacts, D8 |
| [gradle-plugin](../modules/gradle-plugin) | Discover debuggable variants, instrument the entry point, collect build outputs, and configure launch and reload tasks | AGP, Kotlin Gradle API, upstream Gradle artifacts |

The plugin configures host process and runtime dependencies through Maven coordinates. The host is not packaged into the app. Neither the protocol nor the runtime depends on the upstream JVM agent. Package names, the plugin ID `io.github.ytlog.compose.hot-reload.android`, and Maven coordinates are preserved. Resource updates use device protocol version 3 and require a matching runtime installation.

The Gradle project names remain `hot-reload-android-core`, `hot-reload-android-runtime`, `hot-reload-android-host`, and `hot-reload-android-gradle-plugin`. The root settings map them to the four module directories, allowing composite builds to keep substituting project dependencies using the existing coordinates.

## Reload flow

Gradle compiles Kotlin / Java changes, and the upstream analyzer identifies Compose scopes to invalidate. The Android host converts the changes to DEX and sends them to the device over ADB. On the device, JVMTI and art-dcevm redefine existing classes and interfaces in a single atomic batch, then reinitialize statics and invalidate Compose groups on the main thread.

Before DEX conversion, the host isolates lambda call sites in classes and interfaces and preserves the D8 bridge methods needed by existing closures. Upstream analysis still uses the bytecode before isolation. See the [Android guide](android.md) for details.

## Host boundaries

- `host/device`: ADB, device connections, protocol I/O, and correlating ART errors with the current request.
- `host/dex`: D8, DEX accessor reading, and lambda isolation.
- `host/session`: Bytecode and resource snapshots from installation, the running analysis index, resource compatibility checks, and acknowledged digests.
- `host/reload`: A reload transaction covering definition preparation, analysis, DEX generation, device execution, and commit.
- `host/upstream`: Java agent bridging, analysis compatibility entry points, orchestration messages, and devtools integration.

`ReloadAttempt` holds staged analysis for one reload. Rejected attempts are discarded. The analysis index is committed only when the device reports that existing classes have been redefined or that the request succeeded. Successfully applied statics-only definitions carry forward into the next attempt. The irreversible effects of added DEX and user code remain subject to the [Android limitations](android.md#limitations).

Upstream analysis types remain available to host analysis and DEX conversion. Calls with compatibility risks are maintained at these boundaries rather than copying or rewriting upstream algorithms. The Gradle plugin's `upstream` package centralizes upstream snapshot and reload tasks, PID file reading, and launch parameters.

## JetBrains functionality in use

| Functionality | Upstream modules |
|---|---|
| Bytecode analysis, dirty scopes, and infrastructure | analysis, core, annotations |
| Static reinitialization transforms, tooling startup, logs, and PID files | Platform-independent entry points in agent |
| Messages between builds, host, and devtools | orchestration |
| Snapshot and reload tasks and launch parameters | gradle-plugin, gradle-core |
| Developer tools, automatic compilation, error handling, and state resets | devtools, devtools-api |
| Transitive build requirements of these artifacts | gradle-idea, runtime-api, runtime-jvm, and upstream test fixtures |

The source for MCP, desktop samples, IDE test projects, Native/JS targets, and upstream CI and publishing tools remains in the upstream project. These are not Android root subprojects. `prepareUpstream` selects only the required JVM publication and common metadata tasks; it does not build these optional applications or run desktop feature regressions.

## Upstream version and builds

[lock.properties](../upstream/lock.properties) records the source, commit, version, and exclusions. `.idea/` and `.run/` are local IDE configuration and are excluded from Git. All other upstream files match the pinned original commit file for file. The Android publication version is in the root `gradle.properties`, and dependency versions are in the [version catalog](../gradle/libs.versions.toml). The Android version can evolve independently. The upstream version catalog, lock file, and upstream `gradle.properties` must agree; the root build checks this.

```bash
./gradlew prepareUpstream
./gradlew androidCheck
./sample/gradlew -p sample assembleDebug
./sample/gradlew -p sample hotRunDebug --auto
```

Required upstream artifacts are published to `upstream/compose-hot-reload/build/repo`. The Maven boundary avoids the upstream Compose Gradle plugin's bootstrap cycle. Upstream is not an included build of the Android root build.

If artifacts are missing, `verifyUpstream` points to `./gradlew prepareUpstream` before compilation. This check does not block root project configuration or the preparation task itself. Upstream artifacts resolve only from the designated local repository, preventing remote artifacts with the same version from hiding a missing preparation step.

## Integration and validation

The sample uses `pluginManagement.includeBuild("..")` to load the Android plugin from the root build. Its top-level `includeBuild("..")` substitutes the host and runtime coordinates, and JetBrains artifacts come from the same local upstream repository.

`androidCheck` runs protocol and host tests, Gradle plugin validation, and the runtime Debug AAR build. The device regression entry point is `python3 tools/device-test.py --serial DEVICE`, covering 18 source scenarios and automatically saving and restoring sample source files. `python3 tools/workflow-test.py --serial DEVICE` separately checks `--auto`, devtools messages, composition error recovery, and host lifecycle. The orchestration probe is compiled only into the host test classpath and is excluded from the production host JAR and APK.

The root `.github/workflows/android.yml` prepares the SDK and pinned upstream artifacts on GitHub Actions, runs `androidCheck`, and builds the standalone sample. Tests requiring a physical device remain local device checks.

## Resource update pipeline

The Android plugin uses AGP's public `SingleArtifact.APK` to obtain the current variant's complete resource output. `hotResources<Variant>` extracts the resource table, `res/`, `assets/`, and the Manifest used for validation. It uses the aapt2 supplied by AGP to record resource IDs and qualifier configurations. Entry compression methods are preserved, and uncompressed entries are aligned to 4 bytes to preserve `noCompress` and file descriptor access semantics. DEX, signatures, and native libraries are excluded from the resource snapshot. The snapshot becomes an input to the existing `hotSnapshot` task, so manual `reload` and `--auto` share the same pipeline. A single universal APK is currently required; AGP handles resource compilation and merging.

The host's `ResourceIndex` uses the snapshot installed at startup as its baseline. It first checks the Manifest, resource names and IDs, and whether installed files or qualifier configurations have been removed. Incompatible changes are rejected before any class is submitted. Resource content updates and code travel in the same device request. The resource digest is acknowledged only after a successful device response, allowing retries after failure. The protocol version is 3; run `hotRun` again to install a matching runtime when upgrading from an older runtime.

On the main thread, the runtime first parses the new resource provider, applies class changes, replaces the shared provider using the public [ResourcesLoader](https://developer.android.com/reference/android/content/res/loader/ResourcesLoader) / [ResourcesProvider](https://developer.android.com/reference/android/content/res/loader/ResourcesProvider) APIs, then reinitializes static fields and refreshes Compose. The Application and both existing and future Activities share the loader. The development entry point works independently of the devtools setting. On updates, it supplies fresh Resources and Context instances and clears the vector drawable cache to release old Theme/Resources instances. Android compares Themes by their style sequences and deduplicates repeated styles. The runtime encodes the resource generation using separate empty styles for each bit, changing Compose painter's theme cache key without changing theme attributes. Ordinary `remember` state is preserved, and the Activity is not recreated. Class definitions, appended DEX, and user static initialization after resource application still do not form a fully reversible transaction.

`python3 tools/resource-test.py --serial DEVICE` checks resource content, mixed code and resource updates, and recovery after rejection. `--auto` checks automatic reload when resource files are saved. See [resource updates](android.md#resource-updates) for the supported usage scope.

## Compose dependency refresh

The host compares each dirty group's complete scope tree against the currently applied analysis. Unchanged scope trees made dirty only by changed dependencies are sent separately from groups requiring reset. Device protocol 3 carries both lists. Stateless trees with unchanged group/call layout also recompose without reset when constants change. Changed layouts, cache initializers and dependencies evaluated by those initializers retain upstream group-reset behavior. A host analysis extension distinguishes direct cache evaluation from the bodies of cached lambda objects, so changing a callback body does not replay its cache initializer. Compiler cache calculation ranges in ClassFile debug metadata restrict this dependency check to the initializer, excluding ordinary rendering calls outside it. Missing parse/cache-range metadata conservatively retains group-reset behavior.

For unchanged trees, the device enumerates existing compositions through Compose's registration observer, disposes each temporary observer and calls `ControlledComposition.invalidateAll`. Existing restart scopes are enumerated through Compose tooling composition data and marked for one forced recomposition, so unchanged arguments cannot skip a newly installed body when parent invalidations subsume child invalidations. This schedules recomposition without bashing slot groups; keyed children can move while retaining their state. It does not copy application state or replay constructors. The observer API is resolved by name because the application supplies Compose. Runtimes lacking that API retain the previous group-reset path and log that remember preservation is unavailable. When Compose reports a composition error, use its existing group-invalidation recovery path to resume failed compositions; plain scope invalidation alone cannot perform that recovery. State preservation is verified with the sample's resolved Compose runtime; this is not a claim for every older Compose release.
