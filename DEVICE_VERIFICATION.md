# Device Verification — UnoOne

**Status:** Template / not yet populated. This file is the single place where real-device
end-to-end verification results are recorded. Until the matrix below is filled in with actual
device logs, **UnoOne is an alpha, not production-verified** (see `STATUS.md`).

> CI verifies build, unit tests (289), lint, and `assembleDebug` on a host JVM — it cannot run
> native Sherpa (Whisper/MMS) or LiteRT-LM (Gemma 3n E4B / Gemma 4 E2B) inference, Accessibility, or camera.
> Those require a real device with models installed. This file captures that verification.
>
> **Two brain profiles** are now selectable. The **default is Gemma 3n E4B** (`gemma-3n-e4b`, folder
> `gemma-local/`) — the device-verified fallback. **Gemma 4 E2B** (`gemma-4-e2b`, folder
> `gemma-4-e2b/`, 128K context, ~2.58 GB) is **Experimental and not device-verified**. It must pass
> step 5 below (load + perform a tool call) on each device before it may be treated as working.

## Device matrix

| Device | Android | SoC | RAM | Build/install/UI smoke | Sherpa STT | Sherpa TTS | Wake word | Gemma 3n E4B planning | Gemma 4 E2B load+self-test | Accessibility control | Blind Aid | Date | Log file |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Xiaomi 14 | 15 (35) | Snapdragon 8 Gen 3 | 12 GB | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |
| Samsung Galaxy S24 | 15 (35) | Exynos 2400 / SD8G3 | 8/12 GB | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |
| Pixel 8 | 15 (35) | Tensor G3 | 8/12 GB | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | — | — |

Legend: ✅ verified · ❌ failed · ☐ not yet run.

## Per-device test procedure

For each device, install the debug APK, install the needed models (Model Status screen or ADB),
grant all permissions, then run:

### 1. Build / install / UI smoke
- [ ] `assembleDebug` APK installs without error.
- [ ] App launches; Model Status screen lists models with correct presence/health.
- [ ] Floating bubble toggles on/off; persistent notification shows.

### 2. Sherpa STT (offline)
- [ ] English: "create a note buy milk" → transcribed → note saved. (model: `sherpa-asr-en`)
- [ ] One Indic language (hi/bn/ta/te/kn/ml): same command in that language → transcribed. (model: `sherpa-asr-whisper`, language selected in Settings)

### 3. Sherpa TTS (offline)
- [ ] English response spoken. (`sherpa-tts-en`)
- [ ] One Indic TTS spoken. (`sherpa-tts-<lang>`)

### 4. Wake word
- [ ] Say "UnoOne" → detection fires → command captured. (`vad`)

### 5. Gemma 3n E4B planning (default brain)
- [ ] Load `.litertlm` → `GemmaPlanner` initializes (GPU, falls back to CPU if needed). Record the winning backend.
- [ ] A complex command produces a correct tool call, parsed + safety-classified + executed.
- [ ] **Unknown-tool rejection**: a prompt that would make the model propose a non-canonical tool (e.g. attempt to invoke `make_payment`/`send_message`) is rejected by `GemmaPlanner` and never executed.
- [ ] **Inference timeout**: a deliberately long/complex prompt completes within the 30s `INFERENCE_TIMEOUT_MS`; if a call hangs, the brain closes cleanly and reloads on next use (no hang, no crash).
- [ ] **Brain self-test** (Settings → Model Status → Brain Model → Run Self-Test on the `gemma-3n-e4b` profile): reports the loaded backend, load time, the proposed tool, and that the tool is in the `CanonicalToolRegistry` (tool accepted).
- [ ] Run `GemmaPlannerAccuracyTest` instrumented: `./gradlew connectedDebugAndroidTest`. (It loads the first `.litertlm` under `gemma-local/` — the Gemma 3n E4B profile.)

### 5b. Gemma 4 E2B load + self-test (Experimental — required before treating Gemma 4 as working)
> Gemma 4 E2B is **not device-verified**. It is selectable + self-testable but must pass this step on each device before it may be marketed as functional. The default stays Gemma 3n E4B until the matrix is green.

- [ ] Install the `gemma-4-e2b` model (Model Status screen, or `adb push` into `…/files/models/gemma-4-e2b/`).
- [ ] In Settings → Model Status → Brain Model, select the **Gemma 4 E2B** profile and confirm it loads (record backend: GPU or CPU fallback, load time).
- [ ] Run the **Brain Self-Test** on the `gemma-4-e2b` profile. Record: loaded backend, load ms, proposed tool, `toolAccepted` (must be `true` — i.e. the proposed tool is in the `CanonicalToolRegistry`).
- [ ] Issue a real complex command through the Gemma 4 brain and confirm the proposed tool is canonical, parsed, safety-classified, and executed correctly.
- [ ] **`UnoOneToolSet`↔registry runtime cross-check**: this cannot run as a JVM unit test (the `litertlm` AAR bytecode is newer than the JDK 17 test JVM can load — `UnsupportedClassVersionError`). On device, confirm the tool names `UnoOneToolSet` advertises to the model exactly match the 26 canonical tools the planner will accept. A mismatch means the model could propose a tool the planner rejects (safe) or, worse, the planner accepts a tool the model was never told about. Record the comparison.
- [ ] Restore the default (Gemma 3n E4B) selection afterward unless you intend to keep Gemma 4.

