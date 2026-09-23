package tachiyomi.data.tsuzuki.collections

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tachiyomi.domain.tsuzuki.collections.cache.InFlightQueryDeduplicator
import tachiyomi.domain.tsuzuki.collections.cache.MemoryCatalogCache
import tachiyomi.domain.tsuzuki.collections.execution.CatalogRefreshCoordinator
import tachiyomi.domain.tsuzuki.collections.execution.CollectionExecutionResources
import tachiyomi.domain.tsuzuki.collections.scheduler.CollectionQueryScheduler

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CollectionExecutionResourcesImpl : CollectionExecutionResources {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val memoryCache: MemoryCatalogCache = MemoryCatalogCache()
    override val inFlightDeduplicator: InFlightQueryDeduplicator = InFlightQueryDeduplicator(scope)
    override val scheduler: CollectionQueryScheduler = CollectionQueryScheduler(scope)
    override val refreshCoordinator: CatalogRefreshCoordinator = CatalogRefreshCoordinator(scheduler)
}
