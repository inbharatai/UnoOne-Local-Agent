# UnoOne Safety & Tool Risk Model

How UnoOne decides what an agent action may do, and the roadmap to tighten it. This is the
reference behind `SafetyGuard`, `ToolPermissionRegistry`, and the Play-review safety narrative.

## 1. Two-layer risk classification (implemented)

Every tool call passes through `SafetyPipeline.classifyRisk(tool, input)`, which takes the **max**
of:

- **Tool-level risk** — `SafetyGuard.classify(toolName)` → a fixed tier per tool.
- **Input-level risk** — `SafetyGuard.classifyFromInput(rawText)` → keyword-based override that can
  only raise (never lower) the tier.

Tiers:

| Tier | Behavior |
|---|---|
| `DIRECT` | Executes immediately (notes, search, open Chrome, etc.) |
| `CONFIRM` | One-tap confirmation dialog |
| `STRONG_CONFIRM` | User must type "confirm" (destructive / automation / drafts) |
| `BLOCK` | Never executes (payments, passwords, OTP, auto-send, install, factory reset) |

## 2. Contextual draft vs. auto-send (implemented 2026-06-26)

`classifyFromInput` now distinguishes **draft** paths from **auto-send** paths:

- `send money / pay / bank / credit card / wire transfer / OTP / password` → **BLOCK**
- `auto send / send automatically / send it now` → **BLOCK**
- `draft …` or `send/… WhatsApp/email …` → **STRONG_CONFIRM** (draft; user still presses send)
- generic `send …` / `message` with no draft/app context → **BLOCK**

So "send a WhatsApp message to mom" routes to `send_whatsapp` (STRONG_CONFIRM) and proceeds with
confirmation instead of being hard-blocked. Auto-send of a final message is always blocked.

## 3. Tool risk tiers (current)

- **DIRECT:** `create_note`, `search_notes`, `summarize_text`, `speak_response`, `open_chrome`,
  `open_app`, `deactivate_blind_aid`, `check_calendar`.
- **CONFIRM:** `open_url`, `open_calendar_insert`, `open_dialer`, `share_text`, `read_screen`,
  `ocr_screen`, `open_camera`, `create_skill`, `voice_recording`, `web_search`, and the standalone
  defensive entries `click`, `type`, `long_press`.
- **STRONG_CONFIRM:** `delete_notes`, `delete_all_notes`, `export_data`, `detect_objects`,
  `draft_email`, `send_whatsapp`, `system_control`, `find_and_click`, `fill`.
- **BLOCK:** `send_message`, `make_payment`, `install_app`, `access_passwords`, `silent_control`.

### Tool-schema alignment note (item 29)

All UI control is executed through `system_control(action=…)` (Option A). The standalone names
`click`/`type`/`long_press`/`find_and_click`/`fill` also appear in the risk table as **defensive
classifications** so any tool call that arrives under those names is still gated, even though the
normal path is `system_control`. This is intentional defense-in-depth, not a naming bug.

## 3.1 User-selected enforcement level

The stored security level is consulted by both the phone agent and Secure Browser PageAgent:

| Level | Phone agent | Secure Browser |
|---|---|---|
| `STANDARD` (default) | Judge and every confirmation/block enforced. | Confirmation, takeover and block decisions enforced. |
| `RELAXED` | Judge disabled; BLOCK enforced; confirmations auto-approve. | Standard browser action decisions remain enforced. |
| `OFF` | Judge, confirmations and blocks bypassed. | PageAgent confirmation, takeover and block decisions bypassed. |

`OFF` is deliberately unsafe and is labelled as such in Settings and Secure Browser. It allows
PageAgent to interact with forms, user-selected file inputs, credentials and payment fields without
an UnoOne safety prompt. It does not disable the exact-origin WebView message bridge, grant arbitrary
filesystem access, or add a JavaScript/native-code execution tool. Those boundaries remain in every
mode. Changing the mode is local and persistent; `STANDARD` remains the first-launch default.

## 4. Online tools toggle (spec — partial)

- `web_search` is `CONFIRM` and in `UnoOneToolSet` so the model can propose it.
- **Roadmap:** add an `Online tools: OFF by default` setting. When OFF, exclude `web_search` from
  the Gemma tool schema so the model cannot propose it; show a privacy warning when turning ON.
- Until that setting ships, the offline-first guard in `ActionExecutor` (`isOnline()`) returns an
  explicit offline message when there is no network, and `web_search` never auto-opens links.

## 5. Control Mode selector (spec)

A visible in-app selector bounding what Accessibility automation may do:

| Mode | Allowed |
|---|---|
| Observe only | `read_screen`, summarize — no taps/types |
| Assist | suggest actions, ask before every tap/type |
| Agent | execute DIRECT actions, confirm risky ones (current effective behavior via STRONG_CONFIRM) |
| Lockdown | disable all automation |

Until the selector ships, the STRONG_CONFIRM gate on every `system_control`/`find_and_click`/`fill`
is the effective control mode.

## 6. `open_app` category escalation (spec)

`open_app` is currently DIRECT. Roadmap: escalate by app category — normal apps DIRECT;
camera/settings/browser/payment/banking/password-manager/work-profile → CONFIRM; unknown
package name proposed by the LLM → CONFIRM.

## 7. Calendar / read_screen consent (spec)

- `check_calendar` is DIRECT after `READ_CALENDAR` is granted. Roadmap: first calendar access
  CONFIRM, or a persistent "Allow calendar reads without confirmation: OFF by default" setting.
- `read_screen` is CONFIRM. Roadmap: session consent ("Allow screen reading for next 10 minutes" /
  "only in current app" / "always ask") to reduce confirmation fatigue for accessibility users.

## 8. Blind Aid camera vs. navigation (spec)

`detect_objects` currently requires CAMERA + Accessibility. Roadmap: split into
`detect_objects_camera` (CAMERA only — CameraX + ML Kit object detection) and
`blind_aid_navigation_mode` (CAMERA + Accessibility — when UI foregrounding/context is needed),
reducing permission burden and Play risk.

## 9. Package resolution (current + roadmap)

`PackageResolver` uses a curated ~10-app map (no `QUERY_ALL_PACKAGES`). Roadmap: add
`queryIntentActivities()` for launchable apps plus user-selected app shortcuts saved locally.
`QUERY_ALL_PACKAGES` will not be requested.

## 10. Audit integrity (spec)

`AuditLogger` stores a SHA-256 of the raw input (not cleartext) and the outcome. Roadmap: add a
local hash chain (`log_hash = sha256(previous_hash + timestamp + tool + args + status)`) so
exported logs can show tampering. Requires a new `previousHash` column on `ActionLogEntity` + a
Room migration — tracked, not yet shipped.

## 11. Privacy dashboard (spec)

A front-and-center screen showing: mic active, camera active, screen reading active, online tools
active, Android fallback active, Gemma loaded, last 10 actions, export/delete data. The building
blocks exist (audit viewer, `DataExporter`, `VoiceRuntimeState`) but are not yet consolidated into
one dashboard.
