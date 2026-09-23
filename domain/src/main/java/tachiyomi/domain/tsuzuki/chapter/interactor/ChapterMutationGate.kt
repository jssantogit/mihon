package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes local chapter insertion and evidence reconciliation so a user tap
 * cannot race a just-arriving Add-on chapter into duplicate canonical IDs.
 */
@SingleIn(AppScope::class)
class ChapterMutationGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock {
        action()
    }
}
