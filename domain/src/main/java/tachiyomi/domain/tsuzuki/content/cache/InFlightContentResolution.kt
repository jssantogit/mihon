package tachiyomi.domain.tsuzuki.content.cache

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.content.ContentOption

@Inject
@SingleIn(AppScope::class)
class InFlightContentResolution {

    private val mutex = Mutex()
    private val active = mutableMapOf<ContentOptionCacheKey, CompletableDeferred<Result<List<ContentOption>>>>()

    suspend fun execute(
        key: ContentOptionCacheKey,
        block: suspend () -> Result<List<ContentOption>>,
    ): Result<List<ContentOption>> {
        val (deferred, leader) = mutex.withLock {
            active[key]?.let { existing -> return@withLock existing to false }
            CompletableDeferred<Result<List<ContentOption>>>().also { created ->
                active[key] = created
            } to true
        }

        if (!leader) return deferred.await()

        return try {
            val result = block()
            deferred.complete(result)
            result
        } catch (error: CancellationException) {
            deferred.cancel(error)
            throw error
        } catch (error: Throwable) {
            Result.failure<List<ContentOption>>(error).also(deferred::complete)
        } finally {
            mutex.withLock {
                if (active[key] === deferred) active.remove(key)
            }
        }
    }
}
