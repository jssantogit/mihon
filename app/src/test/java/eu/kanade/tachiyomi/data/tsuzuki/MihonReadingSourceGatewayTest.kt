package eu.kanade.tachiyomi.data.tsuzuki

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor

class MihonReadingSourceGatewayTest {

    private lateinit var preferenceStore: InMemoryPreferenceStore
    private lateinit var sourcePreferences: SourcePreferences
    private lateinit var fakeSourceManager: FakeSourceManager
    private lateinit var fakeMangaRepository: FakeMangaRepository
    private lateinit var networkToLocalManga: NetworkToLocalManga
    private lateinit var gateway: MihonReadingSourceGateway

    @BeforeEach
    fun setUp() {
        preferenceStore = InMemoryPreferenceStore()
        sourcePreferences = SourcePreferences(preferenceStore)
        fakeSourceManager = FakeSourceManager()
        fakeMangaRepository = FakeMangaRepository()
        networkToLocalManga = NetworkToLocalManga(fakeMangaRepository)
        gateway = MihonReadingSourceGateway(
            sourceManager = fakeSourceManager,
            sourcePreferences = sourcePreferences,
            networkToLocalManga = networkToLocalManga,
        )
    }

    @Test
    fun `getAvailableSources returns only installed enabled CatalogueSource for requested language`() = runTest {
        val enSource1 = TestCatalogueSource(id = 1L, name = "Source 1", lang = "en")
        val enSource2 = TestCatalogueSource(id = 2L, name = "Source 2", lang = "en")
        val jaSource = TestCatalogueSource(id = 3L, name = "Source 3", lang = "ja")
        fakeSourceManager.sourcesList.addAll(listOf(enSource1, enSource2, jaSource))

        val result = gateway.getAvailableSources("en")

        result shouldContainExactly listOf(
            ReadingSourceDescriptor(
                sourceId = 1L,
                name = "Source 1",
                language = "en",
                isInstalled = true,
                isEnabled = true,
            ),
            ReadingSourceDescriptor(
                sourceId = 2L,
                name = "Source 2",
                language = "en",
                isInstalled = true,
                isEnabled = true,
            ),
        )
    }

    @Test
    fun `getAvailableSources excludes disabled sources`() = runTest {
        val enSource1 = TestCatalogueSource(id = 1L, name = "Source 1", lang = "en")
        val enSource2 = TestCatalogueSource(id = 2L, name = "Source 2", lang = "en")
        fakeSourceManager.sourcesList.addAll(listOf(enSource1, enSource2))

        sourcePreferences.disabledSources.set(setOf("1"))

        val result = gateway.getAvailableSources("en")

        result shouldContainExactly listOf(
            ReadingSourceDescriptor(
                sourceId = 2L,
                name = "Source 2",
                language = "en",
                isInstalled = true,
                isEnabled = true,
            ),
        )
    }

    @Test
    fun `getAvailableSources excludes StubSource`() = runTest {
        val enSource = TestCatalogueSource(id = 1L, name = "Source 1", lang = "en")
        val stubSource = StubSource(id = 2L, lang = "en", name = "Stub")
        fakeSourceManager.sourcesList.addAll(listOf(enSource, stubSource))

        val result = gateway.getAvailableSources("en")

        result shouldContainExactly listOf(
            ReadingSourceDescriptor(
                sourceId = 1L,
                name = "Source 1",
                language = "en",
                isInstalled = true,
                isEnabled = true,
            ),
        )
    }

    @Test
    fun `searchSource queries page 1 with default filters and collapses duplicate URLs`() = runTest {
        val manga1 = SManga.create().apply {
            url = "/manga/1"
            title = "Title 1"
            thumbnail_url = "https://thumb/1.jpg"
        }
        val manga1Duplicate = SManga.create().apply {
            url = "/manga/1"
            title = "Title 1 Duplicate"
            thumbnail_url = "https://thumb/1-dup.jpg"
        }
        val manga2 = SManga.create().apply {
            url = "/manga/2"
            title = "Title 2"
            thumbnail_url = "https://thumb/2.jpg"
        }
        val source = TestCatalogueSource(
            id = 10L,
            name = "Test Source",
            lang = "en",
            searchResults = listOf(manga1, manga1Duplicate, manga2),
        )
        fakeSourceManager.sourcesList.add(source)

        val result = gateway.searchSource(10L, "test query")

        result.isSuccess shouldBe true
        val candidates = result.getOrThrow()
        candidates shouldContainExactly listOf(
            ReadingSourceCandidate(
                sourceId = 10L,
                sourceUrl = "/manga/1",
                title = "Title 1",
                thumbnailUrl = "https://thumb/1.jpg",
            ),
            ReadingSourceCandidate(
                sourceId = 10L,
                sourceUrl = "/manga/2",
                title = "Title 2",
                thumbnailUrl = "https://thumb/2.jpg",
            ),
        )
        source.lastPageSearched shouldBe 1
        source.lastQuerySearched shouldBe "test query"
        source.lastFiltersSearched shouldBe source.getFilterList()
        fakeMangaRepository.insertedCount shouldBe 0
    }

