package eu.kanade.tachiyomi.ui.tsuzuki.source

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.SetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSourcePreferencesScreenModelTest {

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
    fun `loads grouped language preferences in stored order`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository()
        preferences.replaceForLanguage("ja", listOf(30L, 10L))
        preferences.replaceForLanguage("en", listOf(20L))
        val gateway = FakeGateway(
            mapOf(
                "ja" to listOf(
                    descriptor(10L, "Japanese A", "ja"),
                    descriptor(30L, "Japanese B", "ja"),
                ),
                "en" to listOf(descriptor(20L, "English", "en")),
            ),
        )

        val model = model(preferences, gateway)
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<ReadingSourcePreferencesScreenState.Success>()
        state.languages shouldBe listOf("en", "ja")
        state.selectedLanguage shouldBe "en"
        state.configuredSources.map { it.sourceId } shouldBe listOf(20L)

        model.selectLanguage("ja")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ReadingSourcePreferencesScreenState.Success>()
            .configuredSources.map { it.sourceId } shouldBe listOf(30L, 10L)
    }

    @Test
    fun `edits stay pending until explicit save`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository()
        preferences.replaceForLanguage("en", listOf(1L, 2L))
        val gateway = FakeGateway(
            mapOf(
                "en" to listOf(
                    descriptor(1L, "One", "en"),
                    descriptor(2L, "Two", "en"),
                    descriptor(3L, "Three", "en"),
                ),
            ),
        )
        val model = model(preferences, gateway)
        advanceUntilIdle()

        model.addSource(3L)
        model.removeSource(1L)
        advanceUntilIdle()

        val pending = model.state.value.shouldBeInstanceOf<ReadingSourcePreferencesScreenState.Success>()
        pending.pendingSourceIds shouldBe listOf(2L, 3L)
        preferences.getForLanguage("en").map { it.sourceId } shouldBe listOf(1L, 2L)

        model.save()
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ReadingSourcePreferencesScreenState.Success>()
            .savedSourceIds shouldBe listOf(2L, 3L)
        preferences.getForLanguage("en").map { it.sourceId } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `moves sources up and down without persisting`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository()
        preferences.replaceForLanguage("en", listOf(1L, 2L, 3L))
        val gateway = FakeGateway(mapOf("en" to (1L..3L).map { descriptor(it, "Source $it", "en") }))
        val model = model(preferences, gateway)
        advanceUntilIdle()

        model.moveDown(0)
        model.moveDown(1)
        model.moveUp(2)
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<ReadingSourcePreferencesScreenState.Success>()
            .pendingSourceIds shouldBe listOf(2L, 1L, 3L)
        preferences.getForLanguage("en").map { it.sourceId } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun `language edits remain isolated`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository()
        preferences.replaceForLanguage("en", listOf(1L))
        preferences.replaceForLanguage("ja", listOf(2L))
        val gateway = FakeGateway(
            mapOf(
                "en" to listOf(descriptor(1L, "English", "en"), descriptor(3L, "English 2", "en")),
                "ja" to listOf(descriptor(2L, "Japanese", "ja"), descriptor(4L, "Japanese 2", "ja")),
            ),
        )
        val model = model(preferences, gateway)
        advanceUntilIdle()

        model.addSource(3L)
        model.selectLanguage("ja")
        advanceUntilIdle()
        model.addSource(4L)
        model.save()
        advanceUntilIdle()

        preferences.getForLanguage("en").map { it.sourceId } shouldBe listOf(1L)
        preferences.getForLanguage("ja").map { it.sourceId } shouldBe listOf(2L, 4L)
    }

    private fun model(
        preferences: FakePreferencesRepository,
        gateway: FakeGateway,
    ) = ReadingSourcePreferencesScreenModel(
        getPreferredReadingSources = GetPreferredReadingSources(preferences),
        setPreferredReadingSources = SetPreferredReadingSources(preferences),
        readingSourceGateway = gateway,
    )

    private fun descriptor(id: Long, name: String, language: String) = ReadingSourceDescriptor(id, name, language)

    private class FakePreferencesRepository : ReadingSourcePreferenceRepository {
        private val values = mutableMapOf<String, List<ReadingSourcePreference>>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<ReadingSourcePreference>>>()

        override suspend fun getForLanguage(
            language: String,
        ): List<ReadingSourcePreference> = values[language].orEmpty()

        override fun observeForLanguage(
            language: String,
        ): Flow<List<ReadingSourcePreference>> =
            flows.getOrPut(language) { MutableStateFlow(values[language].orEmpty()) }

        override suspend fun getConfiguredLanguages(): List<String> = values
            .filterValues { it.isNotEmpty() }
            .keys
            .sorted()

        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) {
            require(orderedSourceIds.distinct().size == orderedSourceIds.size)
            val next = orderedSourceIds.mapIndexed { index, id -> ReadingSourcePreference(language, id, index) }
            values[language] = next
            flows.getOrPut(language) { MutableStateFlow(emptyList()) }.value = next
        }
    }

    private class FakeGateway(
        private val sources: Map<String, List<ReadingSourceDescriptor>>,
    ) : ReadingSourceGateway {
        override suspend fun listInstalled(
            language: String,
        ): List<ReadingSourceDescriptor> = sources[language].orEmpty()

        override suspend fun search(
            sourceId: Long,
            query: String,
        ): Result<List<ReadingSourceCandidate>> = Result.success(emptyList())

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> =
            Result.failure(UnsupportedOperationException())
    }
}
