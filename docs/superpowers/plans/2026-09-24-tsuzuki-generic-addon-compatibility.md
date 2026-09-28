# Generic Mihon Add-on Compatibility Implementation Plan

> **For agentic workers:** implement one bounded task at a time with RED → GREEN evidence. The project is `CI_FIRST`: the named Gradle commands below run in GitHub Actions, not locally (`localHeavyAttempts=0`).

**Goal:** Make installed and enabled Mihon extensions participate safely in Change Source, canonical binding, chapter alternatives, and Reader delivery without site-specific code.

**Architecture:** Reuse Mihon's repository/extension/runtime stack and Tsuzuki's existing `AddonRepository`, `ResolveContentBinding`, `ContentBindingRepository`, chapter/content resolver, and Reader preparation. Add only the missing search-progress and UI affordances. Keep catalog metadata separate from executable runtime eligibility.

**Tech stack:** Kotlin, coroutines/Flow, Metro, Compose, SQLDelight, Mihon `CatalogueSource`, MockWebServer, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-09-24-tsuzuki-generic-addon-compatibility-design.md`; authoritative parent: `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`.

## Global constraints

- `CanonicalTitle != Source Manga`, `CanonicalChapter != Source Chapter`, and metadata/index entries do not establish reading availability.
- Only loaded, trusted, installed, enabled internal `CatalogueSource`s may be searched. One extension package remains one visible Add-on.
- No silent title merge, preference change, extension installation, source enablement, or CAPTCHA bypass.
- Preserve cancellation, canonical progress/history, and current fallback policy. Provider calls remain bounded and external tests opt-in.
- No local Gradle. Each product slice requires Fast CI v2.1 success before the next slice. Two focused correction attempts per slice; on repeated failure, stop and report.

## Review focus

1. Mixed enabled/disabled sources in a multi-source extension must never search the disabled one.
2. A slow/failed source cannot erase completed candidate results from another source; error is not empty.
3. Similar editions across languages/sources must not be silently bound to one canonical title.
4. An empty selector must guide discovery without treating search candidates as readable chapters.
5. Switching providers must preserve canonical progress and only save a preference after successful Reader preparation.

## Task 1 — Confirm discovery and repository contracts

**Domain:** Add-on runtime adapter. **Files:** `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/addon/MihonAddonRepositoryTest.kt`, `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGatewayTest.kt`, relevant extension-store tests; production adapter only if a failing test proves a defect.

- [x] Add/confirm tests for a trusted multi-source extension (one visible Add-on), mixed enabled/disabled IDs, no loaded runtime extension, uninstall/update state, and non-catalogue search exclusion. Assert search targets derive from runtime, never from `index.pb` alone.
- [x] Baseline GREEN: the focused contracts matched existing behavior; no adapter defect was demonstrated, so no production change was made. `ExtensionManager` supplies `Extension.Installed` only for successful trusted loads; `MihonAddonRepository` consumes only that runtime list; `MihonReadingSourceGateway` uses loaded `CatalogueSource`s and excludes disabled IDs. There is no extension-store test suite in the repository, and the Add-on adapter has no catalog-index dependency.
- [x] Fast CI v2.1 run `36059811270` passed the Change Planner, App — Tsuzuki, Domain — Tsuzuki, and CI Gate jobs. Format was not selected by the planner for these test-only paths; `git diff --check` passed before commit. No local Gradle was run. Test slice committed/pushed as `fa6b952a3cb0e9bb8b033d05d2c6bd96f456c41d`.

## Task 2 — Bounded, progressive search and safe binding

**Domain:** Content binding. **Files:** `domain/src/main/java/tachiyomi/domain/tsuzuki/content/interactor/ResolveContentBinding.kt`, `domain/src/test/java/tachiyomi/domain/tsuzuki/content/ResolveContentBindingTest.kt`, and only necessary small domain search-progress types.

- [x] Write tests first: preferred-language/source ordering, initial batch cap, explicit broadening, no repeated queried IDs, per-source completion/failure, timeout, coroutine cancellation before search and persistence, ambiguous editions, reuse of a persisted binding, idempotent upsert, and a healthy result when a peer fails. No test uses equal titles across distinct candidates as canonical proof. RED confirmed in CI run `36062310406` on test-only commit `10fa30197`; Domain — Tsuzuki failed because the new API/types were not implemented, while App — Tsuzuki passed.
- [x] Add a cold `Flow` of closed per-source progress events to the existing resolver, requested with ordered preferred languages, an initial/broaden mode, IDs already queried, and a small batch limit. Intersect eligible IDs with the selected Add-on's installed/enabled `mihonSourceIds`; use `ReadingSourceGateway.listInstalled(language)` only to order preferred IDs. Without preferences, use a small first batch. Explicit broadening searches only remaining enabled IDs. Do not promote the diagnostic-only `AddonSourceEligibilityRepository` to product authority or parse opaque `providerTitleKey` strings to infer source IDs.
- [x] Extract and reuse existing scoring, confidence, ambiguity, materialization, and persistence rules. Keep `execute`/`executeAll` behavior for existing callers, covered by regression. Emit binding reuse, safe bound result, confirmation candidates, empty/no-match, closed classified failure, and completion as distinct events. Retain concurrency capped at four and check cancellation before persistence. The default cooperative source deadline is 25 seconds; it cannot guarantee interruption of blocking Java extension work, which remains an explicit limitation. Errors are never represented as empty results.
- [x] GREEN in Domain and App Tsuzuki Fast CI jobs; implementation commit `3e1380f2e` passed run `36063807145`, the timeout/cancellation regressions in `35d89c782` passed run `36064602980`, and the final IO-context isolation in `55f304c78` passed run `36065487318`. These runs selected Change Planner, Domain — Tsuzuki, App — Tsuzuki, and CI Gate. Format was skipped by affected-mode routing for these runtime paths; `git diff --check` passed. No local Gradle command was run. Commits were pushed before Task 3 UI changes.

## Task 3 — Change Source integration

**Domain:** UI. **Files:** `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/content/ContentBindingLinkScreenModel.kt`, `app/src/main/java/eu/kanade/presentation/tsuzuki/content/ContentBindingLinkSheet.kt`, their tests; title-detail wiring only if required.

- [x] RED screen-model tests for installed/enabled Add-ons, existing binding status, preferred-language first pass, explicit “search more”, progressive candidates/errors, confirmation, and stale/cancelled search. The test-only commit `b2632cff3` failed App CI `36068922232` before implementation.
- [x] Consume Task 2's resolver contract without duplicate scoring or all-device search. One package remains one visible Add-on; internal source/language stays candidate provenance. UI implementation `2544fcbdb` and suspend-test correction `60ff51cfb` passed App and Domain in `36070093569`.
- [x] Full CI `36075917262` passed App, Domain, Format, release compilation, and CI Gate after scoped formatter corrections. No local Gradle was run.

## Task 4 — Chapter-selector discovery path

**Domain:** UI. **Files:** `app/src/main/java/eu/kanade/presentation/tsuzuki/content/ContentOptionSelectorSheet.kt`, `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreen.kt`, `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`, relevant screen-model/UI tests.

- [x] RED tests: no actual option shows “find/add reading source”; failed providers remain distinct; selecting this action opens the existing binding flow and refreshes options only after valid binding. Do not add search candidates directly to selector options. App Tsuzuki run `36072373031` failed at the expected unresolved discovery-state helper after imports were corrected.
- [x] Implement minimal navigation/interaction. On title detail, the action opens the existing binding sheet for the current canonical title; successful binding events refresh title and active chapter options. From Reader, an explicit component-targeted `MainActivity` action carries only the nonblank canonical title ID, clears the Reader route to the existing main activity, and opens the same title with its binding sheet requested. The action has no manifest filter/deep link, malformed IDs are a no-op, and the sheet request is consumed once. Reader content/fallback/preference behavior is unchanged.
- [x] **Approved navigation correction (2026-09-25):** the prior Reader-route clearing requirement above is superseded. Reader-initiated discovery is temporary: after dismissing the existing binding sheet, one Back resumes the same canonical chapter and reading position when preserved. Verify both cold and warm Activity stacks (`ReaderActivity` is `singleTask`, `MainActivity` is `singleTop`), avoid changing normal library-origin navigation, and do not mutate progress or preference merely by searching/binding. The existing binding sheet and screen model are reused directly in Reader; normal library-origin title navigation is unchanged. Emulator run `36124854455` at code HEAD `6ce320052` passed the cold, warm, and invalid-intent cases with exactly one JUnit test each, original Reader/task continuity, canonical chapter restoration after recreation, no reopened sheet, and unchanged disposable-fixture progress/preferences. The fixture had no loaded page, so page-position preservation was **not observable**. CI v2.1 run `36124853985` passed App, Format, and CI Gate; other jobs were skipped by the planner. The earlier Android run `36123207314` failed because the test demanded sheet survival across recreation before exercising Back, a requirement not in the approved contract. No live MangaFire/MangaBall probe or local Gradle was run. Physical-phone UX acceptance remains pending.
- [x] GREEN in App Tsuzuki + Format Fast CI run `36080352470` after RED run `36079886917` exposed missing route/parser and one-shot flow helpers. App, Format, Change Planner, and CI Gate passed for commit `0f9b810ff`; no local Gradle was run. APK Build and unrelated jobs were skipped.

## Task 5 — Generic contract/E2E validation and checkpoint

**Domain:** Test infrastructure. **Files:** `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/LocalMihonSourceHarness.kt`, focused contract tests, `.github/scripts` routing tests if needed, `docs/research/` handoff.

- [x] Reuse controlled HTTP/fake Mihon source harness for single-source and four-language multi-source profiles. The new progressive journey on the same canonical state covers bounded search, ambiguous confirmation, partial failure, mapped evidence, actual selector options, Reader preparation, and `getPageList`; CI `36079178929` passed App, Format, and CI Gate. Existing focused tests cover empty/partial inventory, disabled/removal, identity mismatch, fallback on/off, and progress preservation separately. Those latter behaviors are **not** newly proven as one real-extension journey.
- [x] Keep the existing MangaFire real E2E separate and opt-in. It was not rerun for this branch; the earlier one-title/one-language run `36050812440` remains regression evidence only. No 222-source matrix or second live extension probe was run.
- [x] Check affected-mode routing (`python3 .github/scripts/test_ci_v2_plan.py`: 34 tests passed) and run the final full checkpoint because this diff spans domain and UI. Full CI `36080952197` passed Format, App/Domain/Data/Core tests, SQLDelight migrations, Supabase backend, release compilation, and CI Gate at code HEAD `efac6d86e`. Native Package Gate was skipped by policy; it is not claimed as passing. No merge, PR, external probe, or distribution APK.

## Stop conditions

Stop a slice for an untrusted/private source, impossible safe identity match, repeated CI failure beyond the task's two focused corrections, or a needed second architecture domain not covered by that slice. Document `CROSS_DOMAIN_REQUEST` or the blocker and continue only with an independent approved slice; never weaken confidence gates or fabricate a GREEN.
