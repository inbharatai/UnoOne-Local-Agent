# UnoOne Agent Flow

## The Local Agent Loop

```
User speaks
    │
    ▼
┌──────────────────────────────────────┐
│ 1. LISTENING                         │
│    • Mic permission check             │
│    • AudioRecord starts               │
│    • VAD detects voice start          │
│    • Show pulsing mic animation       │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 2. TRANSCRIBING LOCALLY              │
│    • PCM buffer → Sherpa-ONNX STT     │
│    • Show "Thinking..." subtitle      │
│    • Measure STT latency              │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 3. UNDERSTANDING LOCALLY             │
│    • Text → prompt template           │
│    • Inject memory context (optional) │
│    • Local LLM inference              │
│    • Parse JSON tool call             │
│    • Measure model latency            │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 4. TOOL SELECTED                     │
│    • Validate tool exists             │
│    • Validate required args           │
│    • Show tool name + args preview    │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 5. SAFETY CHECK                      │
│    • Risk classifier runs             │
│    • Risk 0: direct execute           │
│    • Risk 1: show confirmation         │
│    • Risk 2: strong confirmation        │
│    • Risk 3: block + log              │
└──────────────────────────────────────┘
    │
    ▼ (if approved)
┌──────────────────────────────────────┐
│ 6. EXECUTING                         │
│    • Invoke Android Intent or DB op   │
│    • Catch exceptions                 │
│    • Measure execution latency        │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 7. VERIFYING                         │
│    • Check action result              │
│    • Verify expected side effect      │
│    • Store ActionLog entry            │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 8. SPEAKING RESPONSE                 │
│    • Format confirmation text         │
│    • Run TTS                        │
│    • Measure TTS latency              │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│ 9. DONE / FAILED                     │
│    • Update timeline to final state   │
│    • Show user-readable reason        │
│    • Offer retry if failed            │
└──────────────────────────────────────┘
```

## UI Timeline States

| # | State Label        | Color / Icon        | Duration           |
|---|--------------------|---------------------|--------------------|
| 1 | Listening          | Red pulse, mic      | Until VAD end      |
| 2 | Transcribing       | Yellow, waveform    | STT latency        |
| 3 | Understanding      | Blue, brain icon    | Model latency      |
| 4 | Tool Selected      | Purple, wrench      | Instant            |
| 5 | Safety Check       | Orange, shield      | Until user confirm |
| 6 | Executing          | Cyan, gear spin     | Execution latency  |
| 7 | Verifying          | Teal, checkmark     | Instant            |
| 8 | Speaking Response  | Green, speaker      | TTS latency        |
| 9 | Done               | Green check         | Persistent         |
| - | Failed             | Red X, retry button | Persistent         |

## Example: "Create a note: buy milk tomorrow"

**Timeline progression:**

1. **Listening** — user presses mic, speaks.
2. **Transcribing** — "create a note buy milk tomorrow" appears.
3. **Understanding** — model returns:
   ```json
   {
     "tool": "create_note",
     "args": {
       "title": "Buy milk",
       "content": "Buy milk tomorrow",
       "tags": ["shopping"]
     }
   }
   ```
4. **Tool Selected** — `create_note` validated.
5. **Safety Check** — Risk 0, direct execute.
6. **Executing** — Room DB insert.
7. **Verifying** — Note ID returned, count verified.
8. **Speaking Response** — TTS: "Note created: Buy milk tomorrow."
9. **Done** — timeline shows green check, note visible in Notes tab.

## Example: "Open google dot com"

1. **Listening** — user speaks.
2. **Transcribing** — "open google dot com"
3. **Understanding** — model returns:
   ```json
   {
     "tool": "open_url",
     "args": {
       "url": "https://www.google.com"
     }
   }
   ```
4. **Tool Selected** — `open_url` validated.
5. **Safety Check** — Risk 1, confirmation dialog shown:
   > "Open https://www.google.com in browser?"
   > [Cancel] [Open]
6. **Executing** — Chrome intent launched.
7. **Verifying** — `startActivity` succeeded.
8. **Speaking Response** — TTS: "Opening Google."
9. **Done**

## Error Paths

### STT Failure
- State: Listening → **Failed**
- Reason: "Could not hear you clearly. Please try again."
- Action: Retry mic button shown.

### Model Parse Failure
- State: Understanding → **Failed**
- Reason: "I did not understand that command."
- Action: Fallback to chat-style response or retry.

### Tool Validation Failure
- State: Tool Selected → **Failed**
- Reason: "That action is not supported yet."
- Action: Log unsupported tool for future review.

### Safety Block
- State: Safety Check → **Failed**
- Reason: "This action is not allowed for safety reasons."
- Action: Log block, no retry.

### Execution Failure
- State: Executing → **Failed**
- Reason: "Could not complete the action. Chrome may not be installed."
- Action: Retry button if transient.

## Logging

Every loop iteration creates an `action_logs` row:

```
input_text:     "create a note buy milk tomorrow"
input_type:     "voice"
selected_tool:  "create_note"
tool_args_json: "{...}"
risk_level:     0
status:         "success"
error_message:  null
stt_latency_ms: 420
model_latency_ms: 1800
tts_latency_ms: 350
created_at:     2026-05-23T10:15:30Z
```
