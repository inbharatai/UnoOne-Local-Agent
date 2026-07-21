# UnoOne Gemma 4 Migration Record

## Status

The original migration from mock/legacy inference to LiteRT-LM is complete. The active development work is now the upgrade from Gemma 4 E2B to **Gemma 4 E4B** on branch:

```text
feat/e4b-agentic-runtime
```

Draft pull request: **#2**

This file is a migration record and completion checklist. Current product status is in [STATUS.md](STATUS.md), while the physical Xiaomi 14 procedure is in [docs/E4B_XIAOMI14_HANDOFF.md](docs/E4B_XIAOMI14_HANDOFF.md).

## Why E4B

The E2B model was insufficiently reliable for strict tool selection and required-argument formatting in the intended agentic workflows. E4B is adopted as the sole planning model to improve instruction following and structured action proposals.

The upgrade is not treated as a model-file swap. UnoOne remains reliable only when deterministic routing, schema validation, native safety, controlled execution and result verification remain outside the model.

## Target model contract

| Field | Value |
|---|---|
| Model id | `gemma-4-e4b` |
| Folder | `brain/gemma-4-e4b` |
| Android file | `gemma-4-E4B-it.litertlm` |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Runtime | LiteRT-LM `0.13.1` |
| Backend order | GPU, then CPU fallback |
| Default context | 2,048 tokens |
| Maximum supported context | 32,768 tokens |
| Minimum RAM gate | 8,192 MB |
| Recommended RAM gate | 12,288 MB |

The Android artifact above must not be replaced with the smaller web-specific build.

## Target execution architecture

```text
voice / text / accessibility input
                  ↓
       language and text normalisation
                  ↓
        deterministic command router
          │                   │
          │ direct            │ ambiguous / multi-step
          ▼                   ▼
 native Android handler   Gemma 4 E4B planner
          │                   │
          └────────┬──────────┘
                   ▼
        canonical tool validation
                   ▼
      permission + safety + confirmation
                   ▼
       deterministic action execution
                   ▼
          post-action verification
                   ▼
            offline spoken reply
```

The model proposes one action at a time. Native code remains responsible for authorisation, execution and verification.

## Completed branch work

- [x] Created a dedicated E4B branch and draft pull request.
- [x] Replaced the active install catalogue with one E4B LLM descriptor.
- [x] Pinned exact filename, size and SHA-256.
- [x] Set an initial 2,048-token mobile context.
- [x] Kept GPU-first and CPU fallback.
- [x] Added optional OpenCL/VNDK native-library declarations.
- [x] Removed arbitrary largest-file model selection.
- [x] Required exact path, size and hash before returning a model path.
- [x] Added guarded legacy E2B cleanup after verified E4B activation.
- [x] Pointed application startup, recovery, model status and Secure Browser at E4B.
- [x] Kept one process-wide exclusive model lease.
- [x] Tightened prompt and tool descriptions against invented recipients, packages, dates, times and success.
- [x] Updated Android and distribution invariant checks.
- [x] Updated model-manifest tests.
- [x] Updated active README/status/model/distribution documentation.
- [x] Added an exact Xiaomi 14 validation handoff.
- [x] Updated Page Agent model identity to E4B.

## Remaining code-quality work before merge

- [ ] Latest Android CI passes on the final branch head.
- [ ] Latest Distribution CI passes on the final branch head.
- [ ] Remove the temporary deprecated E2B registry alias after every active runtime reference is converted.
- [ ] Review remaining E2B strings and retain only clearly historical validation records and guarded migration constants.
- [ ] Consider lazy/short-lived safety and chat conversations if device memory shows pressure; do not refactor blindly before measurements.
- [ ] Confirm no model hash is computed on the Android main thread.
- [ ] Confirm no duplicate engine, foreground service, STT or TTS instance appears during transitions.

## Required physical-device gates

- [ ] Pull and build branch with JDK 17.
- [ ] Install debug APK on Xiaomi 14.
- [ ] Download and verify exact E4B bytes.
- [ ] Record actual GPU or CPU backend from logs.
- [ ] Measure model load and first-token latency.
- [ ] Record process memory, heat and battery behaviour.
- [ ] Run deterministic wake, blind, language, app-open and navigation commands.
- [ ] Run E4B tool-name and required-argument evaluation.
- [ ] Verify Hindi reply-language compliance.
- [ ] Test WhatsApp, Gmail and Calendar reviewable drafts.
- [ ] Test Blind Aid unload and one-time brain reload.
- [ ] Test Secure Browser exclusive acquire/release.
- [ ] Run at least 50 mixed tasks.
- [ ] Scan for crash, ANR, native signal, OOM and low-memory kill.
- [ ] Remove legacy E2B only after all E4B gates pass.

## E2B retirement rule

E2B is no longer an installable or selectable production model. Existing phone files are temporary migration data only.

The old folder must not be deleted until:

1. E4B integrity is verified;
2. E4B loads successfully;
3. the on-device tool self-test passes;
4. Blind Aid and Secure Browser transitions pass;
5. the sustained stability run passes.

Cleanup must use the guarded migration with canonical-path validation.

## Accuracy acceptance targets

These are release targets to measure, not current claims:

| Metric | Target |
|---|---:|
| Deterministic command routing | at least 99% |
| Exact E4B tool selection | at least 95% |
| Required argument accuracy | at least 95% |
| Wrong-recipient execution | 0 |
| Unconfirmed external sending | 0 |
| False success announcement | 0 |
| Hindi reply-language compliance | at least 98% |
| Crash, ANR or OOM in sustained run | 0 |

## Honesty boundary

Repository and CI work can prove source consistency, tests and APK assembly. They cannot prove:

- that E4B loads on the Xiaomi 14;
- that GPU or NPU acceleration is active;
- real speech accuracy;
- tool-call accuracy on the physical model;
- memory, heat, battery or latency performance;
- stable Accessibility behaviour under HyperOS;
- production readiness.

Those remain physical-device and release gates.
