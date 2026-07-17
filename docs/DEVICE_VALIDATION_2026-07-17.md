# Connected-device validation — July 17, 2026

## Scope

Primary device: Xiaomi 14 (`7f8cafef`), Android 15. The installed build was the
debug APK produced from this working tree. This is engineering validation of an
alpha prototype, not production certification.

## Final gates

| Gate | Result |
|---|---|
| All-module Android JVM tests, lint, debug APK and Android-test APK | Pass; 1,072 Gradle tasks |
| Repository and exact-model invariants | Pass; one Gemma 4 E2B brain, exact artifact integrity, 32K context, no legacy runtime |
| Connected-device instrumentation | `OK (55 tests)` in 206.218 seconds |
| No-network device subset | `OK (20 tests)` in 77.202 seconds with Wi-Fi and mobile data disabled |
| Page Agent TypeScript/unit tests | Pass; 4 tests |
| Page Agent Playwright | Pass; 5 scenarios |
| Blind Aid live camera | Preview, object boxes/labels and spoken feedback confirmed with the lens uncovered |

The language-pack repair test intentionally removes and downloads the Malayalam
TTS pack. It therefore requires connectivity and is excluded from the no-network
subset. Once installed, Malayalam STT/TTS and normal agent use remain local.

## Speech and hands-free

- English STT/TTS and all six Indic TTS engines initialize on the phone.
- Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam pass native-script
  TTS-to-STT round trips through the offline Omnilingual recognizer.
- Native wake prefixes, selected-language wake cues, and deterministic core
  command routing are covered for the six Indic languages.
- Microphone entry points fail closed without runtime permission and while the
  master disable is active.
- No Android or cloud speech fallback is used when local model files are absent.

This does not replace a recorded acoustic corpus. Real speakers, accents,
background noise, microphone distance, and a second device remain release gates.

## Canonical tool coverage

Every canonical tool has an executor branch and safety classification. The
coverage level below prevents an intent/branch test from being mistaken for a
completed external action.

| Area | Tools | Validation |
|---|---|---|
| Notes and local data | `create_note`, `search_notes`, `delete_notes`, `delete_all_notes`, `export_data` | Unit/device CRUD and persistence; destructive paths require confirmation |
| Text and speech | `summarize_text`, `speak_response`, `voice_recording` | Device inference/speech initialization, multilingual round trip and disabled-state gates |
| Apps and URLs | `open_chrome`, `open_app`, `open_url`, `open_camera`, `open_dialer`, `system_control` | Canonical parsing, arguments, risk/permission checks and Android intent/controller branches |
| Messaging and calendar | `draft_email`, `send_whatsapp`, `check_calendar`, `open_calendar`, `open_calendar_insert`, `share_text` | Draft/intent/controller branches and confirmation policy; UnoOne does not press another app's final Send or Save button |
| Screen and vision | `read_screen`, `ocr_screen`, `detect_objects`, `deactivate_blind_aid`, `describe_scene` | MediaProjection gate, rendered OCR, live CameraX detection/speech, phone fixture and stale-state shutdown |
| Skills | `create_skill` | Built-in/custom skill routing, step safety, disabled learned suggestions and Skills UI |
| Secure Browser | `secure_browser_task` | Android WebView form/read-page tests plus browser E2E for text, email, numeric, textarea, select, checkbox, radio, date, submit and authorized file input |
| Documents | `prepare_document_fill` | Exact PDF AcroForm and DOCX save-as-copy round trips; originals unchanged |
| Network search | `web_search` | Canonical/safety branch and offline failure behavior; no public-web success claim was made during the no-network run |

## Page Agent safety modes

Standard mode blocks payments and requires manual takeover for credentials, OTP,
CAPTCHA and legal acceptance. The explicit prototype **Off** level removes
per-action browser policy blocks, but transport boundaries remain: HTTP,
executable URLs, embedded credentials, localhost, `.local`, IP literals and
invalid bridge sessions are still rejected.

## Master disable

Device tests cover durable disabled preference, microphone/TTS fail-closed
entry points, cancellation without replay, and rapid enable/disable races. While
disabled, typed/voice work, model recovery, browser work and background agent
operations remain gated until an explicit enable action.

## Honest remaining release work

- Repeat the suite on the minimum API-28 device and at least one non-Xiaomi OEM.
- Run controlled speech and vision datasets with precision/recall, false-wake,
  latency, thermal and battery measurements.
- Validate real cross-app Accessibility gestures with the user-authorized service
  enabled; external WhatsApp/email/calendar final-send actions remain deliberately
  outside automated testing.
- Test a signed release build, upgrade path, process-death/reboot matrix, long
  soak, low-storage and memory-pressure conditions.
- Run public-site Page Agent compatibility against an approved site list when
  the device is intentionally online.
