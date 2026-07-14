# Phase 5 — Gemma 4 E2B on Xiaomi 14 (VERIFIED, not skipped)

Date: 2026-07-14 (run completed 19:05:57 IST)
Device: Xiaomi 14 (houji), SoC SM8650 (Snapdragon 8 Gen 3), arm64-v8a, RAM 11,436,548 kB (~12 GB)
Build: app-debug.apk @ `com.unoone.agent` v0.4.0-alpha-v2 (versionCode 4), installed via `/data/local/tmp` + `pm install` (HyperOS "Install via USB" gate workaround)
Branch: `validation/xiaomi14-end-to-end` @ 932e9ed (off clean main@90e879e)
Test runner: `androidx.test.runner.AndroidJUnitRunner` (added to `app/build.gradle.kts` defaultConfig)

## 0. Root cause of the earlier false "OK (4 tests) in 0.061s"

The first instrumented run reported `OK (4 tests)` in **0.061 s** — suspiciously fast. Diagnosis via a one-shot
`ModelPathDiagnosticTest` (runs as the real `untrusted_app` context, not `run-as`) showed:

```
getExternalFilesDir(models)=/storage/emulated/0/Android/data/com.unoone.agent/files/models   (app-owned)
brain dir path=/storage/.../files/models/brain/gemma-4-e2b
brain dir exists=false isDirectory=false        <- app CANNOT see the dir
listFiles()=null (denied/missing)
getLlmModelPath()=null
```

**Cause:** the `brain/gemma-4-e2b` directory was created by `adb push` (owned by `shell`), and the app's
`untrusted_app` SELinux context cannot traverse a shell-owned directory under the package path.
`ensureModelDirectories().mkdirs()` is a silent no-op because the shell-owned `brain` already "exists" from
the shell view but is invisible to the app -> `getLlmModelPath()` returned null -> all 4 tests skipped via
`assumeTrue("No .litertlm model found")` (JUnit counts an assumption failure as a non-failure -> "OK").

**Fix:** remove the shell-owned `brain` tree; let the app recreate `brain/gemma-4-e2b` app-owned
(via `ensureModelDirectories()` inside the diagnostic test); re-push the model into the now-app-owned
dir. The pushed file is `shell:ext_data_rw -rw-rw-rw-` but the **parent dirs are app-owned/traversable**,
so the app can list + read it:

```
brain dir exists=true isDirectory=true
listFiles()=1
  entry: gemma-4-E2B-it.litertlm len=2588147712 isFile=true
getLlmModelPath()=/storage/emulated/0/Android/data/com.unoone.agent/files/models/brain/gemma-4-e2b/gemma-4-E2B-it.litertlm
exact file exists=true len=2588147712 canRead=true
```

## 1. Authentication (sha256, byte-for-byte)

| Source | sha256 | size (bytes) |
|---|---|---|
| Manifest (`models_manifest.json`, gemma-4-e2b) | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | 2588147712 |
| Host source (`.model-cache/gemma-4-E2B-it.litertlm`) | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | 2588147712 |
| On-device (`/storage/.../brain/gemma-4-e2b/gemma-4-E2B-it.litertlm`) | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` | 2588147712 |

**All three identical -> authenticated, untampered.**

## 2. It works — model LOADS and PLANS

`am instrument` of `GemmaPlannerAccuracyTest` + `BrainEvalHarnessTest`:
```
Time: 77.657
OK (4 tests)
run finished: 4 tests, 0 failed, 0 ignored
```

LiteRT-LM loader logs (real native lib):
```
GemmaPlanner: loading Gemma 4 E2B (gemma-4-e2b) from /storage/.../gemma-4-E2B-it.litertlm
engine_settings: MainExecutorSettings: backend: GPU          (GPU first attempt)
E native: gpu_backend_opengl.cc:170  (GPU creation failed)
W UnoOne: GemmaPlanner: GPU backend failed (Failed to create engine: INTERNAL: ...)
engine_settings: MainExecutorSettings: backend: CPU          (automatic fallback)
I UnoOne: GemmaPlanner: Gemma 4 E2B loaded on CPU backend, conversation ready
```

GPU backend failed on this device (OpenGL delegate internal error, SM8650) -> automatic **CPU fallback**
worked as designed by `BackendPreference.GPU_FIRST`. The brain runs on **CPU**.

Tool planning (real inferences):
```
planning for: Remember to buy milk tomorrow
  -> tool=create_note, args={"content":"Remember to buy milk tomorrow","title":"Buy Milk Reminder"}   OK
