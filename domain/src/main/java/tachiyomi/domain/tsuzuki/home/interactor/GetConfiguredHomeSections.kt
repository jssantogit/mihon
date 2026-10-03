package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
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
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority
import tachiyomi.domain.tsuzuki.home.model.HomeCollectionBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderPreview
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
            val lists = folders.flatMap { folder ->
                store.getLists(folder.id)
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            }
            sections += HomeSection.CollectionSection(
                collectionId = collection.id,
                title = collection.title,
                previewItems = loadPreview(lists, pageSize),
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

    fun subscribeCollection(
        collectionId: String,
        pageSize: Int = DEFAULT_PREVIEW_PAGE_SIZE,
    ): Flow<HomeCollectionBrowse?> {
        require(pageSize > 0) { "Collection preview page size must be positive" }

        return store.observeCollections().flatMapLatest { collections ->
            val collection = collections.firstOrNull {
                it.id == collectionId && it.origin == CollectionOrigin.USER
            } ?: return@flatMapLatest flowOf(null)

            observeCollectionBrowse(collection, pageSize)
        }
    }

    fun subscribeFolder(
        collectionId: String,
        folderId: String,
        pageSize: Int = DEFAULT_PREVIEW_PAGE_SIZE,
    ): Flow<HomeFolderBrowse?> {
        require(pageSize > 0) { "Folder preview page size must be positive" }

        return store.observeFolders(collectionId).flatMapLatest { folders ->
            val folder = folders.firstOrNull { it.id == folderId }
                ?: return@flatMapLatest flowOf(null)
            val children = folders
                .filter { it.parentFolderId == folderId }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            val childPreviews = observeFolderPreviews(children, pageSize)

            combine(
                store.observeLists(folderId),
                childPreviews,
            ) { lists, previews ->
                HomeFolderBrowse(
                    collectionId = collectionId,
                    folderId = folderId,
                    title = folder.title,
                    childFolders = previews,
                    lists = lists.sortedWith(compareBy({ it.sortOrder }, { it.id })),
                )
            }
        }
    }

    private fun observeCollection(
        collection: tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection,
        pageSize: Int,
    ): Flow<HomeSection.CollectionSection> {
        return observeRootFolderLists(collection.id).mapLatest { folderLists ->
            HomeSection.CollectionSection(
                collectionId = collection.id,
                title = collection.title,
                previewItems = loadPreview(
                    lists = folderLists.flatMap { it.second },
                    pageSize = pageSize,
                ),
            )
        }
    }

    private fun observeCollectionBrowse(
        collection: tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection,
        pageSize: Int,
    ): Flow<HomeCollectionBrowse> {
        return observeRootFolderLists(collection.id).mapLatest { folderLists ->
            HomeCollectionBrowse(
                collectionId = collection.id,
                title = collection.title,
                folders = folderLists.map { (folder, lists) ->
                    HomeFolderPreview(
                        folderId = folder.id,
                        title = folder.title,
                        previewItems = loadPreview(lists, pageSize),
                    )
                },
            )
        }
    }

    private fun observeFolderPreviews(
        folders: List<CollectionFolder>,
        pageSize: Int,
    ): Flow<List<HomeFolderPreview>> {
        if (folders.isEmpty()) return flowOf(emptyList())

        return combine(
            folders.map { folder ->
                store.observeLists(folder.id).mapLatest { lists ->
                    HomeFolderPreview(
                        folderId = folder.id,
                        title = folder.title,
                        previewItems = loadPreview(
                            lists = lists.sortedWith(compareBy({ it.sortOrder }, { it.id })),
                            pageSize = pageSize,
                        ),
                    )
                }
            },
        ) { previews ->
            previews.toList()
        }
    }

    private fun observeRootFolderLists(
        collectionId: String,
    ): Flow<List<Pair<CollectionFolder, List<CollectionList>>>> {
        return store.observeFolders(collectionId).flatMapLatest { folders ->
            val roots = folders
                .filter { it.parentFolderId == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            if (roots.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    roots.map { folder ->
                        store.observeLists(folder.id).mapLatest { lists ->
                            folder to lists.sortedWith(compareBy({ it.sortOrder }, { it.id }))
                        }
                    },
                ) { folderLists ->
                    folderLists.toList()
                }
            }
        }
    }

    private suspend fun loadPreview(
        lists: List<CollectionList>,
        pageSize: Int,
    ): List<tachiyomi.domain.tsuzuki.catalog.model.CatalogItem> {
        for (list in lists
            .asSequence()
            .filter(CollectionList::enabled)) {
            val content = try {
                loader.load(list.id, pageSize)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                HomeRowContent.Unavailable("Preview unavailable")
            }
            if (content is HomeRowContent.Content && content.items.isNotEmpty()) {
                return content.items.take(pageSize)
            }
        }
        return emptyList()
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 4
        const val DEFAULT_PREVIEW_PAGE_SIZE = 4
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
                priority = QuerySchedulePriority.BACKGROUND,
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
