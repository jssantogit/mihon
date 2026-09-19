package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingSourcePreferenceRepositoryImpl(
    private val database: Database,
) : ReadingSourcePreferenceRepository {

    override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> {
        return database.tsuzuki_source_preferencesQueries
            .getPreferencesForLanguage(language, ::mapPreference)
            .awaitAsList()
    }

    override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> {
        return database.tsuzuki_source_preferencesQueries
            .getPreferencesForLanguage(language, ::mapPreference)
            .subscribeToList()
    }

    override suspend fun getConfiguredLanguages(): List<String> {
        return database.tsuzuki_source_preferencesQueries
            .getConfiguredLanguages()
            .awaitAsList()
    }

    override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) {
        val uniqueCount = orderedSourceIds.distinct().size
        require(uniqueCount == orderedSourceIds.size) {
            "Duplicate source IDs are not allowed in preferences for language $language"
        }
        database.transaction {
            database.tsuzuki_source_preferencesQueries.deletePreferencesForLanguage(language)
            orderedSourceIds.forEachIndexed { index, sourceId ->
                database.tsuzuki_source_preferencesQueries.insertPreference(
                    language = language,
                    sourceId = sourceId,
                    position = index.toLong(),
                )
            }
        }
    }

    private fun mapPreference(
        language: String,
        sourceId: Long,
        position: Long,
    ) = ReadingSourcePreference(
        language = language,
        sourceId = sourceId,
        position = position.toInt(),
    )
}
