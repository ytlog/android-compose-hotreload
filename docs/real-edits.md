# Real Android edit validation

The sample's `RealEditActivity` uses a Compose page, a ViewModel, an editable draft, detail selection and a listener registered once. Source edits follow the normal Kotlin → ClassFile → host D8 → device JVMTI → Compose invalidation path.

## Local engine

With the two repositories in the same parent directory:

```sh
cd ../art-dcevm
ENGINE_VERSION=0.1.0-dev.$(git rev-parse --short=8 HEAD)
./gradlew :artdcevm:publishToMavenLocal -PpublishVersion="$ENGINE_VERSION"
cd ../android-compose-hotreload
export ORG_GRADLE_PROJECT_artDcevmVersion="$ENGINE_VERSION"
python3 tools/real-edit-test.py --serial DEVICE_SELECTOR --device-id local-apiNN \
  --art-dcevm-version "$ENGINE_VERSION" --scenario-group full
```

`DEVICE_SELECTOR` is a placeholder. Never commit the real device serial. An explicit `artDcevmVersion` selects Maven Local exclusively for `io.github.ytlog:artdcevm` in both builds; a missing artifact fails resolution. Other dependencies keep their original repositories. Without this property, builds retain the published engine version. The environment variable keeps composite builds and later reloads on the same version. Each Gradle invocation can also pass `-PartDcevmVersion=VERSION`.

For manual use, run `./sample/gradlew -p sample hotRunDebug`, then open the page with `adb -s DEVICE_SELECTOR shell am start -n dev.compose.hotreload.sample/.RealEditActivity`. Stop a manual hotRun before running the automated test.

## Cases and evidence

| Group | Actual edits |
| --- | --- |
| `business` | Change return values, conditions and data processing; extract a helper; invoke through the UI |
| `rendering` | Change text, add and interact with a component, then remove it |
| `callbacks` | Change an already registered lambda body, insert another lambda before it, add a captured local |
| `recovery` | Reject making a live ViewModel abstract with a companion business edit, retry after correcting the invalid edit, then add a class and update its caller |
| `full` | Execute all groups consecutively in one process |

The runner types into the real TextField and selects a detail. It checks the process, ViewModel and listener identities, draft, selection, operation count and an unrelated `remember` scope. Every step requests GC and backgrounds/resumes the same Activity. It checks installed APK and native library hashes against the local AAR and records source and artifact provenance.

Sources are temporarily edited and restored on exit. Commit or back up work first, and never run two source-editing scripts in the same checkout concurrently. An existing `EditFormatter.kt` is never overwritten. Raw logs, source diffs, UI snapshots, screenshots and reports stay in ignored `build/evidence/`.

A new listener's success does not prove that an old registered listener works, especially after adding captures. Retain failing runs. Reload timings include compilation and transport and are not ART pause measurements. The tests do not claim constructor replay or reconstruction of arbitrary new captured values.

Prioritize business logic, rendering and callbacks before signature refactors, model initialization, suspended coroutines/Flow and Repository/DI/proxy behavior. Coroutines and Flow are not covered yet. Run affected groups during development and `full` on both devices after freezing, serially on each device. Keep unchanged engine matrix evidence with its original provenance, separate from the new sample APK runs. Functional success does not resolve historical performance or long-pause failures.

If a reload is accepted but an old callback fails, the runner retains `FAIL` and continues with explicit registration and independent later steps. Its final exit code remains nonzero; this is a functional gap, not a full acceptance pass.
