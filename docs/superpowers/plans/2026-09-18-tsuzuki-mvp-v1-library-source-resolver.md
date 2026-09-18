# Tsuzuki MVP-V1 Canonical Library + Source Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the accepted canonical catalog foundation into a source-independent Tsuzuki Library, conservatively import existing Mihon library entries, store ordered reading-source preferences, and resolve a canonical title to a persisted Mihon source mapping without beginning chapter or Reader integration.

**Architecture:** Tsuzuki remains the owner of title identity and Library membership. Mihon's existing library, Source API, extension loading, network execution, manga persistence, Reader, and downloader remain operational infrastructure behind narrow adapters. Source search candidates are transient until accepted; accepted candidates are materialized into Mihon's `mangas` table only to obtain a stable operational `mihonMangaId`, then linked to the canonical title through `SourceTitleMapping`.

**Tech Stack:** Kotlin, coroutines/Flow, Metro DI, SQLDelight, Compose, Voyager, Mihon `Source`/`SourceManager`, existing `NetworkToLocalManga`, JUnit 5/Kotest, GitHub Actions Fast CI.

**Spec:** `docs/TSUZUKI-SPEC.md` sections 2, 3, 5, 7, 8, 23, 26, 28, and 29.

## Global Constraints

- `Library != Tracker`.
- `Source Manga != CanonicalTitle`.
- `CatalogProvider != Reading Source`.
- Canonical identity must remain Tsuzuki-owned; provider IDs and Mihon manga IDs never become `CanonicalTitle.id`.
- Adding a title to the canonical Library must not require a reading source.
- Existing Mihon library content must remain accessible even when canonical enrichment fails.
- Existing Mihon favorites, categories, chapters, downloads, and tracker records must not be destructively migrated or rewritten in this milestone.
- Mihon-library import must not call Kitsu or any `CatalogProvider`.
- Resolver automatic search is bounded to at most the first 3 configured preferred sources for the requested language.
- Searching source candidates must not scan every installed source unless the user explicitly invokes the broadened "check more sources" action.
- Low-confidence or conflicting source matches must never be silently accepted.
- Accepted source mappings are persisted and reused before any new network search.
- Source failures must not remove or block canonical Library access.
- Search candidates remain transient until acceptance; merely displaying candidates must not create a canonical title or favorite a Mihon manga.
- No Canonical Chapter Engine, chapter normalization, fallback reading, Reader/progress integration, Collections, Drive sync, or broad navigation redesign in this plan.
- Fast CI is the normal acceptance gate. Local heavy Gradle execution is optional when authoritative delegated/CI evidence exists.
- An APK checkpoint is required only at the final on-device vertical-slice acceptance task.

## Repository Findings That Drive This Plan

- The canonical foundation already provides `CanonicalLibraryRepository`, `SourceTitleMappingRepository`, `MaterializeCanonicalTitle.fromCatalog(...)`, and `MaterializeCanonicalTitle.fromSource(...)`.
- Existing SQLDelight tables `tsuzuki_library_entries` and `tsuzuki_source_mappings` already persist canonical membership and source mappings.
- `MangaRepository.getLibraryManga()` and `GetLibraryManga.await()` expose the current Mihon library without requiring provider metadata.
- `SourceManager` already exposes installed sources and resolves a source by ID.
- Mihon source search is available through `Source.getSearchManga(page, query, filters)`.
- Existing global/migration search materializes every returned result through `NetworkToLocalManga`; Tsuzuki must not copy that behavior for resolver browsing. Only an accepted resolver candidate should be materialized.
- `NetworkToLocalManga` already provides the correct idempotent bridge into Mihon's `mangas` table for an accepted source candidate.
- Existing `SourcePreferences.migrationSources` is not sufficient for Tsuzuki because it is global rather than ordered per language. Tsuzuki therefore needs its own ordered preference persistence.
- The current `SourceTitleMapping.preferredOverride` field is sufficient for a title-level preferred mapping once repository support for atomic selection is added.
- Existing `LibraryTab` remains Mihon's source-bound library. A minimal canonical-library screen is necessary because source-less canonical entries cannot appear in the existing Mihon Library UI.

---

