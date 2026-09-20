package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackCanonicalChapter
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.interactor.RepairZeroPlaceholderChapterSemantics
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.reader.interactor.GetAdjacentCanonicalChapter
import tachiyomi.domain.tsuzuki.reader.interactor.PrepareCanonicalChapterForReader
import tachiyomi.domain.tsuzuki.reader.interactor.RecordCanonicalReaderProgress
import tachiyomi.domain.tsuzuki.reader.interactor.SetCanonicalAutomaticFallback
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterDirection
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.util.Date
import kotlin.time.Clock

/**
 * Presenter used by the activity to perform background operations.
 */
@AssistedInject
class ReaderViewModel(
    @Assisted private val savedState: SavedStateHandle,
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val imageSaver: ImageSaver,
    val readerPreferences: ReaderPreferences,
    private val basePreferences: BasePreferences,
    private val downloadPreferences: DownloadPreferences,
    private val trackPreferences: TrackPreferences,
    private val trackChapter: TrackChapter,
    private val trackCanonicalChapter: TrackCanonicalChapter,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getNextChapters: GetNextChapters,
    private val upsertHistory: UpsertHistory,
    private val updateChapter: UpdateChapter,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val refreshCanonicalChapters: RefreshCanonicalChapters,
    private val repairZeroPlaceholderChapterSemantics: RepairZeroPlaceholderChapterSemantics,
    private val prepareCanonicalChapterForReader: PrepareCanonicalChapterForReader,
    private val recordCanonicalReaderProgress: RecordCanonicalReaderProgress,
    private val getAdjacentCanonicalChapter: GetAdjacentCanonicalChapter,
    private val setCanonicalAutomaticFallback: SetCanonicalAutomaticFallback,
    private val setMangaViewerFlags: SetMangaViewerFlags,
    private val getIncognitoState: GetIncognitoState,
    private val libraryPreferences: LibraryPreferences,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val coverCache: CoverCache,
    private val chapterCache: ChapterCache,
    private val downloadCache: DownloadCache,
) : ViewModel() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(ReaderViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): ReaderViewModel {
            return create(extras.createSavedStateHandle())
        }

        fun create(@Assisted savedState: SavedStateHandle): ReaderViewModel
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /**
     * Legacy Reader coordinates remain supported. Canonical launches instead
     * resolve these coordinates lazily through the Tsuzuki compatibility bridge.
     */
    private val canonicalChapterId = savedState.get<String>("canonical_chapter")
    private val canonicalPreferredLanguage = savedState.get<String>("canonical_language")

    var mangaId = savedState.get<Long>("manga") ?: -1L
        private set(value) {
            savedState["manga"] = value
            field = value
        }

    private var initialChapterId = savedState.get<Long>("chapter") ?: -1L
        set(value) {
            savedState["chapter"] = value
            field = value
        }

    val hasValidArgs: Boolean
        get() = canonicalChapterId != null || (mangaId != -1L && initialChapterId != -1L)

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The source of the manga loaded in the reader. Null until it has been resolved.
     */
    val source: Source?
        get() = state.value.source

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /** Canonical identity attached to the operational Reader chapter for this session. */
    private var canonicalSession: OperationalReaderChapter? = null
    private var pendingCanonicalFallback: CanonicalReaderPreparation.FallbackRequired? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga, downloadCache)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private var incognitoMode: Boolean = false
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val canonicalId = canonicalChapterId
                if (canonicalId != null) {
                    when (
                        val preparation = prepareCanonicalChapterForReader.execute(
                            canonicalChapterId = canonicalId,
                            preferredLanguage = canonicalPreferredLanguage,
                        )
                    ) {
                        is CanonicalReaderPreparation.Ready -> {
                            loadCanonicalTarget(preparation.target, resetPage = false)
                        }
                        is CanonicalReaderPreparation.FallbackRequired -> {
                            showCanonicalFallback(preparation)
                        }
                        is CanonicalReaderPreparation.Unavailable -> {
                            error("No readable variant is available for canonical chapter $canonicalId")
                        }
                        is CanonicalReaderPreparation.Failed -> throw preparation.error
                    }
                } else {
                    loadLegacyInitialChapter()
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    private suspend fun loadLegacyInitialChapter() {
        val manga = getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
        val source = sourceManager.getOrStub(manga.source)
        incognitoMode = getIncognitoState.await(manga.source)
        mutableState.update { it.copy(manga = manga, source = source) }
        if (chapterId == -1L) chapterId = initialChapterId

        val initial = chapterList.first { chapterId == it.chapter.id }
        attachCanonicalSessionForLegacy(manga, source, initial)

        loader = ChapterLoader(context, downloadManager, downloadProvider, chapterCache, manga, source)
        loadChapter(loader!!, initial)
    }

    private suspend fun attachCanonicalSessionForLegacy(
        manga: Manga,
        source: Source,
        readerChapter: ReaderChapter,
    ) {
        try {
            var mapping = sourceTitleMappingRepository.getBySource(
                sourceId = manga.source,
                sourceUrl = manga.url,
            ) ?: return

            val sourceAvailable = source !is StubSource
            if (
                mapping.mihonMangaId != manga.id ||
                (sourceAvailable && mapping.availability == SourceMappingAvailability.UNAVAILABLE)
            ) {
                mapping = mapping.copy(
                    mihonMangaId = manga.id,
                    availability = if (sourceAvailable) {
                        SourceMappingAvailability.AVAILABLE
                    } else {
                        mapping.availability
                    },
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                )
                sourceTitleMappingRepository.upsert(mapping)
            }

            try {
                repairZeroPlaceholderChapterSemantics.execute(mapping.canonicalTitleId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) {
                    "Failed to repair persisted canonical chapter semantics"
                }
            }

            var variant = canonicalChapterRepository
                .getVariantBySourceIdentity(
                    sourceId = manga.source,
                    sourceChapterId = readerChapter.chapter.url,
                )
                ?.takeIf { it.sourceMappingId == mapping.id }

            if (variant == null && sourceAvailable) {
                refreshCanonicalChapters.execute(
                    canonicalTitleId = mapping.canonicalTitleId,
                    mappingId = mapping.id,
                ).getOrThrow()
                variant = canonicalChapterRepository
                    .getVariantBySourceIdentity(
                        sourceId = manga.source,
                        sourceChapterId = readerChapter.chapter.url,
                    )
                    ?.takeIf { it.sourceMappingId == mapping.id }
            }

            if (variant != null) {
                canonicalSession = OperationalReaderChapter(
                    canonicalChapterId = variant.canonicalChapterId,
                    variantId = variant.id,
                    sourceMappingId = mapping.id,
                    mihonMangaId = manga.id,
                    mihonChapterId = readerChapter.chapter.id!!,
                    sourceId = manga.source,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) {
                "Failed to attach legacy Reader session to canonical progress"
            }
        }
    }

    private suspend fun loadCanonicalTarget(
        target: OperationalReaderChapter,
        resetPage: Boolean,
    ) {
        canonicalSession = target
        pendingCanonicalFallback = null
        mangaId = target.mihonMangaId
        initialChapterId = target.mihonChapterId
        chapterId = target.mihonChapterId
        if (resetPage) {
            chapterPageIndex = -1
        }

        val manga = getManga.await(target.mihonMangaId)
            ?: error("Requested manga of id ${target.mihonMangaId} not found")
        val source = sourceManager.getOrStub(manga.source)
        incognitoMode = getIncognitoState.await(manga.source)
        val canonicalLoader = ChapterLoader(
            context,
            downloadManager,
            downloadProvider,
            chapterCache,
            manga,
            source,
        )
        loader = canonicalLoader

        val chapter = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false)
            .firstOrNull { it.id == target.mihonChapterId }
            ?: error("Operational chapter ${target.mihonChapterId} not found")

        mutableState.update {
            it.copy(
                manga = manga,
                source = source,
                dialog = null,
            )
        }
        loadChapter(canonicalLoader, ReaderChapter(chapter.toDbChapter()))
    }

    private suspend fun showCanonicalFallback(
        fallback: CanonicalReaderPreparation.FallbackRequired,
    ) {
        pendingCanonicalFallback = fallback
        withUIContext {
            mutableState.update { it.copy(dialog = Dialog.CanonicalFallback(fallback)) }
        }
    }

    fun confirmCanonicalFallback(always: Boolean) {
        val fallback = pendingCanonicalFallback ?: return
        val hadActiveSession = canonicalSession != null
        viewModelScope.launchIO {
            try {
                if (always) {
                    setCanonicalAutomaticFallback.execute(
                        canonicalTitleId = fallback.canonicalTitleId,
                        enabled = true,
                    )
                }
                when (
                    val preparation = prepareCanonicalChapterForReader.execute(
                        canonicalChapterId = fallback.canonicalChapterId,
                        preferredLanguage = canonicalPreferredLanguage,
                        allowFallbackOnce = true,
                    )
                ) {
                    is CanonicalReaderPreparation.Ready -> {
                        if (hadActiveSession) {
                            updateHistory()
                        }
                        loadCanonicalTarget(preparation.target, resetPage = hadActiveSession)
                        restartReadTimer()
                    }
                    is CanonicalReaderPreparation.FallbackRequired -> showCanonicalFallback(preparation)
                    is CanonicalReaderPreparation.Unavailable -> {
                        logcat(LogPriority.WARN) { "Fallback chapter is no longer available" }
                    }
                    is CanonicalReaderPreparation.Failed -> throw preparation.error
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logcat(LogPriority.ERROR, error) { "Failed to prepare canonical fallback" }
                if (!hadActiveSession) {
                    mutableState.update { it.copy(initError = error) }
                }
            }
        }
    }

    fun cancelCanonicalFallback() {
        pendingCanonicalFallback = null
        mutableState.update { it.copy(dialog = null) }
        if (canonicalSession == null) {
            eventChannel.trySend(Event.CloseReader)
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        val session = canonicalSession
        val canonicalPrevious = session?.let {
            getAdjacentCanonicalChapter.execute(
                canonicalChapterId = it.canonicalChapterId,
                direction = CanonicalChapterDirection.PREVIOUS,
            )
        }
        val canonicalNext = session?.let {
            getAdjacentCanonicalChapter.execute(
                canonicalChapterId = it.canonicalChapterId,
                direction = CanonicalChapterDirection.NEXT,
            )
        }
        val newChapters = if (session != null) {
            // Source-specific adjacent rows are not canonical navigation.
            ViewerChapters(chapter, null, null)
        } else {
            val chapterPos = chapterList.indexOf(chapter)
            ViewerChapters(
                chapter,
                chapterList.getOrNull(chapterPos - 1),
                chapterList.getOrNull(chapterPos + 1),
            )
        }

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                    canonicalCanNavigatePrevious = canonicalPrevious != null,
                    canonicalCanNavigateNext = canonicalNext != null,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val source = state.value.source ?: return
            val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                source,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        if (canonicalSessionFor(currentChapter) != null) {
            chapterToDownload = null
            return
        }
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex) {
                updateChapterProgressOnComplete(readerChapter)
            }

            val canonicalSession = canonicalSessionFor(readerChapter)
            if (canonicalSession != null) {
                try {
                    recordCanonicalReaderProgress.recordPage(
                        canonicalChapterId = canonicalSession.canonicalChapterId,
                        variantId = canonicalSession.variantId,
                        pageIndex = pageIndex,
                        completed = readerChapter.pages?.lastIndex == pageIndex,
                        mihonChapterId = readerChapter.chapter.id!!,
                    )
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Failed to persist canonical reader progress" }
                }
            } else {
                updateChapter.await(
                    ChapterUpdate(
                        id = readerChapter.chapter.id!!,
                        read = readerChapter.chapter.read,
                        lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                    ),
                )
            }
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        val canonical = canonicalSessionFor(readerChapter)
        if (canonical == null) {
            updateTrackChapterRead(readerChapter)
        } else {
            updateCanonicalTrackChapterRead(canonical)
        }
        deleteChapterIfNeeded(readerChapter)

        // Canonical progress owns cross-variant read state. Legacy duplicate
        // marking would incorrectly make one source's numbering authoritative.
        if (canonical != null) return

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            val canonicalSession = canonicalSessionFor(readerChapter)
            if (canonicalSession != null) {
                try {
                    recordCanonicalReaderProgress.recordHistory(
                        canonicalChapterId = canonicalSession.canonicalChapterId,
                        variantId = canonicalSession.variantId,
                        sessionReadDuration = sessionReadDuration,
                        mihonChapterId = chapterId,
                    )
                } catch (error: Throwable) {
                    logcat(LogPriority.ERROR, error) { "Failed to persist canonical reader history" }
                }
            } else {
                upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            }
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        if (canonicalSession != null) {
            loadAdjacentCanonical(CanonicalChapterDirection.NEXT)
            return
        }
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        if (canonicalSession != null) {
            loadAdjacentCanonical(CanonicalChapterDirection.PREVIOUS)
            return
        }
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    private suspend fun loadAdjacentCanonical(direction: CanonicalChapterDirection) {
        val session = canonicalSession ?: return
        val adjacent = getAdjacentCanonicalChapter.execute(
            canonicalChapterId = session.canonicalChapterId,
            direction = direction,
        ) ?: return

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            when (
                val preparation = prepareCanonicalChapterForReader.execute(
                    canonicalChapterId = adjacent.id,
                    preferredLanguage = canonicalPreferredLanguage,
                )
            ) {
                is CanonicalReaderPreparation.Ready -> {
                    updateHistory()
                    loadCanonicalTarget(preparation.target, resetPage = true)
                    restartReadTimer()
                }
                is CanonicalReaderPreparation.FallbackRequired -> showCanonicalFallback(preparation)
                is CanonicalReaderPreparation.Unavailable -> {
                    logcat(LogPriority.WARN) { "Canonical adjacent chapter ${adjacent.id} has no readable variant" }
                }
                is CanonicalReaderPreparation.Failed -> throw preparation.error
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logcat(LogPriority.ERROR, error) { "Failed to load canonical adjacent chapter" }
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    private fun canonicalSessionFor(readerChapter: ReaderChapter): OperationalReaderChapter? {
        val session = canonicalSession ?: return null
        return session.takeIf { it.mihonChapterId == readerChapter.chapter.id }
    }

    fun getSource() = state.value.source as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(coverManager, stream(), updateManga, coverCache)
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    private fun updateCanonicalTrackChapterRead(session: OperationalReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        viewModelScope.launchNonCancellable {
            trackCanonicalChapter.await(
                context = context,
                canonicalChapterId = session.canonicalChapterId,
            )
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val source: Source? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val canonicalCanNavigatePrevious: Boolean = false,
        val canonicalCanNavigateNext: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
        data class CanonicalFallback(
            val fallback: CanonicalReaderPreparation.FallbackRequired,
        ) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data object CloseReader : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}
