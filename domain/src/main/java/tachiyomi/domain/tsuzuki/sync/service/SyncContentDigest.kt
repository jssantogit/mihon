package tachiyomi.domain.tsuzuki.sync.service

import java.security.MessageDigest

fun interface SyncContentDigest {
    fun digest(content: String): String
}

class Sha256SyncContentDigest : SyncContentDigest {

    override fun digest(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))

        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
