package tachiyomi.domain.tsuzuki.chapter.evidence

data class PersistedChapterEvidence(
    val evidence: ChapterEvidence,
    val mappedCanonicalChapterId: String?,
    val rawMetadata: ByteArray = byteArrayOf(),
)

data class ChapterEvidenceWrite(
    val evidence: ChapterEvidence,
    val mappedCanonicalChapterId: String?,
)

data class ChapterEvidenceSupportSnapshot(
    val mappedCanonicalChapterIds: Set<String> = emptySet(),
    val addonMappedChapterIds: Map<String, Set<String>> = emptyMap(),
)

interface ChapterEvidenceRepository {

    /** Run chapter and evidence writes against the same persistence transaction. */
    suspend fun <T> withTransaction(block: suspend () -> T): T = block()

    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence>

    suspend fun getSupportSnapshot(canonicalTitleId: String): ChapterEvidenceSupportSnapshot {
        val evidence = getByCanonicalTitleId(canonicalTitleId)
        val mappedCanonicalChapterIds = evidence.mapNotNullTo(linkedSetOf()) {
            it.mappedCanonicalChapterId
        }
        val addonMappedChapterIds = evidence.asSequence()
            .filter { it.evidence.producerKind == ProducerKind.ADDON }
            .mapNotNull { persisted ->
                persisted.mappedCanonicalChapterId?.let { chapterId ->
                    persisted.evidence.producerId to chapterId
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, chapterIds) -> chapterIds.toSet() }
        return ChapterEvidenceSupportSnapshot(
            mappedCanonicalChapterIds = mappedCanonicalChapterIds,
            addonMappedChapterIds = addonMappedChapterIds,
        )
    }

    suspend fun getByProducerExternalKey(
        producerKind: ProducerKind,
        producerId: String,
        externalChapterKey: String,
    ): PersistedChapterEvidence?

    suspend fun upsert(
        evidence: ChapterEvidence,
        mappedCanonicalChapterId: String?,
    ): PersistedChapterEvidence

    suspend fun upsertBatch(writes: List<ChapterEvidenceWrite>): List<PersistedChapterEvidence> =
        writes.map { write -> upsert(write.evidence, write.mappedCanonicalChapterId) }

    /**
     * Persists evidence whose stable id and raw metadata were already resolved from a title snapshot.
     *
     * The default preserves compatibility for in-memory/fake repositories. SQL-backed repositories
     * may override this to avoid reloading the same title snapshot during reconciliation persistence.
     */
    suspend fun upsertResolvedBatch(values: List<PersistedChapterEvidence>): List<PersistedChapterEvidence> =
        upsertBatch(
            values.map { value ->
                ChapterEvidenceWrite(
                    evidence = value.evidence,
                    mappedCanonicalChapterId = value.mappedCanonicalChapterId,
                )
            },
        )
}
