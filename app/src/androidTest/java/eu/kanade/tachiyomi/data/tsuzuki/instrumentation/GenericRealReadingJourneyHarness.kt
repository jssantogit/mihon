package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.MihonCanonicalReaderGateway
import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterContentPreparer
import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterInventoryGateway
import eu.kanade.tachiyomi.data.tsuzuki.MihonReadingSourceGateway
import eu.kanade.tachiyomi.data.tsuzuki.addon.DefaultAddonRegistry
import eu.kanade.tachiyomi.data.tsuzuki.addon.LocalContentProvider
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonAddonProviderFactory
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonAddonSourceEligibilityRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentBindingRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentPreferenceRepositoryImpl
import tachiyomi.data.tsuzuki.download.CanonicalDownloadRepositoryImpl
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.interactor.PrepareCanonicalChapterForReader
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.io.File
import java.util.UUID

internal class MihonJourneyDisposableDatabaseContext(
    private val targetContext: Context,
    private val disposableDatabaseName: String,
    private val logicalDatabaseName: String = "tachiyomi.db",
) : ContextWrapper(targetContext) {
    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File {
        check(name == logicalDatabaseName) {
            "Unexpected database name requested by test composition"
        }
        return targetContext.getDatabasePath(disposableDatabaseName)
    }

    override fun deleteDatabase(name: String): Boolean {
        check(name == logicalDatabaseName) {
            "Unexpected database name deleted by test composition"
        }
        return targetContext.deleteDatabase(disposableDatabaseName)
    }
}

internal class RecordingDiagnostics : ChapterInventoryDiagnostics {
    private var activeTitleId: String? = null
    private val collected = mutableListOf<ChapterInventoryDiagnosticEvent>()

    @Synchronized
    override fun start(canonicalTitleId: String): String {
        activeTitleId = canonicalTitleId
        collected.clear()
        return UUID.randomUUID().toString()
    }

    @Synchronized
    override fun stop() {
        activeTitleId = null
    }

    @Synchronized
    override fun clear() {
        activeTitleId = null
        collected.clear()
    }

    @Synchronized
    override fun isRecording(canonicalTitleId: String): Boolean = activeTitleId == canonicalTitleId

    @Synchronized
    override fun record(event: ChapterInventoryDiagnosticEvent) {
        if (activeTitleId != null) collected += event
    }

    @Synchronized
    override fun report(): String = ""

    @Synchronized
    fun events(): List<ChapterInventoryDiagnosticEvent> = collected.toList()
}

