#!/usr/bin/env python3
"""One-time cleanup of stale dual-brain comments and instrumentation model paths.

This file is temporary and will be removed after the cleanup commit is verified.
"""

from pathlib import Path

REPLACEMENTS = {
    "android-app/UnoOneAgent/localbrain/build.gradle.kts": [
        (
            "// LiteRT-LM for Gemma 3n E4B on-device inference with manual tool calling",
            "// LiteRT-LM for Gemma 4 E2B on-device inference with manual tool calling",
        )
    ],
    "android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/eval/EvalPromptSet.kt": [
        (
            "accuracy score, for Gemma 3n E4B vs Gemma 4 E2B.",
            "accuracy score for the qualified Gemma 4 E2B artifact and backend configuration.",
        )
    ],
    "android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/LocalBrain.kt": [
        (
            "performs manual tool calling. Profile-aware: callers can load either Gemma 4 E2B or the legacy\n * Gemma 3n E4B through the same interface by passing a [BrainModelSpec].",
            "performs manual tool calling. Callers pass the sole Gemma 4 E2B [BrainModelSpec] so model\n * identity, backend preference and device gates remain explicit.",
        ),
        (
            "/** Legacy single-path load (default profile, Gemma 3n E4B). */",
            "/** Convenience load using the sole Gemma 4 E2B profile. */",
        ),
        (
            "/** Profile-aware load — loads [modelPath] as [spec] (Gemma 4 E2B or Gemma 3n E4B). */",
            "/** Loads [modelPath] using the explicit Gemma 4 E2B [spec]. */",
        ),
    ],
    "android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/UnoOneToolSet.kt": [
        (
            "Declares every capability UnoOne exposes to Gemma 3n E4B via LiteRT-LM manual tool calling.",
            "Declares every capability UnoOne exposes to Gemma 4 E2B via LiteRT-LM manual tool calling.",
        )
    ],
    "android-app/UnoOneAgent/localbrain/src/main/java/com/unoone/agent/localbrain/GemmaPlanner.kt": [
        (
            "profile's preferred backend order. Either Gemma 4 E2B or Gemma 3n E4B loads through this same\n * safe interface.",
            "Gemma 4 E2B profile's preferred backend order. No secondary or legacy brain is accepted by\n * this runtime contract.",
        ),
        (
            "Legacy single-arg load — loads [modelPath] as the default profile (Gemma 3n E4B). Preserved so\n     * existing callers/tests that only know a path keep compiling. New callers should pass a spec.",
            "Convenience single-arg load — loads [modelPath] with the sole Gemma 4 E2B profile. Callers\n     * that already hold the model specification should use the explicit overload below.",
        ),
    ],
    "android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/AgentOrchestrator.kt": [
        (
            "Multimodal vision gate for `describe_scene`. False by default: the shipped Gemma 3n E4B / Gemma 4\n * E2B `.litertlm` artifacts are text-only",
            "Multimodal vision gate for `describe_scene`. False by default: the shipped Gemma 4 E2B\n * `.litertlm` artifact is text-only",
        ),
        (
            "// ships (the loaded Gemma 3n E4B / Gemma 4 E2B models are text-only). When VISION_MODEL_ENABLED",
            "// ships (the loaded Gemma 4 E2B artifact is text-only). When VISION_MODEL_ENABLED",
        ),
    ],
    "android-app/UnoOneAgent/app/src/main/java/com/unoone/agent/execution/ActionExecutor.kt": [
        (
            "LiteRT-LM `Content.ImageBytes`. Null by default → vision is inactive (the shipped Gemma 3n E4B\n     * / Gemma 4 E2B models are text-only)",
            "LiteRT-LM `Content.ImageBytes`. Null by default → vision is inactive (the shipped Gemma 4 E2B\n     * artifact is text-only)",
        )
    ],
    "android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/localbrain/BrainEvalHarnessTest.kt": [
        (
            "[com.unoone.agent.core.eval.EvalSummary] to logcat. To compare Gemma 3n E4B vs Gemma 4 E2B, push\n * one model, run, record the numbers, push the other, run again.",
            "[com.unoone.agent.core.eval.EvalSummary] to logcat. Run it against the exact Gemma 4 E2B\n * artifact and record the backend, device and model hash with the result.",
        ),
        ("/path/to/gemma-4-e2b-it.litertlm", "/path/to/gemma-4-E2B-it.litertlm"),
        (
            "/sdcard/Android/data/com.unoone.agent/files/models/gemma-local/",
            "/sdcard/Android/data/com.unoone.agent/files/models/brain/gemma-4-e2b/",
        ),
    ],
    "android-app/UnoOneAgent/app/src/androidTest/java/com/unoone/agent/localbrain/GemmaPlannerAccuracyTest.kt": [
        ("Real accuracy test for the Gemma 3n E4B brain.", "Real device-time accuracy test for the Gemma 4 E2B brain."),
        ("/path/to/gemma-4-e2b-it.litertlm", "/path/to/gemma-4-E2B-it.litertlm"),
        (
            "/sdcard/Android/data/com.unoone.agent/files/models/gemma-local/",
            "/sdcard/Android/data/com.unoone.agent/files/models/brain/gemma-4-e2b/",
        ),
    ],
}


def main() -> None:
    changed = []
    for relative, replacements in REPLACEMENTS.items():
        path = Path(relative)
        text = path.read_text(encoding="utf-8")
        original = text
        for old, new in replacements:
            if old in text:
                text = text.replace(old, new)
            elif new not in text:
                raise SystemExit(f"Neither expected old nor replacement text found in {relative}: {old!r}")
        if text != original:
            path.write_text(text, encoding="utf-8")
            changed.append(relative)
    print("Updated files:")
    for path in changed:
        print(f" - {path}")


if __name__ == "__main__":
    main()
