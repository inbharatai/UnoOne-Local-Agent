# UnoOne Upgrade Plan: Gemma 4 E2B + LiteRT-LM Brain

> **⚠️ This is a historical migration plan, not the current runtime.**
> The current production brain is **Gemma 3n E4B via LiteRT-LM** (`gemma-3n-e4b.litertlm`).
> This document captures the original plan to migrate from mock ONNX inference to a real
> on-device LLM; the "Gemma 4 E2B" target below was the *planned* next step and has not been
> shipped. Keep it as a point-in-time reference; for current status see [STATUS.md](STATUS.md).

**Date:** 2026-06-15  
**Scope:** Replace the mock ONNX inference path in `:localbrain` with a real on-device LLM brain using **Gemma 4 E2B** and **LiteRT-LM**, add manual tool-calling integration with the existing `SafetyGuard`/`AgentRouter`, and standardize the OCR stack. All other modules (voice, accessibility, phone control, safety, storage, skills) remain unchanged structurally.

---

## 1. Goals & Non-Goals

### Goals
- Replace `LocalBrain.runInference()` mock output with real LiteRT-LM inference.
- Register UnoOne tools as LiteRT-LM `ToolSet`/`OpenApiTool` declarations so Gemma 4 can plan multi-step actions.
- Force **manual tool calling** (`automaticToolCalling = false`) so every generated tool call must pass `SafetyGuard` and user confirmation before execution.
- Keep `RuleBasedParser` as the fast offline fallback for simple commands.
- Standardize ML Kit OCR on the **bundled local** dependency inside `:phonecontrol` and add a screenshot-OCR fallback path via `MediaProjection`.
- Update `ModelManager` to detect/verify `.litertlm` model files.
- Update documentation (`STATUS.md`, `README.md`) to reflect the new brain stack.

### Non-Goals
- No change to the 13-module structure.
- No change to `SafetyGuard` risk tiers (DIRECT/CONFIRM/STRONG_CONFIRM/BLOCK).
- No change to `RuleBasedParser` semantics.
- No cloud inference; the system stays fully offline.
- No blind-aid camera pipeline changes beyond OCR standardization.

---

## 2. Architecture After the Change

```
Voice / Text / Camera / Screen
           ↓
   Input Normalizer (existing InputSanitizer)
           ↓
   CommandParser
     ├─ RuleBasedParser (fast path)
     └─ GemmaPlanner (LiteRT-LM, slow path)
           ↓
   Context Builder
     ├─ current app / activity
     ├─ visible accessibility text
     ├─ OCR text (MediaProjection fallback)
     ├─ user memory / skill memory
     └─ RAG snippets (offline/local only for now)
           ↓
   Gemma 4 E2B via LiteRT-LM
     ├─ EngineConfig(modelPath=.litertlm, backend=GPU/NPU)
     ├─ Engine.initialize()
     └─ ConversationConfig(tools=UnoOneToolSet, automaticToolCalling=false)
           ↓
   ToolCall JSON (parsed from responseMessage.toolCalls)
           ↓
   SafetyGuard + SafetyPipeline
     ├─ DIRECT → execute
     ├─ CONFIRM / STRONG_CONFIRM → show dialog
     └─ BLOCK → reject
           ↓
   AgentRouter / ActionExecutor
     ├─ AccessibilityControl
     ├─ PhoneControl
     ├─ OcrControl
     ├─ CalendarControl
     ├─ Notes / Skills / Memory
     └─ BlindAidManager
           ↓
   Verification + TTS response
```

---

## 3. Detailed Implementation Steps

### Phase A — Dependencies & Module Setup

#### A1. Update `:localbrain/build.gradle.kts`
- **Remove** ONNX Runtime dependency:
  ```kotlin
  // implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
  ```
- **Add** LiteRT-LM Android/Kotlin artifact:
  ```kotlin
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
  ```
  *(Pinned to `0.13.1` — the latest version published on Google Maven as of 2026-06; verified via `dl.google.com` maven-metadata. No `1.0.x` release exists. `latest.release` is avoided for reproducible builds.)*
- Keep `kotlinx-coroutines-android` and `kotlinx-serialization-json`.

#### A2. Standardize OCR dependency
- In `:app/build.gradle.kts`, **remove**:
  ```kotlin
  implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
  ```
