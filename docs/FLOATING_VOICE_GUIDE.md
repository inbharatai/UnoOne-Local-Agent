# Floating voice: bounded native actions, explicit Owl review

> **Current maintenance build:** 0.8.1-alpha-voice / versionCode 9. The behavior described here is unchanged; use the [cleanup build receipts](evidence/cleanup-delivery/results.json) for current APK identity. The 0.8.0 hashes and test receipts below remain historical.

**Development build: 0.8.0-alpha-voice / versionCode 8. Final local gates PASSED; physical qualification PENDING.** 1006 JVM tests passed, zero failures/errors/skips; lint and both APK assemblies passed. See [matching current artifact/build receipts](evidence/floating-voice-delivery/results.json). This describes bounded supported paths, not whole-phone completion, acoustic qualification or a measured speed claim.

## What changed—and what did not

The existing floating bubble now feeds the shared `UnifiedVoiceCoordinator`, also used by foreground and wake ingress. It freezes capture identity, cancellation generation and available underlying-app evidence before recognition. Native-first rules and Android actions already existed: this work unifies intake and removes unnecessary model-lock waiting on eligible deterministic paths; it does not invent native-first execution or prove lower phone latency.

Known bounded actions use the existing native task runtime, fresh observations, scope checks and postconditions. Unknown device instructions ask for clarification rather than entering an unrestricted action-model planner. Tool-less conversation requires a supporting selected model; **generic Owl chat is unsupported**. Open-ended requests such as booking a trip or managing arbitrary apps are not implemented by this grammar. Queue admission is not task completion.

## Setup and use

Enable the agent and grant the permissions needed by the chosen feature: microphone and verified local speech assets for local voice, overlay permission for the bubble, Accessibility for supported UI operations. Screen capture requires separate Android MediaProjection consent and task-specific review. Keep notifications enabled, including the service channel and Android notification permission where required: temporary self-hiding is denied without an available native Stop notification. Diagnostics collection is a separate opt-in, not a prerequisite for voice.

Open the bubble's chat and use its microphone. The default spoken readiness cue remains **“Listening. Say one command.”** Do not treat a tap or a queued task as proof of microphone readiness or success. Read or listen to the native status/result. A request needing screen permission does not automatically replay when permission is granted: grant permission, then make a new request.

Default local speech capture records PCM for Sherpa recognition. The opt-in Android STT fallback, including the public `transcribeWithAndroid` path, uses the same capture ownership/drain rules and only `createOnDeviceSpeechRecognizer` on **API 31+ with an available installed on-device recognizer**. Older devices or unavailable local recognizers are rejected; install the local speech models instead. There is **no provider-dependent/cloud STT fallback**. Language availability depends on the installed local recognizer; no recognition-accuracy guarantee is implied.

## Exact bounded grammar

Examples use illustrative installed-app and accessible-widget labels; they are not promises that those apps expose supported widgets. Quotes are delimiter syntax (straight or smart double quotes); ordinary single labels may be unquoted. Quote literal values or labels containing `in`, `into`, `and`, `or` or `then`.

| Say | Source behavior |
|---|---|
| `open Settings` / `launch app Settings` | Resolve one installed app and request OpenApp. |
| `read screen` / `read this screen in Settings` | Native accessible UI readback, not arbitrary visual interpretation. |
| `scroll down in Settings` / `scroll up` | Bounded native scroll on the chosen/current underlying app. |
| `go back in Settings` | Bounded native Back. |
| `focus "Search" in Settings` | Bind a unique native-known eligible field. |
| `tap "Library"` / `click "Library" in Example App` | Only eligible native-known control semantics, not every visible label. |
| `select tab "Library" in Example App` | Native-known eligible tab. |
| `type "AbC 42" into "Draft title" in Example App` | Preserve the exact value; field semantics and authorization still apply. `write` is an alias. |
| `open Settings then focus "Search"` | Explicit sequence; subsequent omitted app inherits the preceding OpenApp. |
| `stop` / `stop everything` | Priority cancellation, ahead of stale-capture checks. |

At most eight explicit steps, separated by `then` or `and then`, are accepted by this compiler. App qualifiers are optional when a fresh, unambiguous **frozen CurrentUnderlying** identity was captured; no request is rebound to whatever happens to be foreground later. Without valid evidence, name the app. Naming an app for a field operation does not implicitly launch it: say `open … then …` when launching is intended.

