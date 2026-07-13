#!/usr/bin/env python3
"""Fail CI when UnoOne V2's single-brain and artifact invariants drift."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-app" / "UnoOneAgent"
MANIFEST = ANDROID / "modelmanager" / "src" / "main" / "assets" / "models_manifest.json"
BRAIN_MODEL = ANDROID / "core" / "src" / "main" / "java" / "com" / "unoone" / "agent" / "core" / "model" / "BrainModel.kt"

EXPECTED_ID = "gemma-4-e2b"
EXPECTED_FILE = "gemma-4-E2B-it.litertlm"
EXPECTED_SIZE = 2_588_147_712
EXPECTED_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
EXPECTED_CONTEXT = 32_768

FORBIDDEN_RUNTIME_PATTERNS = {
    "ModelFamily.GEMMA_3N": re.compile(r"ModelFamily\.GEMMA_3N"),
    "BrainModelId.GEMMA_3N": re.compile(r"BrainModelId\.GEMMA_3N"),
    "Gemma 3n manifest id": re.compile(r"[\"']gemma-3n[^\"']*[\"']", re.IGNORECASE),
    "legacy gemma-local folder": re.compile(r"[\"']gemma-local(?:/)?[\"']", re.IGNORECASE),
}


def fail(message: str) -> None:
    print(f"UnoOne V2 invariant failure: {message}", file=sys.stderr)
    raise SystemExit(1)


def verify_manifest() -> None:
    try:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot parse {MANIFEST.relative_to(ROOT)}: {exc}")

    llms = [model for model in manifest.get("models", []) if model.get("type") == "llm"]
    if len(llms) != 1:
        fail(f"expected exactly one LLM descriptor, found {len(llms)}")

    model = llms[0]
    if model.get("id") != EXPECTED_ID:
        fail(f"sole LLM id must be {EXPECTED_ID!r}, found {model.get('id')!r}")
    files = model.get("files", [])
    if len(files) != 1:
        fail(f"{EXPECTED_ID} must declare exactly one artifact, found {len(files)}")

    artifact = files[0]
    expected = {
        "name": EXPECTED_FILE,
        "sizeBytes": EXPECTED_SIZE,
        "sha256": EXPECTED_SHA256,
    }
    for key, value in expected.items():
        if artifact.get(key) != value:
            fail(f"{EXPECTED_ID} {key} must be {value!r}, found {artifact.get(key)!r}")
    if not str(artifact.get("url", "")).startswith("https://"):
        fail("development Gemma artifact URL must use HTTPS")


def verify_registry() -> None:
    text = BRAIN_MODEL.read_text(encoding="utf-8")
    required_fragments = [
        "enum class BrainModelId { GEMMA_4_E2B }",
        "enum class ModelFamily { GEMMA_4 }",
        f'fileName = "{EXPECTED_FILE}"',
        f"maximumContextTokens = {EXPECTED_CONTEXT:,}".replace(",", "_"),
        "val all: List<BrainModelSpec> = listOf(GEMMA_4_E2B)",
    ]
    for fragment in required_fragments:
        if fragment not in text:
            fail(f"BrainModel registry is missing required fragment: {fragment}")


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
    verify_manifest()
    verify_registry()
    verify_no_legacy_runtime_references()
    print(
        "UnoOne V2 invariants verified: one Gemma 4 E2B brain, exact artifact integrity, "
        "32K context, no legacy runtime identifiers."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
