# Overnight runtime compatibility investigation

## Scope and baseline

- Branch: `tsuzuki/runtime-e2e-tests` (no branch switch or branch creation).
- Baseline/remote HEAD at session start: `203b73e5a8454f2f8c48eb0993dd6357f33e40f8`.
- Working tree was clean. No local edits existed to preserve.
- The current opt-in external matrix is run `35944402938` for that exact SHA; it has completed. Artifacts from all four shards have been downloaded and preserved under `/tmp/tsuzuki-source-matrix` before any push.
- The ordinary CI run `35944402927` for the same SHA completed successfully.
- Focused test-harness changes preserve sanitized AndroidJUnitRunner facts when a live source batch is incomplete, emit safe stage boundaries, and allow targeted retries. A CI planner fix also treats all `docs/` content as non-build input; the research CSV exposed an existing fail-safe-to-full path. No production runtime behavior is changed.

## Evidence captured so far

The live workflow's contracts and Android instrumentation compilation passed. Shard 0 failed after completing the MangaBall, MangaFlix, and initial MangaDex observations; its sanitized artifacts contain 54 accepted rows out of 56: 42 MangaBall, 1 MangaFlix, 11 MangaDex. AnimeXNovel and Manga Livre.to have no accepted per-source observation. The job annotations identify both batches as failing the required one-test JUnit success check, but the original runner deletes its temporary output and only emits the generic validator message. The evidence identifies incomplete test-harness results, not whether the cause is extension/runtime behavior, Android instrumentation, or blocked I/O.

Shard 1 has completed successfully. Its artifacts contain 56 observations: 50 MangaDex and 6 MangaDot. All are `RESULTS`; this verifies title-search results through the real Tsuzuki gateway for those specific source/language combinations, not chapter inventories or reader availability.

Shard 2 completed successfully with 56 MangaDot observations, all `RESULTS`. Shard 3 failed its accounting check. Its artifact has 47 rows: 46 MangaDot `RESULTS` and one Mangas Brasuka `HTTP_RESPONSE` with status 403. The MangaFire 1.6.34 batch produced no accepted observation for its 7 internal sources and failed the required one-test JUnit completion check. No source ID, language, or search outcome is available for those seven attempts.

The verified total is 213/222 source observations. By extension: MangaBall 42/42 `RESULTS`; AnimeXNovel 0/1 valid observation; MangaFlix 1/1 `RESULTS`; Manga Livre.to 0/1; MangaDex 61/61 `RESULTS`; MangaDot 108/108 `RESULTS`; MangaFire 0/7 valid observations; Mangas Brasuka 1/1 `HTTP_RESPONSE`/403. Of 213 accepted observations, 212 are `RESULTS` and one is HTTP 403. Nine sources have no valid outcome: AnimeXNovel (1), Manga Livre.to (1), and MangaFire (7). Their exact IDs and languages were not emitted. See `overnight-runtime-e2e-results.csv` for accepted sanitized source rows and explicit batch gaps. A `RESULTS` outcome indicates only that the search returned candidate records; it does not establish exact canonical identity, chapter inventories, or readable content.

The workflow's failure annotations confirm that the three incomplete batches were rejected because the runner did not end with the required single passing JUnit test; they do not report the underlying runner outcome. Sanitized shard-0 logs show each of the two failures occurred about 34 seconds after the fixture began, but the temporary runner output was deleted and no test/search stage was retained. Offline extension installation/registration passed for those fixtures. Shard-3 logs identify MangaFire's installed package/version as `eu.kanade.tachiyomi.extension.all.mangafire` / `1.6.34`; its actual search outcome remains unknown. This is a confirmed observability gap, not a confirmed provider/runtime defect. RED/GREEN Python regressions now cover a bounded allowlisted batch summary preserving valid source events and safe stage markers when JUnit completion is absent, while never converting missing events to `EMPTY`.

Focused local checks passed: CI planner tests (34, including RED/GREEN proving research CSV no longer selects full/release lanes), extension live report tests (7), existing Android instrumentation sanitizer tests (6), new live batch summary tests (7, including unknown-versus-empty, privacy, size bounds, rejected oversize input, and non-allowlisted/wrong-shard targets), fixture contracts (3), package detection tests (5), fixture integrity for all eight APKs, `bash -n` for the live runner, `actionlint` v1.7.12, workflow YAML syntax parsing, and `git diff --check`. The affected-mode planner selected `App — Tsuzuki` plus Format; it did not select Domain, Database, Supabase, or Release. No Gradle command was run locally.

## Architectural constraints reviewed

The authoritative runtime design keeps canonical titles and chapters Tsuzuki-owned; source identities and source chapters remain evidence/variants. Mihon remains the operational reading mechanism. The compatibility matrix is an opt-in observation, never a routine CI requirement. It makes one serialized search request per source, with backoff for explicit CAPTCHA/429, and must not publish raw provider responses, URLs, cookies, headers, or exception messages.

The Android probe wraps a gateway search in coroutine `withTimeout`, but an extension may invoke Java/blocking operations. A coroutine timeout is not proof that such a call is interrupted. The current incomplete batches contain no stage/result event to establish whether they reached the extension lookup or a source call, so no blocking-call conclusion is drawn. The new retry will record lookup/source-entry/result stages without changing operational behavior.

## Next actions

