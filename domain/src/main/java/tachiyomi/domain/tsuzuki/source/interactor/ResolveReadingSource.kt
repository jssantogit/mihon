package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticConfidenceBucket
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticErrorCategory
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.util.UUID

@Inject
class ResolveReadingSource(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val getPreferredReadingSources: GetPreferredReadingSources,
    private val readingSourceGateway: ReadingSourceGateway,
    private val scoreSourceTitleMatch: ScoreSourceTitleMatch,
    private val confirmSourceMapping: ConfirmSourceMapping,
    private val diagnosticRecorder: StructuredDiagnosticRecorder = NoOpStructuredDiagnosticRecorder,
) {

    suspend fun execute(
        canonicalTitleId: String,
        language: String,
        broaden: Boolean = false,
    ): SourceResolutionResult {
        val trace = ResolverTrace(diagnosticRecorder, canonicalTitleId, language, broaden)
        return try {
            trace.emit(DiagnosticEventName.SOURCE_RESOLVE_STARTED, DiagnosticStage.RESOLVE, DiagnosticOutcome.STARTED)
            val result = executeResolution(canonicalTitleId, language, broaden, trace)
            trace.finish(result.toDiagnosticOutcome(trace.sourceFailureCount))
            result
        } catch (e: CancellationException) {
            try {
                trace.finish(DiagnosticOutcome.CANCELLED, DiagnosticSeverity.INFO)
            } catch (_: Throwable) {
                // Preserve the cancellation already raised by resolver work.
            }
            throw e
        } catch (e: Throwable) {
            try {
                trace.finish(DiagnosticOutcome.FAILED, DiagnosticSeverity.ERROR, errorCategory = errorCategory(e))
            } catch (_: Throwable) {
                // Preserve the original resolver exception if diagnostics also fail.
            }
            throw e
        }
    }

    private suspend fun executeResolution(
        canonicalTitleId: String,
        language: String,
        broaden: Boolean,
        trace: ResolverTrace,
    ): SourceResolutionResult {
        val existingMappings = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
        val existing = existingMappings.firstOrNull { it.preferredOverride }
            ?: existingMappings.firstOrNull { it.language.equals(language, ignoreCase = true) }
            ?: existingMappings.firstOrNull()
        if (
            existing != null &&
            existing.mihonMangaId != null &&
            existing.availability != SourceMappingAvailability.UNAVAILABLE
        ) {
            trace.emit(
                name = DiagnosticEventName.SOURCE_RESOLVE_MAPPING_REUSED,
                stage = DiagnosticStage.RESOLVE,
                outcome = DiagnosticOutcome.REUSED,
                sourceId = existing.sourceId,
                mihonMangaId = existing.mihonMangaId,
                mappingReused = true,
            )
            return SourceResolutionResult.Resolved(
                mapping = existing,
                reused = true,
            )
        }

        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: return SourceResolutionResult.NotFound(
                searchedSourceIds = emptyList(),
                canBroaden = false,
            )

        if (existing != null) {
            trace.emit(
                name = DiagnosticEventName.SOURCE_MATCH_EVALUATED,
                stage = DiagnosticStage.CONFIRMATION,
                outcome = DiagnosticOutcome.STARTED,
                sourceId = existing.sourceId,
                mappingReused = false,
            )
            val result = confirmSourceMapping.execute(
                canonicalTitleId = canonicalTitleId,
                candidate = ReadingSourceCandidate(
                    sourceId = existing.sourceId,
                    sourceName = "Source ${existing.sourceId}",
                    language = existing.language,
                    sourceUrl = existing.sourceUrl,
                    title = canonicalTitle.displayTitle,
                    thumbnailUrl = null,
                    author = null,
                    artist = null,
                    description = null,
                    genres = null,
                    status = 0L,
                ),
                matchConfidence = existing.matchConfidence,
                verifiedByUser = existing.verifiedByUser,
            )
            trace.recordConfirmationResult(result, existing.sourceId)
            return result
        }

        val preferred = getPreferredReadingSources.await(language)
        if (preferred.isEmpty()) {
            trace.emit(
                name = DiagnosticEventName.SOURCE_RESOLVE_PREFERRED_SOURCES,
                stage = DiagnosticStage.PREFERRED_SOURCES,
                outcome = DiagnosticOutcome.NO_PREFERRED_SOURCES,
                preferredCount = 0,
                targetCount = 0,
            )
            return SourceResolutionResult.NoPreferredSources(language)
        }

        val targetSourceIds = if (broaden) {
            broadenedSourceIds(language, preferred, trace)
        } else {
            preferred.take(3).map { it.sourceId }
        }
        trace.emit(
            name = DiagnosticEventName.SOURCE_RESOLVE_PREFERRED_SOURCES,
            stage = DiagnosticStage.PREFERRED_SOURCES,
            outcome = DiagnosticOutcome.SUCCEEDED,
            preferredCount = preferred.size,
            targetCount = targetSourceIds.size,
        )

        val searchedSourceIds = mutableListOf<Long>()
        val allCandidates = mutableListOf<ScoredSourceCandidate>()

        for ((rank, sourceId) in targetSourceIds.withIndex()) {
            searchedSourceIds += sourceId
            val attempt = rank + 1
            trace.emit(
                name = DiagnosticEventName.SOURCE_SEARCH_STARTED,
                stage = DiagnosticStage.SEARCH,
                outcome = DiagnosticOutcome.STARTED,
                sourceId = sourceId,
                attempt = attempt,
            )
            val searchResult = try {
                readingSourceGateway.search(sourceId, canonicalTitle.displayTitle)
            } catch (e: CancellationException) {
                trace.recordSearchFailure(sourceId, attempt, DiagnosticOutcome.CANCELLED, e)
                throw e
            } catch (e: Throwable) {
                trace.recordSearchFailure(sourceId, attempt, DiagnosticOutcome.THREW, e)
                continue
            }

            val failure = searchResult.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) {
                    trace.recordSearchFailure(sourceId, attempt, DiagnosticOutcome.CANCELLED, failure)
                    throw failure
                }
                trace.recordSearchFailure(sourceId, attempt, DiagnosticOutcome.TYPED_FAILURE, failure)
                continue
            }

            val sourceCandidates = searchResult.getOrThrow()
                .map { candidate ->
                    ScoredSourceCandidate(
                        candidate = candidate,
                        confidence = scoreSourceTitleMatch(
                            targetTitle = canonicalTitle.displayTitle,
                            candidateTitle = candidate.title,
                        ),
                        sourcePreferenceRank = rank,
                    )
                }
                .sortedByDescending { it.confidence }

            allCandidates += sourceCandidates
            trace.emit(
                name = DiagnosticEventName.SOURCE_SEARCH_COMPLETED,
                stage = DiagnosticStage.SEARCH,
                outcome = if (sourceCandidates.isEmpty()) {
                    DiagnosticOutcome.NOT_FOUND_NO_CANDIDATES
                } else {
                    DiagnosticOutcome.CANDIDATES
                },
                sourceId = sourceId,
                attempt = attempt,
                candidateCount = sourceCandidates.size,
            )

            val best = sourceCandidates.firstOrNull()
            if (best != null) {
                val second = sourceCandidates.getOrNull(1)
                val unambiguous = second == null || best.confidence - second.confidence > 0.08
                trace.recordBestMatch(
                    best = best,
                    count = sourceCandidates.size,
                    autoConfirmAttempted = best.confidence >= 0.97 && unambiguous,
                    ambiguous = !unambiguous,
                )
                if (best.confidence >= 0.97 && unambiguous) {
                    val confirmed = try {
                        confirmSourceMapping.execute(
                            canonicalTitleId = canonicalTitleId,
                            candidate = best.candidate,
                            matchConfidence = best.confidence,
                            verifiedByUser = false,
                        )
                    } catch (e: CancellationException) {
                        trace.recordConfirmationFailure(sourceId, e, DiagnosticOutcome.CANCELLED)
                        throw e
                    } catch (e: Throwable) {
                        trace.recordConfirmationFailure(sourceId, e)
                        null
                    }

                    when (confirmed) {
                        is SourceResolutionResult.Resolved -> {
                            trace.recordConfirmationResult(confirmed, sourceId)
                            return confirmed
                        }
                        is SourceResolutionResult.Conflict -> {
                            trace.recordConfirmationResult(confirmed, sourceId)
                            return confirmed
                        }
                        else -> Unit
                    }
                }
            }
        }

        val confirmationCandidates = allCandidates
            .filter { it.confidence >= 0.70 }
            .sortedWith(
                compareBy<ScoredSourceCandidate> { it.sourcePreferenceRank }
                    .thenByDescending { it.confidence },
            )
            .take(5)

        if (confirmationCandidates.isNotEmpty()) {
            return SourceResolutionResult.NeedsConfirmation(confirmationCandidates)
        }

        return SourceResolutionResult.NotFound(
            searchedSourceIds = searchedSourceIds,
            canBroaden = !broaden && canBroaden(
                language = language,
                preferred = preferred,
                searchedSourceIds = searchedSourceIds,
                trace = trace,
            ),
        )
    }

    private suspend fun broadenedSourceIds(
        language: String,
        preferred: List<ReadingSourcePreference>,
        trace: ResolverTrace,
    ): List<Long> {
        val preferredIds = preferred.map { it.sourceId }
        val installed = try {
            readingSourceGateway.listInstalled(language)
        } catch (e: CancellationException) {
            trace.recordInstalledListFailure(DiagnosticOutcome.CANCELLED, e)
            throw e
        } catch (e: Throwable) {
            trace.recordInstalledListFailure(DiagnosticOutcome.THREW, e)
            emptyList()
        }
        return (preferredIds + installed.map { it.sourceId })
            .distinct()
    }

    private suspend fun canBroaden(
        language: String,
        preferred: List<ReadingSourcePreference>,
        searchedSourceIds: List<Long>,
        trace: ResolverTrace,
    ): Boolean {
        if (preferred.any { it.sourceId !in searchedSourceIds }) {
            return true
        }

        val installed = try {
            readingSourceGateway.listInstalled(language)
        } catch (e: CancellationException) {
            trace.recordInstalledListFailure(DiagnosticOutcome.CANCELLED, e)
            throw e
        } catch (e: Throwable) {
            trace.recordInstalledListFailure(DiagnosticOutcome.THREW, e)
            return false
        }
        return installed.any { it.sourceId !in searchedSourceIds }
    }
}

