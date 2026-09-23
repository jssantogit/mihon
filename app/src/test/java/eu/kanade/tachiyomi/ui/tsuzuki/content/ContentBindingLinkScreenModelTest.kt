package eu.kanade.tachiyomi.ui.tsuzuki.content

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingConfirmationRequiredException
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate

@OptIn(ExperimentalCoroutinesApi::class)
class ContentBindingLinkScreenModelTest {
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
    fun `MangaBall ambiguous editions require explicit choice before linking`() = runTest(dispatcher) {
        val addon = addon("mangaball", "Manga Ball", enabled = true, sourceIds = listOf(7L))
        val choices = listOf(
            candidate(7L, "/edition-one"),
            candidate(7L, "/edition-two"),
        )
        val resolver = mockk<ResolveContentBinding>()
        val confirm = mockk<ConfirmContentBinding>()
        coEvery { resolver.executeAll("canonical", addon.id) } returns
            Result.failure(ContentBindingConfirmationRequiredException(choices))
        coEvery { confirm.execute("canonical", addon.id, choices.last()) } returns
            Result.success(
                ContentBinding(
                    id = "bound",
                    canonicalTitleId = "canonical",
                    addonId = addon.id,
                    providerTitleKey = "7:/edition-two",
                    matchConfidence = 1.0,
                    verifiedByUser = true,
                    availability = ContentBindingAvailability.AVAILABLE,
                    runtimePayload = byteArrayOf(1),
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
            )
        val model = ContentBindingLinkScreenModel(repo(listOf(addon)), resolver, confirm)

        model.start("canonical")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ContentBindingLinkState.Addons>()
            .enabled.single().id shouldBe addon.id

        model.selectAddon(addon.id)
        advanceUntilIdle()
        val pending = model.state.value.shouldBeInstanceOf<ContentBindingLinkState.Candidates>()
        pending.candidates.map { it.candidate.sourceUrl } shouldBe listOf("/edition-one", "/edition-two")

        model.confirm(candidate(7L, "/never-displayed"))
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ContentBindingLinkState.Candidates>()
        coVerify(exactly = 0) { confirm.execute(any(), any(), any()) }

        model.confirm(choices.last())
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ContentBindingLinkState.Linked>().addonName shouldBe "Manga Ball"
        coVerify(exactly = 1) { confirm.execute("canonical", addon.id, choices.last()) }
    }

    @Test
    fun `disabled and source-less Add-ons are not offered for manual title linking`() = runTest(dispatcher) {
        val addons = listOf(
            addon("mangaball", "Manga Ball", enabled = true, sourceIds = listOf(7L)),
            addon("mangafire", "MangaFire", enabled = false, sourceIds = listOf(8L)),
            addon("empty", "Empty", enabled = true, sourceIds = emptyList()),
        )
        val model = ContentBindingLinkScreenModel(
            repo(addons),
            mockk(),
            mockk(),
        )
        model.start("canonical")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<ContentBindingLinkState.Addons>()
            .enabled.map { it.id.value } shouldBe listOf("mangaball")
    }

    private fun repo(addons: List<InstalledAddon>) = object : AddonRepository {
        override fun observeInstalled() = flowOf(addons)
        override suspend fun snapshot() = addons
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private fun addon(id: String, name: String, enabled: Boolean, sourceIds: List<Long>) = InstalledAddon(
        id = AddonId(id),
        displayName = name,
        enabled = enabled,
        versionName = "1.0",
        mihonSourceIds = sourceIds,
        hasSettings = false,
    )

    private fun candidate(id: Long, url: String) = ScoredSourceCandidate(
        candidate = ReadingSourceCandidate(
            sourceId = id,
            sourceName = "Manga Ball",
            language = "pt-BR",
            sourceUrl = url,
            title = "One-Punch Man",
            thumbnailUrl = null,
            author = null,
            artist = null,
            description = null,
            genres = null,
            status = 0L,
        ),
        confidence = 1.0,
        sourcePreferenceRank = 0,
    )
}
