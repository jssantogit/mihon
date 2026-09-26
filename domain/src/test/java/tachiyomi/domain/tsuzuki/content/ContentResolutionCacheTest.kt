package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicInteger

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
    fun `repeated blocked requests from one addon leave a slot for a healthy addon`() = runTest {
        val inFlight = InFlightContentResolution()
        val releaseSlow = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val slowCalls = AtomicInteger()
        val activeSlowCalls = AtomicInteger()
        val peakSlowCalls = AtomicInteger()
        val slowRequests = (1..4).map { index ->
            async {
                inFlight.execute(
                    ContentOptionCacheKey("title", "chapter-$index", AddonId("slow")),
                ) {
                    slowCalls.incrementAndGet()
                    val active = activeSlowCalls.incrementAndGet()
                    peakSlowCalls.updateAndGet { maxOf(it, active) }
                    slowStarted.complete(Unit)
                    try {
                        withContext(NonCancellable) { releaseSlow.await() }
                        Result.success(emptyList())
                    } finally {
                        activeSlowCalls.decrementAndGet()
                    }
                }
            }
        }

        slowStarted.await()
        runCurrent()
        slowCalls.get() shouldBe 1

        val overflow = inFlight.execute(
            ContentOptionCacheKey("title", "chapter-overflow", AddonId("slow")),
        ) { Result.success(emptyList()) }
        overflow.isFailure shouldBe true

        val healthy = inFlight.execute(
            ContentOptionCacheKey("title", "chapter-healthy", AddonId("healthy")),
        ) {
            Result.success(listOf(contentOption("healthy", "chapter-healthy")))
        }
        healthy.getOrThrow().single().canonicalChapterId shouldBe "chapter-healthy"
        peakSlowCalls.get() shouldBe 1

        releaseSlow.complete(Unit)
        slowRequests.awaitAll()
        slowCalls.get() shouldBe 4
    }

    @Test
    fun `invalidated provider completion is not shared with a refreshed chapter request`() = runTest {
        val inFlight = InFlightContentResolution()
        val key = ContentOptionCacheKey("title", "chapter-1", AddonId("mangadex"))
        val releaseStale = CompletableDeferred<Unit>()
        val staleStarted = CompletableDeferred<Unit>()
        val stale = async {
            inFlight.execute(key) {
                staleStarted.complete(Unit)
                withContext(NonCancellable) { releaseStale.await() }
                Result.success(listOf(contentOption("mangadex", "chapter-1")))
            }
        }

        staleStarted.await()
        inFlight.invalidateChapter("title", "chapter-1")
        stale.await().isFailure shouldBe true

        releaseStale.complete(Unit)
        val refreshed = inFlight.execute(key) {
            Result.success(listOf(contentOption("mangadex", "chapter-1")))
        }

        refreshed.getOrThrow().single().canonicalChapterId shouldBe "chapter-1"
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
    fun `late result cannot repopulate cache after key invalidation`() = runTest {
        val cache = ContentOptionCache()
        val key = ContentOptionCacheKey("title", "chapter-1", AddonId("mangadex"))
        val token = cache.beginLookup(key)

        cache.invalidate(key)

        cache.putIfCurrent(token, listOf(contentOption("mangadex", "chapter-1"))) shouldBe false
        cache.get(key) shouldBe null
        cache.finishLookup(token)
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

    private fun contentOption(addonId: String, chapterId: String) = ContentOption(
        key = "$addonId:$chapterId",
        canonicalChapterId = chapterId,
        addonId = AddonId(addonId),
        language = "en",
        scanlationGroup = null,
        releaseDate = null,
        delivery = ContentDelivery.LocalArchive("content://$addonId/$chapterId"),
    )
}
