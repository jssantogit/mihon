package tachiyomi.data.tsuzuki.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceWrite
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterEvidenceRepositoryImpl(
    private val database: Database,
) : ChapterEvidenceRepository {

    override suspend fun <T> withTransaction(block: suspend () -> T): T =
        database.transactionWithResult { block() }

    override suspend fun getByCanonicalTitleId(
        canonicalTitleId: String,
    ): List<PersistedChapterEvidence> {
        return database.tsuzuki_chapter_evidenceQueries
            .getTsuzukiChapterEvidenceByTitle(canonicalTitleId, ::mapEvidence)
            .awaitAsList()
    }

    override suspend fun getByProducerExternalKey(
        producerKind: ProducerKind,
        producerId: String,
        externalChapterKey: String,
    ): PersistedChapterEvidence? {
        return database.tsuzuki_chapter_evidenceQueries
            .getTsuzukiChapterEvidenceByProducerExternalKey(
                producerKind = producerKind.name,
                producerId = producerId,
                externalChapterKey = externalChapterKey,
                mapper = ::mapEvidence,
            )
            .awaitAsOneOrNull()
    }

    override suspend fun upsert(
        evidence: ChapterEvidence,
        mappedCanonicalChapterId: String?,
    ): PersistedChapterEvidence = upsertBatch(
        listOf(ChapterEvidenceWrite(evidence, mappedCanonicalChapterId)),
    ).single()

    override suspend fun upsertBatch(writes: List<ChapterEvidenceWrite>): List<PersistedChapterEvidence> =
        database.transactionWithResult {
            if (writes.isEmpty()) return@transactionWithResult emptyList()
            val canonicalTitleIds = writes.map { it.evidence.canonicalTitleId }.distinct()
            require(canonicalTitleIds.size == 1) { "Chapter evidence batch must belong to one canonical title" }

            val existingByTitle = getByCanonicalTitleId(canonicalTitleIds.single())
            val byId = existingByTitle.associateByTo(linkedMapOf()) { it.evidence.id }
            val byExternalKey = existingByTitle.mapNotNull { persisted ->
                persisted.evidence.externalChapterKey?.let { key ->
                    ExternalEvidenceKey(
                        persisted.evidence.producerKind,
                        persisted.evidence.producerId,
                        key,
                    ) to persisted
                }
            }.toMap().toMutableMap()

            writes.map { write ->
                val evidence = write.evidence
                val externalKey = evidence.externalChapterKey?.let {
                    ExternalEvidenceKey(evidence.producerKind, evidence.producerId, it)
                }
                val existingByExternalKey = externalKey?.let { key ->
                    byExternalKey[key] ?: getByProducerExternalKey(
                        producerKind = evidence.producerKind,
                        producerId = evidence.producerId,
                        externalChapterKey = key.externalChapterKey,
                    )?.also { existing ->
                        require(existing.evidence.canonicalTitleId == evidence.canonicalTitleId) {
                            "External chapter evidence identity is already attached to another canonical title"
                        }
                        byExternalKey[key] = existing
                    }
                }
                val existing = existingByExternalKey ?: byId[evidence.id]
                val stableEvidence = if (existing != null) evidence.copy(id = existing.evidence.id) else evidence
                val rawMetadata = existing?.rawMetadata ?: byteArrayOf()

                database.tsuzuki_chapter_evidenceQueries.upsertTsuzukiChapterEvidence(
                    id = stableEvidence.id,
                    canonicalTitleId = stableEvidence.canonicalTitleId,
                    producerKind = stableEvidence.producerKind.name,
                    producerId = stableEvidence.producerId,
                    externalChapterKey = stableEvidence.externalChapterKey,
                    rawLabel = stableEvidence.rawLabel,
                    rawNumber = stableEvidence.rawNumber,
                    volume = stableEvidence.volume?.toLong(),
                    title = stableEvidence.title,
                    observedAt = stableEvidence.observedAt,
                    confidence = stableEvidence.confidence,
                    authorityClass = stableEvidence.authority.name,
                    mappedCanonicalChapterId = write.mappedCanonicalChapterId,
                    rawMetadata = rawMetadata,
                )

                val persisted = PersistedChapterEvidence(
                    evidence = stableEvidence,
                    mappedCanonicalChapterId = write.mappedCanonicalChapterId,
                    rawMetadata = rawMetadata,
                )
                existing?.evidence?.externalChapterKey?.let { previousKey ->
                    if (previousKey != stableEvidence.externalChapterKey) {
                        byExternalKey.remove(
                            ExternalEvidenceKey(existing.evidence.producerKind, existing.evidence.producerId, previousKey),
                        )
                    }
                }
                byId[stableEvidence.id] = persisted
                if (externalKey != null) byExternalKey[externalKey] = persisted
                persisted
            }
        }

    private fun mapEvidence(
        id: String,
        canonicalTitleId: String,
        producerKind: String,
        producerId: String,
        externalChapterKey: String?,
        rawLabel: String,
        rawNumber: Double?,
        volume: Long?,
        title: String?,
        observedAt: Long,
        confidence: Double,
        authorityClass: String,
        mappedCanonicalChapterId: String?,
        rawMetadata: ByteArray,
    ) = PersistedChapterEvidence(
        evidence = ChapterEvidence(
            id = id,
            canonicalTitleId = canonicalTitleId,
            producerKind = ProducerKind.valueOf(producerKind),
            producerId = producerId,
            externalChapterKey = externalChapterKey,
            rawLabel = rawLabel,
            rawNumber = rawNumber,
            volume = volume?.toInt(),
            title = title,
            observedAt = observedAt,
            confidence = confidence,
            authority = ChapterEvidenceAuthority.valueOf(authorityClass),
        ),
        mappedCanonicalChapterId = mappedCanonicalChapterId,
        rawMetadata = rawMetadata,
    )

    private data class ExternalEvidenceKey(
        val producerKind: ProducerKind,
        val producerId: String,
        val externalChapterKey: String,
    )
}
