package eu.kanade.tachiyomi.ui.tsuzuki.collections

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.execution.CollectionCacheMode
import tachiyomi.domain.tsuzuki.collections.execution.CollectionExecutionCachePolicy
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
import tachiyomi.domain.tsuzuki.collections.interactor.ExportCollections
import tachiyomi.domain.tsuzuki.collections.interactor.ImportCollections
import tachiyomi.domain.tsuzuki.collections.interactor.ImportCollectionsResult
import tachiyomi.domain.tsuzuki.collections.interactor.ManageCollectionDefinitions
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority

@Immutable
data class CollectionFolderUiModel(
    val folder: CollectionFolder,
    val depth: Int,
    val lists: List<CollectionList>,
)

@Immutable
data class CollectionUiModel(
    val collection: TsuzukiCollection,
    val folders: List<CollectionFolderUiModel>,
)

@Immutable
data class CollectionListDraft(
    val title: String,
    val query: QueryExpression?,
    val sort: CatalogSort,
    val layoutType: String?,
)

sealed interface CollectionsAction {
    data class CreateCollection(val title: String) : CollectionsAction
    data class RenameCollection(val collection: TsuzukiCollection, val title: String) : CollectionsAction
    data class DuplicateCollection(val collectionId: String) : CollectionsAction
    data class DeleteCollection(val collectionId: String) : CollectionsAction
    data class MoveCollection(val collection: TsuzukiCollection, val delta: Long) : CollectionsAction

    data class CreateFolder(
        val collectionId: String,
        val parentFolderId: String?,
        val title: String,
    ) : CollectionsAction

    data class RenameFolder(val folder: CollectionFolder, val title: String) : CollectionsAction
    data class DeleteFolder(val folderId: String) : CollectionsAction
    data class MoveFolder(val folder: CollectionFolder, val delta: Long) : CollectionsAction

    data class CreateList(
        val collectionId: String,
        val folderId: String,
        val draft: CollectionListDraft,
    ) : CollectionsAction

    data class UpdateList(
        val list: CollectionList,
        val draft: CollectionListDraft,
    ) : CollectionsAction

    data class SetListEnabled(val listId: String, val enabled: Boolean) : CollectionsAction
    data class DuplicateList(val listId: String) : CollectionsAction
    data class DeleteList(val listId: String) : CollectionsAction
    data class MoveList(val list: CollectionList, val delta: Long) : CollectionsAction
    data class ListVisibilityChanged(val listId: String, val visible: Boolean) : CollectionsAction
    data class RefreshList(val listId: String) : CollectionsAction
    data class LoadMore(val listId: String) : CollectionsAction
}

@Immutable
sealed interface CollectionListRuntimeState {
    data object Idle : CollectionListRuntimeState
    data object Loading : CollectionListRuntimeState

    data class Content(
        val items: List<CatalogItem>,
        val nextCursor: ResidualPageCursor?,
    ) : CollectionListRuntimeState

    data class Error(
        val message: String,
    ) : CollectionListRuntimeState
}

@Immutable
sealed interface CollectionsTransferState {
    data object Idle : CollectionsTransferState

    data object Working : CollectionsTransferState

    data class ExportReady(
        val json: String,
    ) : CollectionsTransferState

    data class ImportSucceeded(
        val result: ImportCollectionsResult,
    ) : CollectionsTransferState

    data class Error(
        val message: String,
    ) : CollectionsTransferState
}

@Immutable
sealed interface CollectionsScreenState {
    data object Loading : CollectionsScreenState

    data class Ready(
        val collections: List<CollectionUiModel>,
        val transferState: CollectionsTransferState,
        val listRuntimeStates: Map<String, CollectionListRuntimeState>,
    ) : CollectionsScreenState

