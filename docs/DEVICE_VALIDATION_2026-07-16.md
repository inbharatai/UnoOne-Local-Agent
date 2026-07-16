# Connected-device validation — 2026-07-16

Device: Xiaomi 14 (`7f8cafef`), Android 15, 1200 × 2670.  Build: debug APK from
`android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk`.

## Automated gates

- Android lint, JVM unit tests, debug APK and Android-test APK builds passed.
- Connected-device instrumentation passed `OK (48 tests)` and includes offline speech initialization,
  Gemma planning, rendered-image OCR, camera policies, skills safety, browser safety modes,
  PageAgent asset authenticity, notes, memory, and verified PDF/DOCX round trips.
- PageAgent TypeScript typecheck and Vitest passed; Playwright passed text/email/number/textarea,
  blocked payment fields, a real file chooser, and a complex select/checkbox/radio/date/submit form.

## Manual connected-phone matrix

| Area | Result | Evidence / boundary |
| --- | --- | --- |
| Landing panel | Pass | Agent activity is always visible, collapsible, and shows the current/latest step. Skills count links directly to Skills. |
| Skills | Pass | Four enabled built-ins were seeded: Read Screen Aloud, Start Blind Aid Guidance, Fill an Offline PDF Form, and Fill an Offline DOCX Template. Steps and triggers are visible. Learned routines remain disabled until review. |
| Hands-free | Pass after device-found fixes | Spoken cue completes before AudioRecord starts. Sherpa decoding and synthesis run off the UI thread. Start/stop released the recorder and produced no watchdog/frame-skip trace. “Stop listening” now ends the session even while an agent task is processing. |
| Blind Aid camera and detection | Pass for lifecycle/stale-cache fix; broader accuracy qualification pending | Replaced the generic fallback with official EfficientDet-Lite2 and full-frame + centered detail inference. On-device regression detects a representative mobile phone. Live CameraX stop released the detector and no object/TTS callbacks continued after close; unchanged warnings are cooldown-limited. A controlled multi-light person/car/product corpus is still required before a production accuracy claim. |
| Read Screen / OCR | Pass after device-found fix | Android 15 MediaProjection foreground service started with type `mediaProjection`; ML Kit extracted visible Settings text and offline TTS spoke it. The display is reused for repeated reads. |
| Airplane mode | Pass | App reported OFFLINE; landing panel, local Skills, hands-free controls, and Secure Browser/PageAgent runtime remained available. Airplane mode was restored to off after the test. |
| Calendar | Pass | Voice/text command `open calendar` launched Google Calendar directly. The insert-event flow remains a review draft; Save was not pressed. |
| WhatsApp | Pass | `open whatsapp` launched the installed WhatsApp Business package through the new consumer/business fallback. Draft-message code does not press Send. |
| Email | Pass (automated boundary) | Mailto draft preserves recipient, subject and body and requires the external mail UI; Send was not pressed. |
| Offline Document Agent | Pass (engine and device fixtures); picker UI remains locked-device gated | Xiaomi ART tests created real AcroForm PDF and DOCX fixtures, discovered their fields, filled exact text/checkbox/template values, reopened and verified both outputs, and proved the originals were byte-identical. The landing-screen picker/card could not be visually rerun because the device returned to the pattern-locked AOD after instrumentation. |
| PageAgent | Pass with native-picker boundary noted | Packaged runtime initialized in a physical Xiaomi WebView, filled an indexed local form through the authenticated native bridge, returned a correlated task result, and extracted title/body for read-aloud. The former white start page is now an offline Page Agent home with URL/form/voice steps and readable examples. Playwright covers complex controls and a real chooser. The phone was pattern-locked before a foreground Android picker rerun, so that visible picker handoff remains pending. |
| Prototype safety Off | Pass | Settings exposed `Off — prototype (agent + browser)` and it was selected on-device. Device ART tests prove a non-approved public HTTPS target is admitted while HTTP, credentials-in-URL, localhost and IP targets remain blocked. Android runtime permissions and OS consent dialogs still apply. |

## Intentionally not crossed

No WhatsApp message, email, calendar event, payment, credential, OTP, CAPTCHA, or external form was
finally sent/saved/submitted. Those are irreversible external boundaries; the draft/review handoff and
safety behavior were tested without affecting another person or account.

ADB can validate recorder ownership, timings and state transitions, but it cannot provide a controlled
accent/noise corpus through the physical microphone. Recognition quality across supported languages still
needs a repeatable recorded-speech benchmark on target devices before a production claim of “bulletproof.”
The device became pattern-locked late in this run, so final foreground UI checks that require an unlocked
Activity (notably the Android document picker and a visible arbitrary-site navigation) remain explicitly
unverified; headless physical-WebView, policy, read-page and form-fill tests continued to run on the device.
