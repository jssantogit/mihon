package tachiyomi.data.tsuzuki.home

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.home.model.ContinueReadingVisibility
import tachiyomi.domain.tsuzuki.home.repository.ContinueReadingVisibilityRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ContinueReadingVisibilityRepositoryImpl(
    private val database: Database,
) : ContinueReadingVisibilityRepository {

    override suspend fun get(canonicalTitleId: String): ContinueReadingVisibility? {
        return database.tsuzuki_continue_reading_stateQueries
            .getTsuzukiContinueReadingState(canonicalTitleId)
            .awaitAsOneOrNull()
            ?.let { row ->
                ContinueReadingVisibility(
                    canonicalTitleId = row.canonical_title_id,
                    hiddenAt = row.hidden_at,
                )
            }
    }

    override suspend fun getAll(): List<ContinueReadingVisibility> {
        return database.tsuzuki_continue_reading_stateQueries
            .getAllTsuzukiContinueReadingState()
            .awaitAsList()
            .map(::mapVisibility)
    }

    override fun observeAll(): Flow<List<ContinueReadingVisibility>> {
        return database.tsuzuki_continue_reading_stateQueries
            .getAllTsuzukiContinueReadingState()
            .subscribeToList()
            .let { flow ->
                kotlinx.coroutines.flow.map(flow) { rows ->
                    rows.map(::mapVisibility)
                }
            }
    }

    override suspend fun hide(
        canonicalTitleId: String,
        hiddenAt: Long,
    ) {
        require(hiddenAt >= 0) { "Continue Reading hidden time must not be negative" }
        database.tsuzuki_continue_reading_stateQueries
            .upsertTsuzukiContinueReadingState(
                canonicalTitleId = canonicalTitleId,
                hiddenAt = hiddenAt,
            )
    }

    override suspend fun clear(canonicalTitleId: String) {
        database.tsuzuki_continue_reading_stateQueries
            .deleteTsuzukiContinueReadingState(canonicalTitleId)
    }

    private fun mapVisibility(
        row: tachiyomi.data.Tsuzuki_continue_reading_state,
    ): ContinueReadingVisibility {
        return ContinueReadingVisibility(
            canonicalTitleId = row.canonical_title_id,
            hiddenAt = row.hidden_at,
        )
    }
}
