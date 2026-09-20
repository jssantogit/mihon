package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.ParsedChapterLabel
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import java.util.UUID
import kotlin.time.Clock

/**
 * Repairs chapter-zero rows created before semantic zero placeholders were
 * understood by [ParseCanonicalChapterLabel].
 *
 * Older builds interpreted labels such as "Ch. 0 - Oneshot" as a regular
 * chapter zero. That could collapse an unrelated regular chapter zero into the
 * same canonical node. This repair is intentionally narrow: only semantic
 * labels already trapped inside a REGULAR/0 node are reconsidered.
 *
 * When existing canonical progress/history points at one of the affected
 * variants, that semantic group keeps the old canonical chapter ID so reading
 * state remains attached without rewriting progress or history.
 */
class RepairZeroPlaceholderChapterSemantics internal constructor(
    private val parser: ParseCanonicalChapterLabel,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        parser: ParseCanonicalChapterLabel,
        canonicalChapterRepository: CanonicalChapterRepository,
        canonicalReadingRepository: CanonicalReadingRepository,
    ) : this(
        parser = parser,
        canonicalChapterRepository = canonicalChapterRepository,
        canonicalReadingRepository = canonicalReadingRepository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(canonicalTitleId: String): Int {
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        val identities = linkedMapOf<CanonicalChapterIdentity, CanonicalChapter>()
        chapters
            .filter { it.identity.isSpecific }
            .forEach { chapter -> identities.putIfAbsent(chapter.identity, chapter) }

        var repaired = 0
        for (legacyZero in chapters) {
            if (!legacyZero.isLegacyRegularZero()) continue

            val variants = canonicalChapterRepository
                .getVariantsByCanonicalChapterId(legacyZero.id)
            if (variants.isEmpty()) continue

            val classified = variants.map { variant ->
                val parsed = parser.execute(variant.rawName, variant.rawNumberHint)
                ClassifiedVariant(
                    variant = variant,
                    parsed = parsed,
                    targetIdentity = if (parsed.type.isSemanticType()) {
                        parsed.identity
                    } else {
                        legacyZero.identity
                    },
                )
            }
            if (classified.all { it.targetIdentity == legacyZero.identity }) continue

            val groups = classified.groupBy(ClassifiedVariant::targetIdentity)
            val progressVariantId = canonicalReadingRepository
                .getProgress(legacyZero.id)
                ?.lastVariantId
            val historyVariantId = canonicalReadingRepository
                .getHistory(legacyZero.id)
                ?.lastVariantId
            val stateVariantId = progressVariantId ?: historyVariantId
            val stateIdentity = stateVariantId?.let { variantId ->
                classified.firstOrNull { it.variant.id == variantId }?.targetIdentity
            }

            var keepIdentity = stateIdentity
                ?: legacyZero.identity.takeIf(groups::containsKey)
                ?: groups.keys.minBy(CanonicalChapterIdentity::sortKey)

            val existingKeep = identities[keepIdentity]
                ?.takeIf { it.id != legacyZero.id }
            if (existingKeep != null && keepIdentity != legacyZero.identity) {
                // Merging two already-persisted canonical nodes requires moving
                // their independent reading state as well. Do not guess here.
                if (stateIdentity == keepIdentity || !groups.containsKey(legacyZero.identity)) {
                    continue
                }
                keepIdentity = legacyZero.identity
            }

            val now = clock()
            val chapterWrites = mutableListOf<CanonicalChapter>()
            val variantWrites = mutableListOf<ChapterVariant>()
            val createdTargets = mutableMapOf<CanonicalChapterIdentity, CanonicalChapter>()

            val keptChapter = if (keepIdentity == legacyZero.identity) {
                legacyZero
            } else {
                legacyZero.reclassified(
                    parsed = groups.getValue(keepIdentity).first().parsed,
                    now = now,
                ).also(chapterWrites::add)
            }

            for ((targetIdentity, group) in groups) {
                if (targetIdentity == keepIdentity) continue

                val target = identities[targetIdentity]
                    ?.takeIf { it.id != legacyZero.id }
                    ?: createdTargets[targetIdentity]
                    ?: createTargetChapter(
                        legacyZero = legacyZero,
                        classified = group.first(),
                        now = now,
                    ).also { created ->
                        createdTargets[targetIdentity] = created
                        chapterWrites += created
                    }

                group.forEach { classifiedVariant ->
                    if (classifiedVariant.variant.canonicalChapterId != target.id) {
                        variantWrites += classifiedVariant.variant.copy(
                            canonicalChapterId = target.id,
                            updatedAt = now,
                        )
                    }
                }
            }

            canonicalChapterRepository.upsertBatch(
                chapters = chapterWrites,
                variants = variantWrites,
            )

            if (identities[legacyZero.identity]?.id == legacyZero.id) {
                identities.remove(legacyZero.identity)
            }
            identities[keptChapter.identity] = keptChapter
            createdTargets.values.forEach { identities[it.identity] = it }

            repaired += variantWrites.size
            if (keptChapter.identity != legacyZero.identity) repaired++
        }

        return repaired
    }

    private fun createTargetChapter(
        legacyZero: CanonicalChapter,
        classified: ClassifiedVariant,
        now: Long,
    ): CanonicalChapter {
        return if (classified.targetIdentity == legacyZero.identity) {
            legacyZero.copy(
                id = idFactory(),
                createdAt = now,
                updatedAt = now,
            )
        } else {
            CanonicalChapter(
                id = idFactory(),
                canonicalTitleId = legacyZero.canonicalTitleId,
                displayNumber = classified.parsed.displayNumber,
                type = classified.parsed.type,
                baseNumber = classified.parsed.baseNumber,
                part = classified.parsed.part,
                alphaSuffix = classified.parsed.alphaSuffix,
                confidence = classified.parsed.confidence,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private fun CanonicalChapter.reclassified(
        parsed: ParsedChapterLabel,
        now: Long,
    ) = copy(
        displayNumber = parsed.displayNumber,
        type = parsed.type,
        baseNumber = parsed.baseNumber,
        part = parsed.part,
        alphaSuffix = parsed.alphaSuffix,
        confidence = parsed.confidence,
        updatedAt = now,
    )

    private fun CanonicalChapter.isLegacyRegularZero(): Boolean {
        return type == CanonicalChapterType.REGULAR &&
            baseNumber == 0 &&
            part == null &&
            alphaSuffix == null
    }

    private fun CanonicalChapterType.isSemanticType(): Boolean {
        return this != CanonicalChapterType.REGULAR &&
            this != CanonicalChapterType.UNKNOWN
    }

    private data class ClassifiedVariant(
        val variant: ChapterVariant,
        val parsed: ParsedChapterLabel,
        val targetIdentity: CanonicalChapterIdentity,
    )
}
