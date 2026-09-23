package eu.kanade.tachiyomi.data.tsuzuki.addon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticLabels
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.TimeSource

class MihonChapterProbeProvider internal constructor(
    override val addonId: AddonId,
    private val contentBindingRepository: ContentBindingRepository,
    private val parser: ParseCanonicalChapterLabel,
    private val fetchInventory: suspend (ContentBinding) -> Result<SourceChapterInventory>,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
) : ChapterProbeProvider {

    override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> {
        val totalStart = TimeSource.Monotonic.markNow()
        return try {
            val bindings = contentBindingRepository.getByTitle(canonicalTitleId)
                .filter { it.addonId == addonId && it.availability == ContentBindingAvailability.AVAILABLE }
            if (bindings.isEmpty()) {
                recordProbe(
                    canonicalTitleId = canonicalTitleId,
                    outcome = ChapterInventoryDiagnosticOutcome.NO_BINDING,
                    received = 0,
                    accepted = 0,
                    provisional = 0,
                    discarded = 0,
                    elapsedMillis = totalStart.elapsedNow().inWholeMilliseconds,
                    reasons = mapOf(ChapterInventoryDiagnosticReason.NO_BINDING to 1),
                )
                return Result.success(emptyList())
            }

            val fetchGate = Semaphore(MAX_CONCURRENT_INVENTORY_FETCHES)
            val inventoryResults = coroutineScope {
                bindings.map { binding ->
                    async {
                        binding to fetchGate.withPermit {
                            try {
                                fetchInventory(binding)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }
                        }
                    }
                }.awaitAll()
            }

            val evidence = mutableListOf<ChapterEvidence>()
            val labels = mutableListOf<String>()
            val reasons = mutableMapOf<ChapterInventoryDiagnosticReason, Int>()
            var received = 0
            var discardedForMissingIdentity = 0
            var lowConfidence = 0
            val sourceIds = mutableSetOf<Long>()
            val languages = mutableSetOf<String>()
            var successfulInventoryCount = 0
            var firstFailure: Throwable? = null

            for ((binding, inventoryResult) in inventoryResults) {
                val inventory = inventoryResult.getOrElse { error ->
                    if (error is CancellationException) throw error
                    firstFailure = firstFailure ?: error
                    val (outcome, reason) = ChapterInventoryDiagnosticFailures.classify(error)
                    reasons.increment(reason)
                    if (isDiagnosticsRecording(canonicalTitleId)) {
                        val payload = runCatching { MihonContentBindingPayloadCodec.decode(binding.runtimePayload) }
                            .getOrNull()
                        diagnostics.recordIfEnabled(
                            canonicalTitleId,
                            ChapterInventoryDiagnosticEvent(
                                stage = ChapterInventoryDiagnosticStage.PROBE,
                                outcome = outcome,
                                addonId = addonId.value,
                                sourceId = payload?.sourceId,
                                language = payload?.language,
                                received = 0,
                                reasons = mapOf(reason to 1),
                            ),
                        )
                    }
                    continue
                }
                successfulInventoryCount++
                val observedAt = clock()
                received += inventory.chapters.size
                sourceIds += inventory.sourceId
                languages += inventory.language

                for (snapshot in inventory.chapters) {
                    if (snapshot.sourceChapterId.isBlank()) {
                        reasons.increment(ChapterInventoryDiagnosticReason.MISSING_SOURCE_ID)
                    }
                    if (snapshot.sourceChapterUrl.isBlank()) {
                        reasons.increment(ChapterInventoryDiagnosticReason.MISSING_SOURCE_URL)
                    }
                    val externalKey = snapshot.sourceChapterId
                        .takeIf(String::isNotBlank)
                        ?: snapshot.sourceChapterUrl.takeIf(String::isNotBlank)
                    if (externalKey == null) {
                        discardedForMissingIdentity++
                        continue
                    }
                    val parsed = parser.execute(snapshot.rawName, snapshot.rawNumberHint)
                    ChapterInventoryDiagnosticLabels.fromIdentity(parsed.identity)?.let(labels::add)
                    if (parsed.confidence < RELIABLE_CONFIDENCE || !parsed.identity.isSpecific) {
                        lowConfidence++
                        reasons.increment(ChapterInventoryDiagnosticReason.LOW_CONFIDENCE)
                    }
                    evidence += ChapterEvidence(
                        id = evidenceId(canonicalTitleId, snapshot.sourceId, externalKey),
                        canonicalTitleId = canonicalTitleId,
                        producerKind = ProducerKind.ADDON,
                        producerId = addonId.value,
                        externalChapterKey = snapshot.sourceId.toString() + ":" + externalKey,
                        rawLabel = snapshot.rawName,
                        rawNumber = snapshot.rawNumberHint,
                        volume = null,
                        title = null,
                        observedAt = observedAt,
                        confidence = parsed.confidence,
                        authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                    )
                }
            }

            val uniqueEvidence = evidence.distinctBy(ChapterEvidence::id)
            val duplicateCount = evidence.size - uniqueEvidence.size
            if (duplicateCount > 0) reasons[ChapterInventoryDiagnosticReason.DUPLICATE] = duplicateCount
            val discarded = discardedForMissingIdentity + duplicateCount
            val outcome = when {
                firstFailure != null && successfulInventoryCount == 0 ->
                    firstFailure?.toDiagnosticOutcome() ?: ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR
                firstFailure != null -> ChapterInventoryDiagnosticOutcome.PARTIAL
                received == 0 -> ChapterInventoryDiagnosticOutcome.EMPTY
                discarded > 0 || lowConfidence > 0 || reasons.isNotEmpty() ->
                    ChapterInventoryDiagnosticOutcome.PARTIAL
                else -> ChapterInventoryDiagnosticOutcome.SUCCESS
            }
            recordProbe(
                canonicalTitleId = canonicalTitleId,
                sourceId = sourceIds.singleOrNull(),
                language = languages.singleOrNull(),
                outcome = outcome,
                received = received,
                accepted = uniqueEvidence.size,
                provisional = uniqueEvidence.size,
                discarded = discarded,
                elapsedMillis = totalStart.elapsedNow().inWholeMilliseconds,
                labels = labels,
                reasons = reasons,
            )

            if (evidence.isEmpty() && firstFailure != null) {
                Result.failure(firstFailure)
            } else {
                Result.success(uniqueEvidence)
            }
        } catch (error: CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                recordProbeFailure(
                    canonicalTitleId = canonicalTitleId,
                    error = error,
                    elapsedMillis = totalStart.elapsedNow().inWholeMilliseconds,
                )
            }
            throw error
        } catch (error: Throwable) {
            recordProbeFailure(
                canonicalTitleId = canonicalTitleId,
                error = error,
                elapsedMillis = totalStart.elapsedNow().inWholeMilliseconds,
            )
            Result.failure(error)
        }
    }

    private fun recordProbe(
        canonicalTitleId: String,
        sourceId: Long? = null,
        language: String? = null,
        outcome: ChapterInventoryDiagnosticOutcome,
        received: Int,
        accepted: Int,
        provisional: Int,
        discarded: Int,
        elapsedMillis: Long,
        labels: List<String> = emptyList(),
        reasons: Map<ChapterInventoryDiagnosticReason, Int> = emptyMap(),
    ) {
        if (!isDiagnosticsRecording(canonicalTitleId)) return
        val (boundaryLabels, gaps) = ChapterInventoryDiagnosticLabels.boundariesAndGaps(labels)
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.PROBE,
                outcome = outcome,
                sourceId = sourceId,
                addonId = addonId.value,
                language = language,
                elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                received = received,
                accepted = accepted,
                provisional = provisional,
                discarded = discarded,
                labels = boundaryLabels,
                gaps = gaps,
                reasons = reasons,
            ),
        )
    }

    private fun recordProbeFailure(
        canonicalTitleId: String,
        error: Throwable,
        elapsedMillis: Long,
    ) {
        if (!isDiagnosticsRecording(canonicalTitleId)) return
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.PROBE,
                outcome = error.toDiagnosticOutcome(),
                addonId = addonId.value,
                elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                received = 0,
                accepted = 0,
                provisional = 0,
                discarded = 0,
            ),
        )
    }

    private fun Throwable.toDiagnosticOutcome(): ChapterInventoryDiagnosticOutcome =
        ChapterInventoryDiagnosticFailures.classify(this).first

    private fun isDiagnosticsRecording(canonicalTitleId: String): Boolean = try {
        diagnostics.isRecording(canonicalTitleId)
    } catch (_: Exception) {
        false
    }

    private fun MutableMap<ChapterInventoryDiagnosticReason, Int>.increment(
        reason: ChapterInventoryDiagnosticReason,
    ) {
        this[reason] = (this[reason] ?: 0) + 1
    }

    private fun evidenceId(
        canonicalTitleId: String,
        sourceId: Long,
        externalKey: String,
    ): String {
        val stableKey = addonId.value + "|" + canonicalTitleId + "|" + sourceId + "|" + externalKey
        return UUID.nameUUIDFromBytes(stableKey.encodeToByteArray()).toString()
    }

    private companion object {
        const val MAX_CONCURRENT_INVENTORY_FETCHES = 4
        const val RELIABLE_CONFIDENCE = 0.95
    }
}
