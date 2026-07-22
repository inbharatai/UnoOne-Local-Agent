# UnoOne Model Acquisition and Distribution

## Objective

Engineering may acquire approved upstream artifacts from third-party sources, subject to provenance and licence review. Production users must eventually download the exact approved bytes from UnoOne-controlled storage through a signed catalogue.

The distribution backend never receives prompts, speech, screenshots, documents or browser form values and never performs model inference.

## Non-negotiable rules

1. Do not commit multi-gigabyte model weights to Git.
2. Do not guess a filename, byte size, checksum, licence or device result.
3. Do not substitute a web artifact for an Android artifact.
4. Do not mark integrity verification as physical-device qualification.
5. Preserve upstream provenance, licence and notices.
6. Production catalogues must fail closed for invalid or missing signatures.
7. Production files must come from an approved UnoOne-controlled HTTPS origin.
8. User data is never uploaded to the distribution service for inference.

## Active engineering artifact

| Field | Value |
|---|---|
| Model | Gemma 4 E4B Instruct |
| Manifest id | `gemma-4-e4b` |
| Android file | `gemma-4-E4B-it.litertlm` |
| Exact size | `3,659,530,240` bytes |
| SHA-256 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |
| Engineering source | `litert-community/gemma-4-E4B-it-litert-lm` |
| Immutable revision | `28299f30ee4d43294517a4ac93abd6163412f07f` |
| Runtime | LiteRT-LM |
| Enforced phone context | 2,048 tokens |
| Artifact capability ceiling | 32,768 tokens; not configured on phone |
| Minimum RAM product gate | 8,192 MB |
| Recommended RAM product gate | 12,288 MB |
| Physical-device qualification | pending |
| Production approval | pending |

The active Android catalogue must not use the separate web-specific `.litertlm` build.

## Qualification stages

| Stage | Meaning | Required evidence |
|---|---|---|
| `acquired` | exact upstream artifact identified | repository, revision/version label, exact filename, licence and notices |
| `integrity-verified` | local bytes measured and matched | exact byte size and SHA-256 |
| `load-tested` | LiteRT-LM initialized the artifact | device model, Android/HyperOS build, backend and load logs |
| `tool-tested` | phone and Page Agent planning passed | exact tool/argument evaluation with unknown/malformed calls rejected |
| `device-qualified` | sustained phone gate passed | memory, latency, heat, battery, crash/ANR/OOM and task-loop evidence |
| `production-approved` | legal, integrity, security and device gates approved | signed release record, protected keys and catalogue entry |

A stage may advance only from committed evidence. The Xiaomi 14 procedure is in [E4B_XIAOMI14_HANDOFF.md](E4B_XIAOMI14_HANDOFF.md).

## Engineering acquisition flow

```text
approved upstream artifact
          ↓
provenance and licence review
          ↓
exact filename / size / SHA-256
          ↓
app-private resumable download
          ↓
LiteRT-LM Android load test
          ↓
phone-tool and Page Agent evaluation
          ↓
Xiaomi 14 sustained qualification
          ↓
secondary-device qualification
          ↓
UnoOne-controlled object storage
          ↓
signed production catalogue
```

## Integrity record command

After obtaining the exact artifact locally:

```bash
python scripts/models/qualify_artifact.py \
  /absolute/path/to/gemma-4-E4B-it.litertlm \
  --id gemma-4-e4b \
  --version <immutable-upstream-version-or-revision> \
  --runtime litertlm \
  --license <verified-license> \
  --provider Hugging-Face \
  --repository litert-community/gemma-4-E4B-it-litert-lm \
  --revision <immutable-revision> \
  --upstream-file gemma-4-E4B-it.litertlm \
  --minimum-ram-mb 8192 \
  --recommended-ram-mb 12288 \
  --output artifacts/qualification/gemma-4-e4b.json
```

The RAM values are product gates for the current target device class, not universal performance claims.

## Application installation safety

The in-app installer must:

- calculate required free storage from remaining bytes plus a safety margin;
- download to `<filename>.part`;
- resume only when the server supports the requested range;
- restart safely when a server ignores the range;
- validate exact size;
- validate exact SHA-256;
- atomically rename the verified file;
- retain the previous working model until the new model loads and self-tests successfully;
- never mark a corrupt or incomplete file installed.

`ModelManager.getLlmModelPath()` must load only the exact manifest-declared file after integrity verification.

## E2B retirement

E2B is not an active production catalogue entry. Existing phone bytes are temporary migration data, not a runtime fallback.

Do not delete the old E2B folder until E4B has passed:

- exact integrity verification;
- LiteRT-LM initialization;
- on-device tool self-test;
- Blind Aid and Secure Browser transitions;
- sustained 50-task stability.

After success, use the guarded legacy cleanup with canonical-path validation. Never build a recursive deletion path from untrusted input.

## Proposed production storage layout

```text
models.unoone.inbharat.ai/
├── catalogue/
│   ├── stable.json
│   ├── beta.json
│   └── signatures/
├── brain/
│   └── gemma-4-e4b/<approved-version>/
│       ├── gemma-4-E4B-it.litertlm
│       ├── artifact.json
│       ├── SHA256SUMS
│       ├── LICENSE
│       ├── NOTICE
│       └── signature.sig
├── speech/
│   ├── shared/
│   └── languages/
└── vision/
    └── blind-aid/
```

## Production catalogue enforcement

Production builds must require:

- valid Ed25519 catalogue signature;
- exact artifact size and SHA-256;
- approved runtime and app-version compatibility;
- qualification status;
- licence and notice metadata;
- HTTPS UnoOne-controlled origin;
- rollback metadata for the previous approved release.

A bad signature, empty checksum, unsupported runtime, origin mismatch or unapproved qualification status must fail closed.

## Backend responsibilities

The distribution backend may:

- distribute signed APKs, models, language packs and catalogues;
- provide compatibility, release and rollback metadata;
- support resumable object downloads;
- serve licences, notices and release notes.

The backend must not:

- run Gemma inference;
- receive voice, prompts, screenshots, documents or form data;
- store passwords, OTPs, CAPTCHA answers or payment information;
- remotely control the device;
- silently change the local model or security policy.
