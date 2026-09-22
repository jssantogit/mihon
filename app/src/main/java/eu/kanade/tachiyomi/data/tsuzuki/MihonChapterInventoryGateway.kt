package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
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
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

/**
 * Reads Mihon's source/chapter boundary into neutral Tsuzuki observations.
 *
 * [fetch] is read-only. [materializeOperationalChapter] is an explicit compatibility
 * operation that may create a legacy Mihon chapter row, but never canonical state.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonChapterInventoryGateway(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    private val inventoryCache: MihonInventorySnapshotCache = MihonInventorySnapshotCache(),
) : ChapterInventoryGateway {

    override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> =
        fetch(mapping, refresh = false)

    suspend fun fetch(
        mapping: SourceTitleMapping,
        refresh: Boolean,
    ): Result<SourceChapterInventory> {
        val mihonMangaId = mapping.mihonMangaId
            ?: return Result.failure(
                IllegalArgumentException("Source mapping " + mapping.id + " is not materialized"),
            )
        val key = MihonInventoryKey(
            canonicalTitleId = mapping.canonicalTitleId,
            mappingId = mapping.id,
            sourceId = mapping.sourceId,
            mangaId = mihonMangaId,
            sourceUrl = mapping.sourceUrl,
            language = mapping.language,
        )
        return inventoryCache.getOrFetch(key, refresh) {
            fetchLive(mapping, mihonMangaId)
        }
    }

    private suspend fun fetchLive(
        mapping: SourceTitleMapping,
        mihonMangaId: Long,
    ): Result<SourceChapterInventory> {
        return try {
            val manga = mangaRepository.getMangaById(mihonMangaId)
            val source = sourceManager.get(mapping.sourceId)
                ?: error("Source " + mapping.sourceId + " is unavailable")
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

    suspend fun fetch(
        binding: ContentBinding,
        refresh: Boolean = false,
    ): Result<SourceChapterInventory> {
        return try {
            val payload = MihonContentBindingPayloadCodec.decode(binding.runtimePayload)
            fetch(
                SourceTitleMapping(
                    id = binding.id,
                    canonicalTitleId = binding.canonicalTitleId,
                    mihonMangaId = payload.mihonMangaId,
                    sourceId = payload.sourceId,
                    sourceUrl = payload.sourceUrl,
                    language = payload.language,
                    matchConfidence = binding.matchConfidence,
                    verifiedByUser = binding.verifiedByUser,
                    availability = SourceMappingAvailability.valueOf(binding.availability.name),
                    preferredOverride = false,
                    createdAt = binding.createdAt,
                    updatedAt = binding.updatedAt,
                ),
                refresh = refresh,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun materializeOperationalChapter(snapshot: SourceChapterSnapshot): Result<Long> {
        return try {
            val mangaId = snapshot.mihonMangaId
                ?: error("Source chapter snapshot has no materialized Mihon manga")
            val sourceUrl = snapshot.sourceChapterUrl
                .takeIf(String::isNotBlank)
                ?: snapshot.sourceChapterId.takeIf(String::isNotBlank)
                ?: error("Source chapter snapshot has no operational URL")

            val byStoredId = snapshot.mihonChapterId?.let { chapterId ->
                chapterRepository.getChapterById(chapterId)
            }
            val existing = byStoredId
                ?.takeIf { it.mangaId == mangaId && it.url == sourceUrl }
                ?: chapterRepository.getChapterByUrlAndMangaId(sourceUrl, mangaId)
            if (existing != null) return Result.success(existing.id)

            val chapter = Chapter.create().copy(
                mangaId = mangaId,
                url = sourceUrl,
                name = snapshot.rawName,
                scanlator = snapshot.scanlationGroup,
                chapterNumber = snapshot.rawNumberHint ?: -1.0,
                sourceOrder = snapshot.rawSourceOrder ?: 0L,
                dateUpload = snapshot.releaseDate ?: 0L,
                version = snapshot.version ?: 1L,
                memo = snapshot.rawSourceMetadata,
            )
            val inserted = chapterRepository.addAll(listOf(chapter)).singleOrNull()
                ?: error("Failed to materialize operational Mihon chapter")
            Result.success(inserted.id)
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
