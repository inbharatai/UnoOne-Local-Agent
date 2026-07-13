#!/usr/bin/env python3
"""Fail CI when UnoOne V2's core architecture or integrity contracts regress.

The text scan covers first-party executable source, tests and bundled manifests. Generated vendor
bundles and lint baselines are excluded from string scanning; their source inputs and runtime safety
configuration are validated separately.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[2]

ACTIVE_ROOTS = (
    ROOT / "android-app" / "UnoOneAgent",
    ROOT / "web-runtime" / "page-agent-unoone" / "src",
    ROOT / "web-runtime" / "page-agent-unoone" / "tests",
    ROOT / "web-runtime" / "page-agent-unoone" / "e2e",
    ROOT / "installer-pwa" / "src",
    ROOT / "installer-pwa" / "tests",
    ROOT / "distribution" / "api" / "src",
    ROOT / "distribution" / "api" / "tests",
    ROOT / "scripts" / "catalog",
)

TEXT_SUFFIXES = {".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".mjs", ".json", ".xml", ".toml"}
SKIP_DIRS = {"build", "dist", "node_modules", ".gradle", ".git", "playwright-report", "test-results"}
SKIP_RELATIVE_PATHS = {
    "android-app/UnoOneAgent/app/lint-baseline.xml",
}
SKIP_RELATIVE_PREFIXES = (
    "android-app/UnoOneAgent/securebrowser/src/main/assets/page-agent/",
)

PROHIBITED_PATTERNS = {
    r"(?i)gemma[-_ ]?3n": "legacy Gemma 3n identifier",
    r"(?i)gemma-local": "legacy gemma-local folder",
    r"https?://example\.org": "dummy example.org URL",
    r"\bManifestSigningKey\b": "deleted inactive manifest signing key",
    r"\bManifestSignatureVerifier\b": "deleted API-dependent manifest verifier",
    r"\bManifestSigner\b": "deleted redundant bundled-manifest signer",
    r'"manifestSignature"\s*:': "blank/dead bundled-manifest signature field",
}

GEMMA_ID = "gemma-4-e2b"
GEMMA_FILE = "gemma-4-E2B-it.litertlm"
GEMMA_SIZE = 2_588_147_712
GEMMA_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"


def iter_active_files() -> Iterable[Path]:
    seen: set[Path] = set()
    for root in ACTIVE_ROOTS:
        if not root.exists():
            continue
        candidates = [root] if root.is_file() else root.rglob("*")
        for path in candidates:
            if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
                continue
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            relative = path.relative_to(ROOT).as_posix()
            if relative in SKIP_RELATIVE_PATHS or any(relative.startswith(prefix) for prefix in SKIP_RELATIVE_PREFIXES):
                continue
            resolved = path.resolve()
            if resolved not in seen:
                seen.add(resolved)
                yield path


def scan_prohibited_text(errors: list[str]) -> None:
    compiled = [(re.compile(pattern), label) for pattern, label in PROHIBITED_PATTERNS.items()]
    for path in iter_active_files():
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        relative = path.relative_to(ROOT)
        for pattern, label in compiled:
            match = pattern.search(text)
            if match:
                line = text.count("\n", 0, match.start()) + 1
                errors.append(f"{relative}:{line}: {label}")


def load_json(relative: str) -> object:
    path = ROOT / relative
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValueError(f"missing required file: {relative}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON in {relative}: {exc}") from exc


def validate_model_manifest(errors: list[str]) -> set[str]:
    relative = "android-app/UnoOneAgent/modelmanager/src/main/assets/models_manifest.json"
    try:
        value = load_json(relative)
    except ValueError as exc:
        errors.append(str(exc))
        return set()

    if not isinstance(value, dict) or set(value) != {"manifestVersion", "models"}:
        errors.append(f"{relative}: expected only manifestVersion and models top-level fields")
        return set()
    if value.get("manifestVersion") != 2:
        errors.append(f"{relative}: manifestVersion must be 2")
    models = value.get("models")
    if not isinstance(models, list) or not models:
        errors.append(f"{relative}: models must be a non-empty array")
        return set()

    ids: set[str] = set()
    folders: set[str] = set()
    gemma: dict[str, object] | None = None
    for model in models:
        if not isinstance(model, dict):
            errors.append(f"{relative}: model descriptor is not an object")
            continue
        model_id = model.get("id")
        folder = model.get("folder")
        if not isinstance(model_id, str) or not model_id:
            errors.append(f"{relative}: model id is missing")
            continue
        if model_id in ids:
            errors.append(f"{relative}: duplicate model id {model_id}")
        ids.add(model_id)
        if not isinstance(folder, str) or not folder or folder.startswith("/") or ".." in Path(folder).parts:
            errors.append(f"{relative}: invalid folder for {model_id}: {folder!r}")
        elif folder in folders:
            errors.append(f"{relative}: duplicate model folder {folder}")
        else:
            folders.add(folder)
        if model.get("version") in {None, "", "unqualified", "planned", "placeholder"}:
            errors.append(f"{relative}: {model_id} has a non-release artifact version")
        files = model.get("files")
        if not isinstance(files, list) or not files:
            errors.append(f"{relative}: {model_id} has no artifact files")
            continue
        for artifact in files:
            if not isinstance(artifact, dict):
                errors.append(f"{relative}: {model_id} contains a non-object artifact")
                continue
            name = artifact.get("name")
            asset = artifact.get("asset")
            url = artifact.get("url")
            sha256 = artifact.get("sha256")
            size = artifact.get("sizeBytes")
            if not isinstance(name, str) or not name or "/" in name or "\\" in name:
                errors.append(f"{relative}: {model_id} has unsafe artifact name {name!r}")
            if asset is None and (not isinstance(url, str) or not url.startswith("https://")):
                errors.append(f"{relative}: {model_id}/{name} must have an HTTPS URL or bundled asset")
            if asset is not None and (not isinstance(asset, str) or not asset):
                errors.append(f"{relative}: {model_id}/{name} has invalid bundled asset name")
            if not isinstance(sha256, str) or not re.fullmatch(r"[a-f0-9]{64}", sha256):
                errors.append(f"{relative}: {model_id}/{name} has invalid SHA-256")
            if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
                errors.append(f"{relative}: {model_id}/{name} has invalid sizeBytes")
        if model_id == GEMMA_ID:
            gemma = model

    if gemma is None:
        errors.append(f"{relative}: missing {GEMMA_ID}")
    else:
        files = gemma.get("files")
        artifact = files[0] if isinstance(files, list) and len(files) == 1 and isinstance(files[0], dict) else None
        if artifact is None:
            errors.append(f"{relative}: {GEMMA_ID} must have exactly one artifact")
        else:
            if artifact.get("name") != GEMMA_FILE:
                errors.append(f"{relative}: Gemma filename mismatch")
            if artifact.get("sizeBytes") != GEMMA_SIZE:
                errors.append(f"{relative}: Gemma size mismatch")
            if artifact.get("sha256") != GEMMA_SHA256:
                errors.append(f"{relative}: Gemma SHA-256 mismatch")
    return ids


def validate_language_manifest(errors: list[str], model_ids: set[str]) -> None:
    relative = "android-app/UnoOneAgent/languagepacks/src/main/assets/language_packs.json"
    try:
        value = load_json(relative)
    except ValueError as exc:
        errors.append(str(exc))
        return

    if not isinstance(value, dict) or set(value) != {"manifestVersion", "packs"}:
        errors.append(f"{relative}: expected only manifestVersion and packs top-level fields")
        return
    packs = value.get("packs")
    if not isinstance(packs, list) or not packs:
        errors.append(f"{relative}: packs must be a non-empty array")
        return

    ids: set[str] = set()
    codes: set[str] = set()
    for pack in packs:
        if not isinstance(pack, dict):
            errors.append(f"{relative}: pack is not an object")
            continue
        pack_id = pack.get("id")
        code = pack.get("languageCode")
        status = pack.get("status")
        downloadable = pack.get("downloadable", True)
        required = pack.get("requiredModelIds")
        optional = pack.get("optionalModelIds", [])
        if not isinstance(pack_id, str) or not pack_id:
            errors.append(f"{relative}: pack id is missing")
            continue
        if pack_id in ids:
            errors.append(f"{relative}: duplicate pack id {pack_id}")
        ids.add(pack_id)
        if not isinstance(code, str) or not re.fullmatch(r"[a-z]{2,3}-[A-Z]{2}", code):
            errors.append(f"{relative}: invalid language code for {pack_id}: {code!r}")
        elif code in codes:
            errors.append(f"{relative}: duplicate language code {code}")
        else:
            codes.add(code)
        if not isinstance(required, list) or not isinstance(optional, list):
            errors.append(f"{relative}: invalid dependency arrays for {pack_id}")
            continue
        dependencies = required + optional
        if len(dependencies) != len(set(dependencies)):
            errors.append(f"{relative}: duplicate dependency in {pack_id}")
        unknown = sorted(set(dependencies) - model_ids)
        if unknown:
            errors.append(f"{relative}: {pack_id} references unknown models: {', '.join(unknown)}")
        if downloadable is True:
            if status not in {"baseline", "beta", "stable"}:
                errors.append(f"{relative}: downloadable {pack_id} has invalid status {status}")
            if not required:
                errors.append(f"{relative}: downloadable {pack_id} has no required models")
        elif status == "planned" and required:
            errors.append(f"{relative}: planned non-downloadable {pack_id} must not bind unqualified models")


def validate_secure_browser(errors: list[str]) -> None:
    runtime = ROOT / "web-runtime/page-agent-unoone/src/index.ts"
    tools = ROOT / "web-runtime/page-agent-unoone/src/guarded-tools.ts"
    policy = ROOT / "android-app/UnoOneAgent/securebrowser/src/main/java/com/unoone/agent/securebrowser/BrowserSafetyPolicy.kt"
    for path in (runtime, tools, policy):
        if not path.exists():
            errors.append(f"missing required Secure Browser file: {path.relative_to(ROOT)}")
    if runtime.exists() and "experimentalScriptExecutionTool: false" not in runtime.read_text(encoding="utf-8"):
        errors.append("PageAgent arbitrary JavaScript execution is not explicitly disabled")
    if tools.exists() and "execute_javascript: null" not in tools.read_text(encoding="utf-8"):
        errors.append("PageAgent execute_javascript override is missing")
    if policy.exists():
        text = policy.read_text(encoding="utf-8")
        for required in ("PAYMENT", "CREDENTIAL", "CAPTCHA", "LEGAL_ACCEPTANCE", "FINAL_SUBMISSION"):
            if required not in text:
                errors.append(f"Browser safety policy is missing {required}")


def main() -> int:
    errors: list[str] = []
    scan_prohibited_text(errors)
    model_ids = validate_model_manifest(errors)
    validate_language_manifest(errors, model_ids)
    validate_secure_browser(errors)

    if errors:
        print("UnoOne V2 invariant check failed:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    print("UnoOne V2 invariants passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
