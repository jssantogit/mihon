package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingConfirmationRequiredException
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceSearchException
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchMode
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchProgress
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchRequest
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureKind
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureStage
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceOutcome
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.io.IOException

class ResolveContentBindingTest {

    @Test
    fun `initial search orders preferred languages and respects the batch limit`() = runTest {
        val gateway = FakeReadingSourceGateway(
            installedByLanguage = mapOf(
                "pt-BR" to listOf(descriptor(14L, "pt-BR"), descriptor(99L, "pt-BR")),
                "en" to listOf(descriptor(13L, "en"), descriptor(11L, "en")),
            ),
        )
        val resolver = resolver(
            FakeContentBindingRepository(null),
            gateway,
            addonSourceIds = listOf(11L, 12L, 13L, 14L),
        )

        val events = resolver.searchProgress(
            ContentBindingSearchRequest(
                canonicalTitleId = "title",
                addonId = AddonId("mangadex"),
                preferredLanguages = listOf("pt-BR", "en"),
                batchSize = 2,
            ),
        ).toList()

        val completed = events.filterIsInstance<ContentBindingSearchProgress.Completed>().single()
        completed.queriedSourceIds shouldBe listOf(14L, 13L)
        completed.remainingSourceCount shouldBe 2
        gateway.searchedSourceIds.toSet() shouldBe setOf(14L, 13L)
        gateway.searchedSourceIds.size shouldBe 2
    }

    @Test
    fun `initial search without language preferences uses only a small source batch`() = runTest {
        val gateway = FakeReadingSourceGateway()

        val events = resolver(
            FakeContentBindingRepository(null),
            gateway,
            addonSourceIds = listOf(7L, 8L, 9L, 10L),
        ).searchProgress(request(batchSize = 3)).toList()

        events.filterIsInstance<ContentBindingSearchProgress.Completed>().single().queriedSourceIds shouldBe
            listOf(7L, 8L, 9L)
        events.filterIsInstance<ContentBindingSearchProgress.Completed>().single().remainingSourceCount shouldBe 1
        gateway.searchedSourceIds.toSet() shouldBe setOf(7L, 8L, 9L)
    }

    @Test
    fun `broadening searches only remaining enabled source ids and never repeats queried ids`() = runTest {
        val gateway = FakeReadingSourceGateway(
            installedByLanguage = mapOf(
                "pt-BR" to listOf(descriptor(14L, "pt-BR")),
                "en" to listOf(descriptor(13L, "en"), descriptor(11L, "en")),
            ),
        )
        val resolver = resolver(
            FakeContentBindingRepository(null),
            gateway,
            addonSourceIds = listOf(11L, 12L, 13L, 14L),
        )

        val events = resolver.searchProgress(
            ContentBindingSearchRequest(
                canonicalTitleId = "title",
                addonId = AddonId("mangadex"),
                preferredLanguages = listOf("pt-BR", "en"),
                mode = ContentBindingSearchMode.BROADEN,
                alreadyQueriedSourceIds = setOf(14L, 13L),
                batchSize = 2,
            ),
        ).toList()

        gateway.searchedSourceIds.toSet() shouldBe setOf(11L, 12L)
        gateway.searchedSourceIds.size shouldBe 2
        events.filterIsInstance<ContentBindingSearchProgress.Completed>().single()
            .remainingSourceCount shouldBe 0
    }