### Task 1: Canonical Library application layer and query model

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/library/model/CanonicalLibraryItem.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/library/interactor/AddCatalogItemToLibrary.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/library/interactor/ObserveCanonicalLibrary.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/library/interactor/SetCanonicalLibraryStatus.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/library/interactor/RemoveCanonicalLibraryItem.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/library/interactor/AddCatalogItemToLibraryTest.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/library/interactor/CanonicalLibraryOperationsTest.kt`

**Interfaces:**
- Consumes: `MaterializeCanonicalTitleFromCatalog.execute(CatalogItem): CanonicalTitle`, `CanonicalLibraryRepository`.
- Produces:
  - `CanonicalLibraryItem(title: CanonicalTitle, entry: CanonicalLibraryEntry)`
  - `AddCatalogItemToLibrary.execute(item: CatalogItem, status: LibraryStatus = PLANNING): CanonicalLibraryItem`
  - `ObserveCanonicalLibrary.subscribe(): Flow<List<CanonicalLibraryItem>>`
  - `SetCanonicalLibraryStatus.execute(canonicalTitleId: String, status: LibraryStatus)`
  - `RemoveCanonicalLibraryItem.execute(canonicalTitleId: String)`
  - `CanonicalLibraryRepository.getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>>`

- [ ] **Step 1: Add a joined canonical-library query**

Extend `tsuzuki_library_entries.sq` with:

```sql
getAllTsuzukiLibraryItems:
SELECT
    l.canonical_title_id,
    l.status,
    l.favorite,
    l.added_at,
    l.updated_at,
    t.display_title,
    t.identity_state,
    t.created_at AS title_created_at,
    t.updated_at AS title_updated_at
FROM tsuzuki_library_entries l
JOIN tsuzuki_titles t ON t.id = l.canonical_title_id
ORDER BY l.added_at DESC;
```

Do not join source mappings here. Library membership must remain valid with no reading source.

- [ ] **Step 2: Add `CanonicalLibraryItem` and repository mapping**

```kotlin
data class CanonicalLibraryItem(
    val title: CanonicalTitle,
    val entry: CanonicalLibraryEntry,
)
```

Add `getAllItemsAsFlow()` to `CanonicalLibraryRepository` and map the joined SQL query in `CanonicalLibraryRepositoryImpl`.

- [ ] **Step 3: Write failing add-to-library tests**

Cover:
- catalog item materializes through the existing canonical path before membership is written;
- provider ID is not the canonical ID;
- default status is `PLANNING`;
- adding the same catalog identity twice is idempotent and preserves original `addedAt`;
- adding to Library never touches `SourceTitleMappingRepository`.

- [ ] **Step 4: Implement `AddCatalogItemToLibrary`**

Use an injectable clock. Call `MaterializeCanonicalTitleFromCatalog.execute(item)`, then inspect existing membership.

For a new entry:

```kotlin
CanonicalLibraryEntry(
    canonicalTitleId = title.id,
    status = status,
    favorite = true,
    addedAt = now,
    updatedAt = now,
)
```

For an existing entry, do not reset status or `addedAt`.

- [ ] **Step 5: Implement status/remove/observe operations**

`SetCanonicalLibraryStatus` preserves `addedAt`. Removing membership must not delete the canonical title, external identities, Mihon manga rows, or mappings.

- [ ] **Step 6: Run focused tests**

```bash
./gradlew testDebugUnitTest --tests "tachiyomi.domain.tsuzuki.library.interactor.*"
```

- [ ] **Step 7: Commit and push**

```bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/library   domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalLibraryRepository.kt   data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq   data/src/main/java/tachiyomi/data/tsuzuki/CanonicalLibraryRepositoryImpl.kt   domain/src/test/java/tachiyomi/domain/tsuzuki/library
git commit -m "feat(tsuzuki): add canonical library operations"
git push
```

Fast CI must be green before accepting Task 1.

---

### Task 2: Add-to-Library UI and minimal canonical Library surface

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModel.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/library/CanonicalLibraryScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/CatalogScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/tsuzuki/catalog/CatalogItemDetailSheet.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/tsuzuki/catalog/CatalogScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/library/components/LibraryToolbar.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/catalog/CatalogScreenModelTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModelTest.kt`

**Interfaces:**
- Consumes Task 1 interactors.
- Produces a reachable canonical Library and explicit durable `Add to Library` action.

- [ ] **Step 1: Write ScreenModel tests**

Cover:
- preview remains ephemeral until `addToLibrary(item)`;
- explicit add writes one membership;
- repeated add reports already-added without duplicate membership;
- source-less entries are observable;
- status changes update the list;
- remove deletes membership only.

- [ ] **Step 2: Extend CatalogScreenModel with explicit Library action**

