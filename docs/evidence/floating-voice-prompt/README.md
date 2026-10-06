# Rejected compact voice prompt experiments

**Negative evidence only. Both V1 and V2 rejected for production. FULL remains unchanged.**

| Experiment | Baseline valid/correct | Candidate valid/correct | Failure |
|---|---:|---:|---|
| [V1 raw archive](voice-prompt-ab/report.md) | 2/2 | 0/2 | Invalid tool-call envelope in both cases |
| [V2 raw archive](voice-prompt-v2/report.md) | 4/4 | 3/4 | F1 emits unsupported official `focus` action |

Real host Owl/JNI generation used synthetic fixtures. No physical-phone inference, dispatch or completed app action is established. Invalid faster responses are unusable; no successful-action speedup or noninferiority claim follows. V2 N2 lands on the left-inclusive target edge, not its center, and gives no robustness margin. V1 raw-coordinate diagnostics are not codec success or repaired output. TTFT/generated-output-token/stage counters were not exposed.

## Preservation and provenance

All raw UTF-8 text from both original experiment trees is copied byte-for-byte, including prompts, raw outputs, codec failures, frozen bbox/goal metadata, result/timing/resource records, model and source pins, compiler/invocation commands, scripts and historical reports. `archive-manifest.json` records every original file, size, SHA-256, and storage location. PNG fixtures, JVM class files and JNI binaries remain **outside git** under `/agent/workspace/owl-native-prep/voice-prompt-ab` and `voice-prompt-v2`. Referenced model/library paths and their original hash receipts remain in the copied receipts. These paths require the original host artifacts; this text archive alone is not a self-contained model bundle.

Historical report wording (including V1 raw wall percentage) is preserved without alteration for hash fidelity; it is not an endorsed usable speedup. Original manifests hash the original layout and are not rewritten to imply relocation. The new archive manifest supplies the relocation map.

## Candidate quarantine

[Evaluation-only sources/tests and host instructions](../../../evaluation/voice-prompt-experiments/README.md) retain the candidate bytes and source SHA receipts. Only three newly untracked candidate files were moved out of Android main/test source sets. Published production baseline/protocol tests, model/JNI, and OwlPromptBuilder were not changed. Stale build outputs may still contain old candidate classes; final clean build/artifact inspection belongs to the parent gate and is not claimed here.

Final application test counts must be recomputed after that gate; evaluation-only candidate tests are separate and must not be represented as APK/module tests. No new total or release identity is asserted.
