#!/usr/bin/env python3
"""Fail when UnoOne V3's dual-profile brain and artifact invariants drift."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-app" / "UnoOneAgent"
MANIFEST = ANDROID / "modelmanager" / "src" / "main" / "assets" / "models_manifest.json"
BRAIN_MODEL = ANDROID / "core" / "src" / "main" / "java" / "com" / "unoone" / "agent" / "core" / "model" / "BrainModel.kt"
PHONE_PLANNER = ANDROID / "localbrain" / "src" / "main" / "java" / "com" / "unoone" / "agent" / "localbrain" / "GemmaPlanner.kt"
PAGE_PLANNER = ANDROID / "localbrain" / "src" / "main" / "java" / "com" / "unoone" / "agent" / "localbrain" / "PageAgentGemmaPlanner.kt"
LANGUAGE_PACKS = ANDROID / "languagepacks" / "src" / "main" / "assets" / "language_packs.json"

EXPECTED_MANIFEST_VERSION = 4
EXPECTED_ID = "gemma-4-e4b"
EXPECTED_FOLDER = "brain/gemma-4-e4b"
EXPECTED_FILE = "gemma-4-E4B-it.litertlm"
EXPECTED_SIZE = 3_659_530_240
EXPECTED_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
EXPECTED_REVISION = "28299f30ee4d43294517a4ac93abd6163412f07f"
EXPECTED_CONTEXT = 32_768
EXPECTED_DEFAULT_CONTEXT = 2_048

FORBIDDEN_RUNTIME_PATTERNS = {
    "ModelFamily.GEMMA_3N": re.compile(r"ModelFamily\.GEMMA_3N"),
    "BrainModelId.GEMMA_3N": re.compile(r"BrainModelId\.GEMMA_3N"),
    "Gemma 3n manifest id": re.compile(r"[\"']gemma-3n[^\"']*[\"']", re.IGNORECASE),
    "legacy gemma-local folder": re.compile(r"[\"']gemma-local(?:/)?[\"']", re.IGNORECASE),
}


def fail(message: str) -> None:
    print(f"UnoOne V3 invariant failure: {message}", file=sys.stderr)
    raise SystemExit(1)


def verify_manifest() -> None:
    from ci.check_repo_invariants import validate_model_manifest
    errors = []
    validate_model_manifest(errors)
    if errors:
        fail("; ".join(errors))


def verify_registry() -> None:
    text = BRAIN_MODEL.read_text(encoding="utf-8")
    required_fragments = [
        "enum class BrainModelId { GEMMA_4_E4B, GEMMA_4_E2B }",
        "enum class ModelFamily { GEMMA_4 }",
        'manifestId = "gemma-4-e2b"',
        'modelFolder = "brain/gemma-4-e2b"',
        'fileName = "gemma-4-E2B-it.litertlm"',
        f'manifestId = "{EXPECTED_ID}"',
        f'modelFolder = "{EXPECTED_FOLDER}"',
        f'fileName = "{EXPECTED_FILE}"',
        f"maximumContextTokens = {EXPECTED_CONTEXT:,}".replace(",", "_"),
        f"defaultContextTokens = {EXPECTED_DEFAULT_CONTEXT:,}".replace(",", "_"),
        "val all: List<BrainModelSpec> = listOf(GEMMA_4_E2B, GEMMA_4_E4B)",
        "val defaultProfile: BrainModelSpec = GEMMA_4_E2B",
    ]
    for fragment in required_fragments:
        if fragment not in text:
            fail(f"BrainModel registry is missing required fragment: {fragment}")


def verify_runtime_context_and_kws() -> None:
    for path in (PHONE_PLANNER, PAGE_PLANNER):
        text = path.read_text(encoding="utf-8")
        if "maxNumTokens =" not in text or "E4bRuntimeBudgets" not in text:
            fail(f"{path.relative_to(ROOT)} does not visibly apply the E4B runtime context budget")
    language_text = LANGUAGE_PACKS.read_text(encoding="utf-8")
    if '"sherpa-kws-en"' not in language_text:
        fail("language packs must reference the explicitly labelled KWS support model")
    if '"vad"' in language_text or "shared/vad" in language_text:
        fail("ASR/KWS support must not be labelled as a VAD model")


def verify_no_legacy_runtime_references() -> None:
    violations: list[str] = []
    for path in ANDROID.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".json"}:
            continue
        if any(part in {"build", ".gradle"} for part in path.parts):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for label, pattern in FORBIDDEN_RUNTIME_PATTERNS.items():
            if pattern.search(text):
                violations.append(f"{path.relative_to(ROOT)}: {label}")
    if violations:
        fail("legacy runtime references found:\n  " + "\n  ".join(sorted(violations)))


def main() -> int:
    from ci.check_repo_invariants import main as check_repository
    if check_repository() != 0:
        return 1
    verify_manifest()
    verify_registry()
    verify_runtime_context_and_kws()
    verify_no_legacy_runtime_references()
    print(
        "UnoOne V3 invariants verified: exactly two Gemma 4 E2B/E4B Android profiles, exact artifact integrity, "
        "immutable E2B/E4B revisions, enforced 2K runtime configuration, labelled KWS support, and no legacy Gemma 3n runtime identifiers."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
