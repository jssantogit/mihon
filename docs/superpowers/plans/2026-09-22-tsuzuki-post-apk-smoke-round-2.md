# Tsuzuki — Post-APK Smoke Round 2

Date: 2026-09-22
Baseline: `tsuzuki/bootstrap` @ `caa0f6421d625e024b307ff3af8e17c936e5c3e1`
Work branch: `tsuzuki/post-apk-smoke-round-2`
Authoritative architecture: `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`

## Human smoke observations (APK run 35720621951)

1. Dandadan EN content appears cropped; PT-BR displayed in the same reading session.
2. Changing reading source no longer asks whether to make it preferred.
3. Switching Dandadan source sometimes yields a black Reader with no chapter pages.
4. One Punch-Man chapter list starts at 138 (provisional), with no earlier chapters.
5. Death Note shows Kitsu chapter count 108 but no real chapter structure.
6. Hunter x Hunter is ordered 0.5 before 0.
7. Initial chapter discovery still slow, including Nanatsu no Taizai.
8. Reader gives no persistent indication of the active reading content source/language.

## Invariants for this round

- Canonical title/chapter identities survive Add-on changes. Never merge by title alone.
- Kitsu/MAL counts are metadata, not permission to synthesize chapters.
- An Add-on can provide several internal Mihon Sources; per-title preferred content is Add-on scoped,
  while language ranking is global.
- Do not silently switch to another Add-on unless the global automatic fallback setting is on.
- A failed source switch must preserve the working Reader session and canonical reading progress.
- No APK until impacted tests, full final CI and explicit handoff.

## Work packages

### P0 — Reader source-switch integrity (smoke 2, 3, 8)

Affected files: `ReaderViewModel.kt`, `ReaderActivity.kt`,
`ContentSelectorScreenModel.kt`, `ContentOptionSelectorSheet.kt` and relevant Reader tests.

- Establish a safe switch transaction: stage/validate the new operational chapter and nonempty
  pages before replacing active Reader identity, loader, chapter and viewer state.
- Cancel or serialize overlapping selections. On preparation/load failure, retain previous
  session/pages; show visible recoverable error and retry or source selector. A successful
  switch must not retain stale source page index, cached viewer pages or navigation state.
- Persist or visibly present the active Add-on, language, group and current option while reading.
  Show the same selection clearly in the selector, not just the Add-on-scoped Preferred badge.
- Test switching EN -> PT-BR -> EN while pages are loaded, request failure, empty page list,
  concurrent taps, source change while loading and exit/reentry. Preserve prior progress.
- For a *different Add-on*, show a preference confirmation before changing the persisted title
  preference. Changing internal languages of the same Add-on cannot change that Add-on-scoped
  preference; offer a separate, explicitly global language-priority action if desired.
  Do not silently persist the first choice without showing the relevant preference UX.
- The screenshot's EN/PT-BR MangaFire entries are the same installed Add-on. Do not introduce a
  per-title Mihon-Source preference that conflicts with the approved runtime spec.

### P0/P1 — Dandadan EN page integrity (smoke 1)

- Reproduce with the same selected MangaFire EN chapter and capture raw page inventory: URL,
  MIME, dimensions, page count and the original retrieved image bytes versus Reader-rendered page.
- Compare global crop-border flag, image fit, reading mode and single-page versus webtoon mode.
- If the source image itself is partial, report provider-origin failure and surface another
  *installed* Add-on option or manual source selection; do not disguise it with scaling.
- If raw image is intact, add viewer and page-cache regression against source switching.
- Do not infer the cause from screenshots alone.

### P1 — Chapter coverage and fallback (smoke 4, 5)

- Distinguish known chapter *count*, known chapter *identities*, and downloadable content
  *availability*. Display coverage (e.g. first known chapter 138, earlier chapter unknown/missing)
  with explicit source provenance rather than presenting 138 as the first chapter of the work.
- During initial title resolution, probe user-enabled Add-ons in bounded concurrency, reuse
  existing verified ContentBindings, combine actual chapter evidence and compare coverage.
  For missing chapters resolve alternate Add-ons on-demand, honoring global automatic-fallback
  and preferred Add-on policy. Offer manual binding confirmation for ambiguous titles.
- Kitsu 108 / Nanatsu 346 are chapter-count metadata only. Test that a title without
  per-chapter evidence shows no invented chapters and gives an actionable Add-on/binding flow.
- Test One Punch-Man partial 138+ coverage and Death Note with no successful binding;
  distinguish no results, no enabled Add-on, ambiguous match and provider network failures.

### P1 — Numeric chapter ordering (smoke 6)

Confirmed cause: `CanonicalChapterIdentity.compareTo` and `sortKey` treated null `part`
as *after* all present parts, making 0.5 sort before regular 0. Sort whole numbered chapters
before fractional chapters; ensure `compareTo` and `sortKey` agree, and add regression for
0, 0.5, 1, 1.5. SQLDelight stores structured components (not sort key), and repository sorts
from these at read time, so no migration is needed for existing installed data.
Initial branch commits: `e6ba28ab42608d773632255516eb783c27d159d2` and
`4f78f3a30fb4802a8c49af88e8d23d4359eba077`.

### P1 — Initial resolution latency (smoke 7)

Observed code path: `RefreshChapterEvidence` resolves bindings, then probes all Add-ons.
`MihonChapterProbeProvider` now parallelizes internal inventory fetches (max 4), but
`MihonChapterInventoryGateway.fetch` still calls the source's network `getMangaUpdate`.
`MihonContentProvider.resolve` can fetch the same inventories again when opening a chapter.
`RefreshChapterEvidence` also clears the content option cache on every refresh.

- Instrument search/binding/inventory/reconciliation/option-resolution/page-fetch spans.
- Reuse a bounded-TTL inventory snapshot shared by probe and content provider; deduplicate
  in-flight fetches, retain cancellation and avoid global cache invalidation on unrelated titles.
- Render cached chapter rows immediately; refresh in background with visible progress and
  distinct retry/error state. Respect explicit refresh as a forced revalidation.
- Do not promote a stale cached option to executable content without validating live identity.
- Recheck cold/warm load for Dandadan and Nanatsu, and verify latest navigation cancels stale work.

## CI and release protocol

- Each targeted implementation commit uses affected CI only; never stamp microfixes `[ci-full]`.
- Preserve regression fixtures for all reported titles where reproducible.
- A single full CI on consolidated Round 2 HEAD before fast-forward or merge to bootstrap.
- A separate bootstrap verification; APK built only after green and user agreement.
- Human smoke is required for EN image integrity, source switching, One Punch-Man coverage,
  Death Note add-on matching, and first-load performance.
