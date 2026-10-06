# Evaluation-only rejected Owl prompts

**Neither candidate is production-qualified. These sources are outside all Android Gradle source sets.** V1 baseline 2/2 versus candidate 0/2; V2 baseline 4/4 versus candidate 3/4 (unsupported focus). Faster invalid outputs are unusable, not a speedup. FULL production builder is unchanged.

`src/newScopedOwlPrompt.kt` and the two `ScopedOwlPrompt*Test.kt` files were newly untracked files moved byte-for-byte, not deleted published production files. `source-move-receipt.json` records original paths, byte sizes and SHA-256. Original source comments are retained as historical content (including V2's outdated 'unvalidated' comment); the negative model results, not those comments, govern production rejection. Baseline `OwlProtocolTest.kt` remains in its Android test source set.

## Standalone host evaluation (no model, no Gradle)

From the repository root:

```sh
python3 evaluation/voice-prompt-experiments/run_host_tests.py
```

Requires the original host's preinstalled JDK17/Kotlin compiler, serialization/JUnit/Hamcrest jars, pinned host classes and existing core dependency classes recorded in `docs/evidence/floating-voice-prompt/voice-prompt-v2/compile-command.json`. There are no automatic downloads. `GRADLE_USER_HOME` can override the dependency-cache search for JUnit/Hamcrest; the remaining exact original classpaths are deliberately explicit in the receipt. On another machine provision matching pinned inputs and adapt those paths before use. The wrapper derives commands without modifying archived receipts, compiles unchanged baseline codec/binding/builder plus candidate source, the retained production protocol test source and evaluation tests to a fresh external temporary directory, then runs JUnit. It does not load JNI, run a model or write Android build outputs. Candidate tests include protocol regressions and literal-input contracts; `RejectedRawOutputTest` separately asserts archived rejected raw output remains rejected, without coordinate salvage or output repair.

This archive task did not execute the compiler/tests. The runnable command is provided for a separately authorized host gate. Passing source-contract tests would not undo the failed real-model comparison. Do not include these evaluation-only tests in final application/module test counts; recompute those after the parent final gate.

## Historical real-inference reproduction

[Raw evidence](../../docs/evidence/floating-voice-prompt/README.md) preserves each original `prepare.py`, `Probe.kt`, `compile-command.json`, `invocation.json`, `monitor.py`, `analyze.py`, exact prompts, model/runtime pins and fixture bounding boxes. Actual PNG/JNI/class binaries stay outside git at the original paths in `archive-manifest.json`. Original reproduction commands refer to the pre-move candidate path: for historical reproduction use the experiment's preserved `candidate-source.kt` (V1 and V2 differ), and fresh external output directories; never restore candidates into APK sources or overwrite original evidence. Verify all model/library/fixture hashes before a separately authorized expensive inference run. No inference is launched by the host-test wrapper.

Old Android build caches can still contain candidate class/dex files. A parent-owned clean final build and artifact inspection is needed before asserting absence from a newly delivered APK; only source-set quarantine is established here.