internal class ProductionMihonJourneyComposition(
    private val app: App,
    database: Database,
    sourceManager: SourceManager,
    addonId: AddonId,
    onMaterialization: (Result<MaterializedReadingSource>, Long) -> Unit = { _, _ -> },
) {
    private val mangaRepository: MangaRepository = MangaRepositoryImpl(database)
    val chapterRepository: ChapterRepository = ChapterRepositoryImpl(database)
    val canonicalTitleRepository: CanonicalTitleRepository = CanonicalTitleRepositoryImpl(database)
    val contentBindingRepository: ContentBindingRepository = ContentBindingRepositoryImpl(database)
    val canonicalChapterRepository: CanonicalChapterRepository = CanonicalChapterRepositoryImpl(database)
    val chapterEvidenceRepository: ChapterEvidenceRepository = ChapterEvidenceRepositoryImpl(database)
    private val contentPreferenceRepository: ContentPreferenceRepository = ContentPreferenceRepositoryImpl(database)
    val diagnostics = RecordingDiagnostics()
    private val observedSearches = mutableListOf<MihonJourneySearchObservation>()
    val searchObservations: List<MihonJourneySearchObservation>
        get() = synchronized(observedSearches) { observedSearches.toList() }
    var materializationObservation: MihonJourneyMaterializationObservation? = null
        private set
    val chapterLabelParser = ParseCanonicalChapterLabel()
    private val chapterInventoryGateway = MihonChapterInventoryGateway(
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        sourceManager = sourceManager,
        diagnostics = diagnostics,
        chapterLabelParser = chapterLabelParser,
    )
    private val mihonReadingSourceGateway: ReadingSourceGateway = MihonReadingSourceGateway(
        sourceManager = sourceManager,
        sourcePreferences = app.graph.sourcePreferences,
        networkToLocalManga = NetworkToLocalManga(mangaRepository),
    )
    val readingSourceGateway: ReadingSourceGateway = object : ReadingSourceGateway by mihonReadingSourceGateway {
        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            val started = SystemClock.elapsedRealtime()
            val result = mihonReadingSourceGateway.search(sourceId, query)
            synchronized(observedSearches) {
                observedSearches += MihonJourneySearchObservation(
                    sourceId = sourceId,
                    result = result,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                )
            }
            return result
        }

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
            val started = SystemClock.elapsedRealtime()
            val result = mihonReadingSourceGateway.materialize(candidate)
            val elapsedMs = SystemClock.elapsedRealtime() - started
            materializationObservation = MihonJourneyMaterializationObservation(
                succeeded = result.isSuccess,
                elapsedMs = elapsedMs,
            )
            onMaterialization(result, elapsedMs)
            return result
        }
    }
    private val providerFactory = MihonAddonProviderFactory(
        contentBindingRepository = contentBindingRepository,
        canonicalChapterRepository = canonicalChapterRepository,
        chapterEvidenceRepository = chapterEvidenceRepository,
        parser = chapterLabelParser,
        chapterInventoryGateway = chapterInventoryGateway,
        chapterInventoryDiagnostics = diagnostics,
    )
    val chapterProbeProvider: ChapterProbeProvider = providerFactory.chapterProbeProvider(addonId)
    private val chapterReconciliation = ReconcileChapterEvidence(
        parser = chapterLabelParser,
        canonicalChapterRepository = canonicalChapterRepository,
        evidenceRepository = chapterEvidenceRepository,
    )
    val reconcileChapterEvidence: ReconcileChapterEvidence
        get() = chapterReconciliation
    private val addonRegistry: AddonRegistry = DefaultAddonRegistry(
        addonRepository = app.graph.addonRepository,
        providerFactory = providerFactory,
        localContentProvider = LocalContentProvider(CanonicalDownloadRepositoryImpl(database)),
    )
    val resolveChapterContent = ResolveChapterContent(
        addonRegistry = addonRegistry,
        contentPreferenceRepository = contentPreferenceRepository,
        readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore()),
        rankContentOptions = RankContentOptions(),
        contentOptionCache = ContentOptionCache(),
        inFlightContentResolution = InFlightContentResolution(),
        addonRepository = app.graph.addonRepository,
    )
    val confirmContentBinding = ConfirmContentBinding(
        contentBindingRepository = contentBindingRepository,
        canonicalTitleRepository = canonicalTitleRepository,
        addonRepository = app.graph.addonRepository,
        readingSourceGateway = readingSourceGateway,
        scoreSourceTitleMatch = ScoreSourceTitleMatch(),
    )
    val resolveContentBinding = ResolveContentBinding(
        contentBindingRepository = contentBindingRepository,
        canonicalTitleRepository = canonicalTitleRepository,
        addonRepository = app.graph.addonRepository,
        readingSourceGateway = readingSourceGateway,
        scoreSourceTitleMatch = ScoreSourceTitleMatch(),
        addonSourceEligibilityRepository = MihonAddonSourceEligibilityRepository(
            extensionManager = app.graph.extensionManager,
            sourcePreferences = app.graph.sourcePreferences,
        ),
        diagnostics = diagnostics,
    )
    val scoreTitleMatch = ScoreSourceTitleMatch()
    val prepareCanonicalChapterForReader = PrepareCanonicalChapterForReader(
        resolveChapterContent = resolveChapterContent,
        canonicalChapterRepository = canonicalChapterRepository,
        canonicalReadingRepository = CanonicalReadingRepositoryImpl(database),
        canonicalDownloadRepository = CanonicalDownloadRepositoryImpl(database),
        chapterContentPreparer = MihonChapterContentPreparer(
            canonicalReaderGateway = MihonCanonicalReaderGateway(chapterRepository),
            canonicalDownloadRepository = CanonicalDownloadRepositoryImpl(database),
        ),
    )
}

internal data class MihonJourneySearchObservation(
    val sourceId: Long,
    val result: Result<List<ReadingSourceCandidate>>,
    val elapsedMs: Long,
)

internal data class MihonJourneyMaterializationObservation(
    val succeeded: Boolean,
    val elapsedMs: Long,
)

/** Bounds Java extension work that may block outside coroutine cancellation points. */
internal suspend fun <T> runMihonJourneyBounded(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val worker = Thread {
                val outcome = runCatching { runBlocking { block() } }
                if (continuation.isActive) continuation.resumeWith(outcome)
            }.apply {
                name = "Tsuzuki-Mihon-Android-E2E"
                isDaemon = true
            }
            continuation.invokeOnCancellation { worker.interrupt() }
            worker.start()
        }
    }
