# Real Owl production-host probe — completed, with explicit limitations

## Verdict
**The real downloaded 4B + projector loaded through the actual production `OwlLlamaRuntime` Kotlin and `owl_jni.cpp` library. Real text inference returned `4`; real 256×256 red-image inference returned `Red`. Native close acknowledged true.**

**The primary full official GUI prompt was rejected at the 1024-context admission gate before prefill, and the 2048 fallback was deliberately NOT run because the conservative measured-RSS headroom gate failed. Therefore there is NO successful Search-button GUI grounding result, no real-phone result, and no Android/world-accuracy claim.**

**Safety failure found:** malformed UTF-8 JNI input was NOT rejected at input admission. It reached real inference and only subsequently failed the one-token output/EOS limit. Do not mislabel that as a successful UTF-8 rejection test.

No production edits, Gradle, native compilation, commits, or simultaneous models. Only harness/evidence files created. One JVM/native probe process; native-process wall time **28.021 s**, bounded externally to 480 s.

## Exact artifacts and invocation
Evidence directory: `/agent/workspace/owl-native-prep/real-probe/`.
Production binaries/classes used from `/agent/workspace/owl-native-prep/production-host/`; actual `OwlPromptBuilder.kt` compiled separately into the harness, not copied into a mock. Preparatory Kotlin compiler exited before probe launch. Preflight process listing shows no Gradle/native/compiler/model processes active.

Runtime receipt:
```
llama.cpp=4f5406761517648c23dbd60ea5ade37f77a316c9;cpu=2;mmap=true;repack=false;gpu=false;ctx=1024;output=128;imageEdge=256;visionTokens=64;mrope=mtmd;deepstack=mtmd;hostReduced=true;deviceQualified=false
```

JVM flags: `-Xms16m -Xmx128m`; `RLIMIT_AS=(-1,-1)` — **no artificial virtual-address limit**. Exact argv/classpath/library paths are in `invocation.json`. `source-binary-fixture-hashes.json` hashes the production JNI source, .so, Kotlin runtime source, actual builder, harness and PNGs. `evidence-manifest.sha256.json` additionally covers compiled classes and all retained evidence.

Both entire model files were freshly streamed through SHA256 before the JVM launched; no tensor edits:

| Artifact | Bytes | SHA256 |
|---|---:|---|
| GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf | 2,497,282,208 | `8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232` |
| GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf | 453,974,336 | `b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073` |

`model-hash-reverification.json` retains fresh hashes and original download receipts (immutable HF revision `9a79d301329062eb02e46f7ccad82c99075bfaaa`). Weights remain untouched; upstream decoder reports `load_mode=mmap`, CPU_Mapped model buffer 2375.91 MiB. Read-only file-backed pages are evictable; do NOT subtract RSS from MemAvailable or treat all model RSS as anonymous committed memory.

## Actual tests and preserved outcomes
| Test | Actual result | Interpretation |
|---|---|---|
| Production reduced load | Success, ~3.1 s | Actual decoder/context/vision allocated, no fake output |
| Stale queued permit | `IllegalStateException: Stale request permit` | Captured permit, cancelled before generate; refused before inference |
| Actual builder + synthetic GUI, 1024 / output128 | `Full text+vision+reserved output exceeds context; no truncation`, 9 ms | Correct up-front admission failure, NOT model inference failure |
| Text `What is 2+2?` | Exact output `4`, 5.812 s | EOS-complete result, 23 input tokens, output reserve16 |
| Red PNG, dominant-color question | Exact output `Red`, 13.515 s | Actual mtmd vision encoding; 91 total input / 64 image tokens; output reserve16 |
| Count 1..20 with output limit1 | `Output token limit reached without EOS; partial proposal rejected`, 2.974 s | Truncation safely threw; no partial proposal returned |
| Raw invalid UTF-8 bytes `C3 28` in JNI text, output1 | Same output-token-limit exception, 2.095 s | **FAILED input rejection**: native admitted 15 tokens and evaluated malformed input |
| Phone-default 2048 fallback | Skipped explicitly | Observed peak exceeded conservative fallback gate |
| Final close | `FINAL_NATIVE_CLOSE_ACK=true status=UNLOADED` | Native destruction acknowledged; process exited0 |

Each test has `.output.txt` or full `.error.txt` stack trace. `stdout.log` contains timestamps and config; `stderr.log` is preserved **as raw bytes** including the intentionally malformed UTF-8 sequence. Decode that file with backslash replacement only when viewing; do not overwrite the original. JVM exit0 is harness completion, NOT an assertion that all safety/quality tests passed.

