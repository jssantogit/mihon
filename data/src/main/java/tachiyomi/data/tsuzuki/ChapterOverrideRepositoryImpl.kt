package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverride
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverrideKind
import tachiyomi.domain.tsuzuki.chapter.repository.ChapterOverrideRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterOverrideRepositoryImpl(
    private val database: Database,
) : ChapterOverrideRepository {

    override suspend fun getById(id: String): ChapterOverride? {
        return database.tsuzuki_chapter_overridesQueries
            .getTsuzukiChapterOverrideById(id, ::mapOverride)
            .awaitAsOneOrNull()
    }

    override suspend fun getAll(includeDeleted: Boolean): List<ChapterOverride> {
        return if (includeDeleted) {
            database.tsuzuki_chapter_overridesQueries
                .getAllTsuzukiChapterOverrides(::mapOverride)
                .awaitAsList()
        } else {
            database.tsuzuki_chapter_overridesQueries
                .getActiveTsuzukiChapterOverrides(::mapOverride)
                .awaitAsList()
        }
    }

    override suspend fun upsert(override: ChapterOverride) {
        database.tsuzuki_chapter_overridesQueries.upsertTsuzukiChapterOverride(
            id = override.id,
            canonicalTitleId = override.canonicalTitleId,
            canonicalChapterKey = override.canonicalChapterKey,
            sourceId = override.sourceId,
            sourceTitleUrl = override.sourceTitleUrl,
            sourceChapterId = override.sourceChapterId,
            kind = override.kind.name,
            payloadJson = override.payloadJson,
            schemaVersion = override.schemaVersion.toLong(),
            revision = override.revision,
            createdAt = override.createdAt,
            updatedAt = override.updatedAt,
            deletedAt = override.deletedAt,
        )
    }

    private fun mapOverride(
        id: String,
        canonicalTitleId: String,
        canonicalChapterKey: String?,
        sourceId: Long?,
        sourceTitleUrl: String?,
        sourceChapterId: String?,
        kind: String,
        payloadJson: String,
        schemaVersion: Long,
        revision: Long,
        createdAt: Long,
        updatedAt: Long,
        deletedAt: Long?,
    ) = ChapterOverride(
        id = id,
        canonicalTitleId = canonicalTitleId,
        canonicalChapterKey = canonicalChapterKey,
        sourceId = sourceId,
        sourceTitleUrl = sourceTitleUrl,
        sourceChapterId = sourceChapterId,
        kind = ChapterOverrideKind.valueOf(kind),
        payloadJson = payloadJson,
        schemaVersion = schemaVersion.toInt(),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
}
