# UnoOne — Permissions Matrix (Google Play Review)

Every permission and foreground service UnoOne (`com.unoone.agent`) declares, why it is needed,
its protection level, and the Play-review risk it carries. Source of truth:
`android-app/UnoOneAgent/app/src/main/AndroidManifest.xml`.

## Permissions

| Permission | Protection level | Used by | Why | Play risk |
|---|---|---|---|---|
| `INTERNET` | normal | `web_search` tool (RAGManager) | Optional, opt-in online DuckDuckGo lookup only. Off by default; offline-first guard returns an explicit offline message when no network. | Low — normal permission, not runtime. |
| `ACCESS_NETWORK_STATE` | normal | `ActionExecutor.isOnline()` | Detect connectivity so `web_search` fails fast offline instead of waiting on a 5s socket timeout. | Low. |
| `RECORD_AUDIO` | dangerous (runtime) | `voice_recording`, STT, wake word | Capture speech for offline transcription and voice memos. Requested at runtime; denied → graceful `Result.Error`. | Medium — microphone; justify in Data Safety. |
| `MODIFY_AUDIO_SETTINGS` | normal | Voice pipeline | Audio routing for STT/TTS. | Low. |
| `CAMERA` | dangerous (runtime) | `open_camera`, `detect_objects` (Blind Aid) | Launch camera app; Blind Aid real-time object detection via CameraX. | Medium — camera; justify in Data Safety. |
| `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | dangerous ≤ SDK 28 | legacy only | App-private storage via `getExternalFilesDir()` on API 29+; these are legacy stubs for API 28. | Low. |
| `VIBRATE` | normal | Blind Aid haptics | Tactile feedback for obstacle alerts. | Low. |
| `FOREGROUND_SERVICE` | normal | All FGS | Required to start any foreground service. | Low. |
| `FOREGROUND_SERVICE_MICROPHONE` | normal | `VoiceService` | Background wake-word + STT capture. | Medium — mic in background; user-perceptible notification required. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal | `FloatingAgentService` | Persistent floating bubble overlay agent UI. | **High** — Play reviews all `TYPE_SPECIAL_USE`; justification required (see `foreground-service-justification.md`). |
| `WAKE_LOCK` | normal | Voice pipeline | Keep CPU awake during a voice capture/inference sequence. | Low. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | normal | Permission robustness | Request battery-optimization exemption so background voice isn't killed. | Medium — justify; do not auto-request without user action. |
| `POST_NOTIFICATIONS` | dangerous (runtime, API 33+) | FGS notifications | Post the persistent foreground-service notification. | Low. |
| `READ_CALENDAR` / `WRITE_CALENDAR` | dangerous (runtime) | `check_calendar`, `open_calendar_insert` | Read today's events / open event creator. | Medium — calendar data; first read is user-initiated. |
| `SYSTEM_ALERT_WINDOW` | special (Settings) | `FloatingAgentService` | Draw the floating bubble over other apps. | **High** — overlay; justify; user must grant via Settings. |

### Deliberately NOT requested (Play-safe)

- `MANAGE_EXTERNAL_STORAGE` — removed; app uses app-private `getExternalFilesDir()`.
- `READ_CONTACTS` — declared-but-unused; removed.
- `QUERY_ALL_PACKAGES` — removed; `PackageResolver` uses a curated app map. (Planned: `queryIntentActivities()` for launchable apps + user-saved shortcuts — see `docs/SAFETY.md`.)

## Foreground services

| Service | FGS type | Permission | Why user-perceptible |
|---|---|---|---|
| `FloatingAgentService` | `specialUse` | `FOREGROUND_SERVICE_SPECIAL_USE` + `SYSTEM_ALERT_WINDOW` | The floating agent bubble the user explicitly toggles; persistent notification "UnoOne active". |
| `VoiceService` | `microphone` | `FOREGROUND_SERVICE_MICROPHONE` | Background wake-word listening + STT the user enables; persistent notification "UnoOne listening locally — Mic active". |

## Hardware features

- `android.hardware.microphone` (required) — core voice input.
- `android.hardware.camera` (not required) — Blind Aid + camera tool are optional.
- `android.hardware.bluetooth` (not required).

## Targeting

- `compileSdk`/`targetSdk` = 35 (Android 15), `minSdk` = 28 (Android 9).
- Android 14+ requires declared FGS types + matching FGS permissions — both present.