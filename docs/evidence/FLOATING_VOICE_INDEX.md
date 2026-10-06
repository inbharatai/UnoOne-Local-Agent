# Floating voice evidence index — 0.8 source checkpoint

**0.8.0-alpha-voice / versionCode 8: final local gates PASSED; physical gates PENDING.** Current JVM count: 1006, zero failures/errors/skips. [Matching APK/build receipts](floating-voice-delivery/results.json) · [User guide](../FLOATING_VOICE_GUIDE.md). Hosted CI must be checked separately for the delivered commit; no phone speed/accuracy claim follows.

## Source anchors inspected

All paths below are relative to `android-app/UnoOneAgent/`:

- `core/src/main/java/com/unoone/agent/core/voice/VoiceRouteCompiler.kt`: explicit grammar, frozen underlying evidence, exact literals, clarification and explicit Owl review routing.
- `app/src/main/java/com/unoone/agent/UnifiedVoiceCoordinator.kt`: shared intake, priority Stop, retained-rule path, capture-permission no-replay, review expiry and scoped confirmation.
- `app/src/main/java/com/unoone/agent/VoicePurposeAdapter.kt`: case-insensitive unique labels, exact values and limited single-app Owl goals; HOME is not a purpose-adapter operation, while exact global Home/Recents/Notifications rules are retained upstream with DISPATCH/UNVERIFIED outcomes.
- `app/src/main/java/com/unoone/agent/RetainedVoiceRules.kt`: exact global navigation, actual retained tool names and bounded parser-owned compounds; Back/Scroll remain frozen native scope. `AgentOrchestrator.kt` also retains greeting/mic-check and language-change shortcuts. Classification is not authorization.
- `app/src/main/java/com/unoone/agent/FloatingAgentService.kt`: shared coordinator, frozen ingress, foreground capture handles, awaited default cue and post-start optional haptic.
- `app/src/main/java/com/unoone/agent/overlay/OwnOverlayBridge.kt` and `StopSurfaceAdmission.kt`: registered-window receipts, capture-drain precondition and active native notification Stop admission; exact own bubble/chat window registry via public accessibility-node IDs and narrow structural OS-bar source-metadata allowance only; no screenshot foreign-pixel exemption.
- `app/src/main/java/com/unoone/agent/TaskStopReceiver.kt`: explicit native global cancellation and projection-service stop.
- `voice/src/main/java/com/unoone/agent/voice/VoiceLatency.kt`: default Spoken and touch-exploration fallback policy.
- `app/src/main/java/com/unoone/agent/ui/screens/LatencyDiagnosticsScreen.kt`: real Settings screen, opt-in metadata and explicit document export, playback/TTFT caveats.

- `app/.../CaptureRoutingLifetime.kt` and foreground/floating callers: retire capture before task await, enabling a new confirmation capture while preserving original source/generation.
- `voice/.../UtteranceCompletionPolicy.kt` and ingress callers: duration-cutoff ordinary commands/approvals rejected; exact Stop priority retained; 850/500 ms settings unchanged.
- `voice/.../PassiveMonitoringPolicy.kt`: native/Owl busy permits conditional Stop monitoring, not ordinary busy-command conversational multitasking.
- `core/.../latency/LatencyTrace.kt`: OFF by default; disabled/null-token recorder paths avoid diagnostic clock reads. No first-audible/TTFT/native-phase measurement claim.

Source implementation and standalone compilation do not prove Android behavior, audible output, native shutdown timing or real-app task success.

## Real host prompt experiments: retain the failures

Negative evidence is now archived in [repository text receipts and SHA-256 relocation manifest](floating-voice-prompt/README.md). Original binary fixtures/classes/JNI artifacts remain outside Git in the workspace paths below; the text archive is not a self-contained model bundle:

| Workspace archive | Result | Interpretation |
|---|---|---|
| `/agent/workspace/owl-native-prep/voice-prompt-ab` | FULL 2/2 production-codec accepted; compact V1 0/2 | Both compact outputs malformed. Mean generate walls 68,020.0 ms vs 28,823.5 ms are raw return timings, not a usable speedup. |
| `/agent/workspace/owl-native-prep/voice-prompt-v2` | FULL 4/4; compact V2 3/4; focus rejected as unsupported official action | Mean generate walls 67,978.2 ms vs 36,497.0 ms do not justify promotion. Navigation edge hit also has no robustness margin. |

Both runs used real unchanged production host JNI/runtime and fixed synthetic images with independent bounds. No Android actions dispatched. Output codec rejection was not repaired into success. No measured TTFT/generated-token count/native phase profiler; public generate/JNI walls and admitted text/image tokens are not those metrics. **FULL remains default; V1/V2 were moved out of production into evaluation-only sources/tests.** Clean final APK inspection confirms candidate classes/version markers absent. Raw text, failures, prompts, model/source hashes and resource receipts are preserved without cherry-picking; binary originals remain identified by the archive manifest.

Readable workspace handoffs: `/agent/workspace/owl-prompt-ab-handoff.md` and `/agent/workspace/owl-prompt-v2-handoff.md`. These are developer handoffs, not phone results.

## Other evidence and outstanding gates

- [Historical 0.7 Owl host/build receipts](owl-delivery/results.json): not current 0.8 qualification.
- [Historical real Owl host probes](gui-owl-host/SUMMARY.md): retain original failures and later successes; not phone inference or general accuracy.
- Recent workspace integration handoffs inspected: `unified-voice-app-handoff.md`, `voice-latency-handoff.md`, `latency-complete-handoff.md`, `trace-final-handoff.md`, `capture-stop-integration-handoff.md`, `audio-final-handoff.md`. Earlier incomplete handoffs can be superseded by current source; none alone is full acceptance.
- Independent source reviews and targeted rechecks were completed; final clean voice8 local build gates and matching artifact identity passed. Review is bounded, not an exhaustive security certification. Hosted CI remains a separate post-commit check.
- Physical speech/ASR, acoustic Stop/AEC, microphone teardown, overlay/notification admission, screenshot consent/window authority, model inference, real-app workflows and sustained thermal/battery validation: **PENDING**.
- A user's ability-to-run-12B statement is self-report, not measured device specifications or benchmark evidence.

Timing summaries remain opt-in volatile metadata, pilot sample counts and censored/failure-aware descriptions. At least 20 successful samples is only the p95 display threshold, not statistical confidence or phone-performance certification. Use the final receipt totals rather than adding overlapping standalone-test counts.
