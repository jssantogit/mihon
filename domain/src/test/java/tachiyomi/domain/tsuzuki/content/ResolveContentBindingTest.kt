package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingConfirmationRequiredException
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceSearchException
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceSearchException
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceSearchPhase
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.io.IOException

class ResolveContentBindingTest {

    @Test
    fun `existing addon binding is reused without title search`() = runTest {
        val existing = binding(
            providerTitleKey = "remote-123",
            availability = ContentBindingAvailability.AVAILABLE,
        )
        val repository = FakeContentBindingRepository(existing)
        val gateway = FakeReadingSourceGateway()
        val resolver = resolver(repository, gateway)

        val result = resolver.execute("title", AddonId("mangadex")).getOrThrow()

        result.providerTitleKey shouldBe "remote-123"
        gateway.searchCalls shouldBe 0
    }

    @Test
    fun `same title in multiple internal sources creates bindings without false ambiguity`() = runTest {
        val repository = FakeContentBindingRepository(null)
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(sourceId = 7L, sourceUrl = "/en/dandadan", title = "Dandadan")),
                8L to listOf(candidate(sourceId = 8L, sourceUrl = "/pt/dandadan", title = "Dandadan")),
            ),
            materializedBySource = mapOf(
                7L to MaterializedReadingSource(
                    mihonMangaId = 70L,
                    sourceId = 7L,
                    sourceUrl = "/en/dandadan",
                    language = "en",
                    runtimePayload = byteArrayOf(7),
                ),
                8L to MaterializedReadingSource(
                    mihonMangaId = 80L,
                    sourceId = 8L,
                    sourceUrl = "/pt/dandadan",
                    language = "pt-BR",
                    runtimePayload = byteArrayOf(8),
                ),
            ),
        )
        val resolver = resolver(
            repository = repository,
            gateway = gateway,
            addonSourceIds = listOf(7L, 8L),
        )

        val bindings = resolver.executeAll("title", AddonId("mangadex")).getOrThrow()

        bindings.map { it.providerTitleKey }.toSet() shouldBe
            setOf("7:/en/dandadan", "8:/pt/dandadan")
        gateway.searchCalls shouldBe 2
        gateway.materializeCalls shouldBe 2
    }

    @Test
    fun `source search retries punctuation variants without unverified title binding`() = runTest {
        val repository = FakeContentBindingRepository(null)
        val gateway = FakeReadingSourceGateway(
            searchResultsByQuery = mapOf(
                (7L to "One Punch Man") to listOf(
                    candidate(sourceId = 7L, sourceUrl = "/one-punch-man", title = "One Punch-Man"),
                ),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 42L,
                sourceId = 7L,
                sourceUrl = "/one-punch-man",
                language = "en",
                runtimePayload = byteArrayOf(1),
            ),
        )
        val resolver = resolver(repository, gateway, title = "One-Punch Man")

        val bindings = resolver.executeAll("title", AddonId("mangadex")).getOrThrow()

        bindings.single().providerTitleKey shouldBe "7:/one-punch-man"
        gateway.searchedQueries shouldBe listOf("One-Punch Man", "One Punch Man")
    }

    @Test
    fun `alternative title query still requires confirmation when multiple candidates tie`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchResultsByQuery = mapOf(
                (7L to "One Punch Man") to listOf(
                    candidate(7L, "/edition-a", "One Punch-Man"),
                    candidate(7L, "/edition-b", "One-Punch Man"),
                ),
            ),
        )
        val result = resolver(FakeContentBindingRepository(null), gateway, title = "One-Punch Man")
            .executeAll("title", AddonId("mangadex"))

        (result.exceptionOrNull() is ContentBindingConfirmationRequiredException) shouldBe true
        gateway.materializeCalls shouldBe 0
    }

    @Test
    fun `source lookup network failure is not mistaken for no matching title`() = runTest {
        val gateway = FakeReadingSourceGateway(searchFailure = IOException("temporary outage"))
        val result = resolver(FakeContentBindingRepository(null), gateway, title = "One-Punch Man")
            .executeAll("title", AddonId("mangadex"))

        (result.exceptionOrNull() is ContentBindingSourceSearchException) shouldBe true
        gateway.searchedQueries shouldBe listOf("One-Punch Man")
    }

    @Test
    fun `binding diagnosis distinguishes source lookup failure from empty search`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val gateway = FakeReadingSourceGateway(
            searchFailure = ReadingSourceSearchException(
                ReadingSourceSearchPhase.SEARCH,
                IOException("private extension network detail"),
            ),
        )
        val result = resolver(
            FakeContentBindingRepository(null),
            gateway,
            title = "One-Punch Man",
            diagnostics = diagnostics,
        ).executeAll("title", AddonId("mangadex"))

        (result.exceptionOrNull() is ContentBindingSourceSearchException) shouldBe true
        val diagnostic = diagnostics.events.single()
        diagnostic.stage shouldBe ChapterInventoryDiagnosticStage.BINDING
        diagnostic.sourceId shouldBe 7L
        diagnostic.outcome shouldBe ChapterInventoryDiagnosticOutcome.NETWORK_ERROR
        diagnostic.reasons[ChapterInventoryDiagnosticReason.SOURCE_SEARCH_FAILED] shouldBe 1
        diagnostics.report().contains("private extension network detail") shouldBe false
    }

    @Test
    fun `binding diagnosis distinguishes invalid extension response`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val gateway = FakeReadingSourceGateway(
            searchFailure = ReadingSourceSearchException(
                ReadingSourceSearchPhase.SEARCH,
                kotlinx.serialization.SerializationException("raw provider content"),
            ),
        )
        resolver(
            FakeContentBindingRepository(null),
            gateway,
            diagnostics = diagnostics,
        ).executeAll("title", AddonId("mangadex"))

        val event = diagnostics.events.single()
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR
        event.reasons[ChapterInventoryDiagnosticReason.SOURCE_RESPONSE_DECODING_FAILED] shouldBe 1
    }

    @Test
    fun `binding diagnosis separates materialization failure from successful search`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/one-punch-man", "One-Punch Man")),
            ),
        )
        resolver(
            FakeContentBindingRepository(null),
            gateway,
            title = "One-Punch Man",
            diagnostics = diagnostics,
        ).executeAll("title", AddonId("mangadex"))

        diagnostics.events.any {
            it.stage == ChapterInventoryDiagnosticStage.BINDING &&
                it.reasons[ChapterInventoryDiagnosticReason.SOURCE_MATERIALIZATION_FAILED] == 1
        } shouldBe true
    }

    @Test
    fun `stale binding is repaired inside same canonical title`() = runTest {
        val repository = FakeContentBindingRepository(
            binding(
                providerTitleKey = "old",
                availability = ContentBindingAvailability.UNAVAILABLE,
            ),
        )
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(sourceId = 7L, sourceUrl = "/new", title = "Dandadan")),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 99L,
                sourceId = 7L,
                sourceUrl = "/new",
                language = "en",
                runtimePayload = byteArrayOf(7, 9, 11),
            ),
        )
        val resolver = resolver(repository, gateway)

        val repaired = resolver.execute("title", AddonId("mangadex")).getOrThrow()

        repaired.id shouldBe "binding"
        repaired.canonicalTitleId shouldBe "title"
        repaired.providerTitleKey shouldBe "7:/new"
        repaired.availability shouldBe ContentBindingAvailability.AVAILABLE
        repaired.runtimePayload.isNotEmpty() shouldBe true
        gateway.searchCalls shouldBe 1
        gateway.materializeCalls shouldBe 1
    }

    private fun resolver(
        repository: FakeContentBindingRepository,
        gateway: FakeReadingSourceGateway,
        addonSourceIds: List<Long> = listOf(7L),
        title: String = "Dandadan",
        diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
    ): ResolveContentBinding {
        var nextId = 0
        return ResolveContentBinding(
            contentBindingRepository = repository,
            canonicalTitleRepository = FakeCanonicalTitleRepository(title),
            addonRepository = FakeAddonRepository(addonSourceIds),
            readingSourceGateway = gateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
            idFactory = { "new-binding-${nextId++}" },
            clock = { 200L },
            diagnostics = diagnostics,
        )
    }

    private fun binding(
        providerTitleKey: String,
        availability: ContentBindingAvailability,
    ) = ContentBinding(
        id = "binding",
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        providerTitleKey = providerTitleKey,
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = availability,
        runtimePayload = byteArrayOf(1),
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun candidate(
        sourceId: Long,
        sourceUrl: String,
        title: String,
    ) = ReadingSourceCandidate(
        sourceId = sourceId,
        sourceName = "Source $sourceId",
        language = "en",
        sourceUrl = sourceUrl,
        title = title,
        thumbnailUrl = null,
        author = null,
        artist = null,
        description = null,
        genres = null,
        status = 0L,
    )

    private class FakeContentBindingRepository(
        initial: ContentBinding?,
    ) : ContentBindingRepository {
        private val values = mutableListOf<ContentBinding>().apply {
            initial?.let(::add)
        }

        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? {
            return values.lastOrNull {
                it.canonicalTitleId == canonicalTitleId && it.addonId == addonId
            }
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> {
            return values.filter { it.canonicalTitleId == canonicalTitleId }
        }

        override suspend fun upsert(binding: ContentBinding) {
            values.removeAll { it.id == binding.id }
            values += binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            val index = values.indexOfFirst { it.id == bindingId }
            if (index >= 0) {
                values[index] = values[index].copy(
                    availability = ContentBindingAvailability.UNAVAILABLE,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    private class FakeCanonicalTitleRepository(displayTitle: String) : CanonicalTitleRepository {
        private val title = CanonicalTitle(
            id = "title",
            displayTitle = displayTitle,
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override suspend fun getById(id: String): CanonicalTitle? = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: tachiyomi.domain.tsuzuki.model.ExternalIdentity,
        ): CanonicalTitle = error("unused")

        override suspend fun insert(title: CanonicalTitle) = error("unused")
        override suspend fun addExternalIdentity(identity: tachiyomi.domain.tsuzuki.model.ExternalIdentity) =
            error("unused")
    }

    private class FakeAddonRepository(
        sourceIds: List<Long> = listOf(7L),
    ) : AddonRepository {
        private val addon = InstalledAddon(
            id = AddonId("mangadex"),
            displayName = "MangaDex",
            enabled = true,
            versionName = "1.0",
            mihonSourceIds = sourceIds,
            hasSettings = false,
        )

        override fun observeInstalled() = flowOf(listOf(addon))
        override suspend fun snapshot() = listOf(addon)
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class RecordingDiagnostics : ChapterInventoryDiagnostics {
        val events = mutableListOf<ChapterInventoryDiagnosticEvent>()

        override fun start(canonicalTitleId: String) = "test-session"
        override fun stop() = Unit
        override fun clear() = events.clear()
        override fun isRecording(canonicalTitleId: String) = canonicalTitleId == "title"
        override fun record(event: ChapterInventoryDiagnosticEvent) {
            events += event
        }
        override fun report() = events.joinToString("\n") { event ->
            event.stage.name + "|" + event.outcome.name + "|" +
                event.reasons.keys.joinToString(",") { it.name }
        }
    }

    private class FakeReadingSourceGateway(
        private val searchResults: Map<Long, List<ReadingSourceCandidate>> = emptyMap(),
        private val materialized: MaterializedReadingSource? = null,
        private val materializedBySource: Map<Long, MaterializedReadingSource> = emptyMap(),
        private val searchResultsByQuery: Map<Pair<Long, String>, List<ReadingSourceCandidate>> = emptyMap(),
        private val searchFailure: Throwable? = null,
    ) : ReadingSourceGateway {
        var searchCalls = 0
        val searchedQueries = mutableListOf<String>()
        var materializeCalls = 0

        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = emptyList()

        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            searchCalls += 1
            searchedQueries += query
            searchFailure?.let { return Result.failure(it) }
            return Result.success(searchResultsByQuery[sourceId to query] ?: searchResults[sourceId].orEmpty())
        }

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
            materializeCalls += 1
            return (materializedBySource[candidate.sourceId] ?: materialized)
                ?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("No materialized source configured"))
        }
    }
}
