# Phone control

`:phonecontrol` contains UnoOne's device-facing, fully local camera, screen-reading, document, calendar, package, and intent controls. The app and `:agentrouter` decide when a control may run; this module performs the Android operation and returns a typed `Result`.

## Blind Aid

Blind Aid combines a CameraX preview owned by the app UI with `BlindAidManager`, an `ImageAnalysis.Analyzer` backed by MediaPipe Tasks. Detector creation is lazy on the first analyzed frame so opening the camera does not block composition. A qualified custom model at `models/vision/blind-aid/custom_yolov8.tflite` is used when present; otherwise the bundled offline EfficientDet-Lite2 model supplies COCO object names such as person, car, cell phone, bicycle and chair. Lite2's 448x448 input improves small-object accuracy over Lite0. The older generic ML Kit fallback was deliberately removed because labels such as `home good` were not useful for navigation.

The analyzer processes every third frame, closes every `ImageProxy`, emits normalized bounding boxes, and provides confidence-filtered, multi-frame-confirmed spoken scene summaries plus proximity tones and haptics. Activation first asks the application lifecycle to release the resident Gemma engine, leaving memory for CameraX and MediaPipe; deactivation clears detector and narration state before permitting one guarded brain reload. Camera binding is asynchronous and must remain off the main thread except for the lifecycle-bound `bindToLifecycle` call required by CameraX.

## Read Screen

The primary Read Screen flow uses Android `MediaProjection`, not an Accessibility-settings redirect. `ScreenshotPermissionActivity` obtains one explicit system consent, `ScreenshotCapture` captures a bitmap, and `OcrControl` runs the bundled ML Kit Latin text recognizer. Capture and OCR run off the main thread; the result is spoken by the shared voice module. The recognizer is lazy and must be released with `OcrControl.release()` when its owner is cleared.

The Accessibility-based `read_screen` tool remains available for cross-app UI reading when UnoOne's Accessibility service is already enabled, but the home-screen Read Screen button always uses the in-app MediaProjection path.

## Document loading

`document/DocumentLoader` receives a Storage Access Framework `content://` URI and extracts text without uploading the file:

- PDF: Android `PdfRenderer`, up to eight pages, rendered to bounded bitmaps and OCR'd with ML Kit; each bitmap is recycled immediately.
- Image: bounded platform decode followed by ML Kit OCR.
- `.xlsx`: the pure-JVM SAX/ZIP extractor in `:core` (no desktop Apache POI dependency).
- `.docx`: the bounded, XXE-hardened `DocxTemplateProcessor` extracts paragraph text from the document, headers and footers.
- HTML: UTF-8 read followed by the `:core` HTML text extractor.
- CSV and text: UTF-8 plain-text extraction.
- Legacy binary `.xls`: explicitly unsupported; UnoOne reports the limitation instead of returning fabricated content.

Extracted output is capped by `PlainTextExtractor.DEFAULT_MAX_CHARS` to fit the on-device brain context. `ExtractedDoc.truncated` is surfaced in the UI and spoken confirmation. All renderer, stream, and OCR work runs on `Dispatchers.IO`.

## Offline document filling

`document/DocumentFillEngine` provides a separate save-as-copy workflow for editable documents:

- PDF AcroForms are inspected and filled with the Android PDFBox fork. Text, checkbox, radio and choice fields are supported; encrypted, flat/scanned and signature-only PDFs fail explicitly.
- DOCX templates use Word content-control tags/titles or `{{name}}`, `${name}` and `<<name>>` placeholders, including placeholders split across runs.
- Unknown fields are rejected, the source URI cannot be reused as the output URI, and bytes are written to the destination only after the completed document has been reopened and its values verified.

The landing-screen review card owns user-entered values. They are not stored by this module or learned into a skill. `prepare_document_fill` only opens the appropriate picker through the agent's canonical tool and safety pipeline.

## Verification

Run JVM and device coverage from the Android root:

```powershell
.\gradlew.bat :phonecontrol:testDebugUnitTest :core:testDebugUnitTest :app:testDebugUnitTest
adb shell am instrument -w -e class com.unoone.agent.phonecontrol.DocumentFillEngineDeviceTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Physical-device checks still matter for camera preview rendering, spoken output, MediaProjection consent, OCR quality, memory pressure, and TalkBack announcements.
