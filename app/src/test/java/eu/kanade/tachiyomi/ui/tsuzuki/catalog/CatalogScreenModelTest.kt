package eu.kanade.tachiyomi.ui.tsuzuki.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.interactor.GetDiscoverFeed
import tachiyomi.domain.tsuzuki.catalog.interactor.SearchCatalog
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogScreenModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial loading transitions to successful Discover`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            trendingResult = Result.success(CatalogPage(listOf(CatalogItem("fake", "1", "Trending Title")), false)),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("fake", "2", "Popular Title")), false)),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )

        screenModel.state.value.shouldBeInstanceOf<CatalogScreenState.Loading>()
        screenModel.state.value.discoverState.shouldBeInstanceOf<DiscoverState.Loading>()

        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Success>()
        val feed = state.discoverFeed
        feed shouldNotBe null
        feed?.trending?.getOrThrow()?.items?.first()?.title shouldBe "Trending Title"
        feed?.popular?.getOrThrow()?.items?.first()?.title shouldBe "Popular Title"
        state.discoverState.shouldBeInstanceOf<DiscoverState.Success>()
    }

    @Test
    fun `successful Search transitions to Success with results`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(CatalogPage(listOf(CatalogItem("fake", "10", "Chainsaw Man")), false)),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        screenModel.search("Chainsaw")
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Success>()
        state.searchState.shouldBeInstanceOf<SearchState.Success>()
        state.searchResults.size shouldBe 1
        state.searchResults.first().title shouldBe "Chainsaw Man"
    }

    @Test
    fun `typing a query triggers debounced search without submit`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(
                CatalogPage(listOf(CatalogItem("fake", "10", "Chainsaw Man")), false),
            ),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        screenModel.updateSearchQuery("Chainsaw")
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Success>()
        state.searchResults.map { it.title } shouldBe listOf("Chainsaw Man")
        fakeProvider.searchQueries shouldBe listOf("Chainsaw")
    }

    @Test
    fun `typing a newer query cancels the pending debounced search`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(
                CatalogPage(listOf(CatalogItem("fake", "20", "One Piece")), false),
            ),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        screenModel.updateSearchQuery("Dragon")
        screenModel.updateSearchQuery("One Piece")
        advanceUntilIdle()

        fakeProvider.searchQueries shouldBe listOf("One Piece")
        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Success>()
        state.searchResults.map { it.title } shouldBe listOf("One Piece")
    }

    @Test
    fun `empty Search result transitions to Empty state`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.success(CatalogPage(emptyList(), false)),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        screenModel.search("NonexistentTitleXYZ")
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Empty>()
        state.searchState.shouldBeInstanceOf<SearchState.Empty>()
    }

    @Test
    fun `search provider error transitions to Error state`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            searchResult = Result.failure(CatalogError.RateLimitExceeded(retryAfterSeconds = 60)),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        screenModel.search("SpamQuery")
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Error>()
        val searchState = state.searchState
        searchState.shouldBeInstanceOf<SearchState.Error>()
        searchState.error.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
    }

    @Test
    fun `partial Discover degradation transitions to Degraded state`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            trendingResult = Result.failure(CatalogError.NetworkError(RuntimeException("Trending offline"))),
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("fake", "2", "Popular Survives")), false)),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Degraded>()
        state.discoverState.shouldBeInstanceOf<DiscoverState.Degraded>()
        state.discoverFeed.isDegraded shouldBe true
        state.discoverFeed.isCompleteFailure shouldBe false
        state.discoverFeed.trending.isFailure shouldBe true
        state.discoverFeed.popular.isSuccess shouldBe true
    }

    @Test
    fun `complete Discover failure transitions to Error state`() = runTest(testDispatcher) {
        val fakeProvider = FakeCatalogProvider(
            trendingResult = Result.failure(CatalogError.NetworkError(RuntimeException("Trending offline"))),
            popularResult = Result.failure(CatalogError.HttpError(503, "Service unavailable")),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CatalogScreenState.Error>()
        state.discoverState.shouldBeInstanceOf<DiscoverState.Error>()
    }

    @Test
    fun `item selection and preview remains ephemeral with no repository or materialization calls`() = runTest(
        testDispatcher,
    ) {
        val fakeProvider = FakeCatalogProvider(
            popularResult = Result.success(CatalogPage(listOf(CatalogItem("fake", "99", "Monster")), false)),
        )
        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(fakeProvider),
            getDiscoverFeed = GetDiscoverFeed(fakeProvider),
        )
        advanceUntilIdle()

        val previewItem = CatalogItem(
            provider = "fake",
            providerId = "99",
            title = "Monster",
            synopsis = "A psychological thriller",
        )

        screenModel.openPreview(previewItem)
        advanceUntilIdle()
        screenModel.state.value.selectedItem shouldBe previewItem

        screenModel.dismissPreview()
        advanceUntilIdle()
        screenModel.state.value.selectedItem shouldBe null
    }

    @Test
    fun `cancellation exception is propagated and preserves coroutine cancellation`() = runTest(testDispatcher) {
        val cancellingProvider = object : CatalogProvider {
            override val providerId: String = "fake"
            override val displayName: String = "Fake"

            override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
                throw CancellationException("Search coroutine cancelled")
            }

            override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> {
                throw CancellationException("Trending coroutine cancelled")
            }

            override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> {
                return Result.success(CatalogPage(emptyList(), false))
            }

            override suspend fun getDetails(providerId: String): Result<CatalogItem> {
                return Result.failure(UnsupportedOperationException())
            }
        }

        val screenModel = CatalogScreenModel(
            searchCatalog = SearchCatalog(cancellingProvider),
            getDiscoverFeed = GetDiscoverFeed(cancellingProvider),
        )

        shouldThrow<CancellationException> {
            screenModel.executeSearch("ThrowCancellation")
        }

        shouldThrow<CancellationException> {
            screenModel.refreshDiscover()
        }
    }

    private class FakeCatalogProvider(
        var searchResult: Result<CatalogPage> = Result.success(CatalogPage(emptyList(), false)),
        var trendingResult: Result<CatalogPage> = Result.success(CatalogPage(emptyList(), false)),
        var popularResult: Result<CatalogPage> = Result.success(CatalogPage(emptyList(), false)),
    ) : CatalogProvider {
        override val providerId: String = "fake"
        override val displayName: String = "Fake"
        val searchQueries = mutableListOf<String?>()

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> {
            searchQueries += query.query
            return searchResult
        }
        override suspend fun getTrending(offset: Int, limit: Int): Result<CatalogPage> = trendingResult
        override suspend fun getPopular(offset: Int, limit: Int): Result<CatalogPage> = popularResult
        override suspend fun getDetails(providerId: String): Result<CatalogItem> =
            Result.failure(UnsupportedOperationException())
    }
}
