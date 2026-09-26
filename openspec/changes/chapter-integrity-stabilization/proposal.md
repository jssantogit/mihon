# Chapter integrity stabilization

## Why

The Fast branch has two chapter write paths. Canonical detail, Library updates, and discovery use chapter evidence; legacy Reader entry and source switching still refresh source inventories and variants. Evidence reconciliation currently writes each observation separately, including a full title-evidence lookup inside each upsert. This threatens atomicity and grows quadratically. Reports also describe chapter ordering, mismatched inventory counts, and failed Reader switches. The historical One-Punch Man inventory diagnosis predates later parser fixes and cannot establish current provider behavior.

## What changes

- Make evidence reconciliation atomic and linear in the size of one title's inventory, preserving existing IDs, progress, and observations.
- Require a verified, chapter-specific identity for every reading option; keep ambiguous, provisional, confirmed, and conflicted evidence distinct.
- Route legacy inventory observations through the canonical evidence contract where compatibility requires them, retaining Mihon as the operational reader.
- Keep targeted Fast discovery, bounded waits, source eligibility, and selective invalidation.
- Prepare Reader replacements before publishing sessions or mutating history, and recover canonical progress if Mihon projection fails.
- Add deterministic regressions for the reported titles, failures, concurrency, migrations, and performance. Record real device and provider checks separately.

## Impact

Affected areas are Tsuzuki chapter domain, SQLDelight repositories, Mihon adapters, selector, Reader, and focused tests. Existing user databases are preserved. No visual redesign or provider sweep is included.
