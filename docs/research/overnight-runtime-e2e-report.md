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

CI for `cc85f111` (`35978668537`) found two test harness issues before execution: Spotless import ordering and MockK's nullable-return stubbing for `CanonicalDownloadRepository`. The test now uses small typed no-state repository fakes (`16ea7623`). Run `35979198807` passed App Tsuzuki tests but failed Format because the canonical title repository import had to follow the reader imports; `bc438aa4` corrected that, and run `35979668227` passed. Commit `5bd0714d` extended the synthetic journey through `CatalogueSource.getPageList`; run `35980143340` proved the runtime path reached that API but failed because the fixture inherited `HttpSource.pageListRequest`, which requires the app-global Injekt `NetworkHelper` not set up by this local harness. The fixture now overrides the page-list request to use its local `MockWebServer` (`4d8f4c51`); CI `35980679829` passed App Tsuzuki, Format, planner, and gate; Domain was planner-skipped because only app test fixtures changed. This was a deterministic harness setup issue, not an observed extension failure. The synthetic journey does not mutate canonical progress/history; real extension behavior and user-visible Reader fallback remain unverified.

### Current follow-up commits and pending checks

- `ed60614f`: RED source/url binding-integrity regressions; pushed.
- `fbe0f9ab`: fail-closed source/url identity guard; initial CI failed due to test fixture URL mismatch.
- `1250d5df`: accurate mismatch outcome and corrected fixture identities; CI `35978112974` passed App + Domain + planner; no real-provider query.
- `cc85f111`: synthetic journey extended through reader-target preparation; CI `35978668537` failed due to import formatting and invalid nullable MockK stub.
- `16ea7623`: typed in-memory repository fakes replace invalid stubs; CI `35979198807` passed App Tsuzuki but failed Format due to one import-order rule.
- `bc438aa4`: import-order correction; CI `35979668227` passed.
- `5bd0714d`: synthetic page-list fetch via Mihon `CatalogueSource.getPageList`; CI `35980143340` failed at a missing test-only Injekt `NetworkHelper` dependency.
- `4d8f4c51`: the fixture now overrides page-list request construction to use MockWebServer directly; CI `35980679829` passed App Tsuzuki, Format, planner, and gate; Domain was planner-skipped.

All commits were pushed to `tsuzuki/runtime-e2e-tests`. APK Build workflows for `cc85f111`, `16ea7623`, `bc438aa4`, `5bd0714d`, and `4d8f4c51` were planner-skipped; no Tsuzuki APK was generated. No merge, PR, or new branch was created. The next evidence-raising task is a separately authorized and bounded real-extension run that carries one known result past search into exact binding/inventory validation. Do not repeat the 222-source matrix. If the runtime test graph cannot be safely exercised without production-data writes or user confirmation, document that blocker and wait for an isolated test harness rather than claiming the real extension works.

## Real-extension end-to-end follow-up (2026-09-24)

### Start-state verification

- Local branch was `tsuzuki/runtime-e2e-tests`, at `523b514dc012b0e4f6b97335df2afa44d637b56a`, with a clean working tree.
- Direct `git fetch origin` failed with exit 128 because this environment could not resolve `github.com`. The remote branch was independently checked using the configured GitHub connector: comparing remote `tsuzuki/runtime-e2e-tests` with `523b514dc012b0e4f6b97335df2afa44d637b56a` returned `identical`, ahead 0 / behind 0. Thus the remote head at this check was verified without treating the failed fetch as evidence.
- The current checkout contains all eight immutable extension fixtures. The MangaFire APK's observed SHA-256 is `f5a2bedca694bcf1ef7b133c82d0c0d99a8e1c59e9237b0d93f9153d9c3c7378`, matching `.github/scripts/verify_extension_fixture.py`. This establishes fixture integrity, not publisher authenticity; its user-supplied provenance limitation above remains.
- `adb` is not installed in this environment. No physical device or local emulator is available. Local Gradle execution is disallowed by the project-owned `CI_FIRST` policy (`localHeavyAttempts: 0`).

### Runtime path audit and evidence boundary

The repository already has a real-extension fixture workflow and a separate opt-in live MangaFire search, but neither currently traverses the full production Tsuzuki reading path:

| Stage | Evidence in this checkout | Result |
|---|---|---|
| Fixture load / internal source registration | `MangaFireFixtureInstrumentedTest.loadsRealExtensionAndRegistersInternalSources` | Prior Android run proves MangaFire 1.6.34 loaded and its seven internal languages registered. |
| Real source search | `InstalledExtensionFixtureInstrumentedTest.optionalLiveSearchSourceBatch` calls `app.graph.readingSourceGateway.search`; historical run `35952770951` observed candidate results on the seven MangaFire source IDs. The other live method in `MangaFireFixtureInstrumentedTest` calls `CatalogueSource.getSearchManga` directly. | Search outcome only. Neither observation verifies title identity. Do not repeat the seven-source batch. |
| Binding / persistence | Current `AppGraph` exposes `addonRepository` and `readingSourceGateway`, but not the Tsuzuki canonical/binding repositories or their production interactors. Existing Android tests do not create a disposable canonical title and persist/reload a real candidate binding. | Not demonstrated by a real extension. |
| Inventory / reconciliation / alternatives | Production gateways and providers exist, and the deterministic `MihonRuntimeEndToEndIntegrationTest` uses a `MockWebServer` journey. Existing real-extension instrumentation does not invoke them with the APK's English source. | Synthetic path only; real MangaFire stages unverified. |
| Reader target / `getPageList` | The synthetic test reaches `CatalogueSource.getPageList` for a local HTTP fixture. Existing real-extension instrumentation does not resolve a chapter through the production canonical providers or call the extension's page-list method. | Synthetic path only; no real chapter/page-list proof. |

The static constraint is not a demonstrated runtime defect: the current instrumentation's `AppGraph` surface does not expose the canonical-title, binding, chapter-evidence repositories/use cases needed to construct the exact journey without either (a) adding a test-only dependency graph around the real storage graph, or (b) widening the production `AppGraph` API. The latter was not done. AppGraph also exposes the real `MihonReadingSourceGateway`, so a safe limited real search is possible; it is not sufficient for this task's requested end-to-end proof. A fresh CI emulator is the appropriate isolated store for any materialization that creates Mihon rows, but no emulator run was initiated because there is no implemented end-to-end instrumentation method to execute.

### Disposition

- No new runtime failure was reproduced, so no production correction or regression test was justified in this follow-up.
- No additional MangaFire, MangaBall, or MangaDex provider request was made. Historical provider-search records remain search-only evidence.
- No Android instrumentation, new CI, Gradle command, APK generation, commit, or push occurred in this session. The direct Git transport remains unavailable; the successful GitHub connector comparison only verified the initial branch head and did not provide a write/push path for source changes.
- Therefore this follow-up is **INCONCLUSIVE** for the requested real E2E, not a passing validation. It does not establish that MangaFire binding, inventory, reconciliation, alternatives, reader preparation, or page loading works or fails.

### Smallest next experiment

Add an opt-in Android instrumentation composition dedicated to the disposable emulator that wires the actual production `ResolveContentBinding` / `ConfirmContentBinding`, `MihonChapterInventoryGateway`, chapter probe/reconciliation, content provider, and reader preparation against the emulator's real repositories and `AppGraph` source manager. Keep the query to one English source and one selected, score-checked candidate; if more than one plausible edition is returned, require the test's explicit selection of an exactly verified candidate or end as `INCONCLUSIVE`. Persist only a unique throwaway canonical title/binding in the fresh emulator DB; do not run against user library state. Then record each stage as a sanitized terminal outcome and call `getPageList` only for a chapter whose binding/inventory identity has already been verified. Run that lane manually, separately from Fast CI and without repeating the 222-source matrix. Extend the same harness to MangaBall pt-BR and MangaDex pt-BR only after this first journey is green.

## Opt-in real MangaFire journey implementation (2026-09-24)

### Test-only composition

Added `MangaFireRealReadingJourneyInstrumentedTest`, an Android instrumentation method in the separate real-extension workflow. It keeps `AppGraph` unchanged. It uses the actual app graph only for installed extension state, `SourceManager`, source preferences and add-on registry; all canonical/Mihon manga, chapter, binding, evidence and reader repositories are constructed against `AppBindings` with `InstrumentationRegistry.getInstrumentation().context`, i.e. the instrumentation APK's private package database. The binding path uses the production `MihonReadingSourceGateway`, `NetworkToLocalManga`, `ConfirmContentBinding`, `MihonChapterInventoryGateway`, `MihonAddonProviderFactory`, `MihonChapterProbeProvider`, `ReconcileChapterEvidence`, `DefaultAddonRegistry`, `ResolveChapterContent`, `PrepareCanonicalChapterForReader`, `MihonChapterContentPreparer` and `MihonCanonicalReaderGateway`. No target-app library row, account, sync, progress or history is read or modified. The disposable canonical title is inserted in the instrumentation-only DB with `SOURCE_ONLY` identity and a random ID.

The run is explicitly opt-in (`allowLiveProvider=true`) and is bounded to the known MangaFire English internal source `6084907896154116083` and one search for One-Punch Man. It will only select a unique candidate with exact normalized title confidence and a source URL matching the user-supplied public MangaFire title slug `729pj-one-punch-man`; any source-off, ambiguity, identity mismatch, missing match, extension failure or stage timeout ends as `INCONCLUSIVE` with later stages `NOT_RUN`. It never chooses the first search result, enables a disabled source, changes matching thresholds, or falls back to a different edition. The source candidate's public reference URL is checked in memory and never logged.


