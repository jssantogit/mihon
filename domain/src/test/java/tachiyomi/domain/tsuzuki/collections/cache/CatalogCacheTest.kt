package tachiyomi.domain.tsuzuki.collections.cache

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class CatalogCacheTest {

    private val page = CatalogPage(
        items = listOf(
            CatalogItem(
                provider = "fake",
                providerId = "1",
                title = "One",
            ),
        ),
        hasNextPage = true,
        totalCount = 10,
    )

    @Test
    fun `equivalent normalized expressions produce identical cache keys`() {
        val status = QueryExpression.Predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("ongoing"),
        )
        val genre = QueryExpression.Predicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        )

        val first = CatalogCacheKey.fromExpression(
            providerId = "fake",
            expression = QueryExpression.All(status, genre),
            sort = CatalogSort.POPULARITY_DESC,
            rawOffset = 0,
            pageSize = 20,
        )
        val second = CatalogCacheKey.fromExpression(
            providerId = "fake",
            expression = QueryExpression.All(genre, status),
            sort = CatalogSort.POPULARITY_DESC,
            rawOffset = 0,
            pageSize = 20,
        )

        first shouldBe second
    }

    @Test
    fun `cache key keeps sort offset and page size distinct`() {
        val base = CatalogCacheKey.fromExpression(
            providerId = "fake",
            expression = null,
            sort = CatalogSort.POPULARITY_DESC,
            rawOffset = 0,
            pageSize = 20,
        )

        (base == base.copy(sort = CatalogSort.RATING_DESC)) shouldBe false
        (base == base.copy(rawOffset = 20)) shouldBe false
        (base == base.copy(pageSize = 10)) shouldBe false
    }

    @Test
    fun `memory cache exposes fresh stale and miss windows`() = runTest {
        val cache = MemoryCatalogCache()
        val key = key()

        cache.put(
            key = key,
            page = page,
            fetchedAt = 1_000,
            ttlMillis = 100,
            staleWhileRevalidateMillis = 50,
        )

        (cache.get(key, 1_100) as CatalogCacheLookup.Hit).freshness shouldBe CacheFreshness.FRESH
        (cache.get(key, 1_101) as CatalogCacheLookup.Hit).freshness shouldBe CacheFreshness.STALE
        (cache.get(key, 1_150) as CatalogCacheLookup.Hit).freshness shouldBe CacheFreshness.STALE
        cache.get(key, 1_151) shouldBe CatalogCacheLookup.Miss
        cache.size() shouldBe 0
    }

    @Test
    fun `memory cache evicts oldest entry when bounded capacity is reached`() = runTest {
        val cache = MemoryCatalogCache(maxEntries = 2)
        val first = key(offset = 0)
        val second = key(offset = 20)
        val third = key(offset = 40)

        cache.put(first, page, fetchedAt = 1, ttlMillis = 100, staleWhileRevalidateMillis = 0)
        cache.put(second, page, fetchedAt = 2, ttlMillis = 100, staleWhileRevalidateMillis = 0)
        cache.put(third, page, fetchedAt = 3, ttlMillis = 100, staleWhileRevalidateMillis = 0)

        cache.get(first, 3) shouldBe CatalogCacheLookup.Miss
        (cache.get(second, 3) is CatalogCacheLookup.Hit) shouldBe true
        (cache.get(third, 3) is CatalogCacheLookup.Hit) shouldBe true
        cache.size() shouldBe 2
    }

    @Test
    fun `identical simultaneous requests share one producer execution`() = runTest {
        val deduplicator = InFlightQueryDeduplicator(backgroundScope)
        val key = key()
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        val first = async {
            deduplicator.execute(key) {
                calls++
                gate.await()
                Result.success(page)
            }
        }
        val second = async {
            deduplicator.execute(key) {
                calls++
                gate.await()
                Result.success(page)
            }
        }

        runCurrent()
        calls shouldBe 1
        deduplicator.activeCount() shouldBe 1

        gate.complete(Unit)
        runCurrent()

        first.await().getOrThrow() shouldBe page
        second.await().getOrThrow() shouldBe page
        calls shouldBe 1
        deduplicator.activeCount() shouldBe 0
    }

    @Test
    fun `completed failure is evicted so a later request can retry`() = runTest {
        val deduplicator = InFlightQueryDeduplicator(backgroundScope)
        val key = key()
        var calls = 0

        val first = deduplicator.execute(key) {
            calls++
            Result.failure(IllegalStateException("boom"))
        }
        first.isFailure shouldBe true

        val second = deduplicator.execute(key) {
            calls++
            Result.success(page)
        }

        second.getOrThrow() shouldBe page
        calls shouldBe 2
    }

    @Test
    fun `cache deadline saturates instead of overflowing`() {
        safeCacheDeadline(Long.MAX_VALUE - 5, 10) shouldBe Long.MAX_VALUE
    }

    private fun key(offset: Int = 0): CatalogCacheKey = CatalogCacheKey(
        providerId = "fake",
        normalizedQueryKey = CatalogCacheKey.NO_QUERY_KEY,
        sort = CatalogSort.POPULARITY_DESC,
        rawOffset = offset,
        pageSize = 20,
    )
}
