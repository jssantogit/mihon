package tachiyomi.data.tsuzuki.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.SanitizedStructuredDiagnosticEvent
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Best-effort, bounded JSONL history. Only sanitized domain events are accepted. */
class StructuredDiagnosticHistory internal constructor(
    directory: File,
    private val persistenceEnabled: () -> Boolean,
    private val writerExecutor: ExecutorService,
    private val ownsWriterExecutor: Boolean,
    private val maxBytes: Long,
    private val maxAgeMillis: Long,
    private val clockMillis: () -> Long,
    private val lockTimeoutMillis: Long = LOCK_TIMEOUT_MILLIS,
) : AutoCloseable {

    constructor(
        directory: File,
        persistenceEnabled: () -> Boolean,
    ) : this(
        directory = directory,
        persistenceEnabled = persistenceEnabled,
        writerExecutor = processExecutor,
        ownsWriterExecutor = false,
        maxBytes = DEFAULT_MAX_BYTES,
        maxAgeMillis = DEFAULT_MAX_AGE_MILLIS,
        clockMillis = System::currentTimeMillis,
    )

    private val rootDirectory = directory
    private val instanceDirectory = File(directory, "instance-${UUID.randomUUID()}")
    private val droppedEvents = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val json = Json { explicitNulls = false }

    init {
        require(maxBytes > 0)
        require(maxAgeMillis > 0)
    }

    /** Enqueues a sanitized event. Queue saturation and storage errors never escape. */
    fun submit(event: SanitizedStructuredDiagnosticEvent) {
        if (closed.get() != 0L || !isEnabled()) return
        try {
            writerExecutor.execute {
                if (!isEnabled()) {
                    droppedEvents.set(0)
                    return@execute
                }
                val dropped = droppedEvents.getAndSet(0)
                try {
                    val records = buildList {
                        if (dropped > 0) add(encodeDroppedRecord(dropped, clockMillis().coerceAtLeast(0)))
                        add(StructuredDiagnosticHistoryJson.serialize(event))
                    }
                    when (appendRecords(records)) {
                        AppendResult.WRITTEN -> Unit
                        AppendResult.DISABLED -> droppedEvents.set(0)
                        AppendResult.FAILED -> droppedEvents.addAndGet(dropped + 1)
                    }
                } catch (_: RuntimeException) {
                    droppedEvents.addAndGet(dropped + 1)
                }
            }
        } catch (_: RejectedExecutionException) {
            droppedEvents.incrementAndGet()
        } catch (_: RuntimeException) {
            droppedEvents.incrementAndGet()
        }
    }

    /** Waits for accepted writes and records pending saturation when persistence remains enabled. */
    fun flush(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean {
        if (!isEnabled()) {
            droppedEvents.set(0)
            return true
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        val barrier: Future<Boolean> = try {
            submitFlushTask(deadline)
        } catch (_: RuntimeException) {
            return false
        } ?: return false
        return try {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            barrier.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Returns a bounded snapshot after flushing, or an empty string when storage is unavailable. */
    fun snapshot(
        maxSnapshotBytes: Long = maxBytes,
        flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
    ): String {
        flush(flushTimeoutMillis)
        if (!isEnabled()) return ""
        val limit = maxSnapshotBytes.coerceAtLeast(
            0,
        ).coerceAtMost(maxBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (limit == 0) return ""
        return try {
            withRootLock {
                if (!isEnabled()) return@withRootLock ""
                pruneExpired(clockMillis().coerceAtLeast(0))
                if (!pruneToCap(null, 0)) return@withRootLock ""
                if (!isEnabled()) return@withRootLock ""
                val lines = segmentFiles().sortedBy { it.name }.flatMap { file ->
                    runCatching { file.readLines(StandardCharsets.UTF_8) }
                        .getOrDefault(emptyList())
                        .filter(::isValidRecord)
                }
                val output = ArrayDeque<String>()
                var bytes = 0
                for (line in lines.asReversed()) {
                    val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1
                    if (lineBytes > limit) continue
                    if (bytes + lineBytes > limit) break
                    output.addFirst(line)
                    bytes += lineBytes
                }
                if (output.isEmpty()) "" else output.joinToString(separator = "\n", postfix = "\n")
            }
        } catch (_: Exception) {
            ""
        }
    }

    override fun close() {
        if (closed.compareAndSet(0, 1)) {
            flush()
            if (ownsWriterExecutor) writerExecutor.shutdown()
        }
    }

    private fun submitFlushTask(deadlineNanos: Long): Future<Boolean>? {
        while (System.nanoTime() <= deadlineNanos) {
            try {
                return writerExecutor.submit(
                    Callable {
                        var dropped = 0L
                        try {
                            if (!isEnabled()) {
                                droppedEvents.set(0)
                                true
                            } else {
                                dropped = droppedEvents.getAndSet(0)
                                if (dropped == 0L) {
                                    true
                                } else {
                                    when (
                                        appendRecords(
                                            listOf(encodeDroppedRecord(dropped, clockMillis().coerceAtLeast(0))),
                                        )
                                    ) {
                                        AppendResult.WRITTEN -> true
                                        AppendResult.DISABLED -> {
                                            droppedEvents.set(0)
                                            true
                                        }
                                        AppendResult.FAILED -> {
                                            droppedEvents.addAndGet(dropped)
                                            false
                                        }
                                    }
                                }
                            }
                        } catch (_: RuntimeException) {
                            if (dropped > 0 && isEnabled()) droppedEvents.addAndGet(dropped)
                            false
                        }
                    },
                )
            } catch (_: RejectedExecutionException) {
                if (System.nanoTime() >= deadlineNanos) return null
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    private fun appendRecords(records: List<String>): AppendResult = try {
        withRootLock {
            if (!isEnabled()) return@withRootLock AppendResult.DISABLED
            val now = clockMillis().coerceAtLeast(0)
            pruneExpired(now)
            appendBatch(records, now)
        }
    } catch (_: Exception) {
        AppendResult.FAILED
    }

    private fun appendBatch(records: List<String>, now: Long): AppendResult {
        val encoded = records.map { (it + "\n").toByteArray(StandardCharsets.UTF_8) }
        val batchBytes = encoded.sumOf { it.size.toLong() }
        if (batchBytes > maxBytes) return AppendResult.FAILED
        instanceDirectory.mkdirs()
        val day = (now / MILLIS_PER_DAY).toString().padStart(6, '0')
        val candidates = instanceDirectory.listFiles { file -> file.isFile && file.name.startsWith("segment-$day-") }
            .orEmpty()
            .sortedBy { it.name }
        val segmentLimit = minOf(SEGMENT_BYTES, maxBytes)
        val existingSegments = segmentFiles()
        val totalBytes = existingSegments.sumOf(File::length)
        val requiresEviction = totalBytes + batchBytes > maxBytes
        var target = if (requiresEviction) {
            null
        } else {
            candidates.lastOrNull()?.takeIf {
                it.length() + batchBytes <=
                    segmentLimit
            }
        }
        if (target == null) {
            val nextIndex =
                candidates.maxOfOrNull { it.name.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: -1 }
                    ?.plus(1)
                    ?: 0
            target = File(
                instanceDirectory,
                "segment-$day-${now.toString().padStart(13, '0')}-${nextIndex.toString().padStart(4, '0')}.jsonl",
            )
        }
        val createdTarget = !target.exists()
        if (createdTarget) target.createNewFile()
        if (!pruneToCap(target, batchBytes)) {
            if (createdTarget) target.delete()
            pruneEmptyInstanceDirectories()
            return AppendResult.FAILED
        }
        if (!isEnabled()) {
            if (createdTarget) target.delete()
            pruneEmptyInstanceDirectories()
            return AppendResult.DISABLED
        }
        FileOutputStream(target, true).use { output ->
            encoded.forEach { bytes -> output.write(bytes) }
            output.fd.sync()
        }
        return AppendResult.WRITTEN
    }

    private fun pruneExpired(now: Long) {
        val threshold = now - maxAgeMillis
        segmentFiles().forEach { file ->
            val segmentTime = file.name.substringAfter("segment-").split('-').getOrNull(1)?.toLongOrNull()
            if (segmentTime == null || segmentTime < threshold) file.delete()
        }
        pruneEmptyInstanceDirectories()
    }

    /** Removes oldest JSONL segments until size and file-count limits can accommodate the write. */
    private fun pruneToCap(protectedFile: File?, additionalBytes: Long): Boolean {
        val files = segmentFiles().sortedBy { it.name }
        var total = files.sumOf(File::length)
        var remainingFiles = files.size
        for (file in files) {
            if (total + additionalBytes <= maxBytes && remainingFiles <= MAX_SEGMENT_FILES) break
            if (protectedFile != null && file.absolutePath == protectedFile.absolutePath) continue
            val length = file.length()
            if (file.delete()) {
                total -= length
                remainingFiles--
            }
        }
        pruneEmptyInstanceDirectories()
        return total + additionalBytes <= maxBytes && remainingFiles <= MAX_SEGMENT_FILES
    }

    /** Deletes only empty store-owned directories directly below the injected history root. */
    private fun pruneEmptyInstanceDirectories() {
        rootDirectory.listFiles { file -> file.isDirectory && file.name.startsWith(INSTANCE_DIRECTORY_PREFIX) }
            .orEmpty()
            .forEach { directory ->
                if (directory.list()?.isEmpty() == true) directory.delete()
            }
    }

    private fun segmentFiles(): List<File> = rootDirectory.walkTopDown()
        .filter { it.isFile && it.extension == "jsonl" && it.name.startsWith("segment-") }
        .toList()

    private fun isEnabled(): Boolean = try {
        persistenceEnabled()
    } catch (_: RuntimeException) {
        false
    }

    private fun isValidRecord(line: String): Boolean = runCatching {
        val record = json.parseToJsonElement(line).jsonObject
        val schemaVersion = record["schemaVersion"] as? JsonPrimitive ?: return false
        if (schemaVersion.isString || schemaVersion.intOrNull != SCHEMA_VERSION) return false
        val recordType = record["recordType"] as? JsonPrimitive ?: return false
        recordType.isString && recordType.content in SUPPORTED_RECORD_TYPES
    }.getOrDefault(false)

    private fun <T> withRootLock(block: () -> T): T {
        val acquiredLocally = try {
            ROOT_LOCK.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquiredLocally) throw TimeoutException("Diagnostic history lock unavailable")
        return try {
            rootDirectory.mkdirs()
            val lockFile = File(rootDirectory, LOCK_FILE_NAME)
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeoutMillis)
                var lock: java.nio.channels.FileLock? = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                while (lock == null && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(2)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.IOException("Diagnostic history lock interrupted")
                    }
                    lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                }
                val acquired = lock ?: throw TimeoutException("Diagnostic history lock unavailable")
                acquired.use { block() }
            }
        } finally {
            ROOT_LOCK.unlock()
        }
    }

    private fun encodeDroppedRecord(count: Long, timestamp: Long): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("recordType", "dropped_events")
            put("timestampMillis", timestamp)
            put("count", count)
        },
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
        private const val SEGMENT_BYTES = 256L * 1024
        private const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
        private const val DEFAULT_MAX_AGE_MILLIS = 3L * MILLIS_PER_DAY
        private const val DEFAULT_QUEUE_CAPACITY = 128
        private const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 5_000L
        private const val LOCK_TIMEOUT_MILLIS = 250L
        private const val MAX_SEGMENT_FILES = 256
        private const val LOCK_FILE_NAME = ".structured-diagnostics.lock"
        private const val INSTANCE_DIRECTORY_PREFIX = "instance-"
        private val SUPPORTED_RECORD_TYPES = setOf("event", "dropped_events")

        private val ROOT_LOCK = ReentrantLock()
        private val processExecutor: ExecutorService by lazy {
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(DEFAULT_QUEUE_CAPACITY),
                ThreadFactory { runnable ->
                    Thread(runnable, "tsuzuki-diagnostics-writer").apply { isDaemon = true }
                },
                ThreadPoolExecutor.AbortPolicy(),
            )
        }
    }
}

private enum class AppendResult {
    WRITTEN,
    DISABLED,
    FAILED,
}

/** Single public codec for sanitized event JSON used by history and safe diagnostics output. */
object StructuredDiagnosticHistoryJson {

    private val json = Json { explicitNulls = false }

    fun serialize(event: SanitizedStructuredDiagnosticEvent): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", event.schemaVersion)
            put("recordType", "event")
            put("timestampMillis", event.timestampMillis)
            put("severity", event.severity.name)
            put("subsystem", event.subsystem.name)
            put("name", event.name.name.lowercase())
            put("sessionId", event.sessionId)
            event.operationId?.let { put("operationId", it) }
            put("stage", event.stage.name.lowercase())
            put("outcome", event.outcome.name.lowercase())
            event.durationMillis?.let { put("durationMillis", it) }
            event.attempt?.let { put("attempt", it) }
            put("attributes", encodeAttributes(event.attributes))
        },
    )

    private fun encodeAttributes(attributes: Map<DiagnosticAttribute, DiagnosticAttributeValue>) = buildJsonObject {
        attributes.toSortedMap(compareBy { it.name }).forEach { (key, value) ->
            put(key.name.lowercase(), encodeAttribute(value))
        }
    }

    private fun encodeAttribute(value: DiagnosticAttributeValue) = buildJsonObject {
        when (value) {
            is DiagnosticAttributeValue.Number -> {
                put("type", "number")
                put("value", value.value)
            }
            is DiagnosticAttributeValue.Flag -> {
                put("type", "flag")
                put("value", value.value)
            }
            is DiagnosticAttributeValue.Text -> {
                put("type", "text")
                put("value", value.value)
            }
            is DiagnosticAttributeValue.Code -> {
                put("type", "code")
                put("value", value.value.toString())
            }
        }
    }
}