Materialization is observed through a test-only decorator around the real `ReadingSourceGateway`: it captures only `Result.isSuccess` and duration while delegating the real implementation unchanged. It creates a separate `BINDING_MATERIALIZATION` result before `BINDING_CREATE`, so a failed insert/materialization cannot be mistaken for a binding decision failure. A pre-materialization confirmation failure remains a distinct binding-stage failure.

### Verification boundary and current result

Python verification tests now require the correct JUnit class/method, exactly one test, and all 14 ordered stage events (`EXTENSION_INSTALL` through `GET_PAGE_LIST`) to be `PASS` before the runner creates a synthetic one-test JUnit report. Failure artifacts go through a strict allowlist sanitizer and retain stage/category evidence only; raw runner output and crash buffer are not uploaded. The existing offline fixture-registration test remains in the workflow. The live dispatch now runs the full journey instead of the earlier direct raw search test.

Local checks completed: `test_android_instrumentation.py` 12/12; `test_summarize_android_instrumentation.py` 10/10; `test_extension_fixture.py` 6/6; fixture SHA verifier passed at `f5a2bedca694bcf1ef7b133c82d0c0d99a8e1c59e9237b0d93f9153d9c3c7378`; shell syntax and `git diff --check` passed. No Gradle task, local Android test or external MangaFire request was run: this checkout is governed by `CI_FIRST` with zero local-heavy attempts, has no adb, and the GitHub CLI transport failed DNS earlier in this session. Thus the newly written Kotlin instrumentation has **not yet compiled or executed** in this session, and every real MangaFire journey stage remains `NOT_RUN` for evidence purposes. Fixture hash integrity is not proof of publisher authenticity. No claim is made that MangaFire's binding, inventory, reader page-list, or availability is fixed.

The branch at the start of this implementation was `tsuzuki/runtime-e2e-tests`, local HEAD `e0ade119b76a695fce08bf7fbc11600f80df72fc`; it was one commit ahead of `origin/tsuzuki/runtime-e2e-tests` at `523b514dc012b0e4f6b97335df2afa44d637b56a`. A fetch attempt failed with DNS resolution error. No retry was made. The code and test-workflow changes are included with this report; commit, push and CI state are listed in the session handoff after validation.

### Next required action

Run the offline tests/format and Android test compilation in GitHub CI first. Then explicitly dispatch `.github/workflows/mangafire-real-extension.yml` with `live_probe=true` once only (or use the authorized `[android-live]` push marker). The live journey is considered a functional PASS only if the CI artifact contains exactly one test and all 14 stage events pass. A no-result, unsafe candidate identity, disabled source, provider challenge, network failure, timeout, missing inventory, reconciliation miss, empty option set, reader preparation error or empty page list must remain a sanitized failing/inconclusive run; it is not a green journey. After the first result, examine only the sanitized report. Do not extend to MangaBall/MangaDex or fallback until the MangaFire path has an identity-verified bound chapter.

### Session delivery checkpoint

- Local branch HEAD after the test/import correction: `825bcf3458bc9cf9f659fc0bce21c387b12fe79c`.
- Commits added after the verified remote base: `e0ade119b76a695fce08bf7fbc11600f80df72fc` (prior blocker report), `9a1dd34d6` (journey, workflow, validators and report), and `825bcf345` (correct package imports and import order in the Kotlin harness). The latter two form the implementation change; the intermediate `9a1dd34d6` has not been pushed independently.
- The only `git push` attempted for this work failed with DNS error `Could not resolve host: github.com`; no force push or retry occurred. At this checkpoint the local branch is three commits ahead of its last verified remote `523b514dc012b0e4f6b97335df2afa44d637b56a`. The final report/commit will record whether a later single push attempt succeeds.
- No GitHub CI run was triggered or observed for these commits. In particular, Android test compilation, format, emulator instrumentation and the real provider journey are still unverified. The prior green CI links in this report belong to earlier commits only.
- No production source, AppGraph, user database, APK artifact, merge or PR was changed/created. The opt-in emulator workflow is configured to install only the checksum-pinned MangaFire fixture and use the instrumentation package's disposable DB.

#### Final transport/status check

The initial push attempt failed with a DNS lookup error. A subsequent single `git push origin tsuzuki/runtime-e2e-tests` completed, and a successful `git ls-remote origin refs/heads/tsuzuki/runtime-e2e-tests` returned `85d77fac431d030c1fd36d846aa6f1fd64c33473`, matching local HEAD. The branch is therefore pushed and the working tree was clean at this verification. A fresh GitHub combined-status query for `85d77fac431d030c1fd36d846aa6f1fd64c33473` returned `statuses: []` on two checks; the available connector did not return workflow run IDs or checks. This empty result is **not** treated as CI success, failure, or proof a workflow did not start. Android compilation/format and the opt-in MangaFire journey remain unconfirmed; do not report them as executed or green without the Actions run/artifact. The marker-bearing push was configured to opt in the one-source/one-title workflow, but no sanitized run artifact was accessible in this session.

#### Later SSH transport and CI follow-up

This subsection supersedes the preceding transport/status snapshot for the later check. The HTTPS remote could not be used in the earlier mobile-network attempt. Read-only SSH `ls-remote` and SSH fetch both succeeded, exposing two intervening remote commits: `043f66685` (instrumentation import/nullability/format fix) and `c7498d130` (Spotless import order). These were preserved; the local report-only commit was rebased onto `c7498d130`, not force-pushed or used to overwrite remote work. The local `origin` URL was then changed to `git@github.com:jssantogit/mihon.git`, and a normal SSH push succeeded.

- The first report-only push after switching to SSH was `08b52cb6c94dfd7fbf48f4838c21dac69b717e97`; later report-only commits `1e661973f228b7d37f38d823809d8511d541ff82` and `c618d4a66bc50b24d53d8aafcc6349690b7088dc` added the confirmed transport/CI details. Final pushed HEAD at this report update is `c618d4a66bc50b24d53d8aafcc6349690b7088dc`; `git ls-remote` matched it and the working tree was clean.
- Commit `043f66685` CI v2.1 (`35991418592`) had planner success but Format failed and the App job was cancelled; its real-extension compilation workflow (`35991418676`) did pass fixture verification and instrumentation compilation/JUnit-signature checks, while `Load real APK in isolated emulator` was **skipped**. Its all-source and eight-fixture workflows passed their accounting/compile jobs but skipped live search and emulator execution.
- The formatting correction in `c7498d130` was validated by CI v2.1 (`35991761957`): Change Planner, App — Tsuzuki, Format, and CI Gate passed; unrelated backend/release/package lanes were skipped by the planner. Its MangaFire workflow (`35991761905`) passed fixture verification and instrumentation compilation, but again skipped `Load real APK in isolated emulator`. All-source search shards and real-extension fixture emulator jobs were skipped in their respective workflows. Thus the source-code checks are green at `c7498d130`, but no real-extension Android journey or fresh provider request was executed.
- The later APK Build workflow `35991976642`, triggered by the report-only commit `08b52cb6`, concluded `skipped`.
- Therefore the transport blocker is operationally bypassed by SSH, but its underlying cause (mobile carrier/DNS vs. HTTPS route) is not proven. Real MangaFire binding, inventory, reconciliation, reading alternative, and `getPageList` remain unverified. No APK was generated, no merge/PR occurred, and no Gradle command was run locally.

## E2E source-registration failure investigation (2026-09-24)

### Latest baseline and observed failure

- Branch remained `tsuzuki/runtime-e2e-tests`; remote and local were both `9104e9f7bf9405cbf640cfe4dd75c7b83cfccc95` at investigation start, with a clean tree. The branch had advanced beyond the user's stated `7221a9b` baseline; no existing work was discarded.
- CI v2.1 `36001018797` passed Change Planner, App — Tsuzuki, Format and CI Gate. The real-extension workflow `36001018885` passed fixture byte verification and Android instrumentation compilation, then failed during the opt-in emulator journey. The separate eight-fixture and 222-source workflows completed accounting/compile jobs but their emulator/search jobs were skipped.
- The sanitized artifact for `36001018885` records `EXTENSION_INSTALL=PASS`, `SOURCE_REGISTRATION=INCONCLUSIVE|category=INSTRUMENTATION`, and all later required stages `NOT_RUN`. It includes `exceptionType=java.lang.AssertionError`, but no `RUNTIME_SETUP` phase event and no MangaFire search event. The artifact's JUnit assertion is the downstream “all stages completed” guard after the journey stopped; it is not evidence of an Android database failure or an extension/provider failure.

### Root-cause evidence and limits

The failure occurs before the instrumentation-only database setup: `setupPhase` is set and a `RUNTIME_SETUP` event emitted only after source resolution and the `SOURCE_REGISTRATION=PASS` event. No setup-phase events were emitted. In the current test code, before that pass the only generic `INSTRUMENTATION` stop paths are (a) no unique English internal source on the installed extension object, or (b) an immediate `sourceManager.get(sourceId)` returning null/not a `CatalogueSource`.

The fixture test in the same workflow validates MangaFire 1.6.34's English source ID `6084907896154116083` and waits for it to appear in `sourceManager.sources`. The E2E test instead called `sourceManager.get(sourceId)` immediately. `AndroidSourceManager` builds its map asynchronously from `installedExtensionsFlow`; `get()` waits for the map to become non-null, not for this exact source ID to be present. This makes the immediate E2E lookup a concrete race candidate. Because the failed artifact deliberately omitted the branch-specific source-resolution fact, it does not prove whether (a) or (b) was taken. No claim is made that the database or MangaFire caused the observed failure.

### RED regressions and bounded correction