Inject `AddCatalogItemToLibrary`. Use:

```kotlin
sealed interface LibraryActionState {
    data object Idle : LibraryActionState
    data object Saving : LibraryActionState
    data class Saved(val canonicalTitleId: String) : LibraryActionState
    data class Error(val error: Throwable) : LibraryActionState
}
```

`openPreview(item)` still performs no write. Only `addToLibrary(item)` may materialize/persist.

- [ ] **Step 3: Add an explicit button to the detail sheet**

Add `onAddToLibrary` and action state to `CatalogItemDetailSheet`. Keep metadata preview behavior unchanged.

- [ ] **Step 4: Build minimal canonical Library screen**

Show display title, canonical status, and identity state. The list must accept source-less entries. Do not model the list as `LibraryManga`.

- [ ] **Step 5: Add smallest entry point from existing Library**

Extend `LibraryToolbar` with one optional canonical-library overflow action. `LibraryTab` pushes `CanonicalLibraryScreen()`. Do not replace the existing Library tab.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.tsuzuki.catalog.CatalogScreenModelTest"   --tests "eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenModelTest"
git add app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki   app/src/main/java/eu/kanade/presentation/tsuzuki   app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt   app/src/main/java/eu/kanade/presentation/library/components/LibraryToolbar.kt   app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki
git commit -m "feat(tsuzuki): expose source-independent canonical library"
git push
```

---

### Task 3: Conservative Mihon Library import

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/migration/model/MihonLibrarySnapshot.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/migration/model/CanonicalMigrationReport.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/migration/service/MihonLibraryGateway.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/migration/interactor/MigrateMihonLibraryToCanonical.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonLibraryGatewayImpl.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/migration/MigrateMihonLibraryToCanonicalTest.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModel.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModelTest.kt`

**Interfaces:**

```kotlin
data class MihonLibrarySnapshot(
    val mihonMangaId: Long,
    val sourceId: Long,
    val sourceUrl: String,
    val sourceLanguage: String,
    val sourceAvailable: Boolean,
    val title: String,
    val dateAdded: Long,
    val hasStarted: Boolean,
)
```

`MihonLibraryGateway.snapshot(): List<MihonLibrarySnapshot>`

`MigrateMihonLibraryToCanonical.execute(): CanonicalMigrationReport`

- [ ] **Step 1: Write migration tests**

Required:
- unmapped favorite -> one `SOURCE_ONLY` title + one canonical Library entry + one source mapping;
- rerun is idempotent;
- existing source mapping is reused;
- unavailable extension preserves item and mapping with `UNKNOWN` availability;
- no CatalogProvider/Kitsu dependency;
- started -> `READING`;
- unstarted -> `PLANNING`;
- never infer `COMPLETED`;
- existing Mihon favorite/category/chapter state is not mutated.

- [ ] **Step 2: Implement Mihon adapter**

Inject `GetLibraryManga` and `SourceManager`. Read `manga.id/source/url/title/dateAdded`, `LibraryManga.hasStarted`, and source availability/language. Do not require extension installation.

- [ ] **Step 3: Implement idempotent migration**

For each snapshot:
1. `SourceTitleMappingRepository.getBySource(sourceId, sourceUrl)`.
2. Reuse existing canonical ID when mapping exists.
3. Otherwise call `MaterializeCanonicalTitle.fromSource(title)`.
4. Persist direct mapping with:
   - exact `mihonMangaId`;
   - `matchConfidence = 1.0` because it is the exact preexisting source row, not a title guess;
   - `verifiedByUser = false`;
   - `availability = AVAILABLE` only when source currently exists, else `UNKNOWN`;
   - `preferredOverride = false`.
5. Upsert canonical membership without modifying Mihon.

Preserve existing canonical status/`addedAt`.

- [ ] **Step 4: Run migration from canonical Library screen**

On first load, run the idempotent migration before final list state. Failure becomes a non-blocking event; existing Mihon Library remains untouched.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests "tachiyomi.domain.tsuzuki.migration.*"
git add domain/src/main/java/tachiyomi/domain/tsuzuki/migration   app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonLibraryGatewayImpl.kt   domain/src/test/java/tachiyomi/domain/tsuzuki/migration   app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library   app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/library
git commit -m "feat(tsuzuki): import Mihon library conservatively"
git push
```

---

### Task 4: Ordered reading-source preferences and title override persistence

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/model/ReadingSourcePreference.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/repository/ReadingSourcePreferenceRepository.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/GetPreferredReadingSources.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/SetPreferredReadingSources.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/SetTitleSourceOverride.kt`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_preferences.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/16.sqm`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/ReadingSourcePreferenceRepositoryImpl.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/SourceTitleMappingRepositoryImpl.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/source/ReadingSourcePreferenceTest.kt`

