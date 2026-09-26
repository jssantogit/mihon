package eu.kanade.tachiyomi.ui.tsuzuki.content

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.ContentOptionLookup
import tachiyomi.domain.tsuzuki.content.interactor.DiscoverReadableChapter
import tachiyomi.domain.tsuzuki.content.interactor.FastDiscoveryCompletion
import tachiyomi.domain.tsuzuki.content.interactor.FastDiscoveryFailureStage
import tachiyomi.domain.tsuzuki.content.interactor.FastReadingDiscoveryEvent
import tachiyomi.domain.tsuzuki.content.interactor.PlannedAddonSearch
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class ContentSelectorScreenModelTest {

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
    fun `binding refresh waits for reconciliation before showing newly available chapter options`() = runTest(
        dispatcher,
    ) {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val linkedOption = option("reader", "en", null, 1L)
        var reconciled = false
        var resolutions = 0
        val provider = object : ContentProvider {
            override val addonId = AddonId("reader")
            override suspend fun resolve(
                canonicalTitleId: String,
                canonicalChapterId: String,
            ): Result<List<ContentOption>> {
                resolutions++
                return Result.success(if (reconciled) listOf(linkedOption) else emptyList())
            }
        }
        val refresh = mockk<RefreshChapterEvidence>()
        coEvery { refresh.execute("title-1") } coAnswers {
            started.complete(Unit)
            gate.await()
            reconciled = true
            Result.success(Unit)
        }
        val model = model(
            providers = listOf(provider),
            addons = listOf(addon("reader", "Reader")),
            bindingRefresh = refresh,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
        resolutions shouldBe 1

        val refreshJob = async { model.refreshAfterBinding("title-1") }
        runCurrent()
        started.isCompleted shouldBe true
        resolutions shouldBe 1
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Loading>()

        gate.complete(Unit)
        advanceUntilIdle()
        refreshJob.await().isSuccess shouldBe true
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
            .options.single().option.key shouldBe linkedOption.key
        resolutions shouldBe 2
    }

    @Test
    fun `failed binding evidence refresh never turns a missing chapter into a false reading option`() = runTest(
        dispatcher,
    ) {
        val refresh = mockk<RefreshChapterEvidence>()
        coEvery { refresh.execute("title-1") } returns Result.failure(IllegalStateException("evidence unavailable"))
        var resolutions = 0
        val provider = object : ContentProvider {
            override val addonId = AddonId("reader")
            override suspend fun resolve(
                canonicalTitleId: String,
                canonicalChapterId: String,
            ): Result<List<ContentOption>> {
                resolutions++
                return Result.success(emptyList())
            }
        }
        val model = model(
            providers = listOf(provider),
            addons = listOf(addon("reader", "Reader")),
            bindingRefresh = refresh,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        val outcome = model.refreshAfterBinding("title-1")
        advanceUntilIdle()

        outcome.isFailure shouldBe true
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Error>()
            .error.message shouldBe "evidence unavailable"
        resolutions shouldBe 1
    }

    @Test
    fun `late binding refresh cannot replace the selector of a different chapter`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val refresh = mockk<RefreshChapterEvidence>()
        coEvery { refresh.execute("title-1") } coAnswers {
            started.complete(Unit)
            gate.await()
            Result.success(Unit)
        }
        val secondOption = option("reader", "en", null, 1L).copy(
            key = "reader:chapter-2",
            canonicalChapterId = "chapter-2",
        )
        var firstChapterCalls = 0
        val provider = object : ContentProvider {
            override val addonId = AddonId("reader")
            override suspend fun resolve(
                canonicalTitleId: String,
                canonicalChapterId: String,
            ): Result<List<ContentOption>> = Result.success(
                if (canonicalChapterId == "chapter-2") {
                    listOf(secondOption)
                } else {
                    firstChapterCalls++
                    emptyList()
                },
            )
        }
        val model = model(
            providers = listOf(provider),
            addons = listOf(addon("reader", "Reader")),
            bindingRefresh = refresh,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        val pending = async { model.refreshAfterBinding("title-1") }
        runCurrent()
        started.isCompleted shouldBe true
        model.start("title-1", "chapter-2")
        runCurrent()
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Loading>()

        gate.complete(Unit)
        advanceUntilIdle()
        pending.await().isSuccess shouldBe true
        val ready = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        ready.canonicalChapterId shouldBe "chapter-2"
        ready.options.single().option.key shouldBe secondOption.key
        firstChapterCalls shouldBe 1
    }

    @Test
    fun `selector exposes every ranked option with presentation metadata`() = runTest(dispatcher) {
        val first = option(
            addonId = "mangadex",
            language = "en",
            scanlationGroup = "Group A",
            releaseDate = 100L,
        )
        val second = option(
            addonId = "mangafire",
            language = "pt-BR",
            scanlationGroup = "Grupo B",
            releaseDate = 200L,
        )
        val preferences = FakeContentPreferenceRepository(null)
        val model = model(
            providers = listOf(
                provider("mangadex", Result.success(listOf(first))),
                provider("mangafire", Result.success(listOf(second))),
            ),
            addons = listOf(
                addon("mangadex", "MangaDex"),
                addon("mangafire", "MangaFire"),
            ),
            preferenceRepository = preferences,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        state.options.map { it.option.key }.shouldContainExactlyInAnyOrder(first.key, second.key)

        val dex = state.options.first { it.option.key == first.key }
        dex.addonDisplayName shouldBe "MangaDex"
        dex.language shouldBe "en"
        dex.scanlationGroup shouldBe "Group A"
        dex.releaseDate shouldBe 100L

        val firstReadSelection = model.select(dex)
        firstReadSelection.offerSetAsPreferred shouldBe false
        firstReadSelection.rememberFirstPreference shouldBe true
        advanceUntilIdle()
        preferences.value shouldBe null

        // The Reader calls this only after successful page preparation.
        model.confirmInitialPreferred(firstReadSelection)
        advanceUntilIdle()
        preferences.value?.preferredAddonId shouldBe AddonId("mangadex")
        preferences.value?.updatedAt shouldBe 500L

        val fire = state.options.first { it.option.key == second.key }
        fire.addonDisplayName shouldBe "MangaFire"
        fire.language shouldBe "pt-BR"
        fire.scanlationGroup shouldBe "Grupo B"
        fire.releaseDate shouldBe 200L
    }

    @Test
    fun `failed first content choice never becomes preferred`() = runTest(dispatcher) {
        val preferences = FakeContentPreferenceRepository(null)
        val candidate = option("mangafire", "en", null, 10L)
        val model = model(
            providers = listOf(provider("mangafire", Result.success(listOf(candidate)))),
            addons = listOf(addon("mangafire", "MangaFire")),
            preferenceRepository = preferences,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        val choice = model.select(
            model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>().options.single(),
        )
        choice.rememberFirstPreference shouldBe true

        // A Reader preparation failure does not call confirmInitialPreferred.
        advanceUntilIdle()
        preferences.value shouldBe null
    }

    @Test
    fun `multi-language preferred Add-on exposes only one effective preferred option`() = runTest(dispatcher) {
        val preferences = FakeContentPreferenceRepository(
            ContentPreference(
                canonicalTitleId = "title-1",
                preferredAddonId = AddonId("mangafire"),
                updatedAt = 10L,
            ),
        )
        val en = option("mangafire", "en", null, 200L).copy(key = "mangafire:en")
        val pt = option("mangafire", "pt-BR", null, 100L).copy(key = "mangafire:pt")
        val model = model(
            providers = listOf(provider("mangafire", Result.success(listOf(en, pt)))),
            addons = listOf(addon("mangafire", "MangaFire")),
            preferenceRepository = preferences,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        ready.preferredAddonId shouldBe AddonId("mangafire")
        ready.preferredOptionKey shouldBe ready.options.first().option.key
        ready.options.count { it.option.key == ready.preferredOptionKey } shouldBe 1
    }

    @Test
    fun `same addon language switch offers title language preference separately`() = runTest(dispatcher) {
        val preferences = FakeContentPreferenceRepository(
            ContentPreference("title-1", AddonId("mangafire"), 10L, "en"),
        )
        val en = option("mangafire", "en", null, 200L).copy(key = "mf:en")
        val pt = option("mangafire", "pt-BR", null, 100L).copy(key = "mf:pt")
        val model = model(
            providers = listOf(provider("mangafire", Result.success(listOf(en, pt)))),
            addons = listOf(addon("mangafire", "MangaFire")),
            preferenceRepository = preferences,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        val selection = model.select(state.options.first { it.option.key == "mf:pt" })
        selection.offerSetAsPreferred shouldBe false
        selection.offerSetLanguagePreferred shouldBe true
        preferences.value?.preferredLanguage shouldBe "en"
        model.confirmPreferredLanguage(selection)
        advanceUntilIdle()
        preferences.value?.preferredAddonId shouldBe AddonId("mangafire")
        preferences.value?.preferredLanguage shouldBe "pt-BR"
    }

    @Test
    fun `changing addon keeps title language preference`() = runTest(dispatcher) {
        val preferences = FakeContentPreferenceRepository(
            ContentPreference("title-1", AddonId("mangadex"), 10L, "pt-BR"),
        )
        val model = model(
            providers = listOf(provider("mangafire", Result.success(listOf(option("mangafire", "en", null, 10L))))),
            addons = listOf(addon("mangafire", "MangaFire")),
            preferenceRepository = preferences,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        val selection = model.select(
            model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>().options.single(),
        )
        model.confirmPreferred(selection)
        advanceUntilIdle()
        preferences.value?.preferredAddonId shouldBe AddonId("mangafire")
        preferences.value?.preferredLanguage shouldBe "pt-BR"
    }

    @Test
    fun `provider failure does not hide successful alternatives`() = runTest(dispatcher) {
        val healthy = option("healthy", "en", null, 100L)
        val model = model(
            providers = listOf(
                provider("broken", Result.failure(IllegalStateException("offline"))),
                provider("healthy", Result.success(listOf(healthy))),
            ),
            addons = listOf(addon("healthy", "Healthy Add-on")),
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        state.options.map { it.option.key } shouldBe listOf(healthy.key)
        state.failedProviderCount shouldBe 1
    }

    @Test
    fun `all provider failures show a retryable error instead of no chapters`() = runTest(dispatcher) {
        val model = model(
            providers = listOf(
                provider("offline", Result.failure(IllegalStateException("network timeout"))),
            ),
            addons = listOf(addon("offline", "Offline Add-on")),
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Error>()
        state.error.message shouldBe "Could not query 1 reading Add-on(s). Retry or choose another source."
    }

    @Test
    fun `a throwing provider does not prevent healthy Add-ons from appearing`() = runTest(dispatcher) {
        val throwing = object : ContentProvider {
            override val addonId = AddonId("throwing")

            override suspend fun resolve(
                canonicalTitleId: String,
                canonicalChapterId: String,
            ): Result<List<ContentOption>> = throw IllegalStateException("Provider crashed")
        }
        val healthy = option("healthy", "en", null, 100L)
        val model = model(
            providers = listOf(
                throwing,
                provider("healthy", Result.success(listOf(healthy))),
            ),
            addons = listOf(addon("healthy", "Healthy Add-on")),
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        state.options.map { it.option.key } shouldBe listOf(healthy.key)
        state.failedProviderCount shouldBe 1
    }

    @Test
    fun `manual fallback offers preference change without mutating until confirmation`() = runTest(dispatcher) {
        val preferences = FakeContentPreferenceRepository(
            ContentPreference(
                canonicalTitleId = "title-1",
                preferredAddonId = AddonId("preferred"),
                updatedAt = 10L,
            ),
        )
        val fallback = option("fallback", "en", null, 100L)
        val model = model(
            providers = listOf(
                provider("preferred", Result.success(emptyList())),
                provider("fallback", Result.success(listOf(fallback))),
            ),
            addons = listOf(
                addon("preferred", "Preferred"),
                addon("fallback", "Fallback"),
            ),
            preferenceRepository = preferences,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        ready.preferredUnavailable shouldBe true
        val selection = model.select(ready.options.single())

        selection.option shouldBe fallback
        selection.offerSetAsPreferred shouldBe true
        preferences.value?.preferredAddonId shouldBe AddonId("preferred")

        model.confirmPreferred(selection)
        advanceUntilIdle()

        preferences.value?.preferredAddonId shouldBe AddonId("fallback")
        preferences.value?.updatedAt shouldBe 500L
    }

    @Test
    fun `empty resolution is explicit and retryable`() = runTest(dispatcher) {
        val model = model(
            providers = listOf(provider("empty", Result.success(emptyList()))),
            addons = listOf(addon("empty", "Empty")),
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()

        model.retry()
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
    }

    @Test
    fun `selector distinguishes no enabled Add-ons from missing chapter matches`() = runTest(dispatcher) {
        val disabled = addon("offline-addon", "Offline Add-on").copy(enabled = false)
        val model = model(
            providers = emptyList(),
            addons = listOf(disabled),
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
            .noEnabledAddon shouldBe true
    }

    @Test
    fun `selector reports missing matches separately when an Add-on is enabled`() = runTest(dispatcher) {
        val model = model(
            providers = listOf(provider("enabled", Result.success(emptyList()))),
            addons = listOf(addon("enabled", "Enabled Add-on")),
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
            .noEnabledAddon shouldBe false
    }

    @Test
    fun `unexpected selector dependency failure maps to error state`() = runTest(dispatcher) {
        val model = model(
            providers = listOf(provider("healthy", Result.success(listOf(option("healthy", "en", null, 1L))))),
            addons = emptyList(),
            addonRepository = object : AddonRepository {
                override fun observeInstalled(): Flow<List<InstalledAddon>> = MutableStateFlow(emptyList())
                override suspend fun snapshot(): List<InstalledAddon> = error("addon repository failed")
                override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
            },
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Error>()
        state.error.message shouldBe "addon repository failed"
    }

    @Test
    fun `manual link refreshes only the new edition and presents its chapter`() = runTest(dispatcher) {
        val edition = ContentBinding(
            id = "new-edition",
            canonicalTitleId = "title-1",
            addonId = AddonId("reader"),
            providerTitleKey = "7:/original",
            matchConfidence = 0.99,
            verifiedByUser = true,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 2L,
        )
        val verified = option("reader", "en", null, 1L)
        val refresher = mockk<RefreshChapterEvidence>()
        coEvery { refresher.executeForBinding(edition) } returns Result.success(Unit)
        val resolver = mockk<ResolveChapterContent>()
        coEvery { resolver.lookupOptions("title-1", "chapter-1", any()) } returns
            ContentOptionLookup(emptyList(), emptyList(), 1)
        coEvery { resolver.lookupBindingOptions(edition, "chapter-1") } returns
            ContentOptionLookup(listOf(verified), emptyList(), 1)
        val model = ContentSelectorScreenModel(
            resolveChapterContent = resolver,
            contentPreferenceRepository = FakeContentPreferenceRepository(null),
            addonRepository = FakeAddonRepository(listOf(addon("reader", "Reader"))),
            clock = { 500L },
            refreshChapterEvidence = refresher,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()

        val result = model.refreshAfterBindings(BindingRefreshRequest("title-1", listOf(edition)))

        result.isSuccess shouldBe true
        val ready = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        ready.options.single().option shouldBe verified
        ready.options.single().addonDisplayName shouldBe "Reader"
        coVerify(exactly = 1) { refresher.executeForBinding(edition) }
        coVerify(exactly = 0) { refresher.execute(any()) }
        coVerify(exactly = 1) { resolver.lookupBindingOptions(edition, "chapter-1") }
    }

    @Test
    fun `one failing manual edition cannot hide a healthy edition`() = runTest(dispatcher) {
        val valid = ContentBinding(
            id = "valid",
            canonicalTitleId = "title-1",
            addonId = AddonId("reader"),
            providerTitleKey = "7:/original",
            matchConfidence = 0.99,
            verifiedByUser = true,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 2L,
        )
        val failed = valid.copy(id = "failed", providerTitleKey = "8:/original")
        val verified = option("reader", "en", null, 1L)
        val refresher = mockk<RefreshChapterEvidence>()
        coEvery { refresher.executeForBinding(failed) } returns
            Result.failure(IllegalStateException("Provider unavailable"))
        coEvery { refresher.executeForBinding(valid) } returns Result.success(Unit)
        val resolver = mockk<ResolveChapterContent>()
        coEvery { resolver.lookupBindingOptions(valid, "chapter-1") } returns
            ContentOptionLookup(listOf(verified), emptyList(), 1)
        val model = ContentSelectorScreenModel(
            resolveChapterContent = resolver,
            contentPreferenceRepository = FakeContentPreferenceRepository(null),
            addonRepository = FakeAddonRepository(listOf(addon("reader", "Reader"))),
            clock = { 500L },
            refreshChapterEvidence = refresher,
        )
        model.start("title-1", "chapter-1")
        advanceUntilIdle()
        val request = BindingRefreshRequest("title-1", listOf(failed, valid))

        model.refreshAfterBindings(request).isSuccess shouldBe true

        val ready = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        ready.options.single().option shouldBe verified
        ready.failedProviderCount shouldBe 1
        coVerify(exactly = 0) { refresher.execute(any()) }
    }

    @Test
    fun `first verified option appears before other sources finish`() = runTest(dispatcher) {
        val discovered = option("reader", "en", "Team", 10L)
        val discovery = mockk<DiscoverReadableChapter>()
        every { discovery.discover("title-1", "chapter-1", any()) } returns flow {
            emit(
                FastReadingDiscoveryEvent.Searching(
                    listOf(PlannedAddonSearch(AddonId("reader"), setOf(7L), batchSize = 1)),
                ),
            )
            emit(FastReadingDiscoveryEvent.Ready(listOf(discovered), alreadyAvailable = false))
            awaitCancellation()
        }
        val model = model(
            providers = emptyList(),
            addons = listOf(addon("reader", "Reader")),
            discovery = discovery,
        )

        val job = model.start("title-1", "chapter-1")
        runCurrent()
        val state = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        state.options.single().option shouldBe discovered
        state.options.single().addonDisplayName shouldBe "Reader"
        job.isActive shouldBe true

        val selection = model.select(state.options.single())
        selection.option shouldBe discovered
        runCurrent()
        job.isActive shouldBe false
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        verify(exactly = 1) { discovery.discover("title-1", "chapter-1", any()) }
    }

    @Test
    fun `a late provider failure never replaces a verified option`() = runTest(dispatcher) {
        val verified = option("reader", "en", null, 1L)
        val discovery = mockk<DiscoverReadableChapter>()
        every { discovery.discover("title-1", "chapter-1", any()) } returns flowOf(
            FastReadingDiscoveryEvent.Searching(
                listOf(PlannedAddonSearch(AddonId("reader"), setOf(7L), batchSize = 1)),
            ),
            FastReadingDiscoveryEvent.Ready(listOf(verified), alreadyAvailable = false),
            FastReadingDiscoveryEvent.SourceFailed(
                addonId = AddonId("broken"),
                sourceId = 8L,
                stage = FastDiscoveryFailureStage.SEARCH,
            ),
            FastReadingDiscoveryEvent.Completed(FastDiscoveryCompletion.TIME_BUDGET, emptyMap()),
        )
        val model = model(
            providers = emptyList(),
            addons = listOf(addon("reader", "Reader")),
            discovery = discovery,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Ready>()
        ready.options.single().option shouldBe verified
        ready.failedProviderCount shouldBe 1
    }

    @Test
    fun `visible deadline stops a stuck source`() = runTest(dispatcher) {
        val discovery = mockk<DiscoverReadableChapter>()
        every { discovery.discover("title-1", "chapter-1", any()) } returns flow {
            emit(
                FastReadingDiscoveryEvent.Searching(
                    listOf(PlannedAddonSearch(AddonId("reader"), setOf(7L), batchSize = 1)),
                ),
            )
            awaitCancellation()
        }
        val model = model(
            providers = emptyList(),
            addons = listOf(addon("reader", "Reader")),
            discovery = discovery,
        )
        val pending = model.start("title-1", "chapter-1")
        runCurrent()
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Discovering>()

        advanceTimeBy(10_000)
        runCurrent()

        pending.isActive shouldBe false
        val empty = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
        empty.timedOut shouldBe true
        empty.discoveryAttempted shouldBe true
        empty.canonicalChapterId shouldBe "chapter-1"
    }

    @Test
    fun `auto search timeout leaves usable manual source actions instead of endless loading`() = runTest(dispatcher) {
        val discovery = mockk<DiscoverReadableChapter>()
        every { discovery.discover("title-1", "chapter-1", any()) } returns flowOf(
            FastReadingDiscoveryEvent.Searching(
                listOf(PlannedAddonSearch(AddonId("reader"), setOf(7L), batchSize = 1)),
            ),
            FastReadingDiscoveryEvent.Completed(FastDiscoveryCompletion.TIME_BUDGET, emptyMap()),
        )
        val model = model(
            providers = emptyList(),
            addons = listOf(addon("reader", "Reader")),
            discovery = discovery,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val empty = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
        empty.discoveryAttempted shouldBe true
        empty.timedOut shouldBe true
        empty.noEnabledAddon shouldBe false
    }

    @Test
    fun `cancelling discovery keeps chapter and returns to explicit source selection`() = runTest(dispatcher) {
        val discovery = mockk<DiscoverReadableChapter>()
        every { discovery.discover("title-1", "chapter-1", any()) } returns flow {
            emit(
                FastReadingDiscoveryEvent.Searching(
                    listOf(PlannedAddonSearch(AddonId("reader"), setOf(7L), batchSize = 1)),
                ),
            )
            awaitCancellation()
        }
        val model = model(
            providers = emptyList(),
            addons = listOf(addon("reader", "Reader")),
            discovery = discovery,
        )
        val pending = model.start("title-1", "chapter-1")
        runCurrent()
        model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Discovering>()

        model.cancelDiscovery()
        runCurrent()

        pending.isActive shouldBe false
        val empty = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
        empty.canonicalChapterId shouldBe "chapter-1"
        empty.discoveryAttempted shouldBe true
    }

    @Test
    fun `ambiguous discovery asks for confirmation instead of offering a chapter`() = runTest(dispatcher) {
        val discovery = mockk<DiscoverReadableChapter>()
        every { discovery.discover("title-1", "chapter-1", any()) } returns flowOf(
            FastReadingDiscoveryEvent.Completed(FastDiscoveryCompletion.CONFIRMATION_REQUIRED, emptyMap()),
        )
        val model = model(
            providers = emptyList(),
            addons = listOf(addon("reader", "Reader")),
            discovery = discovery,
        )

        model.start("title-1", "chapter-1")
        advanceUntilIdle()

        val empty = model.state.value.shouldBeInstanceOf<ContentSelectorScreenState.Empty>()
        empty.confirmationRequired shouldBe true
        empty.discoveryAttempted shouldBe true
    }

    private fun model(
        providers: List<ContentProvider>,
        addons: List<InstalledAddon>,
        preferenceRepository: FakeContentPreferenceRepository = FakeContentPreferenceRepository(null),
        addonRepository: AddonRepository = FakeAddonRepository(addons),
        bindingRefresh: RefreshChapterEvidence? = null,
        discovery: DiscoverReadableChapter? = null,
    ): ContentSelectorScreenModel {
        val readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        val resolver = ResolveChapterContent(
            addonRegistry = FakeAddonRegistry(providers),
            contentPreferenceRepository = preferenceRepository,
            readerPreferences = readerPreferences,
            rankContentOptions = RankContentOptions(),
            contentOptionCache = ContentOptionCache(),
            inFlightContentResolution = InFlightContentResolution(),
        )
        return ContentSelectorScreenModel(
            resolveChapterContent = resolver,
            contentPreferenceRepository = preferenceRepository,
            addonRepository = addonRepository,
            clock = { 500L },
            refreshChapterEvidence = bindingRefresh,
            discoverReadableChapter = discovery,
        )
    }

    private fun provider(
        id: String,
        result: Result<List<ContentOption>>,
    ): ContentProvider = object : ContentProvider {
        override val addonId = AddonId(id)

        override suspend fun resolve(
            canonicalTitleId: String,
            canonicalChapterId: String,
        ): Result<List<ContentOption>> = result
    }

    private fun option(
        addonId: String,
        language: String?,
        scanlationGroup: String?,
        releaseDate: Long?,
    ) = ContentOption(
        key = "$addonId:chapter-1",
        canonicalChapterId = "chapter-1",
        addonId = AddonId(addonId),
        language = language,
        scanlationGroup = scanlationGroup,
        releaseDate = releaseDate,
        delivery = ContentDelivery.LocalArchive("content://$addonId/chapter-1.cbz"),
    )

    private fun addon(id: String, name: String) = InstalledAddon(
        id = AddonId(id),
        displayName = name,
        enabled = true,
        versionName = "1.0",
        mihonSourceIds = emptyList(),
        hasSettings = false,
    )

    private class FakeAddonRegistry(
        private val providers: List<ContentProvider>,
    ) : AddonRegistry {
        override fun contentProviders(): List<ContentProvider> = providers
        override fun chapterProbeProviders(): List<ChapterProbeProvider> = emptyList()
    }

    private class FakeAddonRepository(
        private val addons: List<InstalledAddon>,
    ) : AddonRepository {
        override fun observeInstalled(): Flow<List<InstalledAddon>> = MutableStateFlow(addons)
        override suspend fun snapshot(): List<InstalledAddon> = addons
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class FakeContentPreferenceRepository(
        var value: ContentPreference?,
    ) : ContentPreferenceRepository {
        override suspend fun get(canonicalTitleId: String): ContentPreference? =
            value?.takeIf { it.canonicalTitleId == canonicalTitleId }

        override fun observe(canonicalTitleId: String): Flow<ContentPreference?> =
            MutableStateFlow(value?.takeIf { it.canonicalTitleId == canonicalTitleId })

        override suspend fun upsert(preference: ContentPreference) {
            value = preference
        }

        override suspend fun delete(canonicalTitleId: String) {
            if (value?.canonicalTitleId == canonicalTitleId) value = null
        }
    }
}
