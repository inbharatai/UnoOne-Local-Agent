# Foreground Service Justification (Google Play Policy)

Google Play requires every foreground service to have an appropriate type, the matching
foreground-service permission, and a user-perceptible reason (the user initiated the task or there
is a clear notification). UnoOne declares two foreground services.

## 1. `FloatingAgentService` — `foregroundServiceType="specialUse"`

**Permission:** `FOREGROUND_SERVICE_SPECIAL_USE` + `SYSTEM_ALERT_WINDOW`.

**Why a foreground service:** the floating agent bubble (overlay) must stay alive across other apps
so the user can invoke voice/text commands from anywhere. Without a foreground service, Android
kills the overlay process under memory pressure, breaking the core use case.

**Why `specialUse`:** the bubble is a persistent, user-toggled on-screen agent interface — it does
not fit the camera/health/location/media/microphone/phone-call/connected-device/data-sync
categories. It is a special-use persistent UI surface.

**User-perceptibility:** the user explicitly toggles the bubble on. A persistent notification is
shown: **"UnoOne active — tap to pause / stop."** The service stops when the user dismisses the
bubble or taps stop.

**Play `specialUse` declaration:** in the Play Console FGS type form, select "Special Use" and
provide this justification and a demo video (see `demo-video-script.md`) showing the user toggling
the bubble and the persistent notification.

## 2. `VoiceService` — `foregroundServiceType="microphone"`

**Permission:** `FOREGROUND_SERVICE_MICROPHONE` + `RECORD_AUDIO` (runtime).

**Why a foreground service:** background wake-word detection + offline STT transcription require a
live microphone session that must survive screen-off and app backgrounding so the user can say
"UnoOne …" and have the device respond.

**Why `microphone`:** the service captures audio — the microphone FGS type is the exact match.

**User-perceptibility:** the user explicitly enables background voice. A persistent notification is
shown: **"UnoOne listening locally — Mic active: yes / Offline mode: Sherpa | fallback | no model —
tap to pause / stop."** No silent background microphone use occurs; if the user stops the service,
mic capture stops.

## Notifications

Both services post a non-dismissible-pending persistent notification with the task state and
pause/stop actions, per Google's "users should be aware of the ongoing task" requirement.