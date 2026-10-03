package eu.kanade.tachiyomi.ui.tsuzuki.home

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.execution.CollectionCacheMode
import tachiyomi.domain.tsuzuki.collections.execution.CollectionExecutionCachePolicy
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.model.HomeCollectionBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderBrowse
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog

@Immutable
sealed interface CollectionBrowseScreenState {
    data object Loading : CollectionBrowseScreenState

    data class Ready(
        val collection: HomeCollectionBrowse,
    ) : CollectionBrowseScreenState

    data object Missing : CollectionBrowseScreenState

    data class Error(
        val message: String,
    ) : CollectionBrowseScreenState
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CollectionBrowseScreenModel(
    private val getConfiguredHomeSections: GetConfiguredHomeSections,
) : ViewModel() {

    private val collectionId = MutableStateFlow<String?>(null)

    val state: StateFlow<CollectionBrowseScreenState> = collectionId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(CollectionBrowseScreenState.Loading)
            } else {
                getConfiguredHomeSections.subscribeCollection(
                    collectionId = id,
                ).map { collection ->
                    if (collection == null) {
                        CollectionBrowseScreenState.Missing
                    } else {
                        CollectionBrowseScreenState.Ready(collection)
                    }
                }
            }
        }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(
                CollectionBrowseScreenState.Error(
                    error.message ?: "Failed to load Collection",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CollectionBrowseScreenState.Loading,
        )

    fun bind(collectionId: String) {
        if (this.collectionId.value != collectionId) {
            this.collectionId.value = collectionId
        }
    }
}

@Immutable
sealed interface FolderCatalogContent {
    data object Idle : FolderCatalogContent
    data object Loading : FolderCatalogContent

    data class Content(
        val items: List<CatalogItem>,
        val nextCursor: ResidualPageCursor?,
    ) : FolderCatalogContent

    data class Error(
        val message: String,
    ) : FolderCatalogContent
}

@Immutable
sealed interface FolderCatalogScreenState {
    data object Loading : FolderCatalogScreenState

    data class Ready(
        val folder: HomeFolderBrowse,
        val selectedListId: String?,
        val content: FolderCatalogContent,
    ) : FolderCatalogScreenState

    data object Missing : FolderCatalogScreenState

    data class Error(
        val message: String,
    ) : FolderCatalogScreenState
}

sealed interface FolderCatalogEvent {
    data class OpenCanonicalTitle(
        val canonicalTitleId: String,
    ) : FolderCatalogEvent
}

private data class FolderBrowseRequest(
    val collectionId: String,
    val folderId: String,
)

private sealed interface FolderBrowseProjection {
    data object Loading : FolderBrowseProjection
    data object Missing : FolderBrowseProjection

    data class Ready(
        val folder: HomeFolderBrowse,
    ) : FolderBrowseProjection

