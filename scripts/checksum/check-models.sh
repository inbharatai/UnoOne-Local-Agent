#!/bin/bash
# Verify model file integrity with SHA-256 checksums
set -e

MODELS_ROOT="../../models"
CHECKSUM_FILE="checksums.txt"

echo "UnoOne Model Checksum Verification"
echo "==================================="
echo

if [ ! -f "$CHECKSUM_FILE" ]; then
    echo "No checksums.txt found. Generating..."
    find "$MODELS_ROOT" -type f -exec sha256sum {} + > "$CHECKSUM_FILE"
    echo "checksums.txt generated."
    exit 0
fi

echo "Verifying files against checksums.txt..."
while read -r expected_hash file_path; do
    if [ -f "$file_path" ]; then
        actual_hash=$(sha256sum "$file_path" | awk '{print $1}')
        if [ "$expected_hash" == "$actual_hash" ]; then
            echo "[OK] $file_path"
        else
            echo "[FAIL] $file_path — checksum mismatch"
        fi
    else
        echo "[MISSING] $file_path"
    fi
done < "$CHECKSUM_FILE"

echo
echo "Verification complete."
