package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class LogcatFailure(val marker: String) {
    START_FAILED("start_failed"),
    TIMEOUT("timeout"),
    EXIT_CODE("exit_code"),
    PROCESS_STUCK("process_stuck"),
    OUTPUT_TRUNCATED("output_truncated"),
    OUTPUT_INCOMPLETE("output_incomplete"),
}

data class LogcatCapture private constructor(
    val content: String,
    val failures: List<LogcatFailure>,
) {
    val failure: LogcatFailure? get() = failures.firstOrNull()
    val isPartial: Boolean get() = failures.isNotEmpty()

    companion object {
        fun complete(content: String) = LogcatCapture(content, emptyList())
        fun partial(content: String, failure: LogcatFailure) = LogcatCapture(content, listOf(failure))
        fun partial(content: String, failures: List<LogcatFailure>) = LogcatCapture(content, failures.distinct())
        fun unavailable(failure: LogcatFailure) = LogcatCapture("", listOf(failure))
    }
}

fun interface LogcatProcessStarter {
    fun start(priority: String): Process
}

/** Runs the platform collector with a time limit and a bounded stdout buffer. */
@SingleIn(AppScope::class)
class BoundedLogcatCollector internal constructor(
    private val processStarter: LogcatProcessStarter,
    private val timeoutMillis: Long,
    private val maxOutputBytes: Int,
) {
    @Inject
    constructor() : this(AndroidLogcatProcessStarter, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_OUTPUT_BYTES)

    init {
        require(timeoutMillis > 0)
        require(maxOutputBytes > 0)
    }

    fun collect(priority: String): LogcatCapture {
        val process = try {
            processStarter.start(priority)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return LogcatCapture.unavailable(LogcatFailure.START_FAILED)
        }

        val input = try {
            process.inputStream
        } catch (error: CancellationException) {
            bestEffortTerminateAndReap(process)
            throw error
        } catch (_: Exception) {
            terminateAndReap(process)
            return LogcatCapture.unavailable(LogcatFailure.START_FAILED)
        }
        val output = BoundedOutput(maxOutputBytes)
        val reader = try {
            Thread({ drainBounded(input, output) }, "tsuzuki-logcat-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: CancellationException) {
            bestEffortTerminateAndReap(process)
            throw error
        } catch (_: Exception) {
            terminateAndReap(process)
            return LogcatCapture.unavailable(LogcatFailure.START_FAILED)
        }

        var readerWasCutOff = false
        val primaryFailure = try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                if (!terminateAndReap(process)) LogcatFailure.PROCESS_STUCK else LogcatFailure.TIMEOUT
            } else if (process.exitValue() != 0) {
                LogcatFailure.EXIT_CODE
            } else {
                null
            }
        } catch (error: CancellationException) {
            bestEffortTerminateAndReap(process)
            throw error
        } catch (_: InterruptedException) {
            terminateAndReap(process)
            Thread.currentThread().interrupt()
            LogcatFailure.START_FAILED
        } catch (_: Exception) {
            terminateAndReap(process)
            LogcatFailure.START_FAILED
        } finally {
            if (!process.isAlive) joinReader(reader)
            if (reader.isAlive) {
                readerWasCutOff = true
                closeInputStreamAsync(input)
                joinReader(reader)
            }
        }

        val failures = buildList {
            primaryFailure?.let(::add)
            if (readerWasCutOff || reader.isAlive || output.readFailed) add(LogcatFailure.OUTPUT_INCOMPLETE)
            if (output.wasTruncated) add(LogcatFailure.OUTPUT_TRUNCATED)
        }.distinct()
        val text = output.snapshot().toString(Charsets.UTF_8)
        if (failures.isEmpty()) return LogcatCapture.complete(text)
        return LogcatCapture.partial(text, failures)
    }

    /** Returns true only after the child exits; all waits have a fixed bound. */
    private fun terminateAndReap(process: Process): Boolean {
        try {
            process.destroy()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Continue to force termination when graceful destroy fails.
        }
        val exited = waitBounded(process, DESTROY_GRACE_MILLIS)
        if (exited) return true
        try {
            process.destroyForcibly()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        return waitBounded(process, FORCE_REAP_MILLIS)
    }

    private fun waitBounded(process: Process, timeoutMillis: Long): Boolean = try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (error: CancellationException) {
        throw error
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) {
        false
    }

    private fun bestEffortTerminateAndReap(process: Process) {
        cleanupStep { process.destroy() }
        val exited = cleanupWait(process, DESTROY_GRACE_MILLIS)
        if (exited) return
        cleanupStep { process.destroyForcibly() }
        cleanupWait(process, FORCE_REAP_MILLIS)
    }

    private fun cleanupWait(process: Process, timeoutMillis: Long): Boolean = try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: CancellationException) {
        false
    } catch (_: Exception) {
        false
    }

    private inline fun cleanupStep(block: () -> Unit) {
        try {
            block()
        } catch (_: CancellationException) {
            // The original cancellation remains the one propagated by collect.
        } catch (_: Exception) {
            // Cleanup is best effort; the original cancellation remains observable.
        }
    }

    private fun closeInputStreamAsync(input: InputStream) {
        try {
            Thread({
                try {
                    input.close()
                } catch (_: Exception) {
                    // The report already marks this capture incomplete.
                }
            }, "tsuzuki-logcat-close").apply {
                isDaemon = true
                start()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Never let a stream close or thread-start failure hold up report composition.
        }
    }

    private fun joinReader(reader: Thread) {
        try {
            reader.join(READER_JOIN_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun drainBounded(input: InputStream, output: BoundedOutput) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            input.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    output.append(buffer, read)
                }
            }
        } catch (_: Exception) {
            output.readFailed = true
        }
    }

    private class BoundedOutput(private val limit: Int) {
        private val bytes = ByteArrayOutputStream(minOf(limit, 16 * 1024))

        @Volatile var wasTruncated: Boolean = false
            private set

        @Volatile var readFailed: Boolean = false

        @Synchronized
        fun append(buffer: ByteArray, length: Int) {
            val remaining = limit - bytes.size()
            if (remaining > 0) bytes.write(buffer, 0, minOf(length, remaining))
            if (length > remaining) wasTruncated = true
        }

        @Synchronized
        fun snapshot(): ByteArray = bytes.toByteArray()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 512 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val DESTROY_GRACE_MILLIS = 250L
        const val FORCE_REAP_MILLIS = 250L
        const val READER_JOIN_MILLIS = 250L
    }
}

private object AndroidLogcatProcessStarter : LogcatProcessStarter {
    override fun start(priority: String): Process = ProcessBuilder(
        "logcat",
        "*:$priority",
        "-d",
        "-t",
        MAX_LOGCAT_LINES.toString(),
        "-v",
        "year",
        "-v",
        "zone",
    ).redirectErrorStream(true).start()

    private const val MAX_LOGCAT_LINES = 4_000
}
