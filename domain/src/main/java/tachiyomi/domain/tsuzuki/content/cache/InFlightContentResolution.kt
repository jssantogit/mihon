package tachiyomi.domain.tsuzuki.content.cache

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentOption

/** Shares provider requests without making extension execution a child of the UI caller. */
@Inject
@SingleIn(AppScope::class)
class InFlightContentResolution {

    private class Task(
        val key: ContentOptionCacheKey,
        val result: CompletableDeferred<Result<List<ContentOption>>>,
        val providerGate: ProviderGate,
        var invalidated: Boolean = false,
    )

    private class ProviderGate(
        val semaphore: Semaphore = Semaphore(MAX_CONCURRENT_PER_ADDON),
        var taskCount: Int = 0,
    )

    private val mutex = Mutex()
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val globalProviderGate = Semaphore(MAX_CONCURRENT_PROVIDER_CALLS)
    private val activeByKey = mutableMapOf<ContentOptionCacheKey, Task>()
    private val outstandingTasks = mutableSetOf<Task>()
    private val providerGates = mutableMapOf<AddonId, ProviderGate>()
    private val outstandingByAddon = mutableMapOf<AddonId, Int>()

    /**
     * Work is admitted only into a bounded queue. Callers can cancel their wait while an
     * extension that ignores cancellation remains counted against its provider and global
     * concurrency limits until it actually returns.
     */
    suspend fun execute(
        key: ContentOptionCacheKey,
        block: suspend () -> Result<List<ContentOption>>,
    ): Result<List<ContentOption>> {
        var created: Task? = null
        val result = mutex.withLock {
            activeByKey[key]?.let { return@withLock it.result }

            val addonTaskCount = outstandingByAddon[key.addonId] ?: 0
            if (outstandingTasks.size >= MAX_OUTSTANDING_PROVIDER_CALLS ||
                addonTaskCount >= MAX_OUTSTANDING_PER_ADDON
            ) {
                return@withLock CompletableDeferred<Result<List<ContentOption>>>().also {
                    it.complete(Result.failure(ProviderResolutionCapacityException()))
                }
            }

            val providerGate = providerGates.getOrPut(key.addonId) { ProviderGate() }
            providerGate.taskCount++
            val task = Task(
                key = key,
                result = CompletableDeferred(),
                providerGate = providerGate,
            )
            activeByKey[key] = task
            outstandingTasks += task
            outstandingByAddon[key.addonId] = addonTaskCount + 1
            created = task
            task.result
        }

        created?.let { task ->
            workScope.launch {
                val resolved = try {
                    // Acquire the Add-on slot first so repeated requests from one unresponsive
                    // extension cannot occupy every global provider slot while waiting in line.
                    task.providerGate.semaphore.withPermit {
                        if (isInvalidated(task)) {
                            Result.failure(StaleContentResolutionException())
                        } else {
                            globalProviderGate.withPermit {
                                if (isInvalidated(task)) {
                                    Result.failure(StaleContentResolutionException())
                                } else {
                                    invokeProvider(block)
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                mutex.withLock {
                    if (!task.invalidated) task.result.complete(resolved)
                    finish(task)
                }
            }
        }

        return result.await()
    }

    /** Prevents late results from an invalidated title/chapter from being shared or cached. */
    internal suspend fun invalidateChapter(canonicalTitleId: String, canonicalChapterId: String) {
        invalidateMatching { key ->
            key.canonicalTitleId == canonicalTitleId && key.canonicalChapterId == canonicalChapterId
        }
    }

    internal suspend fun invalidate(key: ContentOptionCacheKey) {
        invalidateMatching { it == key }
    }

    /** Prevents late results from a disabled, removed, or refreshed provider from resurfacing. */
    internal suspend fun invalidateAddon(addonId: AddonId) {
        invalidateMatching { key -> key.addonId == addonId }
    }

    private suspend fun invalidateMatching(predicate: (ContentOptionCacheKey) -> Boolean) {
        mutex.withLock {
            activeByKey.entries.toList()
                .filter { (key, _) -> predicate(key) }
                .forEach { (key, task) ->
                    activeByKey.remove(key)
                    task.invalidated = true
                    task.result.complete(Result.failure(StaleContentResolutionException()))
                }
        }
    }

    private suspend fun isInvalidated(task: Task): Boolean = mutex.withLock { task.invalidated }

    private fun finish(task: Task) {
        if (activeByKey[task.key] === task) activeByKey.remove(task.key)
        outstandingTasks.remove(task)

        val addonId = task.key.addonId
        val addonTaskCount = (outstandingByAddon[addonId] ?: 1) - 1
        if (addonTaskCount <= 0) {
            outstandingByAddon.remove(addonId)
        } else {
            outstandingByAddon[addonId] = addonTaskCount
        }

        task.providerGate.taskCount--
        if (task.providerGate.taskCount == 0 && providerGates[addonId] === task.providerGate) {
            providerGates.remove(addonId)
        }
    }

    private suspend fun invokeProvider(
        block: suspend () -> Result<List<ContentOption>>,
    ): Result<List<ContentOption>> = try {
        block()
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private class ProviderResolutionCapacityException : IllegalStateException("Provider resolution capacity is full")

    private class StaleContentResolutionException : IllegalStateException("Provider resolution was invalidated")

    private companion object {
        const val MAX_CONCURRENT_PROVIDER_CALLS = 4
        const val MAX_CONCURRENT_PER_ADDON = 1
        const val MAX_OUTSTANDING_PROVIDER_CALLS = 32
        const val MAX_OUTSTANDING_PER_ADDON = 4
    }
}
