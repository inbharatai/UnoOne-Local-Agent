# UnoOne V3 deep review — NOT RELEASE READY

Review date: 2026-10-05. Repository: inbharatai/UnoOne-Local-Agent. Baseline/main: 69bf5e097b85a1bc40da14c07609dde8ca765cbd. V3 implementation is uncommitted on feat/unoone-v3-local-computer-use. No new code was pushed for this review. Final delivery remains main after acceptance, not a permanent feature-branch fork.

## Executive verdict

The work is a partially integrated development candidate, not an expert Android computer-use agent. The new core has useful native safety, immutable observations, bounded control, explicit model selection and cancellation ownership. Those are necessary but do not establish speech accuracy, safe autonomous browsing or general multi-app completion. Do not merge this checkpoint into main as a completed V3 release.

This review combined three independent source tracks (speech, Android controller and browser), inspected complete test XML and lint output, and checked both built APK ZIPs for the actual Page Agent asset. Source-derived exploit/failure scenarios are not presented as physically reproduced exploits. Reviewed-file hashes are included in the detailed appendices; later source changes invalidate those specific review snapshots.

## Measured build/test evidence

- Clean integrated Gradle invocation finished with exit 1, in 13m 41s; raw log is build-evidence/final.log in the execution workspace.
- Unit-test XML: 666 tests; 518 passed; 148 failed during Robolectric runtime dependency acquisition; 0 reported skips and 0 separate errors. All 148 recorded failure messages concern Maven artifact retrieval, not demonstrated assertions against application behavior. They remain blocked/failed, never counted as passes.
- Blocked runtime versions: 123 cases need 4.4_r1-robolectric-r2-i6; 20 need 14-robolectric-10818077-i6; 5 need 9-robolectric-4913185-2-i6. Downloads from repo1.maven.org returned 403. Network access requested.
- Lint: 1 new error, suspicious indentation in AgentOrchestrator.kt around line 1109. Also 15 errors are filtered by the existing lint baseline; a future green lint run would not mean zero historical issues.
- Gradle produced app-debug.apk (392248576 bytes), SHA256 8bbc8350e2f046a03396634a70c84b230d9a96c424171e649924082f9d8e45e7, and instrumentation APK (2647905 bytes), SHA256 a146785ec57aa75c6d94d6aa855cd2d34c78b686145772ce02f6c8cb4e18b9f0. Compilation/packaging is not release acceptance.
- ZIP inspection of BOTH artifacts found no unoone-page-agent.js asset. The browser-agent feature is not present in the built application. Plain WebView behavior must not be confused with the agent runtime.
- Page Agent dependency installation/typecheck/tests/bundle remain blocked by registry.npmjs.org network policy. No green browser test claim.
- No physical device, acoustic accuracy, JNI cancellation stress, airplane-mode, battery or thermal qualification ran. All 100 device benchmark tasks remain pending.

## Highest-priority findings

1. **Speech endpoint correctness:** AudioRecorder uses unsigned arithmetic for signed PCM16 RMS. Executed arithmetic reproduction: alternating +/-1 quiet samples produce 1.0 instead of 0.000030517578125. Foreground endpointer can treat quiet noise as speech and wait until the hard recording cap.
2. **Hands-free cancellation is not implemented end-to-end:** background capture is paused during agent work/TTS, while foreground capture waits sequentially. UI Stop has improved cancellation; spoken Stop cannot be promised during those phases.
3. **Speech safety/feedback defects:** foreground capture lacks the service's call-audio gate; native STT/TTS/KWS initialization bypasses modelmanager integrity verification; TTS can ignore playback failure, truncate long playback at 30 seconds while returning success, and omit cleanup on coroutine cancellation.
4. **Intent accuracy defects:** inherited substring parsing can propose actions for negated/quoted instructions. Wake punctuation and prefixed confirmations differ between ingress paths. Named contact accuracy and recipient resolution are not measured.
5. **Browser runtime absent:** APK generation does not enforce existence of the separately generated Page Agent bundle.
6. **Browser bridge trust boundary:** the injected agent and admitted page scripts share a JS world with readable session data and a page-visible native bridge. Same-origin checks do not distinguish hostile same-page scripts from the trusted agent. This is a critical source-level design issue; no attack was executed on a real user's page.
7. **Browser unsafe action lifecycle:** generic clicks can bypass form-specific confirmation, indexes lack snapshot identity, Stop does not reliably revoke native inference/pending authorization, and authorization is narrated as execution before DOM effects occur.
8. **Android autonomy remains largely unwired:** general runNativeGoals has no production caller; single goals use deterministic open/find/search-focus/edit paths. Vision has no working app provider; new screenshot diagnostics deny by default. SkillsV2 review/execution integration and partner UnoBridge provider are absent. Qwen/AppFunctions are explicitly unavailable.
9. **V2 regression risk:** generic mutations were blocked for safety without an equivalent V3 navigation route. This is not preservation of all previous capability and must be reconciled explicitly, not hidden behind green DTO tests.
10. **Residual model exclusivity race:** ownership is checked before a suspending phone load rather than at allocation under the shared transition lock. Operation serialization alone does not guarantee single engine residency.

## Original acceptance A–J, faithfully mapped

| Test | Current review result |
|---|---|
| A — Open Gmail, verified foreground | Native bounded path exists; physical result pending. |
| B — Read screen via Accessibility/OCR/offline TTS | Existing path exists, but speech playback/observation gaps and physical qualification remain. |
| C — Click Search with semantic action and verification | Current explicit Click is restricted search-field focus; not a qualified generic Search-button click. |
| D — Find WhatsApp chat with Pankaj | End-to-end navigation/contact identity not implemented or qualified by current exact-text Find. |
| E — Draft exact message without Send | Existing review intent exists, but foreground-only verification does not prove recipient/composer/body. |
| F — Find UnoOne battery settings | General multi-step Settings navigation is not user-reachable/qualified. |
| G — Unlabeled visual control grounding and verified action | Real image API/test code exists; production pixels/grounding/action path is unavailable/unqualified. |
| H — Gmail information to Calendar draft | No demonstrated or complete wired cross-app workflow. |
| I — Airplane-mode core operation | No physical test; source-local inference intent is not a measured offline pass. |
| J — Cancel halfway, all activity stops | Native UI cancellation improved; spoken cancellation and browser cancellation remain blockers. |

## Repair and acceptance order

1. Repair signed PCM/endpointer and TTS error/cancellation handling; unify bounded wake/confirmation/stop ingress, call-mode checks and verified speech model loading. Keep emergency audio stop as a real tested path rather than a UI-only substitute.
2. Fix lint and dependency access; rerun all failed test suites without weakening/skipping them. Make missing browser assets a build failure for agent-enabled artifacts.
3. Redesign the browser trust boundary, bind native task/document/action identities, revoke all pending work on Stop, and separate authorization from observed execution. Do not solve this by disabling ordinary network browsing.
4. Wire a reachable native-goal controller with safe semantic search/navigation/recovery and working image observation under a documented privacy policy; reconcile V2 functionality regressions. No arbitrary model Done success.
5. Integrate reviewed executable skills and native app capabilities, then assess model alternatives with the identical on-device task set. No fake Qwen backend.
6. Run consented English/Hindi/Hinglish human-audio tests: raw/normalized WER and CER, wake false accepts/misses, endpoint/truncation, intent and exact slot/recipient identity, plus stop latency. Text aliases and synthetic round trips alone do not measure speech accuracy. Dataset size/threshold proposals in the speech appendix are proposals, not collected evidence.
7. Run A–J and the 100-task/50-task sustained Xiaomi suites against exact source/APK/model/runtime identity. Measure wrong actions and false success separately from average success, plus RAM/thermal/battery/crash/ANR. Keep release claims scoped to that evidence.

No realistic engineering process can certify perfection across every Android app/version/accent. A defensible expert assistant is one that succeeds on measured supported workflows, detects uncertainty, stops safely, requests the right approval, and never falsely announces completion.

# Detailed independent reviews


---

# Cadence — deep speech audit (read-only)

## Scope and evidentiary limits

Repository `/agent/workspace/UnoOne-Local-Agent`; HEAD/base `69bf5e097b85a1bc40da14c07609dde8ca765cbd`. Reviewed the working-tree voice path, speech-related V3 orchestration/native routing and model-integrity changes. **CommandParser.kt and RuleBasedParser.kt have no diff against 69bf5e0** at review time; their defects below are inherited, not invented V3 changes. Voice sources likewise were not on the changed-file list. This is a source/control-flow audit, not physical qualification, not an exhaustive audit of every unrelated V3 file. No production source modified; no Gradle, model loading, downloads, inference or device tests run. One tiny host arithmetic reproduction was run, described below. Existing build outputs are not evidence of this working tree passing. SHA-256 appendix binds the relevant reviewed changed sources and core speech sources.

Paths below are relative to `android-app/UnoOneAgent/` unless indicated otherwise. Line numbers refer to this working-tree snapshot. Severity: P1 = high-impact correctness/accessibility/privacy defect; P2 = functional/reliability defect. “Reproduce” means exact proposed test/control-flow reproduction unless explicitly marked executed.

## Executive conclusion

Do not claim reliable English/Hindi/Hinglish speech yet. There is a deterministic signed-PCM amplitude bug, no usable hands-free stop while inference/TTS owns audio, incomplete foreground call interruption protection, and speech loading does not enforce the new model health checks. Text alias/routing tests and synthesized script checks cannot establish human speech accuracy. V3 cancellation improves UI-triggered safety, but does not supply the missing spoken emergency-stop route.

## Confirmed defects

### P1 S1 — Microphone RMS treats negative PCM16 as unsigned; foreground endpointer sees near-silence as full speech (inherited)

Evidence: `voice/.../recorder/AudioRecorder.kt:84–90` combines bytes into an integer 0..65535, squares it without conversion to signed short, then clamps RMS to 1. `voice/.../AdaptiveSpeechDetector.kt:16–18` correctly sign-converts; these two VAD paths disagree. `app/.../ui/viewmodel/AgentViewModel.kt:552–585` relies on recorder amplitude with threshold 0.018, 850 ms trailing silence and 8 s maximum (`VoiceActivityPolicy.kt:7–9`).

**Executed host arithmetic reproduction:** alternating PCM samples -1,+1 should have RMS `0.000030517578125`; recorder's exact arithmetic yields clamped amplitude **1.0**. A real quiet mic with roughly symmetric tiny negative noise therefore resets `silenceSince`, frequently forcing the eight-second cap instead of early endpoint. The PCM passed to ASR is not itself sign-corrupted: this is segmentation/latency/truncation, not proof of wrong acoustic decoding.

Exact regression: extract/test signed PCM RMS with zeros, +/-1, +/-32767, -32768, sinusoidal 0.005 and odd-length guarded input. Feed 600 ms voiced PCM then 1.2 s +/-1 noise through the actual amplitude/endpointer adapter; assert capture ends after trailing silence, not max duration; compare service and foreground paths.

