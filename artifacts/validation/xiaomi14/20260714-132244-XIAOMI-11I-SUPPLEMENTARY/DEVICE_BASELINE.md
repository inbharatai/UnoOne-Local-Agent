# Device Baseline — 2026-07-14 13:22:44

## CRITICAL: this is NOT the specified primary device

The task's primary device spec is **Xiaomi 14 / Android 15 / API 35 / Snapdragon 8 Gen 3 / 12 GB RAM**.
The connected and authorized device is a **Xiaomi 11i**, which differs on every primary-spec axis.
**User decision (2026-07-14): proceed with the full matrix on this Xiaomi 11i, including the Gemma 4 E2B
load attempt.** All evidence in this run is recorded as Xiaomi 11i. The real Xiaomi 14 primary matrix
remains a documented release blocker (see RELEASE_BLOCKERS.md, to be created).

## Device (as reported by getprop)

| Property | Value |
|---|---|
| ro.product.manufacturer | Xiaomi |
| ro.product.brand | Xiaomi |
| ro.product.model | 21091116I |
| ro.product.device | pissarroin |
| ro.product.name | pissarroin |
| ro.product.marketname | **Xiaomi 11i** (product) |
| ro.product.vendor.marketname | Xiaomi 11i |
| ro.product.odm.marketname | Xiaomi 11i |
| ro.build.version.release | 13 |
| ro.build.version.sdk | 33 |
| ro.build.id | TP1A.220624.014 |
| ro.hardware | mt6877 |
| ro.product.board | pissarroin |
| ro.soc.manufacturer | Mediatek |
| ro.soc.model | MT6877V/TZA |
| ro.product.cpu.abilist | arm64-v8a,armeabi-v7a,armeabi |
| adb serial | 8dtsonmrdqnvin65 |
| adb state | device (authorized) |

## Memory

- `/proc/meminfo` MemTotal: **5,604,372 kB (~5.6 GB)** — BELOW Gemma 4 E2B 6 GB minimum RAM gate
  (STATUS.md: min 6 GB, recommended 8 GB). Gemma load behavior on this device is itself the test.

## Storage

- `/data` (user partition): 107 GB size, 20 GB used, **87 GB free** (19%) — sufficient for APK + 2.59 GB model.

## Battery / thermal (start of run)

- Battery level: **74%** (above 60% gate)
- Battery temperature: 31.7 °C (dumpsys battery `temperature: 317` = 31.7 °C)
- status: 2 (charging), AC powered: true
- Thermal service: Thermal Status 0 (none). CPU/GPU/NPU ~54.5 °C, SKIN 39.1 °C, BATTERY 32.0 °C.

## Implications for the matrix

- Gemma 4 E2B: app RAM gate (≥6 GB) is expected to refuse load, OR load may OOM on 5.6 GB. The
  honest result — whatever the app does — is recorded. Any Gemma latency/throughput numbers from this
  device do NOT represent the Xiaomi 14 and must not be used for qualification.
- API 33 vs target 35: app minSdk is 28, so install should succeed; behavior differences (e.g. API-35
  notification/permission surfaces) are noted where relevant.
- Non-Gemma capabilities (install, UI, Accessibility, notes/skills/memory, speech, Blind Aid, OCR,
  PageAgent safety, lifecycle) are valid to test on this device; results still labeled Xiaomi 11i.

Evidence files in this folder: `adb-devices.txt`, `device-props.txt`, `meminfo.txt`, `df.txt`, `battery.txt`, `thermal.txt`.