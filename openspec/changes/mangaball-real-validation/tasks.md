# Tasks

- [x] Pin and test fixture integrity: SHA-256, APK contents, signature; fail before installation on mismatch.
- [x] Add a reusable, production-backed Android journey profile for MangaBall pt-BR, with one Add-on/42-source grouping and disabled-peer exclusion. Preserve the existing MangaFire journey.
- [x] Exercise one exact-reference candidate through progressive search, explicit confirmation, persistent binding, inventory, reconciliation, content option, Reader target and at most one `getPageList`; stop with stage-specific `INCONCLUSIVE` if unavailable.
- [x] Add offline verifier/sanitizer tests that reject zero JUnit methods, omitted or duplicated stages, fabricated PASS after an external blocker, and sensitive/unallowlisted output.
- [x] Extend the already-registered opt-in Android workflow with a MangaBall profile and routing tests. `live_probe=false` must not call the provider; `live_probe=true` limits calls to the specified source/work/chapter.
- [x] Run and inspect planner-selected CI v2.1 and the reviewed Android workflows without local Gradle. Two focused correction rounds were consumed. Results are recorded in the handoff; this marks the observations/correction budget as completed, not the acceptance gates as green.
- [x] Update `docs/research/generic-addon-compatibility-handoff.md` with per-stage results, exact runs/SHAs, external limits, merge readiness, and separate manual phone acceptance.
- [x] Resolve the Format failure: App, Format and CI Gate passed [36140285894](https://github.com/jssantogit/mihon/actions/runs/36140285894) at Kotlin code SHA `34376b3f`; fixture and `androidTest` compilation also passed at `f2352bd5` in [36141497440](https://github.com/jssantogit/mihon/actions/runs/36141497440).
- [x] Execute one bounded opt-in MangaBall pt-BR diagnostic after introducing sanitized closed fields. [36141497440](https://github.com/jssantogit/mihon/actions/runs/36141497440) confirmed 42 loaded / 41 eligible and classified the source search as `SEARCH / INDETERMINATE / no HTTP status / root class OTHER`. This is an **INCONCLUSIVE** live E2E, not an acceptance PASS.
- [ ] Establish a specific root cause before considering any production runtime change. Further provider requests require a justified, explicitly opted-in narrow diagnostic; do not repeat the 42-source matrix.
- [ ] Complete manual real-phone Reader navigation and page-position UX acceptance with a pinned test APK. No merge/PR/distribution APK from this checkpoint.
