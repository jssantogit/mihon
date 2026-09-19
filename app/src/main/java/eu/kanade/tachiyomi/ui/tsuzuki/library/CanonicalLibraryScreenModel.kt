package eu.kanade.tachiyomi.ui.tsuzuki.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.RemoveCanonicalLibraryItem
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Immutable
sealed interface CanonicalLibraryScreenState {
    data object Loading : CanonicalLibraryScreenState
    data class Success(
        val items: List<CanonicalLibraryItem>,
        val mappingsByCanonicalTitleId: Map<String, List<SourceTitleMapping>> = emptyMap(),
    ) : CanonicalLibraryScreenState
}

sealed interface CanonicalLibraryEvent {
    data class OpenReader(val canonicalChapterId: String) : CanonicalLibraryEvent
    data class ResolveReadingSource(
        val canonicalTitleId: String,
        val title: String,
    ) : CanonicalLibraryEvent
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CanonicalLibraryScreenModel(
    private val observeCanonicalLibrary: ObserveCanonicalLibrary,
    private val setCanonicalLibraryStatus: SetCanonicalLibraryStatus,
    private val removeCanonicalLibraryItem: RemoveCanonicalLibraryItem,
    private val migrateMihonLibraryToCanonical: MigrateMihonLibraryToCanonical,
    private val resolveCanonicalReadingStart: CanonicalReadingStartResolver,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository =
        EmptySourceTitleMappingRepository,
) : ViewModel() {

    private val eventChannel = Channel<CanonicalLibraryEvent>()
    val events = eventChannel.receiveAsFlow()

    val state: StateFlow<CanonicalLibraryScreenState> = flow<CanonicalLibraryScreenState> {
        try {
            migrateMihonLibraryToCanonical.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Mihon library migration failed non-blockingly" }
        }

        emitAll(
            observeCanonicalLibrary.subscribe()
                .flatMapLatest { items ->
                    if (items.isEmpty()) {
                        flowOf(CanonicalLibraryScreenState.Success(items))
                    } else {
                        combine(
                            items.map { item ->
                                observeMappings(item.title.id)
                            },
                        ) { mappings ->
                            CanonicalLibraryScreenState.Success(
                                items = items,
                                mappingsByCanonicalTitleId = items.mapIndexed { index, item ->
                                    item.title.id to mappings[index]
                                }.toMap(),
                            )
                        }
                    }
                },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CanonicalLibraryScreenState.Loading,
    )

    fun setStatus(canonicalTitleId: String, status: LibraryStatus) {
        viewModelScope.launch {
            setCanonicalLibraryStatus.execute(canonicalTitleId, status)
        }
    }

    fun removeItem(canonicalTitleId: String) {
        viewModelScope.launch {
            removeCanonicalLibraryItem.execute(canonicalTitleId)
        }
    }

    fun readOrContinue(canonicalTitleId: String, title: String) {
        viewModelScope.launch {
            when (val result = resolveCanonicalReadingStart.execute(canonicalTitleId)) {
                is CanonicalReadingStart.Ready -> {
                    eventChannel.send(CanonicalLibraryEvent.OpenReader(result.canonicalChapterId))
                }
                is CanonicalReadingStart.Unavailable -> {
                    eventChannel.send(
                        CanonicalLibraryEvent.ResolveReadingSource(
                            canonicalTitleId = canonicalTitleId,
                            title = title,
                        ),
                    )
                }
            }
        }
    }

    private fun observeMappings(canonicalTitleId: String) =
        sourceTitleMappingRepository.getByCanonicalTitleIdAsFlow(canonicalTitleId)
            .catch { error ->
                if (error is CancellationException) throw error
                logcat(LogPriority.WARN, error) {
                    "Source mapping observation failed for canonical title $canonicalTitleId"
                }
                emit(emptyList())
            }
}

private object EmptySourceTitleMappingRepository : SourceTitleMappingRepository {
    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> = emptyList()

    override fun getByCanonicalTitleIdAsFlow(
        canonicalTitleId: String,
    ) = flowOf(emptyList<SourceTitleMapping>())

    override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null

    override suspend fun upsert(mapping: SourceTitleMapping) = Unit

    override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
}
