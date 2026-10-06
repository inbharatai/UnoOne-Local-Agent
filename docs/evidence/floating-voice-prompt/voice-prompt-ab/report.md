# Owl full vs scoped candidate: real host A/B

**SYNTHETIC HOST TEST ONLY — TWO CASES, NOT PHONE SPEED OR NONINFERIORITY.**

Archive: `/agent/workspace/owl-native-prep/voice-prompt-ab`

## Design and identity
Actual unchanged production OwlPromptBuilder.build versus actual compiled buildScoped COMPACT_CANDIDATE_V1; no app integration, production edits, downloads, Gradle, or native changes. Standalone compiler exited before model was loaded. One model/JVM, sequential order baseline-A, candidate-B, candidate-A, baseline-B, no retry/tuning/output repair. Existing production runtime and JNI verified against real-probe-final hashes before launch and after run. Existing 4B Q4_K_M decoder plus Q8_0 mmproj freshly hash verified. Runtime defaults ctx2048/output256/imageEdge512, CPU2, greedy; unchanged 256x256 PNGs and actual image token admission preserved.

Both variants receive identical goal metadata: `{"operation":"CLICK","exactLabel":"Search","scopePackage":"example.app"}`. Baseline passes this exact JSON as scopedGoal, empty untrustedData; candidate obtains it directly from buildScoped. No bbox or case identity supplied to inference. This differs from earlier historical probe wording; only the paired results here are compared. Package is synthetic metadata, not Android scope proof.

Production JNI clears model memory before every accepted generation (owl_jni.cpp line86), then greedy sampling line87; no retained-request context. Success returns only at EOG; truncation/error outputs are not promoted. Full supplied system/user prompts, candidate source/version/SHA, invocation, model hash receipts, raw outputs, production codec outputs, PNGs, independent bbox scores, stderr native admission/profile and 100ms RSS samples are archived.

## Results
| Order | Variant/case | Generate wall ms | Text / image tokens | Codec | Accepted Search hit |
|---|---|---:|---|---|---|
| 1 | baseline-A | 68075 | 1049 / 64 | True | True |
| 2 | candidate-B | 28725 | 386 / 64 | False | False |
| 3 | candidate-A | 28922 | 386 / 64 | False | False |
| 4 | baseline-B | 67965 | 1049 / 64 | True | True |

Observed two-case mean total generate wall: baseline 68020.0ms, candidate 28823.5ms; 57.6% lower candidate mean.

Native stage counters are not exposed by this runtime. These are measured public generate/JNI total wall times and native admission token counts, NOT inferred prefill, vision, decode or time-to-first-token measurements. Output generated-token count is not exposed; null in results, not estimated. Any library diagnostic timing lines remain raw evidence, not a phase profiler.

## Resources and completion
```json
{
  "exit_code": 0,
  "termination": "NORMAL_PROCESS_EXIT",
  "elapsed_seconds": 196.35572022799897,
  "peak_RSS_KiB": 3317648,
  "peak_VmHWM_KiB": 3317844,
  "peak_memory_current_bytes": 4129296384,
  "min_MemAvailable_KiB": 2537728,
  "native_close_ack_seen": true
}
```
Native max request180s, outer900s; RSS3.8GiB / MemAvailable256MiB guards target child PID only. No AS limit imposed. Preflight and final processes retained.

## Recommendation
Keep FULL as default; do not enable this candidate. Candidate failed the production codec on both cases, so its shorter return time is NOT successful-action latency or a usable speedup. Both exact failures remain archived; no tuning/retry/output repair was performed. These two synthetic clicks cannot establish general accuracy, noninferiority, phone speed, or authorization safety.

## Raw coordinate diagnostic
Candidate raw text contains coordinates inside the correct Search bounds in both cases, but its literal `<tool_call> JSON </tool_call>: ...` omits the required mobile_use envelope and places JSON outside the tool_call block. Both outputs are rejected by the actual production codec. Raw coordinate extraction is diagnostic only, not output repair, validation, dispatch or successful-action evidence; detailed raw and codec scores are separate in results.json.

## Raw EOS outputs

### baseline-A
```
Action: Click on the "Search" button with red background
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [741, 673]}}
</tool_call>
```

### candidate-B
```
Action: Click the "Search" button
<tool_call> JSON </tool_call>: {"action":"click","coordinate": [272, 293]}

```

### candidate-A
```
Action: Click the "Search" button
<tool_call> JSON </tool_call>: {"action":"click","coordinate": [744, 678]}

```

### baseline-B
```
Action: Click on the "Search" button to proceed with the operation.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [257, 293]}}
</tool_call>
```
