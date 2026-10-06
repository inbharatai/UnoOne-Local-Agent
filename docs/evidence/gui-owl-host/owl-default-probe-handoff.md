# Fresh real Owl DEFAULT-budget host probe — completed

## Verdict
**PASS on this bounded host synthetic test:** unchanged verified real 4B decoder/projector, rebuilt production JNI + Kotlin, actual `OwlPromptBuilder` and production `OwlOutputCodec`; BOTH moved-Search synthetic images produced EOS-complete click proposals accepted by the production codec, and an independent fixture-bounds scorer found both coordinates inside Search. **NOT phone proof, real-screenshot qualification, broad grounding accuracy, or latency suitability.** No actions were dispatched.

**Original C3 28 regression now rejected before inference** in native system/text, with/without image. Public Kotlin unpaired-surrogate system/text rejected. Historical `/real-probe` negative evidence remains byte-identical, including its failed UTF admission test: `prior-evidence-preservation.json` verifies all 33 prior files unchanged.

Evidence: `/agent/workspace/owl-native-prep/real-probe-default/`. No production edits, Gradle, native compilation, tensor/model rewrites, or simultaneous model allocations. Preparatory harness-only Kotlin compilation completed before launching the sole real-model JVM. Preflight and final process listings had no concurrent compiler/model JVM. One native allocation; optional reload omitted. Final close and repeated-close both acknowledged true.

## Exact configuration and identity
Production receipt (actual returned value):
```
llama.cpp=4f5406761517648c23dbd60ea5ade37f77a316c9;cpu=2;mmap=true;repack=false;gpu=false;ctx=2048;output=256;imageEdge=512;visionTokens=256;mrope=mtmd;deepstack=mtmd;hostReduced=false;deviceQualified=false
```
Default `r.load(modelRoot)` called without budget overrides. Official GUI requests used all 256 reserved output tokens. Images were 256x256, below the actual default maximum edge512; actual image tokens64, NOT256. No context shortening or prompt edits. Official builder inputs were `Click the Search button.` and `Synthetic test fixture only; no real device or execution.`; exact generated system/user stored separately.

Both weights freshly streamed SHA256 before launch:
- Decoder 2,497,282,208 bytes: `8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232`.
- Projector 453,974,336 bytes: `b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073`.
- Production JNI `.so`: `007520c616380fe31e58b5c05fdb8e0b395331833a6ff65f8ee97cf5561b83a7`.
- Production runtime class: `c7ae9296e4bc44127b8e85feb4b882309506c483889a03e081c5229ef860d871`.

Hashes match the UTF rebuild handoff. Exact argv, classpath, environment library paths, source/class/fixture hashes, raw logs, compiler command/log and evidence manifest retained. JVM `-Xms16m -Xmx128m`; CPU2, mmap, no repack. `RLIMIT_AS=[-1,-1]`: no address-space limit.

## Exact raw EOS-complete GUI returns (no repair; EOF immediately after closing tag)
A (`official-gui-A.output.txt`,181 UTF-8 bytes, no trailing newline), native call wall72.007s:
```
Action: Click on the "Search" button to proceed with the search function.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [743, 673]}}
</tool_call>
```
B (`official-gui-B.output.txt`,177 UTF-8 bytes, no trailing newline), native call wall68.719s:
```
Action: Click on the "Search" button to initiate the search function.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [257, 293]}}
</tool_call>
```
Production parsing: A accepted click(743,673), B accepted click(257,293). **No parsing failures occurred; none suppressed.** Separate `.output.txt` codec results preserved. The strict parser did not extract JSON, repair text, or accept a fixture-generated model output.

Independent scorer uses authored fixture bounds, not prompt predictions:
| Synthetic fixture | Search pixel bbox | Normalized bbox | Real predicted point | Inside |
|---|---|---|---|---|
| A: Search lower-right | [134,145,246,205] | [523.4375,566.40625,960.9375,800.78125] | [743,673] | true |
| B: Search upper-left | [12,45,124,105] | [46.875,175.78125,484.375,410.15625] | [257,293] | true |

Both images visibly say SYNTHETIC GUI TEST A/B and were visually inspected. B swaps Search/Home locations; Settings remains lower-left. `fixture.json`, `independent-scores.json` and scorer source retain provenance. Fixture coordinates were never supplied to inference.

