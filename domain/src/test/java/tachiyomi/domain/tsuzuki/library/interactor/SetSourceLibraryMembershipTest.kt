package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.model.SourceLibraryRepresentation
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

class SetSourceLibraryMembershipTest {

    @Test
    fun `adding source representation materializes canonical membership`() = runTest {
        val library = FakeCanonicalLibraryRepository()
        val mappings = FakeSourceTitleMappingRepository()
        val titles = FakeCanonicalTitleRepository()
        val interactor = SetSourceLibraryMembership(
            canonicalLibraryRepository = library,
            sourceTitleMappingRepository = mappings,
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = titles,
                idFactory = { "canonical-1" },
                clock = { 1000L },
            ),
            mappingIdFactory = { "mapping-1" },
            clock = { 2000L },
        )

        interactor.add(
            SourceLibraryRepresentation(
                mihonMangaId = 42L,
                sourceId = 7L,
                sourceUrl = "/title",
                language = "en",
                sourceAvailable = true,
                displayTitle = "Title",
                dateAdded = 1500L,
                hasStarted = true,
            ),
        )

        library.entries["canonical-1"] shouldBe CanonicalLibraryEntry(
            canonicalTitleId = "canonical-1",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1500L,
            updatedAt = 2000L,
        )
        mappings.bySource(7L, "/title") shouldBe SourceTitleMapping(
            id = "mapping-1",
            canonicalTitleId = "canonical-1",
            mihonMangaId = 42L,
            sourceId = 7L,
            sourceUrl = "/title",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 2000L,
            updatedAt = 2000L,
        )
    }

    @Test
    fun `adding existing mapping preserves canonical identity and library status`() = runTest {
        val library = FakeCanonicalLibraryRepository().apply {
            entries["canonical-existing"] = CanonicalLibraryEntry(
                canonicalTitleId = "canonical-existing",
                status = LibraryStatus.COMPLETED,
                favorite = true,
                addedAt = 500L,
                updatedAt = 600L,
            )
        }
        val mappings = FakeSourceTitleMappingRepository().apply {
            stored += SourceTitleMapping(
                id = "mapping-existing",
                canonicalTitleId = "canonical-existing",
                mihonMangaId = null,
                sourceId = 7L,
                sourceUrl = "/title",
                language = "en",
                matchConfidence = 0.8,
                verifiedByUser = true,
                availability = SourceMappingAvailability.UNKNOWN,
                preferredOverride = true,
                createdAt = 300L,
                updatedAt = 400L,
            )
        }
        val interactor = SetSourceLibraryMembership(
            canonicalLibraryRepository = library,
            sourceTitleMappingRepository = mappings,
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = FakeCanonicalTitleRepository(),
                idFactory = { error("must not create title") },
                clock = { 1000L },
            ),
            mappingIdFactory = { error("must not create mapping") },
            clock = { 2000L },
        )

        interactor.add(
            SourceLibraryRepresentation(
                mihonMangaId = 99L,
                sourceId = 7L,
                sourceUrl = "/title",
                language = "pt-BR",
                sourceAvailable = true,
                displayTitle = "Updated title",
                dateAdded = 1500L,
                hasStarted = true,
            ),
        )

        library.entries["canonical-existing"]?.status shouldBe LibraryStatus.COMPLETED
        library.entries["canonical-existing"]?.addedAt shouldBe 500L
        mappings.bySource(7L, "/title")?.mihonMangaId shouldBe 99L
        mappings.bySource(7L, "/title")?.preferredOverride shouldBe true
    }

    @Test
    fun `removing source representation removes canonical membership but keeps mapping`() = runTest {
        val library = FakeCanonicalLibraryRepository().apply {
            entries["canonical-1"] = CanonicalLibraryEntry(
                canonicalTitleId = "canonical-1",
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 100L,
                updatedAt = 100L,
            )
        }
        val mapping = SourceTitleMapping(
            id = "mapping-1",
            canonicalTitleId = "canonical-1",
            mihonMangaId = 42L,
            sourceId = 7L,
            sourceUrl = "/title",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 100L,
            updatedAt = 100L,
        )
        val mappings = FakeSourceTitleMappingRepository().apply { stored += mapping }
        val interactor = SetSourceLibraryMembership(
            canonicalLibraryRepository = library,
            sourceTitleMappingRepository = mappings,
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = FakeCanonicalTitleRepository(),
                idFactory = { error("unused") },
                clock = { 1000L },
            ),
            mappingIdFactory = { error("unused") },
            clock = { 2000L },
        )

        interactor.remove(sourceId = 7L, sourceUrl = "/title")

        library.entries.containsKey("canonical-1") shouldBe false
        mappings.bySource(7L, "/title") shouldBe mapping
    }

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val entries = mutableMapOf<String, CanonicalLibraryEntry>()

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = entries[canonicalTitleId]
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(entries.values.toList())
        override fun getAllItemsAsFlow() = MutableStateFlow(
            emptyList<tachiyomi.domain.tsuzuki.library.model.LibraryTitle>(),
        )
        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }
        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
        }
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val stored = mutableListOf<SourceTitleMapping>()

        fun bySource(sourceId: Long, sourceUrl: String) =
            stored.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun getAll(): List<SourceTitleMapping> = stored.toList()
        override fun getAllAsFlow(): Flow<List<SourceTitleMapping>> = MutableStateFlow(stored.toList())
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            stored.filter { it.canonicalTitleId == canonicalTitleId }
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String) =
            MutableStateFlow(stored.filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getBySource(sourceId: Long, sourceUrl: String) = bySource(sourceId, sourceUrl)
        override suspend fun upsert(mapping: SourceTitleMapping) {
            stored.removeAll { it.id == mapping.id }
            stored += mapping
        }
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(titles[id])
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(title: CanonicalTitle, identity: ExternalIdentity) = title
        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }
}
