package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.ChapterReconciliationReport
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import java.util.UUID
import kotlin.time.Clock

/**
 * Reconciles provider observations into Tsuzuki's canonical chapter graph.
 *
 * All writes happen after the inventory has been interpreted, and are sent as
 * one repository batch. Missing source rows are intentionally never deleted.
 */
class ReconcileChapterInventory internal constructor(
    private val parser: ParseCanonicalChapterLabel,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val idFactory: () -> String,
    private val variantIdFactory: () -> String = idFactory,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        parser: ParseCanonicalChapterLabel,
        canonicalChapterRepository: CanonicalChapterRepository,
    ) : this(
        parser = parser,
        canonicalChapterRepository = canonicalChapterRepository,
        idFactory = { UUID.randomUUID().toString() },
        variantIdFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(inventory: SourceChapterInventory): ChapterReconciliationReport =
        execute(listOf(inventory))

    /**
     * Reconciles multiple inventories for the same canonical title in one
     * in-memory graph and persists the result through a single atomic batch.
     */
    suspend fun execute(inventories: List<SourceChapterInventory>): ChapterReconciliationReport {
        require(inventories.isNotEmpty()) { "At least one chapter inventory is required" }

        val canonicalTitleId = inventories.first().canonicalTitleId
        require(canonicalTitleId.isNotBlank()) { "Canonical title id is required" }
        require(inventories.all { it.canonicalTitleId == canonicalTitleId }) {
            "All chapter inventories must belong to the same canonical title"
        }

        val existingChapters = canonicalChapterRepository
            .getByCanonicalTitleId(canonicalTitleId)
        val chaptersById = existingChapters.associateBy { it.id }.toMutableMap()
        val identities = linkedMapOf<CanonicalChapterIdentity, CanonicalChapter>()
        existingChapters
            .filter { it.identity.isSpecific }
            .forEach { identities.putIfAbsent(it.identity, it) }

        val variantsBySourceIdentity = linkedMapOf<Pair<Long, String>, ChapterVariant>()
        val touchedChapters = linkedMapOf<String, CanonicalChapter>()
        val newChapterIds = linkedSetOf<String>()
        val sourceMappingIds = linkedSetOf<String>()
        val now = clock()

        for (inventory in inventories) {
            require(inventory.sourceMappingId.isNotBlank()) { "Source mapping id is required" }
            sourceMappingIds += inventory.sourceMappingId

            for (snapshot in inventory.chapters) {
                require(snapshot.sourceMappingId == inventory.sourceMappingId) {
                    "Snapshot mapping ${snapshot.sourceMappingId} does not match inventory mapping ${inventory.sourceMappingId}"
                }
                require(snapshot.sourceId == inventory.sourceId) {
                    "Snapshot source ${snapshot.sourceId} does not match inventory source ${inventory.sourceId}"
                }

                val sourceId = snapshot.sourceId
                val sourceChapterId = snapshot.sourceChapterId.ifBlank { snapshot.sourceChapterUrl }
                require(sourceChapterId.isNotBlank()) { "Source chapter identity is required" }
                val sourceIdentity = sourceId to sourceChapterId
                val associatedVariant = variantsBySourceIdentity[sourceIdentity]
                    ?: canonicalChapterRepository.getVariantBySourceIdentity(
                        sourceId = sourceId,
                        sourceChapterId = sourceChapterId,
                    )

                val canonical = if (associatedVariant != null) {
                    // Persisted source identity is authoritative even if a later
                    // parser would interpret the changed label differently.
                    chaptersById[associatedVariant.canonicalChapterId]
                        ?: canonicalChapterRepository.getById(associatedVariant.canonicalChapterId)
                        ?: error("Variant ${associatedVariant.id} references missing canonical chapter")
                } else {
                    val parsed = parser.execute(snapshot.rawName, snapshot.rawNumberHint)
                    val matched = parsed.identity
                        .takeIf { it.isSpecific }
                        ?.let { identities[it] }
                    matched ?: CanonicalChapter(
                        id = idFactory(),
                        canonicalTitleId = canonicalTitleId,
                        displayNumber = parsed.displayNumber,
                        type = parsed.type,
                        baseNumber = parsed.baseNumber,
                        part = parsed.part,
                        alphaSuffix = parsed.alphaSuffix,
                        confidence = parsed.confidence,
                        createdAt = now,
                        updatedAt = now,
                    ).also { chapter ->
                        chaptersById[chapter.id] = chapter
                        if (chapter.identity.isSpecific) identities[chapter.identity] = chapter
                        newChapterIds += chapter.id
                    }
                }

                chaptersById[canonical.id] = canonical
                touchedChapters[canonical.id] = canonical
                val createdAt = associatedVariant?.createdAt
                    ?: snapshot.createdAt.takeIf { it != 0L }
                    ?: now
                val updatedAt = snapshot.updatedAt.takeIf { it != 0L } ?: now
                val variant = ChapterVariant(
                    id = associatedVariant?.id ?: variantIdFactory(),
                    canonicalChapterId = canonical.id,
                    sourceMappingId = snapshot.sourceMappingId.ifBlank { inventory.sourceMappingId },
                    sourceId = sourceId,
                    mihonMangaId = snapshot.mihonMangaId ?: inventory.mihonMangaId,
                    mihonChapterId = snapshot.mihonChapterId,
                    sourceChapterId = sourceChapterId,
                    sourceChapterUrl = snapshot.sourceChapterUrl.ifBlank { sourceChapterId },
                    language = snapshot.language.ifBlank { inventory.language },
                    scanlationGroup = snapshot.scanlationGroup,
                    version = snapshot.version,
                    releaseDate = snapshot.releaseDate,
                    rawName = snapshot.rawName,
                    rawNumberHint = snapshot.rawNumberHint,
                    rawSourceOrder = snapshot.rawSourceOrder,
                    rawSourceMetadata = snapshot.rawSourceMetadata,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
                variantsBySourceIdentity[sourceIdentity] = variant
                variant.sourceMappingId.takeIf(String::isNotBlank)?.let(sourceMappingIds::add)
            }
        }

        canonicalChapterRepository.upsertBatch(
            chapters = touchedChapters.values.filter { it.id in newChapterIds },
            variants = variantsBySourceIdentity.values.toList(),
        )
        return ChapterReconciliationReport(
            canonicalTitleId = canonicalTitleId,
            canonicalChapters = touchedChapters.values.toList(),
            variants = variantsBySourceIdentity.values.toList(),
            sourceMappingIds = sourceMappingIds,
            createdCanonicalChapterIds = newChapterIds,
        )
    }

    suspend operator fun invoke(inventory: SourceChapterInventory): ChapterReconciliationReport = execute(inventory)

    suspend operator fun invoke(inventories: List<SourceChapterInventory>): ChapterReconciliationReport =
        execute(inventories)
}
