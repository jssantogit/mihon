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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import org.json.JSONException
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticLabels
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import java.math.BigDecimal
import kotlin.time.TimeSource

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
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
    private val chapterLabelParser: ParseCanonicalChapterLabel = ParseCanonicalChapterLabel(),
) : ChapterInventoryGateway {

    override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> =
        fetch(mapping, refresh = false)

    suspend fun fetch(
        mapping: SourceTitleMapping,
        refresh: Boolean,
    ): Result<SourceChapterInventory> {
        val mihonMangaId = mapping.mihonMangaId
            ?: return Result.failure<SourceChapterInventory>(
                IllegalArgumentException("Source mapping " + mapping.id + " is not materialized"),
            ).also {
                recordInventoryFailure(
                    canonicalTitleId = mapping.canonicalTitleId,
                    sourceId = mapping.sourceId,
                    language = mapping.language,
                    addonId = null,
                    error = it.exceptionOrNull()!!,
                    elapsedMillis = 0L,
                    reason = ChapterInventoryDiagnosticReason.BINDING_NOT_MATERIALIZED,
                )
            }
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
        val totalStart = TimeSource.Monotonic.markNow()
        return try {
            val manga = mangaRepository.getMangaById(mihonMangaId)
            val source = sourceManager.get(mapping.sourceId)
                ?: error("Source " + mapping.sourceId + " is unavailable")
            if (source is StubSource) throw SourceNotInstalledException()

            val legacyChapters = chapterRepository.getChapterByMangaId(mihonMangaId)
            val legacyByUrl = legacyChapters.associateBy { it.url }
            val networkStart = TimeSource.Monotonic.markNow()
            val update = source.getMangaUpdate(
                manga = manga.toSManga(),
                chapters = legacyChapters.map(Chapter::toSChapter),
                fetchDetails = false,
                fetchChapters = true,
            )

            val networkTime = networkStart.elapsedNow()
            recordInventorySuccess(
                canonicalTitleId = mapping.canonicalTitleId,
                sourceId = mapping.sourceId,
                language = mapping.language,
                chapters = update.chapters,
                elapsedMillis = networkTime.inWholeMilliseconds,
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
            logcat {
                "TsuzukiPerf inventory source=${mapping.sourceId} chapters=${snapshots.size} " +
                    "network=$networkTime total=${totalStart.elapsedNow()}"
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
            if (error is TimeoutCancellationException) {
                recordInventoryFailure(
                    canonicalTitleId = mapping.canonicalTitleId,
                    sourceId = mapping.sourceId,
                    language = mapping.language,
                    addonId = null,
                    error = error,
                    elapsedMillis = totalStart.elapsedNow().inWholeMilliseconds,
                )
            }
            throw error
        } catch (error: Throwable) {
            val structuredError = error.toStructuredChapterInventoryFailure()
            recordInventoryFailure(
                canonicalTitleId = mapping.canonicalTitleId,
                sourceId = mapping.sourceId,
                language = mapping.language,
                addonId = null,
                error = structuredError,
                elapsedMillis = totalStart.elapsedNow().inWholeMilliseconds,
            )
            logcat {
                "TsuzukiPerf inventory source=${mapping.sourceId} failed=${error.javaClass.simpleName} " +
                    "total=${totalStart.elapsedNow()}"
            }
            Result.failure(structuredError)
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
            recordInventoryFailure(
                canonicalTitleId = binding.canonicalTitleId,
                sourceId = null,
                language = null,
                addonId = binding.addonId.value,
                error = error,
                elapsedMillis = 0L,
            )
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

    private fun recordInventorySuccess(
        canonicalTitleId: String,
        sourceId: Long,
        language: String,
        chapters: List<SChapter>,
        elapsedMillis: Long,
    ) {
        if (!isDiagnosticsRecording(canonicalTitleId)) return
        val normalizedLabels = chapters.mapNotNull(::diagnosticLabel)
        val (labels, gaps) = ChapterInventoryDiagnosticLabels.boundariesAndGaps(normalizedLabels)
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY,
                outcome = if (chapters.isEmpty()) {
                    ChapterInventoryDiagnosticOutcome.EMPTY
                } else {
                    ChapterInventoryDiagnosticOutcome.SUCCESS
                },
                sourceId = sourceId,
                language = language,
                elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                received = chapters.size,
                accepted = chapters.size,
                provisional = 0,
                discarded = 0,
                labels = labels,
                gaps = gaps,
                reasons = if (chapters.isEmpty()) {
                    mapOf(ChapterInventoryDiagnosticReason.INVENTORY_EMPTY to 1)
                } else {
                    emptyMap()
                },
            ),
        )
    }

    private fun recordInventoryFailure(
        canonicalTitleId: String,
        sourceId: Long?,
        language: String?,
        addonId: String?,
        error: Throwable,
        elapsedMillis: Long,
        reason: ChapterInventoryDiagnosticReason? = null,
    ) {
        val (outcome, failureReason) = ChapterInventoryDiagnosticFailures.classify(error)
        val reasons = mapOf((reason ?: failureReason) to 1)
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY,
                outcome = outcome,
                sourceId = sourceId,
                addonId = addonId,
                language = language,
                httpStatus = error.diagnosticHttpStatus(),
                elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                received = 0,
                accepted = 0,
                provisional = 0,
                discarded = 0,
                reasons = reasons,
            ),
        )
    }

    private fun diagnosticLabel(chapter: SChapter): String? {
        val parsed = runCatching { chapterLabelParser.execute(chapter.name, chapter.chapter_number) }
            .getOrNull()
        ChapterInventoryDiagnosticLabels.fromIdentity(parsed?.identity ?: return numericLabel(chapter.chapter_number))
            ?.let { return it }
        return numericLabel(chapter.chapter_number)
    }

    private fun numericLabel(value: Float): String? {
        if (!value.isFinite() || value < 0f || value > 999_999_999f) return null
        return runCatching { BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }.getOrNull()
    }

    private fun isDiagnosticsRecording(canonicalTitleId: String): Boolean = try {
        diagnostics.isRecording(canonicalTitleId)
    } catch (_: Exception) {
        false
    }
}

/** Structures a chapter-list failure at the Mihon boundary while retaining its original cause. */
internal fun Throwable.toStructuredChapterInventoryFailure(): ReadingSourceSearchFailure {
    if (this is ReadingSourceSearchFailure) return this
    val causes = generateSequence(this) { it.cause }.take(5).toList()
    val httpStatus = diagnosticHttpStatus()
    val kind = when {
        httpStatus != null -> ReadingSourceFailureKind.HTTP_RESPONSE
        causes.any { it is java.net.SocketTimeoutException || it is TimeoutCancellationException } ->
            ReadingSourceFailureKind.TIMEOUT
        causes.any { it is JSONException || it is SerializationException } ->
            ReadingSourceFailureKind.MALFORMED_RESPONSE
        causes.any { cause ->
            val detail = cause.message.orEmpty()
            detail.contains("captcha_required", ignoreCase = true) ||
                detail.contains("shape-selecting captcha", ignoreCase = true)
        } -> ReadingSourceFailureKind.CAPTCHA_REQUIRED
        causes.any {
            it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketException
        } -> ReadingSourceFailureKind.NETWORK_FAILURE
        causes.any { it is java.io.IOException } -> ReadingSourceFailureKind.INDETERMINATE
        else -> ReadingSourceFailureKind.EXTENSION_FAILURE
    }
    return ReadingSourceSearchFailure(kind = kind, httpStatus = httpStatus, cause = this)
}
