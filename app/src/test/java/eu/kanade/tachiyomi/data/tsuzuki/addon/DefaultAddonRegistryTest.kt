package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.content.ContentOption

class DefaultAddonRegistryTest {

    @Test
    fun `registry exposes providers only for installed and enabled addons`() {
        val enabled = addon("enabled", enabled = true)
        val disabled = addon("disabled", enabled = false)
        val repository = FakeAddonRepository(listOf(enabled, disabled))
        val registry = DefaultAddonRegistry(
            installedAddons = { repository.current },
            contentProviderCandidates = listOf(FakeContentProvider(enabled.id), FakeContentProvider(disabled.id)),
            chapterProbeProviderCandidates = listOf(
                FakeChapterProbeProvider(enabled.id),
                FakeChapterProbeProvider(disabled.id),
            ),
        )

        registry.contentProviders().map { it.addonId }.shouldContainExactly(enabled.id)
        registry.chapterProbeProviders().map { it.addonId }.shouldContainExactly(enabled.id)
    }

    @Test
    fun `registry never exposes addon that is only present in cloud desired state`() {
        val registry = DefaultAddonRegistry(
            installedAddons = { emptyList() },
            contentProviderCandidates = listOf(FakeContentProvider(AddonId("pkg"))),
            chapterProbeProviderCandidates = listOf(FakeChapterProbeProvider(AddonId("pkg"))),
            desiredAddonIds = { setOf(AddonId("pkg")) },
        )

        registry.contentProviders().isEmpty() shouldBe true
        registry.chapterProbeProviders().isEmpty() shouldBe true
    }

    private fun addon(id: String, enabled: Boolean) = InstalledAddon(
        id = AddonId(id),
        displayName = id,
        enabled = enabled,
        versionName = "1.0",
        mihonSourceIds = listOf(1L),
        hasSettings = false,
    )

    private class FakeAddonRepository(
        var current: List<InstalledAddon>,
    ) : AddonRepository {
        override fun observeInstalled() = flowOf(current)
        override suspend fun snapshot() = current
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class FakeContentProvider(
        override val addonId: AddonId,
    ) : ContentProvider {
        override suspend fun resolve(
            canonicalTitleId: String,
            canonicalChapterId: String,
        ): Result<List<ContentOption>> = Result.success(emptyList())
    }

    private class FakeChapterProbeProvider(
        override val addonId: AddonId,
    ) : ChapterProbeProvider {
        override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
            Result.success(emptyList())
    }
}
