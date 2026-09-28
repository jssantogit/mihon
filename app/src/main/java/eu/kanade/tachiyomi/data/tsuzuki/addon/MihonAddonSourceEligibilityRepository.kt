package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository

/** Reads currently loaded CatalogueSource eligibility for chapter safety and opt-in diagnostics. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonAddonSourceEligibilityRepository(
    private val extensionManager: ExtensionManager,
    private val sourcePreferences: SourcePreferences,
) : AddonSourceEligibilityRepository {

    override suspend fun getByAddonId(addonId: AddonId): List<AddonSourceEligibility> {
        val extension = extensionManager.getInstalledExtensions()
            .firstOrNull { it.pkgName == addonId.value }
            ?: return emptyList()
        val disabled = sourcePreferences.disabledSources.get()
        return extension.sources.filterIsInstance<CatalogueSource>().map { source ->
            AddonSourceEligibility(
                sourceId = source.id,
                language = source.lang,
                enabled = source.id.toString() !in disabled,
            )
        }
    }
}
