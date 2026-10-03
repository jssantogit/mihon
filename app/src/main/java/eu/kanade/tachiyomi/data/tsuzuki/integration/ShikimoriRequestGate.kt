package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

@Inject
@SingleIn(AppScope::class)
class ShikimoriRequestGate internal constructor(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onPermit: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val recentRequests = ArrayDeque<Long>()

    suspend fun <T> withPermit(block: suspend () -> T): T {
        awaitPermit()
        return block()
    }

    private suspend fun awaitPermit() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = nowMillis()
                while (recentRequests.isNotEmpty() && now - recentRequests.first() >= MINUTE_WINDOW_MS) {
                    recentRequests.removeFirst()
                }

                val recentSecond = recentRequests.filter { timestamp ->
                    now - timestamp < SECOND_WINDOW_MS
                }
                val secondWait = if (recentSecond.size >= MAX_REQUESTS_PER_SECOND) {
                    SECOND_WINDOW_MS - (now - recentSecond.first())
                } else {
                    0L
                }
                val minuteWait = if (recentRequests.size >= MAX_REQUESTS_PER_MINUTE) {
                    MINUTE_WINDOW_MS - (now - recentRequests.first())
                } else {
                    0L
                }
                val requiredWait = maxOf(secondWait, minuteWait)

                if (requiredWait <= 0L) {
                    recentRequests.addLast(now)
                    onPermit()
                    0L
                } else {
                    requiredWait.coerceAtLeast(1L)
                }
            }

            if (waitMillis <= 0L) return
            pause(waitMillis)
        }
    }

    private companion object {
        const val MAX_REQUESTS_PER_SECOND = 5
        const val MAX_REQUESTS_PER_MINUTE = 90
        const val SECOND_WINDOW_MS = 1_000L
        const val MINUTE_WINDOW_MS = 60_000L
    }
}
