package tachiyomi.domain.tsuzuki.collections.scheduler

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class QuerySchedulePriority(
    internal val rank: Int,
) {
    VISIBLE(0),
    NEXT_SCREEN(1),
    BACKGROUND(2),
}

class ScheduledQuery<T> internal constructor(
    val id: Long,
    val result: Deferred<T>,
    private val cancelAction: suspend () -> Boolean,
) {
    suspend fun await(): T = result.await()

    suspend fun cancel(): Boolean = cancelAction()
}

data class QuerySchedulerSnapshot(
    val queuedCount: Int,
    val runningCount: Int,
    val runningByProvider: Map<String, Int>,
)

class CollectionQueryScheduler(
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 4,
    private val maxConcurrentPerProvider: Int = 2,
) {
    init {
        require(maxConcurrent > 0) { "Scheduler maxConcurrent must be positive" }
        require(maxConcurrentPerProvider > 0) {
            "Scheduler maxConcurrentPerProvider must be positive"
        }
        require(maxConcurrentPerProvider <= maxConcurrent) {
            "Scheduler per-provider concurrency cannot exceed global concurrency"
        }
    }

    private val mutex = Mutex()
    private val queued = mutableListOf<QueuedTask>()
    private val running = mutableMapOf<Long, RunningTask>()
    private val runningByProvider = mutableMapOf<String, Int>()
    private val events = Channel<SchedulerEvent>(Channel.UNLIMITED)
    private val nextId = AtomicLong(1)
    private val nextSequence = AtomicLong(1)

    @Suppress("unused")
    private val dispatcherJob = scope.launch {
        for (event in events) {
            when (event) {
                SchedulerEvent.Wake -> Unit
                is SchedulerEvent.Finished -> releaseRunning(event.id, event.providerId)
            }
            dispatchReady()
        }
    }

    suspend fun <T> schedule(
        providerId: String,
        priority: QuerySchedulePriority,
        block: suspend () -> T,
    ): ScheduledQuery<T> {
        require(providerId.isNotBlank()) { "Scheduler providerId cannot be blank" }

        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<T>()
        val task = QueuedTask(
            id = id,
            providerId = providerId,
            priority = priority,
            sequence = nextSequence.getAndIncrement(),
            run = {
                try {
                    result.complete(block())
                } catch (cancellation: CancellationException) {
                    result.cancel(cancellation)
                    throw cancellation
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            },
            cancelResult = { cancellation ->
                result.cancel(cancellation)
            },
        )

        mutex.withLock {
            queued += task
        }
        events.trySend(SchedulerEvent.Wake)

        return ScheduledQuery(
            id = id,
            result = result,
            cancelAction = { cancel(id) },
        )
    }

    suspend fun reprioritize(
        id: Long,
        priority: QuerySchedulePriority,
    ): Boolean {
        return mutex.withLock {
            val task = queued.firstOrNull { it.id == id } ?: return@withLock false
            task.priority = priority
            events.trySend(SchedulerEvent.Wake)
            true
        }
    }

    suspend fun cancel(id: Long): Boolean {
        val cancellation = CancellationException("Scheduled query $id was cancelled")
        var queuedTask: QueuedTask? = null
        var runningJob: Job? = null

        mutex.withLock {
            val queuedIndex = queued.indexOfFirst { it.id == id }
            if (queuedIndex >= 0) {
                queuedTask = queued.removeAt(queuedIndex)
            } else {
                runningJob = running[id]?.job
            }
        }

        queuedTask?.let {
            it.cancelResult(cancellation)
            return true
        }

        runningJob?.let {
            it.cancel(cancellation)
            return true
        }

        return false
    }

    suspend fun snapshot(): QuerySchedulerSnapshot {
        return mutex.withLock {
            QuerySchedulerSnapshot(
                queuedCount = queued.size,
                runningCount = running.size,
                runningByProvider = runningByProvider.toMap(),
            )
        }
    }

    private suspend fun dispatchReady() {
        while (true) {
            val job = mutex.withLock {
                if (running.size >= maxConcurrent) {
                    return
                }

                val task = queued
                    .asSequence()
                    .filter { (runningByProvider[it.providerId] ?: 0) < maxConcurrentPerProvider }
                    .minWithOrNull(
                        compareBy<QueuedTask> { it.priority.rank }
                            .thenBy { it.sequence },
                    )
                    ?: return

                queued.remove(task)
                runningByProvider[task.providerId] = (runningByProvider[task.providerId] ?: 0) + 1

                val created = scope.launch(start = CoroutineStart.LAZY) {
                    task.run()
                }
                created.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        task.cancelResult(cause)
                    }
                    events.trySend(
                        SchedulerEvent.Finished(
                            id = task.id,
                            providerId = task.providerId,
                        ),
                    )
                }
                running[task.id] = RunningTask(
                    providerId = task.providerId,
                    job = created,
                )
                created
            }

            job.start()
        }
    }

    private suspend fun releaseRunning(
        id: Long,
        providerId: String,
    ) {
        mutex.withLock {
            val removed = running.remove(id) ?: return@withLock
            check(removed.providerId == providerId) {
                "Scheduler provider accounting mismatch for request $id"
            }

            val remaining = (runningByProvider[providerId] ?: 1) - 1
            if (remaining <= 0) {
                runningByProvider.remove(providerId)
            } else {
                runningByProvider[providerId] = remaining
            }
        }
    }

    private data class QueuedTask(
        val id: Long,
        val providerId: String,
        var priority: QuerySchedulePriority,
        val sequence: Long,
        val run: suspend () -> Unit,
        val cancelResult: (CancellationException) -> Unit,
    )

    private data class RunningTask(
        val providerId: String,
        val job: Job,
    )

    private sealed interface SchedulerEvent {
        data object Wake : SchedulerEvent

        data class Finished(
            val id: Long,
            val providerId: String,
        ) : SchedulerEvent
    }
}
