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
| `callbacks` | Keep the initial listener; insert zero/same-capture lambdas; edit its body; add/reorder/remove/restore reference and primitive captures; reject ambiguous insertion and retry |
| `recovery` | Reject making a live ViewModel abstract with companion business and capture-layout edits, check old calls, retry, then add a class and update its caller |
| `full` | Execute all groups consecutively in one process |

The runner types into the real TextField and selects a detail. It checks the process, ViewModel and listener identities, draft, selection, operation count an unrelated `remember` scope and exact factory/callback counts. The initial listener and its reference anchor are immutable; a separately created control closure never replaces them. Every step requests GC and backgrounds/resumes the same Activity. It checks installed APK and native library hashes against the local AAR and records source and artifact provenance.

Sources are temporarily edited and restored on exit. Commit or back up work first, and never run two source-editing scripts in the same checkout concurrently. An existing `EditFormatter.kt` is never overwritten. Raw logs, source diffs, UI snapshots, screenshots and reports stay in ignored `build/evidence/`.

A new listener's success does not prove that an old registered listener works, especially after adding captures. Retain failing runs. Reload timings include compilation and transport and are not ART pause measurements. The tests do not claim constructor replay or reconstruction of arbitrary new captured values.

Prioritize business logic, rendering and callbacks before signature refactors, model initialization, suspended coroutines/Flow and Repository/DI/proxy behavior. Coroutines and Flow are not covered yet. Run affected groups during development and `full` on both devices after freezing, serially on each device. Keep unchanged engine matrix evidence with its original provenance, separate from the new sample APK runs. Functional success does not resolve historical performance or long-pause failures.

## Retained lambda contract

Kotlin numbers compiler-generated implementation methods by source order. Inserting a literal can change that number; adding a capture can also change its descriptor. D8's installed synthetic closure still calls the bridge emitted for the old implementation. Preserving only the current D8 pass's bridges loses that entry, producing `NoSuchMethodError` or an incorrect target if an ordinal is reused.

The host retains the initial D8 bridges and every subsequently emitted closure entry for the session. Those entries invoke the latest logical body. Logical matching uses the enclosing method (or parent lambda), SAM contract, normalized body and capture schema, never the source ordinal alone. Unchanged bodies anchor the match; only a sole remaining old/new pair can evolve. A capture schema alone cannot identify a literal when an inserted decoy retains that schema and the original gains captures. Multiple simultaneous edits in the same scope/SAM group may therefore require separate reloads. An ambiguous edit is rejected on the host before any device update; correcting it can retry without changing the active mapping. A removed source lambda keeps its last body callable by retained closures.

For a retained closure, a capture with the same debug name and JVM type keeps its original value, independently of parameter order. New, renamed or retyped captures get JVM defaults: reference/array `null`, numeric zero, boolean `false`. Initializers, registration factories and constructors do not run again. A new body that dereferences a default-null capture can therefore throw a normal `NullPointerException`; the host does not infer historical initializer values. A newly created control closure captures current real values. Removing and restoring a capture recovers each closure cohort's original binding if it had that name/type at creation.

Missing capture debug names allow only unchanged bodies and capture layouts; evolution is refused. Retained direct compiler entries preserve their original modifiers, including private interface bodies. Ordinary method references retain their declared target contract. The current planner covers LambdaMetafactory compiler bodies, not arbitrary anonymous-class capture migration. It commits its ABI inventory only for owners actually added/redefined on the device, including reported partial success; rejected plans do not advance it.

Callback mismatches fail immediately. The runner has no replacement-listener recovery step. It proves old/new behavior separately through actual calls, then verifies host refusal and native transaction refusal followed by successful retries.
