# SwiftV2Probe — held-out synthetic real 4B JNI validation

**CANDIDATE ONLY. FULL remains default. No phone-speed or general noninferiority claim.**

Archive: `/agent/workspace/owl-native-prep/voice-prompt-v2`

## Declared workflow and frozen design
Four NEW 256×256 GUI cases: navigation CLICK Library, navigation CLICK Settings at new positions, FOCUS Search query, and WRITE an already-focused Draft title with exact literal `AbC 42`. Safe NAVIGATION controls were used instead of semantically unknown/protected Continue. Fixtures and independent renderer bounding boxes were frozen before inference; no expected coordinates or native target geometry were passed to either model prompt. The WRITE literal value was supplied identically as required task data. All screens visibly marked SYNTHETIC GUI / TEST. They are not real screenshots. W1 title and field label slightly overlap; field, caret and focused marker remain visible. This fixed fixture was not repaired or replaced after observing outputs.

Actual unchanged production OwlPromptBuilder.build versus actual compiled buildScopedV2 / COMPACT_CANDIDATE_V2. Identical per-case native-goal JSON and PNG bytes; baseline wraps same goal via build(scopedGoal, empty untrustedData), candidate uses same data directly. Exact system/user prompts and source archived. Order: baseline/candidate N1; candidate/baseline N2; baseline/candidate F1; candidate/baseline W1. Exactly eight attempted calls, no retries/tuning/cherry-picking/output repairs and no malformed-output coordinate extraction.

Existing production runtime classes, JNI and llama shared libraries verified against prior voice-prompt-ab hashes (candidate source intentionally V2, V1 archived unchanged). Decoder Q4_K_M and mmproj Q8_0 freshly SHA-256 verified against receipts. One JVM/model, sequential native greedy requests, default ctx2048/output256/imageEdge512 CPU2. Native request memory reset remains production behavior. No Gradle, model downloads, production/default/native edits or address-space limit. Standalone Kotlin compilation finished before model load.

## Results
| Order | Variant/case | Wall ms | Native text / image tokens | Codec | Bbox or exact text | Native exact action | Valid + correct |
|---|---|---:|---|---|---|---|---|
| 1 | baseline-N1 | 70912 | 1049 / 64 | True | True | True | True |
| 2 | candidate-N1 | 37330 | 493 / 64 | True | True | True | True |
| 3 | candidate-N2 | 36580 | 493 / 64 | True | True | True | True |
| 4 | baseline-N2 | 67141 | 1049 / 64 | True | True | True | True |
| 5 | baseline-F1 | 66983 | 1051 / 64 | True | True | True | True |
| 6 | candidate-F1 | 34833 | 495 / 64 | False | None | False | False |
| 7 | candidate-W1 | 37245 | 502 / 64 | True | True | True | True |
| 8 | baseline-W1 | 66877 | 1058 / 64 | True | True | True | True |

Production-codec valid/correct: baseline 4/4, V2 3/4.

Failure retained: candidate-F1: java.lang.IllegalStateException: Unsupported official action. No malformed coordinates extracted or rescued.
Observed raw mean return walls: baseline 67978.2ms, V2 36497.0ms. Do NOT interpret a faster invalid/incorrect return as successful-action speedup. No overall speedup claim is warranted when any case fails.

Quality caveat: candidate-N2 binds at pixel x=128, exactly the native target left edge (left-inclusive bounds), not the requested center. It passes the frozen bbox/native-action criterion, but provides no robustness margin or center-grounding evidence. Invalid candidate-F1 coordinates are not scored (null), rather than treated as a repaired click.

Actual token counts are JNI admission text/image counts, not estimates. Generated-output token counts and stage/prefill/decode/TTFT counters are not exposed by unchanged runtime: recorded null, not fabricated. Measured walls cover public generate/JNI calls; model-load wall is separately in stdout.

## Production native checks and limits
Actual production OwlOutputCodec.decode executes first. Only accepted outputs enter coordinate/text scoring. Actual production OwlNativeBinding.translate is compiled from source; production DeviceActionValidator and device model dependencies are loaded from existing core classes whose hashes are archived. Each case uses synthetic native UiSnapshot, scope, receipt and clock, asserting expected ClickNode/FocusNode/SetText (exact case-sensitive `AbC 42`). A focus click is not counted as WRITE; no write is counted without a focused field. These simulated native contracts test code paths, NOT real capture consent, freshness, ownership, authorization, dispatch or device success. No UI action was dispatched. Non-ASCII character preservation is not established by the permitted ASCII-valued Unicode literal `AbC 42`.

## Resource receipt
```json
{
  "exit_code": 0,
  "termination": "NORMAL_PROCESS_EXIT",
  "elapsed_seconds": 419.27853550799773,
  "peak_RSS_KiB": 3330200,
  "peak_VmHWM_KiB": 3330628,
  "peak_memory_current_bytes": 4125421568,
  "min_MemAvailable_KiB": 2459468,
  "native_close_ack_seen": true
}
```
RSS/VmHWM ceiling3.8GiB; MemAvailable floor256MiB; 100ms monitoring; outer900s; native request180s. Child PID termination only if a guard trips. No RLIMIT_AS imposed. Preflight and process receipts archived.
Post-run preservation: 511 files checked; changed=[].

## Recommendation
Keep FULL default and V2 disabled. Preserve rejected V1 and all its failures. At most these four synthetic paired cases support a narrowly scoped follow-up; no broad promotion or general noninferiority.

## Raw unmodified EOS outputs

### baseline-N1
```
Action: Click on the "Library" button in the "Demo workspace" section.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [257, 428]}}
</tool_call>
```

### candidate-N1
```
Action: Click the "Library" button.
<tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate": [250, 431]}}
</tool_call>
```

### candidate-N2
```
Action: Click the "Settings" button.
<tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[500, 800]}}
</tool_call>
```

### baseline-N2
```
Action: Click on the "Settings" button to open the settings menu.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [694, 790]}}
</tool_call>
```

### baseline-F1
```
Action: Click on the "Search query" input field to activate it.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [505, 684]}}
</tool_call>
```

### candidate-F1
```
Action: Focus the search query input field.
<tool_call>{"name":"mobile_use","arguments":{"action":"focus","coordinate": [500, 675]}}
</tool_call>
```

### candidate-W1
```
Action: Type "AbC 42" into the focused input field.
<tool_call>{"name":"mobile_use","arguments":{"action":"type","text":"AbC 42"}}
</tool_call>
```

### baseline-W1
```
Action: Enter the text "AbC 42" into the focused input field.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "type", "text": "AbC 42"}}
</tool_call>
```
