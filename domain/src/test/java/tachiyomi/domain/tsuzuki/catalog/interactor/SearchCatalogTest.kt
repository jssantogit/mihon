package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

class SearchCatalogTest {

    @Test
    fun `search delegates to provider and returns page on success`() = runTest {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(
                CatalogPage(items = listOf(CatalogItem("kitsu", "1", "Monster")), hasNextPage = false),
            ),
        )
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor.await("Monster")
        result.isSuccess shouldBe true
        val page = result.getOrThrow()
        page.items.size shouldBe 1
        page.items.first().title shouldBe "Monster"
        fakeProvider.lastQuery?.query shouldBe "Monster"
        fakeProvider.lastQuery?.sort shouldBe CatalogSort.POPULARITY_DESC
        fakeProvider.lastQuery?.offset shouldBe 0
        fakeProvider.lastQuery?.limit shouldBe 20
    }

    @Test
    fun `search forwards query parameters sort and pagination correctly`() = runTest {
        val fakeProvider = FakeCatalogProvider()
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor(
            query = "Berserk",
            sort = CatalogSort.RATING_DESC,
            offset = 40,
            limit = 10,
            genres = listOf("Action"),
            status = CatalogItemStatus.COMPLETED,
        )
        result.isSuccess shouldBe true
        val query = fakeProvider.lastQuery!!
        query.query shouldBe "Berserk"
        query.sort shouldBe CatalogSort.RATING_DESC
        query.offset shouldBe 40
        query.limit shouldBe 10
        query.genres shouldBe listOf("Action")
        query.status shouldBe CatalogItemStatus.COMPLETED
    }

    @Test
    fun `search execute forwards parameters matching await`() = runTest {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(
                CatalogPage(items = listOf(CatalogItem("kitsu", "2", "Vinland Saga")), hasNextPage = false),
            ),
        )
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor.execute("Vinland Saga", offset = 10, limit = 15, sort = CatalogSort.UPDATED_DESC)
        result.isSuccess shouldBe true
        result.getOrThrow().items.first().title shouldBe "Vinland Saga"
        fakeProvider.lastQuery?.query shouldBe "Vinland Saga"
        fakeProvider.lastQuery?.offset shouldBe 10
        fakeProvider.lastQuery?.limit shouldBe 15
        fakeProvider.lastQuery?.sort shouldBe CatalogSort.UPDATED_DESC
    }

    @Test
    fun `search returns typed failure gracefully when provider fails with RateLimitExceeded`() = runTest {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 30)),
        )
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor.await("Monster")
        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        error.retryAfterSeconds shouldBe 30
    }

    @Test
    fun `search returns typed failure gracefully when provider fails with ProviderUnavailable`() = runTest {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.failure(CatalogError.ProviderUnavailable("Kitsu unavailable")),
        )
        val interactor = SearchCatalog(fakeProvider)

        val result = interactor.await("Monster")
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
    }

    @Test
    fun `search catches unexpected Throwable and returns failure`() = runTest {
        val crashingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(query: CatalogQuery): Result<CatalogPage> = throw RuntimeException("Boom")
            override suspend fun getTrending(
                offset: Int,
                limit: Int,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getPopular(
                offset: Int,
                limit: Int,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getDetails(
                providerId: String,
            ): Result<CatalogItem> = Result.failure(NotImplementedError())
        }
        val interactor = SearchCatalog(crashingProvider)

        val result = interactor.await("Monster")
        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "Boom"
    }

    @Test
    fun `search rethrows CancellationException to preserve coroutine cancellation`() = runTest {
        val cancellingProvider = object : CatalogProvider {
            override val providerId: String = "kitsu"
            override val displayName: String = "Kitsu"
            override suspend fun search(
                query: CatalogQuery,
            ): Result<CatalogPage> = throw CancellationException("Scope cancelled")
            override suspend fun getTrending(
                offset: Int,
                limit: Int,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getPopular(
                offset: Int,
                limit: Int,
            ): Result<CatalogPage> = Result.failure(NotImplementedError())
            override suspend fun getDetails(
                providerId: String,
            ): Result<CatalogItem> = Result.failure(NotImplementedError())
        }
        val interactor = SearchCatalog(cancellingProvider)

        shouldThrow<CancellationException> {
            interactor.await("Monster")
        }
    }

    private class FakeCatalogProvider(
        var searchResult: Result<CatalogPage> = Result.success(CatalogPage(emptyList(), false)),
    ) : CatalogProvider {
        override val providerId: String = "kitsu"
        override val displayName: String = "Kitsu"
        var lastQuery: CatalogQuery? = null

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
            lastQuery = query
            return searchResult
        }

        override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> = searchResult
        override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> = searchResult
        override suspend fun getDetails(providerId: String): Result<CatalogItem> = Result.failure(NotImplementedError())
    }
}
