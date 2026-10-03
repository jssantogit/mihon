package tachiyomi.data.tsuzuki.collections

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.collections.execution.CollectionQueryProvider
import tachiyomi.domain.tsuzuki.collections.execution.CollectionQueryProviderRegistry

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CollectionQueryProviderRegistryImpl(
    providers: Set<CollectionQueryProvider>,
) : CollectionQueryProviderRegistry {

    private val providerList: List<CollectionQueryProvider> = providers.sortedBy(CollectionQueryProvider::providerId)

    init {
        require(providerList.map(CollectionQueryProvider::providerId).distinct().size == providerList.size) {
            "Duplicate Collection query provider ids are not allowed"
        }
    }

    private val providers: Map<String, CollectionQueryProvider> =
        providerList.associateBy(CollectionQueryProvider::providerId)

    override fun get(providerId: String): CollectionQueryProvider? = providers[providerId]

    override fun all(): List<CollectionQueryProvider> = providerList
}
