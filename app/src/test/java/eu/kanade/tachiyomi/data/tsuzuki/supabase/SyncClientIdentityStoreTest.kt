package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import org.junit.jupiter.api.Test

class SyncClientIdentityStoreTest {

    @Test
    fun `installation id is stable inside no backup directory`() {
        val directory = Files.createTempDirectory("tsuzuki-client-id").toFile()
        var generated = 0
        val store = SyncClientIdentityStore(
            directory = directory,
            idSource = {
                generated += 1
                "client-$generated"
            },
        )

        store.getOrCreate() shouldBe "client-1"
        store.getOrCreate() shouldBe "client-1"
        SyncClientIdentityStore(
            directory = directory,
            idSource = { "should-not-be-used" },
        ).getOrCreate() shouldBe "client-1"
        generated shouldBe 1
    }

    @Test
    fun `blank or malformed persisted client id is replaced`() {
        val directory = Files.createTempDirectory("tsuzuki-client-id-invalid").toFile()
        directory.resolve(SyncClientIdentityStore.FILE_NAME).writeText("   ")
        val store = SyncClientIdentityStore(
            directory = directory,
            idSource = { "replacement-id" },
        )

        store.getOrCreate() shouldBe "replacement-id"
        directory.resolve(SyncClientIdentityStore.FILE_NAME).readText() shouldBe "replacement-id"
    }
}