### P1 S2 — Spoken stop cannot interrupt inference/TTS; V3's good cancellation primitive is not reachable by voice

Evidence: `VoiceService.kt:348–365` stops recorder and clears state while agent speech or foreground work is active, except confirmation (speech still hard-stops). `UnoOneApplication.kt:233–243` special-cases only pending confirmation, then enqueues a regular command. `AgentViewModel.kt:491–519` captures sequentially, awaits `processCommand` and completion before recording again. Its STOP_PHRASES check at 498 can run only between tasks. `AgentOrchestrator.kt:703–715` takes ownership before normal routing and rejects work while processing; `RuleBasedParser` has blind-aid stop rules, not a global cancellation rule.

Reproduce: wake “Uno open …” or ask a long explanation; while inference or response is ongoing say “Uno stop”, “stop”, or “रुको”. Background microphone is deliberately off; foreground capture is waiting, so nothing can invoke `cancelCurrentCommand`. Injected `postVoiceCommand("stop")` without a pending prompt queues behind work rather than cancelling it. During a pending prompt “stop” can deny that prompt, a different feature.

V3 positive: `AgentOrchestrator.kt:1765–1789` invalidates device epoch and pending approval, cancels child, cancels LLM inference, stops audio; wrapper at 703–731 waits for real child termination before releasing ownership. This supports UI stop, not audio barge-in. Native TTS generation remains non-cancellable but its epoch suppresses stale playback. Do not promise bounded cancel-to-idle latency without native timing tests.

Required design/test: narrowly scoped high-priority stop detector plus immediate stop dispatch outside the command queue/processing lock, with echo rejection, rather than enabling full unrestricted recognition of agent TTS. Measure stop-to-audio-off and stop-to-last-side-effect p50/p95/max at inference, confirmation, synthesis, playback and device dispatch boundaries. Assert no new action after cancellation epoch.

### P1 S3 — Call audio gate only protects background service, not foreground recording

Evidence: `VoiceService.kt:319–343` polls MODE_IN_CALL/MODE_IN_COMMUNICATION and resets buffers. `VoiceCapturePolicy.kt:13–15` covers those two modes. `VoiceModule.kt:192–236` checks enabled/model/mic permission but **no call mode**; `AudioRecorder.kt:40–98` also has no call gate. Foreground `captureUtterance` at AgentViewModel 552–585 never checks call status.

Reproduce: start in-app Listen or a hands-free utterance, then enter a cellular/VoIP call; or press Listen while a call is active. App continues its recording state and may submit collected data. Whether Android supplies silence, local voice or mixed audio is device/OS dependent—**not claiming guaranteed remote-party recording**. Missing policy enforcement is deterministic and contradicts service privacy intent. Test start-during-call and mid-capture transitions on all capture entry points, including floating UI and voice test; require recorder release, PCM discard and no transcript/intent dispatch. Also test MODE_NORMAL restoration and mic permission revocation mid-read.

### P1/P2 S4 — Speech initialization bypasses model-integrity checks strengthened in V3

Evidence: `UnoOneApplication.kt:136–145` calls shared `reinitForLanguage` directly using model directory; `VoiceModule.kt:139–160` initializes native speech directly. `SherpaSttEngine.kt:97–105,163–181` and `SherpaTtsEngine.kt:65–95` resolve/existence-check files, then load; `KeywordSpotter.kt:39–53,82` existence-checks and validates keyword tokens but not artifact hashes. None obtains a verified speech lease/health result before native initialization.

V3 checks are real: `ModelManager.kt:62–94` verifies archives/nonarchives; `ModelInstaller.kt:239–302` uses receipt tied to archive identity plus full extracted-file rehash, verified staging and controlled renames. Those checks are **not a universal runtime speech-load gate**.

Reproduce safely with fake loader/file resolver: replace installed speech model with same-name wrong bytes, then invoke app speech initialization; native load is attempted without `ModelManager.health` rejection. Do not corrupt/load a real model during this audit. Hash mismatch may produce a handled error, native fatal abort, or a different model depending on corruption; the exact native outcome is not established. Require verified bundle binding for all STT/TTS/KWS paths, and test same-size corruption, missing tokens, altered extracted outputs, wrong family, damaged espeak tree and missing receipt. No actual installed device audio artifacts were accessed or hashed here.

### P2 S5 — TTS falsely reports success when PCM player explicitly reports failure

Evidence: `SherpaTtsEngine.kt:117–130` ignores `player.playPcm(...)` return value and unconditionally returns Success. `TtsPlayer.kt:135–139` can reject effectively silent output; 192–194 can return playback failure. Thus synthesis != audible feedback, including confirmation prompts.

Reproduce with nonempty all-zero SynthesizedSpeech or injected failing AudioTrack: `playPcm` returns Error, `speakAtEpoch` returns Success and `speakAwait` delays as if played. Assert failure propagation, zero false audible-success claims and deterministic fallback/retry policy.

### P2 S6 — Long offline replies are truncated at 30 seconds and cancellation cleanup is incomplete

Evidence: `SherpaTtsEngine.kt:162–170` delays `min(duration+100ms, timeoutMs=30000)` then calls `finishPcmPlayback`, which releases track (`TtsPlayer.kt:232–235,250–265`). A generated 45-second paragraph is stopped at 30 seconds while reporting Success. Cancellation during delay skips finish because no finally exists. Explicit `stopSpeaking` correctly stops track via epoch/player; plain coroutine cancellation alone is not equivalent.

Reproduce using synthetic 45 s non-silent PCM and controllable clock/player; expect either all content or explicit timeout/error, never silently successful truncation. Cancel speakAwait after playback begins without calling stop; assert focus/track released. Also `TtsPlayer.kt:253` compares `track.state` to PLAYSTATE_PLAYING instead of `track.playState`; release generally stops audio but the explicit stop branch is wrong.

Sample-rate review: production path correctly passes generated `audio.sampleRate` to `playPcm` and AudioTrack (`SherpaTtsEngine.kt:123`; `TtsPlayer.kt:165–179`). No hardcoded 22050 playback bug found. STT expects raw mono PCM16 at 16 kHz. Round-trip test explicitly resamples; `SynthesizedSpeech.toPcm16()` alone does not, so fixture users must honor rate metadata.

### P2 S7 — Localized parser substring matches execute negated/embedded commands

Evidence: `RuleBasedParser.kt:89–94` calls localized parser first; 484–485 defines `hasAny` as contains; 570–579 turns any matching substring into app launch. No negation/context gate.

Exact reproduction: “क्रोम खोलो मत” returns `open_chrome`; “व्हाट्सऐप खोलो मत” returns open_app WhatsApp; “पंकज ने कहा क्रोम खोलो” also matches launch despite reported speech. The source proves proposed intent; execution still goes through whatever permissions/safety apply. Add negative/quoted/compound Hindi/Hinglish tests and require an explicit imperative parse or clarification instead of substring authority. The English launch alias similarly uses unanchored containsMatchIn (`RuleBasedParser.kt:31–35`), so “don't open chrome” can match Chrome launch when it reaches that branch.

### P2 S8 — Wake matcher rejects ordinary punctuation and common misrecognized confirmation aliases

Evidence: `WakePhraseNormalizer.kt:20` intentionally retains '.', '@', '+' for payload, while `WakePhraseMatcher.kt:89–91` demands exact prefix or prefix followed by space. Thus “Uno. open Chrome” / “Uno. yes” has first token `uno.` and can use fuzzy fallback for safe action tokens, but “yes” and “confirm” are absent from `safeAliasContinuations` (73–77); “Uno. confirm” fails matching. “you know confirm” and “you know yes” also fail by 97–99. “UnoOne. open Chrome” has no exact prefix and fuzzy fallback rejects long first token. Plain “Uno confirm” works after prefix stripping.

Reproduce matcher-only cases above, plus Hindi danda, commas and proper-noun punctuation in payload. Normalize punctuation at wake-prefix boundary without destroying message/email/URL body. Permit carefully constrained confirmation aliases **only in pending-confirmation state**, not globally in ambient speech.

### P2 S9 — Foreground confirmations containing wake prefix are not normalized before decision

Evidence: background VoiceService strips matched wake phrase (466/516/545), but foreground `AgentViewModel.kt:157–172` forwards result.data unchanged to processCommand; `VoiceConfirmationPolicy.kt:20–35` normalizes punctuation only and recognizes exact “yes”/“confirm”, not “uno yes”/“uno confirm”. `resolvePendingVoiceConfirmation` at Orchestrator 1662–1665 does not strip wake prefix. Confirmation prompt explicitly instructs “Uno yes/confirm” (`VoiceConfirmationPolicy.kt:13–17`).

Reproduce directly against the pending decision route: resolve("confirm") -> recognized; resolve("Uno confirm") -> false. A foreground injected full transcript is then rejected by active processing lock or enqueued depending caller. Background acoustic route works if the wake matcher succeeds and narration has finished; do not conflate these paths. Add shared transcript-envelope normalization at ingress and tests for both callers, preserving exact bounded decision policy.

## Other high-value limitations (not presented as measured acoustic failures)

- Background wake begins only after 500 ms chunks, uses >=1 s KWS chunks, ~1 s trailing silence; passive STT fallback buffer is 8 s, command buffer 15 s. Foreground cap is 8 s and 850 ms silence. Long dictation cuts at caps, and internal Hindi pauses may segment; quantify real corpus failures rather than assuming perfect endpointer. Passive speech buffer begins with first energetic chunk, no dedicated pre-roll; quiet syllable loss is a risk requiring audio fixtures.
- `KeywordSpotter.kt:104–106` calls decode once per 1-second append, not `while(isReady)` drain. Streaming backlog/wake latency is a **native API/model-dependent risk** to check with clocked fixtures; not asserted as observed failure. Pause/call branches clear PCM accumulators but do not reset KWS native stream; test old partial “Uno” bridging a call or TTS pause.
- `VoiceService.kt:277–279` snapshots STT availability once. Cold startup can start KWS before shared STT initialization; then STT wake fallback remains disabled for that loop. If neither KWS nor STT is ready at monitoring launch, it exits with monitoringStarted already true (206–225,257–267) and ordinary starts do not retry. Reproduce with delayed fake STT init and absent KWS. This is a concrete lifecycle risk dependent on installation/init ordering.
- TtsPlayer's system fallback is automatic when offline TTS absent (`VoiceModule.kt:182–188`), not opt-in like STT; no local-voice/network flag restriction was seen. Cannot certify fully offline for this fallback. It also queues only text before onInit, losing requested language (`TtsPlayer.kt:75–78,88–92`), so a queued Hindi request later defaults en-IN. Completion callback is global, registered after speaking, timeout thread clears it, and no cancellation cleanup exists (202–219): add concurrent/short-utterance/stop/init-failure tests.
- Mic ownership is a shared flag and service polling, not an acknowledged release handshake: foreground start can race the background AudioRecord for up to a loop period. Treat exact device effect as unqualified; test both active recorders count and wake-to-foreground handoff under load.
- TranscriptQuality is a text/duration heuristic, not posterior confidence (`TranscriptQuality.kt:6–9,20–39`). Background `transcribePcm` stores but does not gate confidence; foreground push-to-talk retries once, then accepts repeated low score (AgentViewModel 159–172). A convincing wrong contact name can score well. Hindi “हाँ” has one Unicode letter plus marks and is penalized to 0.2 by lexical filtering (16–18); check UI path against configured threshold, not acoustic uncertainty.

