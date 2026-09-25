package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.diagnosticHttpStatus
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.content.ContentBindingRepositoryImpl
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import java.io.File
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID

/**
 * Opt-in only. The composition uses production Mihon/Tsuzuki adapters and repositories with a
 * disposable database context; the target-context smoke maps Mihon's database name to a random
 * test-only file and never opens the target app's library database.
 */
@RunWith(AndroidJUnit4::class)
class MangaFireRealReadingJourneyInstrumentedTest {

    @Test(timeout = 30_000L)
    fun instrumentationDatabaseIdentityProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val processUid = runCatching { Process.myUid() }.getOrNull()
        val testPackageUid = packageUid(testContext)
        val targetPackageUid = packageUid(targetContext)
        val testDatabaseParent = databaseParentObservation(testContext)
        val targetDatabaseParent = databaseParentObservation(targetContext)
        val testDataDirectory = dataDirectoryObservation(testContext)
        val targetDataDirectory = dataDirectoryObservation(targetContext)
        val testDatabasePath = databasePathCreationProbe(testContext)
        val instrumentationDatabase = sqliteContextDatabaseProbe(testContext)
        val targetDatabase = sqliteContextDatabaseProbe(targetContext)

        val stream = buildString {
            append("RUNTIME_DB_IDENTITY|processIsTestUid=")
                .append(uidMatches(processUid, testPackageUid))
            append("|processIsTargetUid=").append(uidMatches(processUid, targetPackageUid))
            append("|testDbParentWritable=").append(testDatabaseParent.writable)
            append("|testDbParentState=").append(testDatabaseParent.state)
            append("|targetDbParentWritable=").append(targetDatabaseParent.writable)
            append("|targetDbParentState=").append(targetDatabaseParent.state)
            append("|testDataDirWritable=").append(testDataDirectory.writable)
            append("|testDataDirExecutable=").append(testDataDirectory.executable)
            append("|testDataDirState=").append(testDataDirectory.state)
            append("|targetDataDirWritable=").append(targetDataDirectory.writable)
            append("|targetDataDirExecutable=").append(targetDataDirectory.executable)
            append("|targetDataDirState=").append(targetDataDirectory.state)
        }
        instrumentation.sendStatus(1, Bundle().apply { putString("stream", stream) })
        val pathProbe = buildString {
            append("RUNTIME_DB_PATH_PROBE|outcome=").append(testDatabasePath.outcome)
            append("|parentState=").append(testDatabasePath.parent.state)
            append("|parentWritable=").append(testDatabasePath.parent.writable)
            append("|cleanup=").append(testDatabasePath.cleanup)
        }
        instrumentation.sendStatus(1, Bundle().apply { putString("stream", pathProbe) })
        val sqliteProbe = buildString {
            append("RUNTIME_SQLITE_CONTEXT_PROBE|instrumentationOpen=")
                .append(instrumentationDatabase.openOutcome)
            append("|instrumentationError=").append(instrumentationDatabase.openError)
            append("|instrumentationClose=").append(instrumentationDatabase.closeOutcome)
            append("|instrumentationDelete=").append(instrumentationDatabase.deleteOutcome)
            append("|instrumentationDirCleanup=").append(instrumentationDatabase.directoryCleanup)
            append("|targetOpen=").append(targetDatabase.openOutcome)
            append("|targetError=").append(targetDatabase.openError)
            append("|targetClose=").append(targetDatabase.closeOutcome)
            append("|targetDelete=").append(targetDatabase.deleteOutcome)
            append("|targetDirCleanup=").append(targetDatabase.directoryCleanup)
        }
        instrumentation.sendStatus(1, Bundle().apply { putString("stream", sqliteProbe) })
        assertTrue("Instrumentation temporary database cleanup failed", instrumentationDatabase.cleaned)
        assertTrue("Target temporary database cleanup failed", targetDatabase.cleaned)
    }

    @Test(timeout = 90_000L)
    fun instrumentationContextDatabasePersistsCanonicalTitle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val disposableDatabaseName = "tsuzuki-e2e-${UUID.randomUUID()}.db"
        val databaseContext = MihonJourneyDisposableDatabaseContext(
            targetContext = targetContext,
            disposableDatabaseName = disposableDatabaseName,
        )
        val disposableDatabasePath = databaseContext.getDatabasePath(TARGET_DATABASE_NAME)
        assertTrue("Disposable E2E database name must not pre-exist", !disposableDatabasePath.exists())
        assertTrue(
            "Logical production database name must map to a different disposable file",
            disposableDatabasePath != targetContext.getDatabasePath(TARGET_DATABASE_NAME),
        )
        reportContextObservation(
            applicationContext = "present",
            storage = "TARGET_DISPOSABLE",
            databaseContext = "wrapped",
        )
        assertTrue(
            "The disposable database context must expose an application context",
            databaseContext.applicationContext != null,
        )

        val canonicalTitle = CanonicalTitle(
            id = UUID.randomUUID().toString(),
            displayTitle = "Disposable instrumentation title",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        var driver: app.cash.sqldelight.db.SqlDriver? = null
        try {
            val sqlDriver = diagnosticSetupPhase("DRIVER_CREATE") {
                AppBindings.providesSqlDriver(databaseContext)
            }
            driver = sqlDriver
            val database = diagnosticSetupPhase("DATABASE_CREATE") {
                val database = AppBindings.providesDatabase(sqlDriver)
                // Force the configured AndroidX driver to open the disposable database and
                // initialize its configured SQLDelight schema before repository work.
                sqlDriver.execute(null, "SELECT 1", 0)
                database
            }
            diagnosticSchemaProbe(sqlDriver)
            val repository = CanonicalTitleRepositoryImpl(database)
            diagnosticSetupPhase("TITLE_INSERT") {
                runBlocking { repository.insert(canonicalTitle) }
            }
            diagnosticSetupPhase("TITLE_READ") {
                val recovered = runBlocking { repository.getById(canonicalTitle.id) }
                assertEquals(canonicalTitle, recovered)
            }
        } finally {
            try {
                driver?.let { diagnosticSetupPhase("DRIVER_CLOSE") { it.close() } }
            } finally {
                diagnosticSetupPhase("DATABASE_CLEANUP") {
                    check(databaseContext.deleteDatabase(TARGET_DATABASE_NAME)) {
                        "Could not delete disposable instrumentation database"
                    }
                    check(!disposableDatabasePath.exists()) {
                        "Disposable instrumentation database still exists after delete"
                    }
                }
            }
        }
    }

    @Test(timeout = 240_000L)
    fun optionalRealEnglishReadingJourney() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue(
                "A manually dispatched provider journey is required",
                InstrumentationRegistry.getArguments().getString("allowLiveProvider") == "true",
            )

            val terminal = linkedMapOf<String, StageResult>()
            var currentStage = STAGES.first()
            var stopCategory = "INSTRUMENTATION"
            var setupPhase: String? = null
            var disposableDatabaseContext: MihonJourneyDisposableDatabaseContext? = null
            var sqlDriver: app.cash.sqldelight.db.SqlDriver? = null
            try {
                val app = instrumentation.targetContext.applicationContext as App
                val extension = loadTrustedFixture(app)
                report(currentStage, Outcome.PASS)
                terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                currentStage = "SOURCE_REGISTRATION"
                val englishSources = extension.sources.filter { it.lang.equals("en", ignoreCase = true) }
                val source = englishSources.singleOrNull()
                    ?: stop(Outcome.INCONCLUSIVE, "SOURCE_NOT_FOUND", count = englishSources.size)
                val sourceId = source.id
                if (sourceId != EXPECTED_ENGLISH_SOURCE_ID) stop(Outcome.INCONCLUSIVE, "IDENTITY")
                val registrationStarted = SystemClock.elapsedRealtime()
                val registeredSource = try {
                    withTimeout(SOURCE_REGISTRATION_TIMEOUT_MS) {
                        app.graph.sourceManager.sources
                            .first { sources -> sources.any { it.id == sourceId } }
                            .singleOrNull { it.id == sourceId }
                    }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    stop(
                        Outcome.INCONCLUSIVE,
                        "SOURCE_REGISTRATION_TIMEOUT",
                        sourceId = sourceId,
                        language = source.lang,
                        elapsedMs = SystemClock.elapsedRealtime() - registrationStarted,
                    )
                }
                val registered = registeredSource as? CatalogueSource
                    ?: stop(
                        Outcome.INCONCLUSIVE,
                        "SOURCE_TYPE_MISMATCH",
                        sourceId = sourceId,
                        language = source.lang,
                    )
                val installedAddon = app.graph.addonRepository.snapshot()
                    .singleOrNull { it.id == AddonId(PACKAGE_NAME) }
                    ?: stop(Outcome.INCONCLUSIVE, "BINDING")
                if (!installedAddon.enabled || sourceId !in installedAddon.mihonSourceIds) {
                    stop(Outcome.INCONCLUSIVE, "BINDING")
                }
                if (sourceId.toString() in app.graph.sourcePreferences.disabledSources.get()) {
                    stop(Outcome.INCONCLUSIVE, "SOURCE_DISABLED", sourceId = sourceId, language = source.lang)
                }
                report(currentStage, Outcome.PASS, sourceId = sourceId, language = source.lang)
                terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                setupPhase = "CONTEXT_ISOLATION"
                val disposableDatabaseName = "tsuzuki-e2e-${UUID.randomUUID()}.db"
                val databaseContext = MihonJourneyDisposableDatabaseContext(
                    targetContext = instrumentation.targetContext,
                    disposableDatabaseName = disposableDatabaseName,
                )
                disposableDatabaseContext = databaseContext
                val disposableDatabasePath = databaseContext.getDatabasePath(TARGET_DATABASE_NAME)
                check(!disposableDatabasePath.exists()) {
                    "Disposable E2E database name must not pre-exist"
                }
                check(disposableDatabasePath != instrumentation.targetContext.getDatabasePath(TARGET_DATABASE_NAME)) {
                    "Logical production database name must map to a different disposable file"
                }
                check(databaseContext.applicationContext != null) {
                    "Disposable database context must provide an application context"
                }
                reportContextObservation(
                    applicationContext = "present",
                    storage = "TARGET_DISPOSABLE",
                    databaseContext = "wrapped",
                )
                reportSetupPhase(setupPhase, Outcome.PASS)
                setupPhase = "SQL_DRIVER"
                try {
                    val driver = AppBindings.providesSqlDriver(databaseContext)
                    sqlDriver = driver
                    reportSetupPhase(setupPhase, Outcome.PASS)
                    setupPhase = "DATABASE_ADAPTERS"
                    val database = AppBindings.providesDatabase(driver)
                    reportSetupPhase(setupPhase, Outcome.PASS)
                    setupPhase = "COMPOSITION"
                    var materializationOutcome: Outcome? = null
                    var materializationCategory = "NONE"
                    var materializationElapsedMs: Long? = null
                    val composition = ProductionMihonJourneyComposition(
                        app = app,
                        database = database,
                        sourceManager = app.graph.sourceManager,
                        addonId = AddonId(PACKAGE_NAME),
                    ) { result, elapsedMs ->
                        materializationOutcome = if (result.isSuccess) Outcome.PASS else Outcome.FAIL
                        materializationCategory = if (result.isSuccess) "NONE" else "MATERIALIZATION"
                        materializationElapsedMs = elapsedMs
                    }
                    reportSetupPhase(setupPhase, Outcome.PASS)
                    setupPhase = "CANONICAL_TITLE_CREATE"
                    val canonicalTitleId = UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    val canonicalTitle = CanonicalTitle(
                        id = canonicalTitleId,
                        displayTitle = "One-Punch Man",
                        identityState = CanonicalIdentityState.SOURCE_ONLY,
                        createdAt = now,
                        updatedAt = now,
                    )
                    reportSetupPhase(setupPhase, Outcome.PASS)
                    setupPhase = "CANONICAL_TITLE_PERSIST"
                    composition.canonicalTitleRepository.insert(canonicalTitle)
                    reportSetupPhase(setupPhase, Outcome.PASS)
                    setupPhase = null
                    currentStage = "LIVE_SEARCH"
                    val searchStarted = SystemClock.elapsedRealtime()
                    val search = try {
                        runMihonJourneyBounded(45_000L) { composition.readingSourceGateway.search(sourceId, SEARCH_QUERY) }
                    } catch (error: CancellationException) {
                        if (error !is TimeoutCancellationException) throw error
                        stop(Outcome.INCONCLUSIVE, "TIMEOUT", elapsedMs = SystemClock.elapsedRealtime() - searchStarted)
                    }
                    val candidates = search.getOrElse { error ->
                        val category = externalCategory(error)
                        stop(Outcome.INCONCLUSIVE, category, elapsedMs = SystemClock.elapsedRealtime() - searchStarted)
                    }
                    if (candidates.isEmpty()) {
                        stop(
                            Outcome.INCONCLUSIVE,
                            "NO_RESULTS",
                            count = 0,
                            sourceId = sourceId,
                            language = source.lang,
                            elapsedMs = SystemClock.elapsedRealtime() - searchStarted,
                        )
                    }
                    report(
                        currentStage,
                        Outcome.PASS,
                        count = candidates.size,
                        sourceId = sourceId,
                        language = source.lang,
                        elapsedMs = SystemClock.elapsedRealtime() - searchStarted,
                    )
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "CANDIDATE_IDENTIFICATION"
                    val scored = candidates.map { candidate ->
                        candidate to composition.scoreTitleMatch("One-Punch Man", candidate.title)
                    }
                    val referenceMatches = scored.filter { (candidate, confidence) ->
                        confidence == 1.0 && matchesReference(candidate.sourceUrl)
                    }
                    if (referenceMatches.size != 1) {
                        stop(
                            Outcome.INCONCLUSIVE,
                            if (referenceMatches.size > 1) "AMBIGUOUS" else "LOW_CONFIDENCE",
                            count = referenceMatches.size,
                        )
                    }
                    val (candidate, confidence) = referenceMatches.single()
                    report(currentStage, Outcome.PASS, category = "UNIQUE_REFERENCE_MATCH", count = 1)
                    terminal[currentStage] = StageResult(Outcome.PASS, "UNIQUE_REFERENCE_MATCH")

                    currentStage = "MATCH_DECISION"
                    val explicitChoice = ScoredSourceCandidate(candidate, confidence, sourcePreferenceRank = 0)
                    report(currentStage, Outcome.PASS, category = "EXACT_REFERENCE")
                    terminal[currentStage] = StageResult(Outcome.PASS, "EXACT_REFERENCE")

                    currentStage = "BINDING_CREATE"
                    val bindingResult = composition.confirmContentBinding.execute(
                        canonicalTitleId = canonicalTitleId,
                        addonId = AddonId(PACKAGE_NAME),
                        selected = explicitChoice,
                    )
                    if (materializationOutcome != null) {
                        currentStage = "BINDING_MATERIALIZATION"
                        val materialized = materializationOutcome == Outcome.PASS
                        report(
                            currentStage,
                            materializationOutcome!!,
                            category = materializationCategory.takeUnless { it == "NONE" },
                            sourceId = sourceId,
                            language = source.lang,
                            elapsedMs = materializationElapsedMs,
                        )
                        terminal[currentStage] = StageResult(materializationOutcome!!, materializationCategory)
                        if (!materialized) stop(Outcome.FAIL, materializationCategory)
                    }
                    currentStage = "BINDING_CREATE"
                    val confirmed = bindingResult.getOrElse { stop(Outcome.FAIL, "BINDING") }
                    if (!confirmed.verifiedByUser || confirmed.canonicalTitleId != canonicalTitleId) {
                        stop(Outcome.FAIL, "IDENTITY")
                    }
                    report(currentStage, Outcome.PASS, sourceId = sourceId, language = source.lang)
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "BINDING_PERSISTENCE"
                    val recovered = ContentBindingRepositoryImpl(database).getByTitle(canonicalTitleId)
                        .singleOrNull { it.id == confirmed.id }
                        ?: stop(Outcome.FAIL, "BINDING")
                    if (recovered.runtimePayload.isEmpty() || recovered.providerTitleKey.isBlank()) {
                        stop(Outcome.FAIL, "IDENTITY")
                    }
                    report(currentStage, Outcome.PASS, sourceId = sourceId, language = source.lang)
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "INVENTORY"
                    composition.diagnostics.start(canonicalTitleId)
                    val probeResult = try {
                        runMihonJourneyBounded(60_000L) { composition.chapterProbeProvider.probe(canonicalTitleId) }
                    } catch (error: CancellationException) {
                        if (error !is TimeoutCancellationException) throw error
                        stop(Outcome.INCONCLUSIVE, "TIMEOUT")
                    }
                    val inventoryEvent = composition.diagnostics.events()
                        .lastOrNull { it.stage == ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY }
                        ?: stop(Outcome.FAIL, "INSTRUMENTATION")
                    if (inventoryEvent.outcome != ChapterInventoryDiagnosticOutcome.SUCCESS ||
                        (inventoryEvent.received ?: 0) <= 0
                    ) {
                        stop(
                            if (inventoryEvent.outcome == ChapterInventoryDiagnosticOutcome.EMPTY) {
                                Outcome.INCONCLUSIVE
                            } else {
                                Outcome.INCONCLUSIVE
                            },
                            if (inventoryEvent.outcome == ChapterInventoryDiagnosticOutcome.EMPTY) {
                                "INVENTORY_EMPTY"
                            } else {
                                inventoryEvent.httpStatus?.let(::httpCategory) ?: "EXTENSION"
                            },
                            count = inventoryEvent.received ?: 0,
                            elapsedMs = inventoryEvent.elapsedMillis,
                        )
                    }
                    report(
                        currentStage,
                        Outcome.PASS,
                        count = inventoryEvent.received,
                        sourceId = sourceId,
                        language = source.lang,
                        elapsedMs = inventoryEvent.elapsedMillis,
                    )
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "CHAPTER_PROBE"
                    val evidence = probeResult.getOrElse { error ->
                        stop(Outcome.INCONCLUSIVE, externalCategory(error))
                    }
                    if (evidence.isEmpty()) stop(Outcome.INCONCLUSIVE, "LOW_CONFIDENCE", count = 0)
                    report(
                        currentStage,
                        Outcome.PASS,
                        count = evidence.size,
                        sourceId = sourceId,
                        language = source.lang,
                    )
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "RECONCILIATION"
                    composition.reconcileChapterEvidence.execute(canonicalTitleId, evidence)
                    val chapters = composition.canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
                        .filter { chapter ->
                            val baseNumber = chapter.baseNumber
                            chapter.type == CanonicalChapterType.REGULAR &&
                                baseNumber != null && baseNumber > 0 &&
                                chapter.confirmation.name != "CONFLICTED"
                        }
                    if (chapters.isEmpty()) stop(Outcome.INCONCLUSIVE, "RECONCILIATION", count = 0)
                    report(currentStage, Outcome.PASS, count = chapters.size)
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "CONTENT_RESOLUTION"
                    val chapter = chapters.minBy { it.sortKey }
                    val resolution = composition.resolveChapterContent.execute(canonicalTitleId, chapter.id)
                    val options = when (resolution) {
                        is ContentResolution.Direct -> listOf(resolution.option)
                        is ContentResolution.NeedsSelection -> resolution.options
                        ContentResolution.Unavailable -> stop(Outcome.INCONCLUSIVE, "CONTENT_UNAVAILABLE")
                    }.filter { option ->
                        option.addonId == AddonId(PACKAGE_NAME) && option.language.equals("en", ignoreCase = true)
                    }
                    val exactOptions = options.filter { option ->
                        val delivery = option.delivery as? ContentDelivery.Mihon ?: return@filter false
                        delivery.sourceId == sourceId
                    }
                    if (exactOptions.size != 1) {
                        stop(
                            Outcome.INCONCLUSIVE,
                            if (exactOptions.isEmpty()) "CONTENT_UNAVAILABLE" else "AMBIGUOUS",
                            count = exactOptions.size,
                        )
                    }
                    val option = exactOptions.single()
                    report(
                        currentStage,
                        Outcome.PASS,
                        count = exactOptions.size,
                        sourceId = sourceId,
                        language = "en",
                    )
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "READER_PREPARATION"
                    val preparation = composition.prepareCanonicalChapterForReader.execute(
                        canonicalChapterId = chapter.id,
                        selectedOption = option,
                    )
                    val target = (preparation as? CanonicalReaderPreparation.Ready)?.target
                        as? PreparedChapterContent.MihonOperational
                        ?: stop(Outcome.FAIL, "READER_PREPARATION")
                    if (target.sourceId != sourceId) stop(Outcome.FAIL, "IDENTITY")
                    report(currentStage, Outcome.PASS, sourceId = sourceId)
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")

                    currentStage = "GET_PAGE_LIST"
                    val operationalChapter = composition.chapterRepository.getChapterById(target.chapterId)
                        ?: stop(Outcome.FAIL, "READER_PREPARATION")
                    val pageList = try {
                        runMihonJourneyBounded(60_000L) { registered.getPageList(operationalChapter.toSChapter()) }
                    } catch (error: CancellationException) {
                        if (error !is TimeoutCancellationException) throw error
                        stop(Outcome.INCONCLUSIVE, "TIMEOUT")
                    } catch (error: Throwable) {
                        stop(Outcome.INCONCLUSIVE, externalCategory(error))
                    }
                    if (pageList.isEmpty()) stop(Outcome.INCONCLUSIVE, "CONTENT_UNAVAILABLE", count = 0)
                    report(currentStage, Outcome.PASS, count = pageList.size, sourceId = sourceId, language = "en")
                    terminal[currentStage] = StageResult(Outcome.PASS, "NONE")
                } finally {
                    try {
                        sqlDriver?.let { driver ->
                            diagnosticSetupPhase("DRIVER_CLOSE") { driver.close() }
                        }
                    } finally {
                        disposableDatabaseContext?.let { context ->
                            diagnosticSetupPhase("DATABASE_CLEANUP") {
                                val path = context.getDatabasePath(TARGET_DATABASE_NAME)
                                check(context.deleteDatabase(TARGET_DATABASE_NAME)) {
                                    "Could not delete disposable journey database"
                                }
                                check(!path.exists()) {
                                    "Disposable journey database remains after cleanup"
                                }
                            }
                        }
                    }
                }
            } catch (stop: JourneyStop) {
                currentStage = stop.stage ?: currentStage
                stopCategory = stop.category
                if (currentStage !in terminal) {
                    report(
                        currentStage,
                        stop.outcome,
                        category = stop.category,
                        count = stop.count,
                        sourceId = stop.sourceId,
                        language = stop.language,
                        elapsedMs = stop.elapsedMs,
                    )
                    terminal[currentStage] = StageResult(stop.outcome, stop.category)
                }
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
                stopCategory = "TIMEOUT"
                report(currentStage, Outcome.INCONCLUSIVE, category = stopCategory)
                terminal[currentStage] = StageResult(Outcome.INCONCLUSIVE, stopCategory)
            } catch (error: Throwable) {
                stopCategory = "INSTRUMENTATION"
                val inSetup = setupPhase != null
                if (inSetup) {
                    reportSetupPhase(
                        setupPhase,
                        Outcome.FAIL,
                        setupExceptionType(error),
                        setupFailureFrame(error),
                    )
                }
                if (currentStage !in terminal) {
                    report(currentStage, Outcome.FAIL, category = stopCategory)
                    terminal[currentStage] = StageResult(Outcome.FAIL, stopCategory)
                } else if (!inSetup) {
                    // Do not let a failure after a PASS produce a false all-pass result.
                    terminal[currentStage] = StageResult(Outcome.FAIL, stopCategory)
                }
            }

            var blocker = false
            for (stage in STAGES) {
                if (stage !in terminal) {
                    blocker = true
                    report(stage, Outcome.NOT_RUN, category = stopCategory)
                }
            }
            assertEquals("The real-extension journey must pass every required stage", STAGES.size, terminal.size)
            assertTrue(
                "Real-extension journey is not a complete all-pass result",
                terminal.values.all { it.outcome == Outcome.PASS } && !blocker,
            )
        }
    }

    private suspend fun loadTrustedFixture(app: App): Extension.Installed {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_META_DATA)
        assertEquals("1.6.34", info.versionName)
        val manager = app.graph.extensionManager
        manager.getInstalledExtensions().firstOrNull { it.pkgName == PACKAGE_NAME }?.let { return it }
        val untrusted = withTimeout(45_000L) {
            manager.untrustedExtensionsFlow.first { items -> items.any { it.pkgName == PACKAGE_NAME } }
                .single { it.pkgName == PACKAGE_NAME }
        }
        assertEquals("1.6.34", untrusted.versionName)
        app.graph.sourcePreferences.showNsfwSource.set(true)
        manager.trust(untrusted)
        return withTimeout(30_000L) {
            manager.installedExtensionsFlow.first { items -> items.any { it.pkgName == PACKAGE_NAME } }
                .single { it.pkgName == PACKAGE_NAME }
        }
    }

    private fun matchesReference(sourceUrl: String): Boolean = try {
        val uri = URI(sourceUrl)
        val host = uri.host
        val path = uri.path.orEmpty().trim('/')
        (host == null || host.equals("mangafire.to", ignoreCase = true)) &&
            path.endsWith("title/$EXPECTED_REFERENCE_SLUG", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun setupExceptionType(error: Throwable): String {
        val allowed = setOf(
            "IllegalStateException", "IllegalArgumentException", "SecurityException",
            "SQLException", "SQLiteException", "SQLiteCantOpenDatabaseException", "SQLiteReadOnlyDatabaseException",
            "SQLiteConstraintException", "SQLiteDatabaseCorruptException", "SQLiteDiskIOException",
            "SQLiteFullException", "SQLiteBlobTooBigException", "SQLiteDatatypeMismatchException",
            "SQLiteMisuseException", "SQLiteAccessPermException", "SQLiteBindOrColumnIndexOutOfRangeException",
            "UnsatisfiedLinkError", "NoClassDefFoundError", "ExceptionInInitializerError",
            "ClassNotFoundException", "NullPointerException", "IOException",
        )
        return generateSequence(error) { it.cause }
            .take(6)
            .map { it.javaClass.simpleName }
            .firstOrNull { it in allowed } ?: "OTHER"
    }

    private fun setupFailureFrame(error: Throwable): String? =
        generateSequence(error) { it.cause }
            .flatMap { it.stackTrace.asSequence() }
            .mapNotNull { frame ->
                when {
                    frame.className == "tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl" &&
                        frame.methodName == "insert" -> "TITLE_REPOSITORY_INSERT"
                    frame.className == "tachiyomi.data.Tsuzuki_titlesQueries" &&
                        frame.methodName == "insertTsuzukiTitle" -> "SQLDELIGHT_QUERY"
                    frame.className == "app.cash.sqldelight.BaseTransacterImpl" &&
                        frame.methodName == "notifyQueries" -> "SQLDELIGHT_NOTIFY_QUERIES"
                    frame.className == "app.cash.sqldelight.async.coroutines.DriverExtensionsKt" &&
                        frame.methodName.startsWith("await") -> "SQLDELIGHT_DRIVER_AWAIT"
                    frame.className.startsWith("app.cash.sqldelight.db.QueryResult") -> "SQLDELIGHT_RESULT_VALUE"
                    frame.className.startsWith("app.cash.sqldelight.") -> "SQLDELIGHT_RUNTIME"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriverHolder") ->
                        "EYGRABER_SCHEMA_DRIVER"
                    frame.className.startsWith(
                        "com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfigurableDriver",
                    ) ->
                        "EYGRABER_CONFIGURABLE_DRIVER"
                    frame.className.startsWith(
                        "com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConnectionFactory",
                    ) ||
                        frame.className.startsWith(
                            "com.eygraber.sqldelight.androidx.driver.DefaultAndroidxSqliteConnectionFactory",
                        ) ->
                        "EYGRABER_CONNECTION_FACTORY"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver") ->
                        "EYGRABER_SQLITE_DRIVER"
                    frame.className.startsWith(
                        "com.eygraber.sqldelight.androidx.driver.AndroidxSqliteExecutingDriverKt",
                    ) ->
                        "EYGRABER_EXECUTING_DRIVER_KT"
                    frame.className.startsWith(
                        "com.eygraber.sqldelight.androidx.driver.AndroidxDriverConnectionPool",
                    ) ->
                        "EYGRABER_CONNECTION_POOL"
                    frame.className.startsWith(
                        "com.eygraber.sqldelight.androidx.driver.AndroidxSqliteExecutingDriver",
                    ) ->
                        "EYGRABER_EXECUTING_DRIVER"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.AndroidxPreparedStatement") ->
                        "EYGRABER_PREPARED_STATEMENT"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.AndroidxQuery") ->
                        "EYGRABER_QUERY"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.AndroidxStatement") ->
                        "EYGRABER_STATEMENT"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.AndroidxSqliteUtils") ->
                        "EYGRABER_SQLITE_UTILS"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.ActiveTransaction") ->
                        "EYGRABER_ACTIVE_TRANSACTION"
                    frame.className.startsWith("com.eygraber.sqldelight.androidx.driver.") ->
                        "EYGRABER_ANDROIDX_DRIVER"
                    frame.className.startsWith("androidx.sqlite.driver.bundled.") -> "ANDROIDX_BUNDLED_DRIVER"
                    frame.className.startsWith("androidx.sqlite.") -> "ANDROIDX_SQLITE_CORE"
                    else -> null
                }
            }
            .firstOrNull()

    private inline fun <T> diagnosticSetupPhase(phase: String, block: () -> T): T =
        try {
            block().also { reportSetupPhase(phase, Outcome.PASS) }
        } catch (error: Exception) {
            reportSetupPhase(
                phase,
                Outcome.FAIL,
                setupExceptionType(error),
                setupFailureFrame(error),
                setupSqlCategory(error).takeUnless { it == "NOT_SQLITE" },
            )
            throw error
        }

    private fun reportSetupPhase(
        phase: String?,
        outcome: Outcome,
        exception: String? = null,
        frame: String? = null,
        sqlCategory: String? = null,
    ) {
        if (phase == null) return
        val stream = buildString {
            append("RUNTIME_SETUP|phase=").append(phase).append("|outcome=").append(outcome.name)
            exception?.let { append("|exception=").append(it) }
            frame?.let { append("|frame=").append(it) }
            sqlCategory?.let { append("|sqlCategory=").append(it) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", stream) },
        )
    }

    private fun reportContextObservation(
        applicationContext: String,
        storage: String,
        databaseContext: String,
    ) {
        val stream = buildString {
            append("RUNTIME_CONTEXT|applicationContext=").append(applicationContext)
            append("|storage=").append(storage)
            append("|databaseContext=").append(databaseContext)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", stream) },
        )
    }

    private fun packageUid(context: Context): Int? = try {
        context.packageManager.getApplicationInfo(context.packageName, 0).uid
    } catch (_: Exception) {
        null
    }

    private fun uidMatches(processUid: Int?, packageUid: Int?): String = when {
        processUid == null || packageUid == null -> "unknown"
        processUid == packageUid -> "true"
        else -> "false"
    }

    private fun databaseParentObservation(context: Context): DatabaseParentObservation = try {
        // Avoid Context.getDatabasePath(): Android may create the database directory as a side effect.
        val parent = File(context.applicationInfo.dataDir, "databases")
        when {
            !parent.exists() -> DatabaseParentObservation("unknown", "MISSING")
            !parent.isDirectory -> DatabaseParentObservation("unknown", "NOT_DIRECTORY")
            else -> DatabaseParentObservation(parent.canWrite().toString(), "EXISTS")
        }
    } catch (_: Exception) {
        DatabaseParentObservation("unknown", "ERROR")
    }

    private data class DatabaseParentObservation(val writable: String, val state: String)

    private fun dataDirectoryObservation(context: Context): DataDirectoryObservation = try {
        // Inspect only; unlike Context database helpers this never creates a directory or file.
        val directory = File(context.applicationInfo.dataDir)
        when {
            !directory.exists() -> DataDirectoryObservation("unknown", "unknown", "MISSING")
            !directory.isDirectory -> DataDirectoryObservation("unknown", "unknown", "NOT_DIRECTORY")
            else -> DataDirectoryObservation(
                writable = directory.canWrite().toString(),
                executable = directory.canExecute().toString(),
                state = "EXISTS",
            )
        }
    } catch (_: Exception) {
        DataDirectoryObservation("unknown", "unknown", "ERROR")
    }

    private data class DataDirectoryObservation(
        val writable: String,
        val executable: String,
        val state: String,
    )

    private fun databasePathCreationProbe(context: Context): DatabasePathProbeObservation {
        val dataDirectory = File(context.applicationInfo.dataDir)
        val parent = File(dataDirectory, "databases")
        val dataDirectoryExistedBefore = dataDirectory.exists()
        val before = databaseParentObservation(context)
        if (before.state != "MISSING") {
            return DatabasePathProbeObservation(
                outcome = "PREEXISTING",
                parent = databaseParentObservation(context),
                cleanup = "NOT_NEEDED",
            )
        }

        val outcome = try {
            // getDatabasePath is intentionally the only operation under test. Never open or create
            // the returned database file; it may create the parent directory as a side effect.
            context.getDatabasePath("tsuzuki-probe-${UUID.randomUUID()}.db")
            when {
                !parent.exists() -> "PARENT_MISSING"
                !parent.isDirectory -> "PARENT_NOT_DIRECTORY"
                else -> "CREATED"
            }
        } catch (_: SecurityException) {
            "SECURITY_ERROR"
        } catch (_: java.io.IOException) {
            "IO_ERROR"
        } catch (_: Exception) {
            "OTHER_ERROR"
        }

        val observation = databaseParentObservation(parent)
        val cleanup = cleanupCreatedDatabaseDirectories(
            dataDirectory,
            parent,
            dataDirectoryExistedBefore,
        )
        return DatabasePathProbeObservation(outcome, observation, cleanup)
    }

    private fun databaseParentObservation(parent: File): DatabaseParentObservation = try {
        when {
            !parent.exists() -> DatabaseParentObservation("unknown", "MISSING")
            !parent.isDirectory -> DatabaseParentObservation("unknown", "NOT_DIRECTORY")
            else -> DatabaseParentObservation(parent.canWrite().toString(), "EXISTS")
        }
    } catch (_: Exception) {
        DatabaseParentObservation("unknown", "ERROR")
    }

    private fun cleanupCreatedDatabaseDirectories(
        dataDirectory: File,
        parent: File,
        dataDirectoryExistedBefore: Boolean,
    ): String {
        return try {
            var removed = false
            if (parent.exists()) {
                if (!parent.isDirectory) return "NOT_REMOVED"
                val parentContents = parent.list() ?: return "UNKNOWN"
                if (parentContents.isNotEmpty()) return "NOT_EMPTY"
                if (!parent.delete()) return "DELETE_FAILED"
                removed = true
            }
            if (!dataDirectoryExistedBefore && dataDirectory.exists()) {
                if (!dataDirectory.isDirectory) return "PARTIAL"
                val dataDirectoryContents = dataDirectory.list() ?: return "PARTIAL"
                if (dataDirectoryContents.isNotEmpty()) return "PARTIAL"
                if (!dataDirectory.delete()) return "PARTIAL"
                removed = true
            }
            if (removed) "REMOVED" else "NOT_NEEDED"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private data class DatabasePathProbeObservation(
        val outcome: String,
        val parent: DatabaseParentObservation,
        val cleanup: String,
    )

    private fun sqliteContextDatabaseProbe(context: Context): SqliteContextProbeObservation {
        val databaseName = "tsuzuki-probe-${UUID.randomUUID()}.db"
        val dataDirectory = File(context.applicationInfo.dataDir)
        val databaseParent = File(dataDirectory, "databases")
        val dataDirectoryExistedBefore = dataDirectory.exists()
        val databaseParentExistedBefore = databaseParent.exists()
        var database: SQLiteDatabase? = null
        val openError = try {
            database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            "NONE"
        } catch (error: Exception) {
            sqliteProbeErrorCategory(error)
        }
        val openOutcome = if (database == null) "FAILED" else "PASS"
        val closeOutcome = when {
            database == null -> "NOT_OPENED"
            else -> try {
                database.close()
                "PASS"
            } catch (error: Exception) {
                "ERROR_${sqliteProbeErrorCategory(error)}"
            }
        }
        val deleteOutcome = try {
            if (context.deleteDatabase(databaseName)) "PASS" else "FAILED"
        } catch (error: Exception) {
            "ERROR_${sqliteProbeErrorCategory(error)}"
        }
        val directoryCleanup = if (databaseParentExistedBefore) {
            "NOT_NEEDED"
        } else {
            cleanupCreatedDatabaseDirectories(dataDirectory, databaseParent, dataDirectoryExistedBefore)
        }
        return SqliteContextProbeObservation(
            openOutcome = openOutcome,
            openError = openError,
            closeOutcome = closeOutcome,
            deleteOutcome = deleteOutcome,
            directoryCleanup = directoryCleanup,
            cleaned = closeOutcome in setOf("PASS", "NOT_OPENED") &&
                deleteOutcome == "PASS" && directoryCleanup in setOf("REMOVED", "NOT_NEEDED"),
        )
    }

    private fun sqliteProbeErrorCategory(error: Exception): String = when (error) {
        is SQLiteException -> "SQLITE"
        is SecurityException -> "SECURITY"
        is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
        is IllegalStateException -> "ILLEGAL_STATE"
        is java.io.IOException -> "IO"
        else -> "OTHER"
    }

    private data class SqliteContextProbeObservation(
        val openOutcome: String,
        val openError: String,
        val closeOutcome: String,
        val deleteOutcome: String,
        val directoryCleanup: String,
        val cleaned: Boolean,
    )

    private fun diagnosticSchemaProbe(driver: app.cash.sqldelight.db.SqlDriver) {
        try {
            val foundObjects = runBlocking {
                driver.executeQuery(
                    null,
                    "SELECT name FROM sqlite_master WHERE name IN " +
                        "('tsuzuki_titles', 'tsuzuki_sync_outbox', 'tsuzuki_titles_sync_dirty_insert')",
                    { cursor ->
                        QueryResult.AsyncValue {
                            buildSet {
                                while (cursor.next().await()) cursor.getString(0)?.let(::add)
                            }
                        }
                    },
                    0,
                    {},
                ).await()
            }
            reportSchemaObservation(
                "PASS",
                schemaObjectState("tsuzuki_titles" in foundObjects),
                schemaObjectState("tsuzuki_sync_outbox" in foundObjects),
                schemaObjectState("tsuzuki_titles_sync_dirty_insert" in foundObjects),
            )
        } catch (error: Throwable) {
            reportSchemaObservation(
                "FAIL",
                titles = "unknown",
                outbox = "unknown",
                trigger = "unknown",
                sqlCategory = setupSqlCategory(error),
            )
            throw error
        }
    }

    private fun reportSchemaObservation(
        outcome: String,
        titles: String,
        outbox: String,
        trigger: String,
        sqlCategory: String? = null,
    ) {
        val stream = buildString {
            append("RUNTIME_SCHEMA|outcome=").append(outcome)
            append("|titles=").append(titles)
            append("|outbox=").append(outbox)
            append("|dirtyInsertTrigger=").append(trigger)
            sqlCategory?.let { append("|sqlCategory=").append(it) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", stream) },
        )
    }

    private fun schemaObjectState(found: Boolean): String = if (found) "present" else "missing"

    private fun setupSqlCategory(error: Throwable): String {
        val classNames = generateSequence(error) { it.cause }
            .take(6)
            .map { it.javaClass.simpleName }
            .toSet()
        return when {
            "SQLiteConstraintException" in classNames -> "SQLITE_CONSTRAINT"
            "SQLiteDatabaseCorruptException" in classNames -> "SQLITE_CORRUPT"
            "SQLiteDiskIOException" in classNames -> "SQLITE_IO"
            "SQLiteFullException" in classNames -> "SQLITE_FULL"
            "SQLiteReadOnlyDatabaseException" in classNames -> "SQLITE_READ_ONLY"
            "SQLiteCantOpenDatabaseException" in classNames -> "SQLITE_OPEN"
            classNames.any { it == "SQLException" || it == "SQLiteException" } -> "SQLITE_OTHER"
            else -> "NOT_SQLITE"
        }
    }

    private fun report(
        stage: String,
        outcome: Outcome,
        category: String? = null,
        count: Int? = null,
        sourceId: Long? = null,
        language: String? = null,
        elapsedMs: Long? = null,
    ) {
        val stream = buildString {
            append("RUNTIME_E2E|stage=").append(stage).append("|outcome=").append(outcome.name)
            category?.let { append("|category=").append(it) }
            count?.let { append("|count=").append(it.coerceIn(0, 9_999_999)) }
            sourceId?.let { append("|sourceId=").append(it) }
            language?.let { safeLanguage(it)?.let { safe -> append("|language=").append(safe) } }
            elapsedMs?.let { append("|elapsedMs=").append(it.coerceIn(0, 9_999_999)) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(1, Bundle().apply { putString("stream", stream) })
    }

    private fun safeLanguage(value: String): String? = value.takeIf {
        it.length in 1..16 && it.all { char -> char.isLetterOrDigit() || char == '-' }
    }

    private fun externalCategory(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        val failure = causes.filterIsInstance<tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure>()
            .firstOrNull()
        val status = failure?.httpStatus ?: error.diagnosticHttpStatus()
        return when {
            failure?.kind == ReadingSourceFailureKind.CAPTCHA_REQUIRED -> "CAPTCHA"
            status != null -> httpCategory(status)
            failure?.kind == ReadingSourceFailureKind.TIMEOUT || causes.any {
                it is SocketTimeoutException || it is TimeoutCancellationException
            } -> "TIMEOUT"
            failure?.kind == ReadingSourceFailureKind.NETWORK_FAILURE || causes.any {
                it is UnknownHostException || it is ConnectException || it is SocketException
            } -> "NETWORK"
            failure?.kind == ReadingSourceFailureKind.MALFORMED_RESPONSE -> "MALFORMED"
            else -> "EXTENSION"
        }
    }

    private fun httpCategory(status: Int): String = when (status) {
        403 -> "HTTP_403"
        429 -> "HTTP_429"
        in 500..599 -> "HTTP_5XX"
        else -> "HTTP_OTHER"
    }

    private fun stop(
        outcome: Outcome,
        category: String,
        count: Int? = null,
        sourceId: Long? = null,
        language: String? = null,
        elapsedMs: Long? = null,
    ): Nothing = throw JourneyStop(outcome, category, count, sourceId, language, elapsedMs)

    private enum class Outcome { PASS, FAIL, INCONCLUSIVE, NOT_RUN }

    private data class StageResult(val outcome: Outcome, val category: String)

    private data class JourneyStop(
        val outcome: Outcome,
        val category: String,
        val count: Int? = null,
        val sourceId: Long? = null,
        val language: String? = null,
        val elapsedMs: Long? = null,
        val stage: String? = null,
    ) : RuntimeException()

    private companion object {
        const val TARGET_DATABASE_NAME = "tachiyomi.db"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.mangafire"
        const val EXPECTED_ENGLISH_SOURCE_ID = 6084907896154116083L
        const val EXPECTED_REFERENCE_SLUG = "729pj-one-punch-man"
        const val SEARCH_QUERY = "One Punch Man"
        const val SOURCE_REGISTRATION_TIMEOUT_MS = 30_000L
        val STAGES = listOf(
            "EXTENSION_INSTALL",
            "SOURCE_REGISTRATION",
            "LIVE_SEARCH",
            "CANDIDATE_IDENTIFICATION",
            "MATCH_DECISION",
            "BINDING_MATERIALIZATION",
            "BINDING_CREATE",
            "BINDING_PERSISTENCE",
            "INVENTORY",
            "CHAPTER_PROBE",
            "RECONCILIATION",
            "CONTENT_RESOLUTION",
            "READER_PREPARATION",
            "GET_PAGE_LIST",
        )
    }
}