Voice target labels use **case-insensitive UNIQUE matching**, not fuzzy matching. Values retain exact case/Unicode; this does not guarantee ASR transcription accuracy. Duplicate labels, unresolved apps, stale/foreign windows, unknown widgets, negation, ambiguous connectors and unsupported grammar require clarification/manual help. Broad polite or open-ended phrasing is not equivalent to a supported command.

**Exact parser-owned global Home/Recents/Notifications rules are retained on native dispatch.** Their result is DISPATCH/UNVERIFIED, not verified whole-task completion. They are intercepted by the retained-rule path; the bounded purpose adapter does not itself add HOME support. Back/Scroll stay on frozen app-scoped native routing. Protected credentials, financial/destructive/final-send operations remain blocked or subject to existing native safety rules; heuristics are not universal safety guarantees.

## Retained deterministic tools

Greeting/mic-check replies and language-change shortcuts remain native deterministic paths, not generic Owl conversation. `RetainedVoiceRules` keeps exact global Home/Recents/Notifications rules and actual parser-owned tools: local note creation/search/deletion (including delete-all), email drafts, WhatsApp **draft** preparation (`send_whatsapp` is the internal draft-only name), calendar open/check/insert, voice recording, share-text chooser, dialer opening and skill creation. This is a whitelist of existing tool implementations, not permission for arbitrary natural language or a claim all requests are direct-tier. Supported parser-owned compounds are bounded to two or three supported steps, with open-app/Chrome/camera navigation permitted inside those compounds. These retain the existing SafetyPipeline, confirmations and permission gates; deletion is not silently authorized. A request beginning with “send” asks the user to use draft wording and review/send manually.

Eligible rule/native branches do not require a planning model wherever implemented; this does **not** mean every retained tool, safety tier or workflow runs without any model involvement. Unknown commands do not become Owl chat or automatic Owl fallback.

## Explicit `Use Owl`—same floating voice entry

For supported installed-app/widget scope, say for example:

`Use Owl to focus "Search" in Settings`

or `Use Owl to open Example App then select tab "Library"`.

The prefix is explicit opt-in, **never failure fallback**. Supported scope is one app, an optional first OpenApp, then ReadScreen/click/focus/select-tab/write. Back, scroll and Home are not admitted by this Owl adapter. Unsupported goals are rejected without a native rescue route. Owl's installed/verified model and memory/capture prerequisites still apply; see [Owl setup](GUI_OWL_4B_PHONE_SETUP.md).

The floating panel displays and speaks the exact app/package, operations, unique labels, exact values, local-frame permission and time/step limits. Review it, then use the bound **Confirm/Cancel** buttons or a new capture saying exactly **`confirm`** or **`cancel`** after the question finishes. `yes` is not approval. Review expires after 60 seconds; the separately disclosed approved-execution ceiling is 180 seconds, with the exact compiled step count. Stop/new requests invalidate old authority. Approval is bound to the live review/request/cancellation generation, not reusable transcript text. ReadScreen can remain a native UI-readback path even under this explicit route; opting into Owl does not imply every step calls a model.

Owl proposals still face production format validation, native target binding and fresh postconditions. Unknown canvas/image controls, IME or foreign overlays can require human action. Images remain restricted by known-widget and strict foreign-scope capture checks—**not all-app screenshot access or arbitrary image clicking**.

## Overlay, microphone and Stop boundaries

Own bubble/chat source metadata is bound to the exact registered attached windows using public accessibility-node window IDs, not package-wide exemptions. A narrow structural OS-bar allowance applies only to source metadata; screenshot foreign-pixel policy is unchanged and strict. Only registered UnoOne windows may be temporarily hidden. Capture must drain first; an active enabled native notification with the immutable Stop action is required before hiding. Notification/channel admission is a snapshot, not a promise of perpetual OS visibility. Foreign overlays are not whitelisted. Old hide/restore receipts cannot reopen a closed/replaced chat or restore a stopped generation. Stop notification invokes global cancellation and projection-service shutdown without requiring the microphone, overlay or master switch.

Microphone sessions use unique ownership handles and a process lease across PCM and opt-in Android recognition. Passive wake capture yields to foreground capture; stale cleanup cannot release its successor. Native/API cleanup acknowledgments and worker completion govern release; failed teardown can quarantine admission. These are software ownership guarantees under test, not measured physical microphone/HAL closure.

Foreground capture ownership retires before awaiting the submitted task, allowing a second spoken confirmation capture after the review question. Retirement is not task cancellation: original ingress/source and cancellation generations remain bound. Whole-utterance MAX_DURATION cutoff rejects ordinary commands and approval transcripts rather than dispatching a partial sentence; exact emergency Stop retains priority.

