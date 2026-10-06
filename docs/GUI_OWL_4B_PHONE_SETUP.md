# GUI-Owl 1.5 4B phone setup — experimental candidate

**0.7.0-alpha-owl / versionCode 7: host gates passed; physical qualification pending.** Current full JVM suite: **884 passed, zero failures/errors/skips**. Lint, debug and instrumentation APK assembly passed. Owl dependency closure and 16KB ELF/ZIP alignment were checked; one pre-existing CameraX image utility library remains non-16KB ELF-compatible. This is not whole-app 16KB-device certification. [Current artifact/build receipts](evidence/owl-delivery/results.json) identify the exact APK. New hosted CI must be checked separately after delivery. Historical 0.6 evidence remains separate.

## What is supported

GUI-Owl is an explicit experimental profile, using **LLAMA_CPP, CPU only, Android arm64-v8a only**. It is not the default: Gemma 4 E2B remains the new/unknown-selection default. Existing Gemma E4B, Qwen, verified files, selections and user data are retained; selecting/downloading Owl does not delete them. No silent model fallback.

Use **Tasks → GUI-Owl approved screen task**, or the dedicated **Run Owl Self-Test**. Generic chat, draft generation, browser DOM tasks and reviewed general image-advice/scene-description routes are unsupported for Owl. A native open/read/find operation may use no model at all; that is not evidence of Owl inference.

## Exact artifacts, provenance and license caveat

Both files are required, from the same immutable third-party repository revision:

