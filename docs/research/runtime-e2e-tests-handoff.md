# Runtime V2 deterministic E2E tests — handoff

## Executive status

Work is on `tsuzuki/runtime-e2e-tests`, based on `tsuzuki/runtime-integration-tests` at
`485cce6f4a26fa8f358b97c88cdd27ffce38c858`. The purpose is deterministic CI coverage of the
Runtime V2 path without asserting anything about MangaFire's live service.

The principal scenario uses a loopback `MockWebServer` and production Mihon/Tsuzuki adapters to
connect source discovery, HTTP title search, safe title binding, source manga materialization,
chapter inventory, evidence reconciliation, canonical chapter variants, and reader content options.
All state is shared within a test scenario. No reader fallback, preference, progress, or canonical
identity behavior is changed. One runtime adapter change structures chapter-inventory failures for
diagnostics while retaining the original throwable as its cause.

## Baseline gap and RED proof

The pre-change audit is in [`runtime-integration-test-audit.md`](runtime-integration-test-audit.md).
The earlier suite tested binding, inventory, reconciliation, and selector behavior in isolated
tests, but had no single local HTTP scenario joining those boundaries. It also had no real APK
extension test.

The first end-to-end test was committed before extending the fixture. GitHub Fast CI run
`35912920028` compiled and ran the app unit suite; it failed at the expected missing chapter
assertion (`canonicalChapters.single()` had an empty list). This was a valid RED signal: the
fixture supported title search but its `HttpSource.chapterListParse` was still the default
unsupported implementation, so there was no inventory to reconcile. The same run exposed a
format-only lint issue, fixed in a following test-only commit. Earlier runs `35911728189` and
`35912507597` stopped at test-scaffolding compile/import issues and are not counted as RED evidence.

## Harness and production boundaries

`LocalMihonSourceHarness` now supports one or more language-specific local HTTP servers and
`FixtureHttpSource` parses synthetic search and chapter-list responses. The scenario composes:

- production `MihonReadingSourceGateway` and `MihonChapterInventoryGateway`;
- production `ResolveContentBinding`, `DefaultAddonRegistry`, `MihonAddonProviderFactory`,
  `MihonChapterProbeProvider`, and `MihonContentProvider`;
- production `RefreshChapterEvidence`, `ReconcileChapterEvidence`, and
  `ResolveChapterContent`.

Fakes are limited to infrastructure and external boundaries: an installed add-on/source manager,
Mihon/database repositories backed by in-memory state, canonical persistence repositories, and
an empty metadata integration registry. The fixture HTTP responses never contact public providers
and contain no copyrighted reading pages.

The principal journey verifies that the discovered internal source ID is the ID searched and
materialized; the stored binding retains the same canonical title and Mihon manga ID; an HTTP
chapter becomes canonical evidence/variant state; and the selector returns a Mihon delivery for
that exact source/manga/chapter. A two-language scenario verifies two source options for one
canonical chapter without collapsing language provenance. It also validates that the in-memory
repository fixture stores multiple internal-source bindings for one add-on without overwriting
them; production persistence remains keyed by binding/source representation.

The current evidence-refresh path persists per-source `ChapterEvidence` mappings to a shared
canonical chapter and resolves delivery options from those exact mappings. It does not persist
`ChapterVariant` rows in `ReconcileChapterEvidence`; the separate inventory reconciliation
interactor has its own unit coverage. This E2E test does not invent variant rows or silently change
the production refresh contract.

## Regression scenarios added

The reusable local harness now includes tests for:

- one-source HTTP → binding → inventory → reconciliation → content-option journey;
- same chapter offered in English and Portuguese from separate internal sources;
- unsafe title similarity failing binding rather than silently selecting a manga;
- existing binding reuse without another title search;
- repeat inventory refresh preserving canonical chapter and binding identities;
- a successful, empty HTTP inventory producing an explicit `CHAPTER_INVENTORY / EMPTY` event and
  no reader option;
- malformed inventory remaining a parser error, not being misreported as empty;
- a failed English-source inventory not suppressing the valid Portuguese alternative;
- a source missing one requested chapter falling back to the matching language alternative, while
  a separately confirmed metadata-only chapter remains unavailable when no source lists it.