**Interfaces:**
- `getForLanguage(language)`
- `observeForLanguage(language)`
- `getConfiguredLanguages()`
- `replaceForLanguage(language, orderedSourceIds)`
- `SourceTitleMappingRepository.setPreferredForTitle(canonicalTitleId, mappingId, updatedAt)`

- [ ] **Step 1: Add schema + migration 16**

```sql
CREATE TABLE tsuzuki_source_preferences(
    language TEXT NOT NULL,
    source_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY(language, source_id),
    UNIQUE(language, position)
);
```

Queries return `position ASC`. Replacement runs delete + inserts inside one DB transaction.

- [ ] **Step 2: Add atomic title override query**

Update all mappings for one canonical title so exactly one requested mapping has `preferred_override = 1`, or all are cleared for null. Reject an ID belonging to another canonical title.

- [ ] **Step 3: Write tests**

Cover per-language ordering, language isolation, duplicate input rejection, override replacement, and cross-title override rejection.

- [ ] **Step 4: Verify migration and commit**

```bash
./gradlew verifySqlDelightMigration
git add domain/src/main/java/tachiyomi/domain/tsuzuki/source   domain/src/main/java/tachiyomi/domain/tsuzuki/repository/SourceTitleMappingRepository.kt   data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_preferences.sq   data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq   data/src/main/sqldelight/tachiyomi/migrations/16.sqm   data/src/main/java/tachiyomi/data/tsuzuki   domain/src/test/java/tachiyomi/domain/tsuzuki/source
git commit -m "feat(tsuzuki): persist reading source preferences"
git push
```

---

### Task 5: Mihon reading-source search/materialization adapter

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/model/ReadingSourceDescriptor.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/model/ReadingSourceCandidate.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/model/MaterializedReadingSource.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/service/ReadingSourceGateway.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGateway.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGatewayTest.kt`

**Interfaces:**

```kotlin
data class ReadingSourceDescriptor(
    val sourceId: Long,
    val name: String,
    val language: String,
)

data class ReadingSourceCandidate(
    val sourceId: Long,
    val sourceName: String,
    val language: String,
    val sourceUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>?,
    val status: Long,
)

data class MaterializedReadingSource(
    val mihonMangaId: Long,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
)

interface ReadingSourceGateway {
    suspend fun listInstalled(language: String): List<ReadingSourceDescriptor>
    suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>>
    suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource>
}
```

- [ ] **Step 1: Write adapter tests**

Cover:
- installed/enabled catalogue sources only for requested language;
- disabled source is not offered;
- search calls exactly one source, page 1, default filters;
- search does not call `NetworkToLocalManga`;
- duplicate URLs collapse;
- cancellation rethrows;
- source error becomes failed `Result`;
- materialize calls `NetworkToLocalManga` and returns stable Mihon ID.

- [ ] **Step 2: Implement source listing/search**

Inject `SourceManager`, existing `SourcePreferences` only to honor disabled sources, and `NetworkToLocalManga`.

Do not reuse `SearchViewModel`; it eagerly persists every result.

Search via:

```kotlin
source.getSearchManga(
    page = 1,
    query = query,
    filters = source.getFilterList(),
)
```

Map `SManga` to neutral candidates without inserts.

- [ ] **Step 3: Materialize accepted candidate only**

Build a minimal `Manga.create().copy(...)` from candidate data and call `NetworkToLocalManga`. Do not mark it favorite.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests "eu.kanade.tachiyomi.data.tsuzuki.MihonReadingSourceGatewayTest"
git add domain/src/main/java/tachiyomi/domain/tsuzuki/source   app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGateway.kt   app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/MihonReadingSourceGatewayTest.kt
git commit -m "feat(tsuzuki): adapt Mihon sources for canonical resolution"
git push
```

---

