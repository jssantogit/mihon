package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonReadingSourceGateway(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
) : ReadingSourceGateway {

    override suspend fun getAvailableSources(language: String): List<ReadingSourceDescriptor> {
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
                    isInstalled = true,
                    isEnabled = true,
                )
            }
    }

    override suspend fun searchSource(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
        val disabledSources = sourcePreferences.disabledSources.get()
        if (sourceId.toString() in disabledSources) {
            return Result.failure(IllegalStateException("Source $sourceId is disabled"))
        }

        val source = sourceManager.get(sourceId)
        if (source == null || source !is CatalogueSource) {
            return Result.failure(IllegalStateException("Source $sourceId is not an installed CatalogueSource"))
        }

        return try {
            val filters = source.getFilterList()
            val mangasPage = source.getSearchManga(
                page = 1,
                query = query,
                filters = filters,
            )
            val candidates = mangasPage.mangas
                .distinctBy { it.url }
                .map { sManga ->
                    ReadingSourceCandidate(
                        sourceId = sourceId,
                        sourceUrl = sManga.url,
                        title = sManga.title,
                        thumbnailUrl = sManga.thumbnail_url,
                    )
                }
            Result.success(candidates)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun materializeSource(
        sourceId: Long,
        sourceUrl: String,
        title: String,
    ): Result<MaterializedReadingSource> {
        return try {
            val unpersistedManga = Manga.create().copy(
                source = sourceId,
                url = sourceUrl,
                title = title,
                favorite = false,
            )
            val localManga = networkToLocalManga(unpersistedManga)
            Result.success(
                MaterializedReadingSource(
                    sourceId = sourceId,
                    sourceUrl = sourceUrl,
                    mihonMangaId = localManga.id,
                    title = localManga.title.ifBlank { title },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
