package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCacheKey
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

class ContentResolutionCacheTest {

    @Test
    fun `two concurrent resolutions for same chapter share one provider request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = CountingProvider(gate)
        val resolver = resolver(provider)

        val first = async { resolver.execute("title", "chapter") }
        yield()
        val second = async { resolver.execute("title", "chapter") }
        yield()
        gate.complete(Unit)

        first.await()
        second.await()

        provider.resolveCalls shouldBe 1
    }

    @Test
    fun `expired option cache re-queries provider`() = runTest {
        var now = 0L
        val provider = CountingProvider()
        val resolver = resolver(
            provider = provider,
            cache = ContentOptionCache(
                clock = { now },
                successTtlMillis = 100L,
                emptyTtlMillis = 20L,
                maxEntries = 16,
            ),
        )

        resolver.execute("title", "chapter")
        now = 101L
        resolver.execute("title", "chapter")

        provider.resolveCalls shouldBe 2
    }

    @Test
    fun `invalidating addon clears its cached chapter options`() = runTest {
        val cache = ContentOptionCache()
        val key = ContentOptionCacheKey(
            canonicalTitleId = "title",
            canonicalChapterId = "chapter",
            addonId = AddonId("mangadex"),
        )
        val option = ContentOption(
            key = "cached",
            canonicalChapterId = "chapter",
            addonId = AddonId("mangadex"),
            language = "en",
            scanlationGroup = null,
            releaseDate = null,
            delivery = ContentDelivery.LocalArchive("content://cached"),
        )
        cache.put(key, listOf(option))

        cache.get(key) shouldBe listOf(option)
        cache.invalidateAddon(AddonId("mangadex"))

        cache.get(key) shouldBe null
    }

    @Test
    fun `refresh invalidates only options belonging to the affected title`() = runTest {
        val cache = ContentOptionCache()
        val affected = ContentOptionCacheKey("title-a", "chapter-1", AddonId("mangadex"))
        val otherChapter = affected.copy(canonicalChapterId = "chapter-2")
        val unaffected = affected.copy(canonicalTitleId = "title-b")
        val option = ContentOption(
            key = "cached",
            canonicalChapterId = "chapter-1",
            addonId = AddonId("mangadex"),
            language = "en",
            scanlationGroup = null,
            releaseDate = null,
            delivery = ContentDelivery.LocalArchive("content://cached"),
        )
        cache.put(affected, listOf(option))
        cache.put(otherChapter, listOf(option))
        cache.put(unaffected, listOf(option))

        cache.invalidateTitle("title-a")

        cache.get(affected) shouldBe null
        cache.get(otherChapter) shouldBe null
        cache.get(unaffected) shouldBe listOf(option)
    }

    private fun resolver(
        provider: ContentProvider,
        cache: ContentOptionCache = ContentOptionCache(),
    ): ResolveChapterContent {
        val readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        return ResolveChapterContent(
            addonRegistry = object : AddonRegistry {
                override fun contentProviders() = listOf(provider)
                override fun chapterProbeProviders(): List<ChapterProbeProvider> = emptyList()
            },
            contentPreferenceRepository = object : ContentPreferenceRepository {
                override suspend fun get(canonicalTitleId: String): ContentPreference? = null
                override fun observe(canonicalTitleId: String) =
                    kotlinx.coroutines.flow.flowOf<ContentPreference?>(null)
                override suspend fun upsert(preference: ContentPreference) = Unit
                override suspend fun delete(canonicalTitleId: String) = Unit
            },
            readerPreferences = readerPreferences,
            rankContentOptions = RankContentOptions(),
            contentOptionCache = cache,
            inFlightContentResolution = InFlightContentResolution(),
        )
    }

    private class CountingProvider(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ContentProvider {
        override val addonId = AddonId("mangadex")
        var resolveCalls = 0

        override suspend fun resolve(
            canonicalTitleId: String,
            canonicalChapterId: String,
        ): Result<List<ContentOption>> {
            resolveCalls++
            gate?.await()
            return Result.success(
                listOf(
                    ContentOption(
                        key = "mangadex",
                        canonicalChapterId = canonicalChapterId,
                        addonId = addonId,
                        language = "en",
                        scanlationGroup = null,
                        releaseDate = null,
                        delivery = ContentDelivery.LocalArchive("content://chapter"),
                    ),
                ),
            )
        }
    }
}
