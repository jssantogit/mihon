package eu.kanade.tachiyomi.data.tsuzuki.addon

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon

class DefaultAddonRegistry internal constructor(
    private val installedAddons: () -> List<InstalledAddon>,
    private val contentProviderCandidates: List<ContentProvider>,
    private val chapterProbeProviderCandidates: List<ChapterProbeProvider>,
    @Suppress("unused")
    private val desiredAddonIds: () -> Set<AddonId> = { emptySet() },
) : AddonRegistry {

    override fun contentProviders(): List<ContentProvider> {
        val enabledInstalled = enabledInstalledAddonIds()
        return contentProviderCandidates.filter { it.addonId in enabledInstalled }
    }

    override fun chapterProbeProviders(): List<ChapterProbeProvider> {
        val enabledInstalled = enabledInstalledAddonIds()
        return chapterProbeProviderCandidates.filter { it.addonId in enabledInstalled }
    }

    private fun enabledInstalledAddonIds(): Set<AddonId> {
        return installedAddons()
            .asSequence()
            .filter(InstalledAddon::enabled)
            .map(InstalledAddon::id)
            .toSet()
    }
}
