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
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalReaderPreferenceRepositoryImpl(
    private val database: Database,
) : CanonicalReaderPreferenceRepository {

    override suspend fun get(canonicalTitleId: String): CanonicalReaderPreference? {
        return database.tsuzuki_reader_preferencesQueries
            .getTsuzukiReaderPreference(canonicalTitleId, ::mapPreference)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<CanonicalReaderPreference> {
        return database.tsuzuki_reader_preferencesQueries
            .getAllTsuzukiReaderPreferences(::mapPreference)
            .awaitAsList()
    }

    override fun observe(canonicalTitleId: String): Flow<CanonicalReaderPreference?> {
        return database.tsuzuki_reader_preferencesQueries
            .observeTsuzukiReaderPreference(canonicalTitleId, ::mapPreference)
            .subscribeToList()
            .map { it.firstOrNull() }
    }

    override suspend fun upsert(preference: CanonicalReaderPreference) {
        database.tsuzuki_reader_preferencesQueries.upsertTsuzukiReaderPreference(
            canonicalTitleId = preference.canonicalTitleId,
            automaticFallback = preference.automaticFallback,
            updatedAt = preference.updatedAt,
        )
    }

    override suspend fun delete(canonicalTitleId: String) {
        database.tsuzuki_reader_preferencesQueries
            .deleteTsuzukiReaderPreference(canonicalTitleId)
    }

    private fun mapPreference(
        canonicalTitleId: String,
        automaticFallback: Boolean,
        updatedAt: Long,
    ) = CanonicalReaderPreference(
        canonicalTitleId = canonicalTitleId,
        automaticFallback = automaticFallback,
        updatedAt = updatedAt,
    )
}
