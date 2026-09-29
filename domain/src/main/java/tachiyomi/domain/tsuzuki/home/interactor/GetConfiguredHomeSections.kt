package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import tachiyomi.domain.tsuzuki.collections.execution.CollectionCacheMode
import tachiyomi.domain.tsuzuki.collections.execution.CollectionExecutionCachePolicy
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
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

        val collections = store.getCollections()
            .filter { it.origin == CollectionOrigin.USER }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))

        val sections = mutableListOf<HomeSection>()
        for (collection in collections) {
            val folders = store.getFolders(collection.id)
                .filter { it.parentFolderId == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            val rows = folders.map { folder ->
                HomeRow(
                    listId = folder.id,
                    title = folder.title,
                    providerId = "",
                    layoutType = null,
                    content = HomeRowContent.Content(emptyList()),
                )
            }

            sections += HomeSection.CollectionSection(
                collectionId = collection.id,
                title = collection.title,
                rows = rows,
            )
        }
        return sections
    }

    fun subscribe(pageSize: Int = DEFAULT_PAGE_SIZE): Flow<List<HomeSection>> {
        require(pageSize > 0) { "Home Collection page size must be positive" }

        return store.observeCollections().flatMapLatest { collections ->
            val userCollections = collections
                .filter { it.origin == CollectionOrigin.USER }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            if (userCollections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    userCollections.map { collection ->
                        observeCollection(collection, pageSize)
                    },
                ) { sections ->
                    sections.toList()
                }
            }
        }
    }

    private fun observeCollection(
        collection: tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection,
        pageSize: Int,
    ): Flow<HomeSection.CollectionSection> {
        return store.observeFolders(collection.id).mapLatest { folders ->
            val rows = folders
                .filter { it.parentFolderId == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .map { folder ->
                    HomeRow(
                        listId = folder.id,
                        title = folder.title,
                        providerId = "",
                        layoutType = null,
                        content = HomeRowContent.Content(emptyList()),
                    )
                }
            HomeSection.CollectionSection(
                collectionId = collection.id,
                title = collection.title,
                rows = rows,
            )
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