## Actual route examples and language scope

| Input / state | Source-traced route / expected boundary |
|---|---|
| “Uno open Chrome” one breath | Service retains speech burst after KWS; ASR transcript must confirm wake; strips prefix -> postVoiceCommand -> native V3 OpenApp if installed app resolves, before legacy parser. Does not prove audio recognized correctly. |
| “Uno” then command | Decode wake-only -> stop recording -> awaited cue -> command listening. Speaking command over cue is intentionally not captured. |
| “Uno yes” / “Uno confirm” pending prompt | Background strips wake; exact decision outside serial queue works after narration. Ordinary yes does not authorize strong-confirm requirement. Early speech during narration is lost because agent speech overrides confirmation mic exception. Foreground full-prefix variant is S9. |
| Stop during inference/TTS | S2; UI cancellation exists, live spoken interruption does not. |
| “क्रोम खोलो” | Native English-only regex does not match; deterministic localized parser -> open_chrome legacy route. V3 verified native route and legacy route differ by phrasing/language. Test same postcondition/safety behavior for both. |
| “Pankaj ko WhatsApp message bhejo ki Madhav aa gaya” | Contains whatsapp + message -> RuleBasedParser 168–183; no numeric recipient -> empty number and recipient picker, message extraction can include `bhejo ki Madhav aa gaya`; not a resolved Pankaj contact. No evidence names are acoustically preserved. |
| “पंकज को व्हाट्सऐप पर मैसेज लिखो कि माधव आ गया” | Localized rule 557–567 extracts text after कि; no number -> picker. Hindi/English names must be scored as entity slots; translating/transliterating should never silently bind a different person. |
| “call Pankaj” / “माधव को फोन करो” | No deterministic call/contact-name branch found in RuleBasedParser; model-dependent proposal is not proven expert phone control. Must clarify/resolve contact identity through reviewed phone capability, not guess a number. |

**Supported != catalogue != translations:** `VoiceLanguage.SUPPORTED` is only English/Hindi (26–34); input ASR is one Omnilingual CTC `auto` independent of selected reply language (43–53). Whisper is compatibility/evaluation; English transducer is available/KWS fallback, not primary bilingual recognizer. Manifest also retains Bengali/Tamil/Telugu/Kannada/Malayalam TTS entries; localized rules include those languages. Those entries and translated phrase lists are NOT product-supported/qualified speech. “1600 languages” is upstream artifact naming, not 1600 proven product languages or proven code-switch accuracy.

Model catalogue uses exact size/hash for speech files and archive; English KWS and transducer share identical artifact descriptors. URLs largely resolve/main but pinned hashes cause changed bytes to fail verification rather than implicitly upgrade. Actual device artifact integrity and acoustic capability remain unmeasured.

## Existing evidence and missing accuracy harness

Read tests include `HandsFreeVoiceActivationTest.kt:41–58` (injects an already-decoded Hindi string into service), `VoiceCommandToolExecutionDeviceTest.kt:49–74` (injects English alias and directly resolves confirm), `IndicSpeechRoundTripTest.kt:31–34,69–110` (two synthesized sentences and >=60% expected-script letters). Last test can pass a totally wrong Hindi sentence if it uses Hindi script; it also copies evaluation files into production directories at 44–50, so is not a pristine installed-bundle qualification. None tests human mic recognition, wake+command waveform, WER/CER, entity slots or barge-in. Pure tests cover matcher, VAD policy/adaptive detector, accumulator, language mapping, resolver and confirmation policy, but not actual AudioRecorder RMS integration. No Pankaj/Madhav source test found. `evaluation/` contains device-agent JSON fixtures/validator, not a recorded-speech corpus; no WER harness found in searched scripts. Search coverage is not a proof no external corpus exists.

### Exact proposed harness (do not implement in this audit)

Add `evaluation/speech/manifest.jsonl`, a hash-bound consented recorded corpus and a host scorer; add an Android replay runner that exercises production PCM, segmentation, wake normalization and parser ingress without executing real side effects. Separate offline PCM replay from loudspeaker-to-phone mic trials. Each record: id, audio SHA256, speaker/session split, language/script, sample_rate/channels/encoding, reference verbatim transcript, accepted orthographic alternatives, reference wake span/time, reference command span/time, expected intent, slot values and stable contact fixture IDs, noise/SNR/distance/device/channel, and expected deny/clarify. Never use real personal contacts or messages.

Initial locked test set: at least 20 speakers × 30 consented utterances, balanced English/Hindi/Hinglish (600 clips), distinct development speakers; plus non-wake ambient negatives measured in hours. Include regional English/Hindi accents, quiet/traffic/fan/TV conditions, 0.3/1/3 m distance, whisper/normal speech, silence-only, music, overlapping speech, interruptions and 0.3/0.8/1.2/2.0 s within-command pauses. Include Pankaj/पंकज and Madhav/माधव plus confusable alternatives, names in sender/recipient/body, mixed-script outputs, phone digits, email punctuation and negated commands.

Report raw and explicitly normalized WER and CER separately by language/speaker/noise, with insertion/deletion/substitution counts. Unicode normalization must preserve meaningful Hindi marks; transliteration scoring is an additional diagnostic, never an excuse to hide raw errors. Compute wake false accepts/hour and miss rate, command endpoint onset/offset error, truncation rate, intent accuracy, slot exact match, contact-ID exact match and end-to-end authorized task success. Wrong-recipient/action and false-approval counts get their own safety gate; do not average them away. Include latency distribution and memory/thermal sustained run; bind APK/source/model hashes, OS, device and audio route. Set release thresholds with user/product owner against a locked baseline, not invented “perfect” targets; require zero observed unauthorized action/false approval in the safety set while acknowledging finite-test limitations.

## Regression matrix

| Priority | Regression | Assertions |
|---|---|---|
| P1 | PCM signed samples -> foreground VAD | +/-1 remains silence; no cap-induced unnecessary delay; same endpoint across UI and service policies where designed. |
| P1 | Stop under inference, synth, playback, device action | Cancel route bypasses queue; no post-epoch action; stale PCM not replayed; audio/focus stop measured. |
| P1 | Call begins before/during each recording entry | Reject or release mic, clear PCM, no dispatch, safe rearm only after call ends. |
| P1 | Installed bundle corruption with fake native loader | Every STT/TTS/KWS load blocked before native call on invalid hashes/receipt/family. |
| P1 | Hindi/English negation and quoted instructions | No launch/tool proposal from “क्रोम खोलो मत”, “don't open chrome”, reported speech. |
| P2 | Wake punctuation/aliases + commands | Uno/UnoOne/यूनो, punctuation, one breath/two turns, no command prefix loss; unrelated ambient remains negative. |
| P2 | Confirmation every ingress | “Uno yes/confirm” and stripped equivalents; deny yes for strong; reject “yes open Chrome”; pending epoch/race and timeout. |
| P2 | TTS duration/rate/error/cancel | 16k/22.05k actual rate; 45 s content not success-truncated; failed player propagates; cancel always frees focus. |
| P2 | Cold init/model install races | STT fallback becomes available after late init; monitoring restarts after unavailable state. |
| P2 | Pankaj/Madhav English/Hindi/Hinglish corpus | WER/CER plus recipient/contact-ID/body exact match; no invented name-to-phone resolution. |
| P2 | 8/15 s bounds, internal pauses, noise | No silently partial high-risk instruction; explicit retry/clarification after truncation. |
| P2 | Same app action in three languages/aliases | Native vs legacy route parity for permissions, confirmation and verified outcome. |

## Reviewed-source SHA-256 appendix

Hashes follow below. They identify snapshot bytes, not proof that tests ran or a device contains them.

```text
0822fb6e0d12f1e6a7f54b402d84bfdbf928473659f3778db27eaa194200d938  [baseline] android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/languagepacks/HandsFreeVoiceActivationTest.kt
d7f9c4c5b46247188d0c76eb117389d2f6c1613fabdcdb2f258e45b5526d136c  [baseline] android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/languagepacks/IndicSpeechRoundTripTest.kt
e6119ea16bcde97af33c580bb72de538875a1f09c9b27059416f06ff3554a2a0  [baseline] android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/languagepacks/VoiceCommandToolExecutionDeviceTest.kt
bf10a046b3942eab477c47aa4b43e4fb8a11a754394a940c5d8fbbf95fcd693c  [changed] android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/AgentOrchestrator.kt
59f8eb32c37a7f01e2c20b5ac36caa446565cb4056dad14a16310292a22f2d70  [untracked-new] android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/NativeDeviceGoal.kt
e4d6b4d8ed8e37a8c51c220b8740235aa3e1a37eb6836a0a5c4444d042cc181c  [changed] android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/UnoOneApplication.kt
6ae6ca68a9d6324a822705fa17dde335c628a2900157b0cc99997974df2d7c67  [baseline] android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/parsing/CommandParser.kt
90fa9fba4890c76c1186a0fc8f672cba919e1174ab402cd22bfa5a5f5e15f4a8  [baseline] android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/viewmodel/AgentViewModel.kt
3081966b815d95fe28f9a6cb7d1096c0f654f94ca930a3c61f83889e65f9c6b8  [baseline] android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/RuleBasedParser.kt
64e7976f1dc9b171cb4536c44ea021ad75b55b13896e998b9ba7fed1b3ceafd4  [changed] android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelInstaller.kt
e670cb7c77091bd6e98d493cb0ce7de26aa0e29afc019b62193842091480deb1  [changed] android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelManager.kt
67a7c86a70a9711b4d26e859e352a672ea0a7ba99eafc4da4e6f46c51d821d21  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/AdaptiveSpeechDetector.kt
bf9e7bc8c3ee442bbc8f5041146c7cf4cec7f678d03f4075a32e77ca590a20c9  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/PcmChunkAccumulator.kt
4a5366385f58b0d93db7f7173b4c71d0fd2545660eda4c54a021ef46e0469f94  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/VoiceActivityPolicy.kt
233463610d990be384f27260578c77a9573d14e023c908c3932f27c031062876  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/VoiceCapturePolicy.kt
c19563cfafa7ed20361fe39cf10da2c714b1d7b59caff700671adc3ca01a5181  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/VoiceConfirmationPolicy.kt
3bcc0d7e6f517f16bfc83d788f699cc7361d554ced0b213b2bb0304f435de327  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/VoiceLanguage.kt
8add77aefa9658566ff07b1118dfb0a7657ed2925be1fd88b613616604e7da70  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/VoiceModule.kt
6278f896f214c91bd30e0d38653339cac825baeda1de33d487a78e718c8dd63f  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/VoiceService.kt
6776a15ae4e4369240a8319dd2a4a0e1f8bff851e7c76827b110d88a77a7d21f  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/WakePhraseMatcher.kt
33827c62c970a38305246f2dcca607087829a3cc419a70526acf7735c9306042  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/WakePhraseNormalizer.kt
6aefb32f4090b1bd3f75f24206f9f551a01cebac2a60031d8f285c052d593373  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/recorder/AudioRecorder.kt
0246de2daf9c46442f09c49196285d441559bc6adf7e23d8b4f664d726eebb29  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/stt/KeywordSpotter.kt
a22b0ca48aeb16841b930b79238728c899d11ebe1b590e890d6b6be7509707f8  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/stt/SherpaSttEngine.kt
b2c4dbb15a7749a65c3c36afd497302622f02719a1716a21940963970a8d0410  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/stt/TranscriptQuality.kt
701d809702d85ba55aec9de5fb617bf636cd51028639eb277005a9265222aac1  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/tts/SherpaTtsEngine.kt
9b322ab65c036d7cbc631db6d5a674a74d8d10f37d185aca7b6146f8fa023224  [baseline] android-app/UnoOneAgent/voice/src/main/java/com/unoone/agent/voice/tts/TtsPlayer.kt
058d55c5679c7a181e88771db6ca4c3bf9f71b06f580cd0816d36f77c530322f  [changed] android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json
```