### Task 6: Conservative Source Resolver and mapping acceptance

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/model/ScoredSourceCandidate.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/model/SourceResolutionResult.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/ScoreSourceTitleMatch.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/ConfirmSourceMapping.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/source/interactor/ResolveReadingSource.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/source/ResolveReadingSourceTest.kt`
- Create: `domain/src/test/java/tachiyomi/domain/tsuzuki/source/ConfirmSourceMappingTest.kt`

**Interfaces:**

```kotlin
sealed interface SourceResolutionResult {
    data class Resolved(val mapping: SourceTitleMapping, val reused: Boolean) : SourceResolutionResult
    data class NeedsConfirmation(val candidates: List<ScoredSourceCandidate>) : SourceResolutionResult
    data class NotFound(val searchedSourceIds: List<Long>, val canBroaden: Boolean) : SourceResolutionResult
    data class NoPreferredSources(val language: String) : SourceResolutionResult
    data class Conflict(val existingCanonicalTitleId: String) : SourceResolutionResult
}
```

Main entry: `ResolveReadingSource.execute(canonicalTitleId, language, broaden = false)`.

- [ ] **Step 1: Write scoring tests**

Normalize Unicode accents, case, punctuation, whitespace. Score is `0.0..1.0`. Exact normalized title is `1.0`; typo is high but below exact; unrelated is below confirmation threshold; deceptively similar distinct work must not score exact.

Use deterministic token overlap + normalized edit similarity. No AI or network.

- [ ] **Step 2: Write resolver tests**

Cover:
- persisted mapping short-circuits all search;
- source order respected;
- normal path searches at most 3 configured sources;
- one source failure does not cancel healthy siblings;
- cancellation propagates;
- no preferences -> `NoPreferredSources`;
- unique high-confidence -> auto accept;
- ambiguous -> `NeedsConfirmation`;
- low confidence -> `NotFound`;
- normal path never scans unconfigured sources;
- `broaden = true` may inspect additional installed sources for that language;
- source URL already mapped to another title -> `Conflict`.

- [ ] **Step 3: Conservative automatic acceptance**

Per source, rank candidates. Auto eligibility requires:
- score >= `0.97`; and
- no second candidate from same source within `0.08`.

Walk sources in configured preference order; first eligible source wins.

If none qualifies, return at most 5 candidates with score >= `0.70`, ordered by source preference then confidence, through `NeedsConfirmation`.

Threshold changes require fixture evidence.

- [ ] **Step 4: Implement `ConfirmSourceMapping`**

Before materialization:
1. check `getBySource(sourceId, sourceUrl)`;
2. cross-title existing mapping -> conflict;
3. materialize accepted candidate;
4. persist mapping with returned `mihonMangaId`;
5. manual confirmation -> `verifiedByUser = true`; conservative auto-accept -> false;
6. canonical ID never changes.

- [ ] **Step 5: Error behavior**

Individual source failures do not crash the resolver. If all searched sources fail with no candidate, return `NotFound`. Do not mark an existing mapping unavailable from a transient failure.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests "tachiyomi.domain.tsuzuki.source.*"
git add domain/src/main/java/tachiyomi/domain/tsuzuki/source   domain/src/test/java/tachiyomi/domain/tsuzuki/source
git commit -m "feat(tsuzuki): resolve canonical titles to reading sources"
git push
```

---

### Task 7: Source preference and resolver UI vertical slice

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/source/ReadingSourcePreferencesScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/source/ReadingSourcePreferencesScreenModel.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/source/ReadingSourcePreferencesScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/source/SourceResolverScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/source/SourceResolverScreenModel.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/source/SourceResolverScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreen.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModel.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/tsuzuki/library/CanonicalLibraryScreen.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/source/ReadingSourcePreferencesScreenModelTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/source/SourceResolverScreenModelTest.kt`

**Interfaces:** Consumes Tasks 4-6 and produces the human-testable resolver path.

- [ ] **Step 1: Preference ScreenModel tests**

Cover language grouping, stored order, pending edits before save, Up/Down ordering, and language isolation. Use explicit Up/Down actions rather than drag-and-drop.

- [ ] **Step 2: Minimal preference UI**

Allow language selection, source add/remove, move up/down, save. Never silently install extensions.

- [ ] **Step 3: Resolver ScreenModel tests**

States:
- Loading;
- NoPreferredSources;
- Searching;
- Resolved;
- NeedsConfirmation;
- NotFound;
- Conflict;
- Error only for unexpected local/application failures.

Also test mapping reuse, manual confirmation, broadened search, and Library survival on source failure.

- [ ] **Step 4: Resolver screen flow**

```text
Canonical Library item
        |
        +-- existing mapping --> source summary
        |
        +-- no mapping --> Find reading source
                              |
                              +-- one configured language --> resolve
                              |
                              +-- multiple languages --> choose language
                              |
                              +-- none --> configure sources