- Repository: [`mradermacher/GUI-Owl-1.5-4B-Instruct-GGUF`](https://huggingface.co/mradermacher/GUI-Owl-1.5-4B-Instruct-GGUF/tree/9a79d301329062eb02e46f7ccad82c99075bfaaa)
- Quantization revision: `9a79d301329062eb02e46f7ccad82c99075bfaaa`
- Manifest ID: `gui-owl-1.5-4b-instruct-gguf`; bundle folder: `brain/gui-owl-1.5-4b-instruct-gguf`

| Artifact | Exact bytes | SHA-256 |
|---|---:|---|
| `GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf` | 2,497,282,208 | `8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232` |
| `GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf` | 453,974,336 | `b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073` |

**Total: 2,951,256,544 bytes, approximately 2.951 GB / 2.749 GiB.** Allow additional free storage for download/verification. Do not mix projectors, rename arbitrary files into the bundle, or bypass hash checks. The installer uses immutable Hugging Face URLs; acquisition needs network access, but inference has no cloud fallback. Weights stay **outside the APK and Git**.

The primary model reference is [`mPLUG/GUI-Owl-1.5-4B-Instruct` at `3f061c2c562cc860c42bf32542a70e07a7ff4840`](https://huggingface.co/mPLUG/GUI-Owl-1.5-4B-Instruct/tree/3f061c2c562cc860c42bf32542a70e07a7ff4840). **The quantization's source-conversion revision is UNKNOWN.** This reference commit is not proof that it produced the selected quantization. Exact hashes prove file identity, not conversion provenance.

**License discrepancy remains unresolved:** primary/community model cards declare MIT; downloaded GGUF metadata declares Apache-2.0. Preserve both disclosures and verify applicable rights/obligations before commercial use or redistribution. This project provides **no legal/commercial clearance**. Runtime llama.cpp is MIT; packaged notices are in [`localbrain/src/main/cpp/licenses`](../android-app/UnoOneAgent/localbrain/src/main/cpp/licenses/).

## Phone prerequisites and memory policy

- API 28+ Android, **arm64-v8a**. CPU baseline only; no GPU/NPU acceleration or performance promise.
- Android-reported total RAM must be **at least 8192 MiB**, available RAM at admission **at least 5,098,740,192 bytes (approximately 4.75 GiB)**, and Android must not report low memory. Available threshold is the complete pair plus a declared 2 GiB KV/vision/runtime reserve: **policy, not measured peak or a guarantee**.
- A marketed “8 GB” phone can report less than 8192 MiB and fail this gate. **12 GB-class phones are recommended for early testing**, still subject to actual reported total/available memory and device qualification. Disk space does not substitute for RAM; mmap does not eliminate the working set.
- Enable UnoOne's Accessibility service through Android settings for observations, known native actions and postconditions. Grant Android screen capture/**MediaProjection** separately for approved screen tasks. Model opt-in is neither Accessibility permission nor screenshot consent.
- Use a non-sensitive test screen, close overlays/floating windows, dismiss the IME, and avoid private information. Privacy classification is conservative, not a universal secrecy guarantee.

## UI walkthrough (source-inspected labels)

1. Open **Settings → Model Status & Install**. The screen title is **Local Models**.
2. Under **Planning profile**, select GUI-Owl. Read **Opt in to experimental GUI-Owl?**, then **Opt in and select** if you accept. This is independent of Qwen consent; it does not authorize screenshots.
3. Under **Advanced Model Diagnostics**, locate `gui-owl-1.5-4b-instruct-gguf` and choose **Install** (or **Repair** if present but unhealthy). Prefer Wi-Fi; metered download prompts **Use mobile data?**. Wait for the complete pair, not just the decoder.
4. Use **Verify complete SHA-256**. Then **Load Brain**. Read any load/memory error rather than lowering the gate. Installed/hash-verified is not device-qualified.
5. Choose **Run Owl Self-Test**. This uses the resident shared runtime for arithmetic and a clearly labeled synthetic Search PNG, with production prompt/codec and an independent expected rectangle. It captures no phone screenshot and dispatches no action. Record actual pass/fail output and durations; do not pre-fill a success. The generic “Experimental vision” status text is not a qualification receipt for this dedicated route.
6. Open **Tasks → GUI-Owl approved screen task**. Optionally tap **Open Owl practice screen (no capture)** to inspect the native demo, then return. Opening it alone neither captures nor runs a task.
7. Tap **Select own app practice goal**. This sets package `com.unoone.agent` and `device: click "Search" in com.unoone.agent`. Alternatively select a currently installed package and enter an explicit supported goal—there is no guessed default target.
8. Tap **Grant Android screen capture permission**, complete the OS MediaProjection flow, and return. Set **Maximum steps (1–12)** and **Maximum seconds (10–600)**; defaults are 4 and 180.
9. Tap **Review bounded task consent**. Review the exact package, goal, limits and full-screen local-frame permission. **Approve task** authorizes this bounded task without a popup for every frame. **Cancel** does not approve.
10. Approval immediately submits the task and produces an admission receipt. For the own-app practice goal, the admitted worker first acquires exclusive UI ownership, waits three seconds, opens the practice activity with a recorded dispatch intent, then waits about 1.2 seconds for settling. These waits are not a displayed countdown. Stop revokes the original approval during review, queuing or either wait; it cannot revive after Stop. Watch the native Search control/result; return to the task board for receipts and **Stop/Cancel**. Approval is memory-only and cannot resume after process death.

The practice activity is a controlled native test surface, not evidence of arbitrary real-world app success. A passing synthetic self-test and an actual practice-task result are distinct receipts.

## Exact bounded command examples and limits

The task parser/native goal allowlist—not a freeform model goal—defines authority. Use straight quoted exact labels and substitute the selected installed package:

```text
device: open <selected.package> then read screen
device: focus "Search" in <selected.package>
device: write "weather" into "Search" in <selected.package>
device: click "Search" in <selected.package>
device: select tab "Home" in <selected.package>
device: find "Exact name" in <selected.package>
```

`write` replaces the exact field value, not append. Labels must resolve to known native semantics; merely matching text does not make an arbitrary control actionable. **Find only verifies one currently visible, exact noneditable native match. It does not navigate/search unseen content.** Only an explicitly declared initial open is allowed; leaving the package stops rather than reopening it. Other device lanes' scroll/back capabilities do not imply Owl task support.

No raw coordinate taps, arbitrary buttons, freeform goals or model-created consent. The model proposes; strict decoding, native target binding, fresh-state equivalence, ownership/cancellation checks and native postconditions decide execution. “Done,” accepted dispatch and no-op actions are not success.

Full-frame unpadded proportional screenshots are resized with an integer aspect-ratio-preserving calculation to edge ≤512. This calculation can **upscale** a small source; it is not a downsample-only guarantee. Incompatible aspect-ratio geometry is rejected, not silently rounded/cropped/padded. Rotation/state changes stop execution. Unknown/custom canvas, WebView, ImageView, **ImageButton, VideoView**, SurfaceView/TextureView, overlays, multi-window and IME can cause **NEEDS_USER** before capture. Consequently many modern apps are expected to require manual takeover. Do not disable these restrictions to make a demo pass.

## Actual host evidence, including failures

These are real host production-runtime probes with real pinned weights, **not Android/app/phone proof**. No actions were dispatched. Original evidence resides at `/agent/workspace/owl-native-prep/real-probe/`; corrected default-budget evidence at `/agent/workspace/owl-native-prep/real-probe-default/` (workspace evidence paths, not promised repository downloads).

- **Original reduced 1024-context run:** text returned `4`, red image returned `Red`; full official GUI prompt was rejected before prefill for context budget. Default fallback was skipped under the predefined resource-headroom rule. Malformed JNI UTF-8 `C3 28` improperly reached inference and only hit output truncation: a genuine failed input-admission test. Preserve this failure, not relabel it as a pass.
- **Corrected default run:** CPU2, mmap, no repack/GPU, context2048, output256, maximum edge512/vision256; actual 256×256 fixtures used **64 image tokens**. Official prompt admission was text1049 + image64 + reserved256 =1369 ≤2048. Two moved-Search synthetic fixtures returned EOS-complete proposals accepted by the production codec:

| Fixture | Normalized prediction | Independent authored Search bounds | Result |
|---|---|---|---|
| A | (743, 673) | x 523.4375–960.9375; y 566.40625–800.78125 | Inside |
| B | (257, 293) | x 46.875–484.375; y 175.78125–410.15625 | Inside |

Two examples are **not an accuracy percentage**, real-screen benchmark or broad grounding claim. Calls took about72.007s and68.719s on that host, not phone latency. Text `4`, image `Red`, valid Hindi/emoji input → `4`, four malformed raw UTF-8 system/text cases with/without image rejected before inference, and public unpaired-surrogate rejection were also recorded. This does not establish model-generated four-byte UTF-8 output coverage; supplementary-output fixtures are separate unit evidence. Truncated output was rejected without partial proposal. Active **prefill** cancellation returned `Vision/prefill failed` after513ms total call wall, with4ms cancel-to-join; not a decode/vision-stage phone Stop bound. Final and repeated close acknowledged true.

The corrected run elapsed155.702s, peak sampled child RSS3,313,776KiB (~3.160GiB), VmHWM3,313,820KiB, minimum host MemAvailable2,438,560KiB; cgroup peak4,160,905,216 bytes is a different scope, not child RSS. No measured phone peak follows. All33 prior evidence files were retained byte-identical; harness exit0 means completion, not that every operation returned success.

**A separate post-load-permit/stack-batch-fix real-model host rerun now has archived receipts:** [all three runs, negatives and raw-text evidence](evidence/gui-owl-host/SUMMARY.md). The final run's JNI/runtime/planner/prompt/codec source hashes match the current files at the archival check (`current-source-vs-final-probe.json`); subsequent edits require another comparison. Final run wall166.063s, GUI call walls74.082s/71.878s, peak sampled child RSS3,319,156KiB (**3.1654GiB**), not phone memory or latency. Two synthetic predictions again landed inside authored bounds; stale load/request permits, malformed inputs, truncation, sequential reload and close were checked. Final active prefill cancellation returned generic `Vision/prefill failed`,519ms call wall and10ms cancel-to-join, not exhaustive cancellation qualification. Full original runs remain unchanged outside Git; the first invalid-UTF8 stderr has a labeled escaped text view and original hash. Native ARM build/package proof and all physical device gates remain separate; no final Android gate status is upgraded by this host rerun.

## Build, native packaging and release gates

Use [README build recipes](../README.md#4-build-and-install-a-local-debug-candidate) and [general phone setup](UNOONE_V3_PHONE_SETUP.md): **Java17, SDK35, NDK27.2.12479018, CMake3.22.1**, locked browser bundle first, then the full Android gates. The matching host Gradle gates and APK receipts are linked above; no phone was attached during this delivery.

llama.cpp is pinned exactly to `4f5406761517648c23dbd60ea5ade37f77a316c9`; source archive SHA-256 `bc04be751c6946e51909d1d5591d5be0fd3802673625916a81df788948be06b9`. First build downloads pinned runtime sources/dependencies, not model weights. Real mtmd handles Qwen3-VL M-RoPE/DeepStack; text-only GGUF support is insufficient.

Inspect final APK for arm64 `libunoone_owl.so`, `libmtmd.so`, `libllama.so`, `libggml.so`, `libggml-base.so`, `libggml-cpu.so` and the shared C++ runtime/dependency closure. Source sets CPU armv8-a. All seven Owl/runtime dependency ELF entries have at least 16KB LOAD alignment, required DSOs are packaged, and ZIP alignment verification passed. The pre-existing `libimage_processing_util_jni.so` does not have 16KB ELF alignment; whole-app compatibility on a 16KB Android device is not claimed. Actual Android dynamic loading and execution remain physical tests, not consequences of inspection.

| Final-source gate | Status |
|---|---|
| Full JVM tests, lint, debug + instrumentation APK assembly | PASSED: 884 JVM tests; 15 historical lint baseline errors remain filtered |
| Final APK SHA-256, signer, Owl dependencies/16KB + ZIP inspection | PASSED; legacy CameraX utility ELF exception documented above |
| Matching post-fix real host-model rerun | PASSED scoped synthetic host assertions; not phone qualification |
| New hosted CI | PENDING; prior 0.6 CI is historical only |
| Physical install/load/self-test/approved practice task | PENDING |
| Real apps, interruption, memory, thermal, battery and latency | PENDING |

Delivered debug APK: **425,626,895 bytes**, SHA-256 `db72df46bc4f583bd9b5b62cd51addc814889e28ade976ba655bec1d4a3c244b`. Record exact checkout, device/build fingerprint, total/available memory, artifact hashes, runtime receipt, commands, raw failures and all test counts. See [physical test matrix](OWL_DEVICE_TEST_MATRIX.md). Install updates only with the matching signing certificate; never uninstall/clear valuable data to bypass a signature mismatch.
