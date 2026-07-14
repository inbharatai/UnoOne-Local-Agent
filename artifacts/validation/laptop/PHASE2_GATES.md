# Phase 2 — Local Automated Gate Results

**Validation branch:** `validation/xiaomi14-end-to-end`
**Starting commit:** `90e879e42333bbdc71be0cff5bb39287995b59eb`
**Date:** 2026-07-13
**Tester:** Claude (principal engineer)
**Laptop:** Windows 11 26200, JDK 17.0.19 (Temurin), Node 24.13.0, npm 11.6.2, Python 3.12.10

All gates run locally on the laptop. Exit 0 + stage markers confirmed for every
component. Raw logs preserved under `artifacts/validation/laptop/logs/`.

## 1. Repository invariants — PASS

`python scripts/ci/check_repo_invariants.py` → "UnoOne V2 invariants passed" (exit 0).
Architecture guards on clean main:
- `grep -R "GEMMA_3N" android-app/UnoOneAgent` → no matches (V1 dual-brain removed).
- `grep -R "gemma-local" android-app/UnoOneAgent` → doc-only match `README.md:70` (truthful negative claim).
- `BrainModel.kt:10` → `enum class BrainModelId { GEMMA_4_E2B }`; `:13` → `enum class ModelFamily { GEMMA_4 }`.

## 2. PageAgent runtime (`web-runtime/page-agent-unoone`) — PASS

Sequence: install → typecheck → test → build → `npx playwright install chromium` → test:e2e → bundle:android.
All 8 stage markers present incl. `### ALL-PAGE-AGENT-PASS`; no failure indicators.
- vitest unit tests: pass.
- Playwright e2e (form-fill + payment-block): pass.
- Bundled Android asset: `android-app/UnoOneAgent/securebrowser/src/main/assets/page-agent/unoone-page-agent.js`
  - **Size: 193,488 bytes** (non-empty, real minified `UnoOnePageAgentRuntime` IIFE — not a fallback).
  - **SHA-256: `d434912a15ebaac5434cbaf847291d0cdec1cb8054594cd537b4245c02ade71e`**

## 3. Distribution API (`distribution/api`) — PASS

install → typecheck → test → `npx wrangler deploy --dry-run --outdir dist-worker`.
All stage markers incl. `### ALL-DISTRIBUTION-PASS`; no failure indicators.

## 4. Installer PWA (`installer-pwa`) — PASS

install → typecheck → test → build.
All stage markers incl. `### ALL-INSTALLER-PASS`; no failure indicators.

## 5. Android (`android-app/UnoOneAgent`) — PASS (with honest lint caveat)

`./gradlew clean → :app:lintDebug → testDebugUnitTest → assembleDebug`.
All stage markers incl. `### ALL-ANDROID-PASS`; `BUILD SUCCESSFUL in 2m 3s`.

### 5a. Unit tests (JVM) — PASS
- **294 tests, 0 failures, 0 errors, 0 skipped** across 32 test classes
  (aggregate from `**/test-results/testDebugUnitTest/*.xml`).
- Notable coverage: SafetyGuard (30), InputSanitizer (22), RuleBasedParser (26),
  CommandParser (19), ResultTest (20), SafetyPipeline (14), ModelInstaller (14),
  ReActLoopController (13), OutcomeMemoryPolicy (10), CanonicalToolRegistry (10),
  SceneDescriptionBuilder (9), BrowserSafetyPolicy (5), SecureBrowserNativeHandler (4),
  BrowserDomainPolicy (4), VoiceLanguageMapping (8), LanguagePackManifest (2), etc.

### 5b. Lint — PASS via BASELINE (caveat)
`./gradlew :app:lintDebug` → "Lint found no new issues (and 39 errors filtered by baseline lint-baseline.xml)".
- `app/build.gradle.kts:55` declares `baseline = file("lint-baseline.xml")`.
- `app/lint-baseline.xml` (17,987 B, dated 2026-06-24) contains ~39–40 `<issue>` elements
  suppressed at baseline. **This is not a clean lint** — 39 pre-existing errors are acknowledged
  and suppressed, not fixed.
- Sample suppressed issues:
  - `BatteryLife` ×2 — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` flagged under Play Store Content Policy
    (`MainActivity.kt:173`, `PermissionManager.kt:88`). Justifiability for a background
    voice/assistant app must be assessed in the security/privacy review (Phase 12).
  - `GradleDependency` — newer androidx versions available (e.g. core-ktx 1.15.0 → 1.19.0,
    appcompat 1.7.0 → 1.7.1). Staleness, not correctness.
- Full reference baseline copied to `artifacts/validation/laptop/logs/lint-baseline.xml.reference`.
- **Carried-forward finding for Phase 12:** evaluate whether `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  is justified and document it; otherwise it is a Play Store policy risk.

### 5c. Debug APK — built
- **Path:** `android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk`
- **Size:** 372,355,017 bytes (~355 MiB; large due to native libs: Sherpa-ONNX STT/TTS,
  LiteRT-LM, CameraX/ML across ABIs)
- **SHA-256:** `4e72d981747d95dacde4fac019229fd0c3e845c6fb8a9efb267575598c70e174`
- (Package id and launcher activity to be resolved from Gradle/Manifest at Phase 4.)

## 6. Connected-debug Android tests — PENDING DEVICE
`./gradlew connectedDebugAndroidTest` is deferred until the Xiaomi 14 is connected via ADB.

## Phase 2 verdict
All laptop-only automated gates are **green**. The single honest caveat is that Android
lint passes via a baseline suppressing ~39 pre-existing issues (notably
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), which is carried forward to the Phase 12
security/privacy review. Physical-device gates (Phases 3–11) remain pending on the
Xiaomi 14.