- Added a summary-parser RED test for a safe `SOURCE_REGISTRATION_TIMEOUT` stage event; it failed because the category was not allowlisted.
- Added a verifier RED test with the real 19-digit MangaFire source ID; it failed because the validator applied its 10-digit count/time bound to `sourceId` too. This was an independent, confirmed validation defect that would have blocked a successful source-registration event.
- The test now waits on `SourceManager.sources` until the exact expected ID appears, matching the existing fixture test's condition-based registration check and reusing its 30-second bound. Non-timeout cancellation still propagates. Empty English-source and wrong source-type cases have distinct closed categories; a registration timeout carries the expected source ID/language and elapsed time. The real stage is emitted only after exact source resolution and eligibility checks.
- The sanitizer and verifier now accept the three closed source-resolution categories. Source IDs use their own 20-digit numeric bound; count and elapsed-time values retain the 10-digit bound. No arbitrary throwable message, URL, response, or credential is emitted.
- Local RED/GREEN evidence: before correction, `test_summarize_android_instrumentation.py` had one failure for the new timeout category; `test_android_instrumentation.py` then had the expected failures for the 19-digit ID and closed timeout category. After correction, extension fixture tests 6/6, Android instrumentation verifier tests 14/14, summary sanitizer tests 15/15, runner `bash -n`, pinned MangaFire fixture SHA verification, and `git diff --check` passed.

### Next validation and status

The source-registration wait and validator correction are instrumented/test changes, not a production runtime fix. No MangaFire search request has been made by the failed `36001018885` E2E. The next required experiment is one explicitly opted-in rerun of the same real English MangaFire/One-Punch Man journey; it must emit one ordered event for each of the 14 stages. A registration timeout, missing source, or wrong type will now be distinguishable from database composition. The CI_FIRST policy has `localHeavyAttempts=0`; no local Gradle command was run. Current correction is pending review/CI submission at the time of this report entry.

## E2E instrumentation context isolation failure (2026-09-24)

### Result after source-registration correction

- Commit `6a2e1b1cf744ba457b54b7f56d152794975b2062` was pushed to the same exclusive branch with `[android-live]`; no new branch was created.
- Fast CI v2.1 `36003608709` passed Change Planner, Format, App — Tsuzuki and CI Gate. The real-extension job `36003608865` passed the APK fixture verifier and Kotlin instrumentation compilation, then failed on the emulator. The eight-extension fixture's emulator job and the 222-source search shards were skipped; no matrix requests were repeated.
- Sanitized artifact `10810296483` now records `EXTENSION_INSTALL=PASS` and `SOURCE_REGISTRATION=PASS|sourceId=6084907896154116083|language=en`. This verifies the source wait correction for the actual installed MangaFire extension and shows the run progressed farther than `9104e9f7`.
- The first failed setup phase is `CONTEXT_ISOLATION|outcome=FAIL|exception=NullPointerException`. `DB_RESET`, SQL driver, database adapters, production composition, title insertion, and all later E2E stages are not reached. There is no `LIVE_SEARCH` event, so this run made no MangaFire provider search request. The reported `java.lang.AssertionError` is again the final incomplete-stage assertion, not the root exception.

### Diagnosis and next minimal correction

The code at this failure boundary obtained `instrumentation.context.applicationContext` before checking that the database package differs from the target app. The exception type and phase isolate the fault to context acquisition/isolation, but the sanitized run intentionally contains no stack/message, so the precise dereference is not directly observed. The test only requires the instrumentation APK's package context to open its own database; the application-context conversion is unnecessary. The pending correction now uses `instrumentation.context` directly, then still checks `testContext.packageName != app.packageName` before database reset/creation. This preserves the disposable test database and does not expose or modify target-app data. The direct-context change is not yet validated by CI.

The source-registration race is no longer the blocking stage: the event changed from `INCONCLUSIVE|INSTRUMENTATION` to a single `PASS` with the exact English source ID after replacing immediate `get()` with a `sources` Flow wait. This is observed behavioral confirmation for the correction; the original `get()` null result was not itself logged, so the specific pre-fix branch remains inferred from the code/event boundary.

## Follow-up E2E after isolated instrumentation-context change (2026-09-24)

