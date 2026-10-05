# JNIProof — independent read-only evidence review

## Verdict: narrowly ACCEPT real text + vision host bridge evidence

The saved AFTER execution supports real production Kotlin → JNI → MNN text generation and the two controlled red-image fixtures on Linux x86_64/amd64. It is NOT Android-device inference, broad vision accuracy, a 4GB-device qualification, or an independently repeated model run. I did not load the model, run Gradle, edit source, commit, alter saved evidence, kill processes, or touch delivery gates. Only this review file was written.

## Independent checks performed

- Recomputed all 67 non-model SHA-256 entries in `qwen-vision-diagnostic/sha256.json`: **zero mismatches**, including CURRENT production JNI, Kotlin, resize/header/epoch code, saved before/after binaries, harness, outputs, receipts, and libMNN.
- Stream-hashed all nine actual model files: each matches BOTH the diagnostic manifest and `qwen-artifact-evidence/checksums.json`. Pin unchanged: `taobao-mnn/Qwen3.5-2B-MNN@35781816d7b6a9dcb273a6765ac9563401951c3c`. No model substitution/config-content edit detected.
- CURRENT `qwen_jni.cpp` and `vision_budget.h` match the recorded AFTER source hashes exactly. Kotlin is unchanged from original host build evidence. Compared all four production `com/unoone/` class entries in original and diagnostic harness jars: byte-identical. The harness itself intentionally adds the 256 control; it is not identical to the original harness, but the controlled BEFORE and AFTER commands both use the same diagnostic jar.
- Checked saved run commands: same JVM limits, same diagnostic jar, same model config; differences are before/after native-library and output directories. This is saved build/run provenance plus independent hash verification, not a fresh reproducible compilation of the JNI binary.

## Controlled output evidence (raw bytes independently read)

| Phase | Text | red32 | red256 | Every output == callback bytes |
|---|---|---|---|---|
| BEFORE, exit 1 | `{"action":"wait"}` | `white` | `red` | yes |
| AFTER, exit 0 | `{"action":"wait"}` | `red` | `red` | yes |

Fixtures match between phases: red32 `becf0cf30d6978325d9ccdea315923e2dbbf7e591c2857b92b5f82e41957bb98`; red256 `4f55763bbb5f30e77c6a573b1c56d42126c2febfa2686da24fc32e2e76d99ce0`.

`Harness.kt:50–56` uses the same prompts for both sizes and phases: system `Answer with only one lowercase color word.`, user `What color is this image?`, output budget 32. Expected remains red, with the existing case/terminal-period tolerance; raw AFTER output is literally `red`. Callback equality is checked before semantic assertion. No changed expectation hides the original white failure; original files and failed controlled baseline are retained. Text prompt and exact JSON assertion remain present. Saved AFTER log reports all harness assertions passed including close/reload.

## Admission/cancellation and actual image-pad accounting

Reviewed CURRENT production `qwen_jni.cpp`:
- Lines 169–178 select supported graph processor, Qwen3 factor32/min65536 or Qwen2 factor28/min3136; unsupported signatures reject. BEFORE receipt min784 → AFTER min65536; max589824 unchanged.
- Lines 257–270 retain encoded cap, strict PNG/JPEG header admission, add computed resized-area check BEFORE decode, then compare actual decoded dimensions to admitted dimensions. Input remains 32..768 each axis. Encoder output may exceed768 on one thin axis; area remains <=589824. Do not claim every resized axis is <=768.
- Lines 289–307 retain cancellation checks, actual multimodal tokenizer IDs, positive image-token evidence against text baseline, actual prompt+output context admission before decoder prefill, then one-token generation steps. Encoder is bounded but not interruptible mid-call.
- Both BEFORE and AFTER actual receipts explicitly show `image_pad:248056`, `decoder_context_tokens:512`, max images1 and encoded bytes4194304. Line152 overrides runtime fallback151859 from resolved config; lines297/300 count using the overridden runtime value. Thus imagePad248056 is actually in the run configuration, not inferred from fallback text. Decoder admission uses actual token count (not an approximate image-token estimate).
- `image_admission.h` and `request_epoch.h` remain byte-identical to original build evidence. Stale epoch rejection, cancellation and no text-only vision fallback remain.

