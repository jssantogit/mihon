package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class AvailableSyncDocumentAdaptersTest {

    @Test
    fun `case 1 - library round trip carries title bootstrap metadata`() = runTest {
        val title = title("title-1", "Tsuzuki")
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = title.id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 10,
            updatedAt = 20,
        )
        val sourceTitles = mutableMapOf(title.id to title)
        val sourceLibrary = FakeLibraryRepository(
            entries = mutableMapOf(title.id to entry),
            titleLookup = sourceTitles,
        )
        val exported = libraryAdapter(
            sourceLibrary,
            FakeTitleRepository(sourceTitles),
        ).exportDocument()

        exported.kind shouldBe SyncDocumentKind.LIBRARY
        exported.records.keys.toList() shouldContainExactly listOf("title-1")

        val targetTitles = FakeTitleRepository()
        val targetLibrary = FakeLibraryRepository(titleLookup = targetTitles.titles)
        libraryAdapter(targetLibrary, targetTitles).applyDocument(exported)

        targetTitles.getById(title.id) shouldBe title
        targetLibrary.get(title.id) shouldBe entry
    }

    @Test
    fun `case 2 - library tombstone removes entry but keeps canonical title`() = runTest {
        val title = title("title-1", "Tsuzuki")
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = title.id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 10,
            updatedAt = 20,
        )
        val titles = FakeTitleRepository(mutableMapOf(title.id to title))
        val library = FakeLibraryRepository(
            entries = mutableMapOf(title.id to entry),
            titleLookup = titles.titles,
        )
        val active = libraryAdapter(library, titles).exportDocument()
        val tombstone = active.copy(
            records = active.records.mapValues { (_, record) ->
                record.copy(deletedAtEpochMillis = 30)
            },
        )

        libraryAdapter(library, titles).applyDocument(tombstone)

        library.get(title.id) shouldBe null
        titles.getById(title.id) shouldBe title
    }

    @Test
    fun `case 3 - source mapping export excludes local Mihon manga id and apply preserves target link`() = runTest {
        val remoteTitle = title("title-remote", "Tsuzuki")
        val sourceMappings = FakeMappingRepository(
            mutableListOf(
                mapping(
                    id = "phone-random-id",
                    canonicalTitleId = remoteTitle.id,
                    mihonMangaId = null,
                    updatedAt = 50,
                ),
            ),
        )
        val exported = sourceAdapter(
            sourceMappings,
            FakePreferenceRepository(),
            FakeTitleRepository(mutableMapOf(remoteTitle.id to remoteTitle)),
        ).exportDocument()
        val exportedRecord = exported.records.values.single()

        exportedRecord.id shouldBe sourceMappingSyncRecordId(10, "/manga/tsuzuki")
        ("mihonMangaId" in exportedRecord.fields) shouldBe false

        val localMappings = FakeMappingRepository(
            mutableListOf(
                mapping(
                    id = "tablet-random-id",
                    canonicalTitleId = "old-local-title",
                    mihonMangaId = 4242,
                    updatedAt = 10,
                ),
            ),
        )
        val localTitles = FakeTitleRepository(
            mutableMapOf("old-local-title" to title("old-local-title", "Old")),
        )

        sourceAdapter(
            localMappings,
            FakePreferenceRepository(),
            localTitles,
        ).applyDocument(exported)

        val applied = localMappings.getBySource(10, "/manga/tsuzuki")!!
        applied.id shouldBe "tablet-random-id"
        applied.mihonMangaId shouldBe 4242
        applied.canonicalTitleId shouldBe remoteTitle.id
        localTitles.getById(remoteTitle.id) shouldBe remoteTitle
    }

    @Test
    fun `case 4 - source preferences share source mappings document and preserve order`() = runTest {
        val sourcePreferences = FakePreferenceRepository(
            mutableMapOf("pt-BR" to mutableListOf(30L, 10L, 20L)),
        )
        val exported = sourceAdapter(
            FakeMappingRepository(),
            sourcePreferences,
            FakeTitleRepository(),
        ).exportDocument()

        val targetPreferences = FakePreferenceRepository()
        sourceAdapter(
            FakeMappingRepository(),
            targetPreferences,
            FakeTitleRepository(),
        ).applyDocument(exported)

        targetPreferences.getForLanguage("pt-BR").map { it.sourceId } shouldContainExactly
            listOf(30L, 10L, 20L)
    }

    private fun libraryAdapter(
        library: CanonicalLibraryRepository,
        titles: CanonicalTitleRepository,
    ) = CanonicalLibrarySyncAdapter(
        libraryRepository = library,
        titleRepository = titles,
        revisionSource = revisions(),
        clock = clock(),
    )

    private fun sourceAdapter(
        mappings: SourceTitleMappingRepository,
        preferences: ReadingSourcePreferenceRepository,
        titles: CanonicalTitleRepository,
    ) = SourceMappingsSyncAdapter(
        mappingRepository = mappings,
        preferenceRepository = preferences,
        titleRepository = titles,
        revisionSource = revisions(),
        clock = clock(),
    )

    private fun revisions() = object : SyncRevisionSource {
        private var sequence = 1L

        override fun nextRevision() = SyncRevision("test-device", sequence++)
    }

    private fun clock() = object : SyncClock {
        override fun nowEpochMillis(): Long = 100
    }

    private fun title(id: String, name: String) = CanonicalTitle(
        id = id,
        displayTitle = name,
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun mapping(
        id: String,
        canonicalTitleId: String,
        mihonMangaId: Long?,
        updatedAt: Long = 20,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = 10,
        sourceUrl = "/manga/tsuzuki",
        language = "en",
        matchConfidence = 0.99,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 10,
        updatedAt = updatedAt,
    )

    private class FakeLibraryRepository(
        private val entries: MutableMap<String, CanonicalLibraryEntry> = mutableMapOf(),
        private val titleLookup: MutableMap<String, CanonicalTitle> = mutableMapOf(),
    ) : CanonicalLibraryRepository {
        override suspend fun get(canonicalTitleId: String) = entries[canonicalTitleId]

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> =
            flowOf(entries.values.toList())

        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> =
            flowOf(
                entries.values.map { entry ->
                    CanonicalLibraryItem(
                        title = checkNotNull(titleLookup[entry.canonicalTitleId]),
                        entry = entry,
                    )
                },
            )

        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }

        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
        }
    }

    private class FakeTitleRepository(
        val titles: MutableMap<String, CanonicalTitle> = mutableMapOf(),
    ) : CanonicalTitleRepository {
        override suspend fun getById(id: String) = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            flowOf(titles[id])

        override suspend fun getByExternalIdentity(
            provider: String,
            externalId: String,
        ): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title.also { titles[it.id] = it }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun upsert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }

    private class FakeMappingRepository(
        private val mappings: MutableList<SourceTitleMapping> = mutableListOf(),
    ) : SourceTitleMappingRepository {
        override suspend fun getAll() = mappings.toList()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(
            canonicalTitleId: String,
        ): Flow<List<SourceTitleMapping>> =
            flowOf(mappings.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String) =
            mappings.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            val index = mappings.indexOfFirst { it.id == mapping.id }
            if (index >= 0) {
                mappings[index] = mapping
            } else {
                mappings += mapping
            }
        }

        override suspend fun remove(id: String) {
            mappings.removeAll { it.id == id }
        }

        override suspend fun setPreferredForTitle(
            canonicalTitleId: String,
            mappingId: String?,
            updatedAt: Long,
        ) = Unit
    }

    private class FakePreferenceRepository(
        private val values: MutableMap<String, MutableList<Long>> = mutableMapOf(),
    ) : ReadingSourcePreferenceRepository {
        override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> =
            values[language].orEmpty().mapIndexed { index, sourceId ->
                ReadingSourcePreference(language, sourceId, index)
            }

        override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> =
            flowOf(emptyList())

        override suspend fun getConfiguredLanguages(): List<String> =
            values.filterValues { it.isNotEmpty() }.keys.toList()

        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) {
            if (orderedSourceIds.isEmpty()) {
                values.remove(language)
            } else {
                values[language] = orderedSourceIds.toMutableList()
            }
        }
    }
}
