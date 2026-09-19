package tachiyomi.domain.tsuzuki.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ConfirmSourceMapping
import tachiyomi.domain.tsuzuki.source.model.ConfirmSourceResult
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

class ConfirmSourceMappingTest {

    private lateinit var mappingRepository: FakeSourceTitleMappingRepository
    private lateinit var readingSourceGateway: FakeReadingSourceGateway
    private lateinit var confirmSourceMapping: ConfirmSourceMapping

    @BeforeEach
    fun setUp() {
        mappingRepository = FakeSourceTitleMappingRepository()
        readingSourceGateway = FakeReadingSourceGateway()
        confirmSourceMapping = ConfirmSourceMapping(
            sourceTitleMappingRepository = mappingRepository,
            readingSourceGateway = readingSourceGateway,
            idFactory = { "generated-id" },
            clock = { 5000L },
        )
    }

    @Test
    fun `persists new mapping and calls gateway materializeSource`() = runTest {
        readingSourceGateway.materializeResult = Result.success(
            MaterializedReadingSource(sourceId = 10L, sourceUrl = "/manga/1", mihonMangaId = 99L, title = "Title 1"),
        )

        val candidate = ReadingSourceCandidate(sourceId = 10L, sourceUrl = "/manga/1", title = "Title 1")
        val result = confirmSourceMapping.execute(
            canonicalTitleId = "canonical-1",
            candidate = candidate,
            language = "en",
            matchConfidence = 0.98,
            verifiedByUser = true,
        )

        result.shouldBeInstanceOf<ConfirmSourceResult.Success>()
        val mapping = result.mapping
        mapping.id shouldBe "generated-id"
        mapping.canonicalTitleId shouldBe "canonical-1"
        mapping.sourceId shouldBe 10L
        mapping.sourceUrl shouldBe "/manga/1"
        mapping.mihonMangaId shouldBe 99L
        mapping.verifiedByUser shouldBe true
        mapping.availability shouldBe SourceMappingAvailability.AVAILABLE
        mapping.preferredOverride shouldBe false
        mapping.createdAt shouldBe 5000L

        mappingRepository.mappings["generated-id"] shouldBe mapping
    }

    @Test
    fun `verifiedByUser is preserved as false for auto-confirmation`() = runTest {
        readingSourceGateway.materializeResult = Result.success(
            MaterializedReadingSource(sourceId = 10L, sourceUrl = "/manga/1", mihonMangaId = 99L, title = "Title 1"),
        )

        val candidate = ReadingSourceCandidate(sourceId = 10L, sourceUrl = "/manga/1", title = "Title 1")
        val result = confirmSourceMapping.execute(
            canonicalTitleId = "canonical-1",
            candidate = candidate,
            language = "en",
            matchConfidence = 0.98,
            verifiedByUser = false,
        )

        result.shouldBeInstanceOf<ConfirmSourceResult.Success>()
        result.mapping.verifiedByUser shouldBe false
    }

    @Test
    fun `re为其reuses existing mapping for same title`() = runTest {
        val existing = SourceTitleMapping(
            id = "existing-1",
            canonicalTitleId = "canonical-1",
            mihonMangaId = 88L,
            sourceId = 10L,
            sourceUrl = "/manga/1",
            language = "en",
            matchConfidence = 0.95,
            verifiedByUser = false,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        mappingRepository.upsert(existing)

        val candidate = ReadingSourceCandidate(sourceId = 10L, sourceUrl = "/manga/1", title = "Title 1")
        val result = confirmSourceMapping.execute(
            canonicalTitleId = "canonical-1",
            candidate = candidate,
            verifiedByUser = true,
        )

        result.shouldBeInstanceOf<ConfirmSourceResult.Success>()
        result.mapping.id shouldBe "existing-1"
        result.mapping.verifiedByUser shouldBe true
        result.mapping.updatedAt shouldBe 5000L
        readingSourceGateway.materializeCallCount shouldBe 0
    }

    @Test
    fun `rejects mapping owned by another canonical title with Conflict`() = runTest {
        val existingOther = SourceTitleMapping(
            id = "existing-other",
            canonicalTitleId = "canonical-other",
            mihonMangaId = 88L,
            sourceId = 10L,
            sourceUrl = "/manga/1",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        mappingRepository.upsert(existingOther)

        val candidate = ReadingSourceCandidate(sourceId = 10L, sourceUrl = "/manga/1", title = "Title 1")
        val result = confirmSourceMapping.execute(
            canonicalTitleId = "canonical-mine",
            candidate = candidate,
        )

        result.shouldBeInstanceOf<ConfirmSourceResult.Conflict>()
        result.existingCanonicalTitleId shouldBe "canonical-other"
        readingSourceGateway.materializeCallCount shouldBe 0
    }

    @Test
    fun `rethrows CancellationException from gateway`() = runTest {
        readingSourceGateway.errorToThrow = CancellationException("Cancelled")

        val candidate = ReadingSourceCandidate(sourceId = 10L, sourceUrl = "/manga/1", title = "Title 1")
        shouldThrow<CancellationException> {
            confirmSourceMapping.execute(
                canonicalTitleId = "canonical-1",
                candidate = candidate,
            )
        }
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = mutableMapOf<String, SourceTitleMapping>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> {
            return mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> = emptyFlow()

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? {
            return mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {}
    }

    private class FakeReadingSourceGateway : ReadingSourceGateway {
        var materializeCallCount = 0
        var materializeResult: Result<MaterializedReadingSource> = Result.failure(IllegalStateException())
        var errorToThrow: Throwable? = null

        override suspend fun getAvailableSources(language: String): List<ReadingSourceDescriptor> = emptyList()

        override suspend fun searchSource(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> =
            Result.success(emptyList())

        override suspend fun materializeSource(
            sourceId: Long,
            sourceUrl: String,
            title: String,
        ): Result<MaterializedReadingSource> {
            if (errorToThrow != null) throw errorToThrow!!
            materializeCallCount++
            return materializeResult
        }
    }
}
