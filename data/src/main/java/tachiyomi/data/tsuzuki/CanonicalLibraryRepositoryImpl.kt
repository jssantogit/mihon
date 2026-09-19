package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalLibraryRepositoryImpl(
    private val database: Database,
) : CanonicalLibraryRepository {

    override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? {
        return database.tsuzuki_library_entriesQueries
            .getTsuzukiLibraryEntry(canonicalTitleId, ::mapEntry)
            .awaitAsOneOrNull()
    }

    override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> {
        return database.tsuzuki_library_entriesQueries
            .getAllTsuzukiLibraryEntries(::mapEntry)
            .subscribeToList()
    }

    override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> {
        return database.tsuzuki_library_entriesQueries
            .getAllTsuzukiLibraryItems(::mapItem)
            .subscribeToList()
    }

    override suspend fun upsert(entry: CanonicalLibraryEntry) {
        database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
            canonicalTitleId = entry.canonicalTitleId,
            status = entry.status.name,
            favorite = entry.favorite,
            addedAt = entry.addedAt,
            updatedAt = entry.updatedAt,
        )
    }

    override suspend fun remove(canonicalTitleId: String) {
        database.tsuzuki_library_entriesQueries.deleteTsuzukiLibraryEntry(canonicalTitleId)
    }

    private fun mapEntry(
        canonicalTitleId: String,
        status: String,
        favorite: Boolean,
        addedAt: Long,
        updatedAt: Long,
    ) = CanonicalLibraryEntry(
        canonicalTitleId = canonicalTitleId,
        status = LibraryStatus.valueOf(status),
        favorite = favorite,
        addedAt = addedAt,
        updatedAt = updatedAt,
    )

    private fun mapItem(
        canonicalTitleId: String,
        status: String,
        favorite: Boolean,
        addedAt: Long,
        updatedAt: Long,
        displayTitle: String,
        identityState: String,
        titleCreatedAt: Long,
        titleUpdatedAt: Long,
    ): CanonicalLibraryItem {
        val title = CanonicalTitle(
            id = canonicalTitleId,
            displayTitle = displayTitle,
            identityState = CanonicalIdentityState.valueOf(identityState),
            createdAt = titleCreatedAt,
            updatedAt = titleUpdatedAt,
        )
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = canonicalTitleId,
            status = LibraryStatus.valueOf(status),
            favorite = favorite,
            addedAt = addedAt,
            updatedAt = updatedAt,
        )
        return CanonicalLibraryItem(
            title = title,
            entry = entry,
        )
    }
}