- Commit `c895ea493b45f2f66196c8f320b18d929f78ed76` is on `tsuzuki/runtime-e2e-tests`. The test now obtains the disposable database context from `Instrumentation.context` rather than converting it through nullable `applicationContext`; target and instrumentation package isolation remains checked before DB reset.
- CI v2.1 `36004922278` passed Change Planner, Format, App — Tsuzuki and CI Gate. The real-extension instrumentation compiled and its JUnit signature check passed in `36004922207`; the immutable APK fixture hash check also passed. Real emulator instrumentation then failed. Fixture matrix `36004923093` and all-source workflow `36004922266` completed successfully, but their emulator/live-search portions were skipped; neither is evidence for this MangaFire journey. APK Build `36004922265` was skipped.
- Sanitized artifact `10810462016` from `36004922207` records exactly one `SOURCE_REGISTRATION=PASS` with English source ID `6084907896154116083`. Setup phases `CONTEXT_ISOLATION`, `DB_RESET`, `SQL_DRIVER`, `DATABASE_ADAPTERS`, and `COMPOSITION` all pass. `CANONICAL_TITLE_INSERT` fails with categorized exception type `NullPointerException`; all 12 remaining E2E stages are `NOT_RUN`. `LIVE_SEARCH` was never invoked, so the run made no MangaFire provider request. The JUnit `AssertionError` only reflects required stages not having completed.
- This proves the previous context-isolation NPE is no longer at that boundary and narrows the current failure to the combined canonical-title construction/persistence setup phase. It does **not** reveal which expression inside that phase throws. The report intentionally excludes arbitrary stack traces/messages; no code change to production runtime is justified by this evidence. A focused follow-up should split `CANONICAL_TITLE_INSERT` into safe construction and persistence phases or capture an allowlisted project frame, then run the opt-in test only after the repository's correction-cycle limit is explicitly reset. Likely DB/query causes remain hypotheses, not confirmed causes.
- Per `.agents/rules/tsuzuki-development.md`, work stops after at most two correction cycles when acceptance still fails. The source-registration wait/validator change and instrumentation-context change are the two correction cycles in this sequence. Therefore this entry records the current state as **BLOCKED** rather than making an unauthorized third correction. No production defect has been established, and the required 14-stage real E2E remains unproven.

## Authorized continuation: split canonical-title setup phases (2026-09-24)

The user explicitly authorized continuation beyond the prior two-correction limit. This supersedes the stop decision above for this task only; it does not relax any other repository safeguards.

- Added a RED summarizer regression requiring distinct sanitized `CANONICAL_TITLE_CREATE` and `CANONICAL_TITLE_PERSIST` setup events. It initially failed because the sanitizer correctly rejected the new, not-yet-allowlisted phases.
- The instrumentation now reports object construction and SQLDelight persistence independently, without changing repository/runtime code or emitting the title value. If CI reproduces the same NPE, the artifact will identify whether it is in object construction or repository persistence. This is instrumentation only; no runtime root cause has yet been proved.
- Local checks after the split: extension-fixture tests 6/6, instrumentation-verifier tests 14/14, summary-sanitizer tests 16/16, runner shell syntax, pinned APK SHA verification, and `git diff --check` all pass. Kotlin/Android compilation and the live journey remain CI-only under `CI_FIRST`.
- Next: commit/push the instrumentation + sanitizer + regression, then inspect the real emulator artifact for the first failing setup phase. Do not infer a SQLDelight defect unless the persistence phase fails.

### Result of isolated title setup phases

- Commit `d1c600b23e51eb60440bfecc29e28de834eb98bc` was pushed. CI v2.1 `36007378468` passed Change Planner, Format, App — Tsuzuki and CI Gate. MangaFire workflow `36007378190` passed fixture integrity and instrumentation compilation, but its emulator test failed; sanitized artifact `10811168560` was inspected.
- The real run now records `CANONICAL_TITLE_CREATE=PASS` and `CANONICAL_TITLE_PERSIST=FAIL|exception=NullPointerException`, after composition succeeds. This confirms construction is not throwing and localizes the exception to the persistence call path. It does not yet distinguish repository wrapper, generated SQLDelight query, driver, or listener notification. The run still emitted no `LIVE_SEARCH`; there was no MangaFire search request. No production defect is established.
- Added a RED/GREEN sanitizer regression for a closed stack-frame category field. Before support, the valid `SQLDELIGHT_QUERY` frame was dropped; after adding strict frame categories and reporting only allowlisted frames (`TITLE_REPOSITORY_INSERT`, `SQLDELIGHT_QUERY`, `SQLDELIGHT_RUNTIME`, `ANDROIDX_SQLITE_DRIVER`), the sanitizer and verifier tests pass (17/17 and 14/14). Unknown frame names and trailing token fields remain rejected.
- The next instrumentation commit records the first allowlisted stack frame for this persistence exception only. That should distinguish repository wrapper versus generated query/driver without exporting exception messages or arbitrary stack traces. Then rerun the single real E2E; do not alter repository/runtime code until that evidence identifies a cause.

### Sanitized persistence frame and next narrowing step