    data class Error(
        val message: String,
    ) : FolderBrowseProjection
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class FolderCatalogScreenModel(
    private val getConfiguredHomeSections: GetConfiguredHomeSections,
    private val executeCollectionList: ExecuteCollectionList,
    private val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
) : ViewModel() {

    private val request = MutableStateFlow<FolderBrowseRequest?>(null)
    private val selectedListId = MutableStateFlow<String?>(null)
    private val content = MutableStateFlow<FolderCatalogContent>(FolderCatalogContent.Idle)
    private val eventChannel = Channel<FolderCatalogEvent>(Channel.BUFFERED)
    private var listJob: Job? = null

    val events = eventChannel.receiveAsFlow()

    private val projection: StateFlow<FolderBrowseProjection> = request
        .flatMapLatest { request ->
            if (request == null) {
                flowOf(FolderBrowseProjection.Loading)
            } else {
                getConfiguredHomeSections.subscribeFolder(
                    collectionId = request.collectionId,
                    folderId = request.folderId,
                ).map { folder ->
                    if (folder == null) {
                        FolderBrowseProjection.Missing
                    } else {
                        FolderBrowseProjection.Ready(folder)
                    }
                }
            }
        }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(
                FolderBrowseProjection.Error(
                    error.message ?: "Failed to load Folder",
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FolderBrowseProjection.Loading,
        )

    val state: StateFlow<FolderCatalogScreenState> = combine(
        projection,
        selectedListId,
        content,
    ) { projection, selectedListId, content ->
        when (projection) {
            FolderBrowseProjection.Loading -> FolderCatalogScreenState.Loading
            FolderBrowseProjection.Missing -> FolderCatalogScreenState.Missing
            is FolderBrowseProjection.Error -> FolderCatalogScreenState.Error(projection.message)
            is FolderBrowseProjection.Ready -> FolderCatalogScreenState.Ready(
                folder = projection.folder,
                selectedListId = selectedListId,
                content = content,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = FolderCatalogScreenState.Loading,
    )

    init {
        viewModelScope.launch {
            projection.collectLatest { projection ->
                if (projection !is FolderBrowseProjection.Ready) {
                    listJob?.cancel()
                    selectedListId.value = null
                    content.value = FolderCatalogContent.Idle
                    return@collectLatest
                }

                val enabledLists = projection.folder.lists
                    .filter(CollectionList::enabled)
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                val current = selectedListId.value
                val target = current?.takeIf { currentId ->
                    enabledLists.any { it.id == currentId }
                } ?: enabledLists.firstOrNull()?.id

                if (target == null) {
                    listJob?.cancel()
                    selectedListId.value = null
                    content.value = FolderCatalogContent.Idle
                } else if (target != current || content.value is FolderCatalogContent.Idle) {
                    selectedListId.value = target
                    loadList(
                        listId = target,
                        append = false,
                    )
                }
            }
        }
    }

    fun bind(
        collectionId: String,
        folderId: String,
    ) {
        val next = FolderBrowseRequest(collectionId, folderId)
        if (request.value != next) {
            listJob?.cancel()
            selectedListId.value = null
            content.value = FolderCatalogContent.Idle
            request.value = next
        }
    }

    fun selectList(listId: String) {
        val folder = (projection.value as? FolderBrowseProjection.Ready)?.folder ?: return
        if (folder.lists.none { it.id == listId && it.enabled }) return
        if (selectedListId.value == listId && content.value !is FolderCatalogContent.Error) return

        selectedListId.value = listId
        loadList(
            listId = listId,
            append = false,
        )
    }

    fun loadMore() {
        val listId = selectedListId.value ?: return
        val current = content.value as? FolderCatalogContent.Content ?: return
        if (current.nextCursor == null) return
        loadList(
            listId = listId,
            append = true,
        )
    }

    fun retry() {
        val listId = selectedListId.value ?: return
        loadList(
            listId = listId,
            append = false,
        )
    }

    fun openCatalogItem(item: CatalogItem) {
        viewModelScope.launch {
            try {
                val title = materializeCanonicalTitleFromCatalog.execute(item)
                eventChannel.send(FolderCatalogEvent.OpenCanonicalTitle(title.id))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Navigation materialization must fail closed without destabilizing browse.
            }
        }
    }

    private fun loadList(
        listId: String,
        append: Boolean,
    ) {
        listJob?.cancel()

        val existing = content.value as? FolderCatalogContent.Content
        val cursor = if (append) {
            existing?.nextCursor ?: return
        } else {
            ResidualPageCursor()
        }

        if (!append) {
            content.value = FolderCatalogContent.Loading
        }

        val job = viewModelScope.launch {
            try {
                val result = executeCollectionList.execute(
                    ExecuteCollectionListRequest(
                        listId = listId,
                        pageSize = CATALOG_PAGE_SIZE,
                        cursor = cursor,
                        cachePolicy = CollectionExecutionCachePolicy(
                            mode = CollectionCacheMode.CACHE_FIRST,
                        ),
                        priority = QuerySchedulePriority.VISIBLE,
                    ),
                )

                if (selectedListId.value != listId) return@launch

                content.value = when (result) {
                    is ExecuteCollectionListResult.Page -> {
                        val items = if (append) {
                            (existing?.items.orEmpty() + result.page.items)
                                .distinctBy { item -> "${item.provider}:${item.providerId}" }
                        } else {
                            result.page.items
                        }
                        FolderCatalogContent.Content(
                            items = items,
                            nextCursor = result.page.nextCursor,
                        )
                    }
                    is ExecuteCollectionListResult.DefinitionUnavailable ->
                        FolderCatalogContent.Error(result.reason)
                    is ExecuteCollectionListResult.ProviderUnavailable ->
                        FolderCatalogContent.Error(
                            "Provider '${result.providerId}' is unavailable",
                        )
                    is ExecuteCollectionListResult.UnsupportedGlobalSort ->
                        FolderCatalogContent.Error(
                            "This List sort is unavailable for the provider",
                        )
                    is ExecuteCollectionListResult.UnsupportedResidual ->
                        FolderCatalogContent.Error(result.reasons.joinToString(separator = "; "))
                    ExecuteCollectionListResult.CacheMiss ->
                        FolderCatalogContent.Error("No cached data is available")
                    is ExecuteCollectionListResult.ProviderFailure ->
                        FolderCatalogContent.Error(
                            result.cause.message ?: "Provider request failed",
                        )
                    is ExecuteCollectionListResult.PaginationInvariantFailure ->
                        FolderCatalogContent.Error(result.reason)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (selectedListId.value == listId) {
                    content.value = FolderCatalogContent.Error(
                        error.message ?: "Collection List execution failed",
                    )
                }
            }
        }

        listJob = job
        job.invokeOnCompletion {
            if (listJob === job) {
                listJob = null
            }
        }
    }

    private companion object {
        const val PREVIEW_PAGE_SIZE = 4
        const val CATALOG_PAGE_SIZE = 30
    }
}
