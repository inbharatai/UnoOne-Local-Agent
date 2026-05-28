# UnoOne Permissions

## Required Permissions

| Permission | Type | Why | When Requested |
|------------|------|-----|----------------|
| `RECORD_AUDIO` | Runtime | Capture voice for STT | First mic tap |
| `INTERNET` | Install-time | Download models (optional) | Install |
| `WRITE_EXTERNAL_STORAGE` | Runtime (Android < 10) | Store model files on external storage if needed | First model download |
| `READ_EXTERNAL_STORAGE` | Runtime (Android < 10) | Read model files | First model load |
| `MANAGE_EXTERNAL_STORAGE` | Special (Android 11+) | Access model folders in scoped storage | Settings prompt |

## Optional Permissions

| Permission | Type | Why | When Requested |
|------------|------|-----|----------------|
| `CAMERA` | Runtime | Open camera intent | First camera command |
| `READ_CALENDAR` | Runtime | Verify calendar event created | Optional, deferred |
| `WRITE_CALENDAR` | Runtime | Insert calendar event directly | Optional, deferred |
| `CALL_PHONE` | Runtime | Dialer intent | First dialer command |
| `READ_CONTACTS` | Runtime | Resolve contact names | Optional, deferred |
| `BIND_ACCESSIBILITY_SERVICE` | Special | Future screen automation | Accessibility settings, deferred |
| `FOREGROUND_SERVICE` | Install-time | Keep mic listening in background | Install |
| `WAKE_LOCK` | Install-time | Prevent sleep during TTS | Install |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Special | Prevent system killing app | Settings prompt |

## Permission Rationale Strings

### RECORD_AUDIO
> "UnoOne needs microphone access to listen to your voice commands. Audio is processed entirely on your phone and never uploaded."

### STORAGE (Models)
> "UnoOne needs storage access to load AI model files. These files stay on your device."

### CAMERA
> "UnoOne can open your camera when you ask. The app does not take photos without your command."

### CALENDAR
> "UnoOne can help you create calendar events. It only opens the calendar screen with pre-filled details."

### CALL_PHONE / DIALER
> "UnoOne can open the dialer with a number ready to call. It does not make calls automatically."

## Android 13+ Specific

- `READ_MEDIA_AUDIO` — not needed (we use AudioRecord directly)
- `POST_NOTIFICATIONS` — optional, for background listening notification
- `NEARBY_DEVICES` — not needed (local only)

## Permission Flow Diagram

```
App Launch
    │
    ▼
No runtime permissions requested yet
    │
    ▼
User taps mic
    │
    ▼
Show RECORD_AUDIO rationale dialog
    │
    ▼
Request RECORD_AUDIO permission
    │
    ├─ Granted → Start listening
    │
    └─ Denied → Show "Mic required" message
              → Offer Settings shortcut
```

## Settings Screen Permission Status

Settings screen shows:
- Mic permission: Granted / Denied
- Storage permission: Granted / Denied
- Camera permission: Granted / Denied / Not requested
- Calendar permission: Granted / Denied / Not requested
- Battery optimization: Ignored / Optimized (with fix button)

Each row has a button to open Android Settings for that permission.

## Best Practices

1. Never request permission before user action that needs it.
2. Always show rationale before requesting.
3. If denied, gracefully degrade (e.g., show text input instead of mic).
4. If permanently denied, provide deep link to App Settings.
5. Log permission decisions in ActionLogs for debugging.
