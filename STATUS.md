# UnoOne V2 Status

**Updated:** 2026-07-13  
**Branch:** `main`  
**Pull request:** PR #1 merged  
**Release state:** **Alpha / not production-ready**

The full mandatory completion plan is maintained in [README.md](README.md). This file records the current evidence state and must not contain unverified claims.

## Status legend

- **Implemented** — code exists on `main`.
- **Automated gate** — CI/test exists; only the latest green head counts.
- **Device pending** — requires physical Android evidence.
- **Blocked** — release must not proceed.

## Current architecture

| Area | Current state | Evidence still required |
|---|---|---|
| Android project | Implemented, 15 Gradle modules | automated lint, JVM tests and debug APK build are green; physical-device matrix pending |
| Planning brain | Gemma 4 E2B only | Xiaomi 14 + secondary-device load/performance tests |
| Model artifact | exact filename, size and SHA-256 recorded | self-hosted production mirror and device qualification |
| Phone tools | preserved in V2 | regression matrix on devices |
| Offline speech | baseline English + six Indic packs | per-language accuracy, latency and thermal evidence |
| Assamese | planned, non-downloadable | exact STT/TTS artifacts, licence and benchmarks |
| Blind Aid | preserved independently of Gemma | camera/object/haptic/spoken device tests |
| Language packs | dependency-aware manager and UI implemented | clean-device install/repair/uninstall tests |
| Secure Browser | Alibaba PageAgent + local Gemma bridge implemented | controlled workflows + approved-domain device tests |
| Browser safety | native authorization implemented | bypass/prompt-injection/device testing |
| Installer PWA | signed-catalogue flow implemented | automated Distribution CI is green; production deployment pending |
| Distribution API | read-only Worker/R2 design implemented | real buckets, bindings, signed catalogues and download tests |
| Production signing | not configured | protected APK key + Ed25519 catalogue release key |

## Gemma 4 E2B

| Field | Value |
|---|---|
| Manifest id | `gemma-4-e2b` |
| File | `gemma-4-E2B-it.litertlm` |
| Size | `2,588,147,712` bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Context limit | 32,768 tokens |
| Minimum product RAM gate | 6 GB |
| Recommended product RAM gate | 8 GB |
| Device-qualified | **No** |
| Production-approved | **No** |

The phone agent and Secure Browser use an exclusive model lease. They must never hold separate Gemma engines simultaneously.

## Secure Browser policy

| Operation | Policy |
|---|---|
| Read/wait/scroll | allow |
| Ordinary input | allow after native classification |
| Sensitive/unknown action | confirm |
| File transfer | confirm + Android takeover |
| Final submission | confirm |
| Password/OTP/CAPTCHA/legal acceptance | manual takeover |
| Payment/banking/card/UPI PIN | block |
| Arbitrary JavaScript execution | unavailable |

The bridge is exposed only to approved exact HTTPS origins. Browser audits store bounded action summaries and decisions, not typed values.

## Language packs

| Language | State |
|---|---|
| English | required baseline |
| Hindi | baseline |
| Bengali | baseline |
| Tamil | baseline |
| Telugu | baseline |
| Kannada | baseline |
| Malayalam | baseline |
| Assamese | planned, priority, disabled |
| Marathi | planned, disabled |
| Gujarati | planned, disabled |
| Punjabi | planned, disabled |
| Odia | planned, disabled |
| Urdu | planned, disabled |

A language is not promoted from `planned` or `baseline` based only on model presence. Exact integrity, licence, Android load and language benchmark evidence are required.

## Automated gates

### Android CI

- PageAgent dependency installation
- PageAgent TypeScript typecheck
- PageAgent unit tests
- PageAgent Android bundle generation
- Playwright form-fill and payment-block tests
- Android lint
- Android JVM unit tests
- Debug APK assembly
- diagnostic artifact upload on failure

### Distribution CI

- distribution API typecheck and tests
- Cloudflare Worker dry-run bundle
- installer PWA typecheck and tests
- installer PWA build
- catalogue signing round-trip
- tampered-catalogue rejection

Only the latest commit status is authoritative. An older green run does not make a newer head green.

## Release blockers

- [x] Latest Android CI is green.
- [x] Latest Distribution CI is green.
- [ ] Gemma loads and plans correctly on Xiaomi 14.
- [ ] Secondary Android device passes the same Gemma and browser gates.
- [ ] Offline speech baseline matrix is recorded.
- [ ] Blind Aid regression matrix is recorded.
- [ ] Controlled PageAgent workflows and takeover paths pass on device.
- [ ] Production model mirror is available from UnoOne-controlled storage.
- [ ] Real production catalogue public key is embedded in the installer build.
- [ ] Stable and beta catalogues are signed with the protected release key.
- [ ] Release APK is signed and locally checksum-verified.
- [ ] Security, privacy, dependency, licence and SBOM reviews are complete.
- [ ] Rollback instructions and archived pre-V2 branch are confirmed.

## Do not claim yet

Until the blockers above are completed, do not claim that UnoOne V2 is:

- production-ready;
- fully device-verified;
- accurate in Assamese or any other unbenchmarked language;
- thermally stable under sustained Gemma/PageAgent use;
- safe for autonomous payments, credentials, OTP, CAPTCHA or legal acceptance;
- independent of third-party model hosting in production;
- available as a final public installer.
