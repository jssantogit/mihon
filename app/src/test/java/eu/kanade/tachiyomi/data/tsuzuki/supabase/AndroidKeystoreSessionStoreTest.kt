package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AndroidKeystoreSessionStoreTest {

    @Test
    fun `session file never contains plaintext access or refresh tokens`() {
        val directory = Files.createTempDirectory("tsuzuki-session-test").toFile()
        val store = AndroidKeystoreSessionStore(
            directory = directory,
            cipher = ReversibleTestCipher(),
        )
        val session = SupabaseSession(
            accessToken = "access-super-secret",
            refreshToken = "refresh-super-secret",
            expiresAtEpochSeconds = 1234,
            userId = "user-1",
            email = "reader@example.com",
        )

        store.save(session)

        val raw = File(directory, AndroidKeystoreSessionStore.FILE_NAME).readText()
        raw.contains("access-super-secret") shouldBe false
        raw.contains("refresh-super-secret") shouldBe false
        store.load() shouldBe session
    }

    @Test
    fun `clear removes persisted session`() {
        val directory = Files.createTempDirectory("tsuzuki-session-clear-test").toFile()
        val store = AndroidKeystoreSessionStore(
            directory = directory,
            cipher = ReversibleTestCipher(),
        )
        store.save(
            SupabaseSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochSeconds = 1234,
                userId = "user-1",
                email = "reader@example.com",
            ),
        )

        store.clear()

        store.load() shouldBe null
        File(directory, AndroidKeystoreSessionStore.FILE_NAME).exists() shouldBe false
    }

    private class ReversibleTestCipher : SessionCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedSessionPayload =
            EncryptedSessionPayload(
                iv = byteArrayOf(1, 2, 3),
                ciphertext = plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray(),
            )

        override fun decrypt(payload: EncryptedSessionPayload): ByteArray =
            payload.ciphertext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
    }
}
