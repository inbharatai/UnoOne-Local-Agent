# UnoOne V3 — build on your laptop, install and record phone results

> **Historical 0.6/0.7 delivery identities; reusable laptop/ADB setup — banner added 2026-10-07.** The 842-test 0.6 results and 0.7 Owl pointer below are historical, not the current APK. General setup steps remain useful; select the exact revision/artifact from current receipts rather than reusing an old APK hash as current evidence.
>
> Current main entry points: [README](../README.md) → [Floating voice guide](FLOATING_VOICE_GUIDE.md) → [0.8 artifact/build receipts](evidence/floating-voice-delivery/results.json). Existing 0.8.0-alpha-voice / versionCode 8 host gates record **1006 JVM tests passed**, lint and both APK assemblies; physical qualification remains **PENDING**. These are existing main receipts, not a new cleanup validation run.

> **GUI-Owl users:** the current 0.7.0-alpha-owl build has a dedicated [GUI-Owl 4B setup guide](GUI_OWL_4B_PHONE_SETUP.md). The 0.6 receipts below are historical; general laptop/ADB instructions remain useful.

## Status and scope: read before pasting

This guide targets **0.6.0-alpha-v3 / versionCode 6**, including Task Board. Current host gates passed: **842 JVM tests, zero failures/errors/skips**, app lint and both APK assemblies; [matching artifact identities and receipts](evidence/multitask-delivery/results.json). No phone qualification is claimed. Lint retains 15 historical baseline-suppressed errors. The [historical 770-test 0.5 receipts](evidence/phone-delivery/results.json) and older repair4 receipts remain separate. Use the exact main revision confirmed in the delivery message and record `git rev-parse HEAD`; do not infer a future revision.

Compiled pinned MNN Qwen3.5-2B is integrated with LocalBrain and the native browser planner and can be explicitly selected. Real host toy probes observed text `4`, JSON sum `4`, and image `red`. Those probes are not Android inference, an accuracy score or a phone memory result. **Android inference, all 100 physical tasks and sustained thermal/battery qualification remain PENDING.**

Gemma 4 E2B remains the new/unknown-selection default. Verified E4B files and existing selection remain recoverable; selecting Qwen does not delete them. There is no automatic model or cloud fallback. Qwen native libraries are **arm64-v8a only**. Even if a base APK installs on another ABI, that does not imply Qwen works there; 32-bit Qwen is unsupported.

## 1. Install the laptop tools

On Windows install Git for Windows (including Git Bash), Node.js 22.12+ with npm, Python 3, JDK **17**, and Android Studio. In Android Studio → SDK Manager install:

- Android SDK Platform **35**, SDK build tools, Platform Tools, Command-line Tools (latest).
- Show Package Details → NDK (Side by side) **27.2.12479018**.
- CMake **3.22.1** (not an arbitrary newer version).

Configure Android Studio's Gradle JDK to the same JDK 17. A bundled JBR may be a different version; check, do not assume. The first build needs internet for Gradle, npm and the pinned MNN source archive. Models are a separate download; inference is local afterward. Keep adequate free disk for SDK/native compilation and model staging (more than the final model size).

## 2. Get the main development source

These are commands for **your own laptop**, not paths inside the build agent. Choose one shell and use its syntax. Do not reset or overwrite local changes.

### PowerShell — new checkout

```powershell
# Use the main commit confirmed in the delivery message.
New-Item -ItemType Directory -Force "$HOME\source" | Out-Null
Set-Location "$HOME\source"
git clone --branch main --single-branch https://github.com/inbharatai/UnoOne-Local-Agent.git
if ($LASTEXITCODE -ne 0) { throw "Clone failed" }
Set-Location UnoOne-Local-Agent
git rev-parse HEAD
git status --short
```

### PowerShell — existing checkout alternative

```powershell
Set-Location "$HOME\source\UnoOne-Local-Agent"
git status --short
# If the output lists local changes, stop and preserve/review them first.
git switch main
if ($LASTEXITCODE -ne 0) { throw "Switch failed" }
git pull --ff-only origin main
if ($LASTEXITCODE -ne 0) { throw "Pull failed" }
git rev-parse HEAD
```

### Git Bash — new checkout or existing checkout

```bash
# Use the delivered main revision. New checkout:
set -e
mkdir -p "$HOME/source"
cd "$HOME/source"
git clone --branch main --single-branch https://github.com/inbharatai/UnoOne-Local-Agent.git
cd UnoOne-Local-Agent
git rev-parse HEAD
git status --short
# For an EXISTING checkout instead: cd to it; inspect git status --short;
# only with a clean worktree run: git switch main && git pull --ff-only origin main
```

