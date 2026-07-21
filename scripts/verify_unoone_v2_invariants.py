#!/usr/bin/env python3
"""Fail when UnoOne V2's E4B-only brain and artifact invariants drift."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-app" / "UnoOneAgent"
MANIFEST = ANDROID / "modelmanager" / "src" / "main" / "assets" / "models_manifest.json"
BRAIN_MODEL = ANDROID / "core" / "src" / "main" / "java" / "com" / "unoone" / "agent" / "core" / "model" / "BrainModel.kt"

EXPECTED_MANIFEST_VERSION = 3
EXPECTED_ID = "gemma-4-e4b"
EXPECTED_FOLDER = "brain/gemma-4-e4b"
EXPECTED_FILE = "gemma-4-E4B-it.litertlm"
EXPECTED_SIZE = 3_659_530_240
EXPECTED_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
EXPECTED_CONTEXT = 32_768
EXPECTED_DEFAULT_CONTEXT = 2_048

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

    if manifest.get("manifestVersion") != EXPECTED_MANIFEST_VERSION:
        fail(
            f"manifestVersion must be {EXPECTED_MANIFEST_VERSION}, "
            f"found {manifest.get('manifestVersion')!r}"
        )

    llms = [model for model in manifest.get("models", []) if model.get("type") == "llm"]
    if len(llms) != 1:
        fail(f"expected exactly one LLM descriptor, found {len(llms)}")

    model = llms[0]
    if model.get("id") != EXPECTED_ID:
        fail(f"sole LLM id must be {EXPECTED_ID!r}, found {model.get('id')!r}")
    if model.get("folder") != EXPECTED_FOLDER:
        fail(f"sole LLM folder must be {EXPECTED_FOLDER!r}, found {model.get('folder')!r}")
    if int(model.get("minRamMb", 0)) < 8192:
        fail("E4B minimum RAM gate must be at least 8192 MB")

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
    url = str(artifact.get("url", ""))
    if not url.startswith("https://"):
        fail("Gemma artifact URL must use HTTPS")
    if "-web" in url or "-web" in str(artifact.get("name", "")):
        fail("Android catalogue must not use the web-specific E4B artifact")


def verify_registry() -> None:
    text = BRAIN_MODEL.read_text(encoding="utf-8")
    required_fragments = [
        "enum class BrainModelId { GEMMA_4_E4B }",
        "enum class ModelFamily { GEMMA_4 }",
        f'manifestId = "{EXPECTED_ID}"',
        f'modelFolder = "{EXPECTED_FOLDER}"',
        f'fileName = "{EXPECTED_FILE}"',
        f"maximumContextTokens = {EXPECTED_CONTEXT:,}".replace(",", "_"),
        f"defaultContextTokens = {EXPECTED_DEFAULT_CONTEXT:,}".replace(",", "_"),
        "val all: List<BrainModelSpec> = listOf(GEMMA_4_E4B)",
        "val defaultProfile: BrainModelSpec = GEMMA_4_E4B",
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
        "UnoOne V2 invariants verified: one Gemma 4 E4B Android brain, exact artifact integrity, "
        "2K default / 32K maximum context, no legacy Gemma 3n runtime identifiers."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
