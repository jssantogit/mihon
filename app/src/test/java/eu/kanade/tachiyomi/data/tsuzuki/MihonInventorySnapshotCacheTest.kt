package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory

class MihonInventorySnapshotCacheTest {
    private val key = MihonInventoryKey("title", "binding", 7L, 42L, "/title", "en")
    private val inventory = SourceChapterInventory(
        sourceMappingId = "binding",
        sourceId = 7L,
        canonicalTitleId = "title",
        chapters = emptyList(),
        mihonMangaId = 42L,
        language = "en",
    )

    @Test
    fun `probe and content resolution reuse the same warm inventory`() = runTest {
        val cache = MihonInventorySnapshotCache({ 0L }, 100L, 4)
        var requests = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            requests++
            Result.success(inventory)
        }

        cache.getOrFetch(key, fetch = fetch).getOrThrow() shouldBe inventory
        cache.getOrFetch(key, fetch = fetch).getOrThrow() shouldBe inventory
        requests shouldBe 1
    }

    @Test
    fun `simultaneous requests for one binding share one network lookup`() = runTest {
        val cache = MihonInventorySnapshotCache({ 0L }, 100L, 4)
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            requests++
            gate.await()
            Result.success(inventory)
        }

        val probe = async { cache.getOrFetch(key, fetch = fetch) }
        yield()
        val resolver = async { cache.getOrFetch(key, fetch = fetch) }
        yield()
        gate.complete(Unit)
        probe.await().getOrThrow() shouldBe inventory
        resolver.await().getOrThrow() shouldBe inventory
        requests shouldBe 1
    }

    @Test
    fun `explicit refresh and TTL expiry refetch without affecting other bindings`() = runTest {
        var now = 0L
        val cache = MihonInventorySnapshotCache({ now }, 100L, 4)
        var requests = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            requests++
            Result.success(inventory)
        }
        cache.getOrFetch(key, fetch = fetch)
        cache.getOrFetch(key.copy(mappingId = "other"), fetch = fetch)
        cache.getOrFetch(key, refresh = true, fetch = fetch)
        requests shouldBe 3

        now = 101L
        cache.getOrFetch(key, fetch = fetch)
        requests shouldBe 4
    }

    @Test
    fun `failed fetches are retryable and never cached`() = runTest {
        val cache = MihonInventorySnapshotCache({ 0L }, 100L, 4)
        var attempts = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            attempts++
            if (attempts == 1) Result.failure(IllegalStateException("offline"))
            else Result.success(inventory)
        }
        cache.getOrFetch(key, fetch = fetch).isFailure shouldBe true
        cache.getOrFetch(key, fetch = fetch).getOrThrow() shouldBe inventory
        attempts shouldBe 2
    }

    @Test
    fun `invalidating one title preserves another title's warm inventory`() = runTest {
        val cache = MihonInventorySnapshotCache({ 0L }, 100L, 4)
        var requests = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            requests++
            Result.success(inventory)
        }
        val other = key.copy(canonicalTitleId = "other")
        cache.getOrFetch(key, fetch = fetch)
        cache.getOrFetch(other, fetch = fetch)
        cache.invalidateTitle("title")
        cache.getOrFetch(other, fetch = fetch)
        cache.getOrFetch(key, fetch = fetch)
        requests shouldBe 3
    }
}
