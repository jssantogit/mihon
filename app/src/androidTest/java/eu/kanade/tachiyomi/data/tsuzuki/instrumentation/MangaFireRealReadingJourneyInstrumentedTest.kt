package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.MihonCanonicalReaderGateway
import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterContentPreparer
import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterInventoryGateway
import eu.kanade.tachiyomi.data.tsuzuki.MihonReadingSourceGateway
import eu.kanade.tachiyomi.data.tsuzuki.addon.DefaultAddonRegistry
import eu.kanade.tachiyomi.data.tsuzuki.addon.LocalContentProvider
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonAddonProviderFactory
import eu.kanade.tachiyomi.data.tsuzuki.diagnosticHttpStatus
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.interactor.PrepareCanonicalChapterForReader
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID

/**
 * Opt-in only. The composition uses production Mihon/Tsuzuki adapters and repositories, but gives
 * them the instrumentation APK's private SQLDelight database. It never writes to the target app's
 * library database, and every candidate must match the user's exact public MangaFire reference.
 */
@RunWith(AndroidJUnit4::class)
class MangaFireRealReadingJourneyInstrumentedTest {

    @Test(timeout = 90_000L)
    fun instrumentationContextDatabasePersistsCanonicalTitle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val packageIsolated = testContext.packageName != instrumentation.targetContext.packageName
        val applicationContext = runCatching { testContext.applicationContext }
        val applicationContextStatus = when {
            applicationContext.isFailure -> "error"
            applicationContext.getOrNull() == null -> "null"
            else -> "present"
        }
        val applicationContextValue = applicationContext.getOrNull()
        val (databaseContext, wrapperApplied) = createInstrumentationDatabaseContext(
            testContext,
            applicationContextValue,
        )
        reportContextObservation(
            applicationContextStatus,
            packageIsolated,
            if (wrapperApplied) "wrapped" else "raw",
        )
        assertTrue("Persistence must use the instrumentation package database", packageIsolated)
        assertTrue("Instrumentation application context could not be inspected", applicationContext.isSuccess)
        assertTrue(
            "The isolated database context must expose an application context",
            databaseContext.applicationContext != null,
        )

