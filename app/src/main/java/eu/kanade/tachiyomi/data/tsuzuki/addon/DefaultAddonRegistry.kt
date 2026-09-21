package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultAddonRegistry private constructor(
    private val installedAddons: () -> List<InstalledAddon>,
    private val contentProviderFor: (AddonId) -> ContentProvider?,
    private val chapterProbeProviderFor: (AddonId) -> ChapterProbeProvider?,
    private val localContentProvider: ContentProvider?,
    @Suppress("unused")
    private val desiredAddonIds: () -> Set<AddonId>,
) : AddonRegistry {

    internal constructor(
        installedAddons: () -> List<InstalledAddon>,
        contentProviderCandidates: List<ContentProvider>,
        chapterProbeProviderCandidates: List<ChapterProbeProvider>,
        localContentProvider: ContentProvider? = null,
        desiredAddonIds: () -> Set<AddonId> = { emptySet() },
    ) : this(
        installedAddons = installedAddons,
        contentProviderFor = { addonId -> contentProviderCandidates.firstOrNull { it.addonId == addonId } },
        chapterProbeProviderFor = { addonId ->
            chapterProbeProviderCandidates.firstOrNull { it.addonId == addonId }
        },
        localContentProvider = localContentProvider,
        desiredAddonIds = desiredAddonIds,
    )

    @Inject
    constructor(
        addonRepository: AddonRepository,
        providerFactory: MihonAddonProviderFactory,
        localContentProvider: LocalContentProvider,
    ) : this(RuntimeState(addonRepository, providerFactory, localContentProvider))

    private constructor(runtimeState: RuntimeState) : this(
        installedAddons = { runtimeState.installedAddons.value },
        contentProviderFor = runtimeState.providerFactory::contentProvider,
        chapterProbeProviderFor = runtimeState.providerFactory::chapterProbeProvider,
        localContentProvider = runtimeState.localContentProvider,
        desiredAddonIds = { emptySet() },
    )

    override fun contentProviders(): List<ContentProvider> {
        return buildList {
            localContentProvider?.let(::add)
            addAll(enabledInstalledAddons().mapNotNull { contentProviderFor(it.id) })
        }
    }

    override fun chapterProbeProviders(): List<ChapterProbeProvider> {
        return enabledInstalledAddons().mapNotNull { chapterProbeProviderFor(it.id) }
    }

    private fun enabledInstalledAddons(): List<InstalledAddon> {
        return installedAddons().filter(InstalledAddon::enabled)
    }

    private class RuntimeState(
        addonRepository: AddonRepository,
        val providerFactory: MihonAddonProviderFactory,
        val localContentProvider: LocalContentProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val installedAddons: StateFlow<List<InstalledAddon>> = addonRepository.observeInstalled()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }
}
