# UnoOne Architecture

This is the consolidated architecture index. For the deep, module-by-module walkthrough see
[`local-architecture.md`](local-architecture.md); for the upgrade history see
[`../PLAN-Gemma4-Migration.md`](../PLAN-Gemma4-Migration.md) (historical). Current runtime brain:
**Gemma 3n E4B via LiteRT-LM**.

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