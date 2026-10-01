package tachiyomi.domain.tsuzuki.chapter.refresh

import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import java.security.MessageDigest

data class ChapterRefreshSnapshot(
    val canonicalTitleId: String,
    val scopeKey: String,
    val fingerprint: String?,
    val configurationFingerprint: String,
    val observedAt: Long,
    val refreshedAt: Long,
    val itemCount: Int = 0,
) {
    companion object {
        const val TITLE_SCOPE = "__title__"

        fun bindingScope(bindingId: String): String = "binding:$bindingId"
    }
}

object ChapterInventoryFingerprint {

    fun compute(inventory: SourceChapterInventory): String = digest(
        buildList {
            add("mapping=${inventory.sourceMappingId}")
            add("source=${inventory.sourceId}")
            add("title=${inventory.canonicalTitleId}")
            add("manga=${inventory.mihonMangaId.orEmptyToken()}")
            add("language=${inventory.language}")
            add("url=${inventory.sourceUrl}")
            inventory.chapters
                .map { chapter ->
                    listOf(
                        chapter.sourceId.toString(),
                        chapter.sourceMappingId,
                        chapter.sourceChapterId,
                        chapter.sourceChapterUrl,
                        chapter.rawName,
                        chapter.language,
                        chapter.rawNumberHint?.toString().orEmptyToken(),
                        chapter.mihonMangaId.orEmptyToken(),
                    ).joinToString(separator = "\u0000")
                }
                .sorted()
                .forEach { add("chapter=$it") }
        },
    )
}

object ChapterRefreshConfigurationFingerprint {
    fun compute(tokens: Collection<String>): String = digest(tokens.sorted())
}

private fun digest(tokens: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    tokens.forEach { token ->
        val bytes = token.encodeToByteArray()
        digest.update(bytes.size.toString().encodeToByteArray())
        digest.update(':'.code.toByte())
        digest.update(bytes)
        digest.update(';'.code.toByte())
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun Long?.orEmptyToken(): String = this?.toString() ?: "-"
private fun Double?.orEmptyToken(): String = this?.toString() ?: "-"
