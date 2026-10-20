# Android Compose Hot Reload

This is the Android root build. Preserve the pinned upstream source under
`upstream/compose-hot-reload`; its source and license belong to JetBrains and contributors.
Exclude local `.idea/` and `.run/` directories from Git, including those in the upstream tree;
the exclusions are recorded in `upstream/lock.properties`.
Upstream source changes should be intentional and accompanied by a lock/version update.

## Build and verify

```bash
./gradlew prepareUpstream
./gradlew androidCheck
./sample/gradlew -p sample assembleDebug
python3 tools/device-test.py --serial DEVICE
python3 tools/workflow-test.py --serial DEVICE
```

The sample is a standalone composite build. Run `./sample/gradlew -p sample hotRunDebug --auto`
to test continuous reload. Device tests modify and restore sample source files.

## Ownership

- `modules/protocol`: plain Java wire protocol, independent of Gradle, Compose and the upstream agent.
- `modules/runtime`: Android process, ART DCEVM/JVMTI and Compose UI integration.
- `modules/host`: device sessions, reload attempts, bytecode snapshots, D8 and upstream adapters.
- `modules/gradle-plugin`: debuggable variants, instrumentation and Android build/launch tasks.
- `sample`: application plus local library fixture.
- `tools`: device regression and historical diagnostic evidence.

Keep the existing plugin ID and Maven coordinates. Project names are mapped to the new module
directories by settings.gradle.kts for composite-build compatibility.
Android artifact version is independent of the upstream version in gradle/libs.versions.toml
and upstream/lock.properties. See docs/architecture.md for dependency and transaction boundaries.
