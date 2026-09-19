package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

/**
 * Reads Mihon's source/chapter boundary into neutral Tsuzuki observations.
 * The legacy repositories are input-only here; reconciliation owns all writes.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonChapterInventoryGateway(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
) : ChapterInventoryGateway {

    override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> {
        val mihonMangaId = mapping.mihonMangaId
            ?: return Result.failure(IllegalArgumentException("Source mapping ${mapping.id} is not materialized"))

        return try {
            val manga = mangaRepository.getMangaById(mihonMangaId)
            val source = sourceManager.get(mapping.sourceId)
                ?: error("Source ${mapping.sourceId} is unavailable")
            if (source is StubSource) throw SourceNotInstalledException()

            val legacyChapters = chapterRepository.getChapterByMangaId(mihonMangaId)
            val legacyByUrl = legacyChapters.associateBy { it.url }
            val update = source.getMangaUpdate(
                manga = manga.toSManga(),
                chapters = legacyChapters.map(Chapter::toSChapter),
                fetchDetails = false,
                fetchChapters = true,
            )

            val snapshots = update.chapters.mapIndexed { index, chapter ->
                val legacy = legacyByUrl[chapter.url]
                chapter.toSnapshot(
                    mapping = mapping,
                    mihonMangaId = mihonMangaId,
                    mihonChapterId = legacy?.id,
                    sourceOrder = legacy?.sourceOrder ?: index.toLong(),
                )
            }
            Result.success(
                SourceChapterInventory(
                    sourceMappingId = mapping.id,
                    sourceId = mapping.sourceId,
                    canonicalTitleId = mapping.canonicalTitleId,
                    chapters = snapshots,
                    mihonMangaId = mihonMangaId,
                    language = mapping.language,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun SChapter.toSnapshot(
        mapping: SourceTitleMapping,
        mihonMangaId: Long,
        mihonChapterId: Long?,
        sourceOrder: Long,
    ) = SourceChapterSnapshot(
        sourceId = mapping.sourceId,
        sourceMappingId = mapping.id,
        sourceChapterId = url,
        sourceChapterUrl = url,
        rawName = name,
        language = mapping.language,
        scanlationGroup = scanlator,
        releaseDate = date_upload,
        rawNumberHint = chapter_number.toDouble(),
        rawSourceOrder = sourceOrder,
        mihonMangaId = mihonMangaId,
        mihonChapterId = mihonChapterId,
        rawSourceMetadata = memo,
    )
}
