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

- [ ] RED tests for installed/enabled Add-ons, existing binding status, preferred-language first pass, explicit “search more”, progressive candidates/errors, explicit ambiguous-edition confirmation, and stale/cancelled search not overwriting a newer state.
- [ ] Consume Task 2's resolver contract. Do not search all device Add-ons or duplicate title scoring in the ViewModel. Retain one visible Add-on per extension and show internal source/language only as candidate provenance.
- [x] GREEN in App Tsuzuki + Format Fast CI run `36080352470` after RED run `36079886917` exposed missing route/parser and one-shot flow helpers. App, Format, Change Planner, and CI Gate passed for commit `0f9b810ff`; no local Gradle was run. APK Build and unrelated jobs were skipped.

## Task 4 — Chapter-selector discovery path

**Domain:** UI. **Files:** `app/src/main/java/eu/kanade/presentation/tsuzuki/content/ContentOptionSelectorSheet.kt`, `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreen.kt`, `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`, relevant screen-model/UI tests.

- [x] RED tests: no actual option shows “find/add reading source”; failed providers remain distinct; selecting this action opens the existing binding flow and refreshes options only after valid binding. Do not add search candidates directly to selector options. App Tsuzuki run `36072373031` failed at the expected unresolved discovery-state helper after imports were corrected.
- [x] Implement minimal navigation/interaction. On title detail, the action opens the existing binding sheet for the current canonical title; successful binding events refresh title and active chapter options. From Reader, an explicit component-targeted `MainActivity` action carries only the nonblank canonical title ID, clears the Reader route to the existing main activity, and opens the same title with its binding sheet requested. The action has no manifest filter/deep link, malformed IDs are a no-op, and the sheet request is consumed once. Reader content/fallback/preference behavior is unchanged.
- [ ] GREEN in App Tsuzuki + Format Fast CI; review and commit/push.

## Task 5 — Generic contract/E2E validation and checkpoint

**Domain:** Test infrastructure. **Files:** `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/LocalMihonSourceHarness.kt`, focused contract tests, `.github/scripts` routing tests if needed, `docs/research/` handoff.

- [ ] Reuse controlled HTTP/fake Mihon source harness for two independent synthetic profiles (single-source and multi-source/multi-language). Assert search → explicit/safe binding → inventory → reconciliation → selector option → Reader preparation on the same canonical title/store. Cover empty/partial inventory, disabled/removal, failure isolation, identity mismatch, fallback on/off, and unchanged progress/history.
- [ ] Keep the existing MangaFire real E2E as a separate opt-in regression. Do not run the 222-source matrix. If a second real extension is exercised, use a single explicitly authorized source/title probe and classify the evidence separately from deterministic tests.
- [ ] Check CI change-planner routing for adapter/binding/selector/test paths. Run selected Fast CI jobs; request one `[ci-full]` checkpoint only if the final code actually spans domain + adapter + UI. Review final diff, document proven vs unproven behavior, commit/push on `tsuzuki/generic-addon-compatibility` only. No merge/PR/APK.

## Stop conditions

Stop a slice for an untrusted/private source, impossible safe identity match, repeated CI failure beyond the task's two focused corrections, or a needed second architecture domain not covered by that slice. Document `CROSS_DOMAIN_REQUEST` or the blocker and continue only with an independent approved slice; never weaken confidence gates or fabricate a GREEN.
