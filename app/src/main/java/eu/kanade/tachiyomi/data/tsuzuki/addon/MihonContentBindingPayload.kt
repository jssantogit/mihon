package eu.kanade.tachiyomi.data.tsuzuki.addon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class MihonContentBindingPayload(
    val sourceId: Long,
    val mihonMangaId: Long,
    val sourceUrl: String,
    val language: String,
)

object MihonContentBindingPayloadCodec {

    fun encode(payload: MihonContentBindingPayload): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeLong(payload.sourceId)
            output.writeLong(payload.mihonMangaId)
            output.writeString(payload.sourceUrl)
            output.writeString(payload.language)
        }
        return bytes.toByteArray()
    }

    fun decode(payload: ByteArray): MihonContentBindingPayload {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported Mihon binding payload version" }
            val result = MihonContentBindingPayload(
                sourceId = input.readLong(),
                mihonMangaId = input.readLong(),
                sourceUrl = input.readString(),
                language = input.readString(),
            )
            require(input.available() == 0) { "Unexpected trailing Mihon binding payload bytes" }
            return result
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= MAX_FIELD_BYTES) { "Mihon binding payload field is too large" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES) { "Invalid Mihon binding payload field length" }
        val encoded = ByteArray(size)
        readFully(encoded)
        return encoded.decodeToString()
    }

    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 1_048_576
}