    @Test
    fun `searchSource does not call NetworkToLocalManga`() = runTest {
        val manga = SManga.create().apply {
            url = "/manga/1"
            title = "Title 1"
        }
        val source = TestCatalogueSource(id = 10L, name = "Test", lang = "en", searchResults = listOf(manga))
        fakeSourceManager.sourcesList.add(source)

        gateway.searchSource(10L, "query")

        fakeMangaRepository.insertedCount shouldBe 0
    }

    @Test
    fun `searchSource converts source exceptions to failed Result`() = runTest {
        val source = TestCatalogueSource(
            id = 10L,
            name = "Failing Source",
            lang = "en",
            errorToThrow = RuntimeException("Network timeout"),
        )
        fakeSourceManager.sourcesList.add(source)

        val result = gateway.searchSource(10L, "query")

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "Network timeout"
    }

    @Test
    fun `searchSource rethrows CancellationException`() = runTest {
        val source = TestCatalogueSource(
            id = 10L,
            name = "Cancelled Source",
            lang = "en",
            errorToThrow = CancellationException("Job was cancelled"),
        )
        fakeSourceManager.sourcesList.add(source)

        shouldThrow<CancellationException> {
            gateway.searchSource(10L, "query")
        }
    }

    @Test
    fun `materializeSource calls NetworkToLocalManga and does not favorite manga`() = runTest {
        val result = gateway.materializeSource(
            sourceId = 100L,
            sourceUrl = "/manga/berserk",
            title = "Berserk",
        )

        result.isSuccess shouldBe true
        val materialized = result.getOrThrow()
        materialized.sourceId shouldBe 100L
        materialized.sourceUrl shouldBe "/manga/berserk"
        materialized.title shouldBe "Berserk"
        materialized.mihonMangaId shouldBe 1L

        fakeMangaRepository.insertedCount shouldBe 1
        fakeMangaRepository.lastInsertedManga?.favorite shouldBe false
        fakeMangaRepository.lastInsertedManga?.source shouldBe 100L
        fakeMangaRepository.lastInsertedManga?.url shouldBe "/manga/berserk"
    }

    @Test
    fun `materializeSource rethrows CancellationException`() = runTest {
        fakeMangaRepository.errorToThrow = CancellationException("Cancelled in repo")

        shouldThrow<CancellationException> {
            gateway.materializeSource(100L, "/url", "Title")
        }
    }

    private class TestCatalogueSource(
        override val id: Long,
        override val name: String,
        override val lang: String,
        private val searchResults: List<SManga> = emptyList(),
        private val errorToThrow: Throwable? = null,
    ) : CatalogueSource {
        var lastPageSearched: Int? = null
        var lastQuerySearched: String? = null
        var lastFiltersSearched: FilterList? = null
        private val filters = FilterList()

        override val supportsLatest: Boolean = false

        override fun getFilterList(): FilterList = filters

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (errorToThrow != null) throw errorToThrow
            lastPageSearched = page
            lastQuerySearched = query
            lastFiltersSearched = filters
            return MangasPage(searchResults, false)
        }

        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    }

    private class FakeSourceManager : SourceManager {
        val sourcesList = mutableListOf<Source>()

        override val sources: Flow<List<Source>> = emptyFlow()
        override suspend fun get(sourceKey: Long): Source? = sourcesList.firstOrNull { it.id == sourceKey }
        override suspend fun getOrStub(sourceKey: Long): Source = get(sourceKey) ?: StubSource(sourceKey, "", "")
        override suspend fun getAll(): List<Source> = sourcesList
        override suspend fun getOnlineSources(): List<HttpSource> = sourcesList.filterIsInstance<HttpSource>()
        override suspend fun getStubSources(): List<StubSource> = sourcesList.filterIsInstance<StubSource>()
    }

    private class FakeMangaRepository : MangaRepository {
        var insertedCount = 0
        var lastInsertedManga: Manga? = null
        var errorToThrow: Throwable? = null
        private var nextId = 1L

        override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
            if (errorToThrow != null) throw errorToThrow!!
            insertedCount += manga.size
            return manga.map {
                lastInsertedManga = it
                it.copy(id = nextId++)
            }
        }

        override suspend fun getMangaById(id: Long): Manga = throw NotImplementedError()
        override fun getMangaByIdAsFlow(id: Long): Flow<Manga> = emptyFlow()
        override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? = null
        override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> = emptyFlow()
        override suspend fun getFavorites(): List<Manga> = emptyList()
        override suspend fun getReadMangaNotInLibrary(): List<Manga> = emptyList()
        override suspend fun getLibraryManga(): List<LibraryManga> = emptyList()
        override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> = emptyFlow()
        override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> = emptyFlow()
        override suspend fun getDuplicateLibraryManga(
            id: Long,
            title: String,
        ): List<MangaWithChapterCount> = emptyList()
        override suspend fun getUpcomingManga(
            statuses: Set<Long>,
            excludedCategories: List<Long>,
            includedCategories: List<Long>,
        ): Flow<List<Manga>> = emptyFlow()
        override suspend fun resetViewerFlags(): Boolean = true
        override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {}
        override suspend fun update(update: MangaUpdate): Boolean = true
        override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean = true
    }
}
