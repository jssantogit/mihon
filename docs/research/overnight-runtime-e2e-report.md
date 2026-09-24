# Overnight runtime compatibility investigation

## Scope and baseline

- Branch: `tsuzuki/runtime-e2e-tests` (no branch switch or branch creation).
- Baseline/remote HEAD at session start: `203b73e5a8454f2f8c48eb0993dd6357f33e40f8`.
- Working tree was clean. No local edits existed to preserve.
- The current opt-in external matrix is run `35944402938` for that exact SHA; it has completed. Artifacts from all four shards have been downloaded and preserved under `/tmp/tsuzuki-source-matrix` before any push.
- The ordinary CI run `35944402927` for the same SHA completed successfully.
- Focused test-harness changes preserve sanitized AndroidJUnitRunner facts when a live source batch is incomplete, emit safe stage boundaries, and allow targeted retries. A CI planner fix also treats all `docs/` content as non-build input; the research CSV exposed an existing fail-safe-to-full path. No production runtime behavior is changed.

## Evidence captured so far

The live workflow's contracts and Android instrumentation compilation passed. In the original full-matrix run, shard 0 failed after completing the MangaBall, MangaFlix, and initial MangaDex observations; its sanitized artifacts contain 54 accepted rows out of 56: 42 MangaBall, 1 MangaFlix, 11 MangaDex. AnimeXNovel and Manga Livre.to had no accepted per-source observation in that run. The job annotations identify both batches as failing the required one-test JUnit success check, but the original runner deletes its temporary output and only emits the generic validator message. Later targeted retries below resolved these two source observations; the original run alone does not establish whether its cause was extension/runtime behavior, Android instrumentation, or blocked I/O.

Shard 1 has completed successfully. Its artifacts contain 56 observations: 50 MangaDex and 6 MangaDot. All are `RESULTS`; this verifies title-search results through the real Tsuzuki gateway for those specific source/language combinations, not chapter inventories or reader availability.

Shard 2 completed successfully with 56 MangaDot observations, all `RESULTS`. In the original full-matrix run, shard 3 failed its accounting check. Its artifact has 47 rows: 46 MangaDot `RESULTS` and one Mangas Brasuka `HTTP_RESPONSE` with status 403. The original MangaFire 1.6.34 batch produced no accepted observation for its 7 internal sources and failed the required one-test JUnit completion check. No source ID, language, or search outcome was available for those seven attempts in that run; the targeted retry below subsequently observed all seven.

The **original full matrix** yielded 213/222 source observations. By extension: MangaBall 42/42 `RESULTS`; AnimeXNovel 0/1 valid observation; MangaFlix 1/1 `RESULTS`; Manga Livre.to 0/1; MangaDex 61/61 `RESULTS`; MangaDot 108/108 `RESULTS`; MangaFire 0/7 valid observations; Mangas Brasuka 1/1 `HTTP_RESPONSE`/403. Of those accepted observations, 212 are `RESULTS` and one is HTTP 403. At that time nine source outcomes were missing (AnimeXNovel 1, Manga Livre.to 1, MangaFire 7). The targeted retries below resolved those observation gaps: across the original and targeted runs, 222/222 unique sources now have an observed safe category—220 `RESULTS`, one `EMPTY`, and one HTTP 403. Historical missing-batch rows remain in the CSV, and the targeted results are appended as separate run-bound source rows. A `RESULTS` outcome means only that search returned candidates; it does not establish canonical identity, inventory coverage, or reader availability. MangaFire result counts of 50 are capped at the page size, not a total-match count.

### Combined search-outcome matrix

| Extension fixture | Internal sources | `RESULTS` | `EMPTY` | HTTP response error | Evidence |
|---|---:|---:|---:|---|---|
| MangaBall | 42 | 42 | 0 | — | Original full matrix |
| AnimeXNovel | 1 | 0 | 1 | — | Targeted retry |
| MangaFlix | 1 | 1 | 0 | — | Original full matrix |
| Manga Livre.to | 1 | 1 | 0 | — | Targeted retry |
| MangaDex | 61 | 61 | 0 | — | Original full matrix |
| MangaDot | 108 | 108 | 0 | — | Original full matrix |
| MangaFire | 7 | 7 | 0 | — | Targeted retry |
| Mangas Brasuka | 1 | 0 | 0 | 1 × HTTP 403 | Original full matrix |
| **Total unique sources** | **222** | **220** | **1** | **1 × HTTP 403** | Combined run-bound observations |

The table combines distinct run observations; it is not a single 222-source execution. It describes whether the extension search gateway returned candidates, returned none, or surfaced an HTTP response error. It does not assert that any particular candidate is One-Punch Man, that a candidate is safe to bind, or that its chapters/content can be read.

The workflow's failure annotations confirm that the three incomplete batches were rejected because the runner did not end with the required single passing JUnit test; they do not report the underlying runner outcome. Sanitized shard-0 logs show each of the two failures occurred about 34 seconds after the fixture began, but the temporary runner output was deleted and no test/search stage was retained. Offline extension installation/registration passed for those fixtures. Shard-3 logs identify MangaFire's installed package/version as `eu.kanade.tachiyomi.extension.all.mangafire` / `1.6.34`; its search outcome was unknown in the original run, and is now established by the separate targeted retry below. This was a confirmed observability gap, not a confirmed provider/runtime defect. RED/GREEN Python regressions cover a bounded allowlisted batch summary preserving valid source events and safe stage markers when JUnit completion is absent, while never converting missing events to `EMPTY`.