- Keep the bundled ML Kit dependency in `:phonecontrol/build.gradle.kts`:
  ```kotlin
  implementation("com.google.mlkit:text-recognition:16.0.0")
  ```
- Add `:phonecontrol` dependency to `:app` if not already present (it is already present).
- Update `OcrControl.kt` to use `com.google.mlkit.vision.text.TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` — already correct.

#### A3. Verify repositories
- `settings.gradle.kts` already includes `google()` and `mavenCentral()`. LiteRT-LM is hosted on Google Maven, so no new repository is needed.

---

### Phase B — New Core Classes in `:localbrain`

#### B1. `GemmaPlanner.kt`
Responsibility: load the `.litertlm` model, run inference, and return a parsed `ToolCall` (or `null`).

Key design:
- `load(modelPath: String): Result<Unit>` — builds `EngineConfig(modelPath, backend = Backend.GPU() fallback to Backend.CPU())`, initializes the engine, creates a reusable `Conversation` with `automaticToolCalling = false`.
- `plan(command: String, context: ContextSnapshot): Result<ToolCall>` — sends a system prompt + user command, receives `responseMessage`, extracts `toolCalls`, converts the first tool call into `ToolCall(toolName, JsonObject(args))`.
- `isLoaded(): Boolean`.
- `close()` — releases engine/session.

LiteRT-LM imports:
```kotlin
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
```

#### B2. `UnoOneToolSet.kt`
Defines all UnoOne capabilities as LiteRT-LM `ToolSet` functions with `@Tool` descriptions and `@ToolParam` parameters. Examples:

```kotlin
class UnoOneToolSet {
    @Tool(description = "Create a local note")
    fun create_note(
        @ToolParam(description = "Short title of the note") title: String,
        @ToolParam(description = "Full note content") content: String
    ): String = "Note saved: $title"

    @Tool(description = "Open an app by package name")
    fun open_app(
        @ToolParam(description = "Android package name, e.g. com.whatsapp") package_name: String
    ): String = "Opening $package_name"

    @Tool(description = "Perform a system gesture or navigation action")
    fun system_control(
        @ToolParam(description = "Action: click, type, fill, scroll_up, scroll_down, swipe, go_back, go_home, find_and_click, read_screen, long_press, open_notifications, open_recents") action: String,
        @ToolParam(description = "Target text or direction", optional = true) target: String? = null,
        @ToolParam(description = "Value for fill/type actions", optional = true) value: String? = null
    ): String = "Executing $action"

    // ... all remaining tools
}
```

Rationale: LiteRT-LM uses the Kotlin function signatures to generate the tool schema passed to Gemma. This keeps tool definitions in one place and self-documenting.

#### B3. `ContextSnapshot.kt` (data class)
Holds everything Gemma should know before planning:
```kotlin
data class ContextSnapshot(
    val currentPackage: String?,
    val currentActivity: String?,
    val visibleText: String,
    val ocrText: String?,
    val recentNotes: List<String>,
    val userMemory: String,
    val activeSkills: List<String>
)
```

#### B4. `PromptBuilder.kt` rewrite
- Convert `PromptBuilder` from a simple string builder to a helper that assembles a `Message` list for LiteRT-LM.
- Keep the offline-first persona and available-tools list.
- Strip Gemma control tokens from any injected context (reuse `RAGManager.sanitizeWebContext()` logic or move sanitization into a shared utility).

#### B5. Keep/deprecate `LocalBrain.kt`
- Option chosen: **rewrite** `LocalBrain` to wrap `GemmaPlanner`.
- `loadModel(path)` → delegates to `GemmaPlanner.load(path)`.
- `runInference(prompt)` → builds a `ContextSnapshot`, calls `GemmaPlanner.plan(...)`, returns `Result<ToolCall>`.
- `parseToolCall(output: String)` is no longer needed because LiteRT-LM returns structured tool calls, but we keep it as a private fallback for string output parsing (defense in depth).

---

### Phase C — Wiring Into `:app`

#### C1. Update `CommandParser.kt`
Current logic:
```kotlin
override fun parse(text: String): ToolCall? {
    val ruleResult = RuleBasedParser.parse(text)
    if (ruleResult != null) return ruleResult
    if (localBrain.isModelLoaded()) { ... }
    return null
}
```

