# Tsuzuki MVP-V1 — Canonical Chapter Engine

**Status:** Implementation plan  
**Date:** 2026-09-19  
**Branch:** `tsuzuki/mvp-v1-canonical-chapters`  
**Baseline:** `tsuzuki/bootstrap@820cd8b7d15bf8fc5f8d7787abbc19fd16945de6`

## Goal

Implement the first Tsuzuki-owned Canonical Chapter Engine while keeping Mihon's chapter rows and source APIs operational and source-specific.

The governing invariant is:

> **A source chapter is not a canonical chapter.**

This milestone produces canonical chapter identity, source variants, conservative reconciliation, source coverage, gap detection, and per-chapter fallback candidates. It deliberately does **not** integrate the Mihon Reader or canonical progress yet; those belong to Milestone 7.

## Non-negotiable boundaries

- Do not use Mihon's `Chapter.chapterNumber: Double` as canonical identity.
- Do not destructively rewrite existing Mihon chapter rows.
- Do not make one source's chapter list canonical truth.
- Do not merge ambiguous/unparseable source chapters across sources automatically.
- Preserve raw source label, URL, scanlator, upload date, source order, and source-provided number hint.
- Catalog/provider IDs never become chapter IDs.
- Reader, history, read-state, bookmark-state, and tracker progress remain untouched in this milestone.
- A transient source failure must not delete previously reconciled canonical data.
- Local content participates through the same source-inventory boundary.

## Canonical representation

The initial domain model will use structured ordering rather than floating point:

```text
CanonicalChapter
|- id: Tsuzuki UUID
|- canonicalTitleId
|- displayNumber
|- sortKey
|- volume
|- title
|- type
|- baseNumber
|- part
|- alphaSuffix
|- confidence
|- createdAt
└- updatedAt

CanonicalChapterType
|- REGULAR
|- PROLOGUE
|- EPILOGUE
|- EXTRA
|- SPECIAL
|- ONESHOT
└- UNKNOWN
```

`displayNumber` remains presentation-oriented. Ordering/identity uses structured parsed components. `12`, `12.5`, `12a`, `Extra 3`, `Prologue`, and `24 Part 2` must not be collapsed into a single floating-point representation.

A decimal segment such as `37.2` is represented as base chapter `37` plus part `2`, while the original source label remains preserved on the variant.

```text
ChapterVariant
|- id: Tsuzuki UUID
|- canonicalChapterId
|- sourceMappingId
|- sourceId
|- mihonMangaId
|- mihonChapterId?
|- sourceChapterId
|- sourceChapterUrl
|- language
|- scanlationGroup
|- releaseDate
|- rawName
|- rawNumberHint
|- rawSourceOrder
|- createdAt
└- updatedAt
```

For Mihon sources, `sourceChapterId` is the stable source-local key exposed through the adapter, initially derived from the source URL. `mihonChapterId` is nullable because canonical inventory discovery must not require eagerly inserting chapters into Mihon's database.

## Conservative parser rules

Create a deterministic parser owned by Tsuzuki, not by Mihon's `ChapterRecognition`.

Required fixtures include:

- `Chapter 12` -> REGULAR / 12
- `Ch. 012` -> REGULAR / 12
- `12.5` -> REGULAR / 12 / part 5
- `12a` -> REGULAR / 12 / suffix a
- `24 Part 2` -> REGULAR / 24 / part 2
- `Extra 3` -> EXTRA / 3
- `Special 2` -> SPECIAL / 2
- `Prologue` -> PROLOGUE
- `Epilogue` -> EPILOGUE
- `One-shot` -> ONESHOT
- common PT-BR equivalents such as `Prólogo`, `Epílogo`, `Especial`, `Capítulo 12`
- unknown/unparseable text -> UNKNOWN without fabricated numbering

The source's explicit numeric hint may support parsing when the raw label is ambiguous, but may not erase raw semantics or turn an UNKNOWN label into a destructive cross-source merge without sufficient evidence.

