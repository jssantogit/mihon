package tachiyomi.domain.tsuzuki.chapter.evidence

data class PersistedChapterEvidence(
    val evidence: ChapterEvidence,
    val mappedCanonicalChapterId: String?,
    val rawMetadata: ByteArray = byteArrayOf(),
)

interface ChapterEvidenceRepository {
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
}
