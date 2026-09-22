package eu.kanade.tachiyomi.ui.tsuzuki.content

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
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

    private fun model(
        providers: List<ContentProvider>,
        addons: List<InstalledAddon>,
        preferenceRepository: FakeContentPreferenceRepository = FakeContentPreferenceRepository(null),
        addonRepository: AddonRepository = FakeAddonRepository(addons),
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
