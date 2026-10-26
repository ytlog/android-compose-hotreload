# Android Compose Hot Reload

**English** | [简体中文](README.zh-CN.md)

**Hot reload Jetpack Compose apps on Android devices.** Edit Kotlin / Java code, Compose UI, and existing Android resources, then compile and apply changes to the running app. Supported changes require no APK reinstall or process restart and preserve `remember` state where possible.

This project implements the Gradle integration, host service, and device runtime for Android Compose hot reload. It converts compiled code changes to DEX, sends them to the device, and uses [art-dcevm](https://github.com/ytlog/art-dcevm) to redefine classes on ART, reinitialize statics, and refresh Compose UI. It reuses bytecode analysis, reload tasks, orchestration, and developer tools from [JetBrains Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload). Hot reload is intended for debuggable apps during development.

[Use in an existing project](#use-in-an-existing-project) · [Build from source and run the sample](#build-from-source-and-run-the-sample) · [Android guide](docs/android.md) · [Architecture](docs/architecture.md) · [License](LICENSE.txt)

Real application edit cases and Maven Local engine integration: [validation guide](docs/real-edits.md).

## Features

- **Edit Compose UI and function implementations:** recompile changes and see the results in the running app.
- **Update Android resources:** change existing strings, colors, drawables, raw resources, and assets through the same manual or automatic reload workflow. See [resource updates](docs/android.md#resource-updates).
- **Update app and local library modules:** reinitialize top-level properties, add classes, and change Kotlin / Java lambdas.
- **Evolve classes and interfaces:** add, remove, or change fields and methods; evolve default and abstract interface methods; reload interfaces and implementations in the same batch. Each change is checked by the runtime transaction. See the [alpha.3 guide](docs/android.md#alpha3-code-changes).
- **Reload manually or automatically:** run the Gradle `reload` task, or start with `--auto` to compile and reload when files are saved.
- **Use developer tools:** inspect reload results, handle composition errors, and reset UI state through devtools.
- **Inspect failures and retry:** ART rejection details are reported to devtools. Failed source changes remain pending, so you can revert an unsupported change or complete an implementation and reload again.

State retention depends on the change and upstream Compose scope analysis. For example, changing the total number of lines in a source file may cause a composable containing `remember` to reset. See [state resets](docs/android.md#state-resets-upstream-behavior).

## Use in an existing project

For an existing Android application using Jetpack Compose. **The current integration builds from source through a Gradle composite build.** The local Maven directory below provides the pinned upstream dependencies built from source. First complete [source preparation](#1-prepare-the-source-and-artifacts) near the end of this page.

These examples assume this repository and your app project share a parent directory. Adjust paths as needed and merge the snippets into your existing configuration.

### 1. Configure settings.gradle.kts

```kotlin
pluginManagement {
    includeBuild("../android-compose-hotreload")
    repositories {
        maven(file("../android-compose-hotreload/upstream/compose-hot-reload/build/repo"))
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven(file("../android-compose-hotreload/upstream/compose-hot-reload/build/repo"))
        google()
        mavenCentral()
    }
}

includeBuild("../android-compose-hotreload")
```

### 2. Apply the plugin to your app module

Add the plugin to the existing `plugins` block in `app/build.gradle.kts`:

```kotlin
plugins {
    // Keep your existing Android, Kotlin Android, and Compose compiler plugins.
    id("io.github.ytlog.compose.hot-reload.android")
}
```

Your app needs `compileSdk >= 37`, a debug variant with `minSdk >= 30`, and an `arm64-v8a` device. You can use an existing flavor to isolate the debug `minSdk` setting. Keep your app's own Compose dependencies and `targetSdk`.

The plugin automatically adds the device runtime to debuggable build types and instruments the Compose hot reload entry point. For a complete example, see the [sample settings](sample/settings.gradle.kts) and [app configuration](sample/build.gradle.kts).

### 3. Start and reload your app

Connect an authorized ADB device and run this from **your app project's root directory**:

```bash
./gradlew :app:hotRunDebug --auto
```

The task installs and launches the app and opens devtools. Keep it running in the terminal; saving Kotlin / Java source files or supported resource changes triggers compilation and hot reload. When multiple devices are connected, set `ANDROID_SERIAL` to select the target device.

For manual reload, start without `--auto`, edit your code, and run this in another terminal:

```bash
./gradlew :app:reload
```

Replace `:app` with your application module name. With product flavors, use the launch task for the relevant variant. The host exits when the app process exits, and stopping the host also closes the app.

## Requirements

The following build configuration has been used for validation:

| Item | Requirement or configuration |
|---|---|
| Android device | Android 11–17 / API 30–37, `arm64-v8a`, connected through ADB; individual changes are checked by the runtime transaction |
| App | Jetpack Compose, debuggable from process start |
| Host Java | JDK 21 |
| Android SDK | Platform 37; the app's `compileSdk` must be at least 37 |
| Native build tools (building the device runtime) | NDK `27.2.12479018` and CMake `3.22.1`, used to compile this project's JVMTI agent |
| Gradle / AGP / Kotlin | Gradle `8.14.4`, AGP `8.11.1`, Kotlin `2.3.21` |
| ART redefinition engine | `io.github.ytlog:artdcevm:0.1.0-alpha.4` |

art-dcevm and its dependencies require `compileSdk 37`. With AGP `8.11.1`, you can add `android.suppressUnsupportedCompileSdk=37` to your app project's `gradle.properties` to build with the current configuration.

## Current limitations

- Structural class and interface changes depend on runtime services, boot types, and historical pointer-based JNI IDs. Changes such as adding native interface methods are still rejected. See the [full limitations](docs/android.md#limitations).
- Initialization code in already initialized interfaces is not rerun. Kotlin companion storage classes are reinitialized as ordinary classes. Existing callers with inlined `const val` values need recompilation.
- Adding fields does not rerun constructors for existing instances. Cached raw field offsets may become invalid, and restoring a member with the same name does not revive old references to the retired member. Changing invocation modes or removing members may cause linkage errors at old call sites.
- Atomicity applies to existing class definitions. Added DEX files, class loader caches, and arbitrary user side effects cannot be fully rolled back after a failed transaction.
- Adding or removing resource entries, changing resource IDs, deleting installed resource files or configurations, and changing the Manifest require reinstalling the app. Existing resource content supports hot updates.
- Multi-process apps and minified variants are not supported yet.
- Desktop window features in devtools are not connected to Android yet, including screenshots, semantics trees, UI actions, and window resizing.
- IDE plugin run configurations for Android, `ReloadEffects`, and the `AfterHotReloadEffect` / `invokeAfterHotReload` hooks are not connected yet.
- `remember` state may reset when Compose scopes change. Hot reload does not guarantee state retention for every edit.

See the [Android guide](docs/android.md#limitations) for more platform differences and limitations.

## Build from source and run the sample

Run these commands in this repository to prepare the artifacts needed for integration or to try the sample.

### 1. Prepare the source and artifacts

```bash
git clone https://github.com/ytlog/android-compose-hotreload.git
cd android-compose-hotreload

# Replace this with your Android SDK path.
export ANDROID_HOME="/path/to/Android/sdk"

./gradlew prepareUpstream
```

This builds the pinned JetBrains JVM artifacts and publishes them to `upstream/compose-hot-reload/build/repo`. Run it once before integrating, and again after updating the upstream source. Your app's composite build compiles the Android plugin, host, and runtime automatically.

### 2. Run the repository sample

Connect a device and run this from the repository root:

```bash
./sample/gradlew -p sample hotRunDebug --auto
```

Edit the [sample MainActivity.kt](sample/src/main/java/dev/compose/hotreload/sample/MainActivity.kt) to reload automatically. For manual mode, omit `--auto` and run `./sample/gradlew -p sample reload` in another terminal.

See [architecture](docs/architecture.md) for more build and module details.
