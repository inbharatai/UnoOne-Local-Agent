# Tool Schema Registry

## create_note

```json
{
  "tool": "create_note",
  "args": {
    "title": "string",
    "content": "string",
    "tags": ["string"],
    "reminder_time": "optional string (ISO-8601)"
  }
}
```

**Risk:** 0
**Storage:** Insert into `notes` table.

---

## search_notes

```json
{
  "tool": "search_notes",
  "args": {
    "query": "string"
  }
}
```

**Risk:** 0
**Storage:** Query `notes` table with `LIKE`.

---

## summarize_text

```json
{
  "tool": "summarize_text",
  "args": {
    "text": "string",
    "max_sentences": "optional int (default 3)"
  }
}
```

**Risk:** 0
**Logic:** Run local LLM inference with summarization prompt.

---

## speak_response

```json
{
  "tool": "speak_response",
  "args": {
    "text": "string",
    "language": "string (e.g. en, hi)"
  }
}
```

**Risk:** 0
**Voice:** Run TTS engine with text.

---

## open_chrome

```json
{
  "tool": "open_chrome",
  "args": {}
}
```

**Risk:** 0
**Intent:** `ACTION_MAIN` + `com.android.chrome`

---

## open_url

```json
{
  "tool": "open_url",
  "args": {
    "url": "string (full URL with https://)"
  }
}
```

**Risk:** 1
**Intent:** `ACTION_VIEW` + URI
**Safety:** Show confirmation dialog with URL preview.

---

## open_app

```json
{
  "tool": "open_app",
  "args": {
    "app_name": "string (human name)",
    "package_name": "optional string (Android package)"
  }
}
```

**Risk:** 0
**Intent:** `getLaunchIntentForPackage`
**Fallback:** Resolve `app_name` via `PackageResolver`.

---

## open_calendar_insert

```json
{
  "tool": "open_calendar_insert",
  "args": {
    "title": "string",
    "start_time": "string (ISO-8601 or natural language parsed)",
    "end_time": "string",
    "description": "optional string",
    "location": "optional string"
  }
}
```

**Risk:** 1
**Intent:** `ACTION_INSERT` + `CalendarContract.Events.CONTENT_URI`
**Safety:** Show confirmation with event details.

---

## open_camera

```json
{
  "tool": "open_camera",
  "args": {}
}
```

**Risk:** 0
**Intent:** `ACTION_IMAGE_CAPTURE`

---

## show_agent_status

```json
{
  "tool": "show_agent_status",
  "args": {}
}
```

**Risk:** 0
**Logic:** Refresh Settings screen / toast model + voice status.