- Commit `805e34bdabc50bca336b64bc432b0ee1e22a5949` was pushed. CI v2.1 `36009307907` passed Change Planner, Format, App — Tsuzuki, and CI Gate; MangaFire workflow `36009307876` passed fixture integrity and instrumentation compilation but failed in the emulator. Artifact `10812640993` confirms `CANONICAL_TITLE_CREATE=PASS`, `CANONICAL_TITLE_PERSIST=FAIL|exception=NullPointerException|frame=SQLDELIGHT_RUNTIME`. Source registration still passes; live search is not run.
- The allowlisted first stack frame is in SQLDelight runtime code. This rules out object construction and confirms the exception is observed within SQLDelight runtime while persisting; caller frames in the repository/generated query may still be present below it. The category is deliberately coarse and does not yet distinguish query notification from other SQLDelight runtime paths. The SQLDelight/driver remains a boundary location, not a proven product defect.
- The next bounded instrumentation improvement recognizes `BaseTransacterImpl.notifyQueries` as a separate safe category (`SQLDELIGHT_NOTIFY_QUERIES`) while retaining strict allowlisting. That distinguishes a notification failure from the remaining SQLDelight execution path. No production code is changed until the exact boundary and cause are supported by evidence.

### Driver boundary observed; finer categories added

- E2E run `36013228599` (artifact `10813773439`) reports `CANONICAL_TITLE_CREATE=PASS` and `CANONICAL_TITLE_PERSIST=FAIL|exception=NullPointerException|frame=ANDROIDX_SQLITE_DRIVER`. Extension fixture and instrumentation compile passed, but this is still a real emulator failure; no live MangaFire search occurred. Fast CI `36013228351` passed.
- This narrows the first recognized exception frame from SQLDelight runtime to the AndroidX SQLite driver boundary. It does not identify whether the throw came from the `com.eygraber` AndroidX adapter, bundled SQLite driver, or AndroidX SQLite core; no causal defect is established yet.
- Added closed frame categories for those driver layers and a sanitizer regression. The next E2E will discriminate the adapter/library boundary while still omitting frame text, stack traces, query arguments, and messages. Then determine whether a test-composition mismatch is responsible before considering any product code correction.

### Adapter boundary observed; class-level categories added

- Real E2E `36015028263` / artifact `10814951131` reports `CANONICAL_TITLE_CREATE=PASS`, `CANONICAL_TITLE_PERSIST=FAIL|NullPointerException|EYGRABER_ANDROIDX_DRIVER`; extension fixture verification and instrumentation compilation passed. CI v2.1 `36015028271` passed. The adapter class causing the exception is not yet distinguished, and the journey still stops before MangaFire search.
- Added class-family allowlists for the Eygraber AndroidX SQLite schema holder, connection pool, executing driver, prepared statement, and query. The RED sanitizer test confirms a new enum remains dropped until explicitly allowed; all local script checks and pinned APK integrity remain green. These are diagnostics only and do not change driver behavior.
- The next live run should identify the first adapter component. If it is the schema holder, compare schema initialization of the instrumentation database against the production driver setup. If it is execution/statement handling, inspect the controlled query boundary. Do not select a code fix until the emitted category supports one.

### Eygraber frame family still needs decomposition

- Run `36016772280`, artifact `10814943685`, passed extension fixture verification and instrumentation compilation, then failed at `CANONICAL_TITLE_PERSIST` with `NullPointerException|frame=EYGRABER_ANDROIDX_DRIVER`. Source registration and isolated database setup passed; `LIVE_SEARCH` is still `NOT_RUN`. CI v2.1 `36016772505` passed.
- The broad fallback means the throw's first frame was in `com.eygraber.sqldelight.androidx.driver`, but was not one of the initially listed holder, pool, executing-driver, prepared-statement, or query classes. The cached 0.2.2 driver artifact lists additional classes such as `AndroidxSqliteConfigurableDriver`, `AndroidxSqliteConnectionFactory`, `AndroidxSqliteDriver`, `AndroidxStatement`, and `AndroidxSqliteUtils`; this is source inspection, not proof which one threw.
- Added closed categories for those remaining driver class families and a sanitizer regression. The next CI will identify the first mapped driver component. Continue to treat the exception as a test/runtime-boundary observation, not a cause until the matching code path and regression establish it.

## Focused instrumentation-context persistence diagnostic (2026-09-24)