New logic:
```kotlin
override fun parse(text: String): ToolCall? {
    val ruleResult = RuleBasedParser.parse(text)
    if (ruleResult != null) return ruleResult        // fast deterministic path
    if (localBrain.isModelLoaded()) {                // Gemma slow path
        val snapshot = buildContextSnapshot()
        val inferenceResult = localBrain.runInference(text, snapshot)
        if (inferenceResult is Result.Success) return inferenceResult.data
    }
    return null
}
```

- `CommandParser` will need injected access to `AccessibilityControl`, `OcrControl`, `MemoryModule`, and `NoteDao` to build the snapshot. We add these as constructor dependencies with safe fallbacks (nullable) so unit tests still pass without Android framework.
- **Important:** The `parse()` signature stays synchronous (`fun parse`), but `GemmaPlanner.plan()` is inherently async (model I/O can take seconds). The chosen approach is to keep `RuleBasedParser` synchronous and make the Gemma path explicit through a new `parseAsync()` coroutine that the orchestrator calls first when a model is loaded.

#### C2. Add `CommandParser.parseAsync()` + update `AgentOrchestrator`
- Add `suspend fun parseAsync(text: String): ToolCall?` to `ICommandParser` and `CommandParser`.
- Update `AgentOrchestrator.processCommand()`:
  1. Try `RuleBasedParser` synchronously first (fast path).
  2. If no rule match and model is loaded, call `commandParser.parseAsync(sanitizedText)` on `Dispatchers.Default`.
  3. If still null, show the existing "Intent not clear" failure.
- Timeline steps remain: `UNDERSTANDING → TOOL_SELECTED → SAFETY_CHECK → EXECUTING → VERIFYING → SPEAKING/DONE`.

#### C3. Inject context sources into `CommandParser`
Modify `AgentOrchestrator` construction of `CommandParser`:
```kotlin
private val commandParser = CommandParser(
    accessibilityControl = accessibilityControl,
    ocrControl = ocrControl,
    memoryModule = memoryModule,          // already available via lazy init
    noteDao = noteDao
)
```

For tests, provide a no-arg constructor that keeps `RuleBasedParser` working and skips the Gemma path.

#### C4. Update `AgentRouter.kt` (minor)
- Register the same tool names that `UnoOneToolSet` exposes so that dynamically generated calls from Gemma are routable.
- Currently `AgentRouter` only has runtime-registered handlers. We will add a `registerDefaultTools()` helper that mirrors `ActionExecutor.executeTool()` for tools not handled inline.
- Alternatively, route all known tool calls through `ActionExecutor.executeTool()` directly; `AgentRouter` remains the plugin extension point.

---

### Phase D — OCR Upgrade

#### D1. Add MediaProjection screenshot capture helper in `:phonecontrol`
New file: `ScreenshotCapture.kt`
- Uses `MediaProjectionManager.createScreenCaptureIntent()` to request user permission.
- Provides `captureScreen(): Result<Bitmap>`.
- Falls back to `Result.Error("Screenshot capture not permitted")` if denied.

#### D2. Extend `OcrControl`
- Keep existing `recognizeText(bitmap): Result<String>`.
- Add `recognizeScreen(screenshotCapture: ScreenshotCapture): Result<String>` that calls screenshot capture first, then runs ML Kit OCR on the bitmap.

#### D3. Update `ActionExecutor` for `read_screen` / `ocr_screen`
New flow in `executeTool()`:
```kotlin
"read_screen" -> {
    val accResult = accessibilityControl.captureScreenText()
    if (accResult is Result.Success && accResult.data.isNotBlank()) {
        accResult
    } else {
        ocrControl.recognizeScreen(screenshotCapture)
    }
}
```
- If accessibility text is sufficient, use it (fast, no extra permission beyond accessibility).
- If empty/poor, fall back to MediaProjection screenshot OCR.
- Add required `MediaProjection` permission request flow in `SafetyPipeline.getRequiredPermissionsForTool()` (no runtime permission, but user grant is required; we gate it behind a confirmation).

#### D4. Standardize dependency
- `:app` no longer directly depends on `play-services-mlkit-text-recognition`.
- All OCR flows go through `:phonecontrol` using the bundled `com.google.mlkit:text-recognition:16.0.0`.

---

### Phase E — Model Management

#### E1. Update `ModelManager`
- Add a `.litertlm` detection entry:
  ```kotlin
  "gemma-4-e2b" to "llm"
  ```
