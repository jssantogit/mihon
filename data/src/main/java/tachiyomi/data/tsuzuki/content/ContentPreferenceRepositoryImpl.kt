package tachiyomi.data.tsuzuki.content

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
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ContentPreferenceRepositoryImpl(
    private val database: Database,
) : ContentPreferenceRepository {

    override suspend fun get(canonicalTitleId: String): ContentPreference? {
        return database.tsuzuki_content_preferencesQueries
            .getTsuzukiContentPreference(canonicalTitleId, ::mapPreference)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<ContentPreference> {
        return database.tsuzuki_content_preferencesQueries
            .getAllTsuzukiContentPreferences(::mapPreference)
            .awaitAsList()
    }

    override fun observe(canonicalTitleId: String): Flow<ContentPreference?> {
        return database.tsuzuki_content_preferencesQueries
            .observeTsuzukiContentPreference(canonicalTitleId, ::mapPreference)
            .subscribeToList()
            .map { it.firstOrNull() }
    }

    override suspend fun upsert(preference: ContentPreference) {
        database.tsuzuki_content_preferencesQueries.upsertTsuzukiContentPreference(
            canonicalTitleId = preference.canonicalTitleId,
            preferredAddonId = preference.preferredAddonId?.value,
            preferredLanguage = preference.preferredLanguage,
            updatedAt = preference.updatedAt,
        )
    }

    override suspend fun delete(canonicalTitleId: String) {
        database.tsuzuki_content_preferencesQueries.deleteTsuzukiContentPreference(canonicalTitleId)
    }

    private fun mapPreference(
        canonicalTitleId: String,
        preferredAddonId: String?,
        preferredLanguage: String?,
        updatedAt: Long,
    ) = ContentPreference(
        canonicalTitleId = canonicalTitleId,
        preferredAddonId = preferredAddonId?.let(::AddonId),
        preferredLanguage = preferredLanguage,
        updatedAt = updatedAt,
    )
}
