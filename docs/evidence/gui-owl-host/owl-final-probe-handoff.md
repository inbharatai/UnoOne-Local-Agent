# Final real Owl probe after load-permit + stack-batch repairs

**SYNTHETIC HOST TEST — NOT ANDROID / NOT PHONE QUALIFICATION.**

## Result

PASS: all requested probe assertions passed. One run, no retries, no output repair or cherry-picking. All raw returns and exceptions retained. No actions dispatched.

Evidence: `/agent/workspace/owl-native-prep/real-probe-final/`. Sole model JVM exited: `NORMAL_PROCESS_EXIT`, exit `0`, wall `166.063s`. Resource-exclusive preflight, inference and final process snapshots retained. No Gradle, production edits, native compiler, downloads or commits. Standalone harness Kotlin compilation finished before model launch.

## Identity and actual defaults

```
llama.cpp=4f5406761517648c23dbd60ea5ade37f77a316c9;cpu=2;mmap=true;repack=false;gpu=false;ctx=2048;output=256;imageEdge=512;visionTokens=256;mrope=mtmd;deepstack=mtmd;hostReduced=false;deviceQualified=false
```

Called `load(modelRoot)` without budget overrides; GUI requests reserve all 256 output tokens. CPU2; actual ctx2048/output256/imageEdge512. Unchanged 256×256 synthetic fixtures are below max edge512 and tokenize to64 image tokens, not256. Actual production OwlPromptBuilder and OwlOutputCodec sources compiled into harness; actual repaired runtime/planner classes pinned without replacements. JNI loaded from own pinned copy, confirmed by `/proc/self/maps`; dependencies and sources hashed before/after.

Repaired production source binary origin: `/agent/workspace/owl-native-prep/race-production-host`. SHA pins asserted before launch and copied into `pinned-host/`:

- `libunoone_owl.so`: `f3c85dac4703d17211a539c6649b6894fc4884068f13ab0f524e0b0e25610a22`
- `classes/com/unoone/agent/localbrain/owl/OwlLlamaRuntime.class`: `8884b30e8a7886bcd4e84fb448e0ea9b1220c38742852036f62af8b2c26068d3`
- `classes/com/unoone/agent/localbrain/owl/OwlLlamaPlanner.class`: `33f4dd45f0c94c9a7732613b0abdb7751261e3dbb6b98c90f38ec73c09668ed0`

Existing real 4B decoder and mmproj freshly streamed/hash verified; no redownload:
- `GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf`: 2497282208 bytes, SHA256 `8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232`.
- `GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf`: 453974336 bytes, SHA256 `b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073`.

Full source/class/dependency SHA256 manifest: `source-binary-hashes-before.json`; unchanged verification: `source-binary-preservation.json`. Exact argv, libraries, no-AS-limit receipt: `invocation.json`.

## Both moved-Search GUI outcomes

Exact builder inputs: `Click the Search button.` / `Synthetic test fixture only; no real device or execution.` Exact expanded prompts: `official-system.txt` and `official-user.txt`. Bounds never supplied to inference. Images copied byte-for-byte from previous default probe and visually inspected: A Search lower-right, B Search upper-left; Home swapped; Settings unchanged.

### Fixture A

Production codec accepted: **True**; point `[743.0, 673.0]`; independent normalized Search bbox `[523.4375, 566.40625, 960.9375, 800.78125]`; inside **True**.

Exact raw model return (no repair):
```
Action: Click on the "Search" button to proceed with the search function.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [743, 673]}}
</tool_call>
```

### Fixture B

Production codec accepted: **True**; point `[257.0, 293.0]`; independent normalized Search bbox `[46.875, 175.78125, 484.375, 410.15625]`; inside **True**.

Exact raw model return (no repair):
```
Action: Click on the "Search" button to initiate the search function.
<tool_call>
{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [257, 293]}}
</tool_call>
```

## Every test outcome

| Test | Return / exception | Wall ms |
|---|---|---|
| stale-load-permit | ERROR: java.lang.IllegalStateException: Stale load permit | 3 |
| invalid-C328-text-image-false | ERROR: java.lang.IllegalStateException: Malformed UTF-8 input | 1 |
| invalid-C328-text-image-true | ERROR: java.lang.IllegalStateException: Malformed UTF-8 input | 0 |
| invalid-C328-system-image-false | ERROR: java.lang.IllegalStateException: Malformed UTF-8 input | 0 |
| invalid-C328-system-image-true | ERROR: java.lang.IllegalStateException: Malformed UTF-8 input | 0 |
| unpaired-surrogate-text | ERROR: java.lang.IllegalArgumentException: Malformed UTF-16 input; cannot encode UTF-8 | 0 |
| unpaired-surrogate-system | ERROR: java.lang.IllegalArgumentException: Malformed UTF-16 input; cannot encode UTF-8 | 0 |
| stale-queued-permit | ERROR: java.lang.IllegalStateException: Stale request permit | 0 |
| official-gui-A | SUCCESS: output=Action: Click on the "Search" button to proceed with the search function. | 74082 |
| production-codec-A | SUCCESS: output=action=click;x=743.0;y=673.0;proposal=OwlProposal(action=click, coordinate=OwlPoint(x=743.0, y=673.0), coordinate2=null, text=null, time=null, button=null, status=null) | 31 |
| official-gui-B | SUCCESS: output=Action: Click on the "Search" button to initiate the search function. | 71878 |
| production-codec-B | SUCCESS: output=action=click;x=257.0;y=293.0;proposal=OwlProposal(action=click, coordinate=OwlPoint(x=257.0, y=293.0), coordinate2=null, text=null, time=null, button=null, status=null) | 1 |
| text-sanity | SUCCESS: output=4 | 2182 |
| red-image-sanity | SUCCESS: output=Red | 6863 |
| unicode-positive | SUCCESS: output=4 | 2494 |
| output-truncation | ERROR: java.lang.IllegalStateException: Output token limit reached without EOS; partial proposal rejected | 1951 |
| active-cancel | ERROR: java.lang.IllegalStateException: Vision/prefill failed | 519 |
| reload-default | SUCCESS: output=llama.cpp=4f5406761517648c23dbd60ea5ade37f77a316c9;cpu=2;mmap=true;repack=false;gpu=false;ctx=2048;output=256;imageEdge=512;visionTokens=256;mrope=mtmd;deepstack=mtmd;hostReduced=false;deviceQualified=false | 819 |
| reload-text-sanity | SUCCESS: output=4 | 2302 |