## Independent cheap executable reruns

Executed existing compiled binaries directly, with no rebuild/model load:
1. `qwen-vision-diagnostic/resize-oracle`: exit0; factor28/min3136, 543169 cases, maxSide756/maxArea571536; factor32/min65536, 543169 cases, maxSide1280/maxArea589824.
2. `qwen-vision-diagnostic/native-blockers-test`: exit0; reports PASS native epoch barrier, prepare/entry race, fresh/stale epochs; PNG/JPEG bounds and unsupported/truncated input.
3. `qwen-vision-diagnostic/vision-budget-test`: exit0.

## Explicit first-failure / 4GiB guard history — not erased

The original attempt failed during first real text generation: RLIMIT_AS was **3,000,000,000 bytes of virtual address space**, not an RSS cap; MNN allocation warnings preceded AVX GEMM SIGSEGV and JVM exit -6. Preserved under `qwen-jni-host-validation/failure-as3gb/`. This unsafe allocation-failure behavior remains a historical failure, not repaired by the vision resize change.

The subsequent higher-AS attempt kept the **4GiB physical cgroup cap (4,294,967,296 bytes)** and stopped safely under the then-strict near-cgroup guard (current3,662,991,360; exit-9), before completing load/generation; preserved under `failure-cgroup-cache/`. The later cache-aware guarded run completed real text but FAILED red32→white. Controlled BEFORE again fails white. Only AFTER restoration passes both red fixtures. Later runs use AS6GiB plus sampled RSS2.8GiB and conditional near-cgroup protection; these are not proof of a 4GB Android deployment pass. Historical oom_kill1 must not be represented as a newly caused OOM or omitted as though history had been clean.

## Code and artifact SHA-256 (independently matched CURRENT to recorded AFTER)

| Item | SHA-256 |
|---|---|
| CURRENT/AFTER qwen_jni.cpp | `9bba13ed5277c51ec11de2c1881d872e25bbdfabf930c0fd56117c28829240e6` |
| BEFORE qwen_jni.cpp | `aaf80b5b2a9ab92a748b88904cc6ea5013c3bb5fa485a4f5a10565cd80a522c2` |
| CURRENT/AFTER vision_budget.h | `b1f4381499e41deb419df0df20db86ca6390657de2d1dd9ce067e3ccd09b7214` |
| Kotlin QwenMnnRuntime.kt | `6e007a82d4022365776bc94c7a9cdb27546b1dbb22d45e1eb022e0481a863952` |
| image_admission.h | `e00bf6f214458ebd3f51b6c57be177682cf24df41031f3e97841961494e824f4` |
| request_epoch.h | `79cedb2b70099fb5a354b509d26efe361ca6e967ecfd3a5c82fbfa7248f89fa4` |
| BEFORE JNI .so | `c8b200d125e38ba85e67c3bffa4f25e5d7e17ca0229833a421a3a2b267b5d898` |
| AFTER JNI .so | `0affc14993a57e6c2e7d8311ecf34a0fa39c5cd9fa37ea7b1d9e5b5987fc7b16` |
| unchanged libMNN.so | `ddcb418443e7ae0adbcfdffc28d5d52c0339f2d33f901255b5b9c931a1135475` |
| diagnostic harness.jar | `0d1a72dba56f0688a03bd089cc29ed1c07cd67cb75298d9a0ce4f2b471053634` |
| resize-oracle executable | `04d288cf1ca7a258893e470047539e21891250a36ef4cb7445fe1bebad6e4599` |
| native-blockers executable | `4ebc379b3616e4dcb8799765bb1584f1595ce358d73e01333143936b28e09e23` |

JNI source and JNI binary necessarily changed for the repair; it would be false to say *all* artifacts were unchanged. The unchanged identities are model pin/bytes, libMNN, production Kotlin, strict header and cancellation code, and controlled harness/fixtures/prompts. Accept that narrow claim only.
