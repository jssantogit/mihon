# Overnight runtime compatibility investigation

## Scope and baseline

- Branch: `tsuzuki/runtime-e2e-tests` (no branch switch or branch creation).
- Baseline/remote HEAD at session start: `203b73e5a8454f2f8c48eb0993dd6357f33e40f8`.
- HEAD before this report amendment: `9ae3bbbf9988bf38331fd4808f2376aa5dd1f345`.
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

The table combines distinct run observations; it is not a single 222-source execution. The live test queried One-Punch Man using Japanese text for `ja`, Chinese text for `zh`, Korean text for `ko`, Russian text for `ru`, and `One Punch Man` for other languages. It describes whether the extension search gateway returned candidates, returned none, or surfaced an HTTP response error. It does not assert that any particular candidate is One-Punch Man, that a candidate is safe to bind, or that its chapters/content can be read.

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
- Documentation commit `9ae3bbbf` consolidated the 222-source outcome matrix and targeted observations. CI v2 run `35954184810` succeeded with Change Planner and CI Gate; app, Domain, Format, and release lanes were skipped because this commit changed research documentation only. APK Build run `35954184905` was skipped. No APK was generated.

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

Together with the original run these produce 222 unique source observations: 220 `RESULTS`, one `EMPTY`, one HTTP 403. This is full outcome accounting, not 222 successful canonical matches. The observed 403 remains generic HTTP status only; no CAPTCHA classification. Documentation commit `9ae3bbbf9988bf38331fd4808f2376aa5dd1f345` passed CI v2 `35954184810`'s planner/gate, with app, Domain, Format, and release work skipped as planner-directed. APK Build `35954184905` was skipped.

## Full reading-flow follow-up (2026-09-24)

### Scope and evidence boundary

The current code audit confirmed that a search result alone is not sufficient for a reading alternative. `ResolveContentBinding` requires an enabled add-on and eligible internal source, performs search/matching (or explicit confirmation), materializes the selected candidate through `MihonReadingSourceGateway`, and persists a `ContentBinding`. Inventory refresh then resolves the materialized Mihon manga and source, `RefreshChapterEvidence`/`ReconcileChapterEvidence` associate source chapter evidence with canonical chapters, and `MihonContentProvider` only produces an option when a source chapter has a trusted canonical identity match. `PrepareCanonicalChapterForReader` then projects the selected option to an existing Mihon chapter/source coordinate. Chapter-page retrieval by the actual extension/reader is a further boundary.

No new live MangaFire, MangaBall, or MangaDex requests were issued in this follow-up. The historical MangaFire seven-source `RESULTS` observations remain search-only evidence; candidate identity, binding, chapter inventory, option presence, content-page retrieval, and fallback on those real APKs remain unverified.

### Reproduced generic identity-integrity gap

`MihonChapterInventoryGateway.fetchLive` loaded a `Manga` solely by `SourceTitleMapping.mihonMangaId`, then called the configured `Source.getMangaUpdate` without checking that the loaded Mihon row's `source` and `url` still matched the binding's `sourceId` and `sourceUrl`. Normal materialization establishes matching values, but a stale/corrupt persisted ID could have attributed a different manga's chapter inventory to the canonical mapping. This is a reproduced generic guard gap, **not evidence that MangaFire had a mismatched stored row or that this caused its earlier `BINDING_UNAVAILABLE` event**.

RED tests were added at `ed60614f` for both wrong source ID and wrong source URL. CI `35977061996` failed those two new tests (App); Domain and planner passed. The first implementation `fbe0f9ab` correctly blocked both bad identities, but CI `35977621224` exposed invalid pre-existing gateway fixtures whose manga rows had the default blank URL. Those normal inventory, timeout, and network tests failed before reaching the source, while the two new mismatch regressions passed. This was a test-fixture failure caused by enforcing the actual mapping invariant, not a production failure. `1250d5df` aligned those fixtures to the mapping's `/title` URL and made identity mismatches report the closed `INDETERMINATE` outcome with `IDENTITY_MISMATCH`, rather than incorrectly labeling this local integrity failure as an extension failure. CI `35978112974` passed planner, App Tsuzuki and Domain Tsuzuki tests; Format was planner-skipped for that change. The guard returns failure before looking up/invoking the source; diagnostic output contains only source ID, language, elapsed time, and closed reason, never the URL.

