package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val internalContentProviders: List<ContentProvider>,
    @Suppress("unused")
    private val desiredAddonIds: () -> Set<AddonId>,
    private val awaitReadyBlock: suspend () -> Unit,
    private val changes: () -> Flow<Unit>,
) : AddonRegistry {

    internal constructor(
        installedAddons: () -> List<InstalledAddon>,
        contentProviderCandidates: List<ContentProvider>,
        chapterProbeProviderCandidates: List<ChapterProbeProvider>,
        internalContentProviders: List<ContentProvider> = emptyList(),
        desiredAddonIds: () -> Set<AddonId> = { emptySet() },
        awaitReadyBlock: suspend () -> Unit = {},
        changes: () -> Flow<Unit> = { emptyFlow() },
    ) : this(
        installedAddons = installedAddons,
        contentProviderFor = { addonId -> contentProviderCandidates.firstOrNull { it.addonId == addonId } },
        chapterProbeProviderFor = { addonId ->
            chapterProbeProviderCandidates.firstOrNull { it.addonId == addonId }
        },
        internalContentProviders = internalContentProviders,
        desiredAddonIds = desiredAddonIds,
        awaitReadyBlock = awaitReadyBlock,
        changes = changes,
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
        internalContentProviders = runtimeState.internalContentProviders,
        desiredAddonIds = { emptySet() },
        awaitReadyBlock = runtimeState::awaitReady,
        changes = runtimeState::observeChanges,
    )

    override suspend fun awaitReady() {
        awaitReadyBlock()
    }

    override fun observeChanges(): Flow<Unit> = changes()

    override fun contentProviders(): List<ContentProvider> {
        return internalContentProviders + enabledInstalledAddons().mapNotNull { contentProviderFor(it.id) }
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
        localContentProvider: LocalContentProvider,
    ) {
        val internalContentProviders: List<ContentProvider> = listOf(localContentProvider)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val ready = CompletableDeferred<Unit>()
        val installedAddons = MutableStateFlow<List<InstalledAddon>>(emptyList())

        init {
            addonRepository.observeInstalled()
                .onEach { addons ->
                    installedAddons.value = addons
                    if (!ready.isCompleted) {
                        ready.complete(Unit)
                    }
                }
                .launchIn(scope)
        }

        suspend fun awaitReady() {
            ready.await()
        }

        fun observeChanges(): Flow<Unit> = installedAddons
            .drop(1)
            .map { Unit }
    }
}
