package tachiyomi.domain.tsuzuki.integration.cache

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata

@SingleIn(AppScope::class)
class InFlightCanonicalMetadataResolution private constructor(
    private val scope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    private data class Key(
        val canonicalTitleId: String,
        val configurationFingerprint: String,
    )

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Key, CompletableDeferred<Result<ResolvedMetadata>>>()

    constructor(scope: CoroutineScope) : this(scope, Unit)

    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.IO), Unit)

    suspend fun execute(
        canonicalTitleId: String,
        configurationFingerprint: String,
        block: suspend () -> Result<ResolvedMetadata>,
    ): Result<ResolvedMetadata> {
        val key = Key(canonicalTitleId, configurationFingerprint)
        var owner = false
        val deferred = mutex.withLock {
            inFlight[key] ?: CompletableDeferred<Result<ResolvedMetadata>>().also {
                inFlight[key] = it
                owner = true
            }
        }

        if (owner) {
            scope.launch {
                val result = try {
                    block()
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                mutex.withLock {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                        deferred.complete(result)
                    }
                }
            }
        }

        return deferred.await()
    }

    suspend fun invalidateTitle(canonicalTitleId: String) {
        mutex.withLock {
            inFlight.entries.toList()
                .filter { (key, _) -> key.canonicalTitleId == canonicalTitleId }
                .forEach { (key, deferred) ->
                    inFlight.remove(key)
                    deferred.complete(Result.failure(IllegalStateException("Metadata resolution invalidated")))
                }
        }
    }
}
