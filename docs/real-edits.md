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
| `compose-state` | Reorder keyed components and sections; insert, reorder, remove and add lazy-list items; edit a row label; reject and recover a batch while preserving state |
| `compose-recovery` | Run only the row-label/rejection/retry tail of `compose-state` to localize failures |
| `refactoring` | Change Kotlin/Java parameters and return descriptors, append a result class, move a helper between files, rename a Java method, reject/retry a mixed batch and restore original signatures on the retained service |
| `coroutines` | Suspended and fresh tasks, exact request/callback counts, Flow collectors, cancellation, exceptions and atomic recovery |
| `coroutines-recovery` | Isolate rejected-update old/fresh tasks and successful retry |
| `properties` | Retained/new model initialization, getters, added/retyped properties, delegates, data classes and rejection/retry |
| `full` | Execute all groups consecutively in one process, then return to the original page and call its retained listeners |

The runner types into the real TextField and selects a detail. It checks the process, ViewModel and listener identities, draft, selection, operation count an unrelated `remember` scope and exact factory/callback counts. The initial listener and its reference anchor are immutable; a separately created control closure never replaces them. Every step requests GC and backgrounds/resumes the same Activity. It checks installed APK and native library hashes against the local AAR and records source and artifact provenance.

Sources are temporarily edited and restored on exit. Commit or back up work first, and never run two source-editing scripts in the same checkout concurrently. Existing generated fixture files are never overwritten. Kotlin and Java ClassFiles and their hashes are saved for each update. Raw logs, source diffs, UI snapshots, screenshots and reports stay in ignored `build/evidence/`.

A new listener's success does not prove that an old registered listener works, especially after adding captures. Retain failing runs. Reload timings include compilation and transport and are not ART pause measurements. The tests do not claim constructor replay or reconstruction of arbitrary new captured values.

The state page retains an input draft, selected item, ViewModel, service instance, root remember token and scroll state. Keyed panels keep independent remember counters; lazy rows keep independent rememberSaveable tokens and counters plus business-object tokens. The runner checks physical placement, clicks moved items and preserves the first visible key and scroll offset through insertions, reordering and removal. State owners stay in unchanged composables; edited ordering helpers and stateless labels must not reset their unrelated state. The ordering cases edit data-producing Kotlin helpers while keeping the keyed page scope bytecode unchanged. Direct edits to a state-owning scope can still reset that scope under the upstream contract.

Signature cases invoke the same Java service instance after synchronously recompiling Kotlin callers and Java callees. They check both the text and the actual returned class. Moving a helper changes its ClassFile owner; restoring the initial signatures proves continued calls without reinstalling. A rejected batch must preserve existing reachable callers and retained-object behavior. Runtime class addition is append-only and precedes the atomic redefine transaction: a newly appended type can remain after rejection. The recovery edits that type again and checks its corrected body, rather than claiming namespace rollback or class unloading.

Model initialization is covered by the dedicated properties group and contract below; suspended coroutines/Flow are covered by the coroutines contract below. Repository/DI/proxy integration still needs dedicated coverage. Run affected groups during development and `full` on both devices after freezing, serially on each device. Keep unchanged engine matrix evidence with its original provenance, separate from the new sample APK runs. Functional success does not resolve historical performance or long-pause failures.

## Retained lambda contract

Kotlin numbers compiler-generated implementation methods by source order. Inserting a literal can change that number; adding a capture can also change its descriptor. D8's installed synthetic closure still calls the bridge emitted for the old implementation. Preserving only the current D8 pass's bridges loses that entry, producing `NoSuchMethodError` or an incorrect target if an ordinal is reused.

The host retains the initial D8 bridges and every subsequently emitted closure entry for the session. Those entries invoke the latest logical body. Logical matching uses the enclosing method (or parent lambda), SAM contract, normalized body and capture schema, never the source ordinal alone. Unchanged bodies anchor the match; only a sole remaining old/new pair can evolve. A capture schema alone cannot identify a literal when an inserted decoy retains that schema and the original gains captures. Multiple simultaneous edits in the same scope/SAM group may therefore require separate reloads. An ambiguous edit is rejected on the host before any device update; correcting it can retry without changing the active mapping. A removed source lambda keeps its last body callable by retained closures.

For a retained closure, a capture with the same debug name and JVM type keeps its original value, independently of parameter order. New, renamed or retyped captures get JVM defaults: reference/array `null`, numeric zero, boolean `false`. Initializers, registration factories and constructors do not run again. A new body that dereferences a default-null capture can therefore throw a normal `NullPointerException`; the host does not infer historical initializer values. A newly created control closure captures current real values. Removing and restoring a capture recovers each closure cohort's original binding if it had that name/type at creation.

Missing capture debug names allow only unchanged bodies and capture layouts; evolution is refused. Retained direct compiler entries preserve their original modifiers, including private interface bodies. Ordinary method references retain their declared target contract. The current planner covers LambdaMetafactory compiler bodies, not arbitrary anonymous-class capture migration. It commits its ABI inventory only for owners actually added/redefined on the device, including reported partial success; rejected plans do not advance it.

