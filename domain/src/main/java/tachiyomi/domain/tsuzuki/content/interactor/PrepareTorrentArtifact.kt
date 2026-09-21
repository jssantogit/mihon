package tachiyomi.domain.tsuzuki.content

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.PreparedTorrentArtifact
import tachiyomi.domain.tsuzuki.content.TorrentArtifactEngine
import tachiyomi.domain.tsuzuki.content.TorrentArtifactRequest
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

@Inject
class PrepareTorrentArtifact(
    private val engine: TorrentArtifactEngine,
) {

    fun toRequest(delivery: ContentDelivery.Torrent): TorrentArtifactRequest {
        require(delivery.infoHash.isNotBlank()) { "Torrent info hash must not be blank" }
        require(delivery.fileIndex == null || delivery.fileIndex >= 0) {
            "Torrent file index must not be negative"
        }
        delivery.filePath?.let(::validateTorrentFilePath)
        return TorrentArtifactRequest(
            infoHash = delivery.infoHash,
            magnetUri = delivery.magnetUri,
            fileIndex = delivery.fileIndex,
            filePath = delivery.filePath,
        )
    }

    suspend fun execute(delivery: ContentDelivery.Torrent): Result<PreparedChapterContent> {
        return try {
            val request = toRequest(delivery)
            engine.acquire(request).fold(
                onSuccess = ::prepareArtifact,
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Result.failure(error)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun prepareArtifact(artifact: PreparedTorrentArtifact): Result<PreparedChapterContent> {
        if (artifact.localUri.isBlank()) {
            return Result.failure(IllegalArgumentException("Prepared torrent artifact URI must not be blank"))
        }
        val prepared = when (artifact.format.uppercase()) {
            "DIRECTORY" -> PreparedChapterContent.LocalDirectory(artifact.localUri)
            "CBZ", "CBR", "ZIP", "RAR", "ARCHIVE" -> PreparedChapterContent.LocalArchive(artifact.localUri)
            "EPUB" -> PreparedChapterContent.CanonicalDownload(
                uri = artifact.localUri,
                format = "EPUB",
            )
            else -> return Result.failure(
                IllegalArgumentException("Unsupported prepared torrent artifact format: ${artifact.format}"),
            )
        }
        return Result.success(prepared)
    }
}

fun validateTorrentFilePath(path: String): String {
    require(path.isNotBlank()) { "Torrent file path must not be blank" }
    require('\u0000' !in path) { "Torrent file path contains a NUL byte" }
    require(!path.startsWith("/") && !path.startsWith("\\")) {
        "Torrent file path must be relative"
    }
    require(!Regex("^[A-Za-z]:[\\\\/].*").matches(path)) {
        "Torrent file path must not use an absolute Windows path"
    }
    val segments = path.replace('\\', '/').split('/')
    require(segments.none { it == ".." }) { "Torrent file path traversal is not allowed" }
    return path
}