- `detectModels()` should look for files ending in `.litertlm` in `appPrivateModelPath/gemma-local/`.
- `ensureModelDirectories()` already creates `gemma-local`.
- Add helper `getLlmModelPath(): String?` that returns the first `.litertlm` file path or `null`.

#### E2. Orchestrator model load trigger
- In `UnoOneApplication` or `AgentOrchestrator`, after model directories are ensured, call `localBrain.loadModel(path)` if a `.litertlm` file exists.
- Log load success/failure and expose model status in `SettingsViewModel`.

---

### Phase F — Safety & Agentic Constraints

#### F1. Manual tool calling guarantee
- LiteRT-LM `ConversationConfig.automaticToolCalling = false` is the single source of truth.
- `GemmaPlanner.plan()` returns a `ToolCall`, never executes it.
- `AgentOrchestrator` runs every returned `ToolCall` through `SafetyPipeline.classifyRisk()` before `ActionExecutor.executeTool()`.

#### F2. "Draft before send" rule
- For `send_whatsapp` and `draft_email`, keep them at `STRONG_CONFIRM` in `SafetyGuard`.
- In `ActionExecutor`, do **not** auto-press send; current implementation already opens the draft UI. Add a spoken/TTS hint: *"Draft opened. Please review and press send."*
- For SMS/email, create a new `draft_sms` tool and move `send_message` to `BLOCK` (already blocked).

#### F3. Step-by-step verification
- For compound commands, keep per-part safety checks already implemented in `handleCompoundCommand()`.
- For Gemma-generated multi-step plans, we will initially flatten them into a single `ToolCall` per inference; multi-step planning will be added in a follow-up phase.

#### F4. Audit trail
- `ActionExecutor` already logs every action. Ensure Gemma-generated tool calls include the raw JSON in `toolArgsJson`.

---

### Phase G — Tests & Quality

#### G1. Unit tests to update/add
- `CommandParserTest`: keep all RuleBasedParser tests; add `parseAsync_modelLoaded_returnsToolCall()` and `parseAsync_modelNotLoaded_returnsNull()` (mocked `GemmaPlanner`).
- `LocalBrain` tests: replace ONNX tests with LiteRT-LM mocked tests; verify `loadModel` and `runInference` return `ToolCall`.
- `SafetyGuardTest`: verify that every tool exposed in `UnoOneToolSet` has a risk classification.
- Add `UnoOneToolSetTest`: ensure all `@Tool` functions are serializable into LiteRT-LM schema without crash.

#### G2. Build/lint
- Run `./gradlew.bat :localbrain:compileDebugKotlin`.
- Run `./gradlew.bat :app:compileDebugKotlin`.
- Run `./gradlew.bat :app:testDebugUnitTest`.
- Fix any lint `warningsAsErrors` failures (unused imports, missing translations if new strings are added).

#### G3. Device smoke test
- Push a Gemma 4 E2B `.litertlm` to Xiaomi 14:
  ```bash
  adb push gemma-4-e2b-it.litertlm /sdcard/Android/data/com.unoone.agent/files/models/gemma-local/
  ```
- Launch app, confirm model detected in Settings.
- Say/text a complex command not covered by rules (e.g., *"Open WhatsApp and prepare a message to Pankaj saying I will call later"*).
- Verify timeline shows `UNDERSTANDING → TOOL_SELECTED → SAFETY_CHECK → EXECUTING → VERIFYING → SPEAKING`.
- Verify confirmation dialog appears and executing it opens WhatsApp draft.

---

### Phase H — Documentation

#### H1. Update `STATUS.md`
- Change limitation #1 from *"Gemma 2B full inference not yet implemented"* to *"Gemma 4 E2B via LiteRT-LM is implemented; model file must be pushed manually"*.
- Add MediaProjection screenshot OCR as completed.
- Add `.litertlm` push command.

#### H2. Update `README.md`
- Replace the architecture diagram's `ONNX / LocalBrain` block with `Gemma 4 E2B / LiteRT-LM / GemmaPlanner`.
- Update hardware requirements: "Expert" tier now says "Local LLM inference via LiteRT-LM" instead of ONNX.
- Update command parser section: rule-based first, Gemma planner fallback.

---

## 4. Files That Will Change

