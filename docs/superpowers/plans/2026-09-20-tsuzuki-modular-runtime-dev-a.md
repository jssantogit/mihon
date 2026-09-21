# Tsuzuki Modular Runtime — Dev A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared modular contracts plus the Integration/search/chapter-evidence side of the Tsuzuki runtime without touching Add-on delivery, Reader internals, or Supabase sync.

**Architecture:** Dev A first creates the common capability/schema baseline that all three developers consume. After that foundation is merged, Dev A owns first-party Integrations, aggregated Search, canonical identity reconciliation, evidence-driven chapters, update observation, Search/detail UI, and Integration Settings.

**Tech Stack:** Kotlin, Metro DI, SQLDelight, existing Kitsu/MAL clients, Jetpack Compose, GitHub Actions CI v2.

**Spec:** `docs/superpowers/specs/2026-09-20-tsuzuki-modular-runtime-architecture-design.md`

## Global Constraints

- Never use display-title equality as canonical identity.
- Never promote provider IDs into Tsuzuki canonical IDs.
- `chapterCount` is metadata only.
- Add-on runtime, Reader, downloads, Supabase, Home shell, and Google/Drive removal are outside Dev A ownership.
- Foundation migration is exactly `data/src/main/sqldelight/tachiyomi/migrations/30.sqm`.
- After Foundation F1 merges, Dev A must not make breaking changes to shared capability types without stopping B/C.
- Fast CI per task; `[ci-full]` on F1 and the final Dev A checkpoint.

## Review Focus

- Same title text from unrelated providers must remain distinct without verified identity.
- Same verified external identity observed twice must converge to one CanonicalTitle under concurrent materialization.
- Kitsu/MAL being disabled must not delete existing canonical state.
- Chapter counts without chapter evidence must create no CanonicalChapter rows.
- Ambiguous chapter evidence must not force-merge into one logical chapter.

---

