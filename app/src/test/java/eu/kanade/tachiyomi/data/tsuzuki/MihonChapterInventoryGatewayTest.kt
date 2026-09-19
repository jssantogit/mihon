package eu.kanade.tachiyomi.data.tsuzuki

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

class MihonChapterInventoryGatewayTest {

    @Test
    fun `fetch reads legacy chapters passes them to source and never writes mihon rows`() = runTest {
        val mangaRepository = FakeMangaRepository(Manga.create().copy(id = 42L, source = 7L))
        val legacy = Chapter.create().copy(
            id = 100L,
            mangaId = 42L,
            url = "/known",
            name = "Old name",
            chapterNumber = 3.0,
            sourceOrder = 8L,
        )
        val chapterRepository = FakeChapterRepository(listOf(legacy))
        val source = TestSource(7L) {
            listOf(
                chapter("/new", "Chapter 4", 4.5f, "Group", 11L),
                chapter("/known", "Chapter 3", 3f, "Other", 12L),
            )
        }
        val gateway = MihonChapterInventoryGateway(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = FakeSourceManager(source),
        )

        val result = gateway.fetch(mapping())
        val inventory = result.getOrThrow()

        source.lastManga?.url shouldBe mangaRepository.manga.url
        source.lastLegacyUrls shouldContainExactly listOf("/known")
        source.lastFetchDetails shouldBe false
        source.lastFetchChapters shouldBe true
        inventory.sourceMappingId shouldBe "mapping-7"
        inventory.canonicalTitleId shouldBe "title-1"
        inventory.chapters.map { it.sourceChapterId } shouldContainExactly listOf("/new", "/known")
        inventory.chapters.map { it.rawNumberHint } shouldContainExactly listOf(4.5, 3.0)
        inventory.chapters[0].rawSourceOrder shouldBe 0L
        inventory.chapters[1].rawSourceOrder shouldBe 8L
        inventory.chapters[0].scanlationGroup shouldBe "Group"
        inventory.chapters[0].releaseDate shouldBe 11L
        inventory.chapters[0].rawSourceMetadata shouldBe JsonObject(mapOf("source" to JsonPrimitive("memo")))
        chapterRepository.writeCount shouldBe 0
        mangaRepository.writeCount shouldBe 0
    }

    @Test
    fun `fetch wraps source failures and propagates cancellation`() = runTest {
        val mangaRepository = FakeMangaRepository(Manga.create().copy(id = 42L, source = 7L))
        val chapterRepository = FakeChapterRepository(emptyList())
        val source = TestSource(7L) { throw IllegalStateException("network") }
        val gateway = MihonChapterInventoryGateway(
            mangaRepository,
            chapterRepository,
            FakeSourceManager(source),
        )

        gateway.fetch(mapping()).exceptionOrNull()?.message shouldBe "network"

        val cancelled = TestSource(7L) { throw CancellationException("cancelled") }
        val cancelledGateway = MihonChapterInventoryGateway(
            mangaRepository,
            chapterRepository,
            FakeSourceManager(cancelled),
        )
        shouldThrow<CancellationException> { cancelledGateway.fetch(mapping()) }
    }

    @Test
    fun `fetch rejects an unmaterialized mapping before source access`() = runTest {
        val source = TestSource(7L) { emptyList() }
        val gateway = MihonChapterInventoryGateway(
            FakeMangaRepository(Manga.create().copy(id = 42L, source = 7L)),
            FakeChapterRepository(emptyList()),
            FakeSourceManager(source),
        )

        gateway.fetch(mapping().copy(mihonMangaId = null)).isFailure shouldBe true
        source.wasCalled shouldBe false
    }

