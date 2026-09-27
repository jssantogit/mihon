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
    fun `cached inventories preserve original provider timestamps until an actual refetch`() = runTest {
        var now = 0L
        var providerStartedAt = 100L
        val cache = MihonInventorySnapshotCache({ now }, 100L, 4)
        var fetchCount = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            fetchCount++
            Result.success(inventory.copy(fetchStartedAtMillis = providerStartedAt))
        }

        cache.getOrFetch(key, fetch = fetch).getOrThrow().fetchStartedAtMillis shouldBe 100L
        now = 50L
        providerStartedAt = 200L
        cache.getOrFetch(key, fetch = fetch).getOrThrow().fetchStartedAtMillis shouldBe 100L
        fetchCount shouldBe 1

        cache.getOrFetch(key, refresh = true, fetch = fetch)
            .getOrThrow().fetchStartedAtMillis shouldBe 200L
        fetchCount shouldBe 2

        now = 151L
        providerStartedAt = 300L
        cache.getOrFetch(key, fetch = fetch).getOrThrow().fetchStartedAtMillis shouldBe 300L
        fetchCount shouldBe 3
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
            if (attempts == 1) {
                Result.failure(IllegalStateException("offline"))
            } else {
                Result.success(inventory)
            }
        }
        cache.getOrFetch(key, fetch = fetch).isFailure shouldBe true
        cache.getOrFetch(key, fetch = fetch).getOrThrow() shouldBe inventory
        attempts shouldBe 2
    }

    @Test
    fun `invalidated in-flight inventory fails its original caller and cannot overwrite a fresh snapshot`() = runTest {
        val cache = MihonInventorySnapshotCache({ 0L }, 100L, 4)
        val releaseOldFetch = CompletableDeferred<Unit>()
        var requests = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            requests++
            val startedAt = if (requests == 1) 100L else 200L
            if (requests == 1) releaseOldFetch.await()
            Result.success(inventory.copy(fetchStartedAtMillis = startedAt))
        }

        val obsolete = async { cache.getOrFetch(key, fetch = fetch) }
        yield()
        requests shouldBe 1

        // A binding change invalidates this title while the original network
        // request is still in progress. The original caller must fail closed.
        cache.invalidateTitle("title")
        cache.getOrFetch(key, fetch = fetch).getOrThrow().fetchStartedAtMillis shouldBe 200L
        requests shouldBe 2

        releaseOldFetch.complete(Unit)
        obsolete.await().isFailure shouldBe true
        cache.getOrFetch(key, fetch = fetch).getOrThrow().fetchStartedAtMillis shouldBe 200L
        requests shouldBe 2
    }

    @Test
    fun `invalidating a shared in-flight lookup fails all earlier waiters but not the fresh request`() = runTest {
        val cache = MihonInventorySnapshotCache({ 0L }, 100L, 4)
        val releaseOldFetch = CompletableDeferred<Unit>()
        var requests = 0
        val fetch: suspend () -> Result<SourceChapterInventory> = {
            requests++
            if (requests == 1) releaseOldFetch.await()
            Result.success(inventory.copy(fetchStartedAtMillis = requests * 100L))
        }

        val original = async { cache.getOrFetch(key, fetch = fetch) }
        yield()
        val joined = async { cache.getOrFetch(key, fetch = fetch) }
        yield()
        requests shouldBe 1

        cache.invalidateTitle("title")
        val replacement = cache.getOrFetch(key, fetch = fetch).getOrThrow()
        replacement.fetchStartedAtMillis shouldBe 200L
        releaseOldFetch.complete(Unit)
        original.await().isFailure shouldBe true
        joined.await().isFailure shouldBe true
        cache.getOrFetch(key, fetch = fetch).getOrThrow() shouldBe replacement
        requests shouldBe 2
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