### Task F1: Freeze shared capability contracts and cross-stream persistence

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/capability/ProviderIds.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/IntegrationCapabilities.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/IntegrationRegistry.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/model/ExternalRating.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/model/TrackingUpdate.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/addon/AddonCapabilities.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/addon/AddonRegistry.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ChapterEvidence.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/ContentModels.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/ContentBinding.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/content/ContentPreference.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/download/model/CanonicalDownloadArtifact.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/model/CanonicalChapter.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalChapterRepositoryImpl.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_canonical_chapters.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_integration_settings.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_chapter_evidence.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_content_bindings.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_content_preferences.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_chapter_update_state.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_continue_reading_state.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_canonical_downloads.sq`
- Create: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_supabase_sync.sq`
- Create: `data/src/main/sqldelight/tachiyomi/migrations/30.sqm`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/capability/CapabilityContractsTest.kt`
- Test: SQLDelight migration verification through CI v2

**Interfaces:**
- Produces: `IntegrationId`, `AddonId`, `SearchProvider`, `DiscoveryProvider`, `MetadataProvider`, `ChapterEvidenceProvider`, `RatingsProvider`, `TrackingProvider`, `ExternalRating`, `TrackingUpdate`, `ContentProvider`, `ChapterProbeProvider`, `IntegrationRegistry`, `AddonRegistry`, `ChapterEvidence`, `ContentOption`, `ContentDelivery`.
- Consumes: existing `CatalogQuery`, `CatalogPage`, `CatalogItem`, `CanonicalTitle`, `CanonicalChapter`.

- [ ] **Step 1: Write the contract test**

~~~kotlin
class CapabilityContractsTest {

    @Test
    fun `provider ids keep integration and addon namespaces distinct`() {
        val integration = IntegrationId("kitsu")
        val addon = AddonId("kitsu")
        assertEquals("kitsu", integration.value)
        assertEquals("kitsu", addon.value)
        assertNotEquals(integration, addon)
    }

    @Test
    fun `content option always points to a canonical chapter and addon`() {
        val option = ContentOption(
            key = "mangadex:chapter-1:en",
            canonicalChapterId = "chapter-1",
            addonId = AddonId("mangadex"),
            language = "en",
            scanlationGroup = "Group",
            releaseDate = 100L,
            delivery = ContentDelivery.Mihon(
                sourceId = 1L,
                mangaId = 2L,
                chapterId = 3L,
            ),
        )

        assertEquals("chapter-1", option.canonicalChapterId)
        assertEquals(AddonId("mangadex"), option.addonId)
    }
}
~~~

- [ ] **Step 2: Run the targeted domain test and confirm it fails because the new types do not exist**

Run:

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*CapabilityContractsTest'
~~~

Expected: compile/test failure for unresolved shared types.

- [ ] **Step 3: Add the shared types with stable signatures**

~~~kotlin
@JvmInline
value class IntegrationId(val value: String)

@JvmInline
value class AddonId(val value: String)

enum class ProducerKind { INTEGRATION, ADDON }

enum class ChapterEvidenceAuthority {
    EDITORIAL,
    ADDON_PROVISIONAL,
}

enum class CanonicalChapterConfirmation {
    CONFIRMED,
    PROVISIONAL,
    CONFLICTED,
}

sealed interface ContentDelivery {
    data class Mihon(
        val sourceId: Long,
        val mangaId: Long,
        val chapterId: Long,
    ) : ContentDelivery

    data class LocalArchive(val uri: String) : ContentDelivery
    data class LocalDirectory(val uri: String) : ContentDelivery
    data class Torrent(
        val infoHash: String,
        val magnetUri: String?,
        val fileIndex: Int?,
        val filePath: String?,
    ) : ContentDelivery
}
~~~

Define the capability interfaces exactly as frozen in the master plan. Add:

~~~kotlin
data class ExternalRating(
    val providerId: String,
    val label: String,
    val value: Double,
    val scaleMax: Double,
)

data class TrackingUpdate(
    val externalId: String,
    val chapterProgress: Double?,
    val status: LibraryStatus?,
    val score: Double?,
)

interface TrackingProvider {
    val integrationId: IntegrationId
    suspend fun isConnected(): Boolean
    suspend fun update(update: TrackingUpdate): Result<Unit>
}
~~~

- [ ] **Step 4: Add SQLDelight schemas and migration 30**

The migration creates all cross-stream tables in one serial baseline. Use these columns as the minimum contract:

~~~sql
CREATE TABLE tsuzuki_integration_settings(
    integration_id TEXT NOT NULL PRIMARY KEY,
    enabled INTEGER AS Boolean NOT NULL,
    config_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE tsuzuki_chapter_evidence(
    id TEXT NOT NULL PRIMARY KEY,
    canonical_title_id TEXT NOT NULL,
    producer_kind TEXT NOT NULL,
    producer_id TEXT NOT NULL,
    external_chapter_key TEXT,
    raw_label TEXT NOT NULL,
    raw_number REAL,
    volume INTEGER,
    title TEXT,
    observed_at INTEGER NOT NULL,
    confidence REAL NOT NULL,
    authority_class TEXT NOT NULL,
    mapped_canonical_chapter_id TEXT,
    raw_metadata BLOB NOT NULL,
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE,
    FOREIGN KEY(mapped_canonical_chapter_id) REFERENCES tsuzuki_canonical_chapters(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX tsuzuki_chapter_evidence_external_index
ON tsuzuki_chapter_evidence(producer_kind, producer_id, external_chapter_key)
WHERE external_chapter_key IS NOT NULL;

CREATE TABLE tsuzuki_content_bindings(
    id TEXT NOT NULL PRIMARY KEY,
    canonical_title_id TEXT NOT NULL,
    addon_id TEXT NOT NULL,
    provider_title_key TEXT NOT NULL,
    match_confidence REAL NOT NULL,
    verified_by_user INTEGER AS Boolean NOT NULL,
    availability TEXT NOT NULL,
    runtime_payload BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(addon_id, provider_title_key),
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
);

CREATE TABLE tsuzuki_content_preferences(
    canonical_title_id TEXT NOT NULL PRIMARY KEY,
    preferred_addon_id TEXT,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
);

CREATE TABLE tsuzuki_chapter_update_state(
    canonical_chapter_id TEXT NOT NULL PRIMARY KEY,
    canonical_title_id TEXT NOT NULL,
    first_seen_at INTEGER NOT NULL,
    acknowledged_at INTEGER,
    FOREIGN KEY(canonical_chapter_id) REFERENCES tsuzuki_canonical_chapters(id) ON DELETE CASCADE,
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
);

CREATE TABLE tsuzuki_continue_reading_state(
    canonical_title_id TEXT NOT NULL PRIMARY KEY,
    hidden_at INTEGER,
    FOREIGN KEY(canonical_title_id) REFERENCES tsuzuki_titles(id) ON DELETE CASCADE
);

CREATE TABLE tsuzuki_canonical_downloads(
    canonical_chapter_id TEXT NOT NULL PRIMARY KEY,
    local_uri TEXT NOT NULL,
    format TEXT NOT NULL,
    originating_addon_id TEXT,
    originating_option_key TEXT,
    completed_at INTEGER NOT NULL,
    checksum TEXT,
    FOREIGN KEY(canonical_chapter_id) REFERENCES tsuzuki_canonical_chapters(id) ON DELETE CASCADE
);

CREATE TABLE tsuzuki_supabase_sync_cursor(
    domain TEXT NOT NULL PRIMARY KEY,
    event_cursor INTEGER NOT NULL,
    last_successful_sync_at INTEGER
);

CREATE TABLE tsuzuki_supabase_pending_mutation(
    mutation_id TEXT NOT NULL PRIMARY KEY,
    domain TEXT NOT NULL,
    base_cursor INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at INTEGER
);

ALTER TABLE tsuzuki_canonical_chapters
ADD COLUMN confirmation_state TEXT NOT NULL DEFAULT 'PROVISIONAL';
~~~

Update the SQLDelight schema definition, `CanonicalChapter`, and `CanonicalChapterRepositoryImpl` in the same foundation commit so generated query signatures stay compilable. Existing source-derived rows intentionally migrate to `PROVISIONAL`:

~~~kotlin
data class CanonicalChapter(
    val id: String,
    val canonicalTitleId: String,
    val displayNumber: String,
    val volume: Int? = null,
    val title: String? = null,
    val type: CanonicalChapterType = CanonicalChapterType.UNKNOWN,
    val baseNumber: Int? = null,
    val part: Int? = null,
    val alphaSuffix: String? = null,
    val confidence: Double = 0.0,
    val confirmation: CanonicalChapterConfirmation = CanonicalChapterConfirmation.PROVISIONAL,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
~~~

Mirror the new cross-stream tables in the corresponding `.sq` files with named get/upsert/delete queries.

- [ ] **Step 5: Run migration and domain checks**

Run:

~~~bash
./gradlew verifySqlDelightMigration :domain:testDebugUnitTest --tests '*CapabilityContractsTest'
~~~

Expected: PASS.

- [ ] **Step 6: Commit foundation**

~~~bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki \
  domain/src/test/java/tachiyomi/domain/tsuzuki/capability \
  data/src/main/sqldelight/tachiyomi/data \
  data/src/main/sqldelight/tachiyomi/migrations/30.sqm
git commit -m "feat(tsuzuki): establish modular runtime contracts [ci-full]"
~~~

Push and require CI v2 full green before merging this branch into `tsuzuki/bootstrap`.

---

### Task A1: Implement Integration settings persistence and registry

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/model/IntegrationSettings.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/repository/IntegrationSettingsRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/integration/IntegrationSettingsRepositoryImpl.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration/DefaultIntegrationRegistry.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/integration/IntegrationSettingsTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/DefaultIntegrationRegistryTest.kt`

