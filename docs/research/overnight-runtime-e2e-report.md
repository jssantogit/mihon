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

Focused local checks passed: CI planner tests (34, including RED/GREEN proving research CSV no longer selects full/release lanes), extension live report tests (7), existing Android instrumentation sanitizer tests (6), new live batch summary tests (7, including unknown-versus-empty, privacy, size bounds, rejected oversize input, and non-allowlisted/wrong-shard targets), fixture contracts (3), package detection tests (5), fixture integrity for all eight APKs, `bash -n` for the live runner, workflow YAML syntax parsing, and `git diff --check`. The affected-mode planner selected `App — Tsuzuki` plus Format; it did not select Domain, Database, Supabase, or Release. No Gradle command was run locally.

## Architectural constraints reviewed

The authoritative runtime design keeps canonical titles and chapters Tsuzuki-owned; source identities and source chapters remain evidence/variants. Mihon remains the operational reading mechanism. The compatibility matrix is an opt-in observation, never a routine CI requirement. It makes one serialized search request per source, with backoff for explicit CAPTCHA/429, and must not publish raw provider responses, URLs, cookies, headers, or exception messages.

The Android probe wraps a gateway search in coroutine `withTimeout`, but an extension may invoke Java/blocking operations. A coroutine timeout is not proof that such a call is interrupted. The current incomplete batches contain no stage/result event to establish whether they reached the extension lookup or a source call, so no blocking-call conclusion is drawn. The new retry will record lookup/source-entry/result stages without changing operational behavior.

## Next actions

1. All current shard artifacts have been preserved; do not rerun the full matrix.
2. The available logs do not reveal why JUnit did not pass; no provider/runtime/timeout/HTTP root cause is established. The focused diagnostic-observability change, safe stage events, and targeted-dispatch option are local only.
3. Validate the changes through Fast CI plus the workflow's contract/compile-only jobs; ensure this push does not launch provider searches. The planner regression confirms that documentation/CSV handoffs cannot force a full release build.
4. Manually dispatch only the three incomplete batches (`animexnovel`, `mangalivreto`, then `mangafire`) sequentially, review each sanitized artifact, and stop additional requests on CAPTCHA/429. These are at most nine internal-source attempts; do not rerun the full matrix.
5. Use the new stage events to identify the last confirmed boundary. Implement a runtime correction only if a reproducible runtime cause is demonstrated; otherwise fix instrumentation or document uncertainty.

## Current status

- 8/8 user-provided extension APK loading/registration remains prior evidence; 222/222 exposed sources are not yet evidence that every live search works.
- Current live run `35944402938`: shard 0 54/56 accepted; shard 1 56/56; shard 2 56/56; shard 3 47/54. Total 213 accepted source observations: 212 results, one HTTP 403; nine outcomes unknown.
- Root cause for AnimeXNovel, Manga Livre.to, and MangaFire: **unknown**. Their JUnit failures do not prove provider, runtime, timeout, network, HTTP, CAPTCHA, or extension causes.
- Mangas Brasuka HTTP 403 is a confirmed response category only, not evidence of CAPTCHA or Tsuzuki defect.
- Diagnostic instrumentation and targeted dispatch changes remain unpushed; Fast CI is pending.
- MangaFire/chapter inventory/fallback on these actual extensions are not proven by this search matrix.
