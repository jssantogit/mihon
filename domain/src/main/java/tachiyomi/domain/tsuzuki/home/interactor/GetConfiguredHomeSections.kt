package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import tachiyomi.domain.tsuzuki.collections.execution.CollectionCacheMode
import tachiyomi.domain.tsuzuki.collections.execution.CollectionExecutionCachePolicy
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority
import tachiyomi.domain.tsuzuki.home.model.HomeRow
import tachiyomi.domain.tsuzuki.home.model.HomeRowContent
import tachiyomi.domain.tsuzuki.home.model.HomeSection

@Inject
class GetConfiguredHomeSections(
    private val store: CollectionStore,
    private val loader: HomeCollectionListLoader,
) {

    suspend fun execute(pageSize: Int = DEFAULT_PAGE_SIZE): List<HomeSection> {
        require(pageSize > 0) { "Home Collection page size must be positive" }

        return store.getCollections()
            .asSequence()
            .filter { it.origin == CollectionOrigin.USER }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { collection ->
                val lists = store.getFolders(collection.id)
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                    .flatMap { folder ->
                        store.getLists(folder.id)
                            .asSequence()
                            .filter(CollectionList::enabled)
                            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                            .toList()
                    }

                HomeSection.CollectionSection(
                    collectionId = collection.id,
                    title = collection.title,
                    rows = lists.map { list ->
                        HomeRow(
                            listId = list.id,
                            title = list.title,
                            providerId = list.providerId,
                            layoutType = list.layoutType,
                            content = loader.load(
                                listId = list.id,
                                pageSize = pageSize,
                            ),
                        )
                    },
                )
            }
            .toList()
    }

    fun subscribe(pageSize: Int = DEFAULT_PAGE_SIZE): Flow<List<HomeSection>> {
        require(pageSize > 0) { "Home Collection page size must be positive" }
        return store.observeCollections().flatMapLatest {
            flow { emit(execute(pageSize)) }
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 12
    }
}

fun interface HomeCollectionListLoader {
    suspend fun load(
        listId: String,
        pageSize: Int,
    ): HomeRowContent
}

@Inject
@ContributesBinding(AppScope::class)
class DefaultHomeCollectionListLoader(
    private val executeCollectionList: ExecuteCollectionList,
) : HomeCollectionListLoader {

    override suspend fun load(
        listId: String,
        pageSize: Int,
    ): HomeRowContent {
        val result = executeCollectionList.execute(
            ExecuteCollectionListRequest(
                listId = listId,
                pageSize = pageSize,
                cursor = ResidualPageCursor(),
                cachePolicy = CollectionExecutionCachePolicy(
                    mode = CollectionCacheMode.CACHE_FIRST,
                ),
                priority = QuerySchedulePriority.VISIBLE,
            ),
        )

        return when (result) {
            is ExecuteCollectionListResult.Page -> {
                HomeRowContent.Content(result.page.items)
            }
            is ExecuteCollectionListResult.DefinitionUnavailable -> {
                HomeRowContent.Unavailable(result.reason)
            }
            is ExecuteCollectionListResult.ProviderUnavailable -> {
                HomeRowContent.Unavailable(
                    "Provider '${result.providerId}' is unavailable",
                )
            }
            is ExecuteCollectionListResult.UnsupportedGlobalSort -> {
                HomeRowContent.Unavailable(
                    "Sort ${result.sort.name} is unavailable for this provider",
                )
            }
            is ExecuteCollectionListResult.UnsupportedResidual -> {
                HomeRowContent.Unavailable(
                    result.reasons.joinToString(separator = "; "),
                )
            }
            ExecuteCollectionListResult.CacheMiss -> {
                HomeRowContent.Unavailable("No cached data is available")
            }
            is ExecuteCollectionListResult.ProviderFailure -> {
                HomeRowContent.Unavailable(
                    result.cause.message ?: "Provider request failed",
                )
            }
            is ExecuteCollectionListResult.PaginationInvariantFailure -> {
                HomeRowContent.Unavailable(result.reason)
            }
        }
    }
}