        resetInstrumentationDatabase(databaseContext)
        val canonicalTitle = CanonicalTitle(
            id = UUID.randomUUID().toString(),
            displayTitle = "Disposable instrumentation title",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val persistence = runCatching {
            val driver = AppBindings.providesSqlDriver(databaseContext)
            try {
                val repository = CanonicalTitleRepositoryImpl(AppBindings.providesDatabase(driver))
                runBlocking {
                    repository.insert(canonicalTitle)
                    assertEquals(canonicalTitle, repository.getById(canonicalTitle.id))
                }
            } finally {
                driver.close()
            }
        }
        persistence.fold(
            onSuccess = { reportSetupPhase("TEST_CANONICAL_TITLE_PERSIST", Outcome.PASS) },
            onFailure = { error ->
                reportSetupPhase(
                    "TEST_CANONICAL_TITLE_PERSIST",
                    Outcome.FAIL,
                    setupExceptionType(error),
                    setupFailureFrame(error),
                )
                throw error
            },
        )
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
                val rawTestContext = instrumentation.context
                val applicationContext = rawTestContext.applicationContext
                val (testContext, wrapperApplied) = createInstrumentationDatabaseContext(
                    rawTestContext,
                    applicationContext,
                )
                check(testContext.packageName != app.packageName) {
                    "Instrumentation DB must not share the target package"
                }
                check(testContext.applicationContext != null) {
                    "Instrumentation database context must provide an application context"
                }
                reportContextObservation(
                    applicationContext = if (applicationContext == null) "null" else "present",
                    packageIsolated = true,
                    databaseContext = if (wrapperApplied) "wrapped" else "raw",
                )
                reportSetupPhase(setupPhase, Outcome.PASS)
                setupPhase = "DB_RESET"
                resetInstrumentationDatabase(testContext)
                reportSetupPhase(setupPhase, Outcome.PASS)
                setupPhase = "SQL_DRIVER"
                val driver = AppBindings.providesSqlDriver(testContext)
                reportSetupPhase(setupPhase, Outcome.PASS)
                try {
                    setupPhase = "DATABASE_ADAPTERS"
                    val database = AppBindings.providesDatabase(driver)
                    reportSetupPhase(setupPhase, Outcome.PASS)
                    setupPhase = "COMPOSITION"
                    var materializationOutcome: Outcome? = null
                    var materializationCategory = "NONE"
                    var materializationElapsedMs: Long? = null
                    val composition = ProductionComposition(
                        app = app,
                        database = database,
                        sourceManager = app.graph.sourceManager,
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
                        runBounded(45_000L) { composition.readingSourceGateway.search(sourceId, SEARCH_QUERY) }
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
                        runBounded(60_000L) { composition.chapterProbeProvider.probe(canonicalTitleId) }
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
                        runBounded(60_000L) { registered.getPageList(operationalChapter.toSChapter()) }
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
                    driver.close()
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

    /**
     * Mihon extension APIs can block in Java, outside cancellable coroutine suspension points.
     * Run each external call on a daemon worker so the stage deadline can return even if the
     * extension ignores interruption. The worker is interrupted on timeout/cancellation.
     */
    private suspend fun <T> runBounded(timeoutMillis: Long, block: suspend () -> T): T =
        withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val worker = Thread {
                    val outcome = runCatching { runBlocking { block() } }
                    if (continuation.isActive) continuation.resumeWith(outcome)
                }.apply {
                    name = "Tsuzuki-MangaFire-E2E"
                    isDaemon = true
                }
                continuation.invokeOnCancellation { worker.interrupt() }
                worker.start()
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
            "SQLiteException", "SQLiteCantOpenDatabaseException", "SQLiteReadOnlyDatabaseException",
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

    private fun reportSetupPhase(
        phase: String?,
        outcome: Outcome,
        exception: String? = null,
        frame: String? = null,
    ) {
        if (phase == null) return
        val stream = buildString {
            append("RUNTIME_SETUP|phase=").append(phase).append("|outcome=").append(outcome.name)
            exception?.let { append("|exception=").append(it) }
            frame?.let { append("|frame=").append(it) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", stream) },
        )
    }

    private fun reportContextObservation(
        applicationContext: String,
        packageIsolated: Boolean,
        databaseContext: String,
    ) {
        val stream = buildString {
            append("RUNTIME_CONTEXT|applicationContext=").append(applicationContext)
            append("|targetIsolation=").append(if (packageIsolated) "isolated" else "same")
            append("|databaseContext=").append(databaseContext)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", stream) },
        )
    }

    private fun resetInstrumentationDatabase(context: Context) {
        check(
            !context.databaseList().contains("tachiyomi.db") || context.deleteDatabase("tachiyomi.db"),
        ) {
            "Could not reset the instrumentation-only database"
        }
    }

    private fun createInstrumentationDatabaseContext(
        context: Context,
        applicationContext: Context?,
    ): Pair<Context, Boolean> {
        if (applicationContext != null) return context to false
        val contextWithApplicationContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
        }
        return contextWithApplicationContext to true
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

    private class RecordingDiagnostics : ChapterInventoryDiagnostics {
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

    private class ProductionComposition(
        private val app: App,
        database: Database,
        sourceManager: SourceManager,
        onMaterialization: (Result<MaterializedReadingSource>, Long) -> Unit,
    ) {
        private val mangaRepository: MangaRepository = MangaRepositoryImpl(database)
        val chapterRepository: ChapterRepository = ChapterRepositoryImpl(database)
        val canonicalTitleRepository: CanonicalTitleRepository = CanonicalTitleRepositoryImpl(database)
        val contentBindingRepository: ContentBindingRepository = ContentBindingRepositoryImpl(database)
        val canonicalChapterRepository: CanonicalChapterRepository = CanonicalChapterRepositoryImpl(database)
        private val chapterEvidenceRepository: ChapterEvidenceRepository = ChapterEvidenceRepositoryImpl(database)
        private val contentPreferenceRepository: ContentPreferenceRepository = ContentPreferenceRepositoryImpl(database)
        val diagnostics = RecordingDiagnostics()
        private val parser = ParseCanonicalChapterLabel()
        private val chapterInventoryGateway = MihonChapterInventoryGateway(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = sourceManager,
            diagnostics = diagnostics,
            chapterLabelParser = parser,
        )
        private val mihonReadingSourceGateway: ReadingSourceGateway = MihonReadingSourceGateway(
            sourceManager = sourceManager,
            sourcePreferences = app.graph.sourcePreferences,
            networkToLocalManga = NetworkToLocalManga(mangaRepository),
        )
        val readingSourceGateway: ReadingSourceGateway = object : ReadingSourceGateway by mihonReadingSourceGateway {
            override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
                val started = SystemClock.elapsedRealtime()
                val result = mihonReadingSourceGateway.materialize(candidate)
                onMaterialization(result, SystemClock.elapsedRealtime() - started)
                return result
            }
        }
        private val addonId = AddonId(PACKAGE_NAME)
        private val providerFactory = MihonAddonProviderFactory(
            contentBindingRepository = contentBindingRepository,
            canonicalChapterRepository = canonicalChapterRepository,
            chapterEvidenceRepository = chapterEvidenceRepository,
            parser = parser,
            chapterInventoryGateway = chapterInventoryGateway,
            chapterInventoryDiagnostics = diagnostics,
        )
        val chapterProbeProvider: ChapterProbeProvider = providerFactory.chapterProbeProvider(addonId)
        private val chapterReconciliation = ReconcileChapterEvidence(
            parser = parser,
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
        val confirmContentBinding = tachiyomi.domain.tsuzuki.content.interactor.ConfirmContentBinding(
            contentBindingRepository = contentBindingRepository,
            canonicalTitleRepository = canonicalTitleRepository,
            addonRepository = app.graph.addonRepository,
            readingSourceGateway = readingSourceGateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
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

    private companion object {
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
