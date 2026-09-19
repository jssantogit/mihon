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
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

class ConfirmSourceMappingTest {

    private lateinit var mappingRepository: FakeSourceTitleMappingRepository
    private lateinit var gateway: FakeReadingSourceGateway
    private lateinit var confirm: ConfirmSourceMapping

    @BeforeEach
    fun setUp() {
        mappingRepository = FakeSourceTitleMappingRepository()
        gateway = FakeReadingSourceGateway()
        confirm = ConfirmSourceMapping(
            sourceTitleMappingRepository = mappingRepository,
            readingSourceGateway = gateway,
            idFactory = { "generated-id" },
            clock = { 5000L },
        )
    }

    @Test
    fun `new candidate materializes and persists verified mapping`() = runTest {
        gateway.materializeResult = Result.success(MaterializedReadingSource(99L, 10L, "/manga/1", "en"))

        val result = confirm.execute(
            canonicalTitleId = "canonical-1",
            candidate = candidate(),
            matchConfidence = 0.98,
        )

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.reused shouldBe false
        result.mapping shouldBe SourceTitleMapping(
            id = "generated-id",
            canonicalTitleId = "canonical-1",
            mihonMangaId = 99L,
            sourceId = 10L,
            sourceUrl = "/manga/1",
            language = "en",
            matchConfidence = 0.98,
            verifiedByUser = true,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 5000L,
            updatedAt = 5000L,
        )
    }

    @Test
    fun `automatic confirmation persists verifiedByUser false`() = runTest {
        gateway.materializeResult = Result.success(MaterializedReadingSource(99L, 10L, "/manga/1", "en"))

        val result = confirm.execute(
            canonicalTitleId = "canonical-1",
            candidate = candidate(),
            matchConfidence = 1.0,
            verifiedByUser = false,
        )

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.mapping.verifiedByUser shouldBe false
    }

    @Test
    fun `same-title existing mapping is reused without materialization`() = runTest {
        val existing = mapping("canonical-1", verified = false)
        mappingRepository.upsert(existing)

        val result = confirm.execute("canonical-1", candidate())

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.reused shouldBe true
        result.mapping.id shouldBe existing.id
        result.mapping.verifiedByUser shouldBe true
        gateway.materializeCallCount shouldBe 0
    }

    @Test
    fun `same-title incomplete mapping is materialized in place`() = runTest {
        val existing = mapping("canonical-1", verified = true).copy(
            mihonMangaId = null,
            availability = SourceMappingAvailability.UNKNOWN,
        )
        mappingRepository.upsert(existing)
        gateway.materializeResult = Result.success(MaterializedReadingSource(99L, 10L, "/manga/1", "en"))

        val result = confirm.execute("canonical-1", candidate())

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.reused shouldBe true
        result.mapping.id shouldBe existing.id
        result.mapping.mihonMangaId shouldBe 99L
        result.mapping.availability shouldBe SourceMappingAvailability.AVAILABLE
        gateway.materializeCallCount shouldBe 1
    }

    @Test
    fun `cross-title mapping returns conflict without materialization`() = runTest {
        mappingRepository.upsert(mapping("other-title", verified = true))

        val result = confirm.execute("canonical-1", candidate())

        result.shouldBeInstanceOf<SourceResolutionResult.Conflict>()
        result.existingCanonicalTitleId shouldBe "other-title"
        gateway.materializeCallCount shouldBe 0
    }

    @Test
    fun `materialization failure propagates and cancellation remains cancellation`() = runTest {
        gateway.materializeResult = Result.failure(IllegalStateException("materialize failed"))
        shouldThrow<IllegalStateException> {
            confirm.execute("canonical-1", candidate())
        }

        gateway.errorToThrow = CancellationException("cancelled")
        shouldThrow<CancellationException> {
            confirm.execute("canonical-1", candidate())
        }
    }

    private fun candidate() = ReadingSourceCandidate(
        sourceId = 10L,
        sourceName = "Source",
        language = "en",
        sourceUrl = "/manga/1",
        title = "Title 1",
        thumbnailUrl = null,
        author = null,
        artist = null,
        description = null,
        genres = null,
        status = 0L,
    )

    private fun mapping(canonicalTitleId: String, verified: Boolean) = SourceTitleMapping(
        id = "existing",
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = 88L,
        sourceId = 10L,
        sourceUrl = "/manga/1",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = verified,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = mutableMapOf<String, SourceTitleMapping>()
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> = emptyFlow()
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {}
    }

    private class FakeReadingSourceGateway : ReadingSourceGateway {
        var materializeCallCount = 0
        var materializeResult: Result<MaterializedReadingSource> = Result.failure(IllegalStateException())
        var errorToThrow: Throwable? = null

        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = emptyList()
        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> =
            Result.success(emptyList())
        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
            errorToThrow?.let { throw it }
            materializeCallCount++
            return materializeResult
        }
    }
}
