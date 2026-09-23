package tachiyomi.data.tsuzuki.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalTitleSyncSourceImpl(
    private val database: Database,
) : CanonicalTitleSyncSource {

    override suspend fun getAllTitles(): List<CanonicalTitle> {
        return database.tsuzuki_titlesQueries
            .getAllTsuzukiTitles(::mapTitle)
            .awaitAsList()
    }

    private fun mapTitle(
        id: String,
        displayTitle: String,
        identityState: String,
        createdAt: Long,
        updatedAt: Long,
    ) = CanonicalTitle(
        id = id,
        displayTitle = displayTitle,
        identityState = CanonicalIdentityState.valueOf(identityState),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
