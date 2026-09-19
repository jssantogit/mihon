package tachiyomi.domain.tsuzuki.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

/** Persistence boundary for Tsuzuki's canonical chapter graph. */
interface CanonicalChapterRepository {

    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter>

    fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>>

    suspend fun getById(id: String): CanonicalChapter?

    suspend fun getVariantBySourceIdentity(
        sourceId: Long,
        sourceChapterId: String,
    ): ChapterVariant?

    suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant>

    suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant>

    suspend fun upsert(chapter: CanonicalChapter)

    suspend fun upsertVariant(variant: ChapterVariant)

    /** Upserts one inventory batch atomically, or rolls the entire batch back. */
    suspend fun upsertBatch(
        chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    )

    suspend fun getChaptersByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
        getByCanonicalTitleId(canonicalTitleId)

    fun observeChaptersByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
        observeByCanonicalTitleId(canonicalTitleId)

    fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
        observeByCanonicalTitleId(canonicalTitleId)

    suspend fun getVariantBySourceId(
        sourceId: Long,
        sourceChapterId: String,
    ): ChapterVariant? = getVariantBySourceIdentity(sourceId, sourceChapterId)

    suspend fun getVariantsByCanonicalChapter(canonicalChapterId: String): List<ChapterVariant> =
        getVariantsByCanonicalChapterId(canonicalChapterId)

    suspend fun getVariantsBySourceMapping(sourceMappingId: String): List<ChapterVariant> =
        getVariantsBySourceMappingId(sourceMappingId)

    suspend fun upsertCanonicalChapter(chapter: CanonicalChapter) = upsert(chapter)

    suspend fun upsertReconciliationBatch(
        chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) = upsertBatch(chapters, variants)
}
