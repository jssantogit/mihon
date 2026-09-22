package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
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
) {

    @Inject
    constructor(
        parser: ParseCanonicalChapterLabel,
        canonicalChapterRepository: CanonicalChapterRepository,
        evidenceRepository: ChapterEvidenceRepository,
        mutationGate: ChapterMutationGate,
    ) : this(
        parser = parser,
        canonicalChapterRepository = canonicalChapterRepository,
        evidenceRepository = evidenceRepository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
        mutationGate = mutationGate,
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
    )

    suspend fun execute(
        canonicalTitleId: String,
        evidence: List<ChapterEvidence>,
    ) {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id is required" }
        require(evidence.all { it.canonicalTitleId == canonicalTitleId }) {
            "All chapter evidence must belong to canonical title $canonicalTitleId"
        }
        if (evidence.isEmpty()) return
        mutationGate.withLock {
            reconcileUncontended(canonicalTitleId, evidence)
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

        for (observation in evidence) {
            val parsed = parser.execute(observation.rawLabel, observation.rawNumber)
            val parsedIdentityIsReliable = observation.confidence >= RELIABLE_CONFIDENCE &&
                parsed.confidence >= RELIABLE_CONFIDENCE &&
                parsed.identity.isSpecific

            if (
                observation.authority == ChapterEvidenceAuthority.ADDON_PROVISIONAL &&
                !parsedIdentityIsReliable
            ) {
                val persisted = evidenceRepository.upsert(
                    evidence = observation,
                    mappedCanonicalChapterId = null,
                )
                persistedEvidence[persisted.evidence.id] = persisted
                continue
            }

            val externalEvidence = observation.externalChapterKey?.let { externalKey ->
                evidenceRepository.getByProducerExternalKey(
                    producerKind = observation.producerKind,
                    producerId = observation.producerId,
                    externalChapterKey = externalKey,
                )
            }
            val previousEvidence = externalEvidence ?: persistedEvidence[observation.id]
            val mappedChapterId = previousEvidence?.mappedCanonicalChapterId
            val mappedChapter = if (mappedChapterId != null) {
                canonicalChapterRepository.getById(mappedChapterId)?.also { chapter ->
                    require(chapter.canonicalTitleId == canonicalTitleId) {
                        "Evidence mapping points to chapter from another canonical title"
                    }
                }
            } else {
                null
            }

            val mappedIdentityConflicts = mappedChapter != null &&
                parsedIdentityIsReliable &&
                mappedChapter.identity.isSpecific &&
                mappedChapter.identity != parsed.identity

            val reusableByIdentity = if (
                parsedIdentityIsReliable &&
                (mappedChapter == null || mappedIdentityConflicts)
            ) {
                chapters.values.firstOrNull { chapter ->
                    chapter.identity.isSpecific && chapter.identity == parsed.identity
                }
            } else {
                null
            }

            val hasIndependentMappedSupport = mappedChapter != null &&
                mappedIdentityConflicts &&
                persistedEvidence.values.any { support ->
                    support.evidence.id != previousEvidence?.evidence?.id &&
                        support.mappedCanonicalChapterId == mappedChapter.id &&
                        isReliableSupportFor(support.evidence, mappedChapter)
                }

            if (mappedIdentityConflicts && mappedChapter != null && !hasIndependentMappedSupport) {
                val conflicted = mappedChapter.copy(
                    confirmation = CanonicalChapterConfirmation.CONFLICTED,
                    updatedAt = clock(),
                )
                canonicalChapterRepository.upsert(conflicted)
                chapters[conflicted.id] = conflicted
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

            canonicalChapterRepository.upsert(reconciled)
            chapters[reconciled.id] = reconciled

            val persisted = evidenceRepository.upsert(
                evidence = observation,
                mappedCanonicalChapterId = reconciled.id,
            )
            persistedEvidence[persisted.evidence.id] = persisted
        }
    }

    private fun isReliableSupportFor(
        evidence: ChapterEvidence,
        chapter: CanonicalChapter,
    ): Boolean {
        val parsed = parser.execute(evidence.rawLabel, evidence.rawNumber)
        return evidence.confidence >= RELIABLE_CONFIDENCE &&
            parsed.confidence >= RELIABLE_CONFIDENCE &&
            parsed.identity.isSpecific &&
            parsed.identity == chapter.identity
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
}
