package tachiyomi.domain.tsuzuki.integration.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.CapabilityPolicy
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
import tachiyomi.domain.tsuzuki.integration.model.IntegrationPolicy
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class ResolveCanonicalMetadataTest {

    @Test
    fun `field precedence is deterministic and provider specific`() = runTest {
        val repository = FakeCanonicalTitleRepository(
            identities = listOf(
                identity("kitsu", "k1"),
                identity("mal", "m1"),
            ),
        )
        val kitsu = FakeMetadataProvider(
            "kitsu",
            CatalogItem(
                provider = "kitsu",
                providerId = "k1",
                title = "Kitsu title",
                synopsis = "Kitsu synopsis",
                coverUrl = "kitsu-cover",
                score = CatalogScore("kitsu", 8.0, 10.0),
            ),
        )
        val mal = FakeMetadataProvider(
            "mal",
            CatalogItem(
                provider = "mal",
                providerId = "m1",
                title = "MAL title",
                synopsis = "MAL synopsis",
                coverUrl = "mal-cover",
                score = CatalogScore("mal", 9.0, 10.0),
            ),
        )
        val registry = FakeRegistry(listOf(kitsu, mal))

        val resolved = ResolveCanonicalMetadata(repository, registry)
            .execute(TITLE_ID)
            .getOrThrow()

        resolved.title?.providerId?.value shouldBe "kitsu"
        resolved.synopsis?.providerId?.value shouldBe "kitsu"
        resolved.artworkUrl?.providerId?.value shouldBe "kitsu"
        resolved.rating?.providerId?.value shouldBe "mal"
        resolved.rating?.value shouldBe 9.0
    }

    @Test
    fun `staff and editorial fields keep deterministic provenance`() = runTest {
        val repository = FakeCanonicalTitleRepository(
            identities = listOf(
                identity("mangaupdates", "mu1"),
                identity("mal", "m1"),
            ),
        )
        val mangaUpdates = FakeMetadataProvider(
            "mangaupdates",
            CatalogItem(
                provider = "mangaupdates",
                providerId = "mu1",
                title = "MU title",
                authors = listOf("Author MU"),
                artists = listOf("Artist MU"),
                startDate = "1994",
                endDate = "2001",
                volumeCount = 18,
            ),
        )
        val mal = FakeMetadataProvider(
            "mal",
            CatalogItem(
                provider = "mal",
                providerId = "m1",
                title = "MAL title",
                authors = listOf("Author MAL"),
                artists = listOf("Artist MAL"),
                startDate = "1995-01-01",
                volumeCount = 17,
            ),
        )
        val registry = FakeRegistry(listOf(mangaUpdates, mal))

        val resolved = ResolveCanonicalMetadata(repository, registry)
            .execute(TITLE_ID)
            .getOrThrow()

        resolved.authors?.value shouldBe listOf("Author MU")
        resolved.authors?.providerId?.value shouldBe "mangaupdates"
        resolved.artists?.value shouldBe listOf("Artist MU")
        resolved.startDate?.value shouldBe "1994"
        resolved.endDate?.value shouldBe "2001"
        resolved.editorialVolumeCount?.value shouldBe 18
        resolved.editorialVolumeCount?.providerId?.value shouldBe "mangaupdates"
    }

    @Test
    fun `granular capability gate falls back without disabling other fields`() = runTest {
        val repository = FakeCanonicalTitleRepository(
            identities = listOf(
                identity("kitsu", "k1"),
                identity("mal", "m1"),
            ),
        )
        val kitsu = FakeMetadataProvider(
            "kitsu",
            CatalogItem(
                provider = "kitsu",
                providerId = "k1",
                title = "Kitsu title",
                synopsis = "Kitsu synopsis",
                coverUrl = "kitsu-cover",
            ),
        )
        val mal = FakeMetadataProvider(
            "mal",
            CatalogItem(
                provider = "mal",
                providerId = "m1",
                title = "MAL title",
                synopsis = "MAL synopsis",
                coverUrl = "mal-cover",
            ),
        )
        val registry = FakeRegistry(
            providers = listOf(kitsu, mal),
            disabled = setOf(IntegrationId("kitsu") to IntegrationCapability.METADATA_ARTWORK),
        )

        val resolved = ResolveCanonicalMetadata(repository, registry)
            .execute(TITLE_ID)
            .getOrThrow()

        resolved.synopsis?.providerId?.value shouldBe "kitsu"
        resolved.artworkUrl?.providerId?.value shouldBe "mal"
    }

    @Test
    fun `unverified external identities never enrich canonical metadata`() = runTest {
        val repository = FakeCanonicalTitleRepository(
            identities = listOf(
                identity("kitsu", "k1", verified = false),
                identity("mal", "m1"),
            ),
        )
        val registry = FakeRegistry(
            listOf(
                FakeMetadataProvider(
                    "kitsu",
                    CatalogItem("kitsu", "k1", "Kitsu title", synopsis = "restricted"),
                ),
                FakeMetadataProvider(
                    "mal",
                    CatalogItem("mal", "m1", "MAL title", synopsis = "verified"),
                ),
            ),
        )

        val resolved = ResolveCanonicalMetadata(repository, registry)
            .execute(TITLE_ID)
            .getOrThrow()

        resolved.synopsis?.value shouldBe "verified"
        resolved.externalIds.keys shouldBe setOf(IntegrationId("mal"))
    }

    private fun identity(
        provider: String,
        externalId: String,
        verified: Boolean = true,
    ) = ExternalIdentity(
        canonicalTitleId = TITLE_ID,
        provider = provider,
        externalId = externalId,
        verified = verified,
        createdAt = 1L,
    )

    private class FakeMetadataProvider(
        id: String,
        private val item: CatalogItem,
    ) : MetadataProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun getDetails(externalId: String): Result<CatalogItem> =
            Result.success(item)
    }

    private class FakeRegistry(
        private val providers: List<MetadataProvider>,
        private val disabled: Set<Pair<IntegrationId, IntegrationCapability>> = emptySet(),
    ) : IntegrationRegistry {
        private val manifests = providers.map { provider ->
            IntegrationManifest(
                integrationId = provider.integrationId,
                displayName = provider.integrationId.value,
                category = IntegrationCategory.METADATA_SERVICE,
                capabilities = METADATA_CAPABILITIES.associateWith {
                    CapabilityPolicy(IntegrationPolicy.ALLOWED)
                },
            )
        }

        override fun manifests(): List<IntegrationManifest> = manifests

        override fun isGlobalCapabilityActive(
            integrationId: IntegrationId,
            capability: IntegrationCapability,
        ): Boolean = integrationId to capability !in disabled

        override fun metadataProviders(): List<MetadataProvider> =
            metadataProviders(IntegrationCapability.METADATA_BASIC)

        override fun metadataProviders(capability: IntegrationCapability): List<MetadataProvider> =
            providers.filter { isGlobalCapabilityActive(it.integrationId, capability) }

        override fun searchProviders(): List<SearchProvider> = emptyList()

        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()

        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()

        override fun ratingsProviders(): List<RatingsProvider> = emptyList()

        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeCanonicalTitleRepository(
        private val identities: List<ExternalIdentity>,
    ) : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = null

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = emptyFlow()

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null

        override suspend fun getExternalIdentities(canonicalTitleId: String): List<ExternalIdentity> =
            identities.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("Not used")

        override suspend fun insert(title: CanonicalTitle) = error("Not used")

        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("Not used")
    }

    private companion object {
        const val TITLE_ID = "canonical"

        val METADATA_CAPABILITIES = listOf(
            IntegrationCapability.METADATA_BASIC,
            IntegrationCapability.METADATA_ARTWORK,
            IntegrationCapability.METADATA_EDITORIAL,
            IntegrationCapability.METADATA_STAFF,
            IntegrationCapability.RATINGS,
        )
    }
}