---

# Compass — deep current V3 Android controller review

## Verdict and evidence boundary

**Do not accept V3 as an expert autonomous phone controller.** The reachable V3 lane is a small deterministic native-goal executor (open, exact visible find, search-field focus, confirmed search-field edit). The model controller is implemented but dormant; arbitrary Gmail/WhatsApp/Calendar/Settings workflows are not integrated. Safety is materially improved, but some improvements remove working V2 capability rather than replace it. Legacy intent tools, reading, prompt skills and outcome memory remain a separate reachable lane with weaker completion/privacy semantics.

Read-only source/call-site review against `69bf5e097b85a1bc40da14c07609dde8ca765cbd` (HEAD equals that baseline). Included tracked modifications **and untracked V3 sources**, not just `git diff`. No Gradle, compilation, test execution, model loading, Android/device execution, or repository edits. Only this requested report was written. Reproductions below are source-derived minimal test cases, not claims of observed device behavior. Hash inventory at end binds current content; capture is non-atomic. This bounded review traces the requested controller subsystem deeply, not every line of installer/web/runtime changes.

Paths below are relative to `android-app/UnoOneAgent`; `app/.../` means `app/src/main/java/com/unoone/agent/`, and corresponding module `...` means its package source root.

## Reachability trace

1. `AgentOrchestrator.kt:698–729` owns one lazy async command child, joins it before releasing the lock. `:767–784` parses native intent before skills/chat/context capture.
2. `NativeDeviceGoal.kt:23–56` recognizes bare single-token `open/launch` app names, or explicit `device:` find/click/set syntax. Other explicit device commands hand over. Other prose returns null and reaches the **old** rules/chat/Gemma tool pipeline.
3. `DeviceAgentSession.kt:27` accepts `useModelPlanner`, but never uses it. `:58–87` constructs a deterministic planner; `:89–92` runs it with observe consent and only the requested package. `:102–114` has the real model-based `runNativeGoals` API, but whole-tree Kotlin search finds only its declaration. The session is private in the orchestrator. The lazy Gemma brain at orchestrator `:683–688` therefore is not exercised by reachable native goals.
4. `AndroidDeviceAdapter.kt:27–60` captures only active-window accessibility (bounded 256 nodes, depth 24, strings 256), with no OCR/image fusion. Native semantics come from `NativeReviewedTargets`, only three search-field resource patterns (`NativeDeviceGoal.kt:73–78`). All other nodes are UNKNOWN.
5. `DeviceAgentLoop.kt:85–155` observes, evaluates native predicate, proposes one typed action, checks safety/confirmation, dispatches, settles and reobserves. `Done` is explicitly NOT native success (`:104`); accepted dispatch and UI change are also not task success. Epoch/master guards and adapter issued-snapshot, sequence and live-signature checks are real.
6. Legacy `ActionExecutor` still supports launch intents, calendar provider reading, screen reading, notes, web search, browser tasks, legacy skills. `ToolCallValidator` rejects generic click/type/fill/swipe/long-press/find-and-click. No automatic conversion to native V3 replacements exists.
7. `SkillsV2Runner` and `SkillsV2Store` have no app production call sites found. App still constructs `SkillsModule` (`AgentOrchestrator:205`), finds legacy triggers (`:843–846`) and learns from `Result.Success` (`:1553–1566`). V3 native-goal early return bypasses this learning pipeline. Native workflow review/approval/version selection is not integrated.

## Severity findings and minimal repros

### P1 product blocker — general autonomy is not reachable; V2 mutations are retired without parity

**Evidence:** `DeviceAgentSession:27,58–92,102–114`; `NativeDeviceGoal:54–56,73–78`; `ToolCallValidator:52–56`; `AccessibilityControl:14–20,56–63,93`; service `:49–55,95–97`.

**Repro:** enter `device: open Gmail and read the latest email` → NeedsUser before observing. Enter `device: click Battery in Settings` with Settings already visible → matching Battery node is not an editable reviewed FORM_FIELD, so handover. Enter old supported `click Battery`/`find and click Battery` → legacy mutation rejection, not native execution. With `useModelPlanner=true`, deterministic behavior is unchanged.

**Impact:** no Gmail latest-message discovery/open/read pipeline, WhatsApp contact search/draft navigation, Calendar cross-app extraction/insertion, Settings battery navigation or unlabeled-icon control. This is safe denial, not unsafe behavior; it nonetheless defeats the requested expert-phone-control acceptance. Do not weaken UNKNOWN policy or trust labels to conceal it. Ship a clearly scoped preview or implement reviewed navigation semantics/native task predicates and deliberate model-loop entry.

### P1 privacy requirement gap — new screenshot safety does not cover reachable legacy reading/context

**Evidence:** `ScreenPerceptionProvider:50–53` defaults to denying full-screen capture without native exclusion proof. In contrast `OcrControl:65–71` sends the raw captured bitmap to OCR; `ActionExecutor:429–436` returns its content. `CommandParser:158–169` can invoke the same OCR fallback when screen-relevant context has no accessibility text. `UnoOneAccessibilityService:58–61` uses a default UNKNOWN-semantic adapter for read_screen; `AndroidDeviceAdapter:49,70–75` masks password/native-sensitive nodes but cannot identify OTP/plaintext credentials in UNKNOWN nodes.

**Repro:** show synthetic OTP/credential text in a canvas or non-password TextView; grant projection; request OCR/read screen (or a screen-referencing model plan with empty accessibility text). Legacy path recognizes/returns the text without the new full-screen exclusion gate. No external exfiltration is asserted; local exposure/model context/speech is enough to disprove universal sensitive-screen exclusion.

**Impact:** V3 diagnostics being safely blocked does not mean all app screen processing is private under the new contract. Preserve user-requested reading where appropriate, but explicitly define the privacy scope and implement native redaction/consent consistently; do not claim full-screen secret exclusion globally.

### P2 definite compatibility gap — installed-app discovery is restricted by missing launcher visibility declaration

**Evidence:** `phonecontrol/.../AppRegistry.kt:18–23` queries MAIN + CATEGORY_LAUNCHER. `app/src/main/AndroidManifest.xml:49–72` declares a curated package list plus Calendar and mail intents, **not** MAIN/LAUNCHER visibility. On Android 11+ package visibility can filter otherwise-installed launchable apps.

**Repro:** install an unrelated launcher app with no existing visibility relationship (not in the queries list); `device: open <its.package>` passes through resolveLegacyName but discover may not return it, producing unavailable. Existing Gmail/WhatsApp entries are visible; this is not proof every app fails. Settings visibility can vary due system visibility exemptions and should not be asserted universally missing.

**Fix direction:** declare the appropriately scoped launcher query and test visibility without QUERY_ALL_PACKAGES. AppRegistry is a real wired resolver, not general installed-app coverage yet.

### P2 definite completion error — legacy draft claims prove only foreground package, not draft/recipient/content

**Evidence:** `ActionExecutor:122–139` claims email/WhatsApp draft opened; `:150–168` claims Calendar insert/opened. `verifyForegroundLaunch:312–330` only checks launch candidate against cached currentPackage. It does not validate composer, recipient, message body, event fields or a fresh root snapshot.

**Repro:** WhatsApp is already foreground but an invalid/unregistered number opens an error or leaves current screen unchanged; package still matches, so the helper can report “WhatsApp draft opened.” A mail app already foreground can similarly satisfy the helper while a composer fails. Even an accepted intent plus correct package is not content evidence.

**Impact:** normal user “draft WhatsApp to Pankaj” may use the legacy route and appear done although the requested draft is not established. Orchestrator `:1553–1566` records this `Result.Success` into outcome memory/skill usage, compounding false completion. Separate intent-dispatched/action-opened from verified draft predicates. No claim is made that WhatsApp automatically sends: manual send remains required.

### P2 — legacy scroll remains coordinate execution outside V3 policy; scope must be explicit

**Evidence:** `ToolCallValidator:7,52–56` allows scroll_up/down as navigation. Service `:65–89` computes full-root swipe coordinates, `:105–109` dispatches gesture with only master gate. V3 policy `DeviceSafety:91–92` categorically denies Swipe/visual clicks, and guarded native Scroll needs native semantics.

**Repro:** request `scroll down` on a horizontally/vertically interactive canvas, map or sensitive custom control; the old route swipes the center regardless of target semantics/authorized package. A drag may interact with content rather than scroll. Stop does not invalidate an already-issued legacy gesture via DeviceEpoch.

**Impact:** cannot claim every screen-changing gesture passes V3's target/snapshot/epoch boundary. This is retained V2 functionality, not a newly added exploit; avoid silently turning it into universal safe-scroll authority. Review actual scrollable-node targeting or truthfully separate legacy navigation mode.

### P2 latent model-controller blocker — five-second snapshot TTL rejects slow but valid local plans

**Evidence:** `DeviceAction:62–65` enforces 5,000ms; `GemmaE2BBrain:31` decodes against the original perception after inference; `DeviceAgentLoop:95–101` retries with another observation on any invalid/stale proposal, at most two retries. No post-planning native-equivalence refresh exists (the new refresh is confirmation-only).

**Repro:** fake brain takes 5,001ms to return a correct OpenApp or node action for a static screen, clock advances correspondingly. Every proposal becomes stale; three attempts fail (or deadline expires). This is deterministic at API level, though currently not user-reachable because the model-loop caller is absent.

