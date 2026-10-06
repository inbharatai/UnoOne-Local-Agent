# Owl physical-device qualification — all physical results PENDING

No physical device execution, model load or model download was performed while authoring these tests. Instrumentation APK assembly is a parent/build-owner task; assembly is not device qualification. Neither a synthetic PNG success nor a practice-screen success proves general task accuracy.

## Prerequisites and explicit consent

Use only your own Android device and your own Windows computer, with USB debugging authorized on-device. Close unrelated tasks/modes. Select **GUI-Owl-1.5-4B-Instruct**, accept its experimental consent, and deliberately install and load the verified model pair through the application. Tests never change selection, grant consent, download weights, create a second runtime, or reset quarantine. Missing models/consent/wrong resident profile fail rather than silently pass or skip.

Pinned pair: `GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf` (2,497,282,208 bytes) and `GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf` (453,974,336 bytes), from revision `9a79d301329062eb02e46f7ccad82c99075bfaaa`. Approximately **2.95 GB download** combined. Full SHA-256 validation uses the existing model-health gate. The **8 GB device RAM policy** and approximately **4.75 GiB available-memory admission policy** are estimates/policies, **not measured peak memory** and not a promise of successful execution.

Required resident policy: CPU 2 threads, context 2048, output 256, no GPU and no reduced host configuration. The test reads the successful native receipt from the application's existing LocalBrain (read-only instrumentation reflection, no production API change).

## Windows PowerShell commands (user-owned endpoints only)

Run from `android-app\UnoOneAgent` after the build owner supplies same-signed debug app and instrumentation APKs. These are routine device-install/test commands; never use them against someone else's computer or device.

```powershell
adb devices -l
$serial = "YOUR_AUTHORIZED_USB_SERIAL"
adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s $serial install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or signature mismatch occurs, **stop** and obtain APKs signed with the existing signing key. Do not uninstall or clear app data: that can destroy consent, installed models and reports. Do not automatically download the pair. Reopen the app and explicitly select/consent/load Owl after installation and before testing; instrumentation may restart the process, so inability to establish resident Owl is a failed prerequisite, not a skip.

### Exactly two actual runtime probes, no UI dispatch

```powershell
adb -s $serial shell am instrument -w -r -e owlPhysicalConsent true -e class com.unoone.agent.owl.OwlPhysicalQualificationTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath owl-physical-instrumentation.txt
adb -s $serial logcat -d -s OwlQualification:I '*:S' | Out-File -Encoding utf8 owl-physical-logcat.txt
```

Expected success: `OK (1 test)` plus receipt and exactly `text-2-plus-2` and `synthetic-search-png` passed in the log. Any failure, missing output, process death, ANR or timeout is **not** qualification. Text requires real native output `4`; synthetic GUI requires an accepted Search coordinate from actual inference. Two probes have a 210-second independent native-cancel watchdog and 220-second coroutine budget, below five minutes for the probes (full pair hashing/preparation is separate). Blocking native code still requires cooperative cancellation; a hung device is a failure, never forcibly free its resources.

Idle cancellation checks residency/receipt retention only. It does **not** prove active-decode stop latency, native destruction ACK, or quarantine recovery. No lease owner is released and no runtime is unloaded by the test.

### One user-approved native practice operation

Manually grant app accessibility and screen-capture permission in Android, review the non-sensitive practice fixture, and explicitly approve ONE Search click. No real bank, password, OTP, payment or sensitive application is used.

```powershell
adb -s $serial shell am instrument -w -r -e owlPracticeConsent true -e class com.unoone.agent.owl.OwlApprovedPracticeSessionTest com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner | Tee-Object -FilePath owl-practice-instrumentation.txt
```

Expected success: `OK (1 test)`, the real Owl session reports `VERIFIED`, Search disappears, and the native framework TextView reads `Search results`. Missing permission/residency, privacy rejection or `NEEDS_USER` is a failure to qualify, not fake success. Session is limited to one step/90 seconds, with independent cancellation and a 100-second coroutine budget. UI verification is native, never inferred solely from a model `done` response. Other allowed operations remain pending.

Reports are the local `owl-*-instrumentation.txt` and `owl-physical-logcat.txt` files captured by PowerShell; archive them with device model, Android build, APK hashes, model hashes and timestamps. There is no invented device report path and no claim that instrumentation produces host Gradle HTML reports. Review logs before sharing; do not capture unrelated screen contents.

## Physical matrix

| Check | Required evidence | Status |
|---|---|---|
| Exact selection, Owl consent, full pinned pair health | Failing prerequisite assertions/full hash gate | PENDING |
| Resident CPU2/context2048/output256 receipt | OwlQualification log | PENDING |
| Actual native text arithmetic | text-2-plus-2 passed | PENDING |
| Actual synthetic GUI PNG inference | synthetic-search-png passed | PENDING |
| Idle native cancellation retains resources | Resident path/receipt assertions | PENDING |
| User-approved one-click practice session | VERIFIED + native result widget | PENDING |
| Focus/type/read/scroll/back other allowed operations | Separate bounded approved native fixtures | PENDING — not covered by this class |
| Active native stop/drain and destruction ACK | Device instrumentation tracing, retained ownership on failure | PENDING — idle check is insufficient |
| Sensitive negative cases | Explicit fake sensitive fixtures only; never real banking/authentication | PENDING — not covered here |
| Peak RSS, thermal behavior, latency | Actual physical measurement | PENDING — RAM policy is not measurement |
| Camera/rotation/overlay/user takeover | Reviewed dedicated bounded fixtures | PENDING |

Do not relabel any row PASS based on host tests, APK assembly or the presence of these source files.
