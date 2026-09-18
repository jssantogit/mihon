package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
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

        val result = interactor.await(catalogItem)
        result.isSuccess shouldBe true
        val title = result.getOrThrow()

        // Spec Invariant: CanonicalTitle.id MUST NOT be the provider ID
        title.id shouldBe "uuid-12345"
        title.id shouldNotBe catalogItem.providerId
        title.displayTitle shouldBe "Vinland Saga"
        title.identityState shouldBe CanonicalIdentityState.RESOLVED

        // Spec Invariant: Kitsu ID is attached as ExternalIdentity
        repository.identities.size shouldBe 1
        val identity = repository.identities.first()
        identity.canonicalTitleId shouldBe "uuid-12345"
        identity.provider shouldBe "kitsu"
        identity.externalId shouldBe "999"
        identity.verified shouldBe true
    }

    @Test
    fun `invoke and execute match await delegation behavior`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "uuid-direct" },
            clock = { 5000L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(materializeCanonicalTitle)

        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "999",
            title = "Vinland Saga",
        )

        val invokedTitle = interactor(catalogItem)
        invokedTitle.id shouldBe "uuid-direct"
        invokedTitle.displayTitle shouldBe "Vinland Saga"

        val executedTitle = interactor.execute(catalogItem)
        executedTitle shouldBe invokedTitle
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

        val first = interactor.await(catalogItem).getOrThrow()
        val second = interactor.await(catalogItem).getOrThrow()

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

        val result = interactor.await(catalogItem).getOrThrow()

        result shouldBe winner
        repository.titles.keys shouldBe setOf("winner-id")
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
