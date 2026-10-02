package tachiyomi.domain.tsuzuki.integration.interactor

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.cache.RatingEnrichmentCache
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.cache.InFlightCanonicalMetadataResolution
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshotRepository
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class ResolveCanonicalMetadataCachingTest {

    @Test
    fun `fresh persisted snapshot avoids provider call`() = runTest {
        val provider = CountingMetadataProvider()
        val snapshots = FakeSnapshotRepository(
            CanonicalMetadataSnapshot(
                canonicalTitleId = TITLE_ID,
                configurationFingerprint = "cfg",
                metadata = ResolvedMetadata(
                    title = ProvenancedMetadata(
                        value = "Cached",
                        providerId = IntegrationId("kitsu"),
                        externalId = "k1",
                    ),
                ),
                refreshedAt = 1_000L,
            ),
        )
        val resolver = resolver(
            provider = provider,
            snapshots = snapshots,
            clock = { 1_500L },
            scope = backgroundScope,
        )

        resolver.execute(TITLE_ID).getOrThrow().title?.value shouldBe "Cached"
        provider.calls shouldBe 0
    }

    @Test
    fun `simultaneous metadata resolution shares one provider request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = CountingMetadataProvider(gate)
        val resolver = resolver(
            provider = provider,
            snapshots = FakeSnapshotRepository(),
            clock = { 10_000L },
            scope = backgroundScope,
        )

        val first = async { resolver.execute(TITLE_ID).getOrThrow() }
        val second = async { resolver.execute(TITLE_ID).getOrThrow() }
        runCurrent()

        provider.calls shouldBe 1
        gate.complete(Unit)
        first.await().title?.value shouldBe "Live"
        second.await().title?.value shouldBe "Live"
        provider.calls shouldBe 1
    }

    @Test
    fun `provider details also refresh reported chapter count without a second provider call`() = runTest {
        val provider = CountingMetadataProvider()
        val counts = FakeReportedChapterCountRepository()
        val resolver = resolver(
            provider = provider,
            snapshots = FakeSnapshotRepository(),
            counts = counts,
            clock = { 20_000L },
            scope = backgroundScope,
        )

        resolver.execute(TITLE_ID, forceRefresh = true).getOrThrow()

        provider.calls shouldBe 1
        counts.values.single().chapterCount shouldBe 42
        counts.values.single().provider shouldBe "kitsu"
    }

    @Test
    fun `forced metadata refresh reuses cached supplemental rating`() = runTest {
        val provider = CountingMetadataProvider()
        val ratings = CountingRatingsProvider()
        val resolver = resolver(
            provider = provider,
            snapshots = FakeSnapshotRepository(),
            ratingProviders = listOf(ratings),
            clock = { 30_000L },
            scope = backgroundScope,
        )

        resolver.execute(TITLE_ID, forceRefresh = true).getOrThrow()
        resolver.execute(TITLE_ID, forceRefresh = true).getOrThrow()

        provider.calls shouldBe 2
        ratings.calls shouldBe 1
    }

    private fun resolver(
        provider: CountingMetadataProvider,
        snapshots: FakeSnapshotRepository,
        counts: FakeReportedChapterCountRepository = FakeReportedChapterCountRepository(),
        ratingProviders: List<RatingsProvider> = emptyList(),
        clock: () -> Long,
        scope: CoroutineScope,
        ratingCache: RatingEnrichmentCache = RatingEnrichmentCache(
            scope = scope,
            clock = { 0L },
            positiveTtlMillis = 60_000L,
            negativeTtlMillis = 1_000L,
            maxEntries = 32,
        ),
    ) = ResolveCanonicalMetadata(
        canonicalTitleRepository = FakeCanonicalTitleRepository(),
        registry = FakeRegistry(provider, ratingProviders),
        titleArtworkRepository = mockk(relaxed = true),
        diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
        snapshotRepository = snapshots,
        inFlightResolution = InFlightCanonicalMetadataResolution(scope),
        reportedChapterCountRepository = counts,
        clock = clock,
        metadataTtlMillis = 15_000L,
        ratingEnrichmentCache = ratingCache,
    )

    private class CountingMetadataProvider(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : MetadataProvider {
        override val integrationId = IntegrationId("kitsu")
        var calls = 0

        override suspend fun getDetails(externalId: String): Result<CatalogItem> {
            calls++
            gate?.await()
            return Result.success(
                CatalogItem(
                    provider = "kitsu",
                    providerId = externalId,
                    title = "Live",
                    chapterCount = 42,
                ),
            )
        }
    }

    private class FakeRegistry(
        private val provider: MetadataProvider,
        private val ratingProviders: List<RatingsProvider> = emptyList(),
    ) : IntegrationRegistry {
        override fun configurationFingerprint(): String = "cfg"
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = listOf(provider)
        override fun metadataProviders(capability: IntegrationCapability): List<MetadataProvider> = listOf(provider)
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = ratingProviders
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
        override fun isGlobalCapabilityActive(
            integrationId: IntegrationId,
            capability: IntegrationCapability,
        ): Boolean = integrationId == provider.integrationId
    }

    private class CountingRatingsProvider : RatingsProvider {
        override val integrationId = IntegrationId("mal")
        var calls = 0

        override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
            Result.success(emptyList())

        override suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> {
            calls++
            return Result.success(
                CatalogRatingMatch(
                    externalId = "m1",
                    rating = ExternalRating(
                        providerId = "mal",
                        label = "MAL",
                        value = 8.4,
                        scaleMax = 10.0,
                    ),
                    verifiedIdentity = false,
                ),
            )
        }
    }

    private class FakeSnapshotRepository(
        initial: CanonicalMetadataSnapshot? = null,
    ) : CanonicalMetadataSnapshotRepository {
        private var snapshot = initial

        override suspend fun get(canonicalTitleId: String): CanonicalMetadataSnapshot? =
            snapshot?.takeIf { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsertIfNewer(snapshot: CanonicalMetadataSnapshot): CanonicalMetadataSnapshot {
            val current = this.snapshot
            if (current == null || snapshot.refreshedAt >= current.refreshedAt) {
                this.snapshot = snapshot
            }
            return this.snapshot!!
        }

        override suspend fun invalidateTitle(canonicalTitleId: String) {
            if (snapshot?.canonicalTitleId == canonicalTitleId) snapshot = null
        }
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

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = null
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = emptyFlow()
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getExternalIdentities(canonicalTitleId: String): List<ExternalIdentity> = listOf(
            ExternalIdentity(
                canonicalTitleId = canonicalTitleId,
                provider = "kitsu",
                externalId = "k1",
                verified = true,
                createdAt = 1L,
            ),
        )
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("Not used")
        override suspend fun insert(title: CanonicalTitle) = error("Not used")
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("Not used")
    }

    private companion object {
        const val TITLE_ID = "title"
    }
}