**Interfaces:**
- Produces: enabled-capability filtering for Integration providers.
- Consumes: Wave 0 capability interfaces and SQLDelight table.

- [ ] **Step 1: Write failing tests for disabled-by-default behavior**

~~~kotlin
@Test
fun `new integration setting is disabled by default`() {
    val settings = IntegrationSettings(integrationId = IntegrationId("kitsu"))
    assertFalse(settings.enabled)
}

@Test
fun `registry excludes providers whose integration is disabled`() {
    val registry = DefaultIntegrationRegistry(
        settings = fakeSettings("kitsu" to false),
        searchProviders = listOf(FakeSearchProvider("kitsu")),
        discoveryProviders = emptyList(),
        metadataProviders = emptyList(),
        chapterEvidenceProviders = emptyList(),
        ratingsProviders = emptyList(),
    )

    assertTrue(registry.searchProviders().isEmpty())
}
~~~

- [ ] **Step 2: Run targeted tests and confirm failure**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*IntegrationSettingsTest' \
  :app:testDebugUnitTest --tests '*DefaultIntegrationRegistryTest'
~~~

- [ ] **Step 3: Implement the model/repository/registry**

~~~kotlin
data class IntegrationSettings(
    val integrationId: IntegrationId,
    val enabled: Boolean = false,
    val configJson: String = "{}",
    val updatedAt: Long = 0L,
)

interface IntegrationSettingsRepository {
    suspend fun get(id: IntegrationId): IntegrationSettings?
    fun observeAll(): Flow<List<IntegrationSettings>>
    suspend fun upsert(settings: IntegrationSettings)
}
~~~

`DefaultIntegrationRegistry` must filter every capability list using the latest enabled state and must never infer enabled=true from provider presence.

- [ ] **Step 4: Run targeted tests and format**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*IntegrationSettingsTest' \
  :app:testDebugUnitTest --tests '*DefaultIntegrationRegistryTest' \
  spotlessCheck
~~~

- [ ] **Step 5: Commit**

~~~bash
git add domain/src/main/java/tachiyomi/domain/tsuzuki/integration \
  data/src/main/java/tachiyomi/data/tsuzuki/integration \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration \
  domain/src/test/java/tachiyomi/domain/tsuzuki/integration \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration
git commit -m "feat(tsuzuki): add integration registry"
~~~

---

### Task A2: Convert Kitsu into an explicit Integration adapter

