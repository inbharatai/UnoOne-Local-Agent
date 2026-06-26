# Play Review Demo Video Script

A ~2-minute screen recording showing UnoOne's sensitive surfaces are user-initiated and
transparent. Required for `AccessibilityService` and `specialUse` foreground-service review.

## Setup to show on camera

1. Device: one of the verified set (Xiaomi 14 / Samsung S24 / Pixel 8) with models installed.
2. Show the Model Status screen: English STT/TTS present and **Verified** (green); Gemma present
   but labeled **"Present — not hash-verified (manual import)"** (honest state).

## Scene 1 — Onboarding & prominent disclosure (0:00–0:20)

- Launch UnoOne. Show the Accessibility prominent disclosure dialog ("…never reads your screen in
  the background…").
- Tap enable → land in Settings → Accessibility → enable UnoOne → return.

## Scene 2 — User-initiated screen reading + control (0:20–0:50)

- Open a browser. Say/type "read screen" → **CONFIRM** dialog appears → approve → app speaks the
  visible text via offline TTS.
- Type "find and click Login" → **STRONG_CONFIRM** dialog ("type 'confirm' to proceed") → approve
  → app scrolls/taps. Narrate: "every UI action asks first."

## Scene 3 — Foreground-service notifications (0:50–1:15)

- Toggle the floating bubble on → persistent notification appears: **"UnoOne active — tap to
  pause/stop."**
- Enable background voice → notification: **"UnoOne listening locally — Mic active: yes / Offline
  mode: Sherpa — tap to pause/stop."** Narrate: "no silent background mic."

## Scene 4 — Offline voice + Blind Aid (1:15–1:40)

- Say "UnoOne, create a note: buy milk" → offline STT transcribes → note saved → spoken
  confirmation. Narrate: "fully on-device."
- Activate Blind Aid → camera preview + bounding-box overlay + haptic/tone feedback + spoken
  guidance. Narrate the in-app disclaimer: "assistive only, not a certified navigation device."

## Scene 5 — Privacy controls (1:40–2:00)

- Open Logs → show the audit trail (inputs hashed). Tap Export → JSON file generated.
- Show Settings: **Online tools: OFF**; **Allow Android system TTS fallback: OFF**.
- "Delete all notes" → STRONG_CONFIRM → confirm → notes gone. Narrate: "your data is local and
  deletable."

## What the reviewer should conclude

- Accessibility is user-initiated, never background monitoring.
- Foreground services are user-toggled with clear notifications.
- Sensitive actions require confirmation; data is local, hashed, exportable, deletable.
- The only network path (web search) is opt-in and off by default.