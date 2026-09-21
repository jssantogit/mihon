package eu.kanade.tachiyomi.data.tsuzuki.supabase

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleMergePort

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PendingCanonicalTitleMergePort : CanonicalTitleMergePort {

    override suspend fun merge(
        targetId: String,
        localId: String,
    ): Result<Unit> {
        return if (targetId == localId) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Canonical title merge requires Dev A integration",
                ),
            )
        }
    }
}