**Impact:** an E2B/E4B phone whose controller latency exceeds 5s cannot execute any model-proposed action through this loop, even harmless OpenApp. Need measured latency policy and safe fresh target validation/replanning—not blind TTL inflation or stale replay.

### P2 — search focus is still reported with task-success UI; handover reasons are discarded

**Evidence:** `DeviceAgentSession:79–95` implements Click as FocusNode, with ACTION_VERIFIED reason; orchestrator `:777–782` maps every VERIFIED to DONE, “Device goal verified”, success log. `DeviceAgentLoop:106` replaces every AskUser.question with generic “User handover requested.”

**Repro:** `device: click Search in Gmail`, where the reviewed field is uniquely matched, only focuses a field yet is logged success; it does not tap/submit a search. Or request a missing/ambiguous target: specific “Target missing or ambiguous” / “no reviewed native semantics” is lost in the returned outcome.

**Impact:** narrow focus is honestly verified internally, but UI/metrics encourage wider completion interpretation; failure does not tell user how to recover. Add action-only status and retain a bounded native-safe reason (do not blindly render arbitrary model instructions).

### P2 latent/remaining lifecycle correctness — cancellation and diagnostics are not uniformly covered

`ActionExecutor:295–297` catches CancellationException as ordinary error. Orchestrator checks cancellation before execution (`:1542–1544`) but immediately records tool diagnostics/outcome memory after return (`:1549–1563`) without an explicit ensureActive there. Native lane is better guarded; do not generalize it to all old suspending tools. Existing diagnostics lacks ON_STOP clearing and optional interpreter/frame job ownership (prior review), but capture remains blocked and interpreter unwired, reducing present reachability. No hardware race proof is claimed.

## Natural-user scenario matrix

| User task | Actual reachable behavior | Acceptance status |
|---|---|---|
| “Open Gmail” | Native resolver → deterministic launch → fresh active root foreground predicate | Implemented/wired; device qualification absent |
| “Open Gmail and read latest mail” | Native parser returns null unless device-prefixed; ordinary prose uses old planner/rules. No reviewed inbox-row click, latest ordering, mail-body predicate. Explicit device version hands over | Expert task unavailable |
| “Draft WhatsApp to Pankaj: …” | V3 can open WhatsApp; reviewed search field may be focused/edited if already exposed. No contact-name-to-number/contact navigation capability added. Legacy number/message intent draft may work, not verified draft content | Partial old capability, not V3 expert workflow |
| “Take meeting details from Gmail into Calendar” | No user-facing conjunctive model/native-goal call, source extraction or reviewed cross-app verification. Legacy Calendar insert intent accepts supplied exact times, manual save | General cross-app task unavailable |
| “Open Settings → Battery” | Open may resolve; Battery is UNKNOWN/noneditable and cannot be clicked by native lane; old generic click disabled | Open only; navigation unavailable |
| “Read screen” | Legacy accessibility text, bounded and password-redacted; OCR tool separately captures raw image. Does not use Gemma V3 screenshot interpretation | Implemented old reading; privacy limits above |
| “Tap that unlabeled icon” | No image provider in GemmaE2BBrain; no fused perception in session; visual/coordinate actions denied | Unavailable, safe handover |
| Stop during pending edit | Retained epoch invalidated before child cancel; pending approval denied; owning child joined before next lock release | Source implemented; real confirmation/UI races unqualified |
| Airplane mode | Native accessibility/rules and already-installed local brain/OCR can be local. Gmail freshness/WhatsApp delivery depend on network; legacy web_search/browser remain network-capable pathways | Offline task proof and network audit not performed; do not promise latest mail/delivery offline |

## Requirement gap matrix A–J

**The delegation does not include the original definitions of acceptance A–J.** To avoid inventing exact requirement labels, these are reviewer trace buckets covering the requested subjects; the parent must map them to the original acceptance wording before signing off.

| Bucket | Source state | Gap / verdict |
|---|---|---|
| A — local model selection/runtime | Persisted E2B/E4B profile policy is now wired through startup, enable, recovery, model UI and browser selected-profile provider | Source fix present; not physical E2B/native qualification; no automatic load-failure fallback |
| B — user intent and native app execution | Open/find/search-focus/edit implemented and wired | Natural general device task routing absent; explicit unsupported requests hand over; old prose path differs |
| C — perception | Bounded immutable tree, redaction of known sensitive nodes, snapshot/event IDs, OCR/image envelope APIs | Active window only; 256 nodes/256-char truncation; no app model fusion; full-screen diagnostics denied |
| D — typed action/model/schema | Strict DeviceAction codec/validator and separate legacy canonical tool validator | Model loop dormant; schema advertises actions unavailable under current native semantics; latency/TTL gap |
| E — safety/confirmation/native completion | Native package scope, unknown/final-send deny, exact action receipt, postapproval equivalence refresh, native predicates | Legacy paths are not uniformly covered; no reviewed higher-level task predicates; focus UI success ambiguity |
| F — cancellation/lifecycle | Owned child, retained epoch, master gate, native unload acknowledgement/quarantine, browser lease restoration identity | Device/JNI late-callback stress not run; legacy exceptions/gesture and diagnostics lifetime gaps |
| G — multi-step/multi-app expertise | Loop budgets and runNativeGoals conjunction API exist | No production call site, no user task decomposition/authorized action navigation/postconditions |
| H — memory/SkillsV2 | Legacy memory/skills live; V2 structured workflow types/store/replay/runner implemented | No production SkillsV2 review/approval/runner or native-only statistics integration; old learning uses tool Result.Success |
| I — registry and integrations | AppRegistry wired with use-time exact/label/legacy resolution | Launcher visibility incomplete; current parser open regex excludes multiword names; UnoBridge/AppFunctions/Qwen not proven production capability |
| J — offline/evaluation/real-world acceptance | Bounded controller source, pending evaluation corpus and device docs | No current physical Gmail/WhatsApp/Calendar/Settings evidence, sustained-task proof, airplane/network audit or integrated build endorsement from this review |

## Model/tool/schema detail

- `GemmaE2BBrain:19–23` devicePlanning depends on actual loaded LocalBrain; vision additionally requires screenshotProvider and E2B. Orchestrator instantiates it **without** provider. `plan:25–31` is text-first; interpret/ground methods cannot magically capture images.
- `GemmaPlanner:326–349` creates a tool-less conversation, automaticToolCalling=false, rejects streamed/final native tool calls, uses raw text response decoded by strict DeviceActionCodec. This is a sound distinction from legacy planner canonical tool schemas (`:537–546`). It is not proof model responses obey schema on-device.
- `DeviceContextCompactor:144–147` marks screen data untrusted and quotes labels, but strips resource ID/package/capability/semantic detail from node lines and truncates context. Planner still sees many UNKNOWN nodes it cannot act on; prompt says use issued IDs but does not receive native authorization scope. Expect safe failures, not expert reasoning over complete action affordances.
- Typed validation does not grant authority. Native policy forbids unknown mutations, final-send, credentials/payment/OTP/captcha/legal/security/destructive targets and all visual/coordinate actions. Safety OFF/RELAXED does not override this lane. Known safety is positive; semantic coverage is extremely small.
- `NativeGoals.fieldEquals`/session predicates check observed matching fields, not external saved state. `runNativeGoals` requires every predicate true in the same current snapshot; predicates involving two different foreground packages are unsatisfiable, so this is not by itself a cross-app state-history workflow engine.

## Recheck of prior reviews after latest fixes

| Prior issue | Current recheck |
|---|---|
| final-review hardcoded E4B startup/UI/default blocker | **Source-resolved.** `UnoOneApplication:75–86` migrates only absent selection with verified E4B; `:183–186,319–322,367–369` use selected profile. ModelStatusVM `:165–170,239–295` selects/loads/tests selected spec. `BrainSelectionPolicy:5–9` retains legacy installation once, otherwise E2B default. This is explicit selection, NOT automatic E2B-failure fallback. |
| browser self-lease rejection/wrong restore identity | **Source-resolved.** Lease `:49–79` distinguishes resident brain and occupancy; `:181–186` restores prior exact path/spec. Selected-profile callback is now wired (`UnoOneApplication:161–163`). Real races/JNI not tested. |
| slow approval expires without refresh | **Source-resolved for exact unchanged UI.** Loop `:113–130`, DeviceSafety `:35–72` reobserve and privately rebind only identical native form target/full state. Changed UI/truncation safely hands over; confirmation dialog actual foreground behavior still requires hardware testing. |
| unused model flag/dead general loop, unwired vision | **Still present**, verified direct call-site search and source above. |
| diagnostics default-deny capture | **Still present**, deliberate safe unavailability. Do not “fix” by passing unproven true. |
| raw OCR logging | OCR success log removed (`OcrControl:29`); direct legacy raw OCR recognition still exists. |
| native close bookkeeping/quarantine | Repair present at `GemmaPlanner:511–530`: successful retained closes removed, uncertainty quarantines, READY only on idle/clean. No native callback/close correctness qualification here. |
| core clock/action freshness/approval/native done | Required monotonic clocks and strict current source boundary present. New latency gap applies to dormant model path. |
| Skills replay/end-guard | Current runner `:78–101` guards postobservation/success; replay `:117–138` binds reviewed digest/version/build and policy. Still dormant app feature, not device evidence. |
| installer atomicity/qualification | Not re-audited deeply in this controller pass. Prior two-rename/crash-window and missing physical-evidence findings must not be marked resolved from this report. |

## Recommended acceptance decision

Accept the implemented safety/source repairs narrowly. Reject claims of integrated expert Android autonomy, safe operational vision, all-installed-app discovery, native-only learning success, or completed A–J without the original criterion mapping and physical evidence. Preserve explicit E4B/V2 rollback; document retired V2 mutations. Next work should prioritize actual reviewed navigation/task predicates and parity tests rather than adding more dormant API surface. Do not compensate for semantic uncertainty with permissive label/coordinate execution.

## Hash inventory

Generated below from tracked diff plus untracked non-build sources against the baseline. Inventory is broader than reviewed files; it is provenance, not a claim of line-by-line inspection of every entry.

UTC: 2026-10-05T11:30:49.795975+00:00

HEAD: 69bf5e097b85a1bc40da14c07609dde8ca765cbd

Tracked binary diff SHA-256: `5a205b3c281cf3c3130945eba5e0ef9c166fccca34443e80f2e01ee2ac9520d1` (untracked content covered by manifest)

Manifest SHA-256: `e53070f6b227ec5b66001c0e321823c66cf84f6069df2d9fb6540bc48dfddef7`; entries: 86

