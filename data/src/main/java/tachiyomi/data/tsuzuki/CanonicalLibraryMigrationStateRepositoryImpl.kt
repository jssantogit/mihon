package tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.tsuzuki.migration.repository.CanonicalLibraryMigrationStateRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalLibraryMigrationStateRepositoryImpl(
    preferenceStore: PreferenceStore,
) : CanonicalLibraryMigrationStateRepository {

    private val completed = preferenceStore.getBoolean(
        Preference.appStateKey("tsuzuki_canonical_library_migration_v1_complete"),
        false,
    )

    override suspend fun isCompleted(): Boolean = completed.get()

    override suspend fun markCompleted() {
        completed.set(true)
    }
}
