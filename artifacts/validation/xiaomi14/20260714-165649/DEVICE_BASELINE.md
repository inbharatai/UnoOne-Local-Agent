# Device Baseline — Xiaomi 14 (PRIMARY) — 2026-07-14 16:56:49

## Verified as the intended primary device

Confirmed against the task's primary spec. This is the real Xiaomi 14.

| Property | Value |
|---|---|
| ro.product.manufacturer | Xiaomi |
| ro.product.marketname | **Xiaomi 14** |
| ro.product.odm.marketname | Xiaomi 14 |
| ro.product.model | 23127PN0CG |
| ro.product.device | houji |
| ro.soc.manufacturer | QTI (Qualcomm) |
| ro.soc.model | **SM8650** (Snapdragon 8 Gen 3) |
| ro.hardware | qcom |
| ro.build.version.release | **15** |
| ro.build.version.sdk | **35** |
| ro.build.id | AQ3A.240627.003 |
| ro.product.cpu.abilist | arm64-v8a |
| adb serial | 7f8cafef |
| adb state | device (authorized) |

## Memory
- `/proc/meminfo` MemTotal: 11,436,548 kB (~10.9 GB visible ≈ 12 GB physical). Above Gemma 6 GB min gate and 8 GB recommended.

## Storage
- `/data`: 482 GB size, 94 GB used, **388 GB free** (20%). Sufficient for APK + 2.59 GB model + language packs.

## Battery / thermal at start
- Battery level: **29%** — BELOW the 60% gate. AC powered: true (charging).
- Battery temperature: 36.9 °C. Thermal Status: 0. Skin 41.5 °C.
- **Heavy tests (Gemma download/load, 30-min thermal run) held until battery > 60%.**

## App under test
- Package: `com.unoone.agent` (applicationId; namespace `com.unoone.agent`)
- versionName: `0.4.0-alpha-v2`, versionCode: 4
- minSdk 28, targetSdk 35 (matches device API 35)
- Launcher activity: `com.unoone.agent.MainActivity` (MAIN/LAUNCHER)
- Declared services: `FloatingAgentService`, `UnoOneAccessibilityService`, `VoiceService`
- APK SHA-256 (source): `4e72d981747d95dacde4fac019229fd0c3e845c6fb8a9efb267575598c70e174`
- APK path: `android-app/UnoOneAgent/app/build/outputs/apk/debug/app-debug.apk` (372,355,017 B)

Evidence files: `adb-devices.txt`, `device-verify.txt`, `df.txt`, `battery.txt`, `thermal.txt`.