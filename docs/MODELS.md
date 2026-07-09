# UnoOne Models — Installation, Integrity, Profiles

How on-device models are installed, verified, and organized. Source manifest:
`android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json`.

## 1. Integrity model

Every model file in the manifest carries `url`, and ideally `sha256` + `sizeBytes`. The installer
(`ModelInstaller`) streams the download, integrity-checks it, and `ModelManager.modelHealth`
reports three states:

| State | Meaning |
|---|---|
| **Verified** | File present, size matches, SHA-256 matches |
| **Present — not hash-verified (manual import)** | File present & non-empty, but manifest has no sha256/size to byte-check |
| **Needs repair** | Missing, wrong size, or hash mismatch |

### Gemma (LLM) — honest status

UnoOne now has **two selectable brain profiles** (see [Local Brain](../README.md#local-brain-gemma-via-litert-lm)):

- **Gemma 3n E4B** — manifest id `gemma-3n-e4b`, folder `gemma-local/` (kept for migration continuity; a legacy `gemma-local` selection resolves to this profile). This is the **default, device-verified fallback**.
- **Gemma 4 E2B** — manifest id `gemma-4-e2b`, folder `gemma-4-e2b/`, 128K context, ~2.58 GB. **Experimental opt-in, NOT device-verified.** It loads through the same safe code path as Gemma 3n, but no physical device has confirmed it loads + performs a tool call. Do not treat it as working until [`DEVICE_VERIFICATION.md`](../DEVICE_VERIFICATION.md) step 5b is green. The default stays Gemma 3n E4B.

Both ship **URL-only** (`sha256=""`, `sizeBytes=0`) because the exact `.litertlm` you push must be
hash-verified against **your** shipped artifact (no fabrication). A manually imported Gemma therefore
shows **"Present — not hash-verified (manual import)"**, not "Verified."

**To make it Verified:** compute the exact SHA-256 and byte size of the `.litertlm` you will ship
and add them to the manifest entry. Do not call the LLM production-ready until that is done and
`modelHealth` reports Verified on a real device.

### Sherpa voice models — verified

English STT (`sherpa-asr-en`), the shared Indic whisper STT (`sherpa-asr-whisper`), English TTS
(`sherpa-tts-en`), per-language Indic MMS TTS (`sherpa-tts-{hin,ben,tam,tel,kan,mal}`), and the
wake-word (`vad`) all carry stream-computed sha256 + sizeBytes and are integrity-checked on
install. `punctuation` carries URL only.

## 2. Current models

| Model | Type | Backend | Size | Hash | Languages |
|---|---|---|---|---|---|
| `gemma-3n-e4b.litertlm` | llm | any (GPU→CPU) | ~2–5 GB | **none (manual import)** | planning (multilingual) — **default brain** |
| `gemma-4-e2b-it.litertlm` | llm | any (GPU→CPU) | ~2.58 GB | **none (manual import)** | planning (128K ctx) — **Experimental, not device-verified** |
| `sherpa-asr-en` | asr | cpu | ~70 MB | ✅ | English |
| `sherpa-asr-whisper` | asr | cpu | ~111 MB (.tar.bz2) | ✅ | hi/bn/ta/te/kn/ml (shared, runtime language select) |
| `sherpa-tts-en` | tts | cpu | ~110 MB | ✅ | English (Coqui VITS + espeak-ng-data) |
| `sherpa-tts-<lang>` | tts | cpu | ~109 MB each | ✅ | hi/bn/ta/te/kn/ml (MMS VITS) |
| `vad` | vad | cpu | ~70 MB | ✅ | English wake word |
| `punctuation` | punctuation | cpu | — | none (URL only) | — |

Wake word is English-only (no public Indic KWS transducer exists). The *command* may be Indic; only
the wake phrase is English.

## 3. Installation paths

- **In-app:** Model Status screen → Install (streaming progress + integrity check) or Uninstall.
- **Manual (ADB):** `scripts/adb-push-models/push-models.{bat,sh}` push the per-language folders.
- **Gemma (default 3n E4B):** push the `.litertlm` into `models/gemma-local/` (manual import; no hash yet). Manifest id `gemma-3n-e4b`.
- **Gemma (Experimental 4 E2B):** push `gemma-4-e2b-it.litertlm` into `models/gemma-4-e2b/`, then select the profile in Settings → Model Status → Brain Model and run the self-test. Manifest id `gemma-4-e2b` — **not device-verified**.

## 4. Download source abstraction (spec)

Roadmap: classify each manifest source as `bundled | public_url | authenticated_hf | manual_import`.
Hugging Face URLs can be gated/change; for Gemma, prefer `manual_import` (copy file → verify SHA →
load) as the primary path, with `public_url` as a convenience.

## 5. Model profiles (spec)

Don't force users to download everything. Roadmap installer profiles:

| Profile | Contents |
|---|---|
| Tiny | Rules engine only + English STT/TTS |
| Voice | English + one Indic language (STT + TTS + wake word) |
| Agent | Voice profile + Gemma 3n E4B |
| Blind Aid | Camera + object detection only |
| Full | Everything |

## 6. Object detection / YOLO path (spec — item 18)

A custom YOLOv8 model is currently documented under `gemma-local/custom_yolov8.tflite`, which is
semantically wrong (YOLO is not Gemma). Roadmap: move it to `models/object-detection/yolov8n-int8.tflite`
as a separate optional manifest entry of type `object-detection`, independent of `gemma-local`.

## 7. Storage

All models live in app-private storage (`getExternalFilesDir("models")`), so no
`MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` (on API 29+) is needed. Storage usage is
shown on the Model Status screen.