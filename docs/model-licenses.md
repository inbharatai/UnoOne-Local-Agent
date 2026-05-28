# UnoOne Model Licenses

## Policy

Before any model is downloaded or distributed with UnoOne, the following must be verified and documented in this file:

1. **License** — commercial use allowed?
2. **File Size** — what is the total on-device size?
3. **RAM Requirement** — minimum / recommended RAM for inference?
4. **Android Compatibility** — does it run on Android (LiteRT / ONNX / TFLite)?
5. **Integration Path** — verified Kotlin/Java example exists?
6. **Attribution** — any required attribution text?

---

## Gemma (Local LLM)

| Field | Value |
|-------|-------|
| **Model** | Gemma 2B IT (or compatible) |
| **Source** | Google / Kaggle / Hugging Face |
| **License** | Gemma Terms of Use — permissive for commercial use with attribution |
| **File Size** | ~2.5 GB (Q4_0 quantized) |
| **RAM** | ~3 GB at runtime |
| **Android Path** | LiteRT-LM / Google AI Edge / llama.cpp Android |
| **Verified Example** | Google AI Edge samples |
| **Status** | CANDIDATE — verify quantization quality before shipping |

**Attribution:**
> Built with Gemma by Google.

**Next Steps:**
- [ ] Download and verify on Xiaomi 14.
- [ ] Check inference speed (< 2s for short prompt).
- [ ] Verify JSON structured output reliability.

---

## Sherpa-ONNX ASR

| Field | Value |
|-------|-------|
| **Model** | Whisper tiny / small OR Zipformer model from sherpa-onnx releases |
| **Source** | https://github.com/k2-fsa/sherpa-onnx |
| **License** | Apache-2.0 |
| **File Size** | ~150 MB (small) or ~39 MB (tiny) |
| **RAM** | ~500 MB |
| **Android Path** | sherpa-onnx Android AAR / JNI |
| **Verified Example** | sherpa-onnx Android examples |
| **Status** | APPROVED |

**Attribution:**
> Speech recognition powered by sherpa-onnx (Apache-2.0).

**Next Steps:**
- [ ] Integrate sherpa-onnx AAR into `voice` module.
- [ ] Verify Hindi + English transcription.

---

## Sherpa-ONNX TTS

| Field | Value |
|-------|-------|
| **Model** | Piper voice (e.g., en_US-lessac-medium) OR Kokoro ONNX |
| **Source** | https://github.com/k2-fsa/sherpa-onnx/releases |
| **License** | Apache-2.0 (Piper voices vary, check each) |
| **File Size** | ~60–100 MB per voice |
| **RAM** | ~300 MB |
| **Android Path** | sherpa-onnx Android AAR / JNI |
| **Verified Example** | sherpa-onnx TTS Android example |
| **Status** | APPROVED |

**Attribution:**
> Text-to-speech powered by sherpa-onnx and Piper.

**Next Steps:**
- [ ] Select 1–2 default voices.
- [ ] Test latency on Xiaomi 14.

---

## VAD (Voice Activity Detection)

| Field | Value |
|-------|-------|
| **Model** | silero-vad ONNX from sherpa-onnx |
| **Source** | sherpa-onnx releases |
| **License** | Apache-2.0 |
| **File Size** | ~1 MB |
| **RAM** | Negligible |
| **Android Path** | Built into sherpa-onnx |
| **Status** | APPROVED |

---

## Punctuation Model (Optional)

| Field | Value |
|-------|-------|
| **Model** | sherpa-onnx punctuation model |
| **Source** | sherpa-onnx releases |
| **License** | Apache-2.0 |
| **File Size** | ~10–50 MB |
| **Status** | DEFERRED — nice to have |

---

## OCR Model (Optional / Future)

| Field | Value |
|-------|-------|
| **Model** | Tesseract / ONNX OCR / PaddleOCR |
| **Source** | TBD |
| **License** | TBD |
| **Status** | NOT SELECTED — evaluate for document/image skill |

---

## License Summary Table

| Model | License | Commercial | Attribution Required | Status |
|-------|---------|------------|----------------------|--------|
| Gemma 2B IT | Gemma Terms | Yes | Yes | Candidate |
| Sherpa-ONNX ASR | Apache-2.0 | Yes | Recommended | Approved |
| Sherpa-ONNX TTS | Apache-2.0 | Yes | Recommended | Approved |
| Sherpa-ONNX VAD | Apache-2.0 | Yes | Recommended | Approved |
| Punctuation | Apache-2.0 | Yes | Recommended | Deferred |
| OCR | TBD | TBD | TBD | Not selected |

---

## Compliance Checklist

- [ ] All approved model licenses allow commercial distribution.
- [ ] Attribution text included in Settings → About → Open Source.
- [ ] Model files are not modified in a way that violates license terms.
- [ ] No GPL/AGPL models used without legal review.
