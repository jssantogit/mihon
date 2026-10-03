package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import tachiyomi.domain.tsuzuki.collections.execution.CollectionCacheMode
import tachiyomi.domain.tsuzuki.collections.execution.CollectionExecutionCachePolicy
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority
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

            val folderLists = folders
                .take(MAX_PREVIEW_FOLDERS)
                .map { folder ->
                    folder to store.getLists(folder.id)
                }

            sections += HomeSection.CollectionSection(
                collectionId = collection.id,
                title = collection.title,
                previewItems = loadPreview(
                    folderLists = folderLists,
                    pageSize = pageSize,
                ),
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
        return store.observeFolders(collection.id).flatMapLatest { folders ->
            val rootFolders = folders
                .filter { it.parentFolderId == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .take(MAX_PREVIEW_FOLDERS)

            if (rootFolders.isEmpty()) {
                flowOf(
                    HomeSection.CollectionSection(
                        collectionId = collection.id,
                        title = collection.title,
                        previewItems = emptyList(),
                    ),
                )
            } else {
                combine(
                    rootFolders.map { folder ->
                        store.observeLists(folder.id).mapLatest { lists ->
                            folder to lists
                        }
                    },
                ) { folderLists ->
                    HomeSection.CollectionSection(
                        collectionId = collection.id,
                        title = collection.title,
                        previewItems = loadPreview(
                            folderLists = folderLists.toList(),
                            pageSize = pageSize,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadPreview(
        folderLists: List<Pair<CollectionFolder, List<CollectionList>>>,
        pageSize: Int,
    ): List<CatalogItem> {
        val items = mutableListOf<CatalogItem>()
        var attemptedLists = 0

        for ((_, lists) in folderLists) {
            val orderedLists = lists
                .filter(CollectionList::enabled)
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            for (list in orderedLists) {
                if (items.size >= pageSize || attemptedLists >= MAX_PREVIEW_LISTS) break
                attemptedLists += 1

                val remaining = pageSize - items.size
                val content = loader.load(
                    listId = list.id,
                    pageSize = remaining,
                )
                if (content is HomeRowContent.Content) {
                    content.items.forEach { item ->
                        if (items.none { existing ->
                                existing.provider == item.provider &&
                                    existing.providerId == item.providerId
                            }
                        ) {
                            items += item
                        }
                    }
                }
            }

            if (items.size >= pageSize || attemptedLists >= MAX_PREVIEW_LISTS) break
        }

        return items.take(pageSize)
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 4
        const val MAX_PREVIEW_FOLDERS = 4
        const val MAX_PREVIEW_LISTS = 3
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
                    "Sort ${result.sort.cacheKey} is unavailable for this provider",
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