planning for: Open Chrome
  -> tool=open_chrome, args={}                                                                        OK
```

## 3. Accuracy — 18-case eval (100% tool-match)

```
==== UNOONE BRAIN EVAL ====
Profile: unknown | backend:
EvalSummary: 18 / 18 fully correct (100%), 18 / 18 tool-match (100%)
  [OK] nav-chrome          open_chrome
  [OK] nav-app             open_app
  [OK] nav-camera          open_camera
  [OK] nav-dialer          open_dialer
  [OK] note-create         create_note
  [OK] note-search         search_notes
  [OK] note-delete-one     delete_notes
  [OK] note-delete-all     delete_all_notes
  [OK] note-delete-paraphrased  delete_all_notes
  [OK] screen-read         read_screen
  [OK] web-search          web_search
  [OK] url-open            open_url
  [OK] summarize           summarize_text
  [OK] whatsapp-send       send_whatsapp
  [OK] email-draft         draft_email
  [OK] calendar-check      check_calendar
  [OK] voice-record       voice_recording
  [OK] blind-aid-off      deactivate_blind_aid
==== END BRAIN EVAL ====
```
All 18 prompts produced the expected canonical tool. **18/18 (100%) on the physical device.**

## 4. Performance / device-condition metrics (README Phase B)

| Metric | Value |
|---|---|
| Cold model load (CPU, first) | **7556 ms** (~7.5 s) |
| Warm model load (subsequent) | 862 ms / 888 ms / 910 ms |
| Inference latency per prompt (warm) | ~12 s first two (cold-ish KV); ~2.3 s/prompt avg across 18-case eval |
| Backend | **CPU** (GPU failed -> fallback) |
| Device RAM total / free at run | 11,436,548 kB / 737,228 kB |
| CPU thermal (post-run) | CPU0 72.6, CPU1 76.6, CPU2 81.0 C, mStatus=0 (no throttling) |
| Battery | 62 %, temp 37.3 C, AC powered (charging via USB from host) |
| Thermal status | 0 (none) |

Note: explicit "first-token / tokens-per-second" counters are not emitted by the LiteRT-LM runtime to
logcat in this build; load time + per-prompt wall-clock are the available proxies (recorded above).

## 5. README / code discrepancy (honesty note)

README Phase B text says "run 50 phone-planning + 50 PageAgent tasks". The actual
`EvalPromptSet` shipped in code has **18 cases** (the harness asserts `summary.total == cases.size` and
reports `18 / 18`). The 50/50 figure in the README is aspirational, not the implemented set. This matches
the memory-recorded constraint that README claims must not overstate code reality.

## 6. Verdict — README Phase B gate

- [x] Download Gemma 4 E2B artifact (done previously)
- [x] **Prove LiteRT-LM load on Xiaomi 14** — LOADED on CPU backend, conversation ready, cold load 7.5 s
- [x] Run phone-planning task set on device — 18/18 (100%) tool-match (not 50; see section 5)
- [~] Measure cold load / first token / tokens-sec / RAM / temp / battery — cold load + RAM + thermal +
     battery captured; explicit first-token/tokens-sec counters not emitted by runtime (noted)

**Phase 5 PASSED with device evidence.** Gemma 4 E2B is authenticated, loads, and plans at 100% tool-match
on the Xiaomi 14 (CPU backend). Phase B "device-verified" for the load+accuracy gate.

Evidence files in this dir: `phase5-brain-run3-instrument.log`, `phase5-brain-run3-logcat-full.log`.