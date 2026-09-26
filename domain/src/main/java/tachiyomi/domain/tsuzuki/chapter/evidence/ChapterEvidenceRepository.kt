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

interface ChapterEvidenceRepository {

    /** Run chapter and evidence writes against the same persistence transaction. */
    suspend fun <T> withTransaction(block: suspend () -> T): T = block()

    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence>

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
}
