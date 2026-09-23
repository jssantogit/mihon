package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MaterializeCanonicalTitleFromCatalogTest {

    @Test
    fun `catalogItem title and provider identity are forwarded with UUID not providerId`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "uuid-12345" },
            clock = { 5000L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "999",
            title = "Vinland Saga",
        )

        val title = interactor.execute(catalogItem)

        title.id shouldBe "uuid-12345"
        title.id shouldNotBe catalogItem.providerId
        title.displayTitle shouldBe "Vinland Saga"
        title.identityState shouldBe CanonicalIdentityState.RESOLVED

        repository.identities.size shouldBe 1
        val identity = repository.identities.first()
        identity.canonicalTitleId shouldBe "uuid-12345"
        identity.provider shouldBe "kitsu"
        identity.externalId shouldBe "999"
        identity.verified shouldBe true
    }

    @Test
    fun `catalog materialization caches provider chapter count without synthesizing chapters`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val counts = FakeReportedChapterCountRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "uuid-count" },
            clock = { 5000L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(
            materializeCanonicalTitle = materializeCanonicalTitle,
            reportedChapterCountRepository = counts,
            clock = { 6000L },
        )

        interactor.execute(
            CatalogItem(
                provider = "kitsu",
                providerId = "999",
                title = "Boku no Hero Academia",
                chapterCount = 430,
            ),
        )

        counts.values.single() shouldBe ReportedChapterCount(
            canonicalTitleId = "uuid-count",
            provider = "kitsu",
            chapterCount = 430,
            updatedAt = 6000L,
        )
    }

    @Test
    fun `repeated materialization of the same provider identity is idempotent`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "uuid-first" },
            clock = { 5000L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "999",
            title = "Vinland Saga",
        )

        val first = interactor.execute(catalogItem)
        val second = interactor.execute(catalogItem)

        first.id shouldBe "uuid-first"
        second.id shouldBe first.id
        repository.titles.size shouldBe 1
        repository.identities.size shouldBe 1
    }

    @Test
    fun `race and concurrent identity resolution converges via existing atomic foundation behavior`() = runTest {
        val winner = CanonicalTitle(
            id = "winner-id",
            displayTitle = "Vinland Saga",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 90L,
            updatedAt = 90L,
        )
        val repository = RacingCanonicalTitleRepository(winner)
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "candidate-id" },
            clock = { 100L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "999",
            title = "Vinland Saga",
        )

        val result = interactor.execute(catalogItem)

        result shouldBe winner
        repository.titles.keys shouldBe setOf("winner-id")
    }

    @Test
    fun `adapter delegates canonical persistence to existing materializer`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "uuid-delegated" },
            clock = { 6000L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)

        val result = interactor.execute(
            CatalogItem(
                provider = "kitsu",
                providerId = "321",
                title = "Monster",
            ),
        )

        result.id shouldBe "uuid-delegated"
        repository.titles.keys shouldBe setOf("uuid-delegated")
        repository.identities.single().externalId shouldBe "321"
    }

    private class FakeReportedChapterCountRepository : ReportedChapterCountRepository {
        val values = mutableListOf<ReportedChapterCount>()

        override suspend fun getByTitle(canonicalTitleId: String): List<ReportedChapterCount> =
            values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(value: ReportedChapterCount) {
            values.removeAll { it.canonicalTitleId == value.canonicalTitleId && it.provider == value.provider }
            values += value
        }
    }

    private class RacingCanonicalTitleRepository(
        private val winner: CanonicalTitle,
    ) : CanonicalTitleRepository {
        val titles = mutableMapOf(winner.id to winner)
        private var firstLookup = true

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(titles[id])

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            if (firstLookup) {
                firstLookup = false
                return null
            }
            return winner
        }

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = winner

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            error("Catalog materialization must use the atomic repository operation")
        }
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()
        val identities = mutableListOf<ExternalIdentity>()
        private val flow = MutableStateFlow<CanonicalTitle?>(null)

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flow

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
            insert(title)
            addExternalIdentity(identity)
            return title
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
            flow.value = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            identities += identity
        }
    }
}
