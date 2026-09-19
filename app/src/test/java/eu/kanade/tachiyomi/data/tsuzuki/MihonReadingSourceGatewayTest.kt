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

    private lateinit var sourcePreferences: SourcePreferences
    private lateinit var sourceManager: FakeSourceManager
    private lateinit var mangaRepository: FakeMangaRepository
    private lateinit var gateway: MihonReadingSourceGateway

    @BeforeEach
    fun setUp() {
        sourcePreferences = SourcePreferences(InMemoryPreferenceStore())
        sourceManager = FakeSourceManager()
        mangaRepository = FakeMangaRepository()
        gateway = MihonReadingSourceGateway(
            sourceManager = sourceManager,
            sourcePreferences = sourcePreferences,
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
        )
    }

    @Test
    fun `listInstalled filters installed enabled catalogue sources by language`() = runTest {
        sourceManager.sourcesList += listOf(
            TestCatalogueSource(1L, "English A", "en"),
            TestCatalogueSource(2L, "English B", "en"),
            TestCatalogueSource(3L, "Japanese", "ja"),
            StubSource(4L, "en", "Missing"),
        )
        sourcePreferences.disabledSources.set(setOf("2"))

        gateway.listInstalled("en") shouldContainExactly listOf(
            ReadingSourceDescriptor(1L, "English A", "en"),
        )
    }

    @Test
    fun `search uses one source page one default filters and keeps results transient`() = runTest {
        val first = SManga.create().apply {
            url = "/manga/1"
            title = "Title 1"
            thumbnail_url = "https://thumb/1.jpg"
            author = "Author"
            artist = "Artist"
            description = "Description"
            genre = "Action, Drama"
            status = SManga.ONGOING
        }
        val duplicate = first.copy().apply { title = "Duplicate" }
        val source = TestCatalogueSource(
            id = 10L,
            name = "Test Source",
            lang = "en",
            searchResults = listOf(first, duplicate),
        )
        sourceManager.sourcesList += source

        val result = gateway.search(10L, "query").getOrThrow()

        result shouldContainExactly listOf(
            ReadingSourceCandidate(
                sourceId = 10L,
                sourceName = "Test Source",
                language = "en",
                sourceUrl = "/manga/1",
                title = "Title 1",
                thumbnailUrl = "https://thumb/1.jpg",
                author = "Author",
                artist = "Artist",
                description = "Description",
                genres = listOf("Action", "Drama"),
                status = SManga.ONGOING.toLong(),
            ),
        )
        source.lastPageSearched shouldBe 1
        source.lastQuerySearched shouldBe "query"
        source.lastFiltersSearched shouldBe source.getFilterList()
        mangaRepository.insertedCount shouldBe 0
    }

    @Test
    fun `search rejects disabled source without persistence`() = runTest {
        sourceManager.sourcesList += TestCatalogueSource(10L, "Test", "en")
        sourcePreferences.disabledSources.set(setOf("10"))

        gateway.search(10L, "query").isFailure shouldBe true
        mangaRepository.insertedCount shouldBe 0
    }

    @Test
    fun `search wraps source failure but rethrows cancellation`() = runTest {
        sourceManager.sourcesList += TestCatalogueSource(
            10L,
            "Failure",
            "en",
            errorToThrow = RuntimeException("boom"),
        )
        gateway.search(10L, "query").exceptionOrNull()?.message shouldBe "boom"

        sourceManager.sourcesList.clear()
        sourceManager.sourcesList += TestCatalogueSource(
            11L,
            "Cancelled",
            "en",
            errorToThrow = CancellationException("cancelled"),
        )
        shouldThrow<CancellationException> {
            gateway.search(11L, "query")
        }
    }

    @Test
    fun `materialize persists accepted candidate metadata without favoriting`() = runTest {
        val candidate = candidate()
        val materialized = gateway.materialize(candidate).getOrThrow()

        materialized.mihonMangaId shouldBe 1L
        materialized.sourceId shouldBe candidate.sourceId
        materialized.sourceUrl shouldBe candidate.sourceUrl
        materialized.language shouldBe candidate.language

        mangaRepository.insertedCount shouldBe 1
        mangaRepository.lastInsertedManga?.favorite shouldBe false
        mangaRepository.lastInsertedManga?.author shouldBe "Author"
        mangaRepository.lastInsertedManga?.artist shouldBe "Artist"
        mangaRepository.lastInsertedManga?.description shouldBe "Description"
        mangaRepository.lastInsertedManga?.genre shouldBe listOf("Action")
        mangaRepository.lastInsertedManga?.status shouldBe SManga.ONGOING.toLong()
    }

    @Test
    fun `materialize rethrows cancellation`() = runTest {
        mangaRepository.errorToThrow = CancellationException("cancelled")

        shouldThrow<CancellationException> {
            gateway.materialize(candidate())
        }
    }

    private fun candidate() = ReadingSourceCandidate(
        sourceId = 100L,
        sourceName = "Source",
        language = "en",
        sourceUrl = "/manga/berserk",
        title = "Berserk",
        thumbnailUrl = "https://thumb",
        author = "Author",
        artist = "Artist",
        description = "Description",
        genres = listOf("Action"),
        status = SManga.ONGOING.toLong(),
    )

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
            errorToThrow?.let { throw it }
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
            errorToThrow?.let { throw it }
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