```

Confirmation rows show title, source name, language, thumbnail, and confidence as diagnostic information. Do not label an unconfirmed candidate "correct".

`NotFound` exposes `Check more sources`.

- [ ] **Step 5: Title-level override**

When multiple mappings exist, allow one to be preferred via `SetTitleSourceOverride`. Keep other mappings.

- [ ] **Step 6: Verify UI isolation**

No Kitsu DTO/client imports and no Reader/chapter calls in these presentation packages.

- [ ] **Step 7: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.tsuzuki.source.*"
git add app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/source   app/src/main/java/eu/kanade/presentation/tsuzuki/source   app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library   app/src/main/java/eu/kanade/presentation/tsuzuki/library   app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/source
git commit -m "feat(tsuzuki): add source resolver UI slice"
git push
```

---

### Task 8: Integrated acceptance, APK, and device smoke

**Files:** Modify only Tasks 1-7 files if concrete evidence requires correction.

- [ ] **Step 1: Audit full milestone diff**

```bash
git diff tsuzuki/bootstrap...HEAD
```

Verify:
- no provider/source/Mihon ID became canonical ID;
- catalog preview still persists only from explicit Add to Library;
- source-less canonical Library works;
- Mihon migration has no Kitsu dependency;
- migration never mutates existing favorites/categories/chapters;
- normal resolver checks <= 3 preferred sources;
- broadened search requires explicit user action;
- candidate browsing does not eagerly insert Mihon rows;
- only accepted candidate calls `NetworkToLocalManga`;
- mapping conflicts fail closed;
- no chapter/Reader/progress implementation entered.

- [ ] **Step 2: Require final Fast CI**

Format, Kotlin Compile, Unit Tests, SQLDelight Migrations all `SUCCESS`.

- [ ] **Step 3: Create one APK checkpoint**

```bash
git commit --allow-empty -m "chore: library and source resolver device checkpoint [apk]"
git push
```

Do not add `[full-ci]` to the same checkpoint.

- [ ] **Step 4: Verify signed artifact**

APK workflow success, exact checkpoint SHA, arm64 artifact, and successful `apksigner verify` using persistent Tsuzuki signing secrets.

- [ ] **Step 5: Human smoke**

1. Existing Mihon Library and Reader still work.
2. Canonical Library opens.
3. Existing favorites appear after conservative import.
4. Missing extension does not make migrated canonical item disappear.
5. Catalog title adds to canonical Library without source.
6. Source-less title survives reopen.
7. Ordered source preferences can be configured per language.
8. Normal resolver uses only configured preferred set.
9. Obvious match resolves and persists.
10. Ambiguous match requires confirmation.
11. `Check more sources` broadens only after explicit tap.
12. Persisted mapping is reused.
13. Source failure does not block Library.
14. APK updates over prior persistently signed build without uninstall.

- [ ] **Step 6: Stop for human acceptance**

Do not merge before human smoke. After acceptance, PR `tsuzuki/mvp-v1-library-source-resolver` -> `tsuzuki/bootstrap`.

---

## Execution Grouping

Recommended AGY execution grouping:

```text
Block A: Tasks 1 + 2
  Canonical Library core + visible Add/Library path

Block B: Tasks 3 + 4
  Conservative Mihon import + ordered source preferences

Block C: Tasks 5 + 6
  Mihon source adapter + resolver engine

Block D: Task 7
  Resolver/preferences UI vertical slice

Block E: Task 8
  Audit + signed APK + human smoke
```

Each block has one final Fast CI gate. Do not stop between tasks inside a block unless BLOCKED or an architectural contradiction appears.

## Plan Self-Review Results

- **Spec coverage:** Canonical Library, source-independent membership, conservative Mihon import, language-ordered source preferences, title override, bounded resolver, persisted mapping, explicit broadened search, and minimal device proof are assigned.
- **Dependency order:** Library -> migration/preferences -> source adapter/resolver -> UI -> smoke.
- **Safety:** Kitsu is absent from migration, candidates are transient, ambiguity/conflicts fail closed, and Mihon state is not destructively rewritten.
- **Scope:** Canonical Chapters and Reader/progress remain the next milestone.
- **CI:** Fast CI is authoritative; only final vertical acceptance requests an APK.
