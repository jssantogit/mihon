package tachiyomi.domain.tsuzuki.migration.repository

interface CanonicalLibraryMigrationStateRepository {
    suspend fun isCompleted(): Boolean
    suspend fun markCompleted()
}