## Reconciliation policy

Automatic cross-source grouping is allowed only when variants have a sufficiently specific compatible parsed identity:

```text
same canonicalTitle
+ same chapter type
+ same base number when applicable
+ same part when applicable
+ same alpha suffix when applicable
```

Examples:

- `Ch 12` and `Chapter 12` may reconcile.
- `12` and `12a` must not reconcile.
- REGULAR 3 and EXTRA 3 must not reconcile.
- two UNKNOWN labels from different sources must not reconcile merely because their titles look similar.
- the same `(sourceId, sourceChapterId)` must remain idempotently attached to the same variant across refreshes.

When no safe existing canonical chapter matches, create a new canonical chapter with a Tsuzuki UUID.

Do not delete canonical chapters merely because a refresh no longer returns a variant. Marking stale/unavailable variants is a later refinement unless concrete evidence requires it.

## Task 1 — Domain models and parser

**Create:**
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/CanonicalChapterType.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/CanonicalChapterIdentity.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/CanonicalChapter.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/ChapterVariant.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/ParsedChapterLabel.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/ParseCanonicalChapterLabel.kt`
- `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/ParseCanonicalChapterLabelTest.kt`

Acceptance:
- no `Double` field in canonical chapter identity/order;
- raw display representation remains distinct from normalized identity;
- parser covers numeric, decimal/part, alpha suffix, special types, PT-BR terms, and UNKNOWN;
- deterministic sort key generation;
- no dependency on Reader, Kitsu, or Catalog DTOs.

## Task 2 — Persistence and repositories

**Create:**
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/repository/CanonicalChapterRepository.kt`
- `data/src/main/sqldelight/tachiyomi/data/tsuzuki_canonical_chapters.sq`
- `data/src/main/sqldelight/tachiyomi/data/tsuzuki_chapter_variants.sq`
- `data/src/main/sqldelight/tachiyomi/migrations/17.sqm`
- `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalChapterRepositoryImpl.kt`
- repository tests under `domain/src/test` and/or `data/src/test` at the narrowest practical boundary.

Repository contract must support:
- get chapters by canonical title;
- observe chapters by canonical title;
- get chapter by ID;
- get variant by source identity;
- upsert canonical chapter;
- upsert variant;
- atomic reconciliation writes for one inventory batch;
- variants by canonical chapter;
- variants by source mapping.

Required DB constraints:
- foreign keys to canonical titles/chapters;
- unique source identity for a variant;
- indexes by canonical title, canonical chapter, and source mapping.

Migration 17 must preserve every existing Tsuzuki/Mihon row.

## Task 3 — Non-destructive Mihon chapter inventory adapter

**Create:**
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/SourceChapterSnapshot.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/SourceChapterInventory.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/service/ChapterInventoryGateway.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterInventoryGateway.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/MihonChapterInventoryGatewayTest.kt`

The adapter:
1. receives an accepted `SourceTitleMapping`;
2. requires a materialized `mihonMangaId`;
3. loads the Mihon manga row;
4. obtains the installed source from `SourceManager`;
5. calls the source's suspend `getMangaUpdate(... fetchDetails=false, fetchChapters=true)`;
6. returns neutral source-chapter snapshots;
7. does **not** call `SyncChaptersWithSource`;
8. does **not** insert/update/delete Mihon chapter rows;
9. propagates cancellation;
10. turns source/network failure into a failed `Result`.

Existing DB chapters may be passed to the source API as input because extensions can use them for incremental responses, but reading the DB must remain non-mutating.

## Task 4 — Reconciliation engine

**Create:**
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/ReconcileChapterInventory.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/RefreshCanonicalChapters.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/ChapterReconciliationReport.kt`
- tests under `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter`.

