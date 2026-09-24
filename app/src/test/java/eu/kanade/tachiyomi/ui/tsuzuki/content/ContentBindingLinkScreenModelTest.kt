package eu.kanade.tachiyomi.ui.tsuzuki.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailure
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureKind
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureStage
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchMode
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchProgress
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchRequest
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceOutcome
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate

@OptIn(ExperimentalCoroutinesApi::class)
class ContentBindingLinkScreenModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `enabled installed add-ons are unique by package and disabled or source-less add-ons are hidden`() = runTest(dispatcher) {
        val packageAddon = addon("pkg.reader", "Reader", enabled = true, sourceIds = listOf(7L))
        val addons = listOf(
            packageAddon,
            packageAddon.copy(mihonSourceIds = listOf(8L)),
            addon("pkg.disabled", "Disabled", enabled = false, sourceIds = listOf(9L)),
            addon("pkg.empty", "Empty", enabled = true, sourceIds = emptyList()),
        )
        val model = model(addons = addons)

        model.start("canonical")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.Addons>()
        state.enabled.map { it.id } shouldBe listOf(AddonId("pkg.reader"))
        state.enabled.single().mihonSourceIds shouldBe listOf(7L)
    }

    @Test
    fun `existing bindings are informational while a new bounded search still runs`() = runTest(dispatcher) {
        val reader = addon("reader", "Reader", enabled = true, sourceIds = listOf(7L))
        val resolver = mockk<ResolveContentBinding>()
        every { resolver.searchProgress(any()) } returns flowOf(
            ContentBindingSearchProgress.ExistingBindingsObserved(bindingCount = 3),
            source(7L, ContentBindingSourceOutcome.EMPTY),
            ContentBindingSearchProgress.Completed(queriedSourceIds = listOf(7L), remainingSourceCount = 0),
        )
        val model = model(addons = listOf(reader), resolver = resolver)

        model.start("canonical")
        advanceUntilIdle()
        model.selectAddon(reader.id)
        advanceUntilIdle()

        val result = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        result.existingBindingCount shouldBe 3
        result.boundCount shouldBe 0
        result.emptySourceCount shouldBe 1
        result.confirmationCandidates shouldBe emptyList()
        verify(exactly = 1) { resolver.searchProgress(match { it.mode == ContentBindingSearchMode.INITIAL }) }
    }

    @Test
    fun `initial pass orders title and global preferred languages and returns progressive peer results`() = runTest(dispatcher) {
        val reader = addon("reader", "Reader", enabled = true, sourceIds = listOf(7L, 8L, 9L))
        val preferences = mockk<ContentPreferenceRepository>()
        coEvery { preferences.get("canonical") } returns ContentPreference(
            canonicalTitleId = "canonical",
            preferredAddonId = null,
            preferredLanguage = "pt-BR",
            updatedAt = 1L,
        )
        val globalPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore()).also {
            it.preferredLanguages.set(listOf("en", "es"))
        }
        val firstSourceObserved = CompletableDeferred<Unit>()
        val releasePeer = CompletableDeferred<Unit>()
        val resolver = mockk<ResolveContentBinding>()
        val capturedRequest = slot<ContentBindingSearchRequest>()
        every { resolver.searchProgress(capture(capturedRequest)) } returns flow {
            emit(source(7L, ContentBindingSourceOutcome.BOUND, bindings = listOf(binding("first"))))
            firstSourceObserved.complete(Unit)
            releasePeer.await()
            emit(
                source(
                    8L,
                    ContentBindingSourceOutcome.FAILURE,
                    failure = ContentBindingSearchFailure(
                        ContentBindingSearchFailureStage.SEARCH,
                        ContentBindingSearchFailureKind.NETWORK_FAILURE,
                    ),
                ),
            )
            emit(ContentBindingSearchProgress.Completed(listOf(7L, 8L, 9L), remainingSourceCount = 0))
        }
        val model = model(
            addons = listOf(reader),
            resolver = resolver,
            contentPreferences = preferences,
            readerPreferences = globalPreferences,
        )

        model.start("canonical")
        advanceUntilIdle()
        model.selectAddon(reader.id)
        runCurrent()

        val partial = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        partial.isSearching shouldBe true
        partial.boundCount shouldBe 1
        partial.failureCount shouldBe 0
        firstSourceObserved.isCompleted shouldBe true

        releasePeer.complete(Unit)
        advanceUntilIdle()

        capturedRequest.captured.preferredLanguages shouldBe listOf("pt-BR", "en", "es")
        capturedRequest.captured.mode shouldBe ContentBindingSearchMode.INITIAL
        capturedRequest.captured.batchSize shouldBe ContentBindingSearchRequest.DEFAULT_BATCH_SIZE
        val completed = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        completed.isSearching shouldBe false
        completed.boundCount shouldBe 1
        completed.failureCount shouldBe 1
        completed.failureKinds shouldBe listOf(ContentBindingSearchFailureKind.NETWORK_FAILURE)
        completed.queriedSourceIds shouldBe setOf(7L, 8L, 9L)
    }

    @Test
    fun `search more broadens only to unqueried enabled source ids and is hidden when complete`() = runTest(dispatcher) {
        val reader = addon("reader", "Reader", enabled = true, sourceIds = listOf(7L, 8L, 9L, 10L))
        val resolver = mockk<ResolveContentBinding>()
        val requests = mutableListOf<ContentBindingSearchRequest>()
        every { resolver.searchProgress(any()) } answers {
            val request = firstArg<ContentBindingSearchRequest>()
            requests += request
            when (request.mode) {
                ContentBindingSearchMode.INITIAL -> flowOf(
                    source(7L, ContentBindingSourceOutcome.EMPTY),
                    source(8L, ContentBindingSourceOutcome.NO_MATCH),
                    source(9L, ContentBindingSourceOutcome.CONFIRMATION_REQUIRED, candidates = listOf(candidate(9L, "/one"))),
                    ContentBindingSearchProgress.Completed(listOf(7L, 8L, 9L), remainingSourceCount = 1),
                )
                ContentBindingSearchMode.BROADEN -> flowOf(
                    source(10L, ContentBindingSourceOutcome.EMPTY),
                    ContentBindingSearchProgress.Completed(listOf(10L), remainingSourceCount = 0),
                )
            }
        }
        val model = model(addons = listOf(reader), resolver = resolver)

        model.start("canonical")
        advanceUntilIdle()
        model.selectAddon(reader.id)
        advanceUntilIdle()

        var result = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        result.queriedSourceIds shouldBe setOf(7L, 8L, 9L)
        result.remainingSourceCount shouldBe 1
        result.confirmationCandidates.single().candidate.sourceUrl shouldBe "/one"

        model.searchMore()
        advanceUntilIdle()

        result = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        result.queriedSourceIds shouldBe setOf(7L, 8L, 9L, 10L)
        result.remainingSourceCount shouldBe 0
        result.emptySourceCount shouldBe 2
        requests.map { it.mode } shouldBe listOf(ContentBindingSearchMode.INITIAL, ContentBindingSearchMode.BROADEN)
        requests.last().alreadyQueriedSourceIds shouldBe setOf(7L, 8L, 9L)

        model.searchMore()
        advanceUntilIdle()
        requests.size shouldBe 2
    }

    @Test
    fun `ambiguous editions stay unbound until the exact displayed candidate is confirmed`() = runTest(dispatcher) {
        val reader = addon("reader", "Reader", enabled = true, sourceIds = listOf(7L))
        val choices = listOf(candidate(7L, "/edition-one"), candidate(7L, "/edition-two"))
        val resolver = mockk<ResolveContentBinding>()
        every { resolver.searchProgress(any()) } returns flowOf(
            source(7L, ContentBindingSourceOutcome.CONFIRMATION_REQUIRED, candidates = choices),
            ContentBindingSearchProgress.Completed(listOf(7L), remainingSourceCount = 0),
        )
        val confirm = mockk<ConfirmContentBinding>()
        coEvery { confirm.execute("canonical", reader.id, choices.last()) } returns Result.success(binding("confirmed"))
        val model = model(addons = listOf(reader), resolver = resolver, confirm = confirm)

        model.start("canonical")
        advanceUntilIdle()
        model.selectAddon(reader.id)
        advanceUntilIdle()

        var result = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        result.boundCount shouldBe 0
        result.confirmationCandidates.map { it.candidate.sourceUrl } shouldBe listOf("/edition-one", "/edition-two")

        model.confirm(candidate(7L, "/not-listed"))
        advanceUntilIdle()
        verify(exactly = 0) { confirm.execute(any(), any(), any()) }

        model.confirm(choices.last())
        advanceUntilIdle()

        result = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        result.boundCount shouldBe 1
        result.confirmationCandidates.map { it.candidate.sourceUrl } shouldBe listOf("/edition-one")
        verify(exactly = 1) { confirm.execute("canonical", reader.id, choices.last()) }
    }

    @Test
    fun `a cancelled stale search cannot overwrite a newer add-on selection`() = runTest(dispatcher) {
        val first = addon("first", "First", enabled = true, sourceIds = listOf(7L))
        val second = addon("second", "Second", enabled = true, sourceIds = listOf(8L))
        val resolver = mockk<ResolveContentBinding>()
        every { resolver.searchProgress(match { it.addonId == first.id }) } returns flow {
            emit(source(7L, ContentBindingSourceOutcome.BOUND, bindings = listOf(binding("stale"))))
            awaitCancellation()
        }
        every { resolver.searchProgress(match { it.addonId == second.id }) } returns flowOf(
            source(8L, ContentBindingSourceOutcome.FAILURE),
            ContentBindingSearchProgress.Completed(listOf(8L), remainingSourceCount = 0),
        )
        val model = model(addons = listOf(first, second), resolver = resolver)

        model.start("canonical")
        advanceUntilIdle()
        model.selectAddon(first.id)
        runCurrent()
        model.selectAddon(second.id)
        advanceUntilIdle()

        val result = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.SearchResults>()
        result.addon.id shouldBe second.id
        result.boundCount shouldBe 0
        result.failureCount shouldBe 1
        result.isSearching shouldBe false
    }

    private fun model(
        addons: List<InstalledAddon>,
        resolver: ResolveContentBinding = mockk(),
        confirm: ConfirmContentBinding = mockk(),
        contentPreferences: ContentPreferenceRepository = mockk<ContentPreferenceRepository>().also {
            coEvery { it.get(any()) } returns null
        },
        readerPreferences: CanonicalReaderPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore()),
    ) = ContentBindingLinkScreenModel(
        addonRepository = repo(addons),
        resolveContentBinding = resolver,
        confirmContentBinding = confirm,
        contentPreferenceRepository = contentPreferences,
        readerPreferences = readerPreferences,
    )

    private fun repo(addons: List<InstalledAddon>) = object : AddonRepository {
        override fun observeInstalled() = flowOf(addons)
        override suspend fun snapshot() = addons
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private fun addon(id: String, name: String, enabled: Boolean, sourceIds: List<Long>) = InstalledAddon(
        id = AddonId(id),
        displayName = name,
        enabled = enabled,
        versionName = "1.0",
        mihonSourceIds = sourceIds,
        hasSettings = false,
    )

    private fun candidate(id: Long, url: String) = ScoredSourceCandidate(
        candidate = ReadingSourceCandidate(
            sourceId = id,
            sourceName = "Reader internal source",
            language = "pt-BR",
            sourceUrl = url,
            title = "One-Punch Man",
            thumbnailUrl = null,
            author = null,
            artist = null,
            description = null,
            genres = null,
            status = 0L,
        ),
        confidence = 0.88,
        sourcePreferenceRank = 0,
    )

    private fun binding(id: String) = ContentBinding(
        id = id,
        canonicalTitleId = "canonical",
        addonId = AddonId("reader"),
        providerTitleKey = "opaque-key",
        matchConfidence = 0.88,
        verifiedByUser = false,
        availability = ContentBindingAvailability.AVAILABLE,
        runtimePayload = byteArrayOf(1),
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun source(
        sourceId: Long,
        outcome: ContentBindingSourceOutcome,
        candidates: List<ScoredSourceCandidate> = emptyList(),
        bindings: List<ContentBinding> = emptyList(),
        failure: ContentBindingSearchFailure? = null,
    ) = ContentBindingSearchProgress.SourceCompleted(
        sourceId = sourceId,
        language = "en",
        outcome = outcome,
        candidates = candidates,
        bindings = bindings,
        failure = failure,
    )

}
