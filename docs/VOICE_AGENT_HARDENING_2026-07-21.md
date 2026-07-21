# Voice Agent hardening — July 21, 2026

This record describes the current revision honestly. It separates automated evidence from physical-device observations and does not treat parser coverage as an acoustic-accuracy benchmark.

## Root causes fixed

- Native keyword spotting and offline-STT fallback could both accept the same speech burst. A process-local monotonic cooldown now admits one activation and stores no transcript.
- The low-latency keyword list had `Uno One` but not the shorter `Uno`. A stricter short-keyword entry now covers it while the transcript matcher continues to reject unrelated phrases such as “you know what happened.”
- Explicit language changes returned immediately, discarding a second action in commands such as “Speak in Hindi and start blind mode.” Language extraction now preserves and routes the remaining command after both offline engines switch successfully.
- Blind Mode lacked several English, Hinglish, and native-Hindi aliases. The deterministic parser now covers the documented activation variants before Gemma inference.
- Hindi Blind Aid activation/deactivation wording was inconsistent and gendered voice wording could disagree with the installed voice. Status narration is now native, concise, and gender-neutral.
- Voice diagnostics existed only in runtime memory. Debug builds now expose state, readiness, wake match, transcript normalization, routing, execution, verification, and recovery information on Voice Test. Release builds do not expose it and no private transcript is persisted.
- Setup requested permissions unrelated to initial voice activation. Startup now asks only for Microphone and, where required, Notifications; Camera and calendar-read remain contextual. Contacts and calendar-write are intentionally absent.
- Current Google Calendar advertises event insertion through the standard event MIME type. UnoOne now sends both the event content URI and `vnd.android.cursor.dir/event`, and declares the corresponding package-visibility query.

## Automated evidence

- Android lint: no current errors or warnings beyond the checked-in baseline.
- JVM tests: 549 passed, 0 failures, 0 errors.
- Debug APK, unsigned release APK, and Android-test source compilation: passed.
- Repository invariants: passed.
- Page Agent: TypeScript type-check passed; 8 unit tests passed; 5 Playwright scenarios passed; Android runtime bundle rebuilt.
- Browser scenarios cover ordinary fields, email/number/textarea, select/checkbox/radio/date, explicit submit, native file selection, and payment blocking.

## Xiaomi 14 / Android 15 observations

The current debug APK installed successfully with `adb push` plus `pm install -r`.

- “Speak in Hindi and start blind mode” changed both offline engines to Hindi and routed to `detect_objects` without Gemma planning.
- CameraX loaded the bundled EfficientDet-Lite2 model and produced live detections including person, cell phone, bottle, book, remote, spoon, toothbrush, and tie in the observed scene.
- Offline Hindi TTS generated and played activation/object speech. Logs prove synthesis/playback, not human-rated pronunciation quality.
- “Stop blind mode” closed the camera; no later Blind Aid detection or narration callback appeared in the observation window.
- Read Screen routed deterministically, captured the foreground Accessibility content, and generated offline speech.
- WhatsApp Business, Gmail, and Google Calendar opened in the foreground. WhatsApp and Gmail review drafts opened. The Calendar review form showed the exact `UnoOne QA draft` title, July 22, 2026 date, and 5–6 PM time. UnoOne pressed neither Send nor Save.
- Secure Browser opened its Compose/WebView surface and loaded the local Page Agent planner on CPU. Full public-site reliability remains site-specific; automated local browser fixtures are the repeatable form-filling evidence.
- Disable UnoOne survived force-stop and restart, blocked a Calendar command, left no agent service active, and allowed a new command only after explicit re-enable.
- No UnoOne fatal exception was observed in these checks.

HyperOS reset UnoOne Accessibility after the final APK update. This is an OS security behavior, not something the app may bypass. The user explicitly re-enabled it before the final Calendar foreground/read-back check.

## Deliberate boundaries

- English and Hindi are the only exposed languages in this hardening cycle.
- Spoken contact-name resolution and ambiguity selection are not implemented; UnoOne does not request Contacts permission.
- WhatsApp, email, and calendar creation are reviewable external-app drafts. UnoOne does not press Send or Save.
- Calendar conflict detection, provider insertion, cancellation, and rescheduling are not implemented. Calendar read summary and a reviewable `ACTION_INSERT` draft are the current contract.
- Voice barge-in while UnoOne itself is speaking is not supported because the microphone is intentionally gated during TTS to prevent self-transcription. The visible Stop control remains available.
- Screen-off/background wake reliability is subject to Android foreground-service, microphone, battery, and OEM autostart policy.
- Wake phrase matching and deterministic routing are tested, but a controlled corpus for accents, noise, distance, false accepts, false rejects, and end-to-end latency is still required.
- Blind Aid is assistive guidance based on COCO object classes, not certified navigation or fine-grained brand/product recognition.
- Page Agent fills supported HTML controls. PDF AcroForms and DOCX templates use the separate offline Document Agent; arbitrary scanned/flat documents remain unsupported.
