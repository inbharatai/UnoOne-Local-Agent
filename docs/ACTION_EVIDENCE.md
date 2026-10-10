# Action evidence and task completion

An Android native goal can have a verified postcondition without proving the user's wider task is complete. The task ledger now records `ACTION_VERIFIED` separately from `VERIFIED`.

| Native goal | Successful task outcome | What was checked |
|---|---|---|
| Open an app; read a screen | `VERIFIED` | The explicit open/read goal's native predicate |
| Find visible text; click, focus, edit, scroll or go back | `ACTION_VERIFIED` | The bounded native action or observation only |
| Sequence containing any action-only step | `ACTION_VERIFIED` | Each step's native predicate; no inferred end-to-end result |
| A missing, ambiguous, blocked or unverified step | Existing `NEEDS_USER`, `FAILED` or cancellation | No promotion based on the requested goal |

The coordinator, Task Board, voice result, latency trace and recovery journal preserve this distinction. Action-only results explicitly say that wider completion was not established. A later step must still reobserve the current app and establish its own postcondition; this outcome is never permission to auto-run more steps or silently retry a side effect.

This change improves reporting accuracy. It does not add arbitrary app control, independent recipient identity checks, a general phone agent planner, device qualification or a measured task success rate. The frozen 100-task Android corpus and physical speech, thermal and battery gates remain pending.
