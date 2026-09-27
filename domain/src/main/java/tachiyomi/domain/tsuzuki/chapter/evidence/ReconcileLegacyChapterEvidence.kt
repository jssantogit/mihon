package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import java.util.UUID

/**
 * Staged writer for materialized Mihon inventories. Reconciliation is always
 * authoritative for chapter identity; the operational variants are derived
 * from *persisted* mapped evidence inside the same SQLDelight transaction.
 *
 * Not wired into RefreshCanonicalChapters or the Reader until behavioral
 * equivalence and Android process-restart checks are complete.
 */
@Inject
class ReconcileLegacyChapterEvidence(
    private val adapter: LegacyInventoryEvidenceAdapter,
    private val reconciler: ReconcileChapterEvidence,
    private val chapters: CanonicalChapterRepository,
    private val sourceMappings: SourceTitleMappingRepository,
) {
    suspend fun execute(
        inventories: List<SourceChapterInventory>,
        observedAt: Long,
    ): List<ChapterVariant> {
        require(inventories.isNotEmpty()) { "At least one legacy inventory is required" }
        val canonicalTitleId = inventories.first().canonicalTitleId
        require(canonicalTitleId.isNotBlank()) { "Canonical title is required" }
        require(inventories.all { it.canonicalTitleId == canonicalTitleId }) {
            "All legacy inventories must belong to the same title"
        }
        require(inventories.map { it.sourceId }.toSet().size == inventories.size) {
            "A legacy source cannot be reconciled twice in one transaction"
        }

        val snapshotsByEvidenceKey =
            linkedMapOf<Pair<String, String>, Pair<SourceChapterInventory, SourceChapterSnapshot>>()
        val evidence = inventories.flatMap { inventory ->
            val firstBySourceKey = linkedMapOf<String, SourceChapterSnapshot>()
            inventory.chapters.forEach { snapshot ->
                firstBySourceKey.putIfAbsent(
                    snapshot.sourceChapterId.ifBlank { snapshot.sourceChapterUrl },
                    snapshot,
                )
            }
            adapter.adapt(inventory, observedAt).onEach { observation ->
                val key = requireNotNull(observation.externalChapterKey)
                val identity = observation.producerId to key
                check(
                    snapshotsByEvidenceKey.putIfAbsent(
                        identity,
                        inventory to firstBySourceKey.getValue(key),
                    ) == null,
                ) { "Duplicate source evidence identity in a legacy reconciliation batch" }
            }
        }
        // Empty provider inventories are non-destructive, but an obsolete
        // source mapping must still fail instead of masquerading as success.
        if (evidence.isEmpty()) {
            return reconciler.executeAndProject(canonicalTitleId, evidence) {
                validateMappings(canonicalTitleId, inventories)
                emptyList<ChapterVariant>()
            }
        }

        return reconciler.executeAndProject(canonicalTitleId, evidence) { persisted ->
            // Read authoritative materialized mappings *inside* the chapter/evidence
            // transaction. An old inventory or a mapping reassigned during fetch
            // may never publish a cross-title or cross-source operational variant.
            validateMappings(canonicalTitleId, inventories)

            val variants = mutableListOf<ChapterVariant>()
            for (record in persisted) {
                val key = requireNotNull(record.evidence.externalChapterKey)
                val (inventory, snapshot) = requireNotNull(
                    snapshotsByEvidenceKey[record.evidence.producerId to key],
                ) { "Persisted chapter evidence has no corresponding source observation" }
                val existing = chapters.getVariantBySourceIdentity(inventory.sourceId, key)
                if (existing != null) {
                    check(existing.sourceMappingId == inventory.sourceMappingId) {
                        "Existing operational variant belongs to another source mapping"
                    }
                    check(chapters.getById(existing.canonicalChapterId)?.canonicalTitleId == canonicalTitleId) {
                        "Existing operational variant belongs to another canonical title"
                    }
                }
                val mappedId = record.mappedCanonicalChapterId
                if (mappedId == null) {
                    check(existing == null) {
                        "An unresolved observation cannot reaffirm an old operational variant"
                    }
                    // Low-confidence evidence remains stored but is not readable.
                    continue
                }
                check(chapters.getById(mappedId)?.canonicalTitleId == canonicalTitleId) {
                    "Projected chapter mapping points outside the current title"
                }
                variants += ChapterVariant(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    canonicalChapterId = mappedId,
                    sourceMappingId = inventory.sourceMappingId,
                    sourceId = inventory.sourceId,
                    mihonMangaId = snapshot.mihonMangaId ?: inventory.mihonMangaId ?: existing?.mihonMangaId,
                    mihonChapterId = snapshot.mihonChapterId ?: existing?.mihonChapterId,
                    sourceChapterId = key,
                    sourceChapterUrl = snapshot.sourceChapterUrl.ifBlank { key },
                    language = snapshot.language.ifBlank { inventory.language },
                    scanlationGroup = snapshot.scanlationGroup ?: existing?.scanlationGroup,
                    version = snapshot.version ?: existing?.version,
                    releaseDate = snapshot.releaseDate ?: existing?.releaseDate,
                    rawName = snapshot.rawName,
                    rawNumberHint = snapshot.rawNumberHint,
                    rawSourceOrder = snapshot.rawSourceOrder,
                    rawSourceMetadata = snapshot.rawSourceMetadata,
                    createdAt = existing?.createdAt ?: snapshot.createdAt.takeIf { it > 0L } ?: observedAt,
                    updatedAt = snapshot.updatedAt.takeIf { it > 0L } ?: observedAt,
                )
            }
            // The reconciler still owns the outer evidence transaction and
            // per-title mutation gate. The repository's nested batch joins it.
            chapters.upsertBatch(emptyList(), variants)
            variants
        }
    }

    /**
     * Re-read the persisted mapping after inventory fetch. Nonempty batches call
     * this inside executeAndProject's transaction; empty batches have no writes.
     * The source *title* URL must match even when manga/source IDs were reused.
     */
    private suspend fun validateMappings(
        canonicalTitleId: String,
        inventories: List<SourceChapterInventory>,
    ) {
        val mappingsById = sourceMappings.getByCanonicalTitleId(canonicalTitleId).associateBy { it.id }
        for (inventory in inventories) {
            val mapping = mappingsById[inventory.sourceMappingId]
                ?: throw IllegalArgumentException("Legacy source mapping is missing or owned by another title")
            require(mapping.sourceId == inventory.sourceId) {
                "Legacy source ID does not match the persisted source mapping"
            }
            require(inventory.sourceUrl.isNotBlank() && inventory.sourceUrl == mapping.sourceUrl) {
                "Legacy inventory source URL changed since fetch"
            }
            require(mapping.availability != SourceMappingAvailability.UNAVAILABLE) {
                "Unavailable source mapping cannot publish chapters"
            }
            require(mapping.mihonMangaId != null && mapping.mihonMangaId == inventory.mihonMangaId) {
                "Legacy inventory must match a materialized Mihon manga"
            }
            require(inventory.language.isBlank() || inventory.language == mapping.language) {
                "Legacy inventory language does not match the source mapping"
            }
            require(inventory.chapters.all { it.mihonMangaId == null || it.mihonMangaId == mapping.mihonMangaId }) {
                "Legacy chapter observation belongs to another Mihon manga"
            }
        }
    }
}