- Rechecked branch identity before editing: local and remote `tsuzuki/runtime-e2e-tests` both pointed at `0f424d1c3bee3f6fd7bda4644c3e27d75ac833eb`. The existing untracked `.github/scripts/__pycache__/` was preserved.
- Retrieved workflow-job evidence for MangaFire run `36019029700`: immutable fixture verification and Android instrumentation compilation succeeded; the emulator job failed. Its sanitized output records `SOURCE_REGISTRATION=PASS`, setup through `CANONICAL_TITLE_CREATE=PASS`, then `CANONICAL_TITLE_PERSIST=FAIL|exception=NullPointerException|frame=EYGRABER_ANDROIDX_DRIVER`; all later journey stages, including `LIVE_SEARCH`, are `NOT_RUN`. No MangaFire request was made by that run.
- CI v2.1 run `36019029788` had `App — Tsuzuki=success` and `Format=failure`; `:app:spotlessKotlinCheck` identifies `MangaFireRealReadingJourneyInstrumentedTest.kt`. The driver-family `startsWith` lines over the configured 120-character limit were wrapped without changing their categories.
- Added a separate opt-in-workflow, offline Android test that observes only whether `Instrumentation.context.applicationContext` is present and whether the test package differs from the target package, then uses the production `AppBindings` driver and `CanonicalTitleRepositoryImpl` to insert/read a disposable canonical title. It does not search MangaFire or touch the target-app database. Its first version intentionally exercises the raw instrumentation context; if the earlier exception reproduces, it reports a sanitized setup phase/type/frame and fails rather than advancing.
- The summary/runner validators now require exactly one safe context observation and exactly one successful isolated persistence event before declaring this focused test green. The E2E verifier remains strict about all 14 ordered stages.
- Local RED/GREEN for the Python diagnostic contract: `test_android_instrumentation.py` initially failed because the focused method was not allowlisted; `test_summarize_android_instrumentation.py` initially failed because context observations were not summarized. After adding the closed contracts, the local script suites pass. The Android diagnostic has not yet run on an emulator; no context-null cause or correction is claimed.
- Next: push one `[android-fixture]` run so the emulator executes the focused persistence diagnostic without a provider request. Only if that run directly observes a null application context and persistence failure will the test composition use a wrapper whose `applicationContext` is itself while retaining the test package's database path. If the application context is non-null or one focused correction does not make persistence pass, stop and report the new evidence; do not modify production runtime or guess at the adapter cause.

### Focused context probe reproduced and test-only correction prepared

- The `[android-fixture]` run `36024805329` passed fixture integrity and instrumentation compilation, then the emulator's focused offline probe failed before any MangaFire search. Sanitized observation: `applicationContext=null|targetIsolation=isolated|databaseContext=raw`; the following isolated production-repository write ended in `TEST_CANONICAL_TITLE_PERSIST=FAIL|exception=NullPointerException|frame=EYGRABER_ANDROIDX_DRIVER`. This run-bound observation confirms the suspected context state and reproduces the persistence failure using only the instrumentation package database. It does not alone prove null context is the cause.
- Fast CI `36024805143` passed Change Planner, App — Tsuzuki, Format, and CI Gate for the probe/sanitizer changes.
- Prepared a single test-harness-only correction: when `Instrumentation.context.applicationContext` is null, pass a delegating `ContextWrapper` whose `applicationContext` returns itself to the unchanged production `AppBindings` SQLite driver and repository. Package identity and database operations continue to delegate to the instrumentation context; the explicit target-package isolation assertion remains before any database reset. When the Android context already has an application context, the raw instrumentation context remains in use.
- The focused regression still inserts and reloads a disposable canonical title via `CanonicalTitleRepositoryImpl`; no provider result, matching result, or runtime behavior is substituted. The 14-stage E2E verifier is unchanged and strict. The wrapper correction is pending fresh Android CI evidence; causal confirmation requires this isolated persistence probe to pass. Do not run the live MangaFire request until the focused probe and Fast CI are green.

### Wrapper did not complete isolated persistence; stop for SQLDelight investigation

- Commit `35ddcc4d1376569c3950fbadcae2eac36722c6e4` is pushed on `tsuzuki/runtime-e2e-tests`. Fast CI v2.1 `36026560270` passed Change Planner, App — Tsuzuki, Format, and CI Gate.
- MangaFire workflow `36026560254` passed the immutable fixture and instrumentation compile/JUnit-signature jobs. The isolated emulator job failed in the focused, offline persistence test; its run-bound output is `contextApplicationContext=null|targetIsolation=isolated|databaseContext=wrapped` followed by `TEST_CANONICAL_TITLE_PERSIST=FAIL|exception=OTHER|frame=ANDROIDX_BUNDLED_DRIVER` and the sanitized exception type `android.database.SQLException`. The wrapper's non-null application-context assertion and test-package isolation assertion passed before the database write failed.
- This directly confirms that the raw instrumentation context reports a null `applicationContext`, and the test-only wrapper changes the observed failure from the prior `NullPointerException`/Eygraber adapter frame to a database `SQLException`/AndroidX bundled driver frame. It does **not** prove that null application context caused the earlier failure; persistence is still not successful. No production code was changed.
- No MangaFire search was made: the workflow's focused persistence test failed and exited before the optional live method. Therefore all 14 live journey stages remain untested in this execution, and no MangaFire runtime compatibility claim follows from it.
- Per the bounded task contract, stop after this one unsuccessful test-composition correction. The next investigation belongs to the SQLDelight/Android database boundary: establish why this production `AppBindings` driver cannot persist in the isolated instrumentation package before making any further test-harness change. Do not retry unchanged code, run live search, or infer a product runtime defect from the current exception category.
- No local Gradle command was run. Lightweight tests and checks remained green before push: extension-fixture 6/6, instrumentation verifier 19/19, sanitizer 18/18, shell syntax, pinned fixture SHA-256, and `git diff --check`.
