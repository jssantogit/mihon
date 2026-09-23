package eu.kanade.tachiyomi.data.tsuzuki.addon

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId

class MihonAddonRepositoryTest {

    @Test
    fun `multi source extension is exposed as one addon`() = runTest {
        val extension = installedExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.example",
            name = "Example",
            sources = listOf(
                FakeSource(id = 1L, lang = "en"),
                FakeSource(id = 2L, lang = "pt-BR"),
            ),
        )
        val disabled = MutableStateFlow(emptySet<String>())
        val repository = MihonAddonRepository(
            installedExtensionsFlow = flowOf(listOf(extension)),
            installedExtensionsSnapshot = { listOf(extension) },
            disabledSourceIds = { disabled.value },
            disabledSourceIdsFlow = disabled,
            setDisabledSourceIds = { disabled.value = it },
        )

        val addons = repository.snapshot()

        addons.size shouldBe 1
        addons.single().id shouldBe AddonId(extension.pkgName)
        addons.single().mihonSourceIds.shouldContainExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `partially disabled multi source addon exposes only enabled sources`() = runTest {
        val extension = installedExtension(
            pkgName = "pkg.partial",
            name = "Example",
            sources = listOf(
                FakeSource(id = 10L, lang = "en"),
                FakeSource(id = 20L, lang = "pt-BR"),
            ),
        )
        val repository = MihonAddonRepository(
            installedExtensionsFlow = flowOf(listOf(extension)),
            installedExtensionsSnapshot = { listOf(extension) },
            disabledSourceIds = { setOf("10") },
            disabledSourceIdsFlow = flowOf(setOf("10")),
            setDisabledSourceIds = {},
        )

        val addon = repository.snapshot().single()

        addon.enabled shouldBe true
        addon.mihonSourceIds.shouldContainExactlyInAnyOrder(20L)
    }

    @Test
    fun `update state is exposed at addon level`() = runTest {
        val extension = installedExtension(
            pkgName = "pkg.update",
            name = "Example",
            sources = listOf(FakeSource(id = 1L, lang = "en")),
            hasUpdate = true,
        )
        val repository = MihonAddonRepository(
            installedExtensionsFlow = flowOf(listOf(extension)),
            installedExtensionsSnapshot = { listOf(extension) },
            disabledSourceIds = { emptySet() },
            disabledSourceIdsFlow = flowOf(emptySet()),
            setDisabledSourceIds = {},
        )

        repository.snapshot().single().hasUpdate shouldBe true
    }

    @Test
    fun `uninstall delegates by addon package rather than source`() = runTest {
        val extension = installedExtension(
            pkgName = "pkg.remove",
            name = "Example",
            sources = listOf(
                FakeSource(id = 10L, lang = "en"),
                FakeSource(id = 20L, lang = "pt-BR"),
            ),
        )
        var removed: Extension.Installed? = null
        val repository = MihonAddonRepository(
            installedExtensionsFlow = flowOf(listOf(extension)),
            installedExtensionsSnapshot = { listOf(extension) },
            disabledSourceIds = { emptySet() },
            disabledSourceIdsFlow = flowOf(emptySet()),
            setDisabledSourceIds = {},
            uninstallExtension = { removed = it },
        )

        repository.uninstall(AddonId("pkg.remove"))

        removed shouldBe extension
    }

    @Test
    fun `disabling addon disables all internal mihon sources`() = runTest {
        val extension = installedExtension(
            pkgName = "pkg",
            name = "Example",
            sources = listOf(
                FakeSource(id = 10L, lang = "en"),
                FakeSource(id = 20L, lang = "pt-BR"),
            ),
        )
        val disabled = MutableStateFlow(emptySet<String>())
        val repository = MihonAddonRepository(
            installedExtensionsFlow = flowOf(listOf(extension)),
            installedExtensionsSnapshot = { listOf(extension) },
            disabledSourceIds = { disabled.value },
            disabledSourceIdsFlow = disabled,
            setDisabledSourceIds = { disabled.value = it },
        )

        repository.setEnabled(AddonId("pkg"), enabled = false)

        disabled.value shouldBe setOf("10", "20")
        repository.snapshot().single().enabled shouldBe false
    }

    private fun installedExtension(
        pkgName: String,
        name: String,
        sources: List<Source>,
        hasUpdate: Boolean = false,
    ) = Extension.Installed(
        name = name,
        pkgName = pkgName,
        versionName = "1.0",
        versionCode = 1L,
        libVersion = 1.0,
        lang = "all",
        isNsfw = false,
        pkgFactory = null,
        sources = sources,
        icon = null,
        hasUpdate = hasUpdate,
        isShared = false,
    )

    private class FakeSource(
        override val id: Long,
        override val lang: String,
    ) : Source {
        override val name = "Source $id"
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
        override suspend fun getPopularManga(page: Int): MangasPage = error("unused")
        override suspend fun getLatestUpdates(page: Int): MangasPage = error("unused")
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = error("unused")
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = error("unused")
        override suspend fun getPageList(chapter: SChapter): List<Page> = error("unused")
    }
}