## All other outcomes retained
| Test | Actual outcome | Time / meaning |
|---|---|---|
| Default load | success | ~812ms; files already host-cached, not cold phone startup |
| Raw original C328 in text, no image | `Malformed UTF-8 input` |2ms, before first inference |
| Raw C328 text+image, system without/with image | same native error all3 |0ms rounded, before first inference |
| Kotlin unpaired high surrogate in text/system | `Malformed UTF-16 input; cannot encode UTF-8` both |0ms rounded; no inference |
| Stale queued permit | `Stale request permit` |0ms; pre-native |
| Text What is 2+2? | exact `4` |1.840s |
| Red PNG dominant color | exact `Red` |7.099s, actual vision |
| Valid Hindi + emoji system/user | exact `4` |2.414s; UTF-positive input round through real inference |
| Count1..20, tokenLimit1 | `Output token limit reached without EOS; partial proposal rejected` |1.792s; no partial returned |
| Active cancellation | `Vision/prefill failed` |513ms actual generate-call duration; no output |
| Close | `FINAL_NATIVE_CLOSE_ACK=true status=UNLOADED` |native destruction ACK |
| Repeat close | `SECOND_CLOSE_ACK=true` |idempotent public close |

Every throw has full `.error.txt`; raw stderr and stdout kept. Harness exit0 means harness finished, not every operation returned successfully. No retry or cherry-picking.

### Active cancellation, not just queued cancellation
Captured permit then deliberately queued120ms (measured126ms until generate-call start). Worker entered actual production JNI; new native admission logged text28/image0/output256. Waited for new native stderr then500ms; cancellation requested at statusGENERATING (`stderrAdvanced=true`). Call finished with `Vision/prefill failed` after513ms total; cancel-to-worker-join4ms. Native stderr records graph compute aborted/error1, failed text decode/chunk0. This is an **active prefill cancellation**, not successful decode-generation cancellation. Queue126ms is separate from actual generate513ms. JNI-internal-only nanosecond duration is not instrumented, so513ms is host wall around the real native call including minimal Kotlin/encoding overhead. No fabricated precise native profiler time. No partial proposal emitted; close ACK followed.

## Admission counts and resources
Each official GUI printed BEFORE admission decision:
```
Owl admission: text_tokens=1049 text_plus_vision=1113 image_tokens=64 reserved_output=256 context=2048 image_edge=512
```
Total1369 <=2048: passes without truncation. Other actual counts: text sanity23; red27+64=91; Unicode41; truncation22; cancel28. This run had no context rejection; prior1024 rejection is preserved. New pre-rejection logging implementation confirmed by source ordering and real successful count receipt; do not claim a newly executed default-context failure.

External monitor100ms, child PID only TERM then KILL after3s if limit; 330s external wall bound. Configured RSS/HWM ceiling3.8GiB (3,984,588KiB rounded down), MemAvailable floor256MiB. Threshold breach would be `RESOURCE_LIMIT`, not model bug. No signals needed.
- Process elapsed155.702s; normal exit0.
- Peak sampled RSS3,313,776KiB =3236.109MiB =3.160263GiB.
- Peak observed VmHWM3,313,820KiB =3.160305GiB.
- Minimum MemAvailable2,438,560KiB =2381.406MiB.
- Peak cgroup `memory.current`4,160,905,216 bytes =3.875145GiB (whole cgroup, not child RSS; logged every sample, not mistaken for the RSS limit).
- Native KV288MiB; text compute50.47MiB; vision compute9.75MiB.

Read-only model mmap pages remain reclaimable. No RSS subtraction from MemAvailable. Sampling cannot rule out sub100ms transients; VmHWM helps retain child peak. `memory.jsonl`, `summary.json`, `invocation.json` preserve exact observations and controls.

## Reasonable follow-up, not applied
Only diagnostic issue observed: cooperative cancellation during prefill surfaces generic `Vision/prefill failed` (also text-only), rather than explicit cancellation. In production nativeGenerate, when eval/decode fails, consider checking abortWork/check before the generic error so cancelled/deadline requests are typed distinctly while genuine graph failures remain failures. Preserve fail-closed no-partial behavior. This is classification/observability, not an unsafe output observed here. No production edits made.

No Android/device ABI install, dependency loading, real screenshot, transformed touchscreen dispatch, thermal/phone memory, p95 latency, broad GUI suite, cancellation during vision/decode, post-cancel reuse, or reload qualification is claimed.