## 3. Configure SDK and Java

### PowerShell

Start at the repository root. Replace the one JDK placeholder with your installed JDK 17 directory (the directory containing `bin\java.exe`). Session variables below apply to this terminal.

```powershell
$env:JAVA_HOME = 'C:\REPLACE_WITH_YOUR_JDK_17_DIRECTORY'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Command failed: $LASTEXITCODE" } }
java -version; Check-Exit
node --version; Check-Exit
npm --version; Check-Exit
python --version; Check-Exit
adb version; Check-Exit
sdkmanager.bat 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'; Check-Exit
sdkmanager.bat --licenses; Check-Exit
```

Read and accept SDK licenses yourself. Alternatively set `sdk.dir` in the untracked `android-app/UnoOneAgent/local.properties` using your actual SDK path. Never commit laptop-specific paths.

### Git Bash

```bash
set -e
export JAVA_HOME='/c/REPLACE_WITH_YOUR_JDK_17_DIRECTORY'
export ANDROID_HOME="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
java -version
node --version
npm --version
python --version
adb version
sdkmanager.bat 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
sdkmanager.bat --licenses
```

If Git Bash cannot resolve Windows tooling, use the PowerShell build block rather than mixing shell quoting.

## 4. Bundle the browser BEFORE Gradle

CMake FetchContent builds MNN at revision [`024a946b0b8fcf87c8a418229fadd4cd7858ffba`](https://github.com/alibaba/MNN/tree/024a946b0b8fcf87c8a418229fadd4cd7858ffba), archive SHA-256 `7bf677961508973f113889aa8c99851c7426b29837fbdb10d9c94b6dd58800e6`. Do not replace the revision with a moving branch or disable integrity checks to fix a network failure. The source archive is fetched into build storage; raw model weights are **not committed to Git**.

### PowerShell — from repository root

```powershell
python scripts/ci/check_repo_invariants.py; Check-Exit
Push-Location web-runtime/page-agent-unoone
npm ci; Check-Exit
npm run typecheck; Check-Exit
npm test; Check-Exit
npx playwright install chromium; Check-Exit
npm run test:e2e; Check-Exit
npm run bundle:android; Check-Exit
Pop-Location
Push-Location android-app/UnoOneAgent
.\gradlew.bat --no-daemon --no-parallel --max-workers=2 clean testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
Check-Exit
Pop-Location
Get-FileHash android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
```

### Git Bash — from repository root

```bash
set -e
python scripts/ci/check_repo_invariants.py
(cd web-runtime/page-agent-unoone && npm ci && npm run typecheck && npm test && npx playwright install chromium && npm run test:e2e && npm run bundle:android)
(cd android-app/UnoOneAgent && bash ./gradlew --no-daemon --no-parallel --max-workers=2 clean testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest)
sha256sum android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk
```

Stop on any error. A test APK being assembled does not mean instrumentation was run. First native build can take a long time; record actual output rather than copying historical pass counts. Open `android-app/UnoOneAgent` in Android Studio if using the IDE, but still create the browser bundle before its Gradle build.

## 5. Install without deliberately removing user data

Enable developer options and USB debugging on your phone, connect by USB, unlock it, and manually accept the laptop's RSA authorization prompt. On HyperOS, additional Install via USB approval may be required. Do not bypass device policy or grant permissions through automation.

```powershell
# Works in PowerShell or Git Bash from repository root:
adb devices -l
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.fingerprint
adb install -r android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk
```

Select a device explicitly with `adb -s SERIAL ...` if more than one is connected. **`-r` preserves app data only when Android accepts a compatible, same-certificate update.** A debug APK built on another laptop may use a different debug keystore. If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, do not uninstall, clear data, or force a downgrade. Stop and obtain an authorized build signed with the original certificate (or plan a separately reviewed backup/migration). Keep signing keys private and outside Git. Compare signing certificates with SDK `apksigner verify --print-certs` when needed. An older repair4 APK/hash is not this build.

Do not follow historical 0.5 installation instructions as a downgrade from installed versionCode 6. Do not add `-d`, uninstall, or clear data to bypass version/signature checks. Preserve existing app data, verified E2B/E4B/Qwen files and model selection; a compatible update is intended to retain them, but verify on the device rather than promising migration success. Record the actual newly built APK hash and signer; historical checksums identify only their historical artifacts.

Open UnoOne manually. Recheck Accessibility after app updates; some OEMs disable it. Do not infer model availability merely from a successful install.

### Task Board after a matching 0.6 install

Open **Agent → Tasks**. Start with a harmless draft; optionally enter exact required phrases, one per line (<=6 unique, <=80 characters each / <=256 combined). Inspect admission, selected-task output and outcome; RESPONDED is not fact verification. At most one repair is allowed, then failed checks/model errors require NEEDS_USER. Try local notes search + draft as independent child outputs, not grounded research or automatic sending. Browser/Skills remain independent lanes, not live Task Board workers; no persistent bot accounts are provisioned.

Use the [10-case safe multitasking protocol](UNOONE_MULTITASKING.md) and retain failed attempts. Global Stop/Camera ACK source and host gates do not certify phone native teardown. Do not interpret an assembled APK as executed instrumentation or device success.

## 6. Manually grant only the permissions you need

- **Accessibility:** use Android's Accessibility settings to enable UnoOne after reading the disclosure; needed for bounded native screen actions.
- **Display over other apps / overlay:** manually allow only if using the overlay controls.
- **MediaProjection:** accept Android's screen-capture consent dialog when you deliberately request a capture. This is not a permanent automatic grant or background capture entitlement.
- **Microphone:** approve when testing voice; local speech packs must be installed separately.
- **Camera:** only for intentional camera/Blind Aid tests. Model downloads do not imply permission to capture.

These commands do **not** use `pm grant`, secure-settings injection or Accessibility autogrant. Screen/page content remains untrusted input, not instructions granting authority. Use a harmless demo screen without personal information for initial tests.

## 7. Explicitly select, install, verify, load and test Qwen

1. Open **Settings → Model Status & Install**.
2. Under **Planning profile**, choose **Qwen 3.5 2B** and read the experimental warning; tap **Opt in and select** only if you intend to switch. E2B stays default otherwise; E4B is retained. Missing/invalid selected files do not trigger automatic fallback.
3. Use the model installation control on the Qwen row, preferably on Wi-Fi. Approve metered data only deliberately. Download the complete **nine-file** pinned set, not just `llm.mnn`.
4. Tap **Verify complete SHA-256** and wait for the actual integrity result. Filenames, exact sizes and hashes must match the manifest; presence alone is not integrity.
5. Tap **Load Brain**. Record the real result and any native/backend error. Then tap **Run Self-Test** and record that result independently. A successful hash is not a successful load; successful load is not task accuracy.
6. Record CPU **2 threads**, context **2,048**, max output **256** from runtime evidence where exposed; distinguish configured policy from confirmed native values. Do not claim NPU/GPU execution.
7. Test a harmless text request, Stop/cancellation and browser lease/return with the same chosen profile. Record failure or pending honestly; no device inference pass is supplied by this guide.
8. To return, explicitly select a verified E2B or E4B profile in the same screen. Keep the other profiles installed; do not delete them to force selection.

The install set totals **1,386,691,327 bytes**:

```text
config.json
export_args.json
llm.mnn
llm.mnn.json
llm.mnn.weight
llm_config.json
tokenizer.txt
visual.mnn
visual.mnn.weight
```

Use the [exact artifact table](UNOONE_V3_QWEN35_MNN.md), [pinned Qwen export](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/tree/35781816d7b6a9dcb273a6765ac9563401951c3c) and [base model/card/license](https://huggingface.co/Qwen/Qwen3.5-2B). The export card declares Apache-2.0. MNN's [pinned license](https://github.com/alibaba/MNN/blob/024a946b0b8fcf87c8a418229fadd4cd7858ffba/LICENSE) and [packaged third-party notices](../android-app/UnoOneAgent/localbrain/src/main/cpp/licenses/) are separate obligations. Model binaries live outside Git, in app-private model storage after installation; no all-files permission is required.

## 8. Reviewed image advice and native workflows

**Image:** Settings → Developer (opt-in) → **Open runtime and screen diagnostics**. Deliberately request a capture and accept the OS dialog if prompted. Inspect the preview, enter a harmless question, check **I reviewed this capture and approve local processing of this image only**, then tap **Analyze captured screen locally**. This uses the selected model; capability/load/admission failures remain errors, not success. Approval is per capture; analysis admission expires after 30 seconds. Known secret regions are masked or capture/admission denied; unknown sensitive content may remain and carries a warning. Never test on banking, passwords, OTPs or private messages. The result is **advice only**—it cannot authorize arbitrary visual clicks. Re-capture if expired; closing discards pixels.

**Skills:** Skills → **Reviewed native workflows (Skills V2)** exposes import, exact-definition review, export and guarded run. Start only with trusted, harmless fixture definitions and inspect permissions, targets, steps, app versions and digest. Candidate updates still need trusted fixtures; do not promise autonomous self-updates. Manual learn recording/capture is unavailable. Stop all must remain usable; do not turn a fixture pass into “all apps work perfectly.”

**Native phone actions:** current-app ClickSearch / ReadScreen / Scroll / Back and bounded name-find are source-supported. Draft intents need review and do not prove a message was sent. Unknown or ambiguous targets require handoff. Browser fixture passes do not certify arbitrary websites. GUI-Owl/AppFunctions remain experiment/unavailable; partner bridge deployment remains incomplete.

## 9. Collect actual results, not invented metrics

From the repository root, a few read-only diagnostics (save output outside Git and review/redact before sharing):

```powershell
adb shell dumpsys meminfo com.unoone.agent
adb shell dumpsys battery
adb shell dumpsys thermalservice
adb shell getprop ro.build.fingerprint
```

`meminfo` can be unavailable if the process is not running. Record its actual TOTAL PSS with units and test phase; leave PSS pending if you did not measure it. Do not treat laptop RSS as phone PSS. After downloads, test offline behavior deliberately (airplane mode; verify Wi-Fi/mobile data state); no cloud fallback is expected. Record speech, audible playback, Stop latency and thermal/battery behavior only when actually measured. Avoid sharing raw screen content or sensitive logs.

Copy this template for each run:

```text
Status: PENDING / PASS / FAIL / BLOCKED (choose based on observed evidence)
UTC time:
Source: git rev-parse HEAD output:
Dirty worktree / local changes:
Build command + exit code + log location:
APK path / byte size / SHA-256 / signer certificate SHA-256:
Phone model / Android / OEM version / build fingerprint / ABI:
Install result (same-certificate -r update?):
Manual permissions actually granted:
Selected model + exact revision:
All 9 artifacts verified? Actual verification receipt:
Backend / threads / context / output cap (configured vs observed):
Load Brain result:
Run Self-Test result:
Task / input / expected native postcondition:
Actual output / fresh observation / result:
Image review/admission outcome (no sensitive pixels in report):
Stop/cancel/unload/browser restore result:
Offline network state + actual result:
Latency (method, start/end, units): PENDING unless measured
Phone TOTAL PSS (phase, units, raw receipt): PENDING unless measured
Thermal/battery (duration, start/end, charging state): PENDING unless measured
Failures / blocked steps / unexecuted tasks:
Evidence files (redacted) and remaining qualification gates:
```

No result in this template is pre-filled as a pass. Keep historical repair4 receipts intact and bind new evidence to the actual final source. Final integrated-gate status must come from the main report, not this setup document.

## 10. Optional physical Qwen JNI qualification — PENDING

```bash
# Git Bash, repository root. No device run has been performed here: status PENDING.
# Normal CI: compile/assemble instrumentation only; do NOT run connectedAndroidTest.
(cd android-app/UnoOneAgent && bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
# Before device execution: manually enable UnoOne and explicitly opt in to experimental Qwen.
# Install ALL NINE pinned qwen3.5-2b-mnn files using section 7. Missing/invalid model FAILS;
# there is no assumption-skip, automatic download, alternate model, or cloud fallback.
# Close exclusive modes and leave the app idle. Suite forces full SHA-256 verification.
# Replace SERIAL with the authorized physical arm64 phone serial from adb devices -l.
# Preserve existing app data: same-certificate update only. Never uninstall or pm clear.
adb -s SERIAL install -r android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r android-app/UnoOneAgent/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class com.unoone.agent.localbrain.QwenMnnPhysicalQualificationTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
# Inspect full instrumentation output: failures/crashes/timeouts are NOT PASS.
# Save actual output and source/APK/device identities using section 9, outside Git.
# One load: actual text, strict JSON sum=4, solid-red PNG through native image processor,
# native CPU/2-thread/non-thinking/output-cap receipt, stale permit rejection, close ACK.
# Not a GUI accuracy benchmark, active-generation cancellation proof, or thermal/PSS result.
# The app-owned engine is unloaded through Orchestrator with ACK before a global native claim.
# Previous resident profile is restored only after test-runtime close ACK; files/selection persist.
# If cleanup is uncertain, the suite retains/quarantines ownership. Do not reset the gate;
# stop testing and restart the app process before investigating. Record FAIL/BLOCKED honestly.
```

