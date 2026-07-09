# UnoOne Architecture

This is the consolidated architecture index. For the deep, module-by-module walkthrough see
[`local-architecture.md`](local-architecture.md); for the upgrade history see
[`../PLAN-Gemma4-Migration.md`](../PLAN-Gemma4-Migration.md) (historical). Current runtime brain:
**Gemma 3n E4B via LiteRT-LM** (default, device-verified fallback); **Gemma 4 E2B** is an
Experimental opt-in profile, not device-verified. Every model-proposed tool call is checked
against a **CanonicalToolRegistry** of 26 tools (unknown tools rejected, required args validated)
before safety/execution.

**Agentic loop + judge + eval (implemented, inference device-time-only).** After an LLM-planned
observation-producing call, a bounded **ReAct loop** (`MAX_STEPS=3`, every step through the safety
pipeline) feeds the tool result back to the model via `planNext`. A second on-device **safety judge**
runs on a dedicated judge conversation and may only *escalate* the keyword-classified risk
(`SafetyJudgePolicy`, never de-escalates). A **calibration eval harness** (`EvalPromptSet` +
`EvalScorer` + instrumented `BrainEvalHarnessTest`) turns "is Gemma good enough?" into a printed
accuracy number for Gemma 3n vs 4. The control/scoring logic (`ReActLoopController`,
`SafetyJudgePolicy`, `EvalScorer`) is JVM-tested; the LiteRT-LM inference (`planNext`, `judgeSafety`)
and the eval runner are device-time-only and **not yet run on a physical device** — no ReAct outcome,
judge verdict, or Gemma accuracy number is claimed or committed.

**Innovations #4–#8 (implemented, control JVM-tested, inference device-time-only / partly inactive).**
- **Multimodal vision (#4):** a new `describe_scene` tool (26th canonical, STRONG_CONFIRM +
  MediaProjection) builds a screen scene from OCR + foreground context via JVM-tested
  `SceneDescriptionBuilder`. The LiteRT-LM `Content.ImageBytes` vision path is wired against the real
  AAR but gated `VISION_MODEL_ENABLED = false` (shipped Gemma models are text-only); it lights up only
  with a vision-capable `.litertlm` artifact. No vision understanding is claimed.
- **Outcome-learned memory (#5):** per-(command-signature, tool) outcomes are stored in Room and
  surfaced to the planner as a hint ("prior avoid: …", "prior worked: …"). `OutcomeMemoryPolicy`
  (signature + token-overlap retrieval + rendering) is JVM-tested; Room I/O in `MemoryModule`; no
  schema migration. The on-device benefit is not yet measured.
- **Streaming (#6):** first-turn LLM planning can stream partial text to the timeline via LiteRT-LM
  `sendMessageAsync` (`Flow<Message>`). `StreamingTextReducer` (robust to cumulative vs delta
  snapshots) is JVM-tested; the `Flow` + UI surfacing are device-time-only, with a fallback to the
  synchronous `plan()` path.
- **Diagnostics self-heal (#7):** a rolling `ToolHealthTracker` flags flaky tools; the brain
  auto-reloads when found down (it self-closes on a 30s timeout). `BrainHealthPolicy` + the tracker are
  JVM-tested; the reload + timeline surfacing are device-time-only.
- **Signed manifest integrity (#8):** `ModelManifest` carries an Ed25519 `manifestSignature`;
  `ManifestSignatureVerifier` canonicalizes + verifies (platform EdDSA, API 33+; minSdk 28 falls back
  to accept + log). JVM-tested. `ManifestSigningKey.PUBLIC_KEY_BASE64` is intentionally blank → wired
  but INACTIVE; no fabricated keys. No Bouncy Castle added (avoids ~6MB APK cost for an inactive
  feature).

## 13-module structure

```
:app                Compose UI, ViewModels, FloatingService, permissions, AgentOrchestrator (8-step pipeline)
:core               Result, ToolCall, TimelineStep, RiskLevel, Logger, safety primitives
:storage            Room DB (5 entities, 5 DAOs), migrations
:modelmanager       manifest load, install + integrity (sha256/size), health, detect
:localbrain         RuleBasedParser, PromptBuilder, GemmaPlanner (LiteRT-LM), UnoOneToolSet, RAGManager
:voice              SherpaSttEngine, SherpaTtsEngine, KeywordSpotterEngine, VoiceService, VoiceModule
:agentrouter        tool registry + plugin routing
:safetyguard        SafetyGuard (4-tier risk), input override
:phonecontrol       PhoneControl, CalendarControl, OcrControl, PackageResolver, BlindAidManager
:memory             keyword context, preferences, corrections, patterns
:skills             JSON step storage, trigger matching, CRUD
:observability      Diagnostics (latency/success), crash logs
:accessibilitycontrol  click/type/fill/scroll/swipe/back/home/read_screen/find+click
```

## Request lifecycle

`user input (text/voice) → InputSanitizer → Skill trigger check → CommandParser.parseAsync
(RuleBasedParser fast path, GemmaPlanner for complex) → ToolCall → runValidatedToolCall:
system-access check → runtime-permission check → risk classification (tool + input, max wins) →
block/confirm gate → ActionExecutor.executeTool → Diagnostics + AuditLogger → timeline + spoken
response.`

Compound commands and skill steps each run the full pipeline per step — safety is never bypassed.

## Dependency-injection note (honest gap)

Hilt is wired at the app level (`@HiltAndroidApp`, `@AndroidEntryPoint` on `MainActivity`) but
`AgentOrchestrator` still constructs its components manually (`MemoryModule`, `OcrControl`,
`AccessibilityControl`, `CommandParser`, `ActionExecutor`, `SafetyPipeline`). This is intentional
deferred work, not a bug: a full Hilt migration is tracked but not yet done, so the codebase is
**not** claiming a clean DI architecture it doesn't have. Single shared instances (`VoiceModule`,
`OcrControl`, and now `AccessibilityControl`) are enforced manually in `AgentOrchestrator`.

## Key cross-cutting decisions

- **Manual tool calling:** `GemmaPlanner` uses `automaticToolCalling = false` — the model proposes,
  the app validates and executes every call.
- **Offline-first:** voice/notes/control/planning are local; the only network path is opt-in
  `web_search` (off by default). See [`SAFETY.md`](SAFETY.md).
- **Integrity:** model files are sha256/size-verified where the manifest carries a hash; Gemma is
  manual-import/unverified until a hash is added. See [`MODELS.md`](MODELS.md).
- **Play readiness:** permissions, foreground services, and accessibility justification in
  [`play-review/`](play-review/).

## Further reading

- [`local-architecture.md`](local-architecture.md) — detailed module walkthroughs
- [`voice-module-implementation.md`](voice-module-implementation.md) — Sherpa STT/TTS/KWS
- [`phonecontrol-implementation.md`](phonecontrol-implementation.md) — phone/OCR/calendar
- [`localbrain-implementation.md`](localbrain-implementation.md) — parser + Gemma planner
- [`tool-schema-registry.md`](tool-schema-registry.md) — tool declarations