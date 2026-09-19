package tachiyomi.domain.tsuzuki.migration

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.migration.model.MihonLibrarySnapshot
import tachiyomi.domain.tsuzuki.migration.service.MihonLibraryGateway
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

class MigrateMihonLibraryToCanonicalTest {

    @Test
    fun `unmapped favorite creates SOURCE_ONLY title, canonical library entry, and source mapping`() = runTest {
        val fixture = TestFixture()
        val snapshot = MihonLibrarySnapshot(
            mihonMangaId = 1L,
            sourceId = 100L,
            sourceUrl = "/manga/1",
            sourceLanguage = "en",
            sourceAvailable = true,
            title = "Chainsaw Man",
            dateAdded = 500L,
            hasStarted = false,
        )
        fixture.gateway.snapshots = listOf(snapshot)

        val report = fixture.interactor.execute()

        report.totalProcessed shouldBe 1
        report.newlyImported shouldBe 1
        report.alreadyMapped shouldBe 0

        fixture.titleRepository.titles.size shouldBe 1
        val title = fixture.titleRepository.titles.values.first()
        title.displayTitle shouldBe "Chainsaw Man"
        title.identityState shouldBe CanonicalIdentityState.SOURCE_ONLY

        val mapping = fixture.mappingRepository.getBySource(100L, "/manga/1")
        mapping shouldBe SourceTitleMapping(
            id = "mapping-1",
            canonicalTitleId = title.id,
            mihonMangaId = 1L,
            sourceId = 100L,
            sourceUrl = "/manga/1",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        val entry = fixture.libraryRepository.get(title.id)
        entry shouldBe CanonicalLibraryEntry(
            canonicalTitleId = title.id,
            status = LibraryStatus.PLANNING,
            favorite = true,
            addedAt = 500L,
            updatedAt = 1000L,
        )
    }

    @Test
    fun `rerun is idempotent and does not create duplicate titles or mappings`() = runTest {
        val fixture = TestFixture()
        val snapshot = MihonLibrarySnapshot(
            mihonMangaId = 1L,
            sourceId = 100L,
            sourceUrl = "/manga/1",
            sourceLanguage = "en",
            sourceAvailable = true,
            title = "Chainsaw Man",
            dateAdded = 500L,
            hasStarted = true,
        )
        fixture.gateway.snapshots = listOf(snapshot)

        val firstReport = fixture.interactor.execute()
        firstReport.newlyImported shouldBe 1
        firstReport.alreadyMapped shouldBe 0

        val secondReport = fixture.interactor.execute()
        secondReport.totalProcessed shouldBe 1
        secondReport.newlyImported shouldBe 0
        secondReport.alreadyMapped shouldBe 1

        fixture.titleRepository.titles.size shouldBe 1
        fixture.mappingRepository.mappings.size shouldBe 1
        fixture.libraryRepository.entries.size shouldBe 1
    }

    @Test
    fun `existing source mapping is reused without creating a new canonical title`() = runTest {
        val fixture = TestFixture()
        val preExistingTitle = CanonicalTitle(
            id = "existing-title-id",
            displayTitle = "Chainsaw Man",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 100L,
            updatedAt = 100L,
        )
        fixture.titleRepository.titles[preExistingTitle.id] = preExistingTitle
        val preExistingMapping = SourceTitleMapping(
            id = "existing-mapping-id",
            canonicalTitleId = preExistingTitle.id,
            mihonMangaId = 1L,
            sourceId = 100L,
            sourceUrl = "/manga/1",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 100L,
            updatedAt = 100L,
        )
        fixture.mappingRepository.upsert(preExistingMapping)

        fixture.gateway.snapshots = listOf(
            MihonLibrarySnapshot(
                mihonMangaId = 1L,
                sourceId = 100L,
                sourceUrl = "/manga/1",
                sourceLanguage = "en",
                sourceAvailable = true,
                title = "Chainsaw Man",
                dateAdded = 500L,
                hasStarted = false,
            ),
        )

        val report = fixture.interactor.execute()
        report.newlyImported shouldBe 0
        report.alreadyMapped shouldBe 1
        fixture.titleRepository.titles.size shouldBe 1
        fixture.titleRepository.titles.containsKey("existing-title-id") shouldBe true
    }

    @Test
    fun `unavailable extension marks mapping availability UNKNOWN`() = runTest {
        val fixture = TestFixture()
        fixture.gateway.snapshots = listOf(
            MihonLibrarySnapshot(
                mihonMangaId = 2L,
                sourceId = 200L,
                sourceUrl = "/manga/2",
                sourceLanguage = "ja",
                sourceAvailable = false,
                title = "Unknown Manga",
                dateAdded = 300L,
                hasStarted = false,
            ),
        )

        fixture.interactor.execute()

        val mapping = fixture.mappingRepository.getBySource(200L, "/manga/2")
        mapping?.availability shouldBe SourceMappingAvailability.UNKNOWN
    }

    @Test
    fun `started manga is imported with READING status`() = runTest {
        val fixture = TestFixture()
        fixture.gateway.snapshots = listOf(
            MihonLibrarySnapshot(
                mihonMangaId = 3L,
                sourceId = 300L,
                sourceUrl = "/manga/3",
                sourceLanguage = "en",
                sourceAvailable = true,
                title = "One Piece",
                dateAdded = 100L,
                hasStarted = true,
            ),
        )

        fixture.interactor.execute()

        val title = fixture.titleRepository.titles.values.first()
        val entry = fixture.libraryRepository.get(title.id)
        entry?.status shouldBe LibraryStatus.READING
    }

    @Test
    fun `unstarted manga is imported with PLANNING status and never COMPLETED`() = runTest {
        val fixture = TestFixture()
        fixture.gateway.snapshots = listOf(
            MihonLibrarySnapshot(
                mihonMangaId = 4L,
                sourceId = 400L,
                sourceUrl = "/manga/4",
                sourceLanguage = "en",
                sourceAvailable = true,
                title = "Bleach",
                dateAdded = 200L,
                hasStarted = false,
            ),
        )

        fixture.interactor.execute()

        val title = fixture.titleRepository.titles.values.first()
        val entry = fixture.libraryRepository.get(title.id)
        entry?.status shouldBe LibraryStatus.PLANNING
        entry?.status shouldBe LibraryStatus.PLANNING // definitely not COMPLETED
    }

    @Test
    fun `preserves existing canonical library status and addedAt if already present`() = runTest {
        val fixture = TestFixture()
        val title = CanonicalTitle(
            id = "existing-title",
            displayTitle = "Naruto",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = 50L,
            updatedAt = 50L,
        )
        fixture.titleRepository.titles[title.id] = title
        val existingEntry = CanonicalLibraryEntry(
            canonicalTitleId = title.id,
            status = LibraryStatus.COMPLETED,
            favorite = true,
            addedAt = 77L,
            updatedAt = 88L,
        )
        fixture.libraryRepository.upsert(existingEntry)

        val mapping = SourceTitleMapping(
            id = "m-1",
            canonicalTitleId = title.id,
            mihonMangaId = 5L,
            sourceId = 500L,
            sourceUrl = "/manga/5",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 50L,
            updatedAt = 50L,
        )
        fixture.mappingRepository.upsert(mapping)

        fixture.gateway.snapshots = listOf(
            MihonLibrarySnapshot(
                mihonMangaId = 5L,
                sourceId = 500L,
                sourceUrl = "/manga/5",
                sourceLanguage = "en",
                sourceAvailable = true,
                title = "Naruto",
                dateAdded = 999L,
                hasStarted = true,
            ),
        )

        fixture.interactor.execute()

        val updatedEntry = fixture.libraryRepository.get(title.id)
        updatedEntry?.status shouldBe LibraryStatus.COMPLETED
        updatedEntry?.addedAt shouldBe 77L
    }

    private class TestFixture {
        val gateway = FakeMihonLibraryGateway()
        val mappingRepository = FakeSourceTitleMappingRepository()
        val titleRepository = FakeCanonicalTitleRepository()
        val libraryRepository = FakeCanonicalLibraryRepository()

        private var titleCounter = 0
        private var mappingCounter = 0
        val interactor = MigrateMihonLibraryToCanonical(
            gateway = gateway,
            sourceTitleMappingRepository = mappingRepository,
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = titleRepository,
                idFactory = { "title-${++titleCounter}" },
                clock = { 1000L },
            ),
            canonicalLibraryRepository = libraryRepository,
            idFactory = { "mapping-${++mappingCounter}" },
            clock = { 1000L },
        )
    }

    private class FakeMihonLibraryGateway : MihonLibraryGateway {
        var snapshots: List<MihonLibrarySnapshot> = emptyList()
        override suspend fun snapshot(): List<MihonLibrarySnapshot> = snapshots
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = mutableMapOf<String, SourceTitleMapping>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> {
            return mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> {
            return MutableStateFlow(mappings.values.filter { it.canonicalTitleId == canonicalTitleId })
        }

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? {
            return mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {
            if (mappingId != null) {
                val target = mappings[mappingId] ?: throw IllegalArgumentException("Mapping not found")
                require(target.canonicalTitleId == canonicalTitleId) { "Cross title" }
            }
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }.forEach {
                mappings[it.id] = it.copy(
                    preferredOverride = it.id == mappingId,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(titles[id])

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            return titles.getOrPut(title.id) { title }
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {}
    }

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val entries = mutableMapOf<String, CanonicalLibraryEntry>()

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = entries[canonicalTitleId]

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(entries.values.toList())

        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> = MutableStateFlow(emptyList())

        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }

        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
        }
    }
}