The reviewed speech-ownership repair closes the specific old A → disable/clear → re-enable/new B → late A completion race: production playback acquires a unique owner and releases only that owner, so A cannot make B appear idle. Both Sherpa and Android playback branches retain ownership through the echo-decay hold. Legacy anonymous accounting cannot release an owned token. This is narrow source-review closure, not a blanket lifecycle guarantee or a new executed-gate/physical-audio result.

Native/Owl busy periods retain conditional stop-only monitoring, not general voice-queue or conversational multitasking. Non-speaking model inference is Stop-only where monitoring is available; it does not admit ordinary queued voice requests while busy. Playback-time voice Stop/Cancel is conditional on available **and enabled** OS AEC plus the wake-qualified exact-command policy. There is **no ordinary-command TTS barge-in** and no measured echo-suppression guarantee; UI/notification Stop remains essential.

Spoken cues stay default. Settings → **Latency diagnostics and listening cue** opens the **Latency diagnostics** screen. Under **Listening cue (touch exploration always uses spoken)**, choose **Spoken (default)** or opt-in **Visual / haptic (where supported)** (the current choice is prefixed **Selected:**); floating haptic feedback is attempted only after successful recorder start, and may be visual-only. Touch exploration/screen-reader accessibility forces the spoken fallback. Native accessible UI readback and spoken status remain the supported path—not an assertion that every app is accessible.

## Latency diagnostics and unchanged runtime

Settings → **Latency diagnostics and listening cue** → **Latency diagnostics** provides the off-by-default **Collect timing metadata** switch, **Clear**, **Refresh** and explicit **Export JSON…** through Android's document picker. Collection is bounded, volatile timing metadata; disabling clears traces. Disabled/null-token recorder paths do not read the diagnostic clock (this does not disable unrelated runtime safety clocks). Export grants the user-selected document destination only; there is no automatic upload. No raw transcripts/text, audio, screenshots, prompts, user labels or raw task IDs are exported by this recorder. This is not a blanket claim about every legacy app log or saved state.

Rows show origin/route/profile, eligible and successful sample counts, p50 and p95, with failed/cancelled/timed-out/open-censored/drop/eviction information. Missing timings are **No data**, not zero. **p95 requires at least 20 successful samples**; even then it is descriptive pilot data, not a qualified phone benchmark. Playback timing is a wait-completion proxy, not audible onset. **TTFT and first-audible onset are not measured; native phase timing is not established**. Repeated-stage/attempt attribution and full physical end-to-end qualification remain limited; do not subtract human waits or infer native substage timing without evidence.

The 850 ms native ASR endpoint and 500 ms KWS setting remain unchanged. Model weights, threads and native backend remain unchanged; these are not tuning-derived speed gains. A user's statement that their device can run 12B is self-reported capability, not measured phone specifications, RAM headroom or this app's qualification.

## Real compact-prompt A/B: rejected, not a shipped speedup

Both comparisons used real host Owl/JNI inference on synthetic GUI fixtures, not a phone. V1 failed the production output codec on **both** candidate cases (baseline 2/2 accepted). V2 failed its **focus case, 1 of 4** (baseline 4/4 accepted; candidate 3/4). Faster invalid outputs are not successful-action speedups. No coordinate extraction or output repair turns a rejected response into success. **Both compact candidates are rejected for production and moved to evaluation-only sources/tests; FULL remains production default.** Clean final APK DEX inspection confirms candidate classes/version markers absent; source prototypes and tests remain in evaluation, not app source sets. [Raw experiments, failures and SHA-256 preservation](evidence/floating-voice-prompt/README.md). Tiny synthetic samples do not establish general noninferiority, useful phone speed, dispatch or real-app completion. Preserve failures alongside successes; [evidence index](evidence/FLOATING_VOICE_INDEX.md).

Final application/module JVM count is 1006, zero failures/errors/skips. Archived candidate evaluation tests are separate, not included in that APK/module count. Browser typecheck,10 unit/15 Playwright fixtures and11 invariant mutation tests passed; production npm audit reported0 vulnerabilities.

Final local host/build gates passed; debug APK SHA-256 `77598ef26a061796f832c24179ee95d4a52ca0047d746936f605c466eebc1ffe` (425,329,787 bytes). Device speech/Stop/acoustic checks, real overlay/compositor behavior, timing and sustained phone qualification remain pending. This is a development delivery, not whole-phone perfection or production certification. The existing non-16KB CameraX ELF exception remains documented in current receipts.
