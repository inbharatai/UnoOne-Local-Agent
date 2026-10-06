# Fresh real host draft-quality gate probe

One fresh run, no cherry-picked reruns. Original `../multitask-host` failure evidence remains byte-for-byte unchanged (full before hash manifest and post-run comparison retained). No production source edits, Gradle, or commit.

## Actual exercised implementation

Direct Kotlin 2.2.21 compiler freshly compiled production `DraftQualityGate` / `DraftConstraintPolicy`, `DraftRequest`, `Result`, `TaskCoordinator`, `TaskContract`, `TaskResourceArbiter`, `QwenMnnPlanner`, and `QwenMnnRuntime`. Full cached kotlinx serialization core+JSON JVM 1.8.1 and coroutines classpath included. Actual `DraftQualityGate.execute` is invoked, not copied checks or a regex/model grader. Other production core/localbrain dependency classes are reused and hashed. Harness jar appears first on the runtime classpath.

`LocalBrainDraftBridge.kt` contains the exact unmodified `LocalBrain.draftText` method extracted from current source, including request bounds, literal-phrase JSON system prompting and blank-output handling. Only its surrounding host class and controller forwarding adapter differ: adapter records exact system/user text, then calls actual `QwenMnnPlanner.controllerRequest` using its unchanged defaults. Provenance verification proves exact method inclusion and hashes LocalBrain source. This is NOT execution of Android LocalBrain composition/lifecycle or UI; no framework mocking or fake model inference is used.

Every gate generate callback independently takes actual `ProcessTaskResources.model.withLease`, calls actual `ctx.beforeModelCall`, and invokes real JNI-backed controllerRequest. Budget maximum is two model calls per task. Gate outcomes feed actual worker `TaskResult`: RESPONDED iff gate checks pass without error; otherwise NEEDS_USER; never VERIFIED.

Existing compiled host JNI and MNN native libraries were reused. All nine model files and both native libraries were freshly SHA-256 checked against original pinned evidence: all match. `loaded-file-hashes.json` captures actually mapped native/runtime files from `/proc/PID/maps`; weights read into memory are represented separately in `run-result.json` and `provenance-verification.json`, not falsely claimed to remain mapped. Native receipt retains CPU/two threads, greedy, thinking disabled, reuse_kv=false and 256-token maximum.

## Observed run

- JVM exit 0; load 7.165 s; calls 7.986 s and 5.686 s; process wall 21.886 s.
- Peak 200 ms sampled RSS: 1,900,703,744 bytes (about 1.770 GiB). Java heap maximum 384 MiB; no RSS/address-space kill limit.
- A `70adb14f-015f-4751-a6fb-72324cf6337b`: one candidate; ORCHID731 native literal check passes; RESPONDED.
- B `b6d47af4-889e-4eb9-bb0f-5ee9707ef5bf`: one candidate; COBALT942 native literal check passes; RESPONDED.
- Two total actual model calls, maximum concurrent calls one. Monotonic call start/end observations retained. Each task independently had a two-call budget.
- Third task `675a597c-afbf-4100-ad2f-190848967fae` asserted QUEUED before cancel, returned CANCELLED/STOPPED, zero native calls. Scheduling barrier only controls admission; it is not fake inference. This does not test active-JNI cancellation.
- Native close ACK=true.

Both first candidates passed, so no repair/repeated failure occurred and no additional model request was manufactured. `repeatFailure=false` is an observation, NOT coverage of the NEEDS_USER/two-attempt failure branch. Existing original marker-omission failure remains retained and failed; this run does not relabel it.

Each `*-execution.json` stores exact gate candidate plus native passed/nonblank/failedPhraseIndexes and semanticFactsVerified=false. Each request has user-provided own marker as its sole native constraint. Opposite-marker context-leak test is a separate observation only and does not influence task outcome: neither candidate included the opposite marker. This tiny sample is not a general isolation proof. The original prompt also asks for placeholders/35 words; these semantic/style requirements are not checked by the literal gate. B added “This is urgent”; that unsupported assertion remains in the raw evidence, reinforcing that RESPONDED is not factual verification.

Complete prompts, outputs, attempts, journal, receipt, stdout/stderr, RSS series, current source/dependency/native/model hashes, harness sources, bridge and driver are retained here. The executed harness JAR's hash is recorded; its compiled binary is retained outside Git and can be regenerated from the supplied harness sources/commands. No model or compiled harness binary is committed. The driver records JVM exit separately (its own shell success alone is not the verdict). No reload, device qualification or Android instrumentation run was performed.
