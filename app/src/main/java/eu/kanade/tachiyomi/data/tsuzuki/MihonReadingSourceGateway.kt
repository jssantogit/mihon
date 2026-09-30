package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayload
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.json.JSONException
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceFailureKind
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceSearchFailure
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonReadingSourceGateway(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : ReadingSourceGateway {

    override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> {
        val disabledSources = sourcePreferences.disabledSources.get()
        return sourceManager.getAll()
            .filterIsInstance<CatalogueSource>()
            .filter { it.id.toString() !in disabledSources }
            .filter { it.lang.equals(language, ignoreCase = true) }
            .map { source ->
                ReadingSourceDescriptor(
                    sourceId = source.id,
                    name = source.name,
                    language = source.lang,
                )
            }
    }

    override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
        val disabledSources = sourcePreferences.disabledSources.get()
        if (sourceId.toString() in disabledSources) {
            return Result.failure(
                ReadingSourceSearchFailure(
                    kind = ReadingSourceFailureKind.SOURCE_DISABLED,
                    cause = IllegalStateException("Source $sourceId is disabled"),
                ),
            )
        }

        val source = sourceManager.get(sourceId)
        if (source !is CatalogueSource) {
            return Result.failure(
                ReadingSourceSearchFailure(
                    kind = ReadingSourceFailureKind.SOURCE_UNAVAILABLE,
                    cause = IllegalStateException("Source $sourceId is not an installed CatalogueSource"),
                ),
            )
        }

        return try {
            val page = source.getSearchManga(
                page = 1,
                query = query,
                filters = source.getFilterList(),
            )
            Result.success(
                page.mangas
                    .distinctBy { it.url }
                    .map { manga ->
                        ReadingSourceCandidate(
                            sourceId = source.id,
                            sourceName = source.name,
                            language = source.lang,
                            sourceUrl = manga.url,
                            title = manga.title,
                            thumbnailUrl = manga.thumbnail_url,
                            author = manga.author,
                            artist = manga.artist,
                            description = manga.description,
                            genres = manga.getGenres(),
                            status = manga.status.toLong(),
                        )
                    },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t.toReadingSourceSearchFailure())
        }
    }

    override suspend fun getDetails(candidate: ReadingSourceCandidate): Result<ReadingSourceCandidate> {
        val disabledSources = sourcePreferences.disabledSources.get()
        if (candidate.sourceId.toString() in disabledSources) {
            return Result.failure(
                ReadingSourceSearchFailure(
                    kind = ReadingSourceFailureKind.SOURCE_DISABLED,
                    cause = IllegalStateException("Source ${candidate.sourceId} is disabled"),
                ),
            )
        }

        val source = sourceManager.get(candidate.sourceId)
        if (source !is CatalogueSource) {
            return Result.failure(
                ReadingSourceSearchFailure(
                    kind = ReadingSourceFailureKind.SOURCE_UNAVAILABLE,
                    cause = IllegalStateException("Source ${candidate.sourceId} is not an installed CatalogueSource"),
                ),
            )
        }

        return try {
            val seed = SManga.create().apply {
                url = candidate.sourceUrl
                title = candidate.title
                thumbnail_url = candidate.thumbnailUrl
                author = candidate.author
                artist = candidate.artist
                description = candidate.description
                genre = candidate.genres?.joinToString(", ")
                status = candidate.status.toInt()
            }
            val details = source.getMangaUpdate(
                manga = seed,
                chapters = emptyList(),
                fetchDetails = true,
                fetchChapters = false,
            ).manga
            Result.success(
                candidate.copy(
                    title = details.title.ifBlank { candidate.title },
                    thumbnailUrl = details.thumbnail_url
                        ?.takeIf { it.isNotBlank() }
                        ?: candidate.thumbnailUrl,
                    author = details.author
                        ?.takeIf { it.isNotBlank() }
                        ?: candidate.author,
                    artist = details.artist
                        ?.takeIf { it.isNotBlank() }
                        ?: candidate.artist,
                    description = details.description
                        ?.takeIf { it.isNotBlank() }
                        ?: candidate.description,
                    genres = details.getGenres()
                        ?.takeIf { it.isNotEmpty() }
                        ?: candidate.genres,
                    status = details.status
                        .takeIf { it != SManga.UNKNOWN }
                        ?.toLong()
                        ?: candidate.status,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error.toReadingSourceSearchFailure())
        }
    }

    private fun Throwable.toReadingSourceSearchFailure(): ReadingSourceSearchFailure {
        if (this is ReadingSourceSearchFailure) return this

        val causes = generateSequence(this) { it.cause }.take(5).toList()
        val httpStatus = causes.filterIsInstance<HttpException>()
            .map(HttpException::code)
            .firstOrNull { it in 100..599 }
        val hasExplicitCaptcha = causes.any { cause ->
            val detail = cause.message.orEmpty()
            detail.contains("captcha_required", ignoreCase = true) ||
                detail.contains("shape-selecting captcha", ignoreCase = true)
        }
        val kind = when {
            hasExplicitCaptcha -> ReadingSourceFailureKind.CAPTCHA_REQUIRED
            causes.any { it is SocketTimeoutException } -> ReadingSourceFailureKind.TIMEOUT
            httpStatus != null -> ReadingSourceFailureKind.HTTP_RESPONSE
            causes.any {
                it is JSONException || it is SerializationException
            } -> ReadingSourceFailureKind.MALFORMED_RESPONSE
            causes.any {
                it is UnknownHostException || it is ConnectException || it is SocketException
            } -> ReadingSourceFailureKind.NETWORK_FAILURE
            causes.any { it is IOException } -> ReadingSourceFailureKind.INDETERMINATE
            else -> ReadingSourceFailureKind.EXTENSION_FAILURE
        }

        return ReadingSourceSearchFailure(
            kind = kind,
            httpStatus = httpStatus,
            cause = this,
        )
    }

    override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
        return try {
            val resolvedCandidate = if (
                candidate.thumbnailUrl.isNullOrBlank() ||
                candidate.description.isNullOrBlank()
            ) {
                getDetails(candidate).getOrElse { candidate }
            } else {
                candidate
            }
            val manga = Manga.create().copy(
                source = resolvedCandidate.sourceId,
                url = resolvedCandidate.sourceUrl,
                title = resolvedCandidate.title,
                thumbnailUrl = resolvedCandidate.thumbnailUrl,
                author = resolvedCandidate.author,
                artist = resolvedCandidate.artist,
                description = resolvedCandidate.description,
                genre = resolvedCandidate.genres,
                status = resolvedCandidate.status,
                initialized = resolvedCandidate !== candidate,
                favorite = false,
            )
            val localManga = networkToLocalManga(manga)
            val payload = MihonContentBindingPayload(
                sourceId = resolvedCandidate.sourceId,
                mihonMangaId = localManga.id,
                sourceUrl = resolvedCandidate.sourceUrl,
                language = resolvedCandidate.language,
            )
            Result.success(
                MaterializedReadingSource(
                    mihonMangaId = localManga.id,
                    sourceId = candidate.sourceId,
                    sourceUrl = candidate.sourceUrl,
                    language = candidate.language,
                    providerTitleKey = candidate.sourceId.toString() + ":" + candidate.sourceUrl,
                    runtimePayload = MihonContentBindingPayloadCodec.encode(payload),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