Callback mismatches fail immediately. The runner has no replacement-listener recovery step. It proves old/new behavior separately through actual calls, then verifies host refusal and native transaction refusal followed by successful retries.

## State preservation boundary

The rendering and refactoring groups reject a batch by making an independent, reflectively retained admission guard abstract. The guard has a real live instance, and ART must reject the transaction; changing it does not change the Compose parameter ABI. The existing `recovery` group separately covers making the live ViewModel abstract.

An explored combination changed the ViewModel modifiers and a row label together. Kotlin incremental compilation changed both parameter-stability checks and intrinsic remember comparisons in its callers, changing real cache-slot layout. The existing group-reset contract then cleared local panel/row state while preserving the ViewModel, draft, selection, service and root remember state. That run remains a state-preservation failure for the stronger expectation; this change does not implement migration between different Compose slot layouts. The tests use the independent guard to isolate transaction atomicity from that layout evolution. No parameter-prologue normalization or compiler-optimization opt-out is applied.

## Model property and initialization contract

The `properties` group retains a provider-owned ViewModel, business model, delegate owner and data class, seeded through actual page input. Each edit calls real getters on retained objects and constructs fresh controls. Immutable anchors and an unedited initialization ledger detect replacement and constructor/initializer replay.

- Existing compatible fields retain values. Updated property initializers and `init` blocks run only for new objects; computed getters use their new bodies on both populations.
- Added fields on old objects start at JVM defaults, including null for a Kotlin non-null String. Dereferencing that null is checked as NPE. Int-to-Long changes reset the old slot to 0L. The tested Any-to-CharSequence StringBuilder reference is retained under the engine's direct compatibility rule; an incompatible Number target clears it.
- Lazy initializer bodies are edited separately: an evaluated retained lazy keeps its cached value; an unevaluated retained lazy executes the updated body on first access. Per-owner counters must remain exactly one for each evaluated initializer. `properties-lazy` isolates these two edits.
- Existing mutableStateOf delegates retain user values when initializers change. Added delegate slots are null on old owners and getters raise NPE. String-to-Int delegate evolution preserves the erased MutableState slot and its old String, so the new getter raises CCE. An explicit page action writes an Int through the setter and verifies recovery; no initializer is replayed.
- Added data-class constructor properties use JVM defaults on old instances and declaration defaults on new ones. Primitive retyping resets old values. Generated componentN, copy/copy$default, equals, hashCode and toString are called directly. A copy has a distinct identity and the receiver's current values, including null/zero, rather than newly declared defaults. Constructor counters include the copy.
- A live independent admission guard rejects a batch containing pending model properties. Old calls and construction remain valid; correcting the guard commits the pending edit in the same process. Every step requests GC and backgrounds/resumes the page. Input, reference anchors and an independent remember token remain checked.

Missing or generically incompatible delegates are recorded usability boundaries, not successful normal property reads. Application initialization migration must be explicit; this group demonstrates setter migration for an existing delegate, not reconstruction of missing delegate initializers. Representative ClassFiles are saved and checked for real descriptor changes and delegate erasure. Run `--scenario-group properties` during development and `full` after freezing. Execution outcomes are recorded separately in local verification records.

## Suspended tasks and Flow

The `coroutines` group uses a retained provider-owned ViewModel, a SupervisorJob, real kotlinx.coroutines, and UI-driven external completions through `suspendCancellableCoroutine`. Reload does not start or restart work. It verifies callback-based request processing, not HTTP transport retries.

Suspended tasks retain pre-suspension values and resume through updated compatible code; fresh tasks use current initialization and bodies. Named suspend methods, suspend lambdas and nested calls are checked separately. Appending a suspension after the current compatible label must execute each request once. Cancellation runs cleanup and completion once; late callbacks are ignored. Exceptions produce one error completion and do not prevent the supervisor's next task.

Cold Flow factories retain their old captures while updated builder bodies execute. Active and fresh collectors must receive each applicable event once through updated map/collect bodies without resubscribing. Suspended map operations, cancellation/recollection, and StateFlow equality conflation are checked explicitly. Atomic refusal preserves the old pending request/collector behavior; corrected updates can proceed.

An unedited ledger checks all requests, deliveries, cancellations, late callbacks, outputs, subscriptions, unsubscriptions and terminal notifications, including output order per collector. Every stage requests GC and backgrounds/resumes the same page, preserving input and identity anchors. Real compiler continuation ClassFiles/DebugMetadata are retained as evidence. Arbitrary label renumbering or incompatible live spill changes are outside the compatible-state contract; appending a suspension does not establish general state-machine migration. See the [detailed contract](real-edits.zh-CN.md#suspend协程与-flow-契约). Run `--scenario-group coroutines` during development; `full` includes it after freeze.


See [integration scenarios](integration-edits.zh-CN.md) for effect ownership, Activity recreation, real HTTP and cached adapters, cross-module compilation, and sustained editing contracts.
