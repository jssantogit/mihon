package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticLabels
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.chapter.interactor.CanonicalChapterCandidateResolution
import tachiyomi.domain.tsuzuki.chapter.interactor.CanonicalChapterCandidateResolver
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import java.util.UUID
import kotlin.time.Clock

class ReconcileChapterEvidence internal constructor(
    private val parser: ParseCanonicalChapterLabel,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val evidenceRepository: ChapterEvidenceRepository,
    private val idFactory: () -> String,
    private val clock: () -> Long,
    private val mutationGate: ChapterMutationGate = ChapterMutationGate(),
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
    private val volumeParser: ParseCanonicalChapterVolume = ParseCanonicalChapterVolume(),
) {

    @Inject
    constructor(
        parser: ParseCanonicalChapterLabel,
        canonicalChapterRepository: CanonicalChapterRepository,
        evidenceRepository: ChapterEvidenceRepository,
        mutationGate: ChapterMutationGate,
        diagnostics: ChapterInventoryDiagnostics,
    ) : this(
        parser = parser,
        canonicalChapterRepository = canonicalChapterRepository,
        evidenceRepository = evidenceRepository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
        mutationGate = mutationGate,
        diagnostics = diagnostics,
    )

    constructor(
        parser: ParseCanonicalChapterLabel,
        canonicalChapterRepository: CanonicalChapterRepository,
        evidenceRepository: ChapterEvidenceRepository,
    ) : this(
        parser = parser,
        canonicalChapterRepository = canonicalChapterRepository,
        evidenceRepository = evidenceRepository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
        diagnostics = NoOpChapterInventoryDiagnostics,
    )

    suspend fun execute(
        canonicalTitleId: String,
        evidence: List<ChapterEvidence>,
    ) {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id is required" }
        require(evidence.all { it.canonicalTitleId == canonicalTitleId }) {
            "All chapter evidence must belong to canonical title $canonicalTitleId"
        }
        if (evidence.isEmpty()) {
            diagnostics.recordIfEnabled(
                canonicalTitleId,
                ChapterInventoryDiagnosticEvent(
                    stage = ChapterInventoryDiagnosticStage.RECONCILIATION,
                    outcome = ChapterInventoryDiagnosticOutcome.EMPTY,
                    received = 0,
                    accepted = 0,
                    provisional = 0,
                    discarded = 0,
                ),
            )
            return
        }
        mutationGate.withLock {
            evidenceRepository.withTransaction {
                reconcileUncontended(canonicalTitleId, evidence)
            }
        }
    }

    private suspend fun reconcileUncontended(
        canonicalTitleId: String,
        evidence: List<ChapterEvidence>,
    ) {
        val chapters = canonicalChapterRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .associateByTo(linkedMapOf(), CanonicalChapter::id)
        val persistedEvidence = evidenceRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .associateBy { it.evidence.id }
            .toMutableMap()
        val persistedEvidenceByExternalKey = persistedEvidence.values
            .mapNotNull { persisted ->
                persisted.evidence.externalChapterKey?.let { externalKey ->
                    EvidenceExternalKey(
                        producerKind = persisted.evidence.producerKind,
                        producerId = persisted.evidence.producerId,
                        externalChapterKey = externalKey,
                    ) to persisted
                }
            }
            .toMap()
            .toMutableMap()
        val chaptersByIdentity = linkedMapOf<CanonicalChapterIdentity, MutableList<CanonicalChapter>>()
        chapters.values.forEach { chapter ->
            if (chapter.identity.isSpecific) {
                chaptersByIdentity.getOrPut(chapter.identity) { mutableListOf() }.add(chapter)
            }
        }
        val chapterUpserts = linkedMapOf<String, CanonicalChapter>()
        val evidenceUpserts = mutableListOf<ChapterEvidenceWrite>()

        fun indexChapter(chapter: CanonicalChapter) {
            if (!chapter.identity.isSpecific) return
            val candidates = chaptersByIdentity.getOrPut(chapter.identity) { mutableListOf() }
            val index = candidates.indexOfFirst { it.id == chapter.id }
            if (index < 0) {
                candidates += chapter
            } else {
                candidates[index] = chapter
            }
        }

        fun stageEvidence(observation: ChapterEvidence, mappedCanonicalChapterId: String?) {
            val externalKey = observation.externalChapterKey?.let {
                EvidenceExternalKey(observation.producerKind, observation.producerId, it)
            }
            val existing = externalKey?.let(persistedEvidenceByExternalKey::get)
                ?: persistedEvidence[observation.id]
            val stableId = existing?.evidence?.id ?: observation.id
            existing?.evidence?.externalChapterKey?.let { previousExternalKey ->
                if (previousExternalKey != observation.externalChapterKey) {
                    persistedEvidenceByExternalKey.remove(
                        EvidenceExternalKey(
                            existing.evidence.producerKind,
                            existing.evidence.producerId,
                            previousExternalKey,
                        ),
                    )
                }
            }
            val persisted = PersistedChapterEvidence(
                evidence = observation.copy(id = stableId),
                mappedCanonicalChapterId = mappedCanonicalChapterId,
                rawMetadata = existing?.rawMetadata ?: byteArrayOf(),
            )
            persistedEvidence[stableId] = persisted
            if (externalKey != null) persistedEvidenceByExternalKey[externalKey] = persisted
            evidenceUpserts += ChapterEvidenceWrite(observation, mappedCanonicalChapterId)
        }

        val initialChapterCount = chapters.size
        val reasonCounts = mutableMapOf<ChapterInventoryDiagnosticReason, Int>()
        val diagnosticLabels = mutableListOf<String>()
        var acceptedCount = 0
        var provisionalCount = 0
        var discardedCount = 0

        for (observation in evidence) {
            val parsed = try {
                parser.execute(observation.rawLabel, observation.rawNumber)
            } catch (error: Throwable) {
                diagnostics.recordIfEnabled(
                    canonicalTitleId,
                    ChapterInventoryDiagnosticEvent(
                        stage = ChapterInventoryDiagnosticStage.RECONCILIATION,
                        outcome = ChapterInventoryDiagnosticOutcome.EXTENSION_ERROR,
                        received = evidence.size,
                        accepted = acceptedCount,
                        provisional = provisionalCount,
                        discarded = discardedCount,
                        reasons = mapOf(ChapterInventoryDiagnosticReason.PARSER_ERROR to 1),
                    ),
                )
                throw error
            }
            ChapterInventoryDiagnosticLabels.fromIdentity(parsed.identity)?.let(diagnosticLabels::add)
            val parsedIdentityIsReliable = observation.confidence >= RELIABLE_CONFIDENCE &&
                parsed.confidence >= RELIABLE_CONFIDENCE &&
                parsed.identity.isSpecific

            if (
                observation.authority == ChapterEvidenceAuthority.ADDON_PROVISIONAL &&
                !parsedIdentityIsReliable
            ) {
                provisionalCount++
                reasonCounts.increment(ChapterInventoryDiagnosticReason.LOW_CONFIDENCE)
                stageEvidence(observation, mappedCanonicalChapterId = null)
                continue
            }

            val externalEvidence = observation.externalChapterKey?.let { externalKey ->
                persistedEvidenceByExternalKey[
                    EvidenceExternalKey(observation.producerKind, observation.producerId, externalKey),
                ]
            }
            val previousEvidence = externalEvidence ?: persistedEvidence[observation.id]
            val mappedChapterId = previousEvidence?.mappedCanonicalChapterId
            val mappedChapter = if (mappedChapterId != null) {
                (chapters[mappedChapterId] ?: canonicalChapterRepository.getById(mappedChapterId))?.also { chapter ->
                    require(chapter.canonicalTitleId == canonicalTitleId) {
                        "Evidence mapping points to chapter from another canonical title"
                    }
                }
            } else {
                null
            }

            val mappedVolumeConflicts = mappedChapter != null &&
                mappedChapter.volume != null &&
                observation.volume != null &&
                mappedChapter.volume != observation.volume
            val mappedIdentityConflicts = mappedChapter != null &&
                parsedIdentityIsReliable &&
                mappedChapter.identity.isSpecific &&
                (mappedChapter.identity != parsed.identity || mappedVolumeConflicts)
            if (mappedIdentityConflicts) {
                reasonCounts.increment(ChapterInventoryDiagnosticReason.IDENTITY_MISMATCH)
            }

            val identityCandidates = chaptersByIdentity[parsed.identity].orEmpty()
            val candidateResolution = if (parsedIdentityIsReliable) {
                CanonicalChapterCandidateResolver.resolve(
                    candidates = identityCandidates,
                    observedVolume = observation.volume,
                    hasExplicitVolumePrefix = observation.volume == null &&
                        volumeParser.hasExplicitVolumePrefix(observation.rawLabel),
                )
            } else {
                CanonicalChapterCandidateResolution.NoMatch
            }
            val ambiguousIdentity = candidateResolution == CanonicalChapterCandidateResolution.Ambiguous &&
                (mappedChapter == null || mappedIdentityConflicts)
            val reusableByIdentity = if (
                parsedIdentityIsReliable &&
                (mappedChapter == null || mappedIdentityConflicts) &&
                !ambiguousIdentity
            ) {
                (candidateResolution as? CanonicalChapterCandidateResolution.UniqueMatch)?.chapter
            } else {
                null
            }

            val mappedChapterHasVolumeVariants = mappedChapter != null &&
                chaptersByIdentity[mappedChapter.identity]
                    .orEmpty()
                    .map(CanonicalChapter::volume)
                    .distinct()
                    .size > 1
            val hasIndependentMappedSupport = mappedChapter != null &&
                mappedIdentityConflicts &&
                persistedEvidence.values.any { support ->
                    support.evidence.id != previousEvidence?.evidence?.id &&
                        support.mappedCanonicalChapterId == mappedChapter.id &&
                        isReliableSupportFor(
                            evidence = support.evidence,
                            chapter = mappedChapter,
                            volumeIsAmbiguous = mappedChapterHasVolumeVariants,
                        )
                }

            if (mappedIdentityConflicts && mappedChapter != null && !hasIndependentMappedSupport) {
                val conflicted = mappedChapter.copy(
                    confirmation = CanonicalChapterConfirmation.CONFLICTED,
                    updatedAt = clock(),
                )
                chapters[conflicted.id] = conflicted
                indexChapter(conflicted)
                chapterUpserts[conflicted.id] = conflicted
            }

            if (ambiguousIdentity) {
                provisionalCount++
                reasonCounts.increment(ChapterInventoryDiagnosticReason.IDENTITY_MISMATCH)
                stageEvidence(observation, mappedCanonicalChapterId = null)
                continue
            }

            // A reliable observation whose stable external key changed semantic
            // identity is re-homed to the correct logical chapter. The old
            // canonical row/user state is preserved; only the provider evidence
            // moves, preventing chapter 4 from ever resolving to chapter 126.
            val selected = when {
                mappedIdentityConflicts -> reusableByIdentity ?: newChapter(
                    canonicalTitleId = canonicalTitleId,
                    observation = observation,
                    parsedIdentity = parsed.identity,
                    displayNumber = parsed.displayNumber.ifBlank { observation.rawLabel.trim() },
                    parsedConfidence = parsed.confidence,
                )
                mappedChapter != null -> mappedChapter
                reusableByIdentity != null -> reusableByIdentity
                else -> newChapter(
                    canonicalTitleId = canonicalTitleId,
                    observation = observation,
                    parsedIdentity = parsed.identity,
                    displayNumber = parsed.displayNumber.ifBlank { observation.rawLabel.trim() },
                    parsedConfidence = parsed.confidence,
                )
            }

            val reconciled = selected.copy(
                volume = selected.volume ?: observation.volume,
                title = selected.title ?: observation.title,
                confidence = if (parsedIdentityIsReliable) {
                    maxOf(selected.confidence, minOf(observation.confidence, parsed.confidence))
                } else {
                    selected.confidence
                },
                confirmation = resolveConfirmation(
                    current = selected.confirmation,
                    authority = observation.authority,
                ),
                updatedAt = if (selected.id in chapters) clock() else selected.updatedAt,
            )

            chapters[reconciled.id] = reconciled
            indexChapter(reconciled)
            chapterUpserts[reconciled.id] = reconciled

            stageEvidence(observation, mappedCanonicalChapterId = reconciled.id)
            acceptedCount++
            reasonCounts.increment(ChapterInventoryDiagnosticReason.PERSISTED_MAPPED)
        }

        canonicalChapterRepository.upsertBatch(chapterUpserts.values.toList(), emptyList())
        evidenceRepository.upsertBatch(evidenceUpserts).forEach { persisted ->
            persistedEvidence[persisted.evidence.id] = persisted
        }

        val normalizedLabels = ChapterInventoryDiagnosticLabels.boundariesAndGaps(diagnosticLabels)
        val reconciliationOutcome = when {
            provisionalCount > 0 && acceptedCount == 0 -> ChapterInventoryDiagnosticOutcome.LOW_CONFIDENCE
            provisionalCount > 0 || discardedCount > 0 -> ChapterInventoryDiagnosticOutcome.PARTIAL
            else -> ChapterInventoryDiagnosticOutcome.SUCCESS
        }
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.RECONCILIATION,
                outcome = reconciliationOutcome,
                received = evidence.size,
                accepted = acceptedCount,
                provisional = provisionalCount,
                discarded = discardedCount,
                labels = normalizedLabels.first,
                gaps = normalizedLabels.second,
                reasons = reasonCounts.toMap(),
            ),
        )

        val persistedMapped = persistedEvidence.values.count { it.mappedCanonicalChapterId != null }
        val persistedUnmapped = persistedEvidence.size - persistedMapped
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.PERSISTENCE,
                outcome = if (persistedUnmapped > 0) {
                    ChapterInventoryDiagnosticOutcome.PARTIAL
                } else {
                    ChapterInventoryDiagnosticOutcome.SUCCESS
                },
                received = initialChapterCount,
                accepted = chapters.size,
                provisional = chapters.values.count {
                    it.confirmation == CanonicalChapterConfirmation.PROVISIONAL
                },
                discarded = 0,
                reasons = buildMap {
                    put(ChapterInventoryDiagnosticReason.PERSISTED_MAPPED, persistedMapped)
                    put(ChapterInventoryDiagnosticReason.PERSISTED_UNMAPPED, persistedUnmapped)
                    put(ChapterInventoryDiagnosticReason.CACHE_SNAPSHOT, initialChapterCount)
                    put(ChapterInventoryDiagnosticReason.REFRESHED_SNAPSHOT, chapters.size)
                },
            ),
        )
    }

    private fun MutableMap<ChapterInventoryDiagnosticReason, Int>.increment(
        reason: ChapterInventoryDiagnosticReason,
    ) {
        this[reason] = (this[reason] ?: 0) + 1
    }

    private fun isReliableSupportFor(
        evidence: ChapterEvidence,
        chapter: CanonicalChapter,
        volumeIsAmbiguous: Boolean,
    ): Boolean {
        val parsed = parser.execute(evidence.rawLabel, evidence.rawNumber)
        return evidence.confidence >= RELIABLE_CONFIDENCE &&
            parsed.confidence >= RELIABLE_CONFIDENCE &&
            parsed.identity.isSpecific &&
            parsed.identity == chapter.identity &&
            when {
                evidence.volume == null -> !volumeIsAmbiguous
                chapter.volume == null -> true
                else -> evidence.volume == chapter.volume
            }
    }

    private fun newChapter(
        canonicalTitleId: String,
        observation: ChapterEvidence,
        parsedIdentity: CanonicalChapterIdentity,
        displayNumber: String,
        parsedConfidence: Double,
    ): CanonicalChapter {
        val now = clock()
        return CanonicalChapter(
            id = idFactory(),
            canonicalTitleId = canonicalTitleId,
            displayNumber = displayNumber,
            volume = observation.volume,
            title = observation.title,
            type = parsedIdentity.type,
            baseNumber = parsedIdentity.baseNumber,
            part = parsedIdentity.part,
            alphaSuffix = parsedIdentity.alphaSuffix,
            confidence = minOf(observation.confidence, parsedConfidence),
            createdAt = now,
            updatedAt = now,
            confirmation = when (observation.authority) {
                ChapterEvidenceAuthority.EDITORIAL -> CanonicalChapterConfirmation.CONFIRMED
                ChapterEvidenceAuthority.ADDON_PROVISIONAL -> CanonicalChapterConfirmation.PROVISIONAL
            },
        )
    }

    private fun resolveConfirmation(
        current: CanonicalChapterConfirmation,
        authority: ChapterEvidenceAuthority,
    ): CanonicalChapterConfirmation = when {
        current == CanonicalChapterConfirmation.CONFLICTED -> CanonicalChapterConfirmation.CONFLICTED
        authority == ChapterEvidenceAuthority.EDITORIAL -> CanonicalChapterConfirmation.CONFIRMED
        current == CanonicalChapterConfirmation.CONFIRMED -> CanonicalChapterConfirmation.CONFIRMED
        else -> CanonicalChapterConfirmation.PROVISIONAL
    }

    private companion object {
        const val RELIABLE_CONFIDENCE = 0.95
    }

    private data class EvidenceExternalKey(
        val producerKind: ProducerKind,
        val producerId: String,
        val externalChapterKey: String,
    )
}
