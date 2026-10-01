package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedLogcatCollectorTest {
    @Test
    fun `keeps a bounded partial capture after a nonzero exit`() {
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { FakeProcess("123456789".toByteArray(), exitCode = 4) },
            timeoutMillis = 1_000,
            maxOutputBytes = 4,
        )

        val capture = collector.collect("E")

        assertEquals("1234", capture.content)
        assertEquals(LogcatFailure.EXIT_CODE, capture.failure)
        assertTrue(capture.failures.contains(LogcatFailure.OUTPUT_TRUNCATED))
    }

    @Test
    fun `kills and reaps a timed out process while retaining partial output`() {
        val process = FakeProcess("partial".toByteArray(), exitCode = 0, completeWait = false)
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { process },
            timeoutMillis = 1,
            maxOutputBytes = 32,
        )

        val capture = collector.collect("E")

        assertEquals("partial", capture.content)
        assertEquals(LogcatFailure.TIMEOUT, capture.failure)
        assertTrue(process.wasDestroyed)
        assertTrue(process.wasForceDestroyed)
        assertTrue(process.wasReaped)
    }

    @Test
    fun `preserves complete stdout after normal process exit`() {
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { FakeProcess("complete output".toByteArray(), exitCode = 0) },
            timeoutMillis = 1_000,
            maxOutputBytes = 64,
        )

        val capture = collector.collect("E")

        assertEquals("complete output", capture.content)
        assertEquals(null, capture.failure)
    }

    @Test
    fun `marks truncated output partial even when the child exits successfully`() {
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { FakeProcess("0123456789".toByteArray(), exitCode = 0) },
            timeoutMillis = 1_000,
            maxOutputBytes = 4,
        )

        val capture = collector.collect("E")

        assertEquals("0123", capture.content)
        assertEquals(LogcatFailure.OUTPUT_TRUNCATED, capture.failure)
    }

    @Test
    fun `marks a child that survives force destroy as unavailable without an unbounded wait`() {
        val process = FakeProcess("partial".toByteArray(), exitCode = 0, completeWait = false, exitsAfterForce = false)
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { process },
            timeoutMillis = 1,
            maxOutputBytes = 32,
        )

        val capture = collector.collect("E")

        assertEquals(LogcatFailure.PROCESS_STUCK, capture.failure)
        assertTrue(process.wasForceDestroyed)
        assertTrue(process.isAlive)
    }

    @Test
    fun `restores interrupt status after interrupted process wait`() {
        val process = FakeProcess("partial".toByteArray(), exitCode = 0, throwOnFirstWait = true)
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { process },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        )

        try {
            val capture = collector.collect("E")
            assertEquals(LogcatFailure.START_FAILED, capture.failure)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `propagates cancellation from process starter`() {
        val cancellation = CancellationException("cancelled start")
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { throw cancellation },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        )

        try {
            collector.collect("E")
            throw AssertionError("expected cancellation")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }
    }

    @Test
    fun `propagates cancellation from process wait after bounded cleanup`() {
        val cancellation = CancellationException("cancelled wait")
        val process = FakeProcess("partial".toByteArray(), exitCode = 0, waitCancellation = cancellation)
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { process },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        )

        try {
            collector.collect("E")
            throw AssertionError("expected cancellation")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
            assertTrue(process.wasDestroyed)
        }
    }

    @Test
    fun `reports ordinary process start and input stream failures`() {
        val startFailure = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { throw IllegalStateException("not available") },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        ).collect("E")
        val inputFailure = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter {
                FakeProcess("".toByteArray(), exitCode = 0, inputStreamFailure = IllegalStateException("no stream"))
            },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        ).collect("E")

        assertEquals(LogcatFailure.START_FAILED, startFailure.failure)
        assertEquals(LogcatFailure.START_FAILED, inputFailure.failure)
    }

    @Test
    fun `marks forced reader cutoff incomplete even when close returns EOF`() {
        val stream = DelayedEofStream()
        val process = FakeProcess(
            output = byteArrayOf(),
            exitCode = 0,
            suppliedInput = stream,
            onTimedWait = { check(stream.readStarted.await(1, TimeUnit.SECONDS)) },
        )
        val capture = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { process },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        ).collect("E")

        assertTrue(stream.closed.await(1, TimeUnit.SECONDS))
        assertTrue(capture.failures.contains(LogcatFailure.OUTPUT_INCOMPLETE))
    }

    @Test
    fun `blocked reader close cannot extend collector beyond bounded waits`() {
        val stream = BlockedCloseStream()
        val process = FakeProcess(
            output = byteArrayOf(),
            exitCode = 0,
            suppliedInput = stream,
            onTimedWait = { check(stream.readStarted.await(1, TimeUnit.SECONDS)) },
        )
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { process },
            timeoutMillis = 1_000,
            maxOutputBytes = 32,
        )
        val startNanos = System.nanoTime()

        try {
            val capture = collector.collect("E")
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            assertTrue(elapsedMillis < 1_500, "collector took $elapsedMillis ms")
            assertTrue(capture.failures.contains(LogcatFailure.OUTPUT_INCOMPLETE))
            assertTrue(stream.closeStarted.await(1, TimeUnit.SECONDS))
        } finally {
            stream.release.countDown()
        }
    }

    private class FakeProcess(
        output: ByteArray,
        private val exitCode: Int,
        private val completeWait: Boolean = true,
        private val exitsAfterForce: Boolean = true,
        private val throwOnFirstWait: Boolean = false,
        private val suppliedInput: InputStream? = null,
        private val inputStreamFailure: Exception? = null,
        private val waitCancellation: CancellationException? = null,
        private val onTimedWait: (() -> Unit)? = null,
    ) : Process() {
        private var alive = true
        private var waitAttempted = false
        private val input = ByteArrayInputStream(output)
        var wasDestroyed = false
            private set
        var wasForceDestroyed = false
            private set
        var wasReaped = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream {
            inputStreamFailure?.let { throw it }
            return suppliedInput ?: input
        }
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            wasReaped = true
            alive = false
            return exitCode
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCancellation?.let { throw it }
            onTimedWait?.invoke()
            if (throwOnFirstWait && !waitAttempted) {
                waitAttempted = true
                throw InterruptedException()
            }
            if (!completeWait && (!wasForceDestroyed || !exitsAfterForce)) return false
            alive = false
            wasReaped = true
            return true
        }
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            wasDestroyed = true
        }
        override fun destroyForcibly(): Process {
            wasForceDestroyed = true
            return this
        }
        override fun isAlive(): Boolean = alive
    }

    private class DelayedEofStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            release.await()
            return -1
        }

        override fun close() {
            closed.countDown()
            release.countDown()
        }
    }

    private class BlockedCloseStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            release.await()
            return -1
        }

        override fun close() {
            closeStarted.countDown()
            release.await()
        }
    }
}
