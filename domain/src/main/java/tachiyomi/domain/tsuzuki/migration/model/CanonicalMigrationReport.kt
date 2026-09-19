package tachiyomi.domain.tsuzuki.migration.model

data class CanonicalMigrationReport(
    val totalProcessed: Int,
    val newlyImported: Int,
    val alreadyMapped: Int,
)
