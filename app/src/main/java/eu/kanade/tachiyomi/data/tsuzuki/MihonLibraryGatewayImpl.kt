package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.tsuzuki.migration.model.MihonLibrarySnapshot
import tachiyomi.domain.tsuzuki.migration.service.MihonLibraryGateway

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonLibraryGatewayImpl(
    private val getLibraryManga: GetLibraryManga,
    private val sourceManager: SourceManager,
) : MihonLibraryGateway {

    override suspend fun snapshot(): List<MihonLibrarySnapshot> {
        val library = getLibraryManga.await()
        return library.map { item ->
            val source = sourceManager.get(item.manga.source)
            val available = source != null && source !is StubSource
            val lang = source?.lang ?: sourceManager.getOrStub(item.manga.source).lang
            MihonLibrarySnapshot(
                mihonMangaId = item.manga.id,
                sourceId = item.manga.source,
                sourceUrl = item.manga.url,
                sourceLanguage = lang,
                sourceAvailable = available,
                title = item.manga.title,
                dateAdded = item.manga.dateAdded,
                hasStarted = item.hasStarted,
            )
        }
    }
}