**Files:**
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/kitsu/KitsuCatalogProvider.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration/KitsuIntegrationProvider.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/KitsuIntegrationProviderTest.kt`
- Modify tests: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchCatalogTest.kt`
- Modify tests: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/GetDiscoverFeedTest.kt`

**Interfaces:**
- Produces: Kitsu `SearchProvider`, `DiscoveryProvider`, `MetadataProvider`.
- Consumes: existing `KitsuCatalogProvider`.

- [ ] **Step 1: Write a failing adapter test proving chapterCount is metadata only**

~~~kotlin
@Test
fun `kitsu search exposes chapter count but does not create chapter evidence`() = runTest {
    val provider = KitsuIntegrationProvider(fakeKitsuCatalog(chapterCount = 205))

    val page = provider.search(CatalogQuery(query = "Dandadan")).getOrThrow()

    assertEquals(205, page.items.single().chapterCount)
    assertFalse(provider is ChapterEvidenceProvider)
}
~~~

- [ ] **Step 2: Run test and confirm failure**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*KitsuIntegrationProviderTest'
~~~

- [ ] **Step 3: Implement Kitsu adapter**

~~~kotlin
class KitsuIntegrationProvider(
    private val delegate: KitsuCatalogProvider,
) : SearchProvider, DiscoveryProvider, MetadataProvider {
    override val integrationId = IntegrationId("kitsu")

    override suspend fun search(query: CatalogQuery) = delegate.search(query)
    override suspend fun trending(offset: Int, limit: Int) = delegate.getTrending(offset, limit)
    override suspend fun popular(offset: Int, limit: Int) = delegate.getPopular(offset, limit)
    override suspend fun recentlyUpdated(offset: Int, limit: Int) =
        delegate.search(CatalogQuery(sort = CatalogSort.UPDATED_DESC, offset = offset, limit = limit))

    override suspend fun getDetails(externalId: String) = delegate.getDetails(externalId)
}
~~~

Do not implement `ChapterEvidenceProvider` from Kitsu unless the actual Kitsu adapter has a verified list of chapter identities, not merely a count.

- [ ] **Step 4: Replace direct catalog interactor dependency with registry-based provider selection tests**

Do not delete the old `CatalogProvider` yet; keep it as compatibility until Task A4 moves callers.

- [ ] **Step 5: Run app/domain tests and commit**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*KitsuIntegrationProviderTest' \
  :domain:testDebugUnitTest --tests '*SearchCatalogTest' \
  :domain:testDebugUnitTest --tests '*GetDiscoverFeedTest' \
  spotlessCheck
git add data/src/main/java/tachiyomi/data/tsuzuki/kitsu \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration \
  domain/src/test/java/tachiyomi/domain/tsuzuki/catalog
git commit -m "refactor(tsuzuki): expose Kitsu as integration capabilities"
~~~

---

### Task A3: Expose MAL metadata/search/ratings behind Integration capabilities

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListApi.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration/MalIntegrationProvider.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/model/ExternalRating.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/MalIntegrationProviderTest.kt`

**Interfaces:**
- Produces: MAL `SearchProvider`, `MetadataProvider`, `RatingsProvider`; existing tracking remains available through Mihon tracker compatibility until a dedicated tracking capability adapter is introduced.
- Consumes: `MyAnimeListApi.search`, `getMangaDetails`, MAL DTOs.

- [ ] **Step 1: Write failing tests for MAL mapping**

~~~kotlin
@Test
fun `mal result preserves MAL external identity and score provenance`() = runTest {
    val provider = MalIntegrationProvider(fakeMalApi(mean = 8.72))

    val page = provider.search(CatalogQuery(query = "Monster")).getOrThrow()
    val ratings = provider.ratings(page.items.single().providerId).getOrThrow()

    assertEquals("mal", page.items.single().provider)
    assertEquals("MAL", ratings.single().label)
    assertEquals(8.72, ratings.single().value)
}
~~~

- [ ] **Step 2: Make `MyAnimeListApi` injectable/testable without changing tracker semantics**

Keep OAuth/token behavior intact. Extract only the minimum constructor/interface seam needed for the Integration adapter.

- [ ] **Step 3: Implement adapter**

~~~kotlin
class MalIntegrationProvider(
    private val api: MyAnimeListApi,
) : SearchProvider, MetadataProvider, RatingsProvider, TrackingProvider {
    override val integrationId = IntegrationId("mal")
    // Search/metadata map TrackSearch/MAL details into CatalogItem with provider="mal".
    // TrackingProvider delegates normalized TrackingUpdate to the existing MAL tracker API.
}
~~~

MAL `num_chapters` maps only to `CatalogItem.chapterCount`.

- [ ] **Step 4: Run tests**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*MalIntegrationProviderTest' spotlessCheck
~~~

- [ ] **Step 5: Commit**

~~~bash
git add app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist \
  app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration \
  domain/src/main/java/tachiyomi/domain/tsuzuki/integration/model \
  app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration
git commit -m "feat(tsuzuki): add MAL integration capabilities"
~~~

---

### Task A4: Aggregate Search across enabled Integrations and reconcile canonical identity safely

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrations.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/ReconcileExternalSearchCandidate.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MergeCanonicalTitles.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleMergeRepository.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/interactor/MaterializeCanonicalTitleFromCatalog.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/repository/CanonicalTitleRepository.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleMergeRepositoryImpl.kt`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_titles.sq`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_external_identities.sq`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_entries.sq`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_library_categories.sq`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_source_mappings.sq`
- Modify: `data/src/main/sqldelight/tachiyomi/data/tsuzuki_canonical_chapters.sq`
- Modify: foundation-owned `tsuzuki_chapter_evidence.sq`, `tsuzuki_content_bindings.sq`, `tsuzuki_content_preferences.sq`, `tsuzuki_chapter_update_state.sq`, `tsuzuki_continue_reading_state.sq`, and `tsuzuki_canonical_downloads.sq`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/catalog/interactor/SearchIntegrationsTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/interactor/ReconcileExternalSearchCandidateTest.kt`
- Test: `data/src/test/java/tachiyomi/data/tsuzuki/CanonicalTitleMergeRepositoryImplTest.kt`

**Interfaces:**
- Produces: one aggregated Search result stream/page for UI plus an atomic local canonical-title rekey/merge primitive that Dev C can invoke when the Supabase identity-claim RPC chooses another canonical ID.
- Consumes: `IntegrationRegistry.searchProviders()`, external identity repository lookups.

- [ ] **Step 1: Write three identity regression tests**

~~~kotlin
@Test
fun `equal title without verified identity stays separate`() = runTest {
    val results = reconcile(
        kitsuItem(id = "1", title = "Same"),
        malItem(id = "2", title = "Same"),
        verifiedLinks = emptySet(),
    )
    assertEquals(2, results.size)
}

@Test
fun `existing external identity resolves to existing canonical title`() = runTest {
    repository.addExternalIdentity(ExternalIdentity("canon-1", "kitsu", "1", true, 1L))
    val resolved = reconciler.execute(kitsuItem(id = "1", title = "Dandadan"))
    assertEquals("canon-1", resolved.canonicalTitleId)
}

@Test
fun `two concurrent materializations of same verified identity converge`() = runTest {
    val first = async { reconciler.execute(kitsuItem(id = "1", title = "Dandadan")) }
    val second = async { reconciler.execute(kitsuItem(id = "1", title = "Dandadan")) }

    val ids = listOf(first.await().canonicalTitleId, second.await().canonicalTitleId)
    assertEquals(1, ids.distinct().size)
    assertEquals(ids.single(), repository.getByExternalIdentity("kitsu", "1")?.id)
}
~~~

- [ ] **Step 2: Run tests and confirm current single-provider/title-centric path fails**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*SearchIntegrationsTest' \
  :domain:testDebugUnitTest --tests '*ReconcileExternalSearchCandidateTest'
~~~

- [ ] **Step 3: Implement bounded concurrent provider search**

~~~kotlin
class SearchIntegrations(
    private val registry: IntegrationRegistry,
) {
    suspend fun execute(query: CatalogQuery): List<CatalogItem> = coroutineScope {
        registry.searchProviders()
            .map { provider -> async { provider.search(query).getOrElse { CatalogPage(emptyList(), false) } } }
            .awaitAll()
            .flatMap { it.items }
    }
}
~~~

Preserve provider/external IDs on every item. One provider failure must not erase successful results from another provider.

- [ ] **Step 4: Implement canonical reconciliation rules**

Priority:
1. exact existing `provider + externalId`;
2. explicit/verified cross-provider identity link when present;
3. otherwise keep result unresolved/distinct.

Do not introduce title-only merge.

- [ ] **Step 5: Write the atomic canonical merge regression**

~~~kotlin
@Test
fun `verified duplicate title can be rekeyed without losing canonical user state`() = runTest {
    seedCanonicalTitle("winner")
    seedCanonicalTitle("duplicate")
    seedLibraryEntry("duplicate")
    seedExternalIdentity("duplicate", provider = "kitsu", externalId = "1")
    seedCanonicalChapter(titleId = "duplicate", chapterId = "chapter-37")
    seedChapterProgress(chapterId = "chapter-37", page = 12)
    seedContentPreference(titleId = "duplicate", addonId = "mangadex")

    mergeRepository.convergeTo(targetId = "winner", localId = "duplicate")

    assertNull(titleRepository.getById("duplicate"))
    assertNotNull(titleRepository.getById("winner"))
    assertEquals("winner", titleRepository.getByExternalIdentity("kitsu", "1")?.id)
    assertEquals("winner", libraryRepository.get("winner")?.canonicalTitleId)
    assertEquals("winner", chapterRepository.getById("chapter-37")?.canonicalTitleId)
    assertEquals(12L, readingRepository.getProgress("chapter-37")?.lastPageRead)
    assertEquals(AddonId("mangadex"), contentPreferenceRepository.get("winner")?.preferredAddonId)
}
~~~

- [ ] **Step 6: Implement `MergeCanonicalTitles` as one SQLDelight transaction**

The convergence primitive is used **only** when equivalence is already proven by a verified external-identity claim. It never decides equivalence itself.

Within one transaction:
1. reject `targetId == localId`;
2. require the local CanonicalTitle to exist;
3. if `targetId` does not exist locally, create the target title row from the local title, repoint all dependent rows, then delete `localId`; this is the normal cross-device rekey path;
4. if both IDs exist locally, move every external identity from local to target, collapsing identical `provider + externalId` rows;
5. union categories and preserve Library membership; if both titles have incompatible singleton values such as distinct non-default status or distinct preferred Add-on, abort the transaction with a typed `CanonicalTitleMergeConflict` instead of choosing by timestamp;
6. repoint source/content bindings, canonical chapters, chapter evidence, update state, Continue Reading suppression, and canonical-download provenance to the target;
7. preserve CanonicalChapter IDs, so chapter progress/history continue to reference the same chapter IDs;
8. when both titles already contain equivalent canonical chapters, invoke the chapter reconciler/override policy before removing one; ambiguous chapter pairs abort with a typed conflict;
9. delete the old CanonicalTitle only after all dependent rows are safely repointed.

Expose:

~~~kotlin
interface CanonicalTitleMergeRepository {
    suspend fun convergeTo(targetId: String, localId: String)
}

class MergeCanonicalTitles(
    private val repository: CanonicalTitleMergeRepository,
) {
    suspend fun execute(targetId: String, localId: String) =
        repository.convergeTo(targetId, localId)
}
~~~

This primitive is deliberately independent of Supabase. Dev C can invoke it after a cloud identity claim returns a different Tsuzuki canonical ID.

- [ ] **Step 7: Run tests, format, commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*SearchIntegrationsTest' \
  :domain:testDebugUnitTest --tests '*ReconcileExternalSearchCandidateTest' \
  :data:testDebugUnitTest --tests '*CanonicalTitleMergeRepositoryImplTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/catalog \
  domain/src/main/java/tachiyomi/domain/tsuzuki/interactor \
  domain/src/main/java/tachiyomi/domain/tsuzuki/repository \
  data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleRepositoryImpl.kt \
  data/src/main/java/tachiyomi/data/tsuzuki/CanonicalTitleMergeRepositoryImpl.kt \
  data/src/main/sqldelight/tachiyomi/data/tsuzuki*.sq \
  domain/src/test/java/tachiyomi/domain/tsuzuki \
  data/src/test/java/tachiyomi/data/tsuzuki/CanonicalTitleMergeRepositoryImplTest.kt
git commit -m "feat(tsuzuki): aggregate search and preserve canonical identity"
~~~

---

### Task A5: Persist and reconcile ChapterEvidence into canonical chapters

**Files:**
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ChapterEvidenceRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/chapter/ChapterEvidenceRepositoryImpl.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidence.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/repository/CanonicalChapterRepository.kt`
- Modify: `data/src/main/java/tachiyomi/data/tsuzuki/CanonicalChapterRepositoryImpl.kt` only for evidence-related query helpers; confirmation state was added in F1.
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/evidence/ReconcileChapterEvidenceTest.kt`

**Interfaces:**
- Produces: `ReconcileChapterEvidence.execute(canonicalTitleId, evidence)`.
- Consumes: `ParseCanonicalChapterLabel`, `CanonicalChapterRepository`, evidence repository.

- [ ] **Step 1: Write evidence behavior tests**

Include exact cases:

~~~kotlin
@Test
fun `addon evidence creates provisional chapter`() = runTest {
    reconciler.execute("title", listOf(addonEvidence(rawLabel = "Chapter 211")))
    val chapter = chapterRepository.getByCanonicalTitleId("title").single()
    assertEquals(CanonicalChapterConfirmation.PROVISIONAL, chapter.confirmation)
}

@Test
fun `editorial evidence promotes matching provisional chapter`() = runTest {
    reconciler.execute("title", listOf(addonEvidence(rawLabel = "Chapter 211")))
    val provisionalId = chapterRepository.getByCanonicalTitleId("title").single().id

    reconciler.execute("title", listOf(editorialEvidence(rawLabel = "Chapter 211")))
    val confirmed = chapterRepository.getByCanonicalTitleId("title").single()

    assertEquals(provisionalId, confirmed.id)
    assertEquals(CanonicalChapterConfirmation.CONFIRMED, confirmed.confirmation)
}

@Test fun `chapter count without evidence creates no rows`() = runTest {
    reconciler.execute("title", emptyList())
    assertTrue(chapterRepository.getByCanonicalTitleId("title").isEmpty())
}

@Test fun `ambiguous extra and decimal evidence remain separate when confidence is low`() = runTest {
    // 12.5 and "Extra 12" with low equivalence confidence -> two logical candidates or conflicted state.
}
~~~

- [ ] **Step 2: Run tests and confirm failure**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*ReconcileChapterEvidenceTest'
~~~

- [ ] **Step 3: Implement repository and deterministic reconciliation**

Rules:
- exact external evidence identity reuses its mapped canonical chapter;
- exact/high-confidence parsed identity may reuse an existing canonical chapter;
- `EDITORIAL` evidence produces/promotes `CONFIRMED`;
- `ADDON_PROVISIONAL` without editorial confirmation produces `PROVISIONAL`;
- incompatible high-confidence contenders mark `CONFLICTED`;
- low-confidence ambiguity never silently merges.

- [ ] **Step 4: Ensure provider disappearance is non-destructive**

Add a regression test that deleting/omitting evidence from one provider does not delete the CanonicalChapter or its progress.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*ReconcileChapterEvidenceTest' spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/chapter \
  data/src/main/java/tachiyomi/data/tsuzuki/chapter \
  data/src/main/java/tachiyomi/data/tsuzuki/CanonicalChapterRepositoryImpl.kt \
  domain/src/test/java/tachiyomi/domain/tsuzuki/chapter
git commit -m "feat(tsuzuki): make chapter graph evidence driven"
~~~

---

### Task A6: Replace source-inventory authority in the new canonical detail/update path

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/interactor/RefreshCanonicalChapters.kt`
- Modify: `domain/src/main/java/tachiyomi/domain/tsuzuki/library/interactor/RefreshLibraryTitleForUpdate.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidence.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/updates/interactor/RecordNewCanonicalChapters.kt`
- Create: `domain/src/main/java/tachiyomi/domain/tsuzuki/updates/repository/ChapterUpdateStateRepository.kt`
- Create: `data/src/main/java/tachiyomi/data/tsuzuki/updates/ChapterUpdateStateRepositoryImpl.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/chapter/evidence/RefreshChapterEvidenceTest.kt`
- Test: `domain/src/test/java/tachiyomi/domain/tsuzuki/updates/RecordNewCanonicalChaptersTest.kt`

**Interfaces:**
- Produces: Integration-led editorial refresh plus an entry point B can call with Add-on provisional evidence.
- Consumes: `IntegrationRegistry.chapterEvidenceProviders()`, `ReconcileChapterEvidence`.

- [ ] **Step 1: Write a failing test proving new detail refresh never requires a SourceTitleMapping**

~~~kotlin
@Test
fun `chapter evidence refresh works with zero source mappings`() = runTest {
    val result = refresh.execute("canonical-title")
    assertTrue(result.isSuccess)
    assertEquals(listOf("1", "2"), chapters().map { it.displayNumber })
}
~~~

- [ ] **Step 2: Implement `RefreshChapterEvidence`**

Query enabled Integration `ChapterEvidenceProvider` capabilities, isolate individual provider failures, combine evidence, reconcile once.

If none of the enabled Integrations provide actual chapter evidence, return success with the existing chapter graph unchanged.

- [ ] **Step 3: Demote `RefreshCanonicalChapters` to legacy compatibility**

It may continue to translate legacy source inventories into **ADDON_PROVISIONAL evidence**, but the new canonical detail/update flow must call `RefreshChapterEvidence`, not source inventory directly.

- [ ] **Step 4: Implement new-chapter observation**

When a new CanonicalChapter first appears for a Library title, insert unacknowledged update state.

Reading/acknowledgment behavior is consumed by Dev C Home and Dev B reader progress.

- [ ] **Step 5: Run tests and commit**

~~~bash
./gradlew :domain:testDebugUnitTest --tests '*RefreshChapterEvidenceTest' \
  :domain:testDebugUnitTest --tests '*RecordNewCanonicalChaptersTest' \
  spotlessCheck
git add domain/src/main/java/tachiyomi/domain/tsuzuki/chapter \
  domain/src/main/java/tachiyomi/domain/tsuzuki/library \
  domain/src/main/java/tachiyomi/domain/tsuzuki/updates \
  data/src/main/java/tachiyomi/data/tsuzuki/updates \
  domain/src/test/java/tachiyomi/domain/tsuzuki
git commit -m "refactor(tsuzuki): remove source inventory authority from new flow"
~~~

---

### Task A7: Build Search, canonical detail, and Integrations Settings surfaces

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/search/TsuzukiSearchTab.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/search/TsuzukiSearchScreenModel.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/search/TsuzukiSearchScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreen.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModel.kt`
- Create: `app/src/main/java/eu/kanade/presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt`
- Create: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiIntegrationsScreen.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/search/TsuzukiSearchScreenModelTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/detail/CanonicalTitleScreenModelTest.kt`

**Interfaces:**
- Produces: standalone screens Dev C can link from final shell.
- Consumes: `SearchIntegrations`, `IntegrationSettingsRepository`, canonical repositories, `RefreshChapterEvidence`.

- [ ] **Step 1: Write Search empty-state test**

~~~kotlin
@Test
fun `search requests integration setup when no search provider is enabled`() = runTest {
    val model = screenModel(registry = emptyRegistry())
    model.search("Dandadan")
    assertIs<SearchState.NeedsIntegration>(model.state.value)
}
~~~

- [ ] **Step 2: Write detail test for provisional indicator data**

~~~kotlin
@Test
fun `detail exposes provisional state without hiding chapter`() = runTest {
    repository.upsert(provisionalChapter("37"))
    val state = model("title").awaitLoaded()
    assertEquals(CanonicalChapterConfirmation.PROVISIONAL, state.chapters.single().confirmation)
}
~~~

- [ ] **Step 3: Implement Search UX**

Empty query:
- recent searches stored locally with a bounded list of 20 distinct queries, newest first;
- system Discover blocks from enabled DiscoveryProviders: Trending, Popular, and Recently Updated.

Those three Search Discover blocks exist once at least one matching Integration capability is enabled. Their ordering/customization may move into the Collections model later; they are not Home defaults and do not violate the empty-by-default Home rule.

Typed query:
- aggregate Integration search;
- show provider provenance only where needed for disambiguation;
- materialize canonical title only when opening/adding, not for every transient result.

No source/Add-on search UI appears here.

- [ ] **Step 4: Implement canonical detail**

Show:
- metadata;
- Library membership/status;
- canonical chapter list;
- read/download state;
- small provisional indicator.

Do not add source IDs, source ordering, per-title language, or Add-on configuration.

Chapter tap must call an injectable callback/route owned by Dev B after integration; until Dev B merge, expose `onOpenChapter(canonicalChapterId)` as the screen contract rather than implementing provider logic here.

- [ ] **Step 5: Implement Integration Settings**

List Kitsu and MAL as disabled by default and expose capability/config/auth state.

This screen does not own Supabase account state.

- [ ] **Step 6: Run tests and commit**

~~~bash
./gradlew :app:testDebugUnitTest --tests '*TsuzukiSearchScreenModelTest' \
  :app:testDebugUnitTest --tests '*CanonicalTitleScreenModelTest' \
  :app:compileDebugKotlin spotlessCheck
git add app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki \
  app/src/main/java/eu/kanade/presentation/tsuzuki \
  app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiIntegrationsScreen.kt \
  app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki
git commit -m "feat(tsuzuki): add integration search and canonical detail"
~~~

---

### Task A8: Dev A integration verification and handoff

**Files:**
- Modify only tests/docs if verification reveals a Dev A-owned regression.
- Do not modify Dev B/C owned files.

**Interfaces:**
- Produces: stable branch for A+B integration.
- Consumes: all Dev A tasks.

- [ ] **Step 1: Run the Dev A acceptance suite**

~~~bash
./gradlew \
  :domain:testDebugUnitTest \
  :data:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:compileDebugKotlin \
  verifySqlDelightMigration \
  spotlessCheck
~~~

- [ ] **Step 2: Manually inspect the diff for forbidden coupling**

Verify no new dependency from:
- canonical title/chapter domain → concrete Kitsu/MAL class;
- Search → Mihon Source;
- chapter evidence → Reader;
- Integration Settings → Supabase account.

- [ ] **Step 3: Commit only verification fixes if needed**

Use a focused message such as:

~~~bash
git commit -m "test(tsuzuki): close integration evidence regressions [ci-full]"
~~~

- [ ] **Step 4: Push and require CI v2 full green**

- [ ] **Step 5: Produce handoff**

First run:

~~~bash
printf 'BASE_SHA=%s\n' "$(git merge-base tsuzuki/bootstrap HEAD)"
printf 'HEAD_SHA=%s\n' "$(git rev-parse HEAD)"
~~~

Then fill the evidence block from those command outputs and the accepted CI run:

~~~text
HANDOFF — DEV A
BASE_SHA: value printed by: git merge-base tsuzuki/bootstrap HEAD
HEAD_SHA: value printed by: git rev-parse HEAD
TASKS_COMPLETED: A1-A8
PUBLIC_INTERFACES_CHANGED: report "none" or enumerate only additive fields actually committed after foundation
MIGRATIONS: 30.sqm came from foundation only
CI_RUN: copy the exact GitHub Actions run ID from the accepted branch push
CI_RESULT: green
FILES_OTHER_DEVS_MAY_NOW_CONSUME:
- IntegrationRegistry
- SearchIntegrations
- ReconcileChapterEvidence
- RefreshChapterEvidence
- TsuzukiSearchTab
- CanonicalTitleScreen
- SettingsTsuzukiIntegrationsScreen
KNOWN_LIMITATIONS:
- Add-on content selection is supplied by Dev B
- primary shell wiring is supplied by Dev C
NEXT_DEPENDENCY: merge Dev A into runtime-v2 integration branch before Dev C final shell wiring
~~~
