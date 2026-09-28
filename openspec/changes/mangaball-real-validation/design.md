# Design decisions

## Production boundary

This change adds **test infrastructure only**. It uses Mihon's extension loader and `SourceManager`, Tsuzuki's `AddonRepository`, `MihonReadingSourceGateway`, `ResolveContentBinding`/`ConfirmContentBinding`, `MihonChapterInventoryGateway`, `ReconcileChapterEvidence`, `ResolveChapterContent`, and Reader preparation. The test may wrap adapters for instrumentation timing but must not reimplement matcher or canonical decisions. `CanonicalTitle` and chapters are generated in one disposable target database, never from the user's library.

## Reference and query budget

One-Punch Man is the initial work because the user supplied a stable MangaBall title URL. The URL path is checked exactly (after safe URI parsing) against the candidate identity; equality of title text alone is insufficient. The public web page currently displays no translations to a web viewer, which is **not** evidence that the Mihon extension inventory is empty or pages inaccessible. The emulator performs only one pt-BR directed search, one selected title inventory, and at most one chapter page-list request. Another source is disabled solely to prove eligibility filtering, not queried as a control. No automatic retry after 429/CAPTCHA.

## Harness and reporting

Prefer extracting a small provider-neutral production composition from the existing MangaFire Android journey over copying its adapter wiring. Avoid changing production runtime. A new MangaBall profile supplies package/version/source ID, reference path and language; the extension-specific profile belongs only in Android test code. Run through a separate `workflow_dispatch` with `live_probe=true` for provider access; fixture loading can run with `live_probe=false`. The sanitizer/verifier is fail-closed: a JUnit PASS with missing, duplicate, contradictory, or out-of-order stages is not an E2E PASS. An external uncertainty remains explicitly `INCONCLUSIVE`; GitHub job success may indicate only that diagnostics were produced, so the handoff must report stage outcomes.

## Risks and controls

- The extension may no longer expose the reference work or chapters. Stop at the first unsupported stage, do not choose a different edition by title.
- Third-party Java code may ignore coroutine cancellation. Reuse the existing daemon-worker deadline wrapper and do not issue broad retry loops.
- A 42-source extension is one Add-on. The test must assert grouping and disabled-source exclusion before live search.
- Reconciliation may yield provisional chapters; only exact content options linked to the selected source/chapter may proceed to Reader preparation.
- `getPageList` may return page metadata but no page bytes/images are retained.

## Acceptance

The task may complete as a demonstrated full journey only if each required stage passes in one real run, deterministic verifier tests and planner-selected CI v2.1 pass, and sanitized artifacts show database cleanup. Otherwise hand off the first inconclusive/failed stage and an objective next experiment. Emulator evidence does not replace manual phone UX acceptance of reading position.
