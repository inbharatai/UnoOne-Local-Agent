# Conservative repository cleanup — 0.8.1-alpha-voice

2026-10-07; base main `a96f0e6fd79314ed497be8e649ed898a2a21d89a`, versionCode9.

## Removed or simplified

- Four private declarations with no consumers in the complete tracked-text/reference scan: AgentScreen.calculateProgress, AgentViewModel.READ_SCREEN_PHRASES, ModelManager.deleteDirectoryContentsReportingFailures, and ModelManager.LEGACY_E2B_ID. Active routing/deletion/fallback code remains.
- 24 ordinary unused type imports; no Compose getValue/setValue/operator imports removed.
- Direct @page-agent/page-controller declaration plus root lock declaration. It remains required transitively by @page-agent/core; resolved package records unchanged. This removes redundant declarations, not the installed package.
- 17 GradleDependency baseline entries redundant with the existing disabled detector. **Not 17 fixed errors or upgraded dependencies.** All15 still-active baseline entries retained; no new detector disabled or suppression added.
- Corrected stale documentation about a deprecated fail-closed model-cleanup helper; public API/body preserved.

## Documentation repaired

Current navigation starts with README/floating guide/current receipts. Older STATUS, migration plan, device verification, current-state audit and setup/development documents now carry dated historical banners. Historical bodies, checkmarks, failures, test totals, hashes and immutable evidence were preserved. Corrected stale current packaging-pending and uncommitted-worktree wording. No history purge or branch deletion.

## Deliberately retained

GemmaE2B/E4B, Qwen and Owl profiles/runtimes; native safety and Stop registrations; voice, browser, BlindAid, notes/documents and both Skills interfaces; licenses; fixtures and negative experiments; model preservation/rollback; user data; supported ABIs. No whole-file deletion had sufficient reachability proof. Ignored build caches are not APK bloat and were not presented as such.

Static inspection measured202,661,468bytes of non-arm64 native payload in the historical0.8 debugAPK. Removing those ABIs changes compatibility; it was NOT done. A separate arm64-specific build could be evaluated with explicit product scope, not disguised as cleanup. No substantial APK/RAM/speed improvement is claimed.

## Verification

Independent source audit/review verified the narrow diff and preserved APIs/registrations/tests/assets. Final cleanup1 clean app/core/voice compile, **1006 JVM tests**, lint and both APK assemblies passed;0failures/errors/skips. Browser clean npmci/typecheck10unit15Playwright passed,productionaudit0vulnerabilities;11 invariant mutation tests passed. Lint still filters15 historical errors;17 redundant unmatched entries removed.

DebugAPK 425,313,403bytes, SHA256 `35f1ad11ba3328f4579e5cd5306b890130f75307c0da6ffdfdbc4fea0520bf7a`. Same signer and preserved Owl/native/DOM packaging checked. Model/native inference was not rerun because the cleanup changes no model runtime behavior; historical model evidence retains its exact scope. Phone speech, app behavior, timings/thermal/battery and full16KB-device qualification remain pending; pre-existing CameraX ELF exception remains. [Matching receipts](evidence/cleanup-delivery/results.json).
