# Tasks

- [x] Pin and test fixture integrity: SHA-256, APK contents, signature; fail before installation on mismatch.
- [x] Add a reusable, production-backed Android journey profile for MangaBall pt-BR, with one Add-on/42-source grouping and disabled-peer exclusion. Preserve the existing MangaFire journey.
- [x] Exercise one exact-reference candidate through progressive search, explicit confirmation, persistent binding, inventory, reconciliation, content option, Reader target and at most one `getPageList`; stop with stage-specific `INCONCLUSIVE` if unavailable.
- [x] Add offline verifier/sanitizer tests that reject zero JUnit methods, omitted or duplicated stages, fabricated PASS after an external blocker, and sensitive/unallowlisted output.
- [x] Extend the already-registered opt-in Android workflow with a MangaBall profile and routing tests. `live_probe=false` must not call the provider; `live_probe=true` limits calls to the specified source/work/chapter.
- [x] Run and inspect planner-selected CI v2.1 and the reviewed Android workflows without local Gradle. Two focused correction rounds were consumed. Results are recorded in the handoff; this marks the observations/correction budget as completed, not the acceptance gates as green.
- [x] Update `docs/research/generic-addon-compatibility-handoff.md` with per-stage results, exact runs/SHAs, external limits, merge readiness, and separate manual phone acceptance.
- [ ] After renewed correction authorization, resolve the remaining Format failure and obtain a truthful final result for the bounded MangaBall journey. Keep live-provider calls opt-in and limited to the one pt-BR source; do not treat `INCONCLUSIVE` or skipped checks as PASS. Manual phone UX acceptance remains separate.