Focused local checks passed: CI planner tests (34, including RED/GREEN proving research CSV no longer selects full/release lanes), extension live report tests (7), existing Android instrumentation sanitizer tests (6), new live batch summary tests (7, including unknown-versus-empty, privacy, size bounds, rejected oversize input, and non-allowlisted/wrong-shard targets), fixture contracts (3), package detection tests (5), fixture integrity for all eight APKs, `bash -n` for the live runner, `actionlint` v1.7.12, workflow YAML syntax parsing, and `git diff --check`. The affected-mode planner selected `App — Tsuzuki` plus Format; it did not select Domain, Database, Supabase, or Release. No Gradle command was run locally.

## Architectural constraints reviewed

The authoritative runtime design keeps canonical titles and chapters Tsuzuki-owned; source identities and source chapters remain evidence/variants. Mihon remains the operational reading mechanism. The compatibility matrix is an opt-in observation, never a routine CI requirement. It makes one serialized search request per source, with backoff for explicit CAPTCHA/429, and must not publish raw provider responses, URLs, cookies, headers, or exception messages.

The Android probe wraps a gateway search in coroutine `withTimeout`, but an extension may invoke Java/blocking operations. A coroutine timeout is not proof that such a call is interrupted. The original incomplete batches contained no stage/result event to establish whether they reached the extension lookup or a source call. The follow-up instrumentation now records terminal extension-lookup stages as well as source entry/results. The observed timeout was in harness lookup before provider search; no claim is made about interruption of a provider's blocking call.

## Actions completed and disposition

1. The original full-matrix artifacts were preserved under `/tmp/tsuzuki-source-matrix`; do not rerun that 222-source matrix.
2. The first focused retry (`35948648078`) was inconclusive, but the enhanced retry (`35949995253`) confirmed `EXTENSION_LOOKUP_TIMEOUT|elapsedMs=30002` in the test's trusted-only extension lookup, with no source search. The exact absent/mis-trusted state remains unknown.
3. The lookup harness checks both trusted-installed and untrusted extension state and performs test-only trust for the exact fixture in the disposable emulator; terminal stage records are allowlisted. The follow-up instrumentation compiled and proceeded to actual source searches.
4. Targeted retries are complete for AnimeXNovel (`35951192811`: its single `pt-BR` source returned `EMPTY`, 0 results, 1368 ms) and Manga Livre.to (`35951908581`: its single `pt-BR` source returned `RESULTS`, one candidate, 2982 ms). Neither proves canonical identity, chapter inventory, or readability.
5. MangaFire-only shard 3 (`35952770951`) completed with all seven source searches returning candidates. No explicit 429/CAPTCHA was observed. Never rerun all shards.
6. A `RESULTS` outcome is only evidence that search returned candidates, not proof of correct binding, inventory coverage, or readable chapters. Use runtime changes only after an exact stage reproduces an application-side cause.

## Current status

- 8/8 user-provided extension APK loading/registration remains prior evidence. Across initial plus targeted searches, all 222 sources have a safe observed search category; this is not proof every search succeeded.
- Original full-matrix run `35944402938`: shard 0 54/56 accepted; shard 1 56/56; shard 2 56/56; shard 3 47/54. It had 213 accepted source observations: 212 results, one HTTP 403; nine outcomes missing in that run. Subsequent targeted probes account for these nine sources.
- Targeted retry `35949995253` confirms an Android instrumentation-stage timeout while seeking the expected installed extension. It establishes no HTTP/provider result and does not identify why that invocation's extension-manager flow lacked a trusted-flow match. The updated harness subsequently reached source search.
- The new targeted retry `35951192811` completed: the single AnimeXNovel source query was `EMPTY` (0 candidates, 1368 ms). This shows the revised probe reached the search gateway, unlike its prior timed-out invocation.
- Manga Livre.to targeted retry `35951908581` completed: its only `pt-BR` source returned `RESULTS` with one candidate (2982 ms). Exact title matching/reading are untested.
- MangaFire targeted retry `35952770951` completed successfully: all seven MangaFire 1.6.34 sources returned search candidates (one returned 11, six returned the capped 50); IDs, languages, counts, and durations are in the CSV.
- Mangas Brasuka HTTP 403 is a confirmed response category only, not evidence of CAPTCHA or Tsuzuki defect.
- Commits `3d6189c5`, `97e6f551`, `803a5340`, `174ad565`, `16f0646f`, and `fb408d8c` are on the exclusive branch. The invalid workflow condition was fixed in `97e6f551`; pushes without `[android-live-matrix]` did not repeat provider requests.
- Fast CI for `803a5340` and `174ad565` passed (planner, App Tsuzuki tests, Format, CI gate). Fixture/MangaFire/live-workflow compile/contract checks also passed; emulator load checks were skipped by push conditions. APK Build was skipped.
- Terminal lookup instrumentation compiled in live-workflow compile job `35951192811`; its opt-in Android shard completed and queried AnimeXNovel successfully as a test flow, with an `EMPTY` provider search response. The prior 30-second timeout was therefore at the test's lookup boundary, not in an AnimeXNovel provider request. The precise trusted/untrusted state at the first failure was not retained, so do not claim the untrusted branch specifically caused recovery.
- Manga Livre.to returned one candidate in a separate one-source targeted probe. MangaFire returned candidates on all seven sources, but exact matching/binding/inventory/reader behavior is unverified.
- MangaFire/chapter inventory/fallback on these actual extensions are not proven by this search matrix.