    @Test
    fun `fetch fails for an absent or stub source without writing mihon rows`() = runTest {
        val mangaRepository = FakeMangaRepository(Manga.create().copy(id = 42L, source = 7L))
        val chapterRepository = FakeChapterRepository(emptyList())

        val absentGateway = MihonChapterInventoryGateway(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = FakeSourceManager(null),
        )
        absentGateway.fetch(mapping()).isFailure shouldBe true

        val stubGateway = MihonChapterInventoryGateway(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = FakeSourceManager(StubSource(7L, "en", "Unavailable")),
        )
        stubGateway.fetch(mapping()).isFailure shouldBe true

        chapterRepository.writeCount shouldBe 0
        mangaRepository.writeCount shouldBe 0
    }

    private fun mapping() = SourceTitleMapping(
        id = "mapping-7",
        canonicalTitleId = "title-1",
        mihonMangaId = 42L,
        sourceId = 7L,
        sourceUrl = "/title",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun chapter(
        url: String,
        name: String,
        number: Float,
        scanlator: String?,
        upload: Long,
    ) = SChapter.create().also {
        it.url = url
        it.name = name
        it.chapter_number = number
        it.scanlator = scanlator
        it.date_upload = upload
        it.memo = JsonObject(mapOf("source" to JsonPrimitive("memo")))
    }

    private class TestSource(
        override val id: Long,
        private val chaptersFactory: suspend () -> List<SChapter>,
    ) : Source {
        override val name: String = "Test"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false
        var wasCalled = false
        var lastManga: SManga? = null
        var lastLegacyUrls: List<String> = emptyList()
        var lastFetchDetails: Boolean? = null
        var lastFetchChapters: Boolean? = null

        override fun getFilterList(): FilterList = FilterList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            wasCalled = true
            lastManga = manga
            lastLegacyUrls = chapters.map { it.url }
            lastFetchDetails = fetchDetails
            lastFetchChapters = fetchChapters
            return SMangaUpdate(manga, chaptersFactory())
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    private class FakeSourceManager(private val source: Source?) : SourceManager {
        override val sources: Flow<List<Source>> = emptyFlow()
        override suspend fun get(sourceKey: Long): Source? = source?.takeIf { it.id == sourceKey }
        override suspend fun getOrStub(sourceKey: Long): Source = get(sourceKey) ?: StubSource(sourceKey, "", "")
        override suspend fun getAll(): List<Source> = listOfNotNull(source)
        override suspend fun getOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()
        override suspend fun getStubSources(): List<StubSource> = emptyList()
    }

    private class FakeMangaRepository(
        val manga: Manga,
    ) : MangaRepository {
        var writeCount = 0
        override suspend fun getMangaById(id: Long): Manga = manga
        override fun getMangaByIdAsFlow(id: Long): Flow<Manga> = emptyFlow()
        override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? = null
        override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> = emptyFlow()
        override suspend fun getFavorites(): List<Manga> = emptyList()
        override suspend fun getReadMangaNotInLibrary(): List<Manga> = emptyList()
        override suspend fun getLibraryManga(): List<LibraryManga> = emptyList()
        override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> = emptyFlow()
        override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> = emptyFlow()
        override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> = emptyList()
        override suspend fun getUpcomingManga(
            statuses: Set<Long>,
            excludedCategories: List<Long>,
            includedCategories: List<Long>,
        ): Flow<List<Manga>> = emptyFlow()
        override suspend fun resetViewerFlags(): Boolean = true
        override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {}
        override suspend fun update(update: MangaUpdate): Boolean { writeCount++; return true }
        override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean { writeCount++; return true }
        override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> { writeCount++; return manga }
    }

    private class FakeChapterRepository(
        private val chapters: List<Chapter>,
    ) : ChapterRepository {
        var writeCount = 0
        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> { writeCount++; return chapters }
        override suspend fun update(chapterUpdate: ChapterUpdate) { writeCount++ }
        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) { writeCount++ }
        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) { writeCount++ }
        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> = chapters
        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = emptyFlow()
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()
        override suspend fun getChapterById(id: Long): Chapter? = chapters.firstOrNull { it.id == id }
        override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> = emptyFlow()
        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
            chapters.firstOrNull { it.url == url }
    }
}
