package tachiyomi.domain.tsuzuki.metadata.interactor

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class RefreshReportedChapterCountsTest {

    @Test
    fun `unverified identities never write reported chapter counts`() = runTest {
        val canonicalRepository = FakeCanonicalTitleRepository(
            listOf(
                identity("kitsu", "verified", verified = true),
                identity("mal", "unverified", verified = false),
            ),
        )
        val reportedRepository = FakeReportedChapterCountRepository()
        val registry = FakeRegistry(
            listOf(
                FakeMetadataProvider("kitsu", chapterCount = 12),
                FakeMetadataProvider("mal", chapterCount = 99),
            ),
        )

        RefreshReportedChapterCounts(
            canonicalTitleRepository = canonicalRepository,
            registry = registry,
            repository = reportedRepository,
        ).execute(TITLE_ID).getOrThrow()

        reportedRepository.values
            .map { it.provider to it.chapterCount }
            .shouldContainExactly("kitsu" to 12)
    }

    private fun identity(
        provider: String,
        externalId: String,
        verified: Boolean,
    ) = ExternalIdentity(
        canonicalTitleId = TITLE_ID,
        provider = provider,
        externalId = externalId,
        verified = verified,
        createdAt = 1L,
    )

    private class FakeMetadataProvider(
        id: String,
        private val chapterCount: Int,
    ) : MetadataProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun getDetails(externalId: String): Result<CatalogItem> =
            Result.success(
                CatalogItem(
                    provider = integrationId.value,
                    providerId = externalId,
                    title = integrationId.value,
                    chapterCount = chapterCount,
                ),
            )
    }

    private class FakeRegistry(
        private val providers: List<MetadataProvider>,
    ) : IntegrationRegistry {
        override fun metadataProviders(): List<MetadataProvider> = providers

        override fun metadataProviders(capability: IntegrationCapability): List<MetadataProvider> =
            if (capability == IntegrationCapability.METADATA_EDITORIAL) providers else emptyList()

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

    private class FakeReportedChapterCountRepository : ReportedChapterCountRepository {
        val values = mutableListOf<ReportedChapterCount>()

        override suspend fun getByTitle(canonicalTitleId: String): List<ReportedChapterCount> =
            values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(value: ReportedChapterCount) {
            values.removeAll {
                it.canonicalTitleId == value.canonicalTitleId &&
                    it.provider == value.provider
            }
            values += value
        }
    }

    private companion object {
        const val TITLE_ID = "canonical"
    }
}