## Targeted retry and follow-up instrumentation (2026-09-24)

The dynamic matrix was manually dispatched for **AnimeXNovel only** (`35948648078`, SHA `97e6f551`). It instantiated only shard 0. The sanitized artifact at
`/tmp/tsuzuki-targeted-animexnovel/animexnovel-1.6.19.apk.diagnostic.txt` reports:

```text
runnerExit=0; expectedClass=true; expectedMethod=true; singleTestDeclared=true
junitPass=false; probeEvents=0; stage=EXTENSION_LOOKUP_START
```

This narrows the observed stopping point: the test method was discovered and emitted `EXTENSION_LOOKUP_START`, but no `EXTENSION_READY`, source attempt, or provider search event was retained. Offline extension installation/trust/registration checks in the workflow's preceding phase passed. The available evidence does **not** distinguish a 30-second lookup timeout from another lookup exception or cancellation; no cause is confirmed. No source request can be attributed to this attempt.

A test-first follow-up extends the allowlisted diagnostic format with terminal extension lookup stages (`TIMEOUT`, `CANCELLED`, `FAILURE`) and a bounded elapsed-time field. The Android test rethrows the original throwable after emitting only the closed stage category and elapsed time; it does not change extension or provider behavior. Python RED was observed before the parser update (the timeout stage was omitted), then the focused suite passed (9 tests) after both stage additions; report-sanitizer tests pass (7). `git diff --check` and Actionlint pass. No Gradle command was run locally. Fast CI for code commit `174ad565` (`35950924576`) and the instrumentation compile/check workflows passed. The live source-search compile passed under `35951192811`; subsequent targeted emulator searches succeeded as described below. Pushes did not carry `[android-live-matrix]`, so they did not repeat the full provider matrix.

### Targeted AnimeXNovel retry with explicit terminal stage

The opt-in single-target retry (`35949995253`, SHA `803a5340`) compiled the Android instrumentation and then failed in the source-probe test phase. Its sanitized diagnostic shows:

```text
junitPass=false; probeEvents=0
EXTENSION_LOOKUP_START
EXTENSION_LOOKUP_TIMEOUT|elapsedMs=30002
```

Therefore this run stopped in the test's **trusted installed-extension lookup** after 30 seconds and did not issue an AnimeXNovel search. It is not evidence of an AnimeXNovel HTTP/provider failure. The preceding offline APK load/registration test passed in its own instrumentation invocation. The live-test method had only waited for the extension in `installedExtensionsFlow`; it did not repeat the offline test's explicit check for the untrusted-extension state and test-only trust path. The exact reason the fresh live-test invocation had no matching trusted-flow item is not distinguished by this event alone; it may have been untrusted or not yet loaded. In `174ad565`, the probe harness was updated to check installed and untrusted state, trust only this pinned fixture in the disposable emulator, and keep the original 30-second total lookup deadline. A new allowlisted `EXTENSION_LOOKUP_UNTRUSTED` event has a regression test.

Run `35949749740` for the preceding `803a5340` commit completed successfully (Change Planner, App Tsuzuki tests, Format, and CI gate green; release, compile, DB, native package and Supabase lanes skipped as planner-directed). Fixture byte verification, MangaFire diagnostic tests/compile, and live workflow contract/compile jobs also passed. The failed live job `35949995253` is separate and targeted to one fixture; no source attempt occurred.

### Completed targeted source observations

- `35951192811` / SHA `174ad565`: AnimeXNovel 1.6.19 source `3639622845750368999`, language `pt-BR`, outcome `EMPTY`, 0 candidates, 1368 ms. The run itself succeeded; no provider error was returned.
- `35951908581` / SHA `16f0646f`: Manga Livre.to 1.6.57 source `1281902081932329042`, `pt-BR`, outcome `RESULTS`, one candidate, 2982 ms.
- `35952770951` / SHA `16f0646f`: MangaFire 1.6.34 all seven sources returned `RESULTS`: `ja` 11; `pt`, `es-419`, `en`, `fr`, `pt-BR`, `es` each 50 candidates (capped page size). No CAPTCHA or 429 was observed. Exact candidate titles and URLs were not exported.

Together with the original run these produce 222 unique source observations: 220 `RESULTS`, one `EMPTY`, one HTTP 403. This is full outcome accounting, not 222 successful canonical matches. The observed 403 remains generic HTTP status only; no CAPTCHA classification.
