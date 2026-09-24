package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.TimeSource

class ResolveContentBinding internal constructor(
    private val contentBindingRepository: ContentBindingRepository,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val addonRepository: AddonRepository,
    private val readingSourceGateway: ReadingSourceGateway,
    private val scoreSourceTitleMatch: ScoreSourceTitleMatch,
    private val idFactory: () -> String,
    private val clock: () -> Long,
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
    private val addonSourceEligibilityRepository: AddonSourceEligibilityRepository? = null,
) {

    @Inject
    constructor(
        contentBindingRepository: ContentBindingRepository,
        canonicalTitleRepository: CanonicalTitleRepository,
        addonRepository: AddonRepository,
        readingSourceGateway: ReadingSourceGateway,
        scoreSourceTitleMatch: ScoreSourceTitleMatch,
        addonSourceEligibilityRepository: AddonSourceEligibilityRepository,
        diagnostics: ChapterInventoryDiagnostics,
    ) : this(
        contentBindingRepository = contentBindingRepository,
        canonicalTitleRepository = canonicalTitleRepository,
        addonRepository = addonRepository,
        readingSourceGateway = readingSourceGateway,
        scoreSourceTitleMatch = scoreSourceTitleMatch,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
        diagnostics = diagnostics,
        addonSourceEligibilityRepository = addonSourceEligibilityRepository,
    )

    suspend fun execute(
        canonicalTitleId: String,
        addonId: AddonId,
    ): Result<ContentBinding> {
        return executeAll(canonicalTitleId, addonId).mapCatching { bindings ->
            bindings.firstOrNull()
                ?: throw ContentBindingNotFoundException(
                    "No content binding found for " + addonId.value,
                )
        }
    }

    /**
     * Resolves every unambiguous internal Mihon Source exposed by one product Add-on.
     *
     * A multi-source extension (for example MangaDex with several languages) is one
     * Tsuzuki Add-on. Equal title matches from different internal Sources are not an
     * ambiguity; each Source may materialize its own provider-neutral ContentBinding.
     */
    suspend fun executeAll(
        canonicalTitleId: String,
        addonId: AddonId,
    ): Result<List<ContentBinding>> {
        return try {
            Result.success(resolveAll(canonicalTitleId, addonId))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Search one bounded batch of installed, enabled sources and emit results as each source
     * finishes. [ContentBindingSearchMode.BROADEN] is an explicit caller action; this resolver
     * never fans out across every source unless the caller requests successive batches. The cold
     * upstream runs on IO because Mihon CatalogueSource calls may block despite the suspend gateway.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun searchProgress(request: ContentBindingSearchRequest): Flow<ContentBindingSearchProgress> = flow {
        val addon = addonRepository.snapshot().firstOrNull { it.id == request.addonId }
        if (addon == null) {
            emitFailure(
                sourceId = null,
                stage = ContentBindingSearchFailureStage.ADDON_DISCOVERY,
                kind = ContentBindingSearchFailureKind.ADDON_NOT_INSTALLED,
            )
            emit(ContentBindingSearchProgress.Completed(emptyList(), 0))
            return@flow
        }

        val enabledSourceIds = addon.mihonSourceIds.distinct()
        if (!addon.enabled || enabledSourceIds.isEmpty()) {
            emitFailure(
                sourceId = null,
                stage = ContentBindingSearchFailureStage.ADDON_DISCOVERY,
                kind = if (addon.enabled) {
                    ContentBindingSearchFailureKind.NO_ENABLED_SOURCES
                } else {
                    ContentBindingSearchFailureKind.ADDON_DISABLED
                },
            )
            emit(ContentBindingSearchProgress.Completed(emptyList(), 0))
            return@flow
        }

        val existingBindings = contentBindingRepository.getByTitle(request.canonicalTitleId)
            .filter {
                it.addonId == request.addonId &&
                    it.availability != ContentBindingAvailability.UNAVAILABLE
            }
        if (existingBindings.isNotEmpty()) {
            emit(ContentBindingSearchProgress.ExistingBindingsObserved(existingBindings.size))
        }

        val canonicalTitle = canonicalTitleRepository.getById(request.canonicalTitleId)
        if (canonicalTitle == null) {
            emitFailure(
                sourceId = null,
                stage = ContentBindingSearchFailureStage.ADDON_DISCOVERY,
                kind = ContentBindingSearchFailureKind.INDETERMINATE,
            )
            emit(ContentBindingSearchProgress.Completed(emptyList(), 0))
            return@flow
        }

        val (orderedSourceIds, sourceLanguages) = orderSources(
            enabledSourceIds = enabledSourceIds,
            preferredLanguages = request.preferredLanguages,
        )
        val remainingSourceIds = orderedSourceIds.filterNot { it in request.alreadyQueriedSourceIds }
        val batch = remainingSourceIds.take(request.batchSize)
        val remainingCount = remainingSourceIds.size - batch.size
        if (batch.isEmpty()) {
            emit(ContentBindingSearchProgress.Completed(emptyList(), remainingCount))
            return@flow
        }

        val gate = Semaphore(MAX_CONCURRENT_SOURCE_SEARCHES)
        val persistenceLock = Mutex()
        channelFlow {
            batch.forEachIndexed { sourceRank, sourceId ->
                launch {
                    gate.withPermit {
                        val event = resolveProgressiveSource(
                            request = request,
                            sourceId = sourceId,
                            sourceRank = sourceRank,
                            language = sourceLanguages[sourceId],
                            canonicalTitle = canonicalTitle,
                            persistenceLock = persistenceLock,
                        )
                        send(event)
                    }
                }
            }
        }.collect { event -> emit(event) }

        emit(
            ContentBindingSearchProgress.Completed(
                queriedSourceIds = batch,
                remainingSourceCount = remainingCount,
            ),
        )
    }.flowOn(Dispatchers.IO)

    private suspend fun resolveProgressiveSource(
        request: ContentBindingSearchRequest,
        sourceId: Long,
        sourceRank: Int,
        language: String?,
        canonicalTitle: tachiyomi.domain.tsuzuki.model.CanonicalTitle,
        persistenceLock: Mutex,
    ): ContentBindingSearchProgress.SourceCompleted {
        val (candidates, searchFailure) = try {
            withTimeout(request.sourceTimeoutMillis) {
                searchSourceTitle(
                    canonicalTitleId = request.canonicalTitleId,
                    addonId = request.addonId,
                    sourceId = sourceId,
                    title = canonicalTitle.displayTitle,
                )
            }
        } catch (_: TimeoutCancellationException) {
            recordBinding(
                request.canonicalTitleId,
                request.addonId,
                ChapterInventoryDiagnosticStage.BINDING_SEARCH,
                ChapterInventoryDiagnosticOutcome.TIMEOUT,
                sourceId = sourceId,
                language = language,
                reason = ChapterInventoryDiagnosticReason.TIMEOUT_FAILURE,
            )
            return failedSource(
                sourceId,
                language,
                ContentBindingSearchFailureStage.SEARCH,
                ContentBindingSearchFailureKind.TIMEOUT,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return failedSource(
                sourceId,
                language,
                ContentBindingSearchFailureStage.SEARCH,
                classifyFailure(error),
                (error as? ReadingSourceSearchFailure)?.httpStatus,
            )
        }

        if (searchFailure != null && candidates.isEmpty()) {
            return failedSource(
                sourceId,
                language,
                ContentBindingSearchFailureStage.SEARCH,
                classifyFailure(searchFailure),
                (searchFailure as? ReadingSourceSearchFailure)?.httpStatus,
            )
        }

        if (candidates.any { it.sourceId != sourceId }) {
            return failedSource(
                sourceId,
                language,
                ContentBindingSearchFailureStage.SEARCH,
                ContentBindingSearchFailureKind.MALFORMED_RESPONSE,
            )
        }

        val scored = scoreCandidates(canonicalTitle.displayTitle, sourceRank, candidates)
        val decision = decideCandidates(scored)
        if (searchFailure != null && decision !is CandidateDecision.Confirmation) {
            return failedSource(
                sourceId,
                language,
                ContentBindingSearchFailureStage.SEARCH,
                classifyFailure(searchFailure),
                (searchFailure as? ReadingSourceSearchFailure)?.httpStatus,
            )
        }

        return when (decision) {
            CandidateDecision.Empty -> {
                recordBinding(
                    request.canonicalTitleId,
                    request.addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATCH,
                    ChapterInventoryDiagnosticOutcome.EMPTY,
                    sourceId = sourceId,
                    language = language,
                    reason = ChapterInventoryDiagnosticReason.NO_SEARCH_RESULTS,
                )
                ContentBindingSearchProgress.SourceCompleted(
                    sourceId = sourceId,
                    language = language,
                    outcome = ContentBindingSourceOutcome.EMPTY,
                )
            }
            CandidateDecision.BelowThreshold -> {
                recordBinding(
                    request.canonicalTitleId,
                    request.addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATCH,
                    ChapterInventoryDiagnosticOutcome.NO_MATCH,
                    sourceId = sourceId,
                    received = candidates.size,
                    reason = ChapterInventoryDiagnosticReason.MATCH_BELOW_THRESHOLD,
                )
                ContentBindingSearchProgress.SourceCompleted(
                    sourceId = sourceId,
                    language = language,
                    outcome = ContentBindingSourceOutcome.NO_MATCH,
                )
            }
            is CandidateDecision.Confirmation -> {
                val diagnosticOutcome = if (decision.reason == ChapterInventoryDiagnosticReason.AMBIGUOUS_CANDIDATES) {
                    ChapterInventoryDiagnosticOutcome.AMBIGUOUS
                } else {
                    ChapterInventoryDiagnosticOutcome.PARTIAL
                }
                recordBinding(
                    request.canonicalTitleId,
                    request.addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATCH,
                    diagnosticOutcome,
                    sourceId = sourceId,
                    language = language ?: decision.candidates.firstOrNull()?.candidate?.language,
                    received = candidates.size,
                    reason = decision.reason,
                )
                ContentBindingSearchProgress.SourceCompleted(
                    sourceId = sourceId,
                    language = language ?: decision.candidates.firstOrNull()?.candidate?.language,
                    outcome = ContentBindingSourceOutcome.CONFIRMATION_REQUIRED,
                    candidates = decision.candidates,
                    failure = searchFailure?.let {
                        ContentBindingSearchFailure(
                            stage = ContentBindingSearchFailureStage.SEARCH,
                            kind = classifyFailure(it),
                            httpStatus = (it as? ReadingSourceSearchFailure)?.httpStatus,
                        )
                    },
                )
            }
            is CandidateDecision.Automatic -> {
                val selected = decision.candidate
                recordBinding(
                    request.canonicalTitleId,
                    request.addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATCH,
                    ChapterInventoryDiagnosticOutcome.SUCCESS,
                    sourceId = sourceId,
                    language = selected.candidate.language,
                    received = candidates.size,
                    accepted = 1,
                )
                val materialized = try {
                    withTimeout(request.sourceTimeoutMillis) {
                        materializeCandidate(selected.candidate)
                    }
                } catch (_: TimeoutCancellationException) {
                    recordBinding(
                        request.canonicalTitleId,
                        request.addonId,
                        ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                        ChapterInventoryDiagnosticOutcome.TIMEOUT,
                        sourceId = sourceId,
                        language = selected.candidate.language,
                        reason = ChapterInventoryDiagnosticReason.TIMEOUT_FAILURE,
                    )
                    return failedSource(
                        sourceId,
                        selected.candidate.language,
                        ContentBindingSearchFailureStage.MATERIALIZATION,
                        ContentBindingSearchFailureKind.TIMEOUT,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val (outcome, failureReason) = ChapterInventoryDiagnosticFailures.classify(error)
                    recordBinding(
                        request.canonicalTitleId,
                        request.addonId,
                        ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                        outcome,
                        sourceId = sourceId,
                        language = selected.candidate.language,
                        reason = if (outcome == ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR) {
                            ChapterInventoryDiagnosticReason.MATERIALIZATION_FAILED
                        } else {
                            failureReason
                        },
                    )
                    return failedSource(
                        sourceId,
                        selected.candidate.language,
                        ContentBindingSearchFailureStage.MATERIALIZATION,
                        classifyFailure(error),
                        (error as? ReadingSourceSearchFailure)?.httpStatus,
                    )
                }

                val binding = try {
                    persistenceLock.lock()
                    try {
                        currentCoroutineContext().ensureActive()
                        val current = contentBindingRepository.getByTitle(request.canonicalTitleId)
                            .firstOrNull {
                                it.canonicalTitleId == request.canonicalTitleId &&
                                    it.addonId == request.addonId &&
                                    it.providerTitleKey == materialized.providerTitleKey
                            }
                        val value = createBinding(
                            canonicalTitleId = request.canonicalTitleId,
                            addonId = request.addonId,
                            scored = selected,
                            materialized = materialized,
                            existing = current,
                        )
                        currentCoroutineContext().ensureActive()
                        try {
                            contentBindingRepository.upsert(value)
                            value
                        } catch (upsertError: CancellationException) {
                            throw upsertError
                        } catch (upsertError: Throwable) {
                            if (current != null) throw upsertError

                            val concurrentlyPersisted = try {
                                contentBindingRepository.getByTitle(request.canonicalTitleId)
                                    .firstOrNull {
                                        it.canonicalTitleId == request.canonicalTitleId &&
                                            it.addonId == request.addonId &&
                                            it.providerTitleKey == materialized.providerTitleKey
                                    }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                null
                            }
                            concurrentlyPersisted ?: throw upsertError
                        }
                    } finally {
                        persistenceLock.unlock()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordBinding(
                        request.canonicalTitleId,
                        request.addonId,
                        ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                        ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR,
                        sourceId = sourceId,
                        language = selected.candidate.language,
                        reason = ChapterInventoryDiagnosticReason.BINDING_PERSISTENCE_FAILED,
                    )
                    return failedSource(
                        sourceId,
                        selected.candidate.language,
                        ContentBindingSearchFailureStage.PERSISTENCE,
                        classifyFailure(error),
                    )
                }

                recordBinding(
                    request.canonicalTitleId,
                    request.addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                    ChapterInventoryDiagnosticOutcome.SUCCESS,
                    sourceId = sourceId,
                    language = selected.candidate.language,
                    accepted = 1,
                )

                ContentBindingSearchProgress.SourceCompleted(
                    sourceId = sourceId,
                    language = selected.candidate.language,
                    outcome = ContentBindingSourceOutcome.BOUND,
                    candidates = listOf(selected),
                    bindings = listOf(binding),
                )
            }
        }
    }

    private fun failedSource(
        sourceId: Long,
        language: String?,
        stage: ContentBindingSearchFailureStage,
        kind: ContentBindingSearchFailureKind,
        httpStatus: Int? = null,
    ) = ContentBindingSearchProgress.SourceCompleted(
        sourceId = sourceId,
        language = language,
        outcome = ContentBindingSourceOutcome.FAILURE,
        failure = ContentBindingSearchFailure(stage, kind, httpStatus),
    )

    private suspend fun FlowCollector<ContentBindingSearchProgress>.emitFailure(
        sourceId: Long?,
        stage: ContentBindingSearchFailureStage,
        kind: ContentBindingSearchFailureKind,
    ) {
        emit(
            ContentBindingSearchProgress.SourceCompleted(
                sourceId = sourceId,
                language = null,
                outcome = ContentBindingSourceOutcome.FAILURE,
                failure = ContentBindingSearchFailure(stage, kind),
            ),
        )
    }

    private suspend fun orderSources(
        enabledSourceIds: List<Long>,
        preferredLanguages: List<String>,
    ): Pair<List<Long>, Map<Long, String>> {
        val allowed = enabledSourceIds.toSet()
        val preferredIds = linkedMapOf<Long, String>()
        for (language in preferredLanguages.distinct().filter(String::isNotBlank)) {
            val installed = try {
                readingSourceGateway.listInstalled(language)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            installed.filter { it.sourceId in allowed }.forEach { descriptor ->
                preferredIds.putIfAbsent(descriptor.sourceId, descriptor.language)
            }
        }
        val ordered = preferredIds.keys.toList() + enabledSourceIds.filterNot { it in preferredIds }
        return ordered.distinct() to preferredIds
    }

    private fun classifyFailure(error: Throwable): ContentBindingSearchFailureKind {
        val sourceFailure = generateSequence(error) { it.cause }
            .filterIsInstance<ReadingSourceSearchFailure>()
            .firstOrNull()
        if (sourceFailure != null) return sourceFailure.kind.toContentBindingFailureKind()
        return when (ChapterInventoryDiagnosticFailures.classify(error).first) {
            ChapterInventoryDiagnosticOutcome.DISABLED -> ContentBindingSearchFailureKind.SOURCE_DISABLED
            ChapterInventoryDiagnosticOutcome.SOURCE_UNAVAILABLE -> ContentBindingSearchFailureKind.SOURCE_UNAVAILABLE
            ChapterInventoryDiagnosticOutcome.HTTP_ERROR -> ContentBindingSearchFailureKind.HTTP_RESPONSE
            ChapterInventoryDiagnosticOutcome.NETWORK_ERROR -> ContentBindingSearchFailureKind.NETWORK_FAILURE
            ChapterInventoryDiagnosticOutcome.TIMEOUT -> ContentBindingSearchFailureKind.TIMEOUT
            ChapterInventoryDiagnosticOutcome.CAPTCHA_REQUIRED -> ContentBindingSearchFailureKind.CAPTCHA_REQUIRED
            ChapterInventoryDiagnosticOutcome.MALFORMED_RESPONSE -> ContentBindingSearchFailureKind.MALFORMED_RESPONSE
            ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR -> ContentBindingSearchFailureKind.EXTENSION_FAILURE
            else -> ContentBindingSearchFailureKind.INDETERMINATE
        }
    }

    private fun scoreCandidates(
        canonicalTitle: String,
        sourceRank: Int,
        candidates: List<ReadingSourceCandidate>,
    ): List<ScoredSourceCandidate> = candidates.map { candidate ->
        ScoredSourceCandidate(
            candidate = candidate,
            confidence = scoreSourceTitleMatch(
                targetTitle = canonicalTitle,
                candidateTitle = candidate.title,
            ),
            sourcePreferenceRank = sourceRank,
        )
    }.sortedByDescending { it.confidence }

    private fun decideCandidates(candidates: List<ScoredSourceCandidate>): CandidateDecision {
        val best = candidates.firstOrNull() ?: return CandidateDecision.Empty
        val second = candidates.getOrNull(1)
        val unambiguous = second == null || best.confidence - second.confidence > AUTO_MATCH_MARGIN
        if (best.confidence < CONFIRMATION_THRESHOLD) return CandidateDecision.BelowThreshold
        if (best.confidence >= AUTO_MATCH_THRESHOLD && unambiguous) {
            return CandidateDecision.Automatic(best)
        }
        return CandidateDecision.Confirmation(
            candidates = candidates
                .filter { it.confidence >= CONFIRMATION_THRESHOLD }
                .take(MAX_CONFIRMATION_CANDIDATES),
            reason = if (unambiguous) {
                ChapterInventoryDiagnosticReason.BINDING_CONFIRMATION_REQUIRED
            } else {
                ChapterInventoryDiagnosticReason.AMBIGUOUS_CANDIDATES
            },
        )
    }

    private suspend fun materializeCandidate(candidate: ReadingSourceCandidate) =
        readingSourceGateway.materialize(candidate).getOrThrow().also { materialized ->
            require(materialized.sourceId == candidate.sourceId) {
                "Materialized source does not match selected candidate"
            }
            require(materialized.sourceUrl == candidate.sourceUrl) {
                "Materialized URL does not match selected candidate"
            }
            require(materialized.runtimePayload.isNotEmpty()) {
                "Materialized binding must include provider runtime payload"
            }
        }

    private fun createBinding(
        canonicalTitleId: String,
        addonId: AddonId,
        scored: ScoredSourceCandidate,
        materialized: MaterializedReadingSource,
        existing: ContentBinding?,
        timestamp: Long = clock(),
    ): ContentBinding = ContentBinding(
            id = existing?.id ?: idFactory(),
            canonicalTitleId = canonicalTitleId,
            addonId = addonId,
            providerTitleKey = materialized.providerTitleKey,
            matchConfidence = scored.confidence,
            verifiedByUser = existing?.verifiedByUser ?: false,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = materialized.runtimePayload,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
        )

    private sealed interface CandidateDecision {
        data object Empty : CandidateDecision
        data object BelowThreshold : CandidateDecision
        data class Automatic(val candidate: ScoredSourceCandidate) : CandidateDecision
        data class Confirmation(
            val candidates: List<ScoredSourceCandidate>,
            val reason: ChapterInventoryDiagnosticReason,
        ) : CandidateDecision
    }

    private suspend fun resolveAll(
        canonicalTitleId: String,
        addonId: AddonId,
    ): List<ContentBinding> {
        val addon = requireExecutableAddon(canonicalTitleId, addonId)
        val existingBindings = contentBindingRepository
            .getByTitle(canonicalTitleId)
            .filter { it.addonId == addonId }
        val availableBindings = existingBindings
            .filter { it.availability != ContentBindingAvailability.UNAVAILABLE }
        if (availableBindings.isNotEmpty()) {
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.BINDING_MATCH,
                ChapterInventoryDiagnosticOutcome.SUCCESS,
                accepted = availableBindings.size,
                reason = ChapterInventoryDiagnosticReason.REUSED_BINDING,
            )
            return availableBindings.sortedWith(
                compareBy<ContentBinding>({ it.createdAt }, { it.id }),
            )
        }

        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: throw ContentBindingNotFoundException(
                "Canonical title $canonicalTitleId does not exist",
            )

        val selected = mutableListOf<ScoredSourceCandidate>()
        val confirmationCandidates = mutableListOf<ScoredSourceCandidate>()
        val searchGate = Semaphore(MAX_CONCURRENT_SOURCE_SEARCHES)

        val sourceIds = addon.mihonSourceIds.distinct()
        val candidatesBySource = coroutineScope {
            sourceIds
                .mapIndexed { sourceRank, sourceId ->
                    async {
                        val (results, failure) = searchGate.withPermit {
                            searchSourceTitle(canonicalTitleId, addonId, sourceId, canonicalTitle.displayTitle)
                        }
                        Triple(
                            sourceRank,
                            scoreCandidates(canonicalTitle.displayTitle, sourceRank, results),
                            failure,
                        )
                    }
                }
                .awaitAll()
        }
        var firstSearchFailure: Throwable? = null
        var firstSearchFailureSourceId: Long? = null
        var hasLowConfidenceMatch = false

        for ((rank, candidates, failure) in candidatesBySource) {
            val sourceId = sourceIds[rank]
            if (firstSearchFailure == null && failure != null) {
                firstSearchFailure = failure
                firstSearchFailureSourceId = sourceId
            }
            when (val decision = decideCandidates(candidates)) {
                CandidateDecision.Empty -> {
                    if (failure == null) {
                        recordBinding(
                            canonicalTitleId,
                            addonId,
                            ChapterInventoryDiagnosticStage.BINDING_MATCH,
                            ChapterInventoryDiagnosticOutcome.NO_MATCH,
                            sourceId = sourceId,
                            reason = ChapterInventoryDiagnosticReason.NO_SEARCH_RESULTS,
                        )
                    }
                }
                CandidateDecision.BelowThreshold -> {
                    hasLowConfidenceMatch = true
                    recordBinding(
                        canonicalTitleId,
                        addonId,
                        ChapterInventoryDiagnosticStage.BINDING_MATCH,
                        ChapterInventoryDiagnosticOutcome.NO_MATCH,
                        sourceId = sourceId,
                        language = candidates.firstOrNull()?.candidate?.language,
                        received = candidates.size,
                        reason = ChapterInventoryDiagnosticReason.MATCH_BELOW_THRESHOLD,
                    )
                }
                is CandidateDecision.Automatic -> {
                    val best = decision.candidate
                    selected += best
                    recordBinding(
                        canonicalTitleId,
                        addonId,
                        ChapterInventoryDiagnosticStage.BINDING_MATCH,
                        ChapterInventoryDiagnosticOutcome.SUCCESS,
                        sourceId = sourceId,
                        language = best.candidate.language,
                        received = candidates.size,
                        accepted = 1,
                    )
                }
                is CandidateDecision.Confirmation -> {
                    val best = candidates.first()
                    val reason = decision.reason
                    val outcome = when (reason) {
                        ChapterInventoryDiagnosticReason.AMBIGUOUS_CANDIDATES ->
                            ChapterInventoryDiagnosticOutcome.AMBIGUOUS
                        ChapterInventoryDiagnosticReason.MATCH_BELOW_THRESHOLD ->
                            ChapterInventoryDiagnosticOutcome.NO_MATCH
                        else -> ChapterInventoryDiagnosticOutcome.PARTIAL
                    }
                    recordBinding(
                        canonicalTitleId,
                        addonId,
                        ChapterInventoryDiagnosticStage.BINDING_MATCH,
                        outcome,
                        sourceId = sourceId,
                        language = best.candidate.language,
                        received = candidates.size,
                        reason = reason,
                    )
                    confirmationCandidates += decision.candidates
                }
            }
        }

        if (selected.isEmpty()) {
            val candidates = confirmationCandidates
                .sortedWith(
                    compareBy<ScoredSourceCandidate> { it.sourcePreferenceRank }
                        .thenByDescending { it.confidence },
                )
                .take(MAX_CONFIRMATION_CANDIDATES)
            val (blockOutcome, blockReason) = when {
                candidates.isNotEmpty() ->
                    ChapterInventoryDiagnosticOutcome.AMBIGUOUS to
                        ChapterInventoryDiagnosticReason.BINDING_CONFIRMATION_REQUIRED
                hasLowConfidenceMatch ->
                    ChapterInventoryDiagnosticOutcome.NO_MATCH to
                        ChapterInventoryDiagnosticReason.MATCH_BELOW_THRESHOLD
                firstSearchFailure != null -> ChapterInventoryDiagnosticFailures.classify(firstSearchFailure)
                else ->
                    ChapterInventoryDiagnosticOutcome.NO_MATCH to
                        ChapterInventoryDiagnosticReason.NO_SEARCH_RESULTS
            }
            val blockingStage = if (firstSearchFailure != null && candidates.isEmpty() &&
                !hasLowConfidenceMatch
            ) {
                ChapterInventoryDiagnosticStage.BINDING_SEARCH
            } else {
                ChapterInventoryDiagnosticStage.BINDING_MATCH
            }
            val affectedSourceCount = if (blockingStage == ChapterInventoryDiagnosticStage.BINDING_SEARCH) {
                candidatesBySource.count { it.third != null }
            } else {
                sourceIds.size
            }
            recordBinding(
                canonicalTitleId,
                addonId,
                blockingStage,
                blockOutcome,
                received = sourceIds.size,
                sourceId = firstSearchFailureSourceId.takeIf {
                    blockingStage == ChapterInventoryDiagnosticStage.BINDING_SEARCH
                },
                affectedSourceCount = affectedSourceCount,
                httpStatus = (firstSearchFailure as? ReadingSourceSearchFailure)?.httpStatus
                    .takeIf { blockingStage == ChapterInventoryDiagnosticStage.BINDING_SEARCH },
                availabilityBlocked = true,
                reason = blockReason,
            )
            if (candidates.isNotEmpty()) {
                throw ContentBindingConfirmationRequiredException(candidates)
            }
            firstSearchFailure?.let { throw ContentBindingSourceSearchException(it) }
            throw ContentBindingNotFoundException(
                "No sufficiently confident title candidate found for " + addonId.value,
            )
        }

        val bindings = mutableListOf<ContentBinding>()
        for (scored in selected) {
            val materialized = try {
                materializeCandidate(scored.candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val (outcome, failureReason) = ChapterInventoryDiagnosticFailures.classify(error)
                recordBinding(
                    canonicalTitleId,
                    addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                    outcome,
                    sourceId = scored.candidate.sourceId,
                    language = scored.candidate.language,
                    reason = if (outcome == ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR) {
                        ChapterInventoryDiagnosticReason.MATERIALIZATION_FAILED
                    } else {
                        failureReason
                    },
                    availabilityBlocked = true,
                    affectedSourceCount = 1,
                )
                throw error
            }

            val now = clock()
            val existing = existingBindings.firstOrNull {
                it.providerTitleKey == materialized.providerTitleKey
            } ?: existingBindings.singleOrNull()
                ?.takeIf { selected.size == 1 }
            val binding = createBinding(
                canonicalTitleId = canonicalTitleId,
                addonId = addonId,
                scored = scored,
                materialized = materialized,
                existing = existing,
                timestamp = now,
            )
            try {
                contentBindingRepository.upsert(binding)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordBinding(
                    canonicalTitleId,
                    addonId,
                    ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                    ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR,
                    sourceId = scored.candidate.sourceId,
                    language = scored.candidate.language,
                    reason = ChapterInventoryDiagnosticReason.BINDING_PERSISTENCE_FAILED,
                )
                throw error
            }
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.BINDING_MATERIALIZATION,
                ChapterInventoryDiagnosticOutcome.SUCCESS,
                sourceId = scored.candidate.sourceId,
                language = scored.candidate.language,
                accepted = 1,
            )
            bindings += binding
        }

        return bindings
            .distinctBy(ContentBinding::providerTitleKey)
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    /**
     * Retry spelling/punctuation variants only when the first lookup has no safe,
     * unambiguous match. All candidates still pass the original confidence and
     * ambiguity gates; a punctuation change never establishes canonical identity.
     */
    private suspend fun searchSourceTitle(
        canonicalTitleId: String,
        addonId: AddonId,
        sourceId: Long,
        title: String,
    ): Pair<List<ReadingSourceCandidate>, Throwable?> {
        val collected = mutableListOf<ReadingSourceCandidate>()
        for ((attemptIndex, query) in titleSearchQueries(title).withIndex()) {
            val started = TimeSource.Monotonic.markNow()
            val response = try {
                readingSourceGateway.search(sourceId, query)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            val matches = response.getOrElse { error ->
                if (error is CancellationException) throw error
                val (outcome, reason) = ChapterInventoryDiagnosticFailures.classify(error)
                recordBinding(
                    canonicalTitleId,
                    addonId,
                    ChapterInventoryDiagnosticStage.BINDING_SEARCH,
                    outcome,
                    sourceId = sourceId,
                    attempt = attemptIndex + 1,
                    elapsedMillis = started.elapsedNow().inWholeMilliseconds,
                    httpStatus = (error as? ReadingSourceSearchFailure)?.httpStatus,
                    reason = reason,
                )
                return collected.distinctBy { it.sourceId to it.sourceUrl } to error
            }
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.BINDING_SEARCH,
                if (matches.isEmpty()) {
                    ChapterInventoryDiagnosticOutcome.EMPTY
                } else {
                    ChapterInventoryDiagnosticOutcome.SUCCESS
                },
                sourceId = sourceId,
                language = matches.firstOrNull()?.language,
                attempt = attemptIndex + 1,
                elapsedMillis = started.elapsedNow().inWholeMilliseconds,
                received = matches.size,
                reason = if (matches.isEmpty()) ChapterInventoryDiagnosticReason.NO_SEARCH_RESULTS else null,
            )
            collected += matches
            val ranked = collected.distinctBy { it.sourceId to it.sourceUrl }
                .sortedByDescending { scoreSourceTitleMatch(title, it.title) }
            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)
            if (best != null) {
                val bestScore = scoreSourceTitleMatch(title, best.title)
                val unambiguous = runnerUp == null ||
                    bestScore - scoreSourceTitleMatch(title, runnerUp.title) > AUTO_MATCH_MARGIN
                if (bestScore >= AUTO_MATCH_THRESHOLD && unambiguous) {
                    break
                }
            }
        }
        return collected.distinctBy { it.sourceId to it.sourceUrl } to null
    }

    private fun titleSearchQueries(title: String): List<String> = buildList {
        add(title)
        if ('-' in title) {
            val spaced = title.replace(Regex("\\s*-\\s*"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (spaced.isNotBlank()) {
                add(spaced)
                val words = spaced.split(" ").filter(String::isNotBlank)
                if (words.size in 2..5) {
                    for (index in 1 until words.size) {
                        add(
                            words.take(index).joinToString(" ") + "-" +
                                words.drop(index).joinToString(" "),
                        )
                    }
                }
            }
        }
    }.distinct().take(MAX_TITLE_SEARCH_QUERIES)

    private suspend fun requireExecutableAddon(canonicalTitleId: String, addonId: AddonId): InstalledAddon {
        val addon = addonRepository.snapshot().firstOrNull { it.id == addonId }
        if (addon == null) {
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.ADDON_DISCOVERY,
                ChapterInventoryDiagnosticOutcome.NO_BINDING,
                availabilityBlocked = true,
                reason = ChapterInventoryDiagnosticReason.ADDON_NOT_INSTALLED,
            )
            throw ContentBindingNotFoundException("Add-on " + addonId.value + " is not installed")
        }
        val sourceEligibility = diagnosticSourceEligibility(canonicalTitleId, addonId)
        if (sourceEligibility.isNotEmpty()) {
            val enabledSources = sourceEligibility.count { it.enabled }
            val disabledSources = sourceEligibility.size - enabledSources
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.SOURCE_ELIGIBILITY,
                if (disabledSources == 0) {
                    ChapterInventoryDiagnosticOutcome.SUCCESS
                } else {
                    ChapterInventoryDiagnosticOutcome.PARTIAL
                },
                received = sourceEligibility.size,
                accepted = enabledSources,
                discarded = disabledSources,
            )
            sourceEligibility.forEach { source ->
                recordBinding(
                    canonicalTitleId,
                    addonId,
                    ChapterInventoryDiagnosticStage.SOURCE_ELIGIBILITY,
                    if (source.enabled) {
                        ChapterInventoryDiagnosticOutcome.SUCCESS
                    } else {
                        ChapterInventoryDiagnosticOutcome.DISABLED
                    },
                    sourceId = source.sourceId,
                    language = source.language,
                    accepted = if (source.enabled) 1 else 0,
                    discarded = if (source.enabled) 0 else 1,
                    reason = if (source.enabled) null else ChapterInventoryDiagnosticReason.SOURCE_DISABLED,
                )
            }
        }
        if (!addon.enabled || addon.mihonSourceIds.isEmpty()) {
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.ADDON_DISCOVERY,
                ChapterInventoryDiagnosticOutcome.DISABLED,
                availabilityBlocked = true,
                affectedSourceCount = sourceEligibility.size,
                reason = ChapterInventoryDiagnosticReason.ALL_SOURCES_DISABLED,
            )
            throw ContentBindingNotFoundException("Add-on " + addonId.value + " has no enabled sources")
        }
        recordBinding(
            canonicalTitleId,
            addonId,
            ChapterInventoryDiagnosticStage.ADDON_DISCOVERY,
            ChapterInventoryDiagnosticOutcome.SUCCESS,
            received = addon.mihonSourceIds.size,
            accepted = addon.mihonSourceIds.size,
        )
        addon.mihonSourceIds.distinct().forEach { sourceId ->
            recordBinding(
                canonicalTitleId,
                addonId,
                ChapterInventoryDiagnosticStage.ADDON_DISCOVERY,
                ChapterInventoryDiagnosticOutcome.SUCCESS,
                sourceId = sourceId,
                accepted = 1,
            )
        }
        return addon
    }

    private fun recordBinding(
        canonicalTitleId: String,
        addonId: AddonId,
        stage: ChapterInventoryDiagnosticStage,
        outcome: ChapterInventoryDiagnosticOutcome,
        sourceId: Long? = null,
        language: String? = null,
        received: Int? = null,
        accepted: Int? = null,
        discarded: Int? = null,
        attempt: Int? = null,
        elapsedMillis: Long? = null,
        reason: ChapterInventoryDiagnosticReason? = null,
        availabilityBlocked: Boolean = false,
        affectedSourceCount: Int? = null,
        httpStatus: Int? = null,
    ) {
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = stage, outcome = outcome, addonId = addonId.value, sourceId = sourceId,
                language = language, received = received, accepted = accepted,
                discarded = discarded,
                httpStatus = httpStatus,
                availabilityBlocked = availabilityBlocked,
                affectedSourceCount = affectedSourceCount,
                attempt = attempt, elapsedMillis = elapsedMillis,
                reasons = reason?.let { mapOf(it to 1) }.orEmpty(),
            ),
        )
    }

    private suspend fun diagnosticSourceEligibility(
        canonicalTitleId: String,
        addonId: AddonId,
    ): List<AddonSourceEligibility> {
        if (!runCatching { diagnostics.isRecording(canonicalTitleId) }.getOrDefault(false)) {
            return emptyList()
        }
        return try {
            addonSourceEligibilityRepository?.getByAddonId(addonId).orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val AUTO_MATCH_THRESHOLD = 0.97
        const val AUTO_MATCH_MARGIN = 0.08
        const val CONFIRMATION_THRESHOLD = 0.70
        const val MAX_CONFIRMATION_CANDIDATES = 5
        const val MAX_TITLE_SEARCH_QUERIES = 3
        const val MAX_CONCURRENT_SOURCE_SEARCHES = 4
    }
}

class ContentBindingConfirmationRequiredException(
    val candidates: List<ScoredSourceCandidate>,
) : IllegalStateException("Content binding requires user confirmation")

class ContentBindingNotFoundException(
    message: String,
) : IllegalStateException(message)

/** A source lookup failed rather than returning a genuine empty title search. */
class ContentBindingSourceSearchException(cause: Throwable) :
    IllegalStateException("Content binding source search is unavailable", cause)
