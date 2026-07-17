# UnoOne Offline Speech Model Qualification

## Purpose

UnoOne language packs must be selected through measured mobile performance, licence review and exact artifact integrity—not by model popularity. This document separates models that can run in the current Android runtime from research/teacher models that require conversion, distillation or replacement.

## Product target

The language-pack architecture covers:

- English
- Assamese
- Hindi
- Bengali
- Marathi
- Gujarati
- Punjabi
- Odia
- Tamil
- Telugu
- Kannada
- Malayalam
- Urdu

English is the required base language. Assamese is the first priority expansion language. A language stays `planned` until both STT and TTS artifacts are qualified.

## Qualification gates

Every speech artifact must have:

1. Exact upstream repository and immutable revision
2. Redistribution-compatible licence
3. Exact file names, byte sizes and SHA-256 values
4. Supported runtime format
5. Successful Android model load
6. Measured RAM, latency, battery and thermal behaviour
7. Language-specific accuracy testing
8. Code-mixing and named-entity testing
9. Clean-noise and real-noise testing
10. Pack self-test and rollback behaviour

## Current runtime-compatible baselines

### English STT

- Current runtime: Sherpa-ONNX streaming Zipformer transducer INT8
- Status: integrity-verified baseline in the bundled model manifest
- Role: required base STT and current English wake-word model family
- Remaining: physical-device accuracy, latency and thermal verification

### Shared Indic STT

- Current runtime: Sherpa-ONNX Omnilingual ASR 300M CTC INT8 (2025-11-12 artifact)
- Manifest id/path: `sherpa-asr-indic` / `speech/shared/sherpa-asr-indic`
- Status: archive size and SHA-256 are pinned; the Android runtime loaded the model and the primary Xiaomi 14 passed native-script MMS-TTS→STT round trips for Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam.
- Role: shared offline recognizer for the six enabled Indian-language profiles.
- Limitation: automatic script identification can be ambiguous for very short or code-mixed speech. The committed round-trip is a functional gate, not a WER/CER or field-noise benchmark.

### Current Indic TTS

- Current runtime: per-language MMS/VITS ONNX models through Sherpa-ONNX
- Status: integrity-verified baselines for Hindi, Bengali, Tamil, Telugu, Kannada and Malayalam
- Role: functional offline voice baseline
- Limitation: baseline naturalness and pronunciation must be measured; no language should be called production-quality from model presence alone.

## AI4Bharat candidates

### IndicConformerASR

- Repository: `AI4Bharat/IndicConformerASR`
- Licence: Apache-2.0 repository licence
- Coverage: includes Assamese and the scheduled Indian-language set described by the project
- Native framework: NVIDIA NeMo `.nemo` checkpoints
- Classification: **accuracy/reference candidate**, not a direct Android pack
- Required engineering:
  - confirm exact checkpoint licence and revision;
  - export or distil to an Android-supported ONNX/transducer architecture;
  - verify operator compatibility with ONNX Runtime or Sherpa-ONNX;
  - quantize without unacceptable language degradation;
  - benchmark streaming latency and memory on phones.

### IndicF5

- Repository: `AI4Bharat/IndicF5`
- Model access: gated upstream model distribution
- Architecture: reference-audio full-precision TTS research model
- Published language list does not establish Assamese coverage
- Classification: **teacher/quality reference**, not a direct mobile pack
- Reasons:
  - large full-precision model;
  - reference-audio workflow;
  - no verified compact Android ONNX export in UnoOne;
  - gated acquisition and licence review still required.

### Indic Parler-TTS

- Upstream: AI4Bharat Indic Parler-TTS model family
- Coverage includes a broad Indian-language set, including Assamese in the published model family
- Classification: **teacher/quality reference**, not a direct mobile pack
- Reasons:
  - model size is unsuitable for the base phone package;
  - no qualified Sherpa-ONNX mobile artifact;
  - intended for higher-quality generation and potential student-model data/distillation.

### Indic-TTS

- Repository: `AI4Bharat/Indic-TTS`
- Licence: Apache-2.0 repository licence
- Stack: ESPnet/FastPitch/HiFi-GAN-era training and inference components
- Classification: **research/training source**, not an automatic Android choice
- Required engineering:
  - identify language/checkpoint coverage and licences;
  - export acoustic and vocoder components;
  - evaluate compact single-speaker/mobile variants;
  - compare against MMS/VITS baseline.

## Assamese plan

Assamese must not be enabled merely because the shared recognizer advertises broad language coverage.

### STT candidates

1. Shared Omnilingual CTC — Android-compatible baseline candidate
2. IndicConformer Assamese checkpoint — teacher/reference and export feasibility
3. Distilled/streaming Assamese transducer — preferred long-term mobile target if measurements justify training

### TTS candidates

1. Verify an exact Assamese MMS/VITS ONNX artifact and its licence/integrity
2. Evaluate AI4Bharat Assamese-capable teacher voices for pronunciation and synthetic-data support
3. Train or distil a compact VITS/Matcha/Piper-style ONNX student if the baseline is insufficient

Until an exact Assamese TTS artifact is qualified, `as-IN-standard` remains `planned` and non-downloadable.

## Benchmark corpus per language

Minimum evaluation set:

- 100 clean commands
- 100 real-noise commands
- 100 regional/accent samples
- 100 code-mixed samples
- 100 names, places, numbers, dates and domain terms

Assamese must include regional names and terms such as Nagaon, Morigaon, Guwahati, Dibrugarh, Tezpur and Sivasagar, plus education, health, agriculture and public-service vocabulary.

## STT metrics

- Word error rate
- Character error rate
- Named-entity accuracy
- Number/date accuracy
- Code-mixing accuracy
- Time to first partial result
- Final transcription latency
- Peak RAM
- Battery and temperature during repeated use

## TTS metrics

- Intelligibility
- Pronunciation accuracy
- Naturalness rating
- Code-mixed pronunciation
- Time to first audio
- Real-time factor
- Peak RAM
- Stability over repeated utterances

## Decision policy

A model may be:

- `planned`: candidate only, no downloadable artifact
- `baseline`: runs in the current runtime but quality is not final
- `beta`: passed integrity, load and limited language testing
- `stable`: passed full corpus, device matrix and release review
- `deprecated`: retained only for migration or rollback

No README or UI may describe a language as accurate, natural or production-ready without committed benchmark evidence.
