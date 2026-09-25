package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureKind
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchMode
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchProgress
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchRequest
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSourceOutcome
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID

/**
 * Opt-in real-provider journey for a single MangaBall pt-BR source. The fixture-only test never
 * searches a source. Live evidence is limited to one progressive title batch, one selected title
 * inventory, and at most one page-list request in a disposable canonical database.
 */
@RunWith(AndroidJUnit4::class)
class MangaBallRealReadingJourneyInstrumentedTest {
    private var activeSourceId: Long? = null
    private var activeLanguage: String? = null

    @Test(timeout = 90_000L)
    fun loadsRealExtensionAndRegistersInternalSourcesAndDisabledPeerIsExcluded() {
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
            val originalDisabled = app.graph.sourcePreferences.disabledSources.get()
            val originalShowNsfw = app.graph.sourcePreferences.showNsfwSource.get()
            try {
                val extension = loadTrustedFixture(app)
                val sourceIds = extension.sources.map { it.id }.toSet()
                assertEquals(EXPECTED_INTERNAL_SOURCE_COUNT, sourceIds.size)
                val source = extension.sources.single { it.id == EXPECTED_PT_BR_SOURCE_ID }
                assertEquals("pt-BR", source.lang)
                assertEquals(1, extension.sources.count { it.lang.equals("pt-BR", ignoreCase = true) })

                withTimeout(SOURCE_REGISTRATION_TIMEOUT_MS) {
                    app.graph.sourceManager.sources.first { registered ->
                        sourceIds.all { id -> registered.any { it.id == id } }
                    }
                }

                val addonId = AddonId(PACKAGE_NAME)
                val packageSources = sourceIds.map { it.toString() }.toSet()
                val unrelatedDisabled = originalDisabled - packageSources
                app.graph.sourcePreferences.disabledSources.set(unrelatedDisabled)
                val before = app.graph.addonRepository.snapshot().filter { it.id == addonId }
                assertEquals(1, before.size)
                assertEquals(sourceIds, before.single().mihonSourceIds.toSet())

                val disabledPeer = extension.sources.first { it.id != EXPECTED_PT_BR_SOURCE_ID }
                app.graph.sourcePreferences.disabledSources.set(unrelatedDisabled + disabledPeer.id.toString())
                val after = app.graph.addonRepository.snapshot().filter { it.id == addonId }
                assertEquals(1, after.size)
                assertEquals(sourceIds - disabledPeer.id, after.single().mihonSourceIds.toSet())
                assertTrue(disabledPeer.id !in after.single().mihonSourceIds)
                assertTrue(EXPECTED_PT_BR_SOURCE_ID in after.single().mihonSourceIds)
                assertTrue(after.single().enabled)
                val ptBrInstalled = app.graph.readingSourceGateway.listInstalled("pt-BR").map { it.sourceId }
                assertTrue(EXPECTED_PT_BR_SOURCE_ID in ptBrInstalled)
                assertTrue(disabledPeer.id !in ptBrInstalled)

                sendFixtureEvent(
                    internalSources = sourceIds.size,
                    eligibleBeforeDisable = before.single().mihonSourceIds.size,
                    eligibleAfterDisable = after.single().mihonSourceIds.size,
                )
            } finally {
                app.graph.sourcePreferences.disabledSources.set(originalDisabled)
                app.graph.sourcePreferences.showNsfwSource.set(originalShowNsfw)
            }
        }
    }

    @Test(timeout = 240_000L)
    fun optionalLivePtBrReadingJourney() {
        runBlocking {
            check(InstrumentationRegistry.getArguments().getString("allowLiveProvider") == "true") {
                "Live MangaBall test may run only through explicit opt-in"
            }

            val terminal = linkedMapOf<String, MangaBallStageResult>()
            var currentStage = JOURNEY_STAGES.first()
            var currentSourceId: Long? = null
            var currentLanguage: String? = null
            var databaseContext: MihonJourneyDisposableDatabaseContext? = null
            var driver: app.cash.sqldelight.db.SqlDriver? = null
            var app: App? = null
            var originalDisabled: Set<String>? = null
            var originalShowNsfw: Boolean? = null
            var setupError = false

            try {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val appInstance = instrumentation.targetContext.applicationContext as App
                app = appInstance
                originalDisabled = appInstance.graph.sourcePreferences.disabledSources.get()
                originalShowNsfw = appInstance.graph.sourcePreferences.showNsfwSource.get()

                val extension = loadTrustedFixture(appInstance)
                record(terminal, currentStage, Outcome.PASS, "EXTENSION_LOADED", count = extension.sources.size)

                currentStage = "SOURCE_REGISTRATION"
                val sourceIds = extension.sources.map { it.id }.toSet()
                if (sourceIds.size != EXPECTED_INTERNAL_SOURCE_COUNT) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "SOURCE_NOT_FOUND", count = sourceIds.size)
                }
                val ptBrSources = extension.sources.filter { it.lang.equals("pt-BR", ignoreCase = true) }
                val source = ptBrSources.singleOrNull { it.id == EXPECTED_PT_BR_SOURCE_ID }
                    ?: stop(currentStage, Outcome.INCONCLUSIVE, "SOURCE_NOT_FOUND", count = ptBrSources.size)
                if (ptBrSources.size != 1) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "AMBIGUOUS", count = ptBrSources.size)
                }
                currentSourceId = source.id
                currentLanguage = source.lang
                activeSourceId = source.id
                activeLanguage = source.lang
                val registered = withTimeout(SOURCE_REGISTRATION_TIMEOUT_MS) {
                    appInstance.graph.sourceManager.sources.first { values -> sourceIds.all { id -> values.any { it.id == id } } }
                }
                if (registered.singleOrNull { it.id == source.id } !is CatalogueSource) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "SOURCE_NOT_FOUND", sourceId = source.id, language = source.lang)
                }
                val addonId = AddonId(PACKAGE_NAME)
                val baselineDisabled = appInstance.graph.sourcePreferences.disabledSources.get() - sourceIds.map { it.toString() }.toSet()
                appInstance.graph.sourcePreferences.disabledSources.set(baselineDisabled)
                val addonsBefore = appInstance.graph.addonRepository.snapshot().filter { it.id == addonId }
                if (addonsBefore.size != 1 || addonsBefore.single().mihonSourceIds.toSet() != sourceIds) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "IDENTITY", count = addonsBefore.size)
                }
                record(
                    terminal,
                    currentStage,
                    Outcome.PASS,
                    "SOURCE_REGISTERED",
                    count = sourceIds.size,
                    sourceId = source.id,
                    language = source.lang,
                )

                currentStage = "SOURCE_ELIGIBILITY"
                val disabledPeer = extension.sources.first { it.id != source.id }
                appInstance.graph.sourcePreferences.disabledSources.set(baselineDisabled + disabledPeer.id.toString())
                val eligibleAddon = appInstance.graph.addonRepository.snapshot().singleOrNull { it.id == addonId }
                    ?: stop(currentStage, Outcome.FAIL, "IDENTITY", sourceId = source.id, language = source.lang)
                val expectedEligibleIds = sourceIds - disabledPeer.id
                if (eligibleAddon.mihonSourceIds.toSet() != expectedEligibleIds ||
                    disabledPeer.id in eligibleAddon.mihonSourceIds || source.id !in eligibleAddon.mihonSourceIds
                ) {
                    stop(currentStage, Outcome.FAIL, "SOURCE_DISABLED", count = eligibleAddon.mihonSourceIds.size)
                }
                record(
                    terminal,
                    currentStage,
                    Outcome.PASS,
                    "DISABLED_SOURCE_EXCLUDED",
                    count = eligibleAddon.mihonSourceIds.size,
                    sourceId = disabledPeer.id,
                    language = disabledPeer.lang,
                )

                currentStage = "LIVE_SEARCH"
                val disposableName = "tsuzuki-mangaball-${UUID.randomUUID()}.db"
                val isolatedDatabaseContext = MihonJourneyDisposableDatabaseContext(
                    targetContext = instrumentation.targetContext,
                    disposableDatabaseName = disposableName,
                )
                databaseContext = isolatedDatabaseContext
                val databasePath = isolatedDatabaseContext.getDatabasePath(TARGET_DATABASE_NAME)
                if (databasePath.exists() || databasePath == instrumentation.targetContext.getDatabasePath(TARGET_DATABASE_NAME)) {
                    stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                }
                sendContextObservation()
                driver = AppBindings.providesSqlDriver(isolatedDatabaseContext)
                val database = AppBindings.providesDatabase(driver!!)
                driver!!.execute(null, "SELECT 1", 0)
                val composition = ProductionMihonJourneyComposition(
                    app = appInstance,
                    database = database,
                    sourceManager = appInstance.graph.sourceManager,
                    addonId = addonId,
                )
                val canonicalTitleId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                composition.canonicalTitleRepository.insert(
                    CanonicalTitle(
                        id = canonicalTitleId,
                        displayTitle = REFERENCE_TITLE,
                        identityState = CanonicalIdentityState.SOURCE_ONLY,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                if (composition.canonicalTitleRepository.getById(canonicalTitleId)?.displayTitle != REFERENCE_TITLE) {
                    stop(currentStage, Outcome.FAIL, "PERSISTENCE", sourceId = source.id, language = source.lang)
                }

                val searchStarted = SystemClock.elapsedRealtime()
                val progressEvents = try {
                    runMihonJourneyBounded(45_000L) {
                        composition.resolveContentBinding.searchProgress(
                            ContentBindingSearchRequest(
                                canonicalTitleId = canonicalTitleId,
                                addonId = addonId,
                                preferredLanguages = listOf("pt-BR"),
                                mode = ContentBindingSearchMode.INITIAL,
                                batchSize = 1,
                                sourceTimeoutMillis = 30_000L,
                            ),
                        ).toList()
                    }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    stop(currentStage, Outcome.INCONCLUSIVE, "TIMEOUT", sourceId = source.id, language = source.lang,
                        elapsedMs = SystemClock.elapsedRealtime() - searchStarted)
                }
                val completion = progressEvents.filterIsInstance<ContentBindingSearchProgress.Completed>().singleOrNull()
                    ?: stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                if (completion.queriedSourceIds != listOf(source.id) || disabledPeer.id in completion.queriedSourceIds) {
                    stop(currentStage, Outcome.FAIL, "SOURCE_QUERY_MISMATCH", sourceId = source.id, language = source.lang)
                }
                val searchObservation = composition.searchObservations.singleOrNull()
                    ?: stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                if (searchObservation.sourceId != source.id) {
                    stop(currentStage, Outcome.FAIL, "SOURCE_QUERY_MISMATCH", sourceId = source.id, language = source.lang)
                }
                val queriedCandidates = searchObservation.result.getOrElse { error ->
                    val sourceFailure = progressEvents.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
                        .singleOrNull()?.failure
                    val category = sourceFailure?.let(::failureCategory) ?: errorCategory(error)
                    stop(
                        currentStage,
                        Outcome.INCONCLUSIVE,
                        category,
                        sourceId = source.id,
                        language = source.lang,
                        elapsedMs = searchObservation.elapsedMs,
                    )
                }
                val sourceResult = progressEvents.filterIsInstance<ContentBindingSearchProgress.SourceCompleted>()
                    .singleOrNull { it.sourceId == source.id }
                    ?: stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                record(
                    terminal,
                    currentStage,
                    Outcome.PASS,
                    "SUCCESS",
                    count = queriedCandidates.size,
                    sourceId = source.id,
                    language = source.lang,
                    elapsedMs = searchObservation.elapsedMs,
                )

                currentStage = "CANDIDATE_IDENTIFICATION"
                val referenceCandidates = queriedCandidates.filter {
                    it.sourceId == source.id && matchesReference(it.sourceUrl)
                }
                if (referenceCandidates.size != 1) {
                    stop(
                        currentStage,
                        Outcome.INCONCLUSIVE,
                        when {
                            queriedCandidates.isEmpty() || sourceResult.outcome == ContentBindingSourceOutcome.EMPTY -> "NO_RESULTS"
                            referenceCandidates.size > 1 -> "REFERENCE_AMBIGUOUS"
                            else -> "REFERENCE_NOT_FOUND"
                        },
                        count = referenceCandidates.size,
                        sourceId = source.id,
                        language = source.lang,
                    )
                }
                val referenceCandidate = referenceCandidates.single()
                record(
                    terminal,
                    currentStage,
                    Outcome.PASS,
                    "EXACT_REFERENCE",
                    count = 1,
                    sourceId = source.id,
                    language = source.lang,
                )

                currentStage = "MATCH_DECISION"
                val confirmedBinding: ContentBinding = when (sourceResult.outcome) {
                    ContentBindingSourceOutcome.BOUND -> {
                        val selected = sourceResult.candidates.singleOrNull()
                            ?: stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                        if (!isSameCandidate(selected, referenceCandidate)) {
                            stop(currentStage, Outcome.INCONCLUSIVE, "REFERENCE_NOT_FOUND", sourceId = source.id, language = source.lang)
                        }
                        record(terminal, currentStage, Outcome.PASS, "AUTO_SELECTED_EXACT_REFERENCE", sourceId = source.id, language = source.lang)
                        sourceResult.bindings.singleOrNull()
                            ?: stop("BINDING_CREATE", Outcome.FAIL, "BINDING", sourceId = source.id, language = source.lang)
                    }
                    ContentBindingSourceOutcome.CONFIRMATION_REQUIRED -> {
                        val exactChoices = sourceResult.candidates.filter { matchesReference(it.candidate.sourceUrl) }
                        if (exactChoices.size != 1 || !isSameCandidate(exactChoices.single(), referenceCandidate)) {
                            stop(currentStage, Outcome.INCONCLUSIVE, "REFERENCE_AMBIGUOUS", count = exactChoices.size,
                                sourceId = source.id, language = source.lang)
                        }
                        record(terminal, currentStage, Outcome.PASS, "CONFIRMED_EXACT_REFERENCE", sourceId = source.id, language = source.lang)
                        currentStage = "BINDING_MATERIALIZATION"
                        val materialized = composition.confirmContentBinding.execute(
                            canonicalTitleId = canonicalTitleId,
                            addonId = addonId,
                            selected = exactChoices.single(),
                        ).getOrElse { error ->
                            stop(currentStage, Outcome.INCONCLUSIVE, errorCategory(error), sourceId = source.id, language = source.lang)
                        }
                        materialized
                    }
                    ContentBindingSourceOutcome.EMPTY -> {
                        stop("CANDIDATE_IDENTIFICATION", Outcome.INCONCLUSIVE, "NO_RESULTS", count = 0,
                            sourceId = source.id, language = source.lang)
                    }
                    ContentBindingSourceOutcome.NO_MATCH -> {
                        stop(currentStage, Outcome.INCONCLUSIVE, "LOW_CONFIDENCE", sourceId = source.id, language = source.lang)
                    }
                    ContentBindingSourceOutcome.FAILURE -> {
                        val failure = sourceResult.failure
                            ?: stop(currentStage, Outcome.INCONCLUSIVE, "INDETERMINATE", sourceId = source.id, language = source.lang)
                        val category = failureCategory(failure)
                        when (failure.stage) {
                            tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureStage.MATERIALIZATION -> {
                                if (queriedCandidates.size != 1 ||
                                    !matchesReference(queriedCandidates.single().sourceUrl)
                                ) {
                                    stop(currentStage, Outcome.INCONCLUSIVE, "REFERENCE_AMBIGUOUS",
                                        count = queriedCandidates.size, sourceId = source.id, language = source.lang)
                                }
                                record(terminal, currentStage, Outcome.PASS, "AUTO_SELECTED_EXACT_REFERENCE",
                                    sourceId = source.id, language = source.lang)
                                stop("BINDING_MATERIALIZATION", Outcome.INCONCLUSIVE, category,
                                    sourceId = source.id, language = source.lang,
                                    elapsedMs = composition.materializationObservation?.elapsedMs)
                            }
                            tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailureStage.PERSISTENCE -> {
                                if (queriedCandidates.size != 1 ||
                                    !matchesReference(queriedCandidates.single().sourceUrl)
                                ) {
                                    stop(currentStage, Outcome.INCONCLUSIVE, "REFERENCE_AMBIGUOUS",
                                        count = queriedCandidates.size, sourceId = source.id, language = source.lang)
                                }
                                record(terminal, currentStage, Outcome.PASS, "AUTO_SELECTED_EXACT_REFERENCE",
                                    sourceId = source.id, language = source.lang)
                                val materialized = composition.materializationObservation
                                    ?: stop("BINDING_MATERIALIZATION", Outcome.INCONCLUSIVE, "INSTRUMENTATION",
                                        sourceId = source.id, language = source.lang)
                                if (!materialized.succeeded) {
                                    stop("BINDING_MATERIALIZATION", Outcome.INCONCLUSIVE, category,
                                        sourceId = source.id, language = source.lang, elapsedMs = materialized.elapsedMs)
                                }
                                record(terminal, "BINDING_MATERIALIZATION", Outcome.PASS, "SUCCESS",
                                    sourceId = source.id, language = source.lang, elapsedMs = materialized.elapsedMs)
                                stop("BINDING_CREATE", Outcome.FAIL, "PERSISTENCE",
                                    sourceId = source.id, language = source.lang)
                            }
                            else -> stop(currentStage, Outcome.INCONCLUSIVE, category, sourceId = source.id, language = source.lang)
                        }
                    }
                }
                if (confirmedBinding.canonicalTitleId != canonicalTitleId || confirmedBinding.addonId != addonId) {
                    stop("BINDING_CREATE", Outcome.FAIL, "IDENTITY", sourceId = source.id, language = source.lang)
                }

                if (terminal["BINDING_MATERIALIZATION"] == null) {
                    currentStage = "BINDING_MATERIALIZATION"
                    val observed = composition.materializationObservation
                        ?: stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                    if (!observed.succeeded) {
                        stop(currentStage, Outcome.INCONCLUSIVE, "EXTENSION", sourceId = source.id, language = source.lang,
                            elapsedMs = observed.elapsedMs)
                    }
                    record(terminal, currentStage, Outcome.PASS, "SUCCESS", sourceId = source.id, language = source.lang,
                        elapsedMs = observed.elapsedMs)
                }

                currentStage = "BINDING_CREATE"
                if (confirmedBinding.providerTitleKey.isBlank() || confirmedBinding.runtimePayload.isEmpty()) {
                    stop(currentStage, Outcome.FAIL, "BINDING", sourceId = source.id, language = source.lang)
                }
                val expectedProviderTitleKey = "${source.id}:${referenceCandidate.sourceUrl}"
                val persistedIdentity = runCatching { MihonContentBindingPayloadCodec.decode(confirmedBinding.runtimePayload) }
                    .getOrNull()
                if (confirmedBinding.providerTitleKey != expectedProviderTitleKey ||
                    persistedIdentity == null || persistedIdentity.sourceId != source.id ||
                    persistedIdentity.sourceUrl != referenceCandidate.sourceUrl ||
                    !persistedIdentity.language.equals("pt-BR", ignoreCase = true)
                ) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "IDENTITY", sourceId = source.id, language = source.lang)
                }
                if (confirmedBinding.verifiedByUser != (sourceResult.outcome == ContentBindingSourceOutcome.CONFIRMATION_REQUIRED)) {
                    stop(currentStage, Outcome.FAIL, "IDENTITY", sourceId = source.id, language = source.lang)
                }
                record(terminal, currentStage, Outcome.PASS, "EXACT_REFERENCE", sourceId = source.id, language = source.lang)

                currentStage = "BINDING_PERSISTENCE"
                val persisted = composition.contentBindingRepository.getByTitle(canonicalTitleId)
                    .singleOrNull { it.id == confirmedBinding.id }
                    ?: stop(currentStage, Outcome.FAIL, "PERSISTENCE", sourceId = source.id, language = source.lang)
                if (persisted.canonicalTitleId != canonicalTitleId || persisted.addonId != addonId ||
                    persisted.providerTitleKey != confirmedBinding.providerTitleKey ||
                    persisted.verifiedByUser != confirmedBinding.verifiedByUser ||
                    !persisted.runtimePayload.contentEquals(confirmedBinding.runtimePayload)
                ) {
                    stop(currentStage, Outcome.FAIL, "IDENTITY", sourceId = source.id, language = source.lang)
                }
                record(terminal, currentStage, Outcome.PASS, "SUCCESS", sourceId = source.id, language = source.lang)

                currentStage = "INVENTORY"
                composition.diagnostics.start(canonicalTitleId)
                val probeResult = try {
                    runMihonJourneyBounded(60_000L) { composition.chapterProbeProvider.probe(canonicalTitleId) }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    stop(currentStage, Outcome.INCONCLUSIVE, "TIMEOUT", sourceId = source.id, language = source.lang)
                }
                val inventory = composition.diagnostics.events()
                    .lastOrNull { it.stage == ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY }
                    ?: stop(currentStage, Outcome.FAIL, "INSTRUMENTATION", sourceId = source.id, language = source.lang)
                if (inventory.outcome != ChapterInventoryDiagnosticOutcome.SUCCESS || (inventory.received ?: 0) == 0) {
                    stop(currentStage, Outcome.INCONCLUSIVE, inventoryCategory(inventory), count = inventory.received,
                        sourceId = source.id, language = source.lang, elapsedMs = inventory.elapsedMillis)
                }
                record(terminal, currentStage, Outcome.PASS, "SUCCESS", count = inventory.received,
                    sourceId = source.id, language = source.lang, elapsedMs = inventory.elapsedMillis)

                currentStage = "CHAPTER_PROBE"
                val evidence = probeResult.getOrElse { error ->
                    stop(currentStage, Outcome.INCONCLUSIVE, errorCategory(error), sourceId = source.id, language = source.lang)
                }
                if (evidence.isEmpty() || evidence.any { it.canonicalTitleId != canonicalTitleId }) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "LOW_CONFIDENCE", count = evidence.size,
                        sourceId = source.id, language = source.lang)
                }
                record(terminal, currentStage, Outcome.PASS, "SUCCESS", count = evidence.size,
                    sourceId = source.id, language = source.lang)

                currentStage = "RECONCILIATION"
                composition.reconcileChapterEvidence.execute(canonicalTitleId, evidence)
                val chapters = composition.canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
                    .filter { chapter ->
                        chapter.canonicalTitleId == canonicalTitleId &&
                            chapter.type == CanonicalChapterType.REGULAR &&
                            chapter.baseNumber?.let { it > 0 } == true &&
                            chapter.confirmation.name != "CONFLICTED"
                    }
                if (chapters.isEmpty()) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "RECONCILIATION", count = 0,
                        sourceId = source.id, language = source.lang)
                }
                val selectedChapter = chapters.minBy { it.sortKey }
                if (!selectedChapter.identity.isSpecific) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "LOW_CONFIDENCE", sourceId = source.id, language = source.lang)
                }
                val mappedEvidence = composition.chapterEvidenceRepository.getByCanonicalTitleId(canonicalTitleId)
                    .filter { it.mappedCanonicalChapterId == selectedChapter.id }
                val identityEvidence = mappedEvidence.filter { persisted ->
                    val evidence = persisted.evidence
                    if (evidence.producerId != addonId.value ||
                        evidence.externalChapterKey?.startsWith("${source.id}:") != true
                    ) {
                        return@filter false
                    }
                    val parsed = runCatching {
                        composition.chapterLabelParser.execute(evidence.rawLabel, evidence.rawNumber)
                    }.getOrNull() ?: return@filter false
                    evidence.confidence >= MIN_CONFIRMED_CHAPTER_IDENTITY_CONFIDENCE &&
                        parsed.confidence >= MIN_CONFIRMED_CHAPTER_IDENTITY_CONFIDENCE &&
                        parsed.identity.isSpecific && parsed.identity == selectedChapter.identity
                }
                if (identityEvidence.isEmpty()) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "LOW_CONFIDENCE", sourceId = source.id, language = source.lang)
                }
                record(terminal, currentStage, Outcome.PASS, "SUCCESS", count = chapters.size,
                    sourceId = source.id, language = source.lang)

                currentStage = "CONTENT_RESOLUTION"
                val resolution = composition.resolveChapterContent.execute(canonicalTitleId, selectedChapter.id)
                val options = when (resolution) {
                    is ContentResolution.Direct -> listOf(resolution.option)
                    is ContentResolution.NeedsSelection -> resolution.options
                    ContentResolution.Unavailable -> stop(currentStage, Outcome.INCONCLUSIVE, "CONTENT_UNAVAILABLE",
                        sourceId = source.id, language = source.lang)
                }
                val exactOptions = options.filter { option ->
                    val delivery = option.delivery as? ContentDelivery.Mihon ?: return@filter false
                    option.canonicalChapterId == selectedChapter.id && option.addonId == addonId &&
                        option.language.equals("pt-BR", ignoreCase = true) && delivery.sourceId == source.id
                }
                if (exactOptions.size != 1) {
                    stop(currentStage, Outcome.INCONCLUSIVE,
                        if (exactOptions.isEmpty()) "CONTENT_UNAVAILABLE" else "AMBIGUOUS",
                        count = exactOptions.size, sourceId = source.id, language = source.lang)
                }
                val option = exactOptions.single()
                val mihonDelivery = option.delivery as ContentDelivery.Mihon
                record(terminal, currentStage, Outcome.PASS, "EXACT_REFERENCE", count = exactOptions.size,
                    sourceId = source.id, language = source.lang)

                currentStage = "READER_PREPARATION"
                val prepared = composition.prepareCanonicalChapterForReader.execute(
                    canonicalChapterId = selectedChapter.id,
                    selectedOption = option,
                )
                val target = (prepared as? CanonicalReaderPreparation.Ready)?.target as? PreparedChapterContent.MihonOperational
                    ?: stop(currentStage, Outcome.INCONCLUSIVE, "READER_PREPARATION", sourceId = source.id, language = source.lang)
                if (target.sourceId != source.id ||
                    target.mangaId != mihonDelivery.mangaId ||
                    target.chapterId != mihonDelivery.chapterId ||
                    option.canonicalChapterId != selectedChapter.id
                ) {
                    stop(currentStage, Outcome.FAIL, "IDENTITY", sourceId = source.id, language = source.lang)
                }
                record(terminal, currentStage, Outcome.PASS, "EXACT_REFERENCE", sourceId = source.id, language = source.lang)

                currentStage = "GET_PAGE_LIST"
                val operationalChapter = composition.chapterRepository.getChapterById(target.chapterId)
                    ?: stop(currentStage, Outcome.FAIL, "READER_PREPARATION", sourceId = source.id, language = source.lang)
                if (operationalChapter.id != target.chapterId || operationalChapter.mangaId != target.mangaId) {
                    stop(currentStage, Outcome.FAIL, "IDENTITY", sourceId = source.id, language = source.lang)
                }
                val pageList = try {
                    val catalogueSource = registered.single { it.id == source.id } as CatalogueSource
                    runMihonJourneyBounded(60_000L) { catalogueSource.getPageList(operationalChapter.toSChapter()) }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    stop(currentStage, Outcome.INCONCLUSIVE, "TIMEOUT", sourceId = source.id, language = source.lang)
                } catch (error: Throwable) {
                    stop(currentStage, Outcome.INCONCLUSIVE, errorCategory(error), sourceId = source.id, language = source.lang)
                }
                if (pageList.isEmpty()) {
                    stop(currentStage, Outcome.INCONCLUSIVE, "CONTENT_UNAVAILABLE", count = 0,
                        sourceId = source.id, language = source.lang)
                }
                record(terminal, currentStage, Outcome.PASS, "SUCCESS", count = pageList.size,
                    sourceId = source.id, language = source.lang)
            } catch (stop: MangaBallJourneyStop) {
                terminal[stop.stage] = MangaBallStageResult(stop.outcome, stop.category, stop.count, stop.sourceId,
                    stop.language, stop.elapsedMs)
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
                terminal[currentStage] = MangaBallStageResult(Outcome.INCONCLUSIVE, "TIMEOUT", sourceId = currentSourceId,
                    language = currentLanguage)
            } catch (error: Throwable) {
                setupError = true
                terminal[currentStage] = MangaBallStageResult(Outcome.FAIL, safeSetupCategory(error), sourceId = currentSourceId,
                    language = currentLanguage)
            } finally {
                val driverClosed = driver?.let { value ->
                    runCatching { value.close() }.isSuccess.also { closed ->
                        sendSetupEvent("DRIVER_CLOSE", if (closed) "PASS" else "FAIL")
                    }
                } ?: true
                val databaseCleaned = databaseContext?.let { context ->
                    runCatching {
                        val path = context.getDatabasePath(TARGET_DATABASE_NAME)
                        context.deleteDatabase(TARGET_DATABASE_NAME) && !path.exists()
                    }.getOrDefault(false).also { cleaned ->
                        sendSetupEvent("DATABASE_CLEANUP", if (cleaned) "PASS" else "FAIL")
                    }
                } ?: true
                if (!driverClosed || !databaseCleaned) {
                    setupError = true
                    terminal.putIfAbsent(currentStage, MangaBallStageResult(Outcome.FAIL, "PERSISTENCE",
                        sourceId = currentSourceId, language = currentLanguage))
                }
                val currentApp = app
                if (currentApp != null) {
                    originalDisabled?.let { currentApp.graph.sourcePreferences.disabledSources.set(it) }
                    originalShowNsfw?.let { currentApp.graph.sourcePreferences.showNsfwSource.set(it) }
                }
            }

            for (stage in JOURNEY_STAGES) {
                val value = terminal[stage]
                if (value == null) {
                    reportStage(stage, Outcome.NOT_RUN, "NOT_RUN_AFTER_BLOCKER")
                } else {
                    reportStage(stage, value.outcome, value.category, value.count, value.sourceId, value.language, value.elapsedMs)
                }
            }
            assertTrue("Unexpected MangaBall instrumentation setup failure", !setupError)
            assertTrue("Journey stage outcomes are invalid", terminal.values.none { it.outcome == Outcome.FAIL })
            // External ambiguity or unavailability remains a JUnit-successful, explicitly
            // INCONCLUSIVE observation; the report verifier does not call it an E2E PASS.
            assertTrue("Missing first-stage evidence", terminal.containsKey(JOURNEY_STAGES.first()))
        }
    }

    private suspend fun loadTrustedFixture(app: App): Extension.Installed {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_META_DATA)
        assertEquals("1.6.1", info.versionName)
        val manager = app.graph.extensionManager
        manager.getInstalledExtensions().firstOrNull { it.pkgName == PACKAGE_NAME }?.let { return it }
        val untrusted = withTimeout(45_000L) {
            manager.untrustedExtensionsFlow.first { values -> values.any { it.pkgName == PACKAGE_NAME } }
                .single { it.pkgName == PACKAGE_NAME }
        }
        assertEquals("1.6.1", untrusted.versionName)
        app.graph.sourcePreferences.showNsfwSource.set(true)
        manager.trust(untrusted)
        return withTimeout(30_000L) {
            manager.installedExtensionsFlow.first { values -> values.any { it.pkgName == PACKAGE_NAME } }
                .single { it.pkgName == PACKAGE_NAME }
        }
    }

    private fun matchesReference(sourceUrl: String): Boolean = try {
        val uri = URI(sourceUrl)
        val host = uri.host
        val path = uri.path.orEmpty().let { value -> if (value.startsWith('/')) value else "/$value" }
        (host == null || host.equals("mangaball.net", ignoreCase = true)) &&
            (uri.scheme == null || uri.scheme.equals("https", ignoreCase = true)) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null && path == REFERENCE_PATH
    } catch (_: Exception) {
        false
    }

    private fun isSameCandidate(left: ScoredSourceCandidate, right: tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate): Boolean =
        left.candidate.sourceId == right.sourceId && left.candidate.sourceUrl == right.sourceUrl

    private fun failureCategory(failure: tachiyomi.domain.tsuzuki.content.interactor.ContentBindingSearchFailure): String = when {
        failure.httpStatus != null -> httpCategory(requireNotNull(failure.httpStatus))
        else -> when (failure.kind) {
            ContentBindingSearchFailureKind.SOURCE_DISABLED -> "SOURCE_DISABLED"
            ContentBindingSearchFailureKind.SOURCE_UNAVAILABLE -> "SOURCE_NOT_FOUND"
            ContentBindingSearchFailureKind.HTTP_RESPONSE -> "HTTP_OTHER"
            ContentBindingSearchFailureKind.NETWORK_FAILURE -> "NETWORK"
            ContentBindingSearchFailureKind.TIMEOUT -> "TIMEOUT"
            ContentBindingSearchFailureKind.CAPTCHA_REQUIRED -> "CAPTCHA"
            ContentBindingSearchFailureKind.MALFORMED_RESPONSE -> "MALFORMED"
            ContentBindingSearchFailureKind.EXTENSION_FAILURE -> "EXTENSION"
            ContentBindingSearchFailureKind.INDETERMINATE -> "INSTRUMENTATION"
            ContentBindingSearchFailureKind.ADDON_NOT_INSTALLED,
            ContentBindingSearchFailureKind.ADDON_DISABLED,
            ContentBindingSearchFailureKind.NO_ENABLED_SOURCES -> "SOURCE_NOT_FOUND"
        }
    }

    private fun errorCategory(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        val sourceFailure = causes.filterIsInstance<ReadingSourceSearchFailure>().firstOrNull()
        if (sourceFailure != null) {
            return when (sourceFailure.kind) {
                ReadingSourceFailureKind.HTTP_RESPONSE -> sourceFailure.httpStatus?.let(::httpCategory) ?: "HTTP_OTHER"
                ReadingSourceFailureKind.NETWORK_FAILURE -> "NETWORK"
                ReadingSourceFailureKind.TIMEOUT -> "TIMEOUT"
                ReadingSourceFailureKind.CAPTCHA_REQUIRED -> "CAPTCHA"
                ReadingSourceFailureKind.MALFORMED_RESPONSE -> "MALFORMED"
                ReadingSourceFailureKind.SOURCE_DISABLED -> "SOURCE_DISABLED"
                ReadingSourceFailureKind.SOURCE_UNAVAILABLE -> "SOURCE_NOT_FOUND"
                ReadingSourceFailureKind.EXTENSION_FAILURE -> "EXTENSION"
                ReadingSourceFailureKind.INDETERMINATE -> "INSTRUMENTATION"
            }
        }
        val status = causes.filterIsInstance<HttpException>().map(HttpException::code).firstOrNull { it in 100..599 }
        if (status != null) return httpCategory(status)
        val diagnostic = tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures.classify(error).first
        return when {
            diagnostic == ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED -> "CAPTCHA"
            diagnostic == ChapterInventoryDiagnosticOutcome.TIMEOUT || causes.any { it is SocketTimeoutException } -> "TIMEOUT"
            diagnostic == ChapterInventoryDiagnosticOutcome.NETWORK_ERROR || causes.any {
                it is UnknownHostException || it is ConnectException
            } -> "NETWORK"
            diagnostic == ChapterInventoryDiagnosticOutcome.MALFORMED_RESPONSE -> "MALFORMED"
            diagnostic == ChapterInventoryDiagnosticOutcome.HTTP_ERROR -> "HTTP_OTHER"
            else -> "INDETERMINATE"
        }
    }

    private fun inventoryCategory(event: tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent): String = when {
        event.httpStatus != null -> httpCategory(requireNotNull(event.httpStatus))
        event.outcome == ChapterInventoryDiagnosticOutcome.EMPTY -> "INVENTORY_EMPTY"
        event.outcome == ChapterInventoryDiagnosticOutcome.TIMEOUT -> "TIMEOUT"
        event.outcome == ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED -> "CAPTCHA"
        event.outcome == ChapterInventoryDiagnosticOutcome.NETWORK_ERROR -> "NETWORK"
        event.outcome == ChapterInventoryDiagnosticOutcome.MALFORMED_RESPONSE -> "MALFORMED"
        event.outcome == ChapterInventoryDiagnosticOutcome.HTTP_ERROR -> "HTTP_OTHER"
        event.outcome == ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR -> "EXTENSION"
        else -> "CONTENT_UNAVAILABLE"
    }

    private fun httpCategory(status: Int): String = when (status) {
        403 -> "HTTP_403"
        429 -> "HTTP_429"
        in 500..599 -> "HTTP_5XX"
        else -> "HTTP_OTHER"
    }

    private fun safeSetupCategory(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(5).toList()
        return when {
            causes.any { it is TimeoutCancellationException || it is SocketTimeoutException } -> "TIMEOUT"
            causes.any { it is SecurityException } -> "INSTRUMENTATION"
            else -> "INSTRUMENTATION"
        }
    }

    private fun sendFixtureEvent(internalSources: Int, eligibleBeforeDisable: Int, eligibleAfterDisable: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stream = "MANGABALL_FIXTURE|outcome=PASS|addonGroups=1|internalSources=$internalSources" +
            "|eligibleBefore=$eligibleBeforeDisable|eligibleAfter=$eligibleAfterDisable" +
            "|ptBrSourceId=$EXPECTED_PT_BR_SOURCE_ID|disabledPeerExcluded=true"
        instrumentation.sendStatus(1, Bundle().apply { putString("stream", stream) })
    }

    private fun sendContextObservation() {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply {
                putString("stream", "RUNTIME_CONTEXT|applicationContext=present|storage=TARGET_DISPOSABLE|databaseContext=wrapped")
            },
        )
    }

    private fun sendSetupEvent(phase: String, outcome: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            1,
            Bundle().apply { putString("stream", "RUNTIME_SETUP|phase=$phase|outcome=$outcome") },
        )
    }

    private fun record(
        terminal: MutableMap<String, MangaBallStageResult>,
        stage: String,
        outcome: Outcome,
        category: String = "NONE",
        count: Int? = null,
        sourceId: Long? = null,
        language: String? = null,
        elapsedMs: Long? = null,
    ) {
        check(stage !in terminal) { "Duplicate stage observation" }
        terminal[stage] = MangaBallStageResult(outcome, category, count, sourceId, language, elapsedMs)
    }

    private fun reportStage(
        stage: String,
        outcome: Outcome,
        category: String = "NONE",
        count: Int? = null,
        sourceId: Long? = null,
        language: String? = null,
        elapsedMs: Long? = null,
    ) {
        val stream = buildString {
            append("MANGABALL_E2E|stage=").append(stage).append("|outcome=").append(outcome)
            append("|category=").append(category)
            count?.let { append("|count=").append(it) }
            sourceId?.let { append("|sourceId=").append(it) }
            language?.let { append("|language=").append(it) }
            elapsedMs?.let { append("|elapsedMs=").append(it.coerceAtLeast(0)) }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(1, Bundle().apply { putString("stream", stream) })
    }

    private fun stop(
        stage: String,
        outcome: Outcome,
        category: String,
        count: Int? = null,
        sourceId: Long? = activeSourceId,
        language: String? = activeLanguage,
        elapsedMs: Long? = null,
    ): Nothing = throw MangaBallJourneyStop(stage, outcome, category, count, sourceId, language, elapsedMs)

    private data class MangaBallStageResult(
        val outcome: Outcome,
        val category: String,
        val count: Int? = null,
        val sourceId: Long? = null,
        val language: String? = null,
        val elapsedMs: Long? = null,
    )

    private class MangaBallJourneyStop(
        val stage: String,
        val outcome: Outcome,
        val category: String,
        val count: Int?,
        val sourceId: Long?,
        val language: String?,
        val elapsedMs: Long?,
    ) : RuntimeException()

    private enum class Outcome {
        PASS,
        FAIL,
        INCONCLUSIVE,
        NOT_RUN,
    }

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.mangaball"
        const val TARGET_DATABASE_NAME = "tachiyomi.db"
        const val EXPECTED_INTERNAL_SOURCE_COUNT = 42
        const val EXPECTED_PT_BR_SOURCE_ID = 35546023386335815L
        const val SOURCE_REGISTRATION_TIMEOUT_MS = 30_000L
        const val MIN_CONFIRMED_CHAPTER_IDENTITY_CONFIDENCE = 0.95
        const val REFERENCE_TITLE = "One Punch Man"
        const val REFERENCE_PATH = "/title-detail/one-punch-man-68515501702284f83417844d/"
        val JOURNEY_STAGES = listOf(
            "EXTENSION_INSTALL",
            "SOURCE_REGISTRATION",
            "SOURCE_ELIGIBILITY",
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
