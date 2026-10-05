# Reviewed host JNI evidence — narrow acceptance

**Physical Android: NOT RUN. Main delivery gates: PENDING.** This archive does not change release test totals, README claims, device qualification or default model selection.

The [independent review](independent-review.md) narrowly accepts real production Kotlin → JNI → MNN inference on **Linux x86_64 (JVM amd64)**. The runtime's static `MNN CPU arm64` label is unmeasured metadata, not the executed architecture. This is **3 toy inference cases / 13 harness assertions**, not broad text/vision accuracy. The independent reviewer checked saved evidence and hashes; did not independently repeat model inference.

| Controlled phase | Text JSON | red32 (expected red) | red256 control (expected red) | Exit |
|---|---|---|---|---|
| BEFORE | `{"action":"wait"}` | `white` — FAIL | `red` | 1 |
| AFTER | `{"action":"wait"}` | `red` | `red` | 0 |

All returned output bytes equal their concatenated callback bytes in both phases. Exact UTF-8 callback files are archived as `.txt`, not binary payloads. Expected red and its existing case/terminal-period tolerance were unchanged. Same diagnostic harness jar, prompts, fixtures, immutable nine-file model and production Kotlin; JNI source/binary changed. The original harness differs from the controlled harness, which added the 256 control. See [harness source](Harness.kt.txt), [before stdout](before/run.stdout), [after stdout](after/run.stdout), [before receipt](before/receipt.json), [after receipt](after/receipt.json), and [diagnosis](vision-diagnosis.md).

## Runtime policy and resize correction

Actual receipts show CPU, greedy sampling, 2 threads (including vision), low precision/memory, thinking disabled, max_new_tokens 64; image prompts use output budget 32. Diagnostic decoder context is **512**, versus application default **2048**; the host test does not validate the full application context. `image_pad=248056` is resolved from actual config. Qwen3 graph processor minimum restored **784 → 65536** (factor32), maximum589824 unchanged; red32 is resized to256² like the passing control. Qwen2 audited branch uses factor28/min3136. Input dimensions remain32..768, encoded bytes<=4194304, one image. Encoder output axes are not all <=768 (thin images can reach1280), but resized area is guarded <=589824 before decode. Actual multimodal token count plus output budget is admitted before decoder prefill. Epoch/header/cancellation protections remain; encoder is not interruptible mid-call.

## Failure and guarded-retry history (not erased)

1. Original first text-generation attempt: **RLIMIT_AS=3,000,000,000 virtual-address bytes**, not RSS. MNN allocation warnings preceded AVX GEMM **SIGSEGV**, JVM exit **-6**. Vision repair does not fix this unsafe allocation-failure behavior.
2. Higher-AS retry retained physical cgroup cap **4,294,967,296 bytes**; strict near-cgroup guard stopped at current3,662,991,360, exit **-9**, before load/generation completion.
3. Later cache-aware guarded run completed real text but failed red32→white; [original output](original/original-image-output.txt) remains archived. Controlled BEFORE reproduced white; only AFTER passed both red fixtures.
4. Controlled runs used **AS6GiB=6,442,450,944**, sampled RSS guard **3,006,477,107** (2.8GiB),100ms polling,110s wall; conditional near-cgroup guard >=3.9GB AND (clean reclaimable cache<400MB OR estimated unreclaimable>=3.5GB). Before/after peaks sampled RSS2,044,821,504/2,080,821,248; HWM2,088,931,328/2,096,349,184. Memory events did not change; historical **oom_kill=1** is not a new event. These are not proof of 4GB Android deployment.

See [before run summary](before/run-summary.json), [after run summary](after/run-summary.json), and [original guarded run](original/run-summary.json). Raw hs_err dumps, process lists and environment inventories were deliberately not copied. Full originals remain outside Git, including both early failures.

## Provenance / archive policy

[Original artifact SHA inventory](original-artifact-sha256.json) records exact source, nine model files, before/after JNI binaries, libMNN, harness, fixtures, outputs and callbacks. Pinned model revision35781816d7b6a9dcb273a6765ac9563401951c3c; MNN revision024a946b0b8fcf87c8a418229fadd4cd7858ffba. [Archive provenance](archive-provenance.json) records each selected original SHA-256 separately from its archived SHA-256; local paths are placeholders `${REPO}`, `${DIAGNOSTIC}`, `${MODEL}`, `${ORIGINAL_HOST}`, `${MNN_HOST}`, `${TOOLCHAINS}`, `${WORKSPACE}`. Placeholders are identities, not resolvable links. The original SHA inventory describes excluded payloads; it does not imply those files are committed. Run summaries omit machine memory-stat dumps and retain only evidence-relevant limits/results. All selected files are UTF-8 text; no weights, executables, jars, images or raw crash dumps included.

Independent review verified all67 non-model entries and all9 actual model files. Archivist separately rechecked67 non-model hashes, validated archived JSON, exact output/callback equality and relative Markdown links. Android arm64 link/build evidence is build-only, **not physical-device inference**. Resize oracle and native blockers tests are bounded checks, not a broad qualification suite.