private fun SourceResolutionResult.toDiagnosticOutcome(sourceFailureCount: Int): DiagnosticOutcome = when (this) {
    is SourceResolutionResult.Resolved -> if (reused) DiagnosticOutcome.REUSED else DiagnosticOutcome.SUCCEEDED
    is SourceResolutionResult.NeedsConfirmation -> DiagnosticOutcome.NEEDS_CONFIRMATION
    is SourceResolutionResult.NotFound -> if (sourceFailureCount > 0) {
        DiagnosticOutcome.NOT_FOUND_WITH_SOURCE_FAILURES
    } else {
        DiagnosticOutcome.NOT_FOUND_NO_CANDIDATES
    }
    is SourceResolutionResult.NoPreferredSources -> DiagnosticOutcome.NO_PREFERRED_SOURCES
    is SourceResolutionResult.Conflict -> DiagnosticOutcome.FAILED
}

private fun errorCategory(error: Throwable): DiagnosticErrorCategory {
    val typed = generateSequence(error) { it.cause }
        .filterIsInstance<ReadingSourceSearchFailure>()
        .firstOrNull()
    if (typed != null) {
        return when (typed.kind) {
            ReadingSourceFailureKind.SOURCE_DISABLED,
            ReadingSourceFailureKind.SOURCE_UNAVAILABLE,
            -> DiagnosticErrorCategory.SOURCE_UNAVAILABLE
            ReadingSourceFailureKind.HTTP_RESPONSE -> DiagnosticErrorCategory.HTTP
            ReadingSourceFailureKind.NETWORK_FAILURE -> DiagnosticErrorCategory.NETWORK
            ReadingSourceFailureKind.TIMEOUT -> DiagnosticErrorCategory.TIMEOUT
            ReadingSourceFailureKind.CAPTCHA_REQUIRED -> DiagnosticErrorCategory.CAPTCHA
            ReadingSourceFailureKind.MALFORMED_RESPONSE -> DiagnosticErrorCategory.MALFORMED_RESPONSE
            ReadingSourceFailureKind.EXTENSION_FAILURE -> DiagnosticErrorCategory.EXTENSION
            ReadingSourceFailureKind.INDETERMINATE -> DiagnosticErrorCategory.UNKNOWN
        }
    }
    return when (error) {
        is java.net.SocketTimeoutException -> DiagnosticErrorCategory.TIMEOUT
        is java.io.IOException -> DiagnosticErrorCategory.NETWORK
        else -> DiagnosticErrorCategory.UNKNOWN
    }
}

