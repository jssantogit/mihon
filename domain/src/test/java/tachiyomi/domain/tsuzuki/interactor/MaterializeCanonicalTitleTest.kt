package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MaterializeCanonicalTitleTest {

    @Test
    fun `catalog materialization reuses title with same provider identity`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val interactor = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "generated-id" },
            clock = { 100L },
        )

        val first = interactor.fromCatalog(
            displayTitle = "Berserk",
            provider = "kitsu",
            externalId = "123",
        )
        val second = interactor.fromCatalog(
            displayTitle = "Berserk",
            provider = "kitsu",
            externalId = "123",
        )

        first.id shouldBe "generated-id"
        second.id shouldBe first.id
        repository.titles.size shouldBe 1
    }

    @Test
    fun `catalog materialization recovers when external identity is claimed concurrently`() = runTest {
        val winner = CanonicalTitle(
            id = "winner-id",
            displayTitle = "Berserk",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 90L,
            updatedAt = 90L,
        )
        val repository = RacingCanonicalTitleRepository(winner)
        val interactor = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "candidate-id" },
            clock = { 100L },
        )

        val result = interactor.fromCatalog(
            displayTitle = "Berserk",
            provider = "kitsu",
            externalId = "123",
        )

        result shouldBe winner
        repository.titles.keys shouldBe setOf("winner-id")
    }

    @Test
    fun `source materialization creates source-only identity without provider id`() = runTest {
        val repository = FakeCanonicalTitleRepository()
        val interactor = MaterializeCanonicalTitle(
            repository = repository,
            idFactory = { "source-id" },
            clock = { 100L },
        )

        val title = interactor.fromSource("Obscure Manga")

        title.id shouldBe "source-id"
        title.identityState shouldBe CanonicalIdentityState.SOURCE_ONLY
        repository.identities.size shouldBe 0
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