1. All current shard artifacts have been preserved; do not rerun the full matrix.
2. The available logs do not reveal why JUnit did not pass; no provider/runtime/timeout/HTTP root cause is established. The focused diagnostic-observability change, safe stage events, and targeted-dispatch option are local only.
3. Push `3d6189c5` Fast CI completed successfully (App Tsuzuki tests + Format; all other release/database/compile lanes skipped by the affected-mode plan). The MangaFire instrumentation compile passed; the general fixture workflow's byte checks and compile passed, while its emulator run was skipped. APK Build was skipped.
4. The live workflow itself failed before creating jobs on `3d6189c5`. Local actionlint v1.7.12 identified the precise error: job-level `if` referenced `matrix.shard`, a context unavailable at that key. No provider searches ran in this failed workflow. The workflow now derives a dynamic shard matrix from its input and avoids `matrix` in the job condition; this correction is still unpushed.
5. Validate the correction through Fast CI and the workflow's contract/compile-only jobs; ensure this push does not launch provider searches. The planner regression confirms that documentation/CSV handoffs cannot force a full release build.
6. Manually dispatch only the three incomplete batches (`animexnovel`, `mangalivreto`, then `mangafire`) sequentially, review each sanitized artifact, and stop additional requests on CAPTCHA/429. These are at most nine internal-source attempts; do not rerun the full matrix.
7. Use the new stage events to identify the last confirmed boundary. Implement a runtime correction only if a reproducible runtime cause is demonstrated; otherwise fix instrumentation or document uncertainty.

## Current status

- 8/8 user-provided extension APK loading/registration remains prior evidence; 222/222 exposed sources are not yet evidence that every live search works.
- Current live run `35944402938`: shard 0 54/56 accepted; shard 1 56/56; shard 2 56/56; shard 3 47/54. Total 213 accepted source observations: 212 results, one HTTP 403; nine outcomes unknown.
- Root cause for AnimeXNovel, Manga Livre.to, and MangaFire: **unknown**. Their JUnit failures do not prove provider, runtime, timeout, network, HTTP, CAPTCHA, or extension causes.
- Mangas Brasuka HTTP 403 is a confirmed response category only, not evidence of CAPTCHA or Tsuzuki defect.
- Push `3d6189c5` contains the diagnostics, CSV report, and targeted dispatch, but its live workflow parse failed as detailed above. The corrected dynamic-matrix version (`97e6f551`) is remote and its contract/compile check passed.
- No regression has yet established the provider cause. Do not infer MangaFire root cause from the workflow parse error.
- MangaFire/chapter inventory/fallback on these actual extensions are not proven by this search matrix.

## Targeted retry and follow-up instrumentation (2026-09-24)

The dynamic matrix was manually dispatched for **AnimeXNovel only** (`35948648078`, SHA `97e6f551`). It instantiated only shard 0. The sanitized artifact at
`/tmp/tsuzuki-targeted-animexnovel/animexnovel-1.6.19.apk.diagnostic.txt` reports:

```text
runnerExit=0; expectedClass=true; expectedMethod=true; singleTestDeclared=true
junitPass=false; probeEvents=0; stage=EXTENSION_LOOKUP_START
```

This narrows the observed stopping point: the test method was discovered and emitted `EXTENSION_LOOKUP_START`, but no `EXTENSION_READY`, source attempt, or provider search event was retained. Offline extension installation/trust/registration checks in the workflow's preceding phase passed. The available evidence does **not** distinguish a 30-second lookup timeout from another lookup exception or cancellation; no cause is confirmed. No source request can be attributed to this attempt.

A test-first follow-up now extends the allowlisted diagnostic format with terminal extension lookup stages (`TIMEOUT`, `CANCELLED`, `FAILURE`) and a bounded elapsed-time field. The Android test rethrows the original throwable after emitting only the closed stage category and elapsed time; it does not change extension or provider behavior. Python RED was observed before the parser update (the timeout stage was omitted), then the focused suite passed (8 tests) after the update; report-sanitizer tests also pass (7). `git diff --check` and Actionlint pass. No Gradle task was run locally. This code has not yet been pushed/compiled by CI.

The upcoming ordinary push to the live-workflow paths is expected to trigger the workflow but **not** external provider requests because the commit will not carry `[android-live-matrix]`; its shard job is marker-gated. The next allowed external actions are sequential targeted dispatches only, first AnimeXNovel with the richer stage, then Manga Livre.to and MangaFire if the artifact leaves requests warranted. Stop on explicit rate-limit/CAPTCHA observations.

### Targeted AnimeXNovel retry with explicit terminal stage

The opt-in single-target retry (`35949995253`, SHA `803a5340`) compiled the Android instrumentation and then failed in the source-probe test phase. Its sanitized diagnostic shows:

```text
junitPass=false; probeEvents=0
EXTENSION_LOOKUP_START
EXTENSION_LOOKUP_TIMEOUT|elapsedMs=30002
```

Therefore this run stopped in the test's **trusted installed-extension lookup** after 30 seconds and did not issue an AnimeXNovel search. It is not evidence of an AnimeXNovel HTTP/provider failure. The preceding offline APK load/registration test passed in its own instrumentation invocation. The live-test method had only waited for the extension in `installedExtensionsFlow`; it did not repeat the offline test's explicit check for the untrusted-extension state and test-only trust path. The exact reason the fresh live-test invocation had no matching trusted-flow item is not distinguished by this event alone; it may be untrusted/not yet loaded. The probe harness is now being updated to check installed then untrusted state, trust only this pinned fixture in the disposable emulator, and keep the original 30-second total lookup deadline. A new allowlisted `EXTENSION_LOOKUP_UNTRUSTED` event has a regression test.

Run `35949749740` for the preceding `803a5340` commit completed successfully (Change Planner, App Tsuzuki tests, Format, and CI gate green; release, compile, DB, native package and Supabase lanes skipped as planner-directed). Fixture byte verification, MangaFire diagnostic tests/compile, and live workflow contract/compile jobs also passed. The failed live job `35949995253` is separate and targeted to one fixture; no source attempt occurred.
