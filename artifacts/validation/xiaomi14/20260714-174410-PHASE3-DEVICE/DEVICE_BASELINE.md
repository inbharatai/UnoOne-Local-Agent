# Phase 3 — Device Verification Evidence (Xiaomi 14)

Collected: 2026-07-14 (session resumed; device connected via ADB after prior "no device" block).

## Device identity
- Serial: 7f8cafef
- Market name: Xiaomi 14
- Model: 23127PN0CG
- Device codename: houji
- Manufacturer / Brand: Xiaomi
- Android release: 15 (SDK 35)
- Build ID: AQ3A.240627.003
- Incremental: OS2.0.207.0.VNCINXM (HyperOS 2.0)
- Security patch: 2025-11-01
- ABI: arm64-v8a (64-bit only)

## Resources
- RAM: 11,436,548 kB total (~12 GB)
- Storage /data: 482 GB total, 95 GB used, 387 GB free (plenty for Gemma 4 E2B ~2.59 GB + language packs)
- USB debugging: authorized (device visible as `device`, shell commands accepted)

## App: com.unoone.agent
- versionName: 0.4.0-alpha-v2
- versionCode: 4
- minSdk 28 / targetSdk 35
- primaryCpuAbi: arm64-v8a
- firstInstallTime / lastUpdateTime: 2026-07-14 17:17:05
- Process running at evidence time: pid 30757

## Install integrity (byte-for-byte)
- Installed base.apk path: /data/app/~~HQ900dPAjn4Agc2xdNBb-g==/com.unoone.agent-7K2xcIf4gMd0Jt2bYsDseA==/base.apk
- Installed base.apk sha256: 4e72d981747d95dacde4fac019229fd0c3e845c6fb8a9efb267575598c70e174
- Built app-debug.apk sha256: 4e72d981747d95dacde4fac019229fd0c3e845c6fb8a9efb267575598c70e174
- Both 372,355,017 bytes — IDENTICAL. Installed app == repo build artifact.

## Prior-session install history (context)
- Earlier ADB `install` failed with INSTALL_FAILED_USER_RESTRICTED (HyperOS blocks ADB install until
  "Install via USB" + "USB debugging (Security settings)" are enabled in Developer Options).
- /sdcard/Download sideload blocked by SELinux (fuse context).
- Install ultimately succeeded at 17:17:05 (on-device approval). Verified byte-for-byte above.

## Method note
- Evidence collected via ADB getprop / dumpsys / df / pidof + `adb exec-out cat` to pull base.apk for hashing.
- NO screenshots taken. (This model has no image input; visual verification is out of scope by user mandate.)

## Files in this folder
- adb-devices.txt, device-props.txt, installed-version.txt, installed-base.apk.sha256, DEVICE_BASELINE.md