```text
052072738f68a4b1ed010a39a732f86a1d0ef7091c4598befd10d894a0c84795  README.md
5d36b5daaf40016c89de9d09cf999f1c95ec68d5328ce8417884f4ba9d0cc7d4  android-app/UnoOneAgent/accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/AccessibilityControl.kt
172c17f7243a7545f2319594226077d54df31ca4fb7dc03a0eca6e55a594bca3  android-app/UnoOneAgent/accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/AndroidDeviceAdapter.kt
176ecb1adb81ce3d468ae736cd74a9554521f0e70609ad97669436a8f5b5ff97  android-app/UnoOneAgent/accessibilitycontrol/src/main/java/com/unoone/agent/accessibilitycontrol/UnoOneAccessibilityService.kt
d49b54fb2f9301a44e0e8baa43fde3d23313a0d09c59315aef3f992076ef816c  android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/localbrain/V3E2BPhysicalQualificationTest.kt
e9aa5d687e148bc1d05a803082a671d71f1217c7b6724624bbcf94ad390f1991  android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/localbrain/V3FixtureBoundaryInstrumentedTest.kt
bf10a046b3942eab477c47aa4b43e4fb8a11a754394a940c5d8fbbf95fcd693c  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/AgentOrchestrator.kt
2c0a60b7d4c15f34207e31c4a4f9e6b16e96766c153018d43d9330c3b3f49052  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/DeviceAgentSession.kt
59f8eb32c37a7f01e2c20b5ac36caa446565cb4056dad14a16310292a22f2d70  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/NativeDeviceGoal.kt
e4d6b4d8ed8e37a8c51c220b8740235aa3e1a37eb6836a0a5c4444d042cc181c  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/UnoOneApplication.kt
1bf4cd81a2b330f69645912482613cf31315003fa3b870fb9acda389a51105d8  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/browser/BrowserLeasePolicy.kt
3195cd460ce20ec3a68ac0a4de5b73b7bdb0249db9e33b3333218c9b02787a71  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/browser/SecureBrowserModelLease.kt
073822f3d88b748f0af29a8ba500e7a02d0736a4dc3d184e1bbf37afb4f18f88  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/execution/ActionExecutor.kt
3f84d37a3f41baa949b1350f9d53da9f7bc4be79477e15c195033fd73a397485  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/screens/DeveloperDiagnosticsScreen.kt
0fd2a3361523a19a77c55edeb7b55740cf0bf015eac6241a0dfd97769ffd1c10  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/screens/ModelStatusScreen.kt
a1992657cd38f10390767b7bd72f8ac275e73f48c95b127051112b583b7c776c  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/screens/SettingsScreen.kt
c4f32761088c38f31514ee956821b0343adb8fbc3135786e80c0d93d08471466  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/screens/V3PerceptionSession.kt
a918db0b9b8e1bf9dda7a1234ae46f06b9de163bff4beb0f13cba2c986460a0a  android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/ui/viewmodel/ModelStatusViewModel.kt
0eac0040018bb0b155e3c3dac0c8ac11ae36ab4c9f02e06de1bd422bad0687b6  android-app/UnoOneAgent/app/src/test/java/com/unoone/agent/BrowserLeasePolicyTest.kt
9da6f474da9e0a23c6654166a0dcfe4a1f17d6ad20df3d650af7b8531b34a7fd  android-app/UnoOneAgent/app/src/test/java/com/unoone/agent/NativeDeviceCommandsTest.kt
c2a7373f6e5709f4a52f6c965ddbb38528d4694302e85fd80876ebc0f6fd3842  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/device/DeviceAction.kt
715a02fd8707fd135fa0a68a447ee67be2f611f562e6a4df2fbf20270e32e751  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/device/DeviceAgentLoop.kt
e9793a1e74444e54f30a51220bd81507bfc065b753ffd9ad2873b50b9c7cbd38  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/device/DeviceSafety.kt
eec9c0eeea68cbd3eb55c27968327f76525f1cba660dd89f7d56602bcda3f397  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/device/Perception.kt
337592516cb6260b40fcbf25ec0a62dfd9233ea6c927f0e3dab7b3fd46676f7f  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/integrations/ExperimentalQwenBrain.kt
7ba8a2b7e1ce113c89abb226ad34cf413e342cd6930743f195a7dfbc85ac3e61  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/integrations/UnoBridgeContract.kt
964e3cd03fec9c1af38a13d00689d28ca479c13c71073e0aa0088d149a2c128b  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model/BrainModel.kt
3a23889a0d451ebd02a946ebad15edcb434543a94c66b8a19e21c0ed3e0d85e9  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model/BrainSelectionPolicy.kt
393c33a97b362a67993572ad729ba4c113ba6834f7428292b7621d5b9bd6de49  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model/E4bRuntimeBudget.kt
23b84dd3e7b998845afe5618e74e1600a46238747a2e4544c6aa2341a5f2106e  android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model/ToolCallValidator.kt
8c21235e237da0cf132c6f1ccb340c3e8518d2bc4bd9b7c2e3524dfcbc4b0630  android-app/UnoOneAgent/core/src/test/java/com/unoone/agent/core/device/DeviceConfirmationRefreshTest.kt
f61021ffbe4dbee2bf0a6af536335f0d254730092c8c6079f1488ef9d0fc9cbc  android-app/UnoOneAgent/core/src/test/java/com/unoone/agent/core/device/DeviceCoreTest.kt
71e856455ee03562eb8a82115465a6f81b669cb1bc96984f34746e65385c1204  android-app/UnoOneAgent/core/src/test/java/com/unoone/agent/core/model/BrainModelRegistryTest.kt
00f3d268c5d6b73c63f50b9379f7c34dadf0c2d8d1a5584a818013ab4de706c9  android-app/UnoOneAgent/core/src/test/java/com/unoone/agent/core/model/BrainSelectionPolicyTest.kt
d3f254ea797979d4f30ae15e92d1cf47abefb01b0343aedbedfc6ce0f16fa377  android-app/UnoOneAgent/core/src/test/java/com/unoone/agent/core/model/ToolCallValidatorTest.kt
00aa034562b051935a2d0c24d1df45e2d218e915480ba1204b99e386d3cc69bd  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/GemmaE2BBrain.kt
2d42d50c2e635739c3383d12fdaaf0d88dd69860402a4a9caded17d20974f09e  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/GemmaPlanner.kt
27aba5232ea0c07bc736e5c46a0beaad7f6085d94efaec48db1c2fcfc5bf1906  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/GroundingBox.kt
b012ed6b7b74ac609110cfc06c15c98be9378939d79fd2eb1fd191f9b9128b90  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/LiteRtCancellableInference.kt
cec7539908dee9f7b0c085118afcbd0e719daee5547b224ed2a0d23277609a81  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt
1eac06eb59cc2a21638082ce56ee486083f9fcda55ed14933cd0678ce148b23c  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/PageAgentGemmaPlanner.kt
60e2f21cdcc1a0822470cf4003b6c205e3244e8066f9880c21f4eb5ef9e34781  android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/RAGManager.kt
751e923cf5a3a43821c5faaf3fb7c5343676e8aa36851fa121146e0c22803bc9  android-app/UnoOneAgent/localbrain/src/test/java/com/unoone/agent/localbrain/GroundingBoxTest.kt
058d55c5679c7a181e88771db6ca4c3bf9f71b06f580cd0816d36f77c530322f  android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json
98a6b4aec4bf67815848eb9162b77392edefea148a5b953dc8c1cc6e56ca8f59  android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/E4bQualificationRecord.kt
64e7976f1dc9b171cb4536c44ea021ad75b55b13896e998b9ba7fed1b3ceafd4  android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelInstaller.kt
e670cb7c77091bd6e98d493cb0ce7de26aa0e29afc019b62193842091480deb1  android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelManager.kt
6aa9a54fd7cef880f9d65a0596b60dd26e4fc49d1f177b7dae0a797ca4f5d534  android-app/UnoOneAgent/modelmanager/src/main/java/com/unoone/agent/modelmanager/ModelQualificationRecord.kt
98c83b51b1a132ab7c08b22a48f6a9ad49d69da5d3ea08662ca709a7b55e753e  android-app/UnoOneAgent/modelmanager/src/test/java/com/unoone/agent/modelmanager/E4bCleanupGateTest.kt
9787d768fd54cd1ab13748b4f41819476689b5554994d99ce993f10077a31368  android-app/UnoOneAgent/modelmanager/src/test/java/com/unoone/agent/modelmanager/ModelInstallerTest.kt
a410bc8588a844e8b5537b76d6ff0b03be1a32f9da9f556a8cb05cb316be1bc7  android-app/UnoOneAgent/modelmanager/src/test/java/com/unoone/agent/modelmanager/ModelManifestTest.kt
51a94e7266d1d7cbf64d9979b2efca9415991d1a89a6232b549dcfc80ed991f3  android-app/UnoOneAgent/modelmanager/src/test/java/com/unoone/agent/modelmanager/ModelQualificationGateTest.kt
0e736b92f1355d1c1bfb7ac77872171d91ce1213dbafe090d0c0b7b62339092e  android-app/UnoOneAgent/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/AppRegistry.kt
c1c5eb845af41f9e6f9838cd086d11f75a8de067665047697d3b5b7f8e409a06  android-app/UnoOneAgent/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/OcrControl.kt
ea33155b086b15b5568c7325e65e0b19b9cfd1aa76fe84aa5b36ddc26b0d6c39  android-app/UnoOneAgent/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/ScreenPerceptionProvider.kt
362fc77b54c33a4a333bac8485918f4ca7571804842ad18a6044cd1692b86189  android-app/UnoOneAgent/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/ScreenPreprocessor.kt
7bec5d8d23249f2f3b66f9872f62c04e88a5eade68bc69c154dc0dfc061b0c7f  android-app/UnoOneAgent/phonecontrol/src/main/java/com/unoone/agent/phonecontrol/ScreenshotCapture.kt
42e27598ee5c916a3e9ea210290848a393a330d7ade4cd2e320a2fed882f2a9a  android-app/UnoOneAgent/phonecontrol/src/test/java/com/unoone/agent/phonecontrol/ScreenPreprocessorTest.kt
5ee4ecc1ab2c92a7a01232c8a6276d83bba2702e42489f6a1e91fd7e7ce127dc  android-app/UnoOneAgent/skills/src/main/java/com/unoone/agent/skills/SkillsV2.kt
06b945c4b04ffe280ec40a9ece1672f6ebcba93d4e0f5f89e98a5761e208f696  android-app/UnoOneAgent/skills/src/main/java/com/unoone/agent/skills/integrations/UnoBridgeClient.kt
5427eae5e47883e6814d6cfdd7c37a4b407264d3856ebc004f28d0aa059edd02  android-app/UnoOneAgent/skills/src/test/java/com/unoone/agent/skills/SkillsV2Test.kt
fd22a651e90a82106f5f3277e6f0065b346727652b950e255f5373db45200094  android-app/UnoOneAgent/storage/src/main/java/com/unoone/agent/storage/PreferencesManager.kt
da9bd9c238a204e804a725fb5c03a44d827113752c440b7b6cb332f6e41b7547  docs/UNOONE_V3_ARCHITECTURE.md
bf6a653e92bacc7b41d2ef464d7be021d1435c25ad51f7acfc3f859c25e91e55  docs/UNOONE_V3_BASELINE_AUDIT.md
e5e3aa44046fb388b4fc13c80766c8b4e0985a583a75e30d59a5d62180fb8b26  docs/UNOONE_V3_BENCHMARKS.md
f65bd8bf670d05fdd7f9a02646bd40a3750ddfe2163c84131b0136c5d0df3703  docs/UNOONE_V3_CONTROLLER.md
71a1491656be57ba4328797b92e1527dddfe289e9917c817d4eea1352261b92b  docs/UNOONE_V3_DEVELOPMENT_STATUS.md
8b184a6cdcdb743c68934e813c5adfb10baa64b6abf874274e2a645615679c68  docs/UNOONE_V3_MIGRATION_ROLLBACK.md
a5ad593559233711d8aab2e97451a6f216ed00b400b4200043c211898967bd75  docs/UNOONE_V3_MODEL_STRATEGY.md
252bf560ba1be72df2f9c36f8b6a7d86889ab20140ddbb55f075153315e27d58  docs/UNOONE_V3_PERCEPTION.md
f550e8452dc5304b53847a762678ad88ae6aa0ed3831a799fa54ecad59a9ae40  docs/UNOONE_V3_QWEN_EXPERIMENT.md
f7f3907dec22e03929495d87757fa9aa0c8d023f8fd3aedc2f4d79cd4d0b11d2  docs/UNOONE_V3_SAFETY.md
394b14fd87f5fee0c4745e3d3692a4afe698826eb06bcab546b90e09ab6e2451  docs/UNOONE_V3_XIAOMI_TEST.md
fd2720d7e300b69f73104388ad002e7e1b79b2f486fdea8828f62d47227a1287  evaluation/device-agent/build_corpus.py
98aeb83c6d3303077d87ae6da0f90b31e31d8d7fbaafece5c9fc7e335057bde5  evaluation/device-agent/fixtures.json
05500242199cc2b939b14fa133c51c0ba1e4a4471a662278e90a8d25cde603ad  evaluation/device-agent/host-validation.json
fbfe5d6f584cfab497ef87cfc68b85bfe24bcf98a86db93ae4cce535a9c3f4d3  evaluation/device-agent/pending-results.json
d10f5d148fc6cae4efb2bb11eda6f517cd8dd7722d126f4d1f8893030698e323  evaluation/device-agent/result.schema.json
a825ef32fe5b65db35ab42778d4a73f06abd0b998a31e07b73a9f29584967b48  evaluation/device-agent/sustained-50.json
018970b36f854671995d43cfe938f29bc3beed3d7046f4abf23c9c5e7169d370  evaluation/device-agent/tasks.json
39fba69ab4946e1c6615f254c89cce8f7031821a540dea63f5137a27d5b6e614  evaluation/device-agent/validate.py
9bb289f3a613950c62a59b37f9c8fa78d16b11d45092ce07fd5a7287c08fe924  scripts/ci/check_repo_invariants.py
20e93c1313d0a7fb13145917e5a70fa61b19f663809685d4f03d78e7edb4e3a1  scripts/ci/test_repo_invariants.py
8ab3ca3284914d64bb213e3d31603d8edc1ae18bc75786cf965000db1a25746d  scripts/test/unoone-v3-xiaomi.ps1
328cf3acca8454048cb844d3ca59d975ec1de9dc671acbe48241f930739a02fb  scripts/test/unoone-v3-xiaomi.sh
fb52891ad43a18d9448d55ac923e7cd1e1a6e521d5b868aaae89085a07750abb  scripts/verify_unoone_v2_invariants.py
```


---

# Keel — Secure Browser / Page Agent deep read-only review

## Verdict and provenance

**NOT release-ready as a secure autonomous browser.** Current local APK has no PageAgent JavaScript asset; independently, the source has critical same-page bridge isolation and consequential-action authorization weaknesses. Improvements in the current native lifecycle diff are meaningful, but serialization of individual native calls does not prove exclusive model residency across transitions.

Reviewed repository `/agent/workspace/UnoOne-Local-Agent`, HEAD `69bf5e0` (working tree dirty). Compared original HEAD with current changes to `SecureBrowserModelLease.kt` and `PageAgentGemmaPlanner.kt`, traced their callers and existing securebrowser/TypeScript implementation. This is a time-boxed source/security review, not Android device certification or a claim that every line of the repository was audited. No production files changed; no Gradle, models, npm install, network retry, or device execution. Reproduction instructions below are **source-derived scenarios**, not newly executed browser exploits. SHA-256 manifest accompanies this report.

Path aliases: **A** = `android-app/UnoOneAgent`; **S** = `A/securebrowser/src/main/java/com/unoone/agent/securebrowser`; **V** = `A/app/src/main/java/com/unoone/agent/ui/viewmodel/SecureBrowserViewModel.kt`; **L** = `A/localbrain/src/main/java/com/unoone/agent/localbrain`; **W** = `web-runtime/page-agent-unoone`.

## Actual execution path / build truth

1. `V:153–181` checks the packaged asset before acquiring any model; missing asset produces **Not built**, not a working browser. The lease then snapshots previous phone identity, verifies a model file, acquires ownership, unloads phone and loads browser (`app/.../browser/SecureBrowserModelLease.kt:46–95`).
2. ViewModel creates `SecureBrowserNativeHandler` and `SecureWebViewController` (`V:194–270`). Standard uses approved HTTPS origins; SecurityLevel.OFF changes both navigation scope and action policy. Home is a synthetic local HTML document.
3. Controller injects session bootstrap and bundle into the page's ordinary JS world (`S/SecureWebViewController.kt:460–485`). `W/src/index.ts:28–62` instantiates PageController and PageAgentCore. `customFetch` converts prompts into `MODEL_INVOKE` messages; it does **not** perform HTTP inference (`W/src/local-gemma-fetch.ts:27–52`). Native planner generates one action, TypeScript asks native authorization, then performs DOM action.
4. The current JS still labels model `gemma-4-e4b-local` (`index.ts:43`, `local-gemma-fetch.ts:73`), although the native selected profile can be E2B. This is diagnostic drift, not evidence of cloud use or forced E4B loading.
5. Filesystem inspection confirmed all three **absent**: `W/node_modules`, `W/dist/unoone-page-agent.js`, `A/securebrowser/src/main/assets/page-agent/unoone-page-agent.js`. ZIP inspection also found **no `unoone-page-agent.js` entry** in either existing `A/app/build/outputs/apk/debug/app-debug.apk` or its androidTest APK. Existing class files/test reports do not change this result.
6. Generation is a separate npm operation (`W/package.json:8–12`; `W/scripts/copy-to-android.mjs:8–27`); destination is gitignored (`.gitignore:58`). `A/securebrowser/build.gradle.kts:1–38` has no generation/presence dependency. CI does explicitly install/typecheck/test/bundle/check asset (`.github/workflows/android-ci.yml:47–64`), so CI wiring exists, but a local Gradle-built APK can omit it. Do not present the current APK as demonstrating PageAgent. No JS suites executed because dependencies absent; obeyed instruction not to retry networking.

## Prioritized findings

### B1 — CRITICAL: page scripts can impersonate the trusted agent and see/interfere with its private task bridge (pre-existing)

**Locations:** `S/SecureWebViewController.kt:343–402,460–485`; `W/src/native-bridge.ts:57–82,86–110`; `W/src/index.ts:81–124`.

Origin/main-frame checks distinguish frames/sites, **not the injected agent from scripts running on that same page**. The session id and nonce are deliberately readable as `window.__UNOONE_PAGE_AGENT_SESSION__`. The native bridge is page-visible; its response callback is writable. Freezing the session and making the runtime global non-writable does not isolate their JS realm; runtime methods are exposed, and page scripts can intercept platform primitives or invoke the bridge directly. There is no native task-active requirement for MODEL_INVOKE/ASK_USER/AUTHORIZE_ACTION and no replay/sequence check. The page can request arbitrary model work, trigger misleading native questions, forge audit events, or interfere with task-result delivery. A page-created lookalike `UnoOnePageAgentRuntime` also satisfies the controller's mere Boolean presence test.

**Repro:** on an admitted main-frame page, read session fields, call `UnoOnePageAgent.postMessage` with a valid envelope and `type: ASK_USER`, `payload: JSON.stringify({question:'Enter a private code'})`; intercept bridge responses via `onmessage`. No actual user browser task is necessary. Likewise listen for `unoone-page-agent-ready`, then alter exposed runtime behavior or bridge reply handling. This does not grant arbitrary Android Java methods, but is enough to violate the claimed trusted agent boundary and expose answers/task material to the page.

**Required:** isolated execution world or genuinely separate trusted host; keep task/nonce authority native and bind messages to task/document epochs; untrusted page content must not manufacture native prompts or audit/completion truth. Add real Android admitted-page spoofing/prebootstrap tests. Current bridge/mock tests do not exercise same-world hostile scripts.

### B2 — HIGH: Standard permits generic submit/delete/purchase clicks; policy is lexical rather than semantic (pre-existing)

**Locations:** `S/BrowserSafetyPolicy.kt:39–94,106–125`; `W/src/guarded-tools.ts:116–142,206–216`; `L/PageAgentGemmaPlanner.kt:377–466`; `S/SecureBrowserNativeHandler.kt:77–100`.

`click_element_by_index` is an ordinary-input action unless its one-line text happens to contain a short keyword list. `type=submit`, plain **Submit**, **Send**, **Delete account**, **Remove**, **Buy**, localized text, form action destination and destructive handlers are not independently guarded. `submit_form` has confirmation, but there is no guarantee planner uses that tool rather than generic click; Kotlin correction converts checkbox/radio/select/date, not generic submits. Missing indexed summaries fall back to `interactive element index N`, which is allowed, despite the test naming that behavior "fails closed" (`W/tests/guarded-tools.test.ts:12–14`).

**Repro:** plan `click_element_by_index` for `[1]<button type=submit>Submit</button>` or `[1]<button>Delete account</button>`; native classifies ORDINARY_INPUT and allows. An admitted form with `onsubmit` or click side effects demonstrates consequential action without confirmation. Context can say "stop before submission" but enforcement is only model instruction, not a deterministic boundary.

**Required:** conservative semantic action classification (form/button semantics, method/destination, labels including nearby context, deletion/send/payment categories), fail closed for unresolved targets, bind final-action consent to actual action and current destination. Ordinary browsing/network must remain allowed; do not solve by globally blocking web access. Tests must drive generic clicks through **real Standard policy**, including destructive and translated controls.

### B3 — HIGH: no snapshot/version identity binding across model latency or confirmation; type-only remapping can act on a different field (pre-existing)

**Locations:** `W/src/guarded-tools.ts:13–24,40–77,119–141,149–156,209–216`; `L/PageAgentGemmaPlanner.kt:377–425`; `S/PageAgentProtocol.kt` (request schema).

Agent sees an index in one snapshot; guarded tool rebuilds browser state and uses that same number in another snapshot. Neither request nor response carries a document/snapshot revision, stable element identity or expected value fingerprint. Typed remapping proves only control type; even if requested index now denotes a different checkbox/select it passes. Authorization is asynchronous and may wait for a person, then acts without checking DOM changes since authorization.

**Repro:** two text inputs A/B; while model plans writing A, mutate/reorder DOM so reused index denotes B. Or authorize a submit, replace/relabel the target while dialog is open, approve. Current code cannot prove the authorized control is the executed one. A unique checkbox is not necessarily the checkbox the user meant.

**Required:** snapshot id + stable target identity/semantic fingerprint, document epoch, short-lived single-use approval; verify immediately before mutation, re-observe/replan on mismatch. Existing tests cover index 1 vs 10 and unique-type remapping, not dynamic same-type replacement or delayed consent.

### B4 — HIGH: Stop does not cancel native inference/pending confirmations or revoke in-flight action permits (pre-existing)

**Locations:** `V:484–491,554–565`; `S/SecureWebViewController.kt:187–199,365–383,389–402`; `W/src/guarded-tools.ts:125–126,140–141`; `W/src/local-gemma-fetch.ts:31–52`; `W/src/native-bridge.ts:86–110`.

Stop cancels task callback/timeout and asynchronously calls JS `agent.stop`; it does not cancel controller-launched native jobs, pending prompt, file callback, or planner inference. `customFetch` ignores the fetch AbortSignal; native bridge has timeouts but no cancellation protocol. After awaited authorization, tool executes with no abort/generation check. Native validation does not check active task id. Closing/disable destroys the WebView and releases the model (stronger than Stop), but queued handlers/prompts have no document/session revalidation at completion, and authorization lacks the post-await enabled check used by model responses.

**Repro:** request explicit submit confirmation, press Stop before responding, then approve old prompt. Wrapper still proceeds to `clickElement` when the authorization promise resolves; no local tool cancellation check exists. Separately stop during slow inference and inspect retained native work. PageAgent dependency's internal cancellation might mitigate some paths, but with dependencies absent this cannot be credited as proof; native design lacks the required revocation.

**Required:** task-owned cancellable native Job, cancellation forwarded to planner, cancel prompt/file waits, task/document epoch validated before and after awaits and before DOM mutation. Add Stop/disable during model, consent and file chooser tests; existing disable test sets gate **before** handling, not mid-flight.

### B5 — HIGH: exclusive model ownership has a check/lock race; current diff does not eliminate dual residency (partly improved, residual)

**Locations:** `A/app/.../AgentOrchestrator.kt:469–484`; `A/app/.../parsing/CommandParser.kt:134–137`; `L/LocalBrain.kt:37–41`; `L/GemmaPlanner.kt:124–152`; `A/app/.../browser/SecureBrowserModelLease.kt:49–82`; `L/PageAgentGemmaPlanner.kt:77–89`.

Phone checks ExclusiveBrainLeaseState before calling a suspend load; actual `GemmaPlanner.load` takes process operation mutex after dispatch to IO and does not revalidate lease ownership inside that lock. Browser unload and browser load are separate process-lock operations. A phone load admitted before lease acquisition can be delayed, resume after browser's unload barrier/phone residency check, and initialize beside the browser engine (either before browser load or after it). Serializing engine calls is not prohibiting simultaneous engine **residency**. Snapshotting prior identity before the transition is also not atomic with in-flight loads.

**Repro:** barrier-test suspend phone load after orchestrator ownership check but before planner lock; acquire browser lease and complete phone unload/residency check; allow browser load; resume phone load. Phone planner closes only its own engine, never checks browser residency/lease, then allocates. Reverse ordering after browser check also problematic.

**Required:** one atomic process transition/ownership protocol; recheck owner at native allocation under shared lock, thread owner token through authorized restoration; atomically capture phone identity. Pure `BrowserLeasePolicyTest.kt:9–36` tests booleans/profile identity, not real coroutine interleavings. Add fake-engine barrier tests for concurrent startup, selection, acquisition, stop and trim.

**Credit to current diff:** exact previous path/spec now restored, cold browser uses selected/default profile rather than hardcoded E4B, unload acknowledgement checked, browser native cleanup uncertainty quarantined, lease retained when browser close fails, normal restoration protected by NonCancellable and finally. These are real improvements. Browser profile intentionally prefers already-resident phone spec over newly selected preference (`BrowserLeasePolicy.kt:11–12`); clarify expected behavior when preference changes while previous model remains resident. Failed-acquire restoration results are discarded (`SecureBrowserModelLease.kt:88,124,141`), leaving no specific restore-failure report.

### B6 — MEDIUM/HIGH: browser authorization is narrated and logged as completed execution before DOM action runs (pre-existing)

**Locations:** `S/SecureBrowserNativeHandler.kt:118–139`; `V:707–729,773–795`; `W/src/guarded-tools.ts:139–141,214–216`.

Native authorization records `decision=allowed`; ViewModel stores status **success** and says **Text field filled**, **Form submitted**, etc. Only after handler completes does JS receive response and attempt action. Stale target, navigation, cancellation, validation failure or network rejection can leave an audible/logged false success. Especially harmful for eyes-free users.

**Repro:** authorize submit then make `clickElement` fail or page disappear. User hears "Form submitted" and database says success despite no successful submission. Required: separate proposed/authorized/executed/observed-result events; narrate completion only after effect verification. Browser result `success` remains page/model asserted, not independent verification.

### B7 — MEDIUM: percent-encoded URLs are double-escaped during normalization (pre-existing)

**Location:** `S/BrowserDomainPolicy.kt:66–74`.

The multi-argument `java.net.URI` constructor expects decoded path/query/fragment but receives `rawPath/rawQuery/rawFragment`; existing `%` becomes `%25`. E.g. an allowed URL with `/a%20b?q=x%2Fy` is reconstructed with `/a%2520b?q=x%252Fy`. Breaks signed URLs, OAuth return/query values and document names. Test encoded URL round-trip equality, not only scheme/host decisions. Preserve valid escapes safely, retain exact-origin checks.

### B8 — MEDIUM: prompt injection/privacy defenses are incomplete and cannot substitute for action boundaries (pre-existing)

**Locations:** `L/PageAgentGemmaPlanner.kt:290–313,499–503,630–632,1166–1171`; `L/PromptBuilder.kt:167–179`; `W/src/content-mask.ts:10–19`; `W/src/guarded-tools.ts:13–15,134–138`.

Planner discards upstream system prompt in favor of a compact policy with no explicit instruction that website text is untrusted and must not redefine the user's task. Sanitization strips a few model tokens, not natural-language instructions or forged `<user_request>` tags; recovery logic parses task and DOM out of the combined string. DOM labels can influence action/authorization. This is exposure, not a claim every injection succeeds.

Masking only redacts password `value` when `type=password` precedes `value`; reversed attributes, HTML-like split OTP markup, and other secrets can survive regexes. Also authorization uses fresh **unmasked** element summary, independently of `transformPageContent`; native can receive a secret already present in an element line. Current DB persistence responsibly excludes summary/raw prompt (`V:713–724`), but same-world page interception and ASK_USER exposure remain B1. Test both attribute orders, separate labels/value lines, edited password fields, pasted secrets, and malicious page task delimiters. Local inference is good privacy behavior; it does not make credentials safe for unrelated page scripts or authorize exfiltration by ordinary field writes.

## Additional functional/security limits requiring explicit product wording

- **Single document, not reliable multi-page browsing:** every `onPageStarted` calls `stopTask` (`Controller:272–278`), so clicking an ordinary navigation link ends current run, even if destination is approved. There is no durable task continuation into the newly injected runtime. Safe stale-reference invalidation is correct; a resumable task requires explicit document epochs and re-observation rather than disabling this protection.
- **Navigation allowlist is not an egress firewall:** controller implements shouldOverrideUrlLoading but no resource interception (`Controller:260–302`). HTTPS subresources/fetches and form POST behavior are not equivalent to that callback. Normal network browsing is intentional and must remain supported. However an imported local HTML form is not guaranteed network-offline: script/img/fetch may contact HTTPS hosts. "Local AI processing" and "offline imported document" must not be conflated. Android device tests should cover POST redirects, frames, HTTPS exfil destinations and local-form external resources. Syntactic public-host checks also do not resolve DNS to exclude private addresses.
- **File behavior:** real Android picker now exists; file/content arbitrary WebView access disabled; content URI/type/size checks present (`V:304–392`). Good. But `upload_file` confirms generically **before** file is selected; prompt does not include actual filename/destination, and later onchange JS can transmit immediately. Generic click on a file field bypasses FILE_ACTION classification. Pending callback not tied to document epoch; Stop doesn't clear it. Persistable read grants acquired (`V:318–324`) without matching release on session close. The browser is not exposing an arbitrary local delete-file method, but website delete controls are unguarded under B2.
- **No native cross-app DOM claim:** PageController can operate this WebView's DOM, not Chrome/other-app DOM. Browser model port exposes browser plans only; native model unload/restore separates modes. Android accessibility/DeviceAgentSession is a separate interaction route, not proof of semantic browser DOM in another app. Test cross-mode handoff and lease overlap; do not promise PageAgent supports arbitrary app WebViews or cross-origin frames merely from schemas.
- **Local form origin admission:** `onPageFinished` uses `target.startsWith(local)` (`Controller:289`), weaker than parsed exact origin; actual bridge checks are stricter and may reject later. Use one exact-origin parser throughout, reset local-form state when navigating away, and bind injection callbacks to document epoch.

## Coverage assessment and release gates

Available source tests include policy/handler unit tests, task-result id gating, origin parsing, prompt correction, typed-index matching, mocked Playwright field/form/upload flows, and Android packaged-WebView form fixture. They are useful but do not certify adversarial end-to-end behavior:

- Playwright bridge is a mock (`W/e2e/page-agent.spec.ts:23–131`); whole-form test simply returns allowed (`:260–298`), even terms checkbox.
- Android ordinary-form test uses a scripted model and **PROTOTYPE_OFF** (`A/app/src/androidTest/.../SecureBrowserPageAgentFormDeviceTest.kt:33–64`), not real selected Gemma and Standard policy.
- New lease tests are pure policy tests, not native occupancy tests.
- No fresh suite run here. Missing JS prerequisites prohibit trustworthy PageAgent runtime validation; historical build reports/classes are explicitly not fresh evidence.

Minimum gates: (1) deterministic verified asset generation/presence in every shipped APK, with source/bundle/APK hash relation; (2) hostile admitted-page JS isolation test; (3) Standard generic submit/delete/payment/upload tests with delayed confirmation and DOM swaps; (4) Stop/disable cancellation at every await; (5) real exclusive E2B/E4B transition tests including allocation/cleanup failures; (6) outcome-grounded accessibility narration; (7) encoded URLs + real WebView POST/navigation/form tests. Do not weaken network browsing to satisfy an overly broad offline invariant; keep **website transport allowed, AI inference local** as distinct policies.