### Official prompt and synthetic fixture
`official-system.txt` (4074 characters) + `official-user.txt` (270) are the exact runtime inputs from `OwlPromptBuilder.build("Click the Search button.", "Synthetic test fixture only; no real device or execution.")`. The native bridge adds its actual pinned ChatML wrapper and real media marker. Full input + image + reserved128 output exceeded ctx1024; no prompt shortening or truncation was performed. The native code prints token totals only AFTER successful admission, so the precise rejected total is **not observable in the current receipt**; no count invented. mtmd reported an 8×8 image token grid. Admission occurs before `mtmd_helper_eval_chunks`.

`synthetic-gui-256.png` is clearly labeled **SYNTHETIC GUI TEST**, with Home upper-left, Settings lower-left, contrasting red Search lower-right. Search center is pixels `(190,175)`, normalized `(742.1875,683.59375)`; fixture bounds/labels saved in `fixture.json`. Visually inspected the file. **Those coordinates describe the authored test fixture, not a model prediction.** The GUI request never reached inference. `synthetic-red-256.png` is the separate solid-red engine sanity input. No real screenshot or real-phone proof.

## External resource monitoring and 2048 decision
`monitor.py` sampled child `/proc/<pid>/status` and system `/proc/meminfo` every 100 ms into `memory.jsonl`. The native code lives in that JVM, so child RSS includes both native and JVM allocations.

- Peak sampled RSS: **3,159,760 KiB = 3085.70 MiB = 3.013 GiB**.
- Minimum system MemAvailable: **2,649,208 KiB = 2587.12 MiB** (many resident file-backed model pages remain reclaimable).
- Hard cutoff: RSS **3,407,872 KiB = 3.25 GiB**, or MemAvailable below **163,840 KiB = 160 MiB**. Either triggers TERM, then KILL after3s and `RESOURCE_LIMIT`, never “model bug.” Neither triggered.
- Native timeout: 480 s, TERM/KILL and `TIME_LIMIT`. Not triggered.
- Outcome: `NORMAL_PROCESS_EXIT`, exit0, close ACK seen.
- Fallback gate fixed before execution: observed peakRSS <2.75 GiB AND minimum MemAvailable >512 MiB. Peak failed; no second allocation/inference process was started.
- Actual remaining RSS margin to hard cutoff was only **242.30 MiB**. Reduced KV is144 MiB; ctx2048 adds approximately another144 MiB before any larger default vision/compute/allocator work, leaving only~98 MiB. High MemAvailable does not itself guarantee compliance with the separate RSS cutoff. Conservative skip was intentional, not a claim that 2048 is impossible.

Raw upstream stderr reports reduced CPU KV144.00 MiB, text compute50.35 MiB, vision compute9.75 MiB. Peak above includes real runtime overhead; no synthetic memory estimate was substituted for measurement. Sampling can miss sub-100ms peaks; VmHWM values are retained per sample.

## Fix instructions and next gate (NOT applied)
1. **Strict input UTF-8 validation:** in `owl_jni.cpp` validate both raw system/text byte strings immediately after `bytes(...)` and BEFORE `plain`, prompt concatenation/tokenization, or eval. Reject malformed/overlong sequences, UTF-16 surrogate scalars, out-of-range codepoints and incomplete sequences. Existing byte budget / reserved marker checks are not UTF-8 validation. The reflection-only diagnostic bypassed Kotlin's String surface but exercised the actual production JNI entrypoint; native input hardening currently fails.
2. **Kotlin malformed-string rejection:** `String.toByteArray(Charsets.UTF_8)` silently replaces unpaired surrogates. Use a UTF-8 `CharsetEncoder` with malformed/unmappable input REPORT (and propagate a clear input error) if the requirement is reject rather than normalize. This public-string issue is source inspection, not a separate executed surrogate test.
3. **Admission observability:** print/return the actual total text+vision, vision count and requested output/context BEFORE throwing the context rejection. Preserve reject-before-prefill and do not truncate the official system prompt.
4. **Primary GUI test remains a gate:** authorize a new exclusive run on a host/RSS budget with comfortable ctx2048 headroom, using the actual builder and unchanged labeled fixture. Require EOS-complete raw output, then independently parse/compare the predicted click with the Search bounds; no action dispatch. Do not report the red-image sanity as GUI grounding success.

No load failure occurred, so no decoder/template/ABI fix is warranted from this run. Existing device defaults, coordinator concurrency, real cancellation timing, actual phone package/dependency loading, and Android quality remain unqualified.
