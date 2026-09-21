package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.ConfigurableSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository

class MihonAddonRepository internal constructor(
    private val installedExtensionsFlow: Flow<List<Extension.Installed>>,
    private val installedExtensionsSnapshot: suspend () -> List<Extension.Installed>,
    private val disabledSourceIds: () -> Set<String>,
    private val disabledSourceIdsFlow: Flow<Set<String>>,
    private val setDisabledSourceIds: (Set<String>) -> Unit,
) : AddonRepository {

    @Inject
    constructor(
        extensionManager: ExtensionManager,
        sourcePreferences: SourcePreferences,
    ) : this(
        installedExtensionsFlow = extensionManager.installedExtensionsFlow,
        installedExtensionsSnapshot = extensionManager::getInstalledExtensions,
        disabledSourceIds = sourcePreferences.disabledSources::get,
        disabledSourceIdsFlow = sourcePreferences.disabledSources.changes()
            .onStart { emit(sourcePreferences.disabledSources.get()) },
        setDisabledSourceIds = sourcePreferences.disabledSources::set,
    )

    override fun observeInstalled(): Flow<List<InstalledAddon>> {
        return combine(
            installedExtensionsFlow,
            disabledSourceIdsFlow,
        ) { extensions, disabled ->
            extensions.map { it.toInstalledAddon(disabled) }
        }.distinctUntilChanged()
    }

    override suspend fun snapshot(): List<InstalledAddon> {
        val disabled = disabledSourceIds()
        return installedExtensionsSnapshot().map { it.toInstalledAddon(disabled) }
    }

    override suspend fun setEnabled(id: AddonId, enabled: Boolean) {
        val extension = installedExtensionsSnapshot().firstOrNull { it.pkgName == id.value } ?: return
        val sourceIds = extension.sources.map { it.id.toString() }.toSet()
        val current = disabledSourceIds()
        setDisabledSourceIds(
            if (enabled) {
                current - sourceIds
            } else {
                current + sourceIds
            },
        )
    }

    private fun Extension.Installed.toInstalledAddon(disabled: Set<String>): InstalledAddon {
        val sourceIds = sources.map { it.id }
        return InstalledAddon(
            id = AddonId(pkgName),
            displayName = name,
            enabled = sourceIds.isNotEmpty() && sourceIds.any { it.toString() !in disabled },
            versionName = versionName,
            mihonSourceIds = sourceIds,
            hasSettings = sources.any { it is ConfigurableSource },
        )
    }
}
