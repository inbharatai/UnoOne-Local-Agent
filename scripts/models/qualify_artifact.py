#!/usr/bin/env python3
"""Create an integrity-measured UnoOne model artifact record.

This script never downloads a model and never invents provenance. It accepts a local artifact that
was obtained through an approved upstream process, computes its exact size and SHA-256, and writes a
catalogue candidate. Device and production qualification must be advanced separately after evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Final

BUFFER_SIZE: Final[int] = 1024 * 1024
ALLOWED_RUNTIMES: Final[set[str]] = {"litertlm", "onnx", "sherpa-onnx", "tflite"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be positive")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, help="Local model artifact to measure")
    parser.add_argument("--id", required=True, help="Stable UnoOne artifact id")
    parser.add_argument("--version", required=True, help="Artifact version")
    parser.add_argument("--runtime", required=True, choices=sorted(ALLOWED_RUNTIMES))
    parser.add_argument("--license", required=True, dest="license_name")
    parser.add_argument("--provider", required=True, help="Upstream provider, e.g. Google")
    parser.add_argument("--repository", required=True, help="Exact upstream repository/model id")
    parser.add_argument("--revision", required=True, help="Immutable upstream commit or revision")
    parser.add_argument("--upstream-file", default="", help="Exact upstream file name")
    parser.add_argument("--minimum-ram-mb", required=True, type=positive_int)
    parser.add_argument("--recommended-ram-mb", required=True, type=positive_int)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    artifact = args.artifact.expanduser().resolve()
    if not artifact.is_file():
        raise SystemExit(f"Artifact does not exist or is not a file: {artifact}")
    if artifact.stat().st_size == 0:
        raise SystemExit(f"Artifact is empty: {artifact}")
    if args.recommended_ram_mb < args.minimum_ram_mb:
        raise SystemExit("recommended RAM cannot be lower than minimum RAM")

    record = {
        "schemaVersion": 1,
        "id": args.id,
        "version": args.version,
        "runtime": args.runtime,
        "fileName": artifact.name,
        "sizeBytes": artifact.stat().st_size,
        "sha256": sha256_file(artifact),
        "license": args.license_name,
        "source": {
            "provider": args.provider,
            "repository": args.repository,
            "revision": args.revision,
            "upstreamFile": args.upstream_file or artifact.name,
        },
        "minimumRamMb": args.minimum_ram_mb,
        "recommendedRamMb": args.recommended_ram_mb,
        "qualificationStatus": "integrity-verified",
        "testedDevices": [],
        "notes": "Generated from local bytes; real-device load/tool qualification remains pending.",
    }

    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(record, indent=2, ensure_ascii=False) + os.linesep, encoding="utf-8")
    temporary.replace(output)

    print(json.dumps({"output": str(output), "sizeBytes": record["sizeBytes"], "sha256": record["sha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
