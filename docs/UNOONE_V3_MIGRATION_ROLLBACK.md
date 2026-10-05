# V3 migration and rollback — preserve data and E4B

## Before migration

Record current source commit/dirty diff, APK version and SHA-256, signing certificate, Android build, selected model, exact model path/hash/size and user-approved settings. Export user data through the existing explicit export UI and verify the exported file can be read; do not assume Android backup includes private models or that an export includes every database/schema. Keep original model files and a trusted baseline APK outside the build output directory. Never use `pm clear`, uninstall, or clear storage as a routine rollback step.

Preserve an original-source export of baseline **69bf5e097b85a1bc40da14c07609dde8ca765cbd** (short `69bf5e0`). From the repository root, choose a new output filename outside the checkout:

```sh
git archive --format=zip --output=../UnoOne-original-69bf5e0.zip 69bf5e097b85a1bc40da14c07609dde8ca765cbd
```

This command is an instruction, not a claim that this documentation pass created the archive. `git archive` exports tracked source only: no APK, downloaded models, untracked secrets, local SDK or user data. Keep those backups separately and hash all retained artifacts. Avoid resetting/cleaning the active working tree; use a separate checkout/worktree if rebuilding baseline.

## Migration behavior

Manifest version 4 adds exact E2B while retaining E4B and speech entries. New/unknown profile selection defaults E2B; persisted `gemma-4-e4b` resolves unchanged. Both profiles remain unqualified until real evidence is approved. Existing E4B files are never an automatic migration deletion target; legacy qualified-cleanup body/gate is inert. Explicit user-directed uninstall remains possible and should not be confused with automatic cleanup.

Download/install into staging, verify pinned size and SHA-256, sync and rename only after verification. Preserve old target on failed activation. Archive payloads use staged inventory verification and **two renames** (live→backup, staged→live). A crash between renames can leave live absent while backup remains. Health must fail closed; no automatic backup recovery or whole-descriptor atomic transaction is established. Do not manually trust orphan staging/backup directories without comparing verified inventory and provenance.

## Preferred rollback: model selection first

1. Stop work, turn master automation off, cancel speech/inference and require unload/idle proof. A quarantined native close may require force-stop/restart before changing runtime ownership.
2. Select retained E4B using existing model selection UI. Verify the exact artifact before load; do not delete E2B or E4B files to force selection.
3. Run explicit load/self-test and controlled non-destructive smoke checks. This restores a candidate fallback, not a device-qualified release. Preserve diagnostic evidence if loading fails.

## APK rollback without data destruction

Use the original trusted APK with the **same application ID and compatible signing certificate**. Back up/export data first and assess whether the older app can read the current database/preferences. An older APK is not automatically schema-compatible; if no proven reverse migration exists, stop rather than clear storage.

```sh
adb install -r -d /path/to/trusted-baseline.apk
```

Windows PowerShell example:

```powershell
adb install -r -d "C:\Backups\unoone-baseline.apk"
```

`-r` requests replacement preserving app data; `-d` requests version downgrade. Android/production builds may reject downgrade (often permitted only for debuggable packages), signature mismatches or schema incompatibility. **Do not respond by uninstalling or clearing data.** Retain the current installation, model files and exports; obtain a compatible same-signer recovery build or reviewed migration procedure. Installing a different-signer APK over existing data is not a supported shortcut. Recheck settings/permissions, model selection and integrity after a successful replacement; do not grant permissions silently.

## Recovery gates still pending

No APK downgrade, database rollback, file recovery, disk-full, rename-failure/power-loss or native residency rollback was physically tested by this documentation pass. Directory fsync durability, cross-process serialization, archive expansion quota and complete multi-archive transactions remain follow-up work. Qualification metadata is evidence-only and not a license to delete fallback artifacts. See [model strategy](UNOONE_V3_MODEL_STRATEGY.md) and [status](UNOONE_V3_DEVELOPMENT_STATUS.md).