private fun httpStatus(error: Throwable): Int? =
    generateSequence(error) { it.cause }
        .filterIsInstance<ReadingSourceSearchFailure>()
        .mapNotNull(ReadingSourceSearchFailure::httpStatus)
        .firstOrNull()

private class ResolverTrace(
    private val recorder: StructuredDiagnosticRecorder,
    canonicalTitleId: String,
    private val language: String,
    private val broaden: Boolean,
) {
    private val startedNanos = System.nanoTime()
    private val operationId = UUID.randomUUID().toString()
    private val sessionId by lazy {
        safely { recorder.sessionId } ?: "00000000-0000-0000-0000-000000000000"
    }
    private val canonicalTitleReference by lazy { safely { recorder.canonicalTitleReference(canonicalTitleId) } }
    var sourceFailureCount: Int = 0
        private set
    private var automaticConfirmationAttempted = false
    private var isFinished = false

    fun emit(
        name: DiagnosticEventName,
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        sourceId: Long? = null,
        mihonMangaId: Long? = null,
        attempt: Int? = null,
        candidateCount: Int? = null,
        preferredCount: Int? = null,
        targetCount: Int? = null,
        mappingReused: Boolean? = null,
        confidence: Double? = null,
        autoConfirmAttempted: Boolean? = null,
        ambiguous: Boolean? = null,
        errorCategory: DiagnosticErrorCategory? = null,
        httpStatus: Int? = null,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        durationMillis: Long? = null,
    ) {
        val attributes = buildMap<String, DiagnosticAttributeValue> {
            put("language", DiagnosticAttributeValue.Text(language))
            put("broadened", DiagnosticAttributeValue.Flag(broaden))
            sourceId?.let { put("source_id", DiagnosticAttributeValue.Number(it)) }
            candidateCount?.let { put("candidate_count", DiagnosticAttributeValue.Number(it.toLong())) }
            preferredCount?.let { put("preferred_source_count", DiagnosticAttributeValue.Number(it.toLong())) }
            targetCount?.let { put("target_source_count", DiagnosticAttributeValue.Number(it.toLong())) }
            mappingReused?.let { put("mapping_reused", DiagnosticAttributeValue.Flag(it)) }
            confidence?.let {
                put("confidence_score", DiagnosticAttributeValue.Number((it * 100).toInt().toLong()))
                put("confidence_bucket", DiagnosticAttributeValue.Code(it.toBucket()))
            }
            autoConfirmAttempted?.let { put("auto_confirm_attempted", DiagnosticAttributeValue.Flag(it)) }
            ambiguous?.let { put("ambiguous", DiagnosticAttributeValue.Flag(it)) }
            errorCategory?.let { put("error_category", DiagnosticAttributeValue.Code(it)) }
            httpStatus?.let { put("http_status", DiagnosticAttributeValue.Number(it.toLong())) }
            canonicalTitleReference?.let { put("canonical_title_ref", DiagnosticAttributeValue.Text(it)) }
            mihonMangaId?.let { id ->
                safely { recorder.mihonMangaReference(id) }?.let {
                    put("mihon_manga_ref", DiagnosticAttributeValue.Text(it))
                }
            }
        }
        safely {
            recorder.record(
                StructuredDiagnosticEvent(
                    timestampMillis = System.currentTimeMillis().coerceAtLeast(0),
                    severity = severity,
                    subsystem = DiagnosticSubsystem.SOURCE,
                    name = name,
                    sessionId = sessionId,
                    operationId = operationId,
                    stage = stage,
                    outcome = outcome,
                    durationMillis = durationMillis,
                    attempt = attempt,
                    attributes = attributes,
                ),
            )
        }
    }

    fun recordSearchFailure(
        sourceId: Long,
        attempt: Int,
        outcome: DiagnosticOutcome,
        error: Throwable,
    ) {
        if (outcome != DiagnosticOutcome.CANCELLED) sourceFailureCount++
        emit(
            name = DiagnosticEventName.SOURCE_SEARCH_FAILED,
            stage = DiagnosticStage.SEARCH,
            outcome = outcome,
            sourceId = sourceId,
            attempt = attempt,
            errorCategory = errorCategory(error),
            httpStatus = httpStatus(error),
            severity = DiagnosticSeverity.WARN,
        )
    }

    fun recordBestMatch(best: ScoredSourceCandidate, count: Int, autoConfirmAttempted: Boolean, ambiguous: Boolean) {
        automaticConfirmationAttempted = automaticConfirmationAttempted || autoConfirmAttempted
        emit(
            name = DiagnosticEventName.SOURCE_MATCH_EVALUATED,
            stage = DiagnosticStage.MATCH,
            outcome = DiagnosticOutcome.CANDIDATES,
            sourceId = best.candidate.sourceId,
            candidateCount = count,
            confidence = best.confidence,
            autoConfirmAttempted = autoConfirmAttempted,
            ambiguous = ambiguous,
        )
    }

    fun recordConfirmationResult(result: SourceResolutionResult, sourceId: Long) {
        if (result is SourceResolutionResult.Resolved || result is SourceResolutionResult.Conflict) {
            emit(
                name = DiagnosticEventName.SOURCE_MATCH_EVALUATED,
                stage = DiagnosticStage.CONFIRMATION,
                outcome = if (result is SourceResolutionResult.Resolved) {
                    DiagnosticOutcome.SUCCEEDED
                } else {
                    DiagnosticOutcome.FAILED
                },
                sourceId = sourceId,
            )
        }
    }

    fun recordConfirmationFailure(
        sourceId: Long,
        error: Throwable,
        outcome: DiagnosticOutcome = DiagnosticOutcome.THREW,
    ) {
        emit(
            name = DiagnosticEventName.SOURCE_MAPPING_CONFIRMATION_FAILED,
            stage = DiagnosticStage.CONFIRMATION,
            outcome = outcome,
            sourceId = sourceId,
            errorCategory = errorCategory(error),
            httpStatus = httpStatus(error),
            severity = DiagnosticSeverity.WARN,
        )
    }

    fun recordInstalledListFailure(outcome: DiagnosticOutcome, error: Throwable) {
        emit(
            name = DiagnosticEventName.SOURCE_RESOLVE_PREFERRED_SOURCES,
            stage = DiagnosticStage.PREFERRED_SOURCES,
            outcome = outcome,
            errorCategory = errorCategory(error),
            httpStatus = httpStatus(error),
            severity = DiagnosticSeverity.WARN,
        )
    }

    fun finish(
        outcome: DiagnosticOutcome,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        errorCategory: DiagnosticErrorCategory? = null,
    ) {
        if (isFinished) return
        isFinished = true
        emit(
            name = DiagnosticEventName.SOURCE_RESOLVE_COMPLETED,
            stage = DiagnosticStage.COMPLETE,
            outcome = outcome,
            autoConfirmAttempted = automaticConfirmationAttempted,
            errorCategory = errorCategory,
            severity = severity,
            durationMillis = ((System.nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000L),
        )
    }

    private fun Double.toBucket(): DiagnosticConfidenceBucket = when {
        this >= 0.97 -> DiagnosticConfidenceBucket.HIGH
        this >= 0.70 -> DiagnosticConfidenceBucket.MEDIUM
        else -> DiagnosticConfidenceBucket.LOW
    }

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