Full raw strings / stack traces are separate `.output.txt` / `.error.txt`; `all-outcomes.json` indexes all results. Harness exit0 alone is not a claim every operation succeeded. Negative cases are expected rejections.

Malformed raw C328 system/text, with/without image, and Kotlin unpaired UTF-16 were all tested **before first generation**. Stale load permit checked before the actual default load; stale queued generate permit checked before GUI calls. Truncation returns no partial proposal. Reload is sequential, allowed only after positive close ACK and <380s elapsed; one new default load and one text check, then final/repeated close. This is ordinary public close/reload, not an exhaustive concurrent load-race test.

### Active cancellation and close event receipts
```
PROBE 1791303537634 ACTIVE_NATIVE_CALL_START queueMs=125
PROBE 1791303538143 ACTIVE_CANCEL_REQUEST status=GENERATING stderrAdvanced=true
PROBE 1791303538158 ACTIVE_CANCEL_RETURN workerAlive=false cancelToJoinMs=10
PROBE 1791303538404 PRE_RELOAD_NATIVE_CLOSE_ACK=true status=UNLOADED
PROBE 1791303541628 FINAL_NATIVE_CLOSE_ACK=true status=UNLOADED
PROBE 1791303541630 SECOND_CLOSE_ACK=true
```
Active-cancel timing is host wall around public generate/JNI, not an internal native profiler. Prefill cancellation returning generic `Vision/prefill failed` remains a diagnostic classification limitation, not an accepted partial output. No decode-stage or vision-stage cancellation qualification asserted.

## Native admission and resource monitor
```
Owl admission: text_tokens=1049 text_plus_vision=1113 image_tokens=64 reserved_output=256 context=2048 image_edge=512
Owl admission: text_tokens=1049 text_plus_vision=1113 image_tokens=64 reserved_output=256 context=2048 image_edge=512
Owl admission: text_tokens=23 text_plus_vision=23 image_tokens=0 reserved_output=16 context=2048 image_edge=512
Owl admission: text_tokens=27 text_plus_vision=91 image_tokens=64 reserved_output=16 context=2048 image_edge=512
Owl admission: text_tokens=41 text_plus_vision=41 image_tokens=0 reserved_output=16 context=2048 image_edge=512
Owl admission: text_tokens=22 text_plus_vision=22 image_tokens=0 reserved_output=1 context=2048 image_edge=512
Owl admission: text_tokens=28 text_plus_vision=28 image_tokens=0 reserved_output=256 context=2048 image_edge=512
Owl admission: text_tokens=23 text_plus_vision=23 image_tokens=0 reserved_output=16 context=2048 image_edge=512
```

External monitor every100ms. Child RSS or retained VmHWM ≥3.8GiB (3,984,588KiB), or system MemAvailable <256MiB, triggers RESOURCE_LIMIT; only own child PID TERM then KILL after3s. External wall bound470s (<8min, plus at most3s termination grace). No RLIMIT_AS; invocation records [-1,-1]. No limit signals required if normal exit. Whole-cgroup memory recorded separately, never confused with child RSS.

- Child wall: 166.063s; peak sampled RSS 3319156KiB (3.165394GiB).
- Peak VmHWM 3319820KiB; minimum MemAvailable 2484920KiB.
- Peak whole-cgroup memory.current 4134780928 bytes.
- Sampling cannot rule out sub100ms transients; VmHWM retains observed child high-water mark. Cached/mmap host startup is not cold phone startup.

## Preservation, assertions, limits

Historical `/real-probe` and `/real-probe-default` evidence unchanged: **True**, 88 files. No old evidence overwritten. Source/pinned binaries/dependencies unchanged: **True**.

```json
{
  "stale-load-permit": true,
  "stale-queued-permit": true,
  "output-truncation": true,
  "unpaired-surrogate-text": true,
  "invalid-C328-text-image-false": true,
  "invalid-C328-text-image-true": true,
  "unpaired-surrogate-system": true,
  "invalid-C328-system-image-false": true,
  "invalid-C328-system-image-true": true,
  "text-sanity": true,
  "red-image-sanity": true,
  "unicode-positive": true,
  "reload-text-sanity": true,
  "gui-A": true,
  "gui-B": true,
  "close-acks": true,
  "normal-exit": true,
  "source-binary-unchanged": true,
  "historical-evidence-unchanged": true,
  "actual-pinned-JNI-mapped": true
}
```

This narrowly revalidates real host inference with the repaired production JNI and Kotlin. It does NOT establish Android ABI/loading/install, real screenshot/touch dispatch, broad grounding accuracy, phone memory/thermal/latency, race exhaustiveness or safety across all upstream allocation failures. Production code not edited. Other workers may resume resource-heavy work after this handoff.