    data class Error(
        val message: String,
    ) : CollectionsScreenState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CollectionsScreenModel(
    private val store: CollectionStore,
    private val manager: ManageCollectionDefinitions,
    private val exportCollections: ExportCollections,
    private val importCollections: ImportCollections,
    private val executeCollectionList: ExecuteCollectionList,
) : ViewModel() {

    private val transferState = MutableStateFlow<CollectionsTransferState>(CollectionsTransferState.Idle)
    private val listRuntimeStates =
        MutableStateFlow<Map<String, CollectionListRuntimeState>>(emptyMap())
    private val visibleListIds = mutableSetOf<String>()
    private val listJobs = mutableMapOf<String, Job>()

    private val collectionsFlow: Flow<List<CollectionUiModel>> = store.observeCollections()
        .flatMapLatest { collections ->
            if (collections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    collections.map(::observeCollection),
                ) { graphs ->
                    graphs
                        .sortedWith(
                            compareBy<CollectionUiModel> { it.collection.sortOrder }
                                .thenBy { it.collection.id },
                        )
                }
            }
        }

    val state: StateFlow<CollectionsScreenState> = combine(
        collectionsFlow,
        transferState,
        listRuntimeStates,
    ) { collections, transfer, runtimes ->
        CollectionsScreenState.Ready(
            collections = collections,
            transferState = transfer,
            listRuntimeStates = runtimes,
        ) as CollectionsScreenState
    }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(CollectionsScreenState.Error(error.message ?: "Failed to load Collections"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CollectionsScreenState.Loading,
        )

    init {
        viewModelScope.launch {
            try {
                manager.ensureSystemDefinitions()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                transferState.value = CollectionsTransferState.Error(
                    error.message ?: "Failed to initialize built-in Collections",
                )
            }
        }
    }

    fun dispatch(action: CollectionsAction) {
        when (action) {
            is CollectionsAction.CreateCollection -> createCollection(action.title)
            is CollectionsAction.RenameCollection -> renameCollection(action.collection, action.title)
            is CollectionsAction.DuplicateCollection -> duplicateCollection(action.collectionId)
            is CollectionsAction.DeleteCollection -> deleteCollection(action.collectionId)
            is CollectionsAction.MoveCollection -> moveCollection(action.collection, action.delta)
            is CollectionsAction.CreateFolder -> createFolder(
                collectionId = action.collectionId,
                parentFolderId = action.parentFolderId,
                title = action.title,
            )
            is CollectionsAction.RenameFolder -> renameFolder(action.folder, action.title)
            is CollectionsAction.DeleteFolder -> deleteFolder(action.folderId)
            is CollectionsAction.MoveFolder -> moveFolder(action.folder, action.delta)
            is CollectionsAction.CreateList -> createList(
                collectionId = action.collectionId,
                folderId = action.folderId,
                title = action.draft.title,
                query = action.draft.query,
                sort = action.draft.sort,
                layoutType = action.draft.layoutType,
            )
            is CollectionsAction.UpdateList -> updateList(
                list = action.list,
                title = action.draft.title,
                query = action.draft.query,
                sort = action.draft.sort,
                layoutType = action.draft.layoutType,
            )
            is CollectionsAction.SetListEnabled -> setListEnabled(action.listId, action.enabled)
            is CollectionsAction.DuplicateList -> duplicateList(action.listId)
            is CollectionsAction.DeleteList -> deleteList(action.listId)
            is CollectionsAction.MoveList -> moveList(action.list, action.delta)
            is CollectionsAction.ListVisibilityChanged -> setListVisible(action.listId, action.visible)
            is CollectionsAction.RefreshList -> refreshList(action.listId)
            is CollectionsAction.LoadMore -> loadMore(action.listId)
        }
    }

    fun createCollection(title: String) = launchAction {
        val nextOrder = currentCollections()
            .maxOfOrNull { it.collection.sortOrder }
            ?.plus(1)
            ?: 0
        manager.createUserCollection(
            title = title,
            sortOrder = nextOrder,
        )
    }

    fun renameCollection(
        collection: TsuzukiCollection,
        title: String,
    ) = launchAction {
        manager.updateUserCollection(collection.copy(title = title))
    }

    fun duplicateCollection(collectionId: String) = launchAction {
        manager.duplicateCollection(collectionId)
    }

    fun deleteCollection(collectionId: String) = launchAction {
        manager.tombstoneCollection(collectionId)
    }

    fun moveCollection(
        collection: TsuzukiCollection,
        delta: Long,
    ) = launchAction {
        val ordered = currentCollections().map { it.collection }
        val index = ordered.indexOfFirst { it.id == collection.id }
        val neighborIndex = neighborIndex(index, ordered.size, delta) ?: return@launchAction
        val neighbor = ordered[neighborIndex]

        manager.reorderCollection(
            collectionId = neighbor.id,
            sortOrder = collection.sortOrder,
        )
        manager.reorderCollection(
            collectionId = collection.id,
            sortOrder = neighbor.sortOrder,
        )
    }

    fun createFolder(
        collectionId: String,
        parentFolderId: String?,
        title: String,
    ) = launchAction {
        val collection = currentCollections().first { it.collection.id == collectionId }
        val siblingMax = collection.folders
            .filter { it.folder.parentFolderId == parentFolderId }
            .maxOfOrNull { it.folder.sortOrder }
            ?: -1

        manager.createUserFolder(
            collectionId = collectionId,
            title = title,
            sortOrder = siblingMax + 1,
            parentFolderId = parentFolderId,
        )
    }

    fun renameFolder(
        folder: CollectionFolder,
        title: String,
    ) = launchAction {
        manager.updateUserFolder(folder.copy(title = title))
    }

    fun deleteFolder(folderId: String) = launchAction {
        manager.tombstoneFolder(folderId)
    }

    fun moveFolder(
        folder: CollectionFolder,
        delta: Long,
    ) = launchAction {
        val siblings = currentCollections()
            .first { it.collection.id == folder.collectionId }
            .folders
            .map { it.folder }
            .filter { it.parentFolderId == folder.parentFolderId }
            .sortedWith(compareBy<CollectionFolder> { it.sortOrder }.thenBy { it.id })

        val index = siblings.indexOfFirst { it.id == folder.id }
        val neighborIndex = neighborIndex(index, siblings.size, delta) ?: return@launchAction
        val neighbor = siblings[neighborIndex]

        manager.reorderFolder(
            folderId = neighbor.id,
            sortOrder = folder.sortOrder,
        )
        manager.reorderFolder(
            folderId = folder.id,
            sortOrder = neighbor.sortOrder,
        )
    }

    fun createList(
        collectionId: String,
        folderId: String,
        title: String,
        query: QueryExpression?,
        sort: CatalogSort,
        layoutType: String?,
    ) = launchAction {
        val folder = currentCollections()
            .flatMap { it.folders }
            .first { it.folder.id == folderId }
        val nextOrder = folder.lists.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0

        manager.createUserList(
            collectionId = collectionId,
            folderId = folderId,
            title = title,
            providerId = "kitsu",
            query = query,
            sort = sort,
            sortOrder = nextOrder,
            layoutType = layoutType,
        )
    }

    fun updateList(
        list: CollectionList,
        title: String,
        query: QueryExpression?,
        sort: CatalogSort,
        layoutType: String?,
    ) = launchAction {
        manager.updateUserList(
            list.copy(
                title = title,
                query = query,
                sort = sort,
                layoutType = layoutType,
            ),
        )
        invalidateList(list.id)
    }

    fun setListEnabled(
        listId: String,
        enabled: Boolean,
    ) = launchAction {
        manager.setListEnabled(listId, enabled)
        if (enabled) {
            invalidateList(listId)
        } else {
            listJobs.remove(listId)?.cancel()
            updateRuntime(listId, CollectionListRuntimeState.Idle)
        }
    }

    fun duplicateList(listId: String) = launchAction {
        manager.duplicateList(listId)
    }

    fun deleteList(listId: String) = launchAction {
        listJobs.remove(listId)?.cancel()
        visibleListIds.remove(listId)
        manager.tombstoneList(listId)
        updateRuntime(listId, CollectionListRuntimeState.Idle)
    }

    fun moveList(
        list: CollectionList,
        delta: Long,
    ) = launchAction {
        val siblings = currentCollections()
            .flatMap { it.folders }
            .first { it.folder.id == list.folderId }
            .lists
            .sortedWith(compareBy<CollectionList> { it.sortOrder }.thenBy { it.id })

        val index = siblings.indexOfFirst { it.id == list.id }
        val neighborIndex = neighborIndex(index, siblings.size, delta) ?: return@launchAction
        val neighbor = siblings[neighborIndex]

        manager.reorderList(
            listId = neighbor.id,
            sortOrder = list.sortOrder,
        )
        manager.reorderList(
            listId = list.id,
            sortOrder = neighbor.sortOrder,
        )
    }

    fun setListVisible(
        listId: String,
        visible: Boolean,
    ) {
        if (visible) {
            visibleListIds += listId
            val runtime = listRuntimeStates.value[listId] ?: CollectionListRuntimeState.Idle
            if (runtime is CollectionListRuntimeState.Idle && isListEnabled(listId)) {
                loadList(listId, append = false, forceNetwork = false)
            }
        } else {
            visibleListIds -= listId
            listJobs.remove(listId)?.cancel()
            if (listRuntimeStates.value[listId] is CollectionListRuntimeState.Loading) {
                updateRuntime(listId, CollectionListRuntimeState.Idle)
            }
        }
    }

    fun refreshList(listId: String) {
        if (!isListEnabled(listId)) return
        loadList(listId, append = false, forceNetwork = true)
    }

    fun loadMore(listId: String) {
        val current = listRuntimeStates.value[listId] as? CollectionListRuntimeState.Content ?: return
        if (current.nextCursor == null || !isListEnabled(listId)) return
        loadList(listId, append = true, forceNetwork = false)
    }

    private fun invalidateList(listId: String) {
        listJobs.remove(listId)?.cancel()
        updateRuntime(listId, CollectionListRuntimeState.Idle)
        if (listId in visibleListIds && isListEnabled(listId)) {
            loadList(listId, append = false, forceNetwork = false)
        }
    }

    private fun loadList(
        listId: String,
        append: Boolean,
        forceNetwork: Boolean,
    ) {
        listJobs.remove(listId)?.cancel()

        val existing = listRuntimeStates.value[listId] as? CollectionListRuntimeState.Content
        val cursor = if (append) {
            existing?.nextCursor ?: return
        } else {
            ResidualPageCursor()
        }

        if (!append) {
            updateRuntime(listId, CollectionListRuntimeState.Loading)
        }

        val job = viewModelScope.launch {
            try {
                val result = executeCollectionList.execute(
                    ExecuteCollectionListRequest(
                        listId = listId,
                        pageSize = LIST_PAGE_SIZE,
                        cursor = cursor,
                        cachePolicy = CollectionExecutionCachePolicy(
                            mode = if (forceNetwork) {
                                CollectionCacheMode.NETWORK_ONLY
                            } else {
                                CollectionCacheMode.CACHE_FIRST
                            },
                        ),
                        priority = QuerySchedulePriority.VISIBLE,
                    ),
                )

                val runtime = when (result) {
                    is ExecuteCollectionListResult.Page -> {
                        val items = if (append) {
                            (existing?.items.orEmpty() + result.page.items)
                                .distinctBy { "${it.provider}:${it.providerId}" }
                        } else {
                            result.page.items
                        }
                        CollectionListRuntimeState.Content(
                            items = items,
                            nextCursor = result.page.nextCursor,
                        )
                    }

                    is ExecuteCollectionListResult.DefinitionUnavailable -> {
                        CollectionListRuntimeState.Error(result.reason)
                    }

                    is ExecuteCollectionListResult.ProviderUnavailable -> {
                        CollectionListRuntimeState.Error(
                            "Provider '${result.providerId}' is unavailable",
                        )
                    }

                    is ExecuteCollectionListResult.UnsupportedGlobalSort -> {
                        CollectionListRuntimeState.Error(
                            "Sort ${result.sort.name} is not supported globally by this provider",
                        )
                    }

                    is ExecuteCollectionListResult.UnsupportedResidual -> {
                        CollectionListRuntimeState.Error(
                            result.reasons.joinToString(separator = "; "),
                        )
                    }

                    ExecuteCollectionListResult.CacheMiss -> {
                        CollectionListRuntimeState.Error("No cached data is available")
                    }

                    is ExecuteCollectionListResult.ProviderFailure -> {
                        CollectionListRuntimeState.Error(
                            result.cause.message ?: "Provider request failed",
                        )
                    }

                    is ExecuteCollectionListResult.PaginationInvariantFailure -> {
                        CollectionListRuntimeState.Error(result.reason)
                    }
                }
                updateRuntime(listId, runtime)
            } catch (error: CancellationException) {
                if (listId !in visibleListIds) {
                    updateRuntime(
                        listId,
                        existing ?: CollectionListRuntimeState.Idle,
                    )
                }
                throw error
            } catch (error: Throwable) {
                updateRuntime(
                    listId,
                    CollectionListRuntimeState.Error(
                        error.message ?: "Collection List execution failed",
                    ),
                )
            }
        }

        listJobs[listId] = job
        job.invokeOnCompletion {
            if (listJobs[listId] === job) {
                listJobs.remove(listId)
            }
        }
    }

    private fun updateRuntime(
        listId: String,
        runtime: CollectionListRuntimeState,
    ) {
        listRuntimeStates.value = listRuntimeStates.value.toMutableMap().apply {
            put(listId, runtime)
        }
    }

    private fun isListEnabled(listId: String): Boolean {
        return currentCollections()
            .asSequence()
            .flatMap { it.folders.asSequence() }
            .flatMap { it.lists.asSequence() }
            .firstOrNull { it.id == listId }
            ?.enabled == true
    }

    fun prepareExport() {
        transferState.value = CollectionsTransferState.Working
        viewModelScope.launch {
            try {
                transferState.value = CollectionsTransferState.ExportReady(
                    json = exportCollections.execute(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                transferState.value = CollectionsTransferState.Error(
                    error.message ?: "Failed to export Collections",
                )
            }
        }
    }

    fun importJson(json: String) {
        transferState.value = CollectionsTransferState.Working
        viewModelScope.launch {
            try {
                transferState.value = CollectionsTransferState.ImportSucceeded(
                    importCollections.execute(json),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                transferState.value = CollectionsTransferState.Error(
                    error.message ?: "Failed to import Collections",
                )
            }
        }
    }

    fun clearTransferState() {
        transferState.value = CollectionsTransferState.Idle
    }

    fun reportTransferError(message: String) {
        transferState.value = CollectionsTransferState.Error(message)
    }

    private fun observeCollection(collection: TsuzukiCollection): Flow<CollectionUiModel> {
        return store.observeFolders(collection.id)
            .flatMapLatest { folders ->
                if (folders.isEmpty()) {
                    flowOf(CollectionUiModel(collection, emptyList()))
                } else {
                    combine(
                        folders.map { folder ->
                            store.observeLists(folder.id).map { lists ->
                                folder to lists
                            }
                        },
                    ) { folderLists ->
                        CollectionUiModel(
                            collection = collection,
                            folders = flattenFolders(folderLists.toList()),
                        )
                    }
                }
            }
    }

    private fun flattenFolders(
        folderLists: List<Pair<CollectionFolder, List<CollectionList>>>,
    ): List<CollectionFolderUiModel> {
        val byParent = folderLists.groupBy { it.first.parentFolderId }
        val result = mutableListOf<CollectionFolderUiModel>()

        fun append(parentId: String?, depth: Int) {
            byParent[parentId]
                .orEmpty()
                .sortedWith(
                    compareBy<Pair<CollectionFolder, List<CollectionList>>> { it.first.sortOrder }
                        .thenBy { it.first.id },
                )
                .forEach { (folder, lists) ->
                    result += CollectionFolderUiModel(
                        folder = folder,
                        depth = depth,
                        lists = lists.sortedWith(
                            compareBy<CollectionList> { it.sortOrder }.thenBy { it.id },
                        ),
                    )
                    append(folder.id, depth + 1)
                }
        }

        append(parentId = null, depth = 0)
        return result
    }

    private fun currentCollections(): List<CollectionUiModel> {
        return (state.value as? CollectionsScreenState.Ready)?.collections.orEmpty()
    }

    private fun neighborIndex(
        currentIndex: Int,
        size: Int,
        delta: Long,
    ): Int? {
        if (currentIndex < 0 || delta == 0L) return null
        val candidate = if (delta < 0) currentIndex - 1 else currentIndex + 1
        return candidate.takeIf { it in 0 until size }
    }

    private fun launchAction(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            transferState.value = CollectionsTransferState.Error(
                error.message ?: "Collection action failed",
            )
        }
    }

    private companion object {
        const val LIST_PAGE_SIZE = 6
    }
}