| File | Change |
|------|--------|
| `localbrain/build.gradle.kts` | Swap ONNX for LiteRT-LM |
| `localbrain/src/.../LocalBrain.kt` | Rewrite as GemmaPlanner wrapper |
| `localbrain/src/.../GemmaPlanner.kt` | **New** LiteRT-LM engine + manual tool calling |
| `localbrain/src/.../UnoOneToolSet.kt` | **New** tool schema declarations for Gemma |
| `localbrain/src/.../ContextSnapshot.kt` | **New** context bundle |
| `localbrain/src/.../PromptBuilder.kt` | Convert to LiteRT-LM message builder |
| `app/build.gradle.kts` | Remove Play Services ML Kit OCR |
| `phonecontrol/build.gradle.kts` | Keep bundled ML Kit OCR (no change) |
| `phonecontrol/src/.../ScreenshotCapture.kt` | **New** MediaProjection helper |
| `phonecontrol/src/.../OcrControl.kt` | Add screenshot-OCR fallback |
| `modelmanager/src/.../ModelManager.kt` | Detect `.litertlm` files |
| `app/src/.../parsing/CommandParser.kt` | Add `parseAsync` + context injection |
| `core/src/.../interfaces/ICommandParser.kt` | Add `parseAsync` + context source setters |
| `app/src/.../AgentOrchestrator.kt` | Wire async Gemma path |
| `app/src/.../execution/ActionExecutor.kt` | OCR fallback + draft-before-send hints |
| `app/src/.../safety/SafetyPipeline.kt` | Add MediaProjection gate |
| `agentrouter/src/.../AgentRouter.kt` | Register default tool handlers |
| `app/src/test/.../CommandParserTest.kt` | Add async path tests |
| `app/src/test/.../SafetyGuardTest.kt` | Cover `UnoOneToolSet` names |
| `STATUS.md` | Update limitations & model push commands |
| `README.md` | Update architecture & brain description |

---

## 5. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| LiteRT-LM artifact or API differs from docs | Pin to `0.13.1` (latest published on Google Maven; no `1.0.x` exists); add compile-time smoke test; provide fallback to RuleBasedParser if `Engine.initialize()` fails. |
| Gemma 4 E2B model is too large for Xiaomi 14 | Start with E2B (2B active parameters); profile memory. If needed, add a smaller distilled model slot. |
| Tool-call JSON shape mismatches our `ToolCall` | Use LiteRT-LM manual tool calling and a dedicated mapper (`LiteRtResponseMapper.kt`) with exhaustive unit tests. |
| MediaProjection permission UX is disruptive | Only trigger on explicit "ocr screen" or when accessibility text is empty; show a one-time education dialog. |
| Lint `warningsAsErrors` fails | Run `./gradlew.bat :app:lintDebug` and fix before final commit. |
| Existing tests break | Keep `CommandParser` no-arg constructor for rule-only tests; mock `GemmaPlanner` in new tests. |

---

## 6. Order of Implementation

1. **Dependencies** (A1–A3) — swap ONNX for LiteRT-LM, standardize OCR.
2. **LiteRT-LM scaffold** (B1–B5) — `GemmaPlanner`, `UnoOneToolSet`, `ContextSnapshot`, `PromptBuilder`.
3. **OCR upgrade** (D1–D3) — MediaProjection screenshot fallback.
4. **Model management** (E1–E2) — `.litertlm` detection and auto-load.
5. **Orchestrator wiring** (C1–C4, F1–F4) — async parse path, safety gate, draft-before-send.
6. **Tests** (G1–G2) — unit tests + compile + lint.
7. **Docs** (H1–H2) — README and STATUS refresh.
8. **Device smoke test** (G3) — Xiaomi 14 end-to-end.

---

## 7. Verification Checklist

- [ ] `./gradlew.bat :app:compileDebugKotlin` passes.
- [ ] `./gradlew.bat :app:testDebugUnitTest` passes.
- [ ] `./gradlew.bat :app:lintDebug` passes (or warnings fixed).
- [ ] Rule-based commands still work offline with no model.
- [ ] Gemma path activates only when `.litertlm` is present and loads successfully.
- [ ] Every Gemma-generated tool call is blocked/routed through `SafetyPipeline`.
- [ ] Screenshot OCR falls back only when accessibility text is empty.
- [ ] STATUS.md and README.md reflect the new brain stack.

---

**Recommended next action:** Approve this plan, then I will begin with Phase A (dependency swap) and proceed file by file.
