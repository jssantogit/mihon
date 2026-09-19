package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class ImportLegacyCanonicalProgress(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
) {

    suspend fun execute(canonicalTitleId: String): Int {
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        val existingProgress = canonicalReadingRepository
            .getProgressByCanonicalTitleId(canonicalTitleId)
            .associateBy { it.canonicalChapterId }
        val historiesByMangaId = mutableMapOf<Long, Map<Long, History>>()
        var imported = 0

        for (canonicalChapter in chapters) {
            val variants = canonicalChapterRepository
                .getVariantsByCanonicalChapterId(canonicalChapter.id)
            if (variants.isEmpty()) continue

            val states = mutableListOf<LegacyState>()
            for (variant in variants) {
                val legacyChapter = findLegacyChapter(variant) ?: continue
                val histories = historiesByMangaId[legacyChapter.mangaId]
                    ?: historyRepository.getHistoryByMangaId(legacyChapter.mangaId)
                        .associateBy { it.chapterId }
                        .also { historiesByMangaId[legacyChapter.mangaId] = it }
                states += LegacyState(
                    variant = variant,
                    chapter = legacyChapter,
                    history = histories[legacyChapter.id],
                )
            }
            if (states.isEmpty()) continue

            val meaningfulStates = states.filter {
                it.chapter.read ||
                    it.chapter.lastPageRead > 0L ||
                    it.history?.readAt != null ||
                    (it.history?.readDuration ?: 0L) > 0L
            }
            if (meaningfulStates.isEmpty()) continue

            val active = meaningfulStates.maxWithOrNull(
                compareBy<LegacyState> { it.history?.readAt?.time ?: Long.MIN_VALUE }
                    .thenBy { it.chapter.lastPageRead }
                    .thenBy { if (it.chapter.read) 1 else 0 },
            ) ?: continue

            if (canonicalChapter.id !in existingProgress) {
                canonicalReadingRepository.upsertProgress(
                    CanonicalChapterProgress(
                        canonicalChapterId = canonicalChapter.id,
                        read = meaningfulStates.any { it.chapter.read },
                        lastPageRead = active.chapter.lastPageRead,
                        lastVariantId = active.variant.id,
                        updatedAt = active.history?.readAt?.time ?: 0L,
                    ),
                )
                imported++
            }

            if (canonicalReadingRepository.getHistory(canonicalChapter.id) == null) {
                val lastRead = meaningfulStates
                    .mapNotNull { state ->
                        state.history?.readAt?.time?.let { readAt -> state to readAt }
                    }
                    .maxByOrNull { it.second }
                if (lastRead != null) {
                    canonicalReadingRepository.recordHistory(
                        CanonicalChapterHistoryUpdate(
                            canonicalChapterId = canonicalChapter.id,
                            variantId = lastRead.first.variant.id,
                            readAt = lastRead.second,
                            sessionReadDuration = meaningfulStates.sumOf {
                                it.history?.readDuration ?: 0L
                            },
                        ),
                    )
                }
            }
        }

        return imported
    }

    private suspend fun findLegacyChapter(variant: ChapterVariant): Chapter? {
        val byId = variant.mihonChapterId
            ?.let { chapterRepository.getChapterById(it) }
            ?.takeIf { chapter ->
                variant.mihonMangaId == null || chapter.mangaId == variant.mihonMangaId
            }
        if (byId != null) return byId

        val mangaId = variant.mihonMangaId ?: return null
        val url = variant.sourceChapterUrl
            ?.takeIf(String::isNotBlank)
            ?: variant.sourceChapterId.takeIf(String::isNotBlank)
            ?: return null
        return chapterRepository.getChapterByUrlAndMangaId(url, mangaId)
    }

    private data class LegacyState(
        val variant: ChapterVariant,
        val chapter: Chapter,
        val history: History?,
    )
}
