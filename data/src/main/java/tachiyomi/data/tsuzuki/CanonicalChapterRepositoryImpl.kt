package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalChapterRepositoryImpl(
    private val database: Database,
) : CanonicalChapterRepository {

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> {
        return database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChaptersByTitle(canonicalTitleId, ::mapChapter)
            .awaitAsList()
            .sortedWith(CHAPTER_COMPARATOR)
    }

    override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> {
        return database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChaptersByTitle(canonicalTitleId, ::mapChapter)
            .subscribeToList()
            .map { chapters -> chapters.sortedWith(CHAPTER_COMPARATOR) }
    }

    override suspend fun getById(id: String): CanonicalChapter? {
        return database.tsuzuki_canonical_chaptersQueries
            .getTsuzukiCanonicalChapterById(id, ::mapChapter)
            .awaitAsOneOrNull()
    }

    override suspend fun getVariantBySourceIdentity(
        sourceId: Long,
        sourceChapterId: String,
    ): ChapterVariant? {
        return database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantBySourceIdentity(
                sourceId = sourceId,
                sourceChapterId = sourceChapterId,
                mapper = ::mapVariant,
            )
            .awaitAsOneOrNull()
    }

    override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> {
        return database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantsByCanonicalChapter(canonicalChapterId, ::mapVariant)
            .awaitAsList()
    }

    override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> {
        return database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantsBySourceMapping(sourceMappingId, ::mapVariant)
            .awaitAsList()
    }

    override suspend fun upsert(chapter: CanonicalChapter) {
        database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
            id = chapter.id,
            canonicalTitleId = chapter.canonicalTitleId,
            displayNumber = chapter.displayNumber,
            volume = chapter.volume?.toLong(),
            title = chapter.title,
            type = chapter.type.name,
            baseNumber = chapter.baseNumber?.toLong(),
            part = chapter.part?.toLong(),
            alphaSuffix = chapter.alphaSuffix,
            confidence = chapter.confidence,
            createdAt = chapter.createdAt,
            updatedAt = chapter.updatedAt,
            confirmationState = chapter.confirmation.name,
        )
    }

    override suspend fun upsertVariant(variant: ChapterVariant) {
        upsertVariantInternal(variant)
    }

    override suspend fun upsertBatch(
        chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) {
        database.transaction {
            chapters.forEach { chapter ->
                database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
                    id = chapter.id,
                    canonicalTitleId = chapter.canonicalTitleId,
                    displayNumber = chapter.displayNumber,
                    volume = chapter.volume?.toLong(),
                    title = chapter.title,
                    type = chapter.type.name,
                    baseNumber = chapter.baseNumber?.toLong(),
                    part = chapter.part?.toLong(),
                    alphaSuffix = chapter.alphaSuffix,
                    confidence = chapter.confidence,
                    createdAt = chapter.createdAt,
                    updatedAt = chapter.updatedAt,
                    confirmationState = chapter.confirmation.name,
                )
            }
            variants.forEach { variant -> upsertVariantInternal(variant) }
        }
    }

    private suspend fun upsertVariantInternal(variant: ChapterVariant) {
        val existing = database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantBySourceIdentity(
                sourceId = variant.sourceId,
                sourceChapterId = variant.sourceChapterId,
                mapper = ::mapVariant,
            )
            .awaitAsOneOrNull()
        val stableVariant = if (existing == null || existing.id == variant.id) {
            variant
        } else {
            variant.copy(id = existing.id)
        }
        database.tsuzuki_chapter_variantsQueries.upsertTsuzukiChapterVariant(
            id = stableVariant.id,
            canonicalChapterId = stableVariant.canonicalChapterId,
            sourceMappingId = stableVariant.sourceMappingId,
            sourceId = stableVariant.sourceId,
            mihonMangaId = stableVariant.mihonMangaId,
            mihonChapterId = stableVariant.mihonChapterId,
            sourceChapterId = stableVariant.sourceChapterId,
            sourceChapterUrl = stableVariant.sourceChapterUrl,
            language = stableVariant.language,
            scanlationGroup = stableVariant.scanlationGroup,
            version = stableVariant.version,
            releaseDate = stableVariant.releaseDate,
            rawName = stableVariant.rawName,
            rawNumberHint = stableVariant.rawNumberHint,
            rawSourceOrder = stableVariant.rawSourceOrder,
            rawSourceMetadata = MemoColumnAdapter.encode(stableVariant.rawSourceMetadata),
            createdAt = stableVariant.createdAt,
            updatedAt = stableVariant.updatedAt,
        )
    }

    private fun mapChapter(
        id: String,
        canonicalTitleId: String,
        displayNumber: String,
        volume: Long?,
        title: String?,
        type: String,
        baseNumber: Long?,
        part: Long?,
        alphaSuffix: String?,
        confidence: Double,
        createdAt: Long,
        updatedAt: Long,
        confirmationState: String,
    ) = CanonicalChapter(
        id = id,
        canonicalTitleId = canonicalTitleId,
        displayNumber = displayNumber,
        volume = volume?.toInt(),
        title = title,
        type = CanonicalChapterType.valueOf(type),
        baseNumber = baseNumber?.toInt(),
        part = part?.toInt(),
        alphaSuffix = alphaSuffix,
        confidence = confidence,
        createdAt = createdAt,
        updatedAt = updatedAt,
        confirmation = CanonicalChapterConfirmation.valueOf(confirmationState),
    )

    private fun mapVariant(
        id: String,
        canonicalChapterId: String,
        sourceMappingId: String,
        sourceId: Long,
        mihonMangaId: Long?,
        mihonChapterId: Long?,
        sourceChapterId: String,
        sourceChapterUrl: String?,
        language: String,
        scanlationGroup: String?,
        version: Long?,
        releaseDate: Long?,
        rawName: String,
        rawNumberHint: Double?,
        rawSourceOrder: Long?,
        rawSourceMetadata: ByteArray,
        createdAt: Long,
        updatedAt: Long,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = canonicalChapterId,
        sourceMappingId = sourceMappingId,
        sourceId = sourceId,
        mihonMangaId = mihonMangaId,
        mihonChapterId = mihonChapterId,
        sourceChapterId = sourceChapterId,
        sourceChapterUrl = sourceChapterUrl,
        language = language,
        scanlationGroup = scanlationGroup,
        version = version,
        releaseDate = releaseDate,
        rawName = rawName,
        rawNumberHint = rawNumberHint,
        rawSourceOrder = rawSourceOrder,
        rawSourceMetadata = MemoColumnAdapter.decode(rawSourceMetadata),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private companion object {
        val CHAPTER_COMPARATOR = compareBy<CanonicalChapter>({
            CanonicalChapterIdentity(
                type = it.type,
                baseNumber = it.baseNumber,
                part = it.part,
                alphaSuffix = it.alphaSuffix,
            )
        }, { it.id })
    }
}
