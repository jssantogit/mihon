package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import java.nio.file.Files

class NoBackupGoogleAccountHintStoreTest {

    private val account = GoogleAccountIdentity("reader@example.com")

    @Test
    fun `case 1 - account identity round trips through no-backup file`() {
        withTempStore { store, _ ->
            store.write(account)

            store.read() shouldBe account
        }
    }

    @Test
    fun `case 2 - persisted file contains only the non-secret account hint`() {
        withTempStore { store, file ->
            store.write(account)

            file.readText() shouldBe account.accountName
        }
    }

    @Test
    fun `case 3 - clearing account hint removes persisted identity`() {
        withTempStore { store, _ ->
            store.write(account)
            store.clear()

            store.read().shouldBeNull()
        }
    }

    @Test
    fun `case 4 - blank or corrupt hint fails closed as no account`() {
        withTempStore { store, file ->
            file.parentFile?.mkdirs()
            file.writeText("   ")

            store.read().shouldBeNull()
        }
    }

    private fun withTempStore(
        block: (NoBackupGoogleAccountHintStore, java.io.File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("tsuzuki-google-auth-test").toFile()
        val file = directory.resolve("account-hint")
        try {
            block(NoBackupGoogleAccountHintStore(file), file)
        } finally {
            directory.deleteRecursively()
        }
    }
}