    @Test
    fun `healthy source result is emitted even when a peer source fails`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchHandler = { sourceId, _ ->
                if (sourceId == 7L) {
                    delay(10)
                    Result.success(listOf(candidate(7L, "/dandadan", "Dandadan")))
                } else {
                    delay(20)
                    Result.failure(IOException("network unavailable"))
                }
            },
            materializedBySource = mapOf(
                7L to materialized(7L, "/dandadan", "en"),
            ),
        )
        val repository = FakeContentBindingRepository(null)
        val events = resolver(
            repository,
            gateway,
            addonSourceIds = listOf(7L, 8L),
        ).searchProgress(request(batchSize = 2)).toList()

        val sourceEvents = events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
        sourceEvents.map { it.sourceId } shouldBe listOf(7L, 8L)
        sourceEvents[0].outcome shouldBe ContentBindingSourceOutcome.BOUND
        sourceEvents[1].outcome shouldBe ContentBindingSourceOutcome.FAILURE
        sourceEvents[1].failure?.kind shouldBe ContentBindingSearchFailureKind.INDETERMINATE
        repository.getByTitle("title").size shouldBe 1
        events.last()::class shouldBe ContentBindingSearchProgress.Completed::class
    }

    @Test
    fun `empty result and source error have distinct progress outcomes`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchHandler = { sourceId, _ ->
                when (sourceId) {
                    7L -> Result.success(emptyList())
                    else -> Result.failure(IOException("lookup failed"))
                }
            },
        )
        val events = resolver(
            FakeContentBindingRepository(null),
            gateway,
            addonSourceIds = listOf(7L, 8L),
        ).searchProgress(request(batchSize = 2)).toList()

        val bySource = events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .associateBy { it.sourceId }
        bySource.getValue(7L).outcome shouldBe ContentBindingSourceOutcome.EMPTY
        bySource.getValue(8L).outcome shouldBe ContentBindingSourceOutcome.FAILURE
        bySource.getValue(8L).failure?.stage shouldBe ContentBindingSearchFailureStage.SEARCH
    }

    @Test
    fun `HTTP rate limit is classified without exposing arbitrary exception text`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchHandler = { _, _ ->
                Result.failure(
                    ReadingSourceSearchFailure(
                        kind = ReadingSourceFailureKind.HTTP_RESPONSE,
                        httpStatus = 429,
                        cause = IOException("provider response body"),
                    ),
                )
            },
        )

        val source = resolver(FakeContentBindingRepository(null), gateway)
            .searchProgress(request())
            .toList()
            .filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .single()

        source.failure?.kind shouldBe ContentBindingSearchFailureKind.HTTP_RESPONSE
        source.failure?.httpStatus shouldBe 429
        source.outcome shouldBe ContentBindingSourceOutcome.FAILURE
    }

    @Test
    fun `low confidence title candidate is no match and is not materialized`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/unrelated", "Unrelated Work")),
            ),
        )

        val source = resolver(FakeContentBindingRepository(null), gateway)
            .searchProgress(request())
            .toList()
            .filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .single()

        source.outcome shouldBe ContentBindingSourceOutcome.NO_MATCH
        source.candidates shouldBe emptyList()
        gateway.materializeCalls shouldBe 0
    }

    @Test
    fun `result whose internal source id differs from queried id cannot be bound`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(99L, "/foreign", "Dandadan")),
            ),
            materialized = materialized(99L, "/foreign", "en"),
        )
        val repository = FakeContentBindingRepository(null)

        val source = resolver(repository, gateway)
            .searchProgress(request())
            .toList()
            .filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .single()

        source.outcome shouldBe ContentBindingSourceOutcome.FAILURE
        source.failure?.kind shouldBe ContentBindingSearchFailureKind.MALFORMED_RESPONSE
        gateway.materializeCalls shouldBe 0
        repository.getByTitle("title").isEmpty() shouldBe true
    }

    @Test
    fun `cooperative source timeout is reported as timeout not empty`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchHandler = { _, _ ->
                delay(1_000)
                Result.success(emptyList())
            },
        )

        val events = resolver(FakeContentBindingRepository(null), gateway)
            .searchProgress(request(timeoutMillis = 50))
            .toList()

        val source = events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>().single()
        source.outcome shouldBe ContentBindingSourceOutcome.FAILURE
        source.failure?.kind shouldBe ContentBindingSearchFailureKind.TIMEOUT
    }

    @Test
    fun `ambiguous source editions request confirmation without binding`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(
                    candidate(7L, "/edition-a", "Dandadan"),
                    candidate(7L, "/edition-b", "Dandadan"),
                ),
            ),
        )
        val repository = FakeContentBindingRepository(null)
        val events = resolver(repository, gateway)
            .searchProgress(request())
            .toList()

        val source = events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>().single()
        source.outcome shouldBe ContentBindingSourceOutcome.CONFIRMATION_REQUIRED
        source.candidates.size shouldBe 2
        gateway.materializeCalls shouldBe 0
        repository.getByTitle("title").isEmpty() shouldBe true
    }

    @Test
    fun `existing binding is only observed and initial search still verifies current source`() = runTest {
        val existing = binding("remote-123", ContentBindingAvailability.AVAILABLE)
        val gateway = FakeReadingSourceGateway()
        val events = resolver(FakeContentBindingRepository(existing), gateway)
            .searchProgress(request())
            .toList()

        events.filterIsInstance<ContentBindingSearchProgress.ExistingBindingsObserved>()
            .single().bindingCount shouldBe 1
        gateway.searchCalls shouldBe 1
        events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .single().outcome shouldBe ContentBindingSourceOutcome.EMPTY
        events.last()::class shouldBe ContentBindingSearchProgress.Completed::class
    }

    @Test
    fun `stale binding is informational and initial search verifies currently enabled sibling source`() = runTest {
        val stale = binding("opaque-provider-title-key", ContentBindingAvailability.AVAILABLE)
        val repository = FakeContentBindingRepository(stale)
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                8L to listOf(candidate(8L, "/dandadan", "Dandadan")),
            ),
            materializedBySource = mapOf(
                8L to materialized(8L, "/dandadan", "en"),
            ),
        )

        val events = resolver(
            repository,
            gateway,
            addonSourceIds = listOf(8L),
        ).searchProgress(request()).toList()

        events.filterIsInstance<ContentBindingSearchProgress.ExistingBindingsObserved>()
            .single().bindingCount shouldBe 1
        gateway.searchedSourceIds shouldBe listOf(8L)
        events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .single { it.sourceId == 8L }.outcome shouldBe ContentBindingSourceOutcome.BOUND
        repository.getByTitle("title").size shouldBe 2
    }

    @Test
    fun `concurrent collectors recover the same canonical binding after unique insert race`() = runTest {
        val repository = ConcurrentUniqueContentBindingRepository()
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/dandadan", "Dandadan")),
            ),
            materializedBySource = mapOf(
                7L to materialized(7L, "/dandadan", "en"),
            ),
        )
        val firstResolver = resolver(repository, gateway, bindingIdPrefix = "first")
        val secondResolver = resolver(repository, gateway, bindingIdPrefix = "second")

        val first = async { firstResolver.searchProgress(request()).toList() }
        val second = async { secondResolver.searchProgress(request()).toList() }
        val eventLists = listOf(first.await(), second.await())

        eventLists.forEach { events ->
            val result = events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>().single()
            result.outcome shouldBe ContentBindingSourceOutcome.BOUND
            result.bindings.single().canonicalTitleId shouldBe "title"
        }
        repository.snapshot().size shouldBe 1
        repository.snapshot().single().canonicalTitleId shouldBe "title"
        repository.snapshot().single().providerTitleKey shouldBe "7:/dandadan"
    }

    @Test
    fun `unique conflict owned by another canonical title remains a persistence failure`() = runTest {
        val otherTitleBinding = binding("7:/dandadan", ContentBindingAvailability.AVAILABLE)
            .copy(canonicalTitleId = "other-title")
        val repository = ConcurrentUniqueContentBindingRepository(
            initial = listOf(otherTitleBinding),
            synchronizeConcurrentReads = false,
        )
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/dandadan", "Dandadan")),
            ),
            materializedBySource = mapOf(
                7L to materialized(7L, "/dandadan", "en"),
            ),
        )

        val source = resolver(repository, gateway)
            .searchProgress(request())
            .toList()
            .filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
            .single()

        source.outcome shouldBe ContentBindingSourceOutcome.FAILURE
        source.failure?.stage shouldBe ContentBindingSearchFailureStage.PERSISTENCE
        repository.snapshot().single().canonicalTitleId shouldBe "other-title"
    }

    @Test
    fun `disabled addon produces a classified result without querying its sources`() = runTest {
        val gateway = FakeReadingSourceGateway()
        val events = resolver(
            FakeContentBindingRepository(null),
            gateway,
            addonSourceIds = listOf(7L),
            addonEnabled = false,
        ).searchProgress(request()).toList()

        val failure = events.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>().single()
        failure.failure?.kind shouldBe ContentBindingSearchFailureKind.ADDON_DISABLED
        failure.failure?.stage shouldBe ContentBindingSearchFailureStage.ADDON_DISCOVERY
        gateway.searchCalls shouldBe 0
    }

    @Test
    fun `explicit repeated search upserts the same provider binding idempotently`() = runTest {
        val repository = FakeContentBindingRepository(null)
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/dandadan", "Dandadan")),
            ),
            materialized = materialized(7L, "/dandadan", "en"),
        )
        val resolver = resolver(repository, gateway)

        resolver.searchProgress(request(mode = ContentBindingSearchMode.BROADEN)).toList()
        val firstId = repository.getByTitle("title").single().id
        resolver.searchProgress(request(mode = ContentBindingSearchMode.BROADEN)).toList()

        repository.getByTitle("title").size shouldBe 1
        repository.getByTitle("title").single().id shouldBe firstId
    }

    @Test
    fun `cancelling progressive search propagates and does not persist binding`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gateway = FakeReadingSourceGateway(
            searchHandler = { _, _ ->
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            },
        )
        val repository = FakeContentBindingRepository(null)
        val resolver = resolver(repository, gateway)
        val job = async {
            resolver.searchProgress(request()).toList()
        }

        started.await()
        job.cancelAndJoin()

        job.isCancelled shouldBe true
        repository.getByTitle("title").isEmpty() shouldBe true
        gateway.materializeCalls shouldBe 0
    }

    @Test
    fun `cancellation during materialization is checked before persistence`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/dandadan", "Dandadan")),
            ),
            materializeHandler = {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            },
        )
        val repository = FakeContentBindingRepository(null)
        val job = async {
            resolver(repository, gateway).searchProgress(request()).toList()
        }

        started.await()
        job.cancelAndJoin()

        job.isCancelled shouldBe true
        gateway.materializeCalls shouldBe 1
        repository.getByTitle("title").isEmpty() shouldBe true
    }

    @Test
    fun `legacy executeAll still resolves the complete addon source set`() = runTest {
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/en/dandadan", "Dandadan")),
                8L to listOf(candidate(8L, "/pt/dandadan", "Dandadan")),
            ),
            materializedBySource = mapOf(
                7L to materialized(7L, "/en/dandadan", "en"),
                8L to materialized(8L, "/pt/dandadan", "pt-BR"),
            ),
        )

        val result = resolver(FakeContentBindingRepository(null), gateway, addonSourceIds = listOf(7L, 8L))
            .executeAll("title", AddonId("mangadex"))
            .getOrThrow()

        result.size shouldBe 2
        gateway.searchedSourceIds.toSet() shouldBe setOf(7L, 8L)
    }

    @Test
    fun `diagnostic reports enabled and disabled internal sources without searching disabled ones`() = runTest {
        val diagnostics = RecordingDiagnostics()
        diagnostics.start("title")
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/one-punch-man", "One-Punch Man")),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 42L,
                sourceId = 7L,
                sourceUrl = "/one-punch-man",
                language = "en",
                runtimePayload = byteArrayOf(1),
            ),
        )
        val resolver = resolver(
            repository = FakeContentBindingRepository(null),
            gateway = gateway,
            addonSourceIds = listOf(7L),
            diagnostics = diagnostics,
            addonSources = listOf(
                AddonSourceEligibility(7L, "en", enabled = true),
                AddonSourceEligibility(8L, "pt-BR", enabled = false),
            ),
            title = "One-Punch Man",
        )

        resolver.executeAll("title", AddonId("mangadex")).isSuccess shouldBe true

        gateway.searchedSourceIds shouldBe listOf(7L)
        diagnostics.events.any {
            it.stage == ChapterInventoryDiagnosticStage.SOURCE_ELIGIBILITY &&
                it.sourceId == 8L &&
                it.language == "pt-BR" &&
                it.outcome == ChapterInventoryDiagnosticOutcome.DISABLED &&
                ChapterInventoryDiagnosticReason.SOURCE_DISABLED in it.reasons
        } shouldBe true
        diagnostics.events.any {
            it.stage == ChapterInventoryDiagnosticStage.SOURCE_ELIGIBILITY &&
                it.received == 2 && it.accepted == 1 && it.discarded == 1
        } shouldBe true
    }

    @Test
    fun `inactive diagnostics do not query the diagnostic-only source inventory`() = runTest {
        var eligibilityQueries = 0
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(7L, "/dandadan", "Dandadan")),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 42L,
                sourceId = 7L,
                sourceUrl = "/dandadan",
                language = "en",
                runtimePayload = byteArrayOf(1),
            ),
        )
        val resolver = resolver(
            repository = FakeContentBindingRepository(null),
            gateway = gateway,
            addonSourceEligibilityRepository = AddonSourceEligibilityRepository {
                eligibilityQueries++
                listOf(AddonSourceEligibility(7L, "en", enabled = true))
            },
        )

        resolver.executeAll("title", AddonId("mangadex")).isSuccess shouldBe true

        eligibilityQueries shouldBe 0
    }

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
        val diagnostics = RecordingDiagnostics()
        diagnostics.start("title")
        val gateway = FakeReadingSourceGateway(searchFailure = IOException("temporary outage"))
        val result = resolver(
            FakeContentBindingRepository(null),
            gateway,
            title = "One-Punch Man",
            diagnostics = diagnostics,
        )
            .executeAll("title", AddonId("mangadex"))

        (result.exceptionOrNull() is ContentBindingSourceSearchException) shouldBe true
        gateway.searchedQueries shouldBe listOf("One-Punch Man")
        val blocker = diagnostics.events.single { it.availabilityBlocked }
        blocker.stage shouldBe ChapterInventoryDiagnosticStage.BINDING_SEARCH
        blocker.affectedSourceCount shouldBe 1
    }

    @Test
    fun `binding search diagnostic preserves structured HTTP status without provider text`() = runTest {
        val diagnostics = RecordingDiagnostics()
        diagnostics.start("title")
        val sourceCause = IllegalStateException("private provider body")
        val gateway = FakeReadingSourceGateway(
            searchFailure = ReadingSourceSearchFailure(
                kind = ReadingSourceFailureKind.HTTP_RESPONSE,
                httpStatus = 403,
                cause = sourceCause,
            ),
        )

        val result = resolver(
            FakeContentBindingRepository(null),
            gateway,
            title = "One-Punch Man",
            diagnostics = diagnostics,
        ).executeAll("title", AddonId("mangadex"))

        result.isFailure shouldBe true
        val searchEvent = diagnostics.events.first {
            it.stage == ChapterInventoryDiagnosticStage.BINDING_SEARCH
        }
        searchEvent.outcome shouldBe ChapterInventoryDiagnosticOutcome.HTTP_ERROR
        searchEvent.httpStatus shouldBe 403
        searchEvent.reasons[ChapterInventoryDiagnosticReason.HTTP_FORBIDDEN] shouldBe 1
        searchEvent.toString().contains("private provider body") shouldBe false
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

    @Test
    fun `binding diagnostic distinguishes spelling attempts and safe match`() = runTest {
        val diagnostic = RecordingDiagnostics()
        diagnostic.start("title")
        val gateway = FakeReadingSourceGateway(
            searchResultsByQuery = mapOf(
                (7L to "One Punch Man") to listOf(
                    candidate(7L, "/one-punch", "One Punch-Man"),
                ),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 4L,
                sourceId = 7L,
                sourceUrl = "/one-punch",
                language = "en",
                runtimePayload = byteArrayOf(1),
            ),
        )
        resolver(
            FakeContentBindingRepository(null),
            gateway,
            title = "One-Punch Man",
            diagnostics = diagnostic,
        )
            .executeAll("title", AddonId("mangadex")).getOrThrow()

        diagnostic.events.filter { it.stage == ChapterInventoryDiagnosticStage.BINDING_SEARCH }
            .map { it.attempt to it.outcome } shouldBe listOf(
            1 to ChapterInventoryDiagnosticOutcome.EMPTY,
            2 to ChapterInventoryDiagnosticOutcome.SUCCESS,
        )
        diagnostic.events.any {
            it.stage == ChapterInventoryDiagnosticStage.BINDING_MATCH &&
                it.outcome == ChapterInventoryDiagnosticOutcome.SUCCESS
        } shouldBe true
        diagnostic.events.any {
            it.stage == ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION &&
                it.outcome == ChapterInventoryDiagnosticOutcome.SUCCESS
        } shouldBe true
    }

    @Test
    fun `explicit captcha during materialization is not conflated with network IO`() = runTest {
        val diagnostic = RecordingDiagnostics()
        diagnostic.start("title")
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(7L to listOf(candidate(7L, "/one-punch", "One-Punch Man"))),
            materializeFailure = IOException("Shape-selecting captcha detected"),
        )
        val result = resolver(
            FakeContentBindingRepository(null),
            gateway,
            title = "One-Punch Man",
            diagnostics = diagnostic,
        ).executeAll("title", AddonId("mangadex"))

        result.isFailure shouldBe true
        val event = diagnostic.events.single {
            it.stage == ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION
        }
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED
        event.reasons[ChapterInventoryDiagnosticReason.CAPTCHA_CHALLENGE] shouldBe 1
    }

    @Test
    fun `manual MangaBall candidate selection preserves canonical identity`() = runTest {
        val repo = FakeContentBindingRepository(null)
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(
                    candidate(7L, "/one-punch-main", "One-Punch Man"),
                    candidate(7L, "/one-punch-alternative", "One-Punch Man"),
                ),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 45L,
                sourceId = 7L,
                sourceUrl = "/one-punch-alternative",
                language = "pt-BR",
                runtimePayload = byteArrayOf(1, 2, 3),
            ),
        )
        val titleRepo = FakeCanonicalTitleRepository("One-Punch Man")
        val addons = FakeAddonRepository()
        val result = resolver(repo, gateway, title = "One-Punch Man")
            .executeAll("title", AddonId("mangadex"))
        val choices = (result.exceptionOrNull() as ContentBindingConfirmationRequiredException).candidates
        choices.size shouldBe 2
        gateway.materializeCalls shouldBe 0

        val selected = choices.single { it.candidate.sourceUrl == "/one-punch-alternative" }
        val binding = ConfirmContentBinding(
            contentBindingRepository = repo,
            canonicalTitleRepository = titleRepo,
            addonRepository = addons,
            readingSourceGateway = gateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
        ).execute("title", AddonId("mangadex"), selected).getOrThrow()

        binding.canonicalTitleId shouldBe "title"
        binding.providerTitleKey shouldBe "7:/one-punch-alternative"
        binding.verifiedByUser shouldBe true
        repo.getByTitle("title").single().id shouldBe binding.id
        titleRepo.getById("title")?.displayTitle shouldBe "One-Punch Man"
    }

    @Test
    fun `manual binding rejects source disabled for the installed Add-on before materialization`() = runTest {
        val repo = FakeContentBindingRepository(null)
        val gateway = FakeReadingSourceGateway()
        val selected = tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate(
            candidate(8L, "/foreign", "Dandadan"),
            confidence = 1.0,
            sourcePreferenceRank = 0,
        )
        val result = ConfirmContentBinding(
            repo,
            FakeCanonicalTitleRepository("Dandadan"),
            FakeAddonRepository(sourceIds = listOf(7L)),
            gateway,
            ScoreSourceTitleMatch(),
        ).execute("title", AddonId("mangadex"), selected)

        result.isFailure shouldBe true
        gateway.materializeCalls shouldBe 0
        repo.getByTitle("title").isEmpty() shouldBe true
    }

    @Test
    fun `manual binding cannot persist mismatched materialized edition`() = runTest {
        val repo = FakeContentBindingRepository(null)
        val gateway = FakeReadingSourceGateway(
            materialized = MaterializedReadingSource(
                mihonMangaId = 44L,
                sourceId = 7L,
                sourceUrl = "/another-edition",
                language = "en",
                runtimePayload = byteArrayOf(7),
            ),
        )
        val selected = tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate(
            candidate(7L, "/requested", "Dandadan"),
            confidence = 1.0,
            sourcePreferenceRank = 0,
        )
        val result = ConfirmContentBinding(
            repo,
            FakeCanonicalTitleRepository("Dandadan"),
            FakeAddonRepository(),
            gateway,
            ScoreSourceTitleMatch(),
        ).execute("title", AddonId("mangadex"), selected)

        result.isFailure shouldBe true
        repo.getByTitle("title").isEmpty() shouldBe true
    }

    private fun resolver(
        repository: ContentBindingRepository,
        gateway: FakeReadingSourceGateway,
        addonSourceIds: List<Long> = listOf(7L),
        title: String = "Dandadan",
        bindingIdPrefix: String = "new-binding",
        diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
        addonSources: List<AddonSourceEligibility> = emptyList(),
        addonSourceEligibilityRepository: AddonSourceEligibilityRepository? = null,
        addonEnabled: Boolean = true,
    ): ResolveContentBinding {
        var nextId = 0
        return ResolveContentBinding(
            contentBindingRepository = repository,
            canonicalTitleRepository = FakeCanonicalTitleRepository(title),
            addonRepository = FakeAddonRepository(addonSourceIds, enabled = addonEnabled),
            readingSourceGateway = gateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
            idFactory = { "$bindingIdPrefix-${nextId++}" },
            clock = { 200L },
            diagnostics = diagnostics,
            addonSourceEligibilityRepository = addonSourceEligibilityRepository
                ?: AddonSourceEligibilityRepository { addonSources },
        )
    }

    private fun request(
        mode: ContentBindingSearchMode = ContentBindingSearchMode.INITIAL,
        batchSize: Int = 3,
        timeoutMillis: Long = 20_000,
    ) = ContentBindingSearchRequest(
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        mode = mode,
        batchSize = batchSize,
        sourceTimeoutMillis = timeoutMillis,
    )

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

    private fun descriptor(sourceId: Long, language: String) = ReadingSourceDescriptor(
        sourceId = sourceId,
        name = "Source $sourceId",
        language = language,
    )

    private fun materialized(sourceId: Long, sourceUrl: String, language: String) = MaterializedReadingSource(
        mihonMangaId = sourceId * 10,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        language = language,
        runtimePayload = byteArrayOf(sourceId.toByte()),
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

    /** A small SQL unique-index analogue with two controlled read races for separate collectors. */
    private class ConcurrentUniqueContentBindingRepository(
        initial: List<ContentBinding> = emptyList(),
        private val synchronizeConcurrentReads: Boolean = true,
    ) : ContentBindingRepository {
        private val lock = Any()
        private val values = initial.toMutableList()
        private val pairedReadGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        private var getByTitleCalls = 0

        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? =
            synchronized(lock) {
                values.lastOrNull { it.canonicalTitleId == canonicalTitleId && it.addonId == addonId }
            }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> {
            val (gate, snapshot) = synchronized(lock) {
                val callIndex = getByTitleCalls++
                val gate = if (synchronizeConcurrentReads && callIndex < 4) {
                    val pair = callIndex / 2
                    pairedReadGates.getOrPut(pair) { CompletableDeferred() }.also {
                        if (callIndex % 2 == 1) it.complete(Unit)
                    }
                } else {
                    null
                }
                gate to values.filter { it.canonicalTitleId == canonicalTitleId }
            }
            gate?.await()
            return snapshot
        }

        override suspend fun upsert(binding: ContentBinding) {
            synchronized(lock) {
                val conflictingBinding = values.firstOrNull {
                    it.addonId == binding.addonId &&
                        it.providerTitleKey == binding.providerTitleKey &&
                        it.id != binding.id
                }
                check(conflictingBinding == null) { "UNIQUE(addon_id, provider_title_key)" }
                values.removeAll { it.id == binding.id }
                values += binding
            }
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            synchronized(lock) {
                val index = values.indexOfFirst { it.id == bindingId }
                if (index >= 0) {
                    values[index] = values[index].copy(
                        availability = ContentBindingAvailability.UNAVAILABLE,
                        updatedAt = updatedAt,
                    )
                }
            }
        }

        fun snapshot(): List<ContentBinding> = synchronized(lock) { values.toList() }
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
        enabled: Boolean = true,
    ) : AddonRepository {
        private val addon = InstalledAddon(
            id = AddonId("mangadex"),
            displayName = "MangaDex",
            enabled = enabled,
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
        private var active = false
        override fun start(canonicalTitleId: String): String {
            events.clear()
            active = true
            return "test"
        }

        override fun stop() {
            active = false
        }

        override fun clear() {
            events.clear()
            active = false
        }

        override fun isRecording(canonicalTitleId: String): Boolean = active && canonicalTitleId == "title"

        override fun record(event: ChapterInventoryDiagnosticEvent) {
            if (active) events += event
        }

        override fun report(): String = ""
    }

    private class FakeReadingSourceGateway(
        private val searchResults: Map<Long, List<ReadingSourceCandidate>> = emptyMap(),
        private val materialized: MaterializedReadingSource? = null,
        private val materializedBySource: Map<Long, MaterializedReadingSource> = emptyMap(),
        private val searchResultsByQuery: Map<Pair<Long, String>, List<ReadingSourceCandidate>> = emptyMap(),
        private val searchFailure: Throwable? = null,
        private val materializeFailure: Throwable? = null,
        private val installedByLanguage: Map<String, List<ReadingSourceDescriptor>> = emptyMap(),
        private val searchHandler: (suspend (Long, String) -> Result<List<ReadingSourceCandidate>>)? = null,
        private val materializeHandler: (suspend (ReadingSourceCandidate) -> Result<MaterializedReadingSource>)? =
            null,
    ) : ReadingSourceGateway {
        private val callLock = Any()
        var searchCalls = 0
        val searchedSourceIds = mutableListOf<Long>()
        val searchedQueries = mutableListOf<String>()
        var materializeCalls = 0

        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> =
            installedByLanguage[language].orEmpty()

        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            synchronized(callLock) {
                searchCalls += 1
                searchedSourceIds += sourceId
                searchedQueries += query
            }
            val handler = searchHandler
            if (handler != null) return handler(sourceId, query)
            searchFailure?.let { return Result.failure(it) }
            return Result.success(searchResultsByQuery[sourceId to query] ?: searchResults[sourceId].orEmpty())
        }

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
            synchronized(callLock) { materializeCalls += 1 }
            val handler = materializeHandler
            if (handler != null) return handler(candidate)
            materializeFailure?.let { return Result.failure(it) }
            return (materializedBySource[candidate.sourceId] ?: materialized)
                ?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("No materialized source configured"))
        }
    }
}
