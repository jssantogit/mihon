package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class AddCatalogItemToLibraryTest {

    @Test
    fun `catalog item materializes through the existing canonical path before membership is written`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository()
        val libraryRepository = FakeCanonicalLibraryRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = titleRepository,
            idFactory = { "canonical-123" },
            clock = { 1000L },
        )
        val materializeFromCatalog = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)
        val interactor = AddCatalogItemToLibrary(
            materializeCanonicalTitleFromCatalog = materializeFromCatalog,
            canonicalLibraryRepository = libraryRepository,
            clock = { 1000L },
        )

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "kitsu-456",
            title = "Attack on Titan",
        )

        val result = interactor.execute(catalogItem)

        // Title was materialized via canonical path
        result.title.id shouldBe "canonical-123"
        result.title.displayTitle shouldBe "Attack on Titan"
        titleRepository.titles["canonical-123"] shouldNotBe null

        // Library entry is created
        result.entry.canonicalTitleId shouldBe "canonical-123"
        result.entry.status shouldBe LibraryStatus.PLANNING
        result.entry.favorite shouldBe true
        result.entry.addedAt shouldBe 1000L
        libraryRepository.get("canonical-123") shouldBe result.entry
    }

    @Test
    fun `provider ID is not the canonical ID`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository()
        val libraryRepository = FakeCanonicalLibraryRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = titleRepository,
            idFactory = { "uuid-not-provider-id" },
            clock = { 2000L },
        )
        val materializeFromCatalog = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)
        val interactor = AddCatalogItemToLibrary(
            materializeCanonicalTitleFromCatalog = materializeFromCatalog,
            canonicalLibraryRepository = libraryRepository,
            clock = { 2000L },
        )

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "provider-id-999",
            title = "Berserk",
        )

        val result = interactor.execute(catalogItem)

        result.title.id shouldNotBe "provider-id-999"
        result.entry.canonicalTitleId shouldNotBe "provider-id-999"
        result.title.id shouldBe "uuid-not-provider-id"
        result.entry.canonicalTitleId shouldBe "uuid-not-provider-id"
    }

    @Test
    fun `default status is PLANNING and custom status is respected`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository()
        val libraryRepository = FakeCanonicalLibraryRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = titleRepository,
            idFactory = { "title-${System.nanoTime()}" },
            clock = { 3000L },
        )
        val materializeFromCatalog = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)
        val interactor = AddCatalogItemToLibrary(
            materializeCanonicalTitleFromCatalog = materializeFromCatalog,
            canonicalLibraryRepository = libraryRepository,
            clock = { 3000L },
        )

        val defaultItem = CatalogItem(
            provider = "kitsu",
            providerId = "item-1",
            title = "Default Title",
        )
        val defaultResult = interactor.execute(defaultItem)
        defaultResult.entry.status shouldBe LibraryStatus.PLANNING

        val readingItem = CatalogItem(
            provider = "kitsu",
            providerId = "item-2",
            title = "Reading Title",
        )
        val readingResult = interactor.execute(readingItem, status = LibraryStatus.READING)
        readingResult.entry.status shouldBe LibraryStatus.READING
    }

    @Test
    fun `adding the same catalog identity twice is idempotent and preserves original addedAt and status`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository()
        val libraryRepository = FakeCanonicalLibraryRepository()
        var currentClock = 1000L
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = titleRepository,
            idFactory = { "uuid-first" },
            clock = { currentClock },
        )
        val materializeFromCatalog = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)
        val interactor = AddCatalogItemToLibrary(
            materializeCanonicalTitleFromCatalog = materializeFromCatalog,
            canonicalLibraryRepository = libraryRepository,
            clock = { currentClock },
        )

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "item-same",
            title = "Monster",
        )

        val first = interactor.execute(catalogItem, status = LibraryStatus.READING)
        first.entry.addedAt shouldBe 1000L
        first.entry.status shouldBe LibraryStatus.READING

        // Advance clock and attempt second add with different status
        currentClock = 5000L
        val second = interactor.execute(catalogItem, status = LibraryStatus.COMPLETED)

        second.title.id shouldBe first.title.id
        second.entry.addedAt shouldBe 1000L
        second.entry.status shouldBe LibraryStatus.READING
        libraryRepository.entries.size shouldBe 1
    }

    @Test
    fun `adding to Library never touches SourceTitleMappingRepository and requires no reading source`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository()
        val libraryRepository = FakeCanonicalLibraryRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = titleRepository,
            idFactory = { "canonical-sourceless" },
            clock = { 5000L },
        )
        val materializeFromCatalog = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)
        val interactor = AddCatalogItemToLibrary(
            materializeCanonicalTitleFromCatalog = materializeFromCatalog,
            canonicalLibraryRepository = libraryRepository,
            clock = { 5000L },
        )

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "kitsu-no-source",
            title = "No Source Title",
        )

        val result = interactor.execute(catalogItem)

        result.primaryMapping shouldBe null
        result.entry.canonicalTitleId shouldBe "canonical-sourceless"
        libraryRepository.get("canonical-sourceless") shouldNotBe null
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()
        val identities = mutableListOf<ExternalIdentity>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(titles[id])

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val titleId = identities
                .firstOrNull { it.provider == provider && it.externalId == externalId }
                ?.canonicalTitleId
            return titleId?.let(titles::get)
        }

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            titles[title.id] = title
            identities += identity
            return title
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            identities += identity
        }
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
