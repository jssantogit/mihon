# Tasks

- [x] Pin and test fixture integrity: SHA-256, APK contents, signature; fail before installation on mismatch.
- [x] Add a reusable, production-backed Android journey profile for MangaBall pt-BR, with one Add-on/42-source grouping and disabled-peer exclusion. Preserve the existing MangaFire journey.
- [x] Exercise one exact-reference candidate through progressive search, explicit confirmation, persistent binding, inventory, reconciliation, content option, Reader target and at most one `getPageList`; stop with stage-specific `INCONCLUSIVE` if unavailable.
- [x] Add offline verifier/sanitizer tests that reject zero JUnit methods, omitted or duplicated stages, fabricated PASS after an external blocker, and sensitive/unallowlisted output.
- [x] Extend the already-registered opt-in Android workflow with a MangaBall profile and routing tests. `live_probe=false` must not call the provider; `live_probe=true` limits calls to the specified source/work/chapter.
- [ ] Run cheap local Python/shell checks, then planner-selected CI v2.1 for code; no local Gradle. Dispatch the Android workflow only after its inputs and fixture hash are reviewed. Inspect sanitized stage artifacts and apply no more than two focused correction rounds.
- [ ] Update `docs/research/generic-addon-compatibility-handoff.md` with per-stage results, exact runs/SHAs, external limits, merge readiness, and separate manual phone acceptance.
