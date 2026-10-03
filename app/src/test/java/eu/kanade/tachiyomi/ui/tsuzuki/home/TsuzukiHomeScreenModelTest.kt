package eu.kanade.tachiyomi.ui.tsuzuki.home

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.interactor.GetHomeHero
import tachiyomi.domain.tsuzuki.home.interactor.ObserveHomeContinueReading
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.home.model.HomeSection
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.interactor.ImportLegacyCanonicalProgress
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiHomeScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fresh Home has no automatic discovery sections`() = runTest(dispatcher) {
        val continueReading = MutableStateFlow(emptyList<HomeContinueReadingItem>())
        val sections = MutableStateFlow(emptyList<HomeSection>())
        val model = createModel(
            continueReading = continueReading,
            sections = sections,
        )

        advanceUntilIdle()

        model.state.value.continueReading shouldBe emptyList()
        model.state.value.sections shouldBe emptyList()
    }

    @Test
    fun `Hero is loaded independently from Continue Reading`() = runTest(dispatcher) {
        val hero = CatalogItem(
            provider = "kitsu",
            providerId = "hero-1",
            title = "Hero work",
            coverUrl = "https://example/hero-cover.jpg",
            bannerUrl = "https://example/hero-banner.jpg",
        )
        val getHomeHero = mockk<GetHomeHero>()
        coEvery { getHomeHero.await(any()) } returns hero
        val model = createModel(
            continueReading = MutableStateFlow(emptyList()),
            sections = MutableStateFlow(emptyList()),
            homeHero = getHomeHero,
        )

        advanceUntilIdle()

        model.state.value.hero shouldBe hero
        model.state.value.continueReading shouldBe emptyList()
    }

    @Test
    fun `continue reading appears only when observer reports real progress`() = runTest(dispatcher) {
        val continueReading = MutableStateFlow(emptyList<HomeContinueReadingItem>())
        val sections = MutableStateFlow(emptyList<HomeSection>())
        val model = createModel(
            continueReading = continueReading,
            sections = sections,
        )
        advanceUntilIdle()

        continueReading.value = listOf(item(updatedAt = 500))
        advanceUntilIdle()

        model.state.value.continueReading.map { it.canonicalTitleId } shouldBe
            listOf("title-1")
    }

    @Test
    fun `continue reading resolves cover even when title is outside Library`() = runTest(dispatcher) {
        val resolver = mockk<ResolveCanonicalSourceManga>()
        coEvery { resolver.execute("title-1", allowNetwork = false) } returns Manga.create().copy(
            id = 77L,
            source = 10L,
            url = "/dandadan",
            title = "Dandadan",
            thumbnailUrl = "https://cdn.example/dandadan.jpg",
        )
        val model = createModel(
            continueReading = MutableStateFlow(listOf(item(updatedAt = 500))),
            sections = MutableStateFlow(emptyList()),
            sourceMangaResolver = resolver,
        )

        advanceUntilIdle()

        val item = model.state.value.continueReading.single()
        item.coverUrl shouldBe null
        item.sourceCover?.sourceId shouldBe 10L
        item.sourceCover?.url shouldBe "https://cdn.example/dandadan.jpg"
        coVerify(exactly = 1) { resolver.execute("title-1", allowNetwork = false) }
        coVerify(exactly = 0) { resolver.execute("title-1", allowNetwork = true) }
    }

    // Provider artwork must survive the transient Search -> canonical boundary.
    @Test
    fun `continue reading prefers persisted canonical provider artwork over source fallback`() = runTest(dispatcher) {
        val resolver = mockk<ResolveCanonicalSourceManga>()
        coEvery { resolver.execute("title-1", allowNetwork = false) } returns Manga.create().copy(
            id = 77L,
            source = 10L,
            url = "/dandadan",
            title = "Dandadan",
            thumbnailUrl = "https://source.example/dandadan.jpg",
        )
        val artwork = FakeTitleArtworkRepository(
            listOf(
                TitleArtworkObservation(
                    canonicalTitleId = "title-1",
                    provider = "kitsu",
                    coverUrl = "https://kitsu.example/dandadan.jpg",
                    updatedAt = 1L,
                ),
            ),
        )
        val model = createModel(
            continueReading = MutableStateFlow(listOf(item(updatedAt = 500))),
            sections = MutableStateFlow(emptyList()),
            sourceMangaResolver = resolver,
            artworkRepository = artwork,
        )

        advanceUntilIdle()

        val item = model.state.value.continueReading.single()
        item.coverUrl shouldBe "https://kitsu.example/dandadan.jpg"
        item.sourceCover?.url shouldBe "https://source.example/dandadan.jpg"
        coVerify(exactly = 0) { resolver.execute("title-1", allowNetwork = true) }
    }

    @Test
    fun `legacy progress import runs once per title across repeated library emissions`() = runTest(dispatcher) {
        val first = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-1",
                displayTitle = "One",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            entry = tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry(
                canonicalTitleId = "title-1",
                status = tachiyomi.domain.tsuzuki.model.LibraryStatus.READING,
                favorite = true,
                addedAt = 1L,
                updatedAt = 1L,
            ),
        )
        val second = first.copy(
            title = first.title.copy(id = "title-2", displayTitle = "Two"),
            entry = first.entry.copy(canonicalTitleId = "title-2"),
        )
        val library = MutableStateFlow(listOf(first))
        val importer = mockk<ImportLegacyCanonicalProgress>()
        coEvery { importer.execute(any()) } returns 0

        createModel(
            continueReading = MutableStateFlow(emptyList()),
            sections = MutableStateFlow(emptyList()),
            libraryItems = library,
            legacyImporter = importer,
        )
        advanceUntilIdle()

        library.value = listOf(first, second)
        advanceUntilIdle()

        coVerify(exactly = 1) { importer.execute("title-1") }
        coVerify(exactly = 1) { importer.execute("title-2") }
    }

    @Test
    fun `remove from continue reading stores suppression without touching progress`() = runTest(dispatcher) {
        val visibility = mockk<ContinueReadingVisibilityRepository>(relaxed = true)
        val model = createModel(
            continueReading = MutableStateFlow(listOf(item(updatedAt = 500))),
            sections = MutableStateFlow(emptyList()),
            visibility = visibility,
        )
        advanceUntilIdle()

        model.removeFromContinueReading(item(updatedAt = 500))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            visibility.hide(
                canonicalTitleId = "title-1",
                hiddenAt = match { it >= 500L },
            )
        }
    }

    @Test
    fun `configured Home catalog item opens canonical detail identity`() = runTest(dispatcher) {
        val catalogItem = CatalogItem(
            provider = "kitsu",
            providerId = "123",
            title = "Dandadan",
        )
        val materializer = mockk<MaterializeCanonicalTitleFromCatalog>()
        coEvery { materializer.execute(catalogItem) } returns CanonicalTitle(
            id = "canonical-dandadan",
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val model = createModel(
            continueReading = MutableStateFlow(emptyList()),
            sections = MutableStateFlow(emptyList()),
            materializer = materializer,
        )

        model.openCatalogItem(catalogItem)
        advanceUntilIdle()

        model.events.first() shouldBe TsuzukiHomeEvent.OpenCanonicalTitle("canonical-dandadan")
    }

    private fun createModel(
        continueReading: MutableStateFlow<List<HomeContinueReadingItem>>,
        sections: MutableStateFlow<List<HomeSection>>,
        visibility: ContinueReadingVisibilityRepository =
            mockk(relaxed = true),
        materializer: MaterializeCanonicalTitleFromCatalog =
            mockk(relaxed = true),
        sourceMangaResolver: ResolveCanonicalSourceManga =
            mockk(relaxed = true),
        artworkRepository: TitleArtworkRepository = FakeTitleArtworkRepository(emptyList()),
        libraryItems: MutableStateFlow<List<CanonicalLibraryItem>> = MutableStateFlow(emptyList()),
        legacyImporter: ImportLegacyCanonicalProgress = mockk(relaxed = true),
        homeHero: GetHomeHero = defaultHomeHero(),
    ): TsuzukiHomeScreenModel {
        val observeHome = mockk<ObserveHomeContinueReading>()
        every { observeHome.subscribe() } returns continueReading

        val configured = mockk<GetConfiguredHomeSections>()
        every { configured.subscribe() } returns sections

        val observeLibrary = mockk<ObserveCanonicalLibrary>()
        every { observeLibrary.subscribe() } returns libraryItems

        val history = mockk<HistoryRepository>()
        every { history.getHistory("") } returns MutableStateFlow(emptyList())

        return TsuzukiHomeScreenModel(
            observeHomeContinueReading = observeHome,
            getConfiguredHomeSections = configured,
            getHomeHero = homeHero,
            visibilityRepository = visibility,
            observeCanonicalLibrary = observeLibrary,
            historyRepository = history,
            importLegacyCanonicalProgress = legacyImporter,
            materializeCanonicalTitleFromCatalog = materializer,
            resolveCanonicalSourceManga = sourceMangaResolver,
            titleArtworkRepository = artworkRepository,
        )
    }

    private fun defaultHomeHero(): GetHomeHero {
        return mockk<GetHomeHero>().also { interactor ->
            coEvery { interactor.await(any()) } returns null
        }
    }

    private class FakeTitleArtworkRepository(
        initial: List<TitleArtworkObservation>,
    ) : TitleArtworkRepository {
        private val values = MutableStateFlow(initial)

        override fun observeAll() = values

        override suspend fun getByTitle(canonicalTitleId: String): List<TitleArtworkObservation> =
            values.value.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(observation: TitleArtworkObservation) {
            values.value = values.value
                .filterNot {
                    it.canonicalTitleId == observation.canonicalTitleId &&
                        it.provider == observation.provider
                } + observation
        }
    }

    private fun item(
        updatedAt: Long,
    ) = HomeContinueReadingItem(
        canonicalTitleId = "title-1",
        title = "Title One",
        canonicalChapterId = "chapter-1",
        chapterDisplayNumber = "1",
        lastPageRead = 4,
        updatedAt = updatedAt,
        newChapterCount = 2,
    )
}
