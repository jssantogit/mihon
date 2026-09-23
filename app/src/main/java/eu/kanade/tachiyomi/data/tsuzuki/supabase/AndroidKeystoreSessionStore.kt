package eu.kanade.tachiyomi.data.tsuzuki.supabase

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SupabaseSessionStore {
    fun load(): SupabaseSession?

    fun save(session: SupabaseSession)

    fun clear()
}

data class EncryptedSessionPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface SessionCipher {
    fun encrypt(plaintext: ByteArray): EncryptedSessionPayload

    fun decrypt(payload: EncryptedSessionPayload): ByteArray
}

class AndroidKeystoreSessionStore(
    private val directory: File,
    private val cipher: SessionCipher,
    private val json: Json = Json,
) : SupabaseSessionStore {

    constructor(
        context: Context,
        json: Json = Json,
    ) : this(
        directory = context.noBackupFilesDir,
        cipher = AndroidKeystoreSessionCipher(),
        json = json,
    )

    private val file: File
        get() = File(directory, FILE_NAME)

    override fun load(): SupabaseSession? {
        if (!file.exists()) return null
        return runCatching {
            val envelope = json.decodeFromString(
                SessionEnvelope.serializer(),
                file.readText(),
            )
            val payload = EncryptedSessionPayload(
                iv = Base64.getDecoder().decode(envelope.iv),
                ciphertext = Base64.getDecoder().decode(envelope.ciphertext),
            )
            val plaintext = cipher.decrypt(payload).decodeToString()
            json.decodeFromString(SupabaseSession.serializer(), plaintext)
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(session: SupabaseSession) {
        directory.mkdirs()
        val plaintext = json.encodeToString(SupabaseSession.serializer(), session).encodeToByteArray()
        val payload = cipher.encrypt(plaintext)
        val envelope = SessionEnvelope(
            iv = Base64.getEncoder().encodeToString(payload.iv),
            ciphertext = Base64.getEncoder().encodeToString(payload.ciphertext),
        )
        val temp = File(directory, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(SessionEnvelope.serializer(), envelope))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    override fun clear() {
        file.delete()
        File(directory, "$FILE_NAME.tmp").delete()
    }

    companion object {
        const val FILE_NAME = "tsuzuki_supabase_session.enc"
    }
}

private class AndroidKeystoreSessionCipher : SessionCipher {

    override fun encrypt(plaintext: ByteArray): EncryptedSessionPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedSessionPayload(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override fun decrypt(payload: EncryptedSessionPayload): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, payload.iv),
        )
        return cipher.doFinal(payload.ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tsuzuki_supabase_session_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
    }
}

@Serializable
private data class SessionEnvelope(
    val iv: String,
    val ciphertext: String,
)