### 5c. Calibration eval (Gemma 3n E4B vs Gemma 4 E2B — measurement)
> The eval harness turns "is Gemma good enough?" into a number. Run it for **both** profiles and
> record the two `EvalSummary` blocks below — these are the real numbers behind any default-switch
> decision. The harness is instrumented; control + scoring are JVM-tested, the runner is device-time-only
> and has **not** been run yet (no accuracy numbers are claimed until it is).

- [ ] Push the Gemma 3n E4B model, run `./gradlew :app:connectedDebugAndroidTest --tests '*.BrainEvalHarnessTest'`, capture the printed `EvalSummary` (profile, backend, accuracy, toolAccuracy, per-case OK/arg/MISS).
- [ ] Push the Gemma 4 E2B model, run the same command, capture its `EvalSummary`.
- [ ] Record both summaries in the matrix row (or attach the two log captures). A profile is "good enough" only when its accuracy on this set is acceptable for the target hardware tier — the harness prints the number, a human makes the call.

### 5d. Agent innovations #4–#8 (device-time-only / partly inactive)

> These five innovations split their control logic (JVM-tested, in `:core`/`:modelmanager`) from
> their LiteRT-LM inference + device wiring (device-time-only). None is claimed working until a
> physical device runs it; two are additionally wired-but-INACTIVE by design.

- [ ] **Streaming (#6):** with `STREAMING_INFERENCE_ENABLED = true`, an LLM-planned command surfaces partial text to the timeline as it streams (an evolving "Thinking" step). Confirm the `sendMessageAsync` `Flow<Message>` emits partials and the final validated tool call matches the synchronous path. If streaming throws, confirm the command still completes via the synchronous fallback.
- [ ] **Self-heal (#7):** force the brain to auto-close (let an inference time out, or unload), then issue a command — confirm the proactive reload fires ("Recovering"/"Recovered" timeline steps) and the command plans via the LLM. Force a tool to fail repeatedly — confirm the flaky-tool surfacing appears once.
- [ ] **Outcome memory (#5):** run a command that fails (e.g. open a non-installed app), then re-issue a similar command — confirm the planner is now told "prior avoid: …". Run the eval harness (5c) with outcome memory populated vs cleared to measure whether the hint changes accuracy.
- [ ] **Multimodal vision (#4):** run `describe_scene` — confirm it returns the OCR + foreground-context description (the JVM-tested fallback, since `VISION_MODEL_ENABLED = false`). Confirm STRONG_CONFIRM fires before capture. Vision understanding is NOT claimed (no vision-capable `.litertlm` ships); re-test only after one ships and the flag is flipped.
- [ ] **Manifest integrity (#8):** confirm `ModelManifestLoader` still loads the shipped (unsigned) manifest with the blank `ManifestSigningKey.PUBLIC_KEY_BASE64`. Signature enforcement is NOT claimed until the publisher sets a key and a device proves EdDSA verifies at API 33+ (minSdk 28 falls back to accept + log).

### 6. Accessibility control

- [ ] `read_screen` returns visible text (after CONFIRM).
- [ ] `find_and_click Login` / `fill …` / scroll / back / home execute (after STRONG_CONFIRM).
- [ ] Service survives screen-off; stops cleanly when disabled.

### 7. Blind Aid
- [ ] `detect_objects` starts camera + overlay + haptics + spoken guidance.
- [ ] Failure states handled: low light / motion blur / camera blocked → spoken warning.
- [ ] `deactivate_blind_aid` stops cleanly.

### 8. Sensitive-surface confirmations
- [ ] `send_whatsapp` draft → STRONG_CONFIRM → WhatsApp opens with pre-filled message (user presses send).
- [ ] `web_search` with Online tools OFF → explicit offline message; with ON → attributed results.
- [ ] "send OTP" / "auto send" → BLOCK.

## How to record results

1. Replace each ☐ with ✅/❌ and fill Date + Log file (path to the captured `adb logcat` or
   `Diagnostics` export for that run).
2. Attach the Diagnostics latency export (Settings → Logs) for the Gemma + Sherpa runs.
3. Commit the completed table. Once all cells are ✅, bump `versionName` to `1.0.0` and remove the
   alpha disclaimer from `STATUS.md` / README.
4. **Gemma 4 E2B default decision is separate.** Passing step 5b on the matrix confirms Gemma 4
   *loads and plans* on a device — it does **not** automatically make Gemma 4 the default. Switching
   the default profile (`BrainModelRegistry.defaultProfile`) is an explicit change made only after
   Gemma 4 is verified across the full matrix **and** step 5c (the calibration eval harness) shows
   it matching or beating Gemma 3n E4B on the prompt set for the target hardware tier. Until then
   the default stays Gemma 3n E4B, and Gemma 4 remains an Experimental opt-in.

## Known device-specific notes (populate during runs)

- Manufacturer autostart prompts (Xiaomi/Huawei/Oppo/Vivo/OnePlus/Asus) — record any extra step.
- GPU backend availability for LiteRT-LM per SoC — record GPU vs CPU fallback.
- Sherpa native `.so` load success per ABI (arm64-v8a expected).