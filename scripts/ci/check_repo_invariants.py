#!/usr/bin/env python3
"""Fail CI when UnoOne V3's core architecture or integrity contracts regress.

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
SKIP_DIRS = {"build", "dist", "node_modules", ".gradle", ".cxx", ".externalNativeBuild", ".git", "playwright-report", "test-results"}
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

GEMMA_ID = "gemma-4-e4b"
GEMMA_FILE = "gemma-4-E4B-it.litertlm"
GEMMA_SIZE = 3_659_530_240
GEMMA_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
GEMMA_REVISION = "28299f30ee4d43294517a4ac93abd6163412f07f"
LEGACY_GEMMA_ID = "gemma-4-e2b"
LEGACY_GEMMA_FILE = "gemma-4-E2B-it.litertlm"
LEGACY_GEMMA_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"


# Audited immutable GuiOwlArtifact values; never derived from the manifest under test.
OWL_ID = "gui-owl-1.5-4b-instruct-gguf"
OWL_REVISION = "9a79d301329062eb02e46f7ccad82c99075bfaaa"
OWL_REPOSITORY = "mradermacher/GUI-Owl-1.5-4B-Instruct-GGUF"
OWL_FILES = {
    "GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf": (2497282208, "8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232"),
    "GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf": (453974336, "b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073"),
}
OWL_DISCLOSURE = "Third-party quantization; canonical source-conversion commit unknown. Model card: MIT; embedded GGUF metadata: Apache-2.0. Retain both notices; no commercial clearance claimed."

QWEN_ID = "qwen3.5-2b-mnn"
QWEN_REVISION = "35781816d7b6a9dcb273a6765ac9563401951c3c"
# Non-LFS response-byte hashes / LFS API SHA-256 metadata; not device verification.
QWEN_FILES = {'config.json': (652, '92853033efe602f95efca3e1c05cd8b108f973c8beed417843a9671f8147ed8d'), 'export_args.json': (1040, 'a5b3a7d2c45e53c887e539259696d5fcec793637c9a3e9fbc7cd0dd008af6a87'), 'llm.mnn': (2148136, '23df98f8b341b277365e0bbca025c1d192939e3d32d7f79776352c6f32e77960'), 'llm.mnn.json': (5344018, '7131ff4f1a441add1039d371815ad94652ae6801f579591cb2f9ad90b5954025'), 'llm.mnn.weight': (1176647702, 'c93f71a2dbecf9328782bd38861656d8faa82e95e7f99607350074768a482054'), 'llm_config.json': (8692, 'a88234b36c2af0eff8e5c89667011badf71c15e30459eb0e21030a8f3f9ed240'), 'tokenizer.txt': (6465727, '7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4'), 'visual.mnn': (488096, '88fc40a7b676e90eb2cb86d854db15cb90b9eb1f34087ab0f48c5e43572c8dac'), 'visual.mnn.weight': (195587264, '8f90e106f5b9ae9a939faed240305cfdd5c6740ae91d3fc418a990bee0cce36b')}


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
    if value.get("manifestVersion") != 6:
        errors.append(f"{relative}: manifestVersion must be 6 for exactly four known profiles")
    models = value.get("models")
    if not isinstance(models, list) or not models:
        errors.append(f"{relative}: models must be a non-empty array")
        return set()

    ids: set[str] = set()
    folders: set[str] = set()
    llm_models: list[dict[str, object]] = []
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
        if model.get("type") == "llm":
            llm_models.append(model)
        if model_id == GEMMA_ID:
            gemma = model

    expected = {
        "gemma-4-e2b": ("gemma-4-E2B-it.litertlm", 2588147712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"),
        GEMMA_ID: (GEMMA_FILE, GEMMA_SIZE, GEMMA_SHA256, GEMMA_REVISION),
    }
    if len(llm_models) != 4 or {m.get("id") for m in llm_models} != set(expected) | {QWEN_ID, OWL_ID}:
        errors.append(f"{relative}: expected exactly E2B, E4B, Qwen MNN and Owl LLAMA_CPP LLM profiles")
    for model in llm_models:
        mid = model.get("id")
        if mid == OWL_ID:
            for key, expected_value in {
                "folder": "brain/" + OWL_ID, "backend": "cpu",
                "version": "GUI-Owl-1.5-4B-Instruct-GGUF-" + OWL_REVISION,
            }.items():
                if model.get(key) != expected_value:
                    errors.append(f"Owl exact {key} mismatch")
            if type(model.get("minRamMb")) is not int or model["minRamMb"] < 8192:
                errors.append("Owl minimum RAM must be at least 8192 MB")
            files = model.get("files", [])
            if not isinstance(files, list):
                errors.append("Owl requires decoder and projector artifacts")
                continue
            exact_files = [
                {"name": name, "sizeBytes": size, "sha256": sha, "archive": False,
                 "url": f"https://huggingface.co/{OWL_REPOSITORY}/resolve/{OWL_REVISION}/{name}"}
                for name, (size, sha) in OWL_FILES.items()
            ]
            if len(files) != 2 or any(f not in exact_files for f in files) or any(f not in files for f in exact_files):
                errors.append("Owl requires exactly the pinned decoder and projector artifacts")
            continue
        if mid == QWEN_ID:
            if model.get("folder") != "brain/" + QWEN_ID or model.get("backend") != "cpu":
                errors.append("Qwen requires exact folder and CPU backend")
            if model.get("version") != "Qwen3.5-2B-MNN-" + QWEN_REVISION:
                errors.append("Qwen requires pinned source version")
            if type(model.get("minRamMb")) is not int or model["minRamMb"] < 8192:
                errors.append("Qwen minimum RAM must be at least 8192 MB")
            files = model.get("files", [])
            if len(files) != 9 or any(not isinstance(f, dict) for f in files) or {f.get("name") for f in files if isinstance(f, dict)} != set(QWEN_FILES):
                errors.append("Qwen requires all nine exact artifacts including visual, tokenizer and configs")
            for artifact in files:
                if not isinstance(artifact, dict) or artifact.get("name") not in QWEN_FILES:
                    continue
                name = artifact["name"]
                size, sha = QWEN_FILES[name]
                exact = {"name": name, "sizeBytes": size, "sha256": sha, "archive": False,
                         "url": f"https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/resolve/{QWEN_REVISION}/{name}"}
                if artifact != exact:
                    errors.append(f"Qwen exact artifact mismatch: {name}")
            continue
        if mid not in expected:
            continue
        name, size, sha, revision = expected[mid]
        if model.get("folder") != "brain/" + mid:
            errors.append(f"{mid}: wrong folder")
        ram = model.get("minRamMb")
        if type(ram) is not int or ram < 8192:
            errors.append(f"{mid}: minimum RAM must be at least 8192 MB")
        files = model.get("files")
        if not isinstance(files, list) or len(files) != 1 or not isinstance(files[0], dict):
            errors.append(f"{mid}: exactly one artifact required")
            continue
        artifact = files[0]
        repo = name.removesuffix(".litertlm") + "-litert-lm"
        exact_url = f"https://huggingface.co/litert-community/{repo}/resolve/{revision}/{name}"
        for key, value in {"name": name, "sizeBytes": size, "sha256": sha, "url": exact_url, "archive": False}.items():
            if artifact.get(key) != value:
                errors.append(f"{mid}: exact pinned {key} mismatch")
        if artifact.get("asset") is not None:
            errors.append(f"{mid}: bundled artifact cannot override pinned download")
    return ids


def validate_owl_source_contract(errors: list[str]) -> None:
    """Check runtime and provenance independently of catalogue metadata."""
    base = ROOT / "android-app/UnoOneAgent/core/src/main/java/com/unoone/agent/core/model"
    try:
        artifact = (base / "GuiOwlArtifact.kt").read_text(encoding="utf-8")
        registry = (base / "BrainModel.kt").read_text(encoding="utf-8")
    except OSError as exc:
        errors.append(f"Owl source contract missing: {exc}")
        return
    constants = dict(re.findall(r'const val (\w+) = "([^"\n]*)"', artifact))
    names = list(OWL_FILES)
    for key, expected in {
        "MANIFEST_ID": OWL_ID, "FOLDER": "brain/" + OWL_ID,
        "REPOSITORY": OWL_REPOSITORY, "REVISION": OWL_REVISION,
        "PRIMARY_REFERENCE_REVISION": "3f061c2c562cc860c42bf32542a70e07a7ff4840",
        "DECODER": names[0], "PROJECTOR": names[1],
        "DECODER_SHA256": OWL_FILES[names[0]][1], "PROJECTOR_SHA256": OWL_FILES[names[1]][1],
        "PROVENANCE_DISCLOSURE": OWL_DISCLOSURE,
    }.items():
        if constants.get(key) != expected:
            errors.append(f"Owl source constant mismatch: {key}")
    for key, expected in {"DECODER_BYTES": OWL_FILES[names[0]][0], "PROJECTOR_BYTES": OWL_FILES[names[1]][0]}.items():
        if not re.search(rf"const val {key} = {expected}L\b", artifact):
            errors.append(f"Owl source constant mismatch: {key}")
    match = re.search(r"val GUI_OWL_1_5_4B_INSTRUCT = BrainModelSpec\((.*?)\n    \)", registry, re.S)
    block = match.group(1) if match else ""
    for key, expected in {
        "runtime": "BrainRuntime.LLAMA_CPP", "supportsBrowserProtocol": "false",
        "isDeviceVerified": "false", "preferredBackend": "BackendPreference.CPU_ONLY",
        "manifestId": "GuiOwlArtifact.MANIFEST_ID", "fileName": "GuiOwlArtifact.DECODER",
    }.items():
        if not re.search(rf"\b{key}\s*=\s*{re.escape(expected)}(?=\s*(?:[,\n]|$))", block):
            errors.append(f"Owl runtime contract mismatch: {key}")
    if 'experimentalLabel = "EXPERIMENTAL' not in block or "GuiOwlArtifact.PROVENANCE_DISCLOSURE" not in block:
        errors.append("Owl experimental provenance disclosure is required")


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
    # V3 moves privileged orchestration out of the website realm. The old index.ts flag
    # no longer proves the boundary; verify the actual bundle entry and native exposure.
    adapter = ROOT / "web-runtime/page-agent-unoone/src/dom-adapter.js"
    vite = ROOT / "web-runtime/page-agent-unoone/vite.config.ts"
    controller = ROOT / "android-app/UnoOneAgent/securebrowser/src/main/java/com/unoone/agent/securebrowser/SecureWebViewController.kt"
    if not adapter.is_file() or not vite.is_file() or "src/dom-adapter.js" not in vite.read_text():
        errors.append("V3 browser must bundle the unprivileged DOM adapter")
    elif any(token in adapter.read_text() for token in ("eval(", "new Function(", "MODEL_INVOKE", "__UNOONE_PAGE_AGENT_SESSION__", "postMessage(")):
        errors.append("DOM adapter exposes script execution or privileged bridge authority")
    if not controller.is_file() or any(token in controller.read_text() for token in ("addJavascriptInterface(", "addWebMessageListener(")):
        errors.append("Native browser controller must not expose privileged calls to website JavaScript")
    if tools.exists() and "execute_javascript: null" not in tools.read_text(encoding="utf-8"):
        errors.append("PageAgent execute_javascript override is missing")
    if policy.exists():
        text = policy.read_text(encoding="utf-8")
        for required in ("PAYMENT", "CREDENTIAL", "CAPTCHA", "LEGAL_ACCEPTANCE", "FINAL_SUBMISSION"):
            if required not in text:
                errors.append(f"Browser safety policy is missing {required}")


def validate_v3_corpus(errors: list[str]) -> None:
    # Execute schema, fixture, corpus and negative-aggregation tests, not source-text assertions.
    import runpy
    try:
        validator = runpy.run_path(str(ROOT / "evaluation/device-agent/validate.py"))
        validator["validate_corpus"]()
    except Exception as exc:
        errors.append(f"V3 schema/evaluation corpus: {exc}")


def main() -> int:
    errors: list[str] = []
    scan_prohibited_text(errors)
    model_ids = validate_model_manifest(errors)
    validate_owl_source_contract(errors)
    validate_language_manifest(errors, model_ids)
    validate_secure_browser(errors)
    validate_v3_corpus(errors)

    if errors:
        print("UnoOne V3 invariant check failed:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    print("UnoOne V3 invariants passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
