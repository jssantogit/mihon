# Generic Mihon Add-on compatibility — integration handoff

Date: 2026-09-25. Branch: `tsuzuki/generic-addon-compatibility`, based on `tsuzuki/runtime-e2e-tests` at `6ae00eb4e3cb9348cbdcaacef189671d2f912738`.

## Decision and implemented path

The Keiyoushi `index.pb` is an extension **catalog**, not proof that a source can read a chapter on a device. The existing Mihon `ExtensionStoreRepository`/`ExtensionApi`, `ExtensionManager`, `ExtensionLoader`, and `SourceManager` retain catalog, installation, trust, and loaded-source authority. This branch adds no second extension manager or site-specific adapter. `MihonAddonRepository` exposes one installed Add-on per extension package and only its loaded, enabled internal `CatalogueSource` IDs. The relevant adapter tests now cover mixed enabled/disabled IDs, removal, missing loaded extension, and rejection of non-catalogue sources.

`ResolveContentBinding.searchProgress` adds a cold, bounded search to the existing canonical binding resolver. INITIAL searches up to three eligible internal sources, ordered by title/global language preference; BROADEN explicitly searches only unqueried eligible IDs. Concurrency is capped at four. Events distinguish prior binding count, safe bound result, confirmation candidates, genuinely empty/no-match, classified failure, and completion. Existing `execute`/`executeAll`, scoring, thresholds, `ConfirmContentBinding`, and persistence rules remain the binding authority. Prior bindings and candidates are **not** chapter options. No canonical title is merged by title similarity.

Change Source consumes these events progressively for one selected Add-on package. It retains good results when a peer source fails, requires explicit confirmation for ambiguous editions, and offers “Search more” rather than searching every installed source automatically. The chapter selector only displays actual `ResolveChapterContent` options. Empty/error states offer an explicit discovery action. Title detail opens the existing binding sheet; Reader uses a component-targeted internal MainActivity action carrying the same nonblank canonical title ID. There is no public deep-link filter. A new preference is not written by discovery or binding; existing Reader preparation/preference timing is unchanged.

The V2 chapter path maps `PersistedChapterEvidence` to `CanonicalChapter`. It need not create legacy `ChapterVariant` rows. `MihonContentProvider` accepts mapped evidence identities (and existing variants) when resolving actual chapter options; an unobserved chapter is not fabricated.

## Verification

| Evidence | Result and limit |
| --- | --- |
| [Full CI 36080952197](https://github.com/jssantogit/mihon/actions/runs/36080952197) at code HEAD `efac6d86e` | **PASS**: Change Planner, Format, Core/Data/Domain/App tests, SQLDelight migrations, Supabase backend, Release Compile, CI Gate. Native Package Gate was skipped; no APK was published. |
| [App/Format CI 36079178929](https://github.com/jssantogit/mihon/actions/runs/36079178929) | **PASS**: deterministic progressive binding → explicit confirmation → HTTP inventory → mapped canonical evidence → two real options → Reader preparation → fixture `getPageList`, on one canonical title/store. |
| [App/Format CI 36080352470](https://github.com/jssantogit/mihon/actions/runs/36080352470) | **PASS**: Reader discovery route, invalid ID no-op, one-shot binding-sheet launch, plus affected App suite. Actual cross-activity navigation was not instrumented on Android. |
| [Earlier real MangaFire E2E 36050812440](https://github.com/jssantogit/mihon/actions/runs/36050812440) | Prior-branch, opt-in, one title/one English source, 14 stages passed. **Not rerun** for this branch; not universal-provider proof. |
| `python3 .github/scripts/test_ci_v2_plan.py` | 34 routing tests passed. Affected-mode checks must not be confused with skipped jobs. |

The deterministic tests use a synthetic Mihon `HttpSource` and MockWebServer; they do not query external providers. Existing domain and adapter tests separately cover disabled/removal, empty/partial inventory, identity mismatch, fallback enabled/disabled, and canonical progress. The new shared-state journey covers multiple internal sources and languages, ambiguity, HTTP 503 isolation, and only observed chapter options. Neither suite proves that every extension APK or website works.

## Known limits and review points

1. Third-party `CatalogueSource` may execute blocking Java/HTTP code. The 25-second coroutine timeout is cooperative, not a hard kill; four hung calls can occupy the four permits. Per-source progress prevents an ordinary slow/failing peer from suppressing completed results, but cannot guarantee responsiveness against four non-cooperative calls.
2. The Reader → MainActivity discovery transition is unit-tested and compiles, but still needs a focused Android smoke test for activity flags, process restoration, and reopening the correct title/sheet. No user data, external source, or provider was used for that test here.
3. No live matrix of 222 sources, no new physical-device test, no CAPTCHA/429 bypass, and no guarantee of site availability. The Keiyoushi index is for distribution metadata only.
4. Some temporary UI copy is English-only. Localization and a physical UX check are follow-up work, not grounds to weaken identity gates.
5. Existing binding counts are informational. A stale binding is never presented as a readable chapter solely because it is stored; chapter availability still requires compatible mapped evidence/inventory and `ContentDelivery` resolution.

## Integration review checklist

- Confirm the internal Reader navigation intent opens the expected canonical title and binding sheet once after activity recreation.
- Exercise one installed multi-source extension with one internal source disabled and verify Change Source never queries that ID.
- Confirm an ambiguous real edition requires user selection and that an unselected candidate does not persist.
- Open a chapter from an alternate verified source; confirm canonical progress and current per-title preference behavior remain unchanged until Reader preparation succeeds.
- Treat HTTP/network/CAPTCHA failures as provider/integration observations, not as empty chapter inventories.

No merge, PR, provider mass query, local Gradle, or distribution APK was performed for this branch.
