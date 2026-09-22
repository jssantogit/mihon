package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveChapterContentTest {

    @Test
    fun `first read requires selector even when one provider exists`() = runTest {
        val resolver = fixture(
            preference = null,
            automaticFallback = false,
            providers = listOf(provider("mangadex", option("mangadex", "en"))),
        )

        val result = resolver.execute("title", "chapter-37")

        result.shouldBeInstanceOf<ContentResolution.NeedsSelection>()
        result.options.size shouldBe 1
        result.preferredAddonId shouldBe null
        result.preferredUnavailable shouldBe false
    }

    @Test
    fun `title language overrides global ranking without hiding alternatives`() = runTest {
        val resolver = fixture(
            preference = ContentPreference(
                canonicalTitleId = "title",
                preferredAddonId = AddonId("mangafire"),
                updatedAt = 2L,
                preferredLanguage = "en",
            ),
            automaticFallback = false,
            preferredLanguages = listOf("pt-BR"),
            providers = listOf(
                provider(
                    "mangafire",
                    option("mangafire", "pt-BR"),
                    option("mangafire", "en"),
                ),
            ),
        )

        val available = resolver.resolveOptions("title", "chapter-37")
        available.map { it.language } shouldBe listOf("en", "pt-BR")
        resolver.execute("title", "chapter-37")
            .shouldBeInstanceOf<ContentResolution.Direct>()
            .option.language shouldBe "en"
    }

    @Test
    fun `preferred addon opens directly when chapter is available`() = runTest {
        val resolver = fixture(
            preference = ContentPreference("title", AddonId("mangadex"), 1L),
            automaticFallback = false,
            providers = listOf(
                provider("mangadex", option("mangadex", "en")),
                provider("mangafire", option("mangafire", "pt-BR")),
            ),
        )

        val result = resolver.execute("title", "chapter-37")

        result.shouldBeInstanceOf<ContentResolution.Direct>()
        result.option.addonId shouldBe AddonId("mangadex")
        result.usedFallback shouldBe false
    }

    @Test
    fun `missing preferred addon opens selector when automatic fallback is off`() = runTest {
        val resolver = fixture(
            preference = ContentPreference("title", AddonId("mangadex"), 1L),
            automaticFallback = false,
            providers = listOf(provider("mangafire", option("mangafire", "pt-BR"))),
        )

        val result = resolver.execute("title", "chapter-37")

        result.shouldBeInstanceOf<ContentResolution.NeedsSelection>()
        result.options.map { it.addonId } shouldBe listOf(AddonId("mangafire"))
        result.preferredAddonId shouldBe AddonId("mangadex")
        result.preferredUnavailable shouldBe true
    }

    @Test
    fun `missing preferred addon selects ranked fallback only when globally enabled`() = runTest {
        val resolver = fixture(
            preference = ContentPreference("title", AddonId("mangadex"), 1L),
            automaticFallback = true,
            preferredLanguages = listOf("pt-BR"),
            providers = listOf(
                provider("mangafire", option("mangafire", "en")),
                provider("mangaball", option("mangaball", "pt-BR")),
            ),
        )

        val result = resolver.execute("title", "chapter-37")

        result.shouldBeInstanceOf<ContentResolution.Direct>()
        result.option.addonId shouldBe AddonId("mangaball")
        result.usedFallback shouldBe true
    }

    @Test
    fun `initial provider fanout is bounded when many Add-ons are enabled`() = runTest {
        val release = CompletableDeferred<Unit>()
        var concurrent = 0
        var peak = 0
        val providers = (1..12).map { index ->
            object : ContentProvider {
                override val addonId = AddonId("addon-$index")

                override suspend fun resolve(
                    canonicalTitleId: String,
                    canonicalChapterId: String,
                ): Result<List<ContentOption>> {
                    concurrent++
                    peak = maxOf(peak, concurrent)
                    try {
                        release.await()
                        return Result.success(listOf(option(addonId.value, "en")))
                    } finally {
                        concurrent--
                    }
                }
            }
        }
        val resolver = fixture(
            preference = null,
            automaticFallback = false,
            providers = providers,
        )

        val pending = async { resolver.execute("title", "chapter-37") }
        runCurrent()
        peak shouldBe 4
        release.complete(Unit)
        advanceUntilIdle()

        pending.await().shouldBeInstanceOf<ContentResolution.NeedsSelection>()
            .options.size shouldBe 12
        concurrent shouldBe 0
    }

    @Test
    fun `switching provider preserves canonical chapter identity used by progress`() = runTest {
        val preferenceRepository = FakeContentPreferenceRepository(
            ContentPreference("title", AddonId("mangadex"), 1L),
        )
        val preferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        val resolver = ResolveChapterContent(
            addonRegistry = FakeAddonRegistry(
                listOf(
                    provider("mangadex", option("mangadex", "en")),
                    provider("mangafire", option("mangafire", "pt-BR")),
                ),
            ),
            contentPreferenceRepository = preferenceRepository,
            readerPreferences = preferences,
            rankContentOptions = RankContentOptions(),
            contentOptionCache = ContentOptionCache(),
            inFlightContentResolution = InFlightContentResolution(),
        )

        val first = resolver.execute("title", "chapter-37")
            .shouldBeInstanceOf<ContentResolution.Direct>()
        preferenceRepository.upsert(
            ContentPreference("title", AddonId("mangafire"), 2L),
        )
        val second = resolver.execute("title", "chapter-37")
            .shouldBeInstanceOf<ContentResolution.Direct>()

        first.option.addonId shouldBe AddonId("mangadex")
        second.option.addonId shouldBe AddonId("mangafire")
        first.option.canonicalChapterId shouldBe "chapter-37"
        second.option.canonicalChapterId shouldBe first.option.canonicalChapterId
    }

    private fun fixture(
        preference: ContentPreference?,
        automaticFallback: Boolean,
        providers: List<ContentProvider>,
        preferredLanguages: List<String> = emptyList(),
    ): ResolveChapterContent {
        val preferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        preferences.automaticFallback.set(automaticFallback)
        preferences.preferredLanguages.set(preferredLanguages)
        return ResolveChapterContent(
            addonRegistry = FakeAddonRegistry(providers),
            contentPreferenceRepository = FakeContentPreferenceRepository(preference),
            readerPreferences = preferences,
            rankContentOptions = RankContentOptions(),
            contentOptionCache = ContentOptionCache(),
            inFlightContentResolution = InFlightContentResolution(),
        )
    }

    private fun provider(id: String, vararg options: ContentOption): ContentProvider {
        return object : ContentProvider {
            override val addonId = AddonId(id)
            override suspend fun resolve(
                canonicalTitleId: String,
                canonicalChapterId: String,
            ): Result<List<ContentOption>> = Result.success(options.toList())
        }
    }

    private fun option(addonId: String, language: String) = ContentOption(
        key = addonId + ":" + language,
        canonicalChapterId = "chapter-37",
        addonId = AddonId(addonId),
        language = language,
        scanlationGroup = null,
        releaseDate = null,
        delivery = ContentDelivery.LocalArchive("content://$addonId/$language"),
    )

    private class FakeAddonRegistry(
        private val providers: List<ContentProvider>,
    ) : AddonRegistry {
        override fun contentProviders(): List<ContentProvider> = providers
        override fun chapterProbeProviders(): List<ChapterProbeProvider> = emptyList()
    }

    private class FakeContentPreferenceRepository(
        private var preference: ContentPreference?,
    ) : ContentPreferenceRepository {
        override suspend fun get(canonicalTitleId: String): ContentPreference? =
            preference?.takeIf { it.canonicalTitleId == canonicalTitleId }

        override fun observe(canonicalTitleId: String): Flow<ContentPreference?> =
            MutableStateFlow(preference?.takeIf { it.canonicalTitleId == canonicalTitleId })

        override suspend fun upsert(preference: ContentPreference) {
            this.preference = preference
        }

        override suspend fun delete(canonicalTitleId: String) {
            if (preference?.canonicalTitleId == canonicalTitleId) preference = null
        }
    }
}
