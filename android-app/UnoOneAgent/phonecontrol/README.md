# Phone control

`:phonecontrol` contains UnoOne's device-facing, fully local camera, screen-reading, document, calendar, package, and intent controls. The app and `:agentrouter` decide when a control may run; this module performs the Android operation and returns a typed `Result`.

## Blind Aid

Blind Aid combines a CameraX preview owned by the app UI with `BlindAidManager`, an `ImageAnalysis.Analyzer` backed by ML Kit object detection. Detector creation is lazy on the first analyzed frame so opening the camera does not block composition. A qualified custom model at `models/vision/blind-aid/custom_yolov8.tflite` is used when present; otherwise the bundled offline ML Kit detector is used.

The analyzer processes every sixth frame, closes every `ImageProxy`, emits normalized bounding boxes, and provides throttled spoken scene summaries plus proximity tones and haptics. Activation first asks the application lifecycle to release the resident Gemma engine, leaving memory for CameraX and ML Kit; deactivation permits a single guarded brain reload. Camera binding is asynchronous and must remain off the main thread except for the lifecycle-bound `bindToLifecycle` call required by CameraX.

## Read Screen

The primary Read Screen flow uses Android `MediaProjection`, not an Accessibility-settings redirect. `ScreenshotPermissionActivity` obtains one explicit system consent, `ScreenshotCapture` captures a bitmap, and `OcrControl` runs the bundled ML Kit Latin text recognizer. Capture and OCR run off the main thread; the result is spoken by the shared voice module. The recognizer is lazy and must be released with `OcrControl.release()` when its owner is cleared.

The Accessibility-based `read_screen` tool remains available for cross-app UI reading when UnoOne's Accessibility service is already enabled, but the home-screen Read Screen button always uses the in-app MediaProjection path.

## Document loading

`document/DocumentLoader` receives a Storage Access Framework `content://` URI and extracts text without uploading the file:

- PDF: Android `PdfRenderer`, up to eight pages, rendered to bounded bitmaps and OCR'd with ML Kit; each bitmap is recycled immediately.
- Image: bounded platform decode followed by ML Kit OCR.
- `.xlsx`: the pure-JVM SAX/ZIP extractor in `:core` (no desktop Apache POI dependency).
- HTML: UTF-8 read followed by the `:core` HTML text extractor.
- CSV and text: UTF-8 plain-text extraction.
- Legacy binary `.xls`: explicitly unsupported; UnoOne reports the limitation instead of returning fabricated content.

Extracted output is capped by `PlainTextExtractor.DEFAULT_MAX_CHARS` to fit the on-device brain context. `ExtractedDoc.truncated` is surfaced in the UI and spoken confirmation. All renderer, stream, and OCR work runs on `Dispatchers.IO`.

## Verification

Run JVM and device coverage from the Android root:

```powershell
.\gradlew.bat :phonecontrol:testDebugUnitTest :app:testDebugUnitTest
adb shell am instrument -w -e class com.unoone.agent.phonecontrol.CameraAccessHeadlessTest,com.unoone.agent.phonecontrol.OcrControlHeadlessTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
```

Physical-device checks still matter for camera preview rendering, spoken output, MediaProjection consent, OCR quality, memory pressure, and TalkBack announcements.