The failing HTTP/parser diagnostic path exposed a classification gap at chapter inventory: bare
HTTP exceptions and parser exceptions were recorded as generic extension failures despite having
a numeric HTTP status or a recognized JSON/serialization parser type. The Mihon chapter-inventory
diagnostic path now classifies these with the existing closed `ReadingSourceSearchFailure` type and
retains the original throwable as `cause` for classification. The gateway still returns the
original error to its caller; no result is converted to an empty inventory. This does not change
fallback or include exception text in diagnostic output. Timeout and cancellation propagation
remain unchanged.

Existing adjacent coverage in `MihonReadingSourceHttpIntegrationTest` also exercises HTTP 200 empty,
HTTP 403 without CAPTCHA misclassification, 429/503, malformed extension response, internal source
error, network/timeout, and cancellation at the Mihon search boundary. Gateway tests cover inventory
failure/cancellation; domain tests cover tied binding candidates and confirmation. These adjacent
tests remain distinct unit/integration cases; they are not claimed as part of the single full HTTP
journey.

## CI evidence

The integration suite is app coverage; CI Change Planner routes it to the App Tsuzuki shard. The
diagnostic adapter change is also routed to both Domain and App Tsuzuki shards. Release compilation
is not selected because no release boundary changed. The Change Planner and routing tests must pass;
skipped jobs are not represented as passing tests. No local Gradle command was run under the
project's CI-first policy.

<!-- Update this section with final GitHub run links and statuses before handoff. -->

## MangaFire 1.6.34 artifact investigation

The official [`keiyoushi/extensions-source`](https://github.com/keiyoushi/extensions-source) source
tree contains `src/all/mangafire/build.gradle.kts`, whose public source configuration sets
`versionCode = 34`, `libVersion = "1.6"`, and declares `en`, `es`, `es-419`, `fr`, `ja`, `pt`, and
`pt-BR` sources. The source at the relevant official commit can be inspected at
[`97d05b14`](https://github.com/keiyoushi/extensions-source/tree/97d05b14e0495cb1ab980fdf618ae0cf887765fe/src/all/mangafire).
That is evidence about source/version configuration, not evidence that an APK was installed or
that the live MangaFire service worked.

The current official `keiyoushi/extensions` repository index retrieved during this session did not
contain a MangaFire APK entry. Its repository branch currently has no published
`tachiyomi-all.mangafire-v1.6.34.apk` path/history through the public GitHub contents/commit APIs,
and an attempted immutable official raw path returned HTTP 404. A SourceForge project named
`keiyoushi-extensions.mirror` advertises a 1.6.34 APK and a SHA-256 value
`2e8230ea7d787ab3c4570c2c6447c9fa95f3ffa12a74f874fcc63297a068d16e`, but SourceForge itself states
that it is **not affiliated with Keiyoushi Extensions**. This mirror-provided checksum is therefore
not treated as an authorized, independently anchored artifact identity and is not sufficient for
installation/testing. No APK was downloaded into the repository or installed.

No `adb` executable, Android device/emulator, or exact APK file was available in this workspace.
The real-extension test lane remains unperformed and separate from Fast CI. Do not infer MangaFire
availability, failure type, or repair from these synthetic tests.

## Three evidence levels

1. **Deterministic synthetic source:** local loopback HTTP tests; evidence only for integration
   contracts and fixture behavior.
2. **Instrumented real extension APK:** not performed; exact authorized signed artifact and Android
   test environment are unavailable.
3. **Live provider connectivity:** not performed or authorized in this CI task; never a Fast CI gate.

## Remaining limitations / next MangaFire step

The test harness does not run the extension DEX or reproduce its approximately 20-second failure.
It does not test CAPTCHA solving, protected network behavior, territory rules, or the current live
site. The minimum next diagnostic step remains a fresh sanitized diagnostic-v2 report from the
user's device after enabling exactly one MangaFire internal source, ensuring all other MangaFire
languages are disabled, and running a single One-Punch Man refresh. Correlate its discovery,
eligibility, binding search/match/materialization, inventory/probe, and selector stages. Only if a
maintainer-authorized APK with an immutable digest and an isolated Android environment becomes
available should a separate real-extension load test be added. Do not bypass CAPTCHA or alter
fallback/preferences to force a green test.

## Final revision/checks

- `BASE_SHA`: `485cce6f4a26fa8f358b97c88cdd27ffce38c858`
- `HEAD_SHA`: pending final push
- Files: pending final status check
- Fast CI: pending final result
- No PR, merge, bootstrap change, or APK generation.
