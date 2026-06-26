# AccessibilityService Justification (Google Play Policy)

UnoOne declares an AccessibilityService (`com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService`).
Google Play permits AccessibilityService use only for apps whose core purpose is to help users
with disabilities, or where the user explicitly enables the service for a clearly disclosed,
user-initiated purpose. This document states that purpose.

## What the service does

UnoOne is an **offline-first, voice-controllable accessibility agent**. The AccessibilityService
is the "hands" of the agent and is used for exactly two user-initiated purposes:

1. **Screen reading** (`read_screen`, `ocr_screen` fallback): read visible text from the
   accessibility tree so a blind/low-vision or voice-only user can ask "what's on my screen?" and
   hear the answer spoken via offline TTS.
2. **User-initiated UI control** (`system_control` with actions `click`, `type`, `fill`,
   `scroll_up/down`, `swipe`, `go_back`, `go_home`, `open_notifications`, `open_recents`,
   `find_and_click`, `long_press`): perform a UI action the user explicitly asked for by voice or
   text, e.g. "find and click Login", "fill username with …", "scroll down".

## What it does NOT do

- It does **not** monitor screen content in the background.
- It does **not** read notifications, credentials, payment fields, or messages except when the
  user explicitly issues a `read_screen`/`system_control` command for that action.
- It does **not** transmit accessibility data off-device. All processing is local; the only network
  path is the opt-in `web_search` tool, which is off by default and never sends accessibility tree
  content.
- It does **not** auto-perform actions. Every action originates from an explicit user command, and
  every `system_control`/`find_and_click`/`fill` action is classified `STRONG_CONFIRM` by
  `SafetyGuard` — the user must confirm before it executes.

## User control & transparency

- The service is **off by default**. The user must enable it in Android Settings → Accessibility,
  via a guided in-app permission flow, and can disable it at any time.
- Every executed action is written to a local **audit log** (input hashed, never stored in
  cleartext) viewable in-app under Logs, and exportable via `export_data`.
- A persistent foreground notification is shown while the agent is active.

## Control modes (planned — see docs/SAFETY.md)

The roadmap adds an explicit in-app **Control Mode** selector so the user can bound what the
service may do: Observe-only (read screen), Assist (suggest, ask before every tap), Agent
(execute direct actions, confirm risky ones), Lockdown (disable automation). Until then, the
`STRONG_CONFIRM` gate is the effective control mode for every UI action.

## Prominent disclosure

The in-app permission flow shows, before routing the user to Settings:
> "UnoOne needs Accessibility to read what's on your screen and to tap/type/fill when you ask it
> to. It never reads your screen in the background and never sends screen content off your phone.
> You can turn this off any time in Settings → Accessibility."