package eu.kanade.tachiyomi.crash

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PersistentCrashLogStoreTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `uncaught crash record survives a new store instance`() {
        val directory = temporaryDirectory.resolve("crash").toFile()
        val store = PersistentCrashLogStore(directory) { 1234L }

        store.record(
            thread = Thread("startup-thread"),
            exception = IllegalStateException("bootstrap failed"),
        )

        val reopened = PersistentCrashLogStore(directory)
        val report = requireNotNull(reopened.read())

        report shouldContain "Tsuzuki persistent crash record"
        report shouldContain "timestamp_ms=1234"
        report shouldContain "thread=startup-thread"
        report shouldContain "IllegalStateException: bootstrap failed"
    }

    @Test
    fun `clear removes persisted crash record`() {
        val directory = temporaryDirectory.resolve("clear").toFile()
        val store = PersistentCrashLogStore(directory) { 1L }
        store.record(Thread.currentThread(), RuntimeException("boom"))

        store.clear() shouldBe true
        store.read() shouldBe null
    }
}
