package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class ReconcileExternalSearchCandidateTest {

    @Test
    fun `existing provider external identity resolves to its canonical title`() = runTest {
        val existing = title("canon-1", "Dandadan")
        val repository = FakeCanonicalTitleRepository().apply {
            titles[existing.id] = existing
            identities += ExternalIdentity(
                canonicalTitleId = existing.id,
                provider = "kitsu",
                externalId = "1",
                verified = true,
                createdAt = 1L,
            )
        }
        val reconcile = ReconcileExternalSearchCandidate(repository)

        val resolved = reconcile.execute(CatalogItem("kitsu", "1", "Dandadan"))

        resolved?.id shouldBe "canon-1"
    }

    @Test
    fun `equal title without verified external identity does not resolve candidates together`() = runTest {
        val repository = FakeCanonicalTitleRepository().apply {
            titles["existing"] = title("existing", "Same")
        }
        val reconcile = ReconcileExternalSearchCandidate(repository)

        val kitsu = reconcile.execute(CatalogItem("kitsu", "1", "Same"))
        val mal = reconcile.execute(CatalogItem("mal", "2", "Same"))

        kitsu shouldBe null
        mal shouldBe null
    }

    @Test
    fun `cross-provider link resolves only after the external identity is explicitly attached`() = runTest {
        val canonical = title("canon-1", "Monster")
        val repository = FakeCanonicalTitleRepository().apply {
            titles[canonical.id] = canonical
            identities += ExternalIdentity(
                canonicalTitleId = canonical.id,
                provider = "kitsu",
                externalId = "10",
                verified = true,
                createdAt = 1L,
            )
        }
        val reconcile = ReconcileExternalSearchCandidate(repository)

        reconcile.execute(CatalogItem("mal", "20", "Monster")) shouldBe null

        repository.identities += ExternalIdentity(
            canonicalTitleId = canonical.id,
            provider = "mal",
            externalId = "20",
            verified = true,
            createdAt = 2L,
        )

        reconcile.execute(CatalogItem("mal", "20", "Monster"))?.id shouldBe "canon-1"
    }

    private fun title(id: String, displayTitle: String) = CanonicalTitle(
        id = id,
        displayTitle = displayTitle,
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()
        val identities = mutableListOf<ExternalIdentity>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            MutableStateFlow(titles[id])

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val canonicalTitleId = identities
                .firstOrNull { it.provider == provider && it.externalId == externalId }
                ?.canonicalTitleId
            return canonicalTitleId?.let(titles::get)
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
}