### Synthetic reader-target continuation

The deterministic local HTTP journey in `MihonRuntimeEndToEndIntegrationTest` exercises real `HttpSource`/`MockWebServer` search, canonical-title matching, binding materialization and persistence, inventory fetch, canonical reconciliation, materialized chapter option, and alternatives. It now sends the selected option through production `PrepareCanonicalChapterForReader`, `MihonChapterContentPreparer`, and `MihonCanonicalReaderGateway`, then calls the same Mihon `CatalogueSource.getPageList` interface for a synthetic chapter URL served by the local test server. It asserts the page-list URL and the same source, manga, chapter, and canonical IDs. This proves the deterministic synthetic path through page-list retrieval; it does not exercise protected content or page retrieval from one of the real APK extensions.

CI for `cc85f111` (`35978668537`) found two test harness issues before execution: Spotless import ordering and MockK's nullable-return stubbing for `CanonicalDownloadRepository`. The test now uses small typed no-state repository fakes (`16ea7623`). Run `35979198807` passed App Tsuzuki tests but failed Format because the canonical title repository import had to follow the reader imports; `bc438aa4` corrected that, and run `35979668227` passed. Commit `5bd0714d` extended the synthetic journey through `CatalogueSource.getPageList`; run `35980143340` proved the runtime path reached that API but failed because the fixture inherited `HttpSource.pageListRequest`, which requires the app-global Injekt `NetworkHelper` not set up by this local harness. The fixture now overrides the page-list request to use its local `MockWebServer` (`4d8f4c51`); CI `35980679829` passed App Tsuzuki, Domain Tsuzuki, Format, planner, and gate. This was a deterministic harness setup issue, not an observed extension failure. The synthetic journey does not mutate canonical progress/history; real extension behavior and user-visible Reader fallback remain unverified.

### Current follow-up commits and pending checks

- `ed60614f`: RED source/url binding-integrity regressions; pushed.
- `fbe0f9ab`: fail-closed source/url identity guard; initial CI failed due to test fixture URL mismatch.
- `1250d5df`: accurate mismatch outcome and corrected fixture identities; CI `35978112974` passed App + Domain + planner; no real-provider query.
- `cc85f111`: synthetic journey extended through reader-target preparation; CI `35978668537` failed due to import formatting and invalid nullable MockK stub.
- `16ea7623`: typed in-memory repository fakes replace invalid stubs; CI `35979198807` passed App Tsuzuki but failed Format due to one import-order rule.
- `bc438aa4`: import-order correction; CI `35979668227` passed.
- `5bd0714d`: synthetic page-list fetch via Mihon `CatalogueSource.getPageList`; CI `35980143340` failed at a missing test-only Injekt `NetworkHelper` dependency.
- `4d8f4c51`: the fixture now overrides page-list request construction to use MockWebServer directly; CI `35980679829` passed App Tsuzuki, Domain Tsuzuki, Format, planner, and gate.

All commits were pushed to `tsuzuki/runtime-e2e-tests`. APK Build workflows for `cc85f111`, `16ea7623`, `bc438aa4`, `5bd0714d`, and `4d8f4c51` were planner-skipped; no Tsuzuki APK was generated. No merge, PR, or new branch was created. The next evidence-raising task is a separately authorized and bounded real-extension run that carries one known result past search into exact binding/inventory validation. Do not repeat the 222-source matrix. If the runtime test graph cannot be safely exercised without production-data writes or user confirmation, document that blocker and wait for an isolated test harness rather than claiming the real extension works.