Required behavior:
- stable/idempotent source variant identity;
- same safe parsed identity from multiple sources -> one canonical chapter with multiple variants;
- incompatible type/part/suffix -> separate canonical chapters;
- UNKNOWN from separate sources -> separate unless already explicitly associated;
- existing variant association wins over a later heuristic parse;
- refresh of one source never deletes variants belonging to other sources;
- individual source failure does not destroy prior canonical state;
- cancellation propagates;
- UUID creation and clock injectable for deterministic tests.

`RefreshCanonicalChapters` resolves persisted `SourceTitleMapping` records and may refresh a requested subset. Normal background behavior should prefer the title's preferred mapping first; broad multi-source coverage refresh remains explicit.

## Task 5 — Coverage, gaps, variant selection, fallback candidates

**Create:**
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/ChapterCoverage.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/CanonicalChapterGap.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/ChapterVariantSelection.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/CalculateChapterCoverage.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/DetectCanonicalChapterGaps.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/SelectChapterVariant.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/FindChapterFallback.kt`
- focused tests.

Coverage:
- denominator includes REGULAR chapters only;
- EXTRA/SPECIAL/PROLOGUE/etc. do not inflate regular coverage;
- reports available / total / ratio;
- reports structural uncertainty when canonical structure is only source-derived or contains unresolved/low-confidence evidence.

Gaps:
- compare canonical REGULAR structure against variants available on a requested source mapping;
- do not fabricate gaps from UNKNOWN chapters;
- preserve certainty/evidence in the result.

Variant selection:
1. requested/preferred language;
2. title-level preferred mapping when applicable;
3. language source preference order;
4. deterministic tie-breakers (verified mapping, release date, stable ID).

Fallback:
- returns an alternative `ChapterVariant` candidate for the same canonical chapter;
- never changes the title's preferred mapping globally;
- performs no Reader navigation and no read/progress mutation;
- title-level "always fallback" preference is deferred to Milestone 7 because it is user reading behavior.

## Task 6 — Integrated acceptance

Audit milestone diff against Sections 9, 10, 23, 28, and 29 of `docs/TSUZUKI-SPEC.md`.

Verify specifically:
- no source chapter became canonical identity;
- no canonical chapter identity uses floating point;
- raw source labels remain preserved;
- Mihon chapters are not destructively rewritten;
- source inventory browsing is non-mutating;
- ambiguity fails conservative;
- variants allow multiple releases/sources for one canonical chapter;
- coverage excludes extras/specials;
- gap detection compares against canonical regular structure;
- fallback remains per-chapter;
- no Reader/progress implementation entered.

Final Fast CI must pass:
- Format
- Kotlin Compile
- Unit Tests
- SQLDelight Migrations

Request a Dev A APK only if a minimal diagnostic UI/device path is added during this milestone. Domain-only acceptance does not require an APK. Reader/device acceptance belongs to Milestone 7.

## Execution grouping

```text
Block A: Task 1
  Canonical models + parser

Block B: Task 2
  Persistence + migration

Block C: Tasks 3 + 4
  Mihon inventory adapter + reconciliation

Block D: Task 5
  Coverage + gaps + selection/fallback candidates

Block E: Task 6
  Audit + final CI
```

One Fast CI gate after each block. Stop only on a demonstrated architectural contradiction, shared-contract conflict with another dev lane, or failing CI that cannot be explained within current scope.

## Explicitly deferred to Milestone 7

- opening `ReaderActivity` from a canonical title/chapter;
- synchronizing accepted source inventory into Mihon operational chapter rows for reading;
- canonical read/unread state;
- last-page progress;
- history;
- tracker progress;
- "Read once / Always fallback / Cancel" UI;
- persistent title-level automatic-fallback user choice;
- device smoke of cross-source reading.

## Explicitly deferred beyond initial MVP-V1 engine

Unless implementation evidence forces an earlier contract:
- advanced manual repair UI;
- editorial chapter-count providers;
- complex edition reconciliation;
- scanlation-group quality scoring;
- destructive stale-variant cleanup;
- AI/fuzzy semantic chapter matching.
