# UnoOne V2 Validation Baseline

Recorded at the start of the `validation/xiaomi14-end-to-end` branch, before any
laptop or physical-device testing.

## Starting point

- **Repository:** https://github.com/inbharatai/UnoOne-Local-Agent
- **Starting commit (origin/main, resolved after `git fetch origin --prune`):**
  `90e879e42333bbdc71be0cff5bb39287995b59eb`
  - Subject: `docs: record merged V2 status and remaining release gates`
- **Validation branch:** `validation/xiaomi14-end-to-end` (created from clean `origin/main`)
- **Archive branch (must NOT delete):** `origin/archive/main-pre-unoone-v2-2026-07` (confirmed present on remote)

## Local WIP preservation (NOT carried onto validation branch)

The working tree was on the deleted `feature/gemma-4-e2b-brain` (`b505cf0`) with
uncommitted edits. Inspection showed those edits were a **regression** toward V1:
they re-added `BrainModelId.GEMMA_3N_E4B`, `ModelFamily.GEMMA_3N`, and reverted the
honest V2 comments ("Model selection is no longer a product feature"). They were
preserved two ways and NOT applied to the validation branch:

1. Patch backup: `../unoone-wip-backup/working-tree.patch` (33,448 B),
   `../unoone-wip-backup/staged.patch` (81 B),
   `../unoone-wip-backup/untracked-files.txt` (0 B — no untracked files existed).
2. Git stash: `stash@{0}: On feature/gemma-4-e2b-brain: pre-validation-regressive-local-wip`
   (8 files, +118/-248).

The stash is left **untouched**. `git stash pop` will NOT be run on the validation
branch. The stash may be inspected later with `git stash show -p stash@{0}` and
dropped only after the validation branch is safely pushed and the patch backup is
retained.

## Architecture guards (clean-main baseline)

- `python scripts/ci/check_repo_invariants.py` → **PASS** ("UnoOne V2 invariants passed", exit 0).
- `grep -R "GEMMA_3N" android-app/UnoOneAgent` → **No matches** (V1 dual-brain fully removed).
- `grep -R "gemma-local" android-app/UnoOneAgent` → single doc-only match
  `README.md:70` ("There is no active `gemma-local` or Gemma 3n compatibility folder in V2."),
  i.e. a truthful negative claim, not active code.
- `BrainModel.kt:10` → `enum class BrainModelId { GEMMA_4_E2B }`
- `BrainModel.kt:13` → `enum class ModelFamily { GEMMA_4 }`

## Laptop environment (Phase 1 — to be filled as components are verified)

| Tool        | Version                                   | Status |
|-------------|-------------------------------------------|--------|
| OS          | Windows 11 Home Single Language 26200     | detected |
| Git         | 2.53.0.windows.1                          | OK |
| JDK         | Temurin 17.0.19+10 (OpenJDK 64-Bit)       | OK |
| Node.js     | v24.13.0                                  | OK |
| npm         | 11.6.2                                    | OK |
| Python      | 3.12.10                                   | OK |
| adb         | 1.0.41 (platform-tools 36.0.2-14143358)  | OK |
| JAVA_HOME   | C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\ | set |
| ANDROID_HOME | unset in env; SDK at C:\Users\reetu\AppData\Local\Android\Sdk; local.properties points Gradle at it; set inline per session | OK |
| Android platforms | android-34, android-35, android-36, android-36.1 | API 35 present |
| Build-tools | 34.0.0, 35.0.0, 36.1.0                   | 35.0.0 present |
| NDK         | 25.1.8937393                              | present |
| git-lfs     | 3.7.1                                    | OK |
| gh CLI      | 2.87.3                                    | OK |
| Disk free   | 507 GB (C:)                              | OK (≫ 2.59 GB model) |

## Initial defect list

(To be appended as the audit and gates proceed. None yet — clean main baseline
passes invariants and the V2 architecture contract is intact.)

## Baseline test results

(To be appended from Phase 2 automated gates.)