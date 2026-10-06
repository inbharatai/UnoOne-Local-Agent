# Real host multitask probe — partial pass, explicit model-output failure

Fresh x86_64 host run using the existing verified Qwen model directory and pinned host `libunoone_qwen.so`/MNN setup. No mock inference, Android AAR native library, production edits, Gradle invocation, or model binary copy. Java heap capped at 384 MiB; no VM/address-space/RSS kill limits. Parent Gradle lint JVM was concurrently present (~968 MiB RSS at preflight). Only one model engine was loaded.

## Actual execution boundary

Freshly compiled unchanged production TaskCoordinator, TaskContract, TaskResourceArbiter (ProcessTaskResources.model), QwenMnnPlanner, and QwenMnnRuntime; dependencies came from existing production core/localbrain Kotlin class directories and cached Kotlin/coroutines/serialization jars. No Android framework stubs were needed for this path. The host-only harness supplies worker registration/request submission and calls **actual production QwenMnnPlanner.controllerRequest**, which calls actual production Kotlin JNI runtime on the same planner/engine. This is not execution of the full Android composition root or LocalBrain.draftText itself. Its system prompt is copied exactly from the new LocalBrain.draftText, including placeholders and 100-word bound. Source, dependency class, model, and native-library hashes are included. Compiler friend-path permits access to existing internal PlannerToolRouter class; tool routing/LiteRT inference was not invoked.

## Observations

- Effective native receipt: CPU, two threads (language and vision), greedy, thinking off, reuse_kv=false, max_new_tokens=256, decoder context=2048. Original config file was not rewritten.
- Load 11.482 s; draft A 30.774 s; draft B 30.500 s; process wall 73.727 s; peak sampled RSS 2,053,775,360 bytes (~1.913 GiB).
- A: task `14148d97-2f80-4069-b518-7fb9835049db`, garden invitation, includes ORCHID731, excludes COBALT942.
- B: task `a8d89d73-bff7-4e73-9219-cc34ca2374b5`, library reminder, excludes ORCHID731 but **omits its requested COBALT942**.
- Both distinct drafts returned RESPONDED. No opposite marker appeared, but the strict marker-following test failed: **overall JVM exit 1**, not a full pass. This is limited two-request isolation evidence, not proof of arbitrary context isolation or output quality.
- The assertion before the failing marker check verified distinct task IDs, exactly two actual controller requests, maximum overlapping controller calls=1, and no output from cancelled task. Instrumentation brackets actual controllerRequest under shared ProcessTaskResources.model. Each request budget allows one model call. The final PASS line did not execute due to the marker failure.
- Third task `1b883523-0a0f-4280-9a63-e932d973e630` was explicitly asserted QUEUED before cancellation and returned CANCELLED/STOPPED; it never acquired a model call. Harness holds first task's real model lease behind a CompletableDeferred barrier while second occupies the other background lane, so third is deterministically queued. This tests coordinator queued admission prevention, **not cancellation of active JNI** or nativeStop acknowledgment. The barrier is scheduling control, not fake inference.
- Native close ACK=true in finally. Reload omitted to keep run bounded.

Full exact prompts, unmodified outputs, stdout/stderr, native config receipt, RSS samples, harness and driver, and hashes are retained. Coordinator journal file was intended to be written after assertions, so it was not produced following the strict marker failure; stdout retains cancellation and outcome receipts. No rerun was performed to cherry-pick a passing answer. The driver itself exits zero after recording JVM exit 1 in run-result.json; interpret the recorded JVM exit and assessment, not the driver's shell status.
