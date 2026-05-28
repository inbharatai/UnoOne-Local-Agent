# PhoneControl Implementation Plan

## Overview

The `phonecontrol` module executes safe Android actions using system `Intents`. No AccessibilityService is used at this stage — only standard, user-visible intents.

## Architecture

```
┌─────────────────────────────────────────┐
│          PhoneControl.kt                │
│  (openChrome, openUrl, openApp, etc.)   │
└─────────────────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
┌────────┐   ┌──────────┐   ┌──────────┐
│ Intent │   │ Package  │   │ Calendar │
│ACTION_ │   │ Manager  │   │ Contract │
│MAIN    │   │          │   │ Intent   │
└────────┘   └──────────┘   └──────────┘
```

## Implemented Actions

All methods already implemented in `PhoneControl.kt`:

| Method | Intent | Risk Level | Confirmation |
|--------|--------|------------|--------------|
| `openChrome()` | `ACTION_MAIN` + `CATEGORY_LAUNCHER` + `com.android.chrome` | 0 | No |
| `openUrl(url)` | `ACTION_VIEW` + URI | 1 | Yes |
| `openApp(pkg)` | `getLaunchIntentForPackage` | 0 | No |
| `openCalendarInsert(...)` | `ACTION_INSERT` + `Events.CONTENT_URI` | 1 | Yes |
| `openCamera()` | `ACTION_IMAGE_CAPTURE` | 0 | No |
| `openSettings()` | `ACTION_SETTINGS` | 0 | No |
| `openDialer(number?)` | `ACTION_DIAL` | 1 | Yes |
| `shareText(text)` | `ACTION_SEND` + `text/plain` | 1 | Yes |

## Error Handling

Every action returns `Result<Unit>`:
- `Success` — intent was launched.
- `Error` — app not installed, permission missing, or unexpected exception.

**User-facing messages:**
- Chrome not installed → "Chrome is not installed on this phone."
- App not installed → "WhatsApp is not installed."
- URL blocked → "Cannot open this URL."

## Package Name Resolution

**File:** `phonecontrol/src/main/java/com/unoone/agent/phonecontrol/PackageResolver.kt`

```kotlin
object PackageResolver {
    fun resolveAppName(name: String): String? {
        return when (name.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "gmail" -> "com.google.android.gmail"
            "calendar" -> "com.google.android.calendar"
            "camera" -> "com.android.camera"
            "settings" -> "com.android.settings"
            "chrome" -> "com.android.chrome"
            "youtube" -> "com.google.android.youtube"
            else -> null
        }
    }
}
```

## Testing

**Test on Xiaomi 14:**
1. Speak "Open Chrome" → Chrome opens.
2. Speak "Open WhatsApp" → WhatsApp opens if installed.
3. Speak "Open Gmail" → Gmail opens if installed.
4. Speak "Open google dot com" → Confirmation dialog → Chrome opens URL.
5. Speak "Book calendar tomorrow at 5 PM meeting with Ramesh" → Calendar insert screen opens with pre-filled fields.

## Acceptance Criteria

- [ ] All intents launch the expected system UI.
- [ ] Missing apps show clear error messages.
- [ ] Risk 1 actions always show confirmation before executing.
- [ ] No background or hidden actions.
- [ ] Works in airplane mode.
