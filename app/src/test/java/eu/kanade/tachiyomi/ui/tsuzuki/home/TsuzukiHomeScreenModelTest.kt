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
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
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
    ): TsuzukiHomeScreenModel {
        val observeHome = mockk<ObserveHomeContinueReading>()
        every { observeHome.subscribe() } returns continueReading

        val configured = mockk<GetConfiguredHomeSections>()
        every { configured.subscribe() } returns sections

        val observeLibrary = mockk<ObserveCanonicalLibrary>()
        every { observeLibrary.subscribe() } returns
            MutableStateFlow(emptyList<CanonicalLibraryItem>())

        val history = mockk<HistoryRepository>()
        every { history.getHistory("") } returns MutableStateFlow(emptyList())

        val importLegacy = mockk<ImportLegacyCanonicalProgress>()
        coEvery { importLegacy.execute(any()) } returns 0

        return TsuzukiHomeScreenModel(
            observeHomeContinueReading = observeHome,
            getConfiguredHomeSections = configured,
            visibilityRepository = visibility,
            observeCanonicalLibrary = observeLibrary,
            historyRepository = history,
            importLegacyCanonicalProgress = importLegacy,
            materializeCanonicalTitleFromCatalog = materializer,
        )
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
