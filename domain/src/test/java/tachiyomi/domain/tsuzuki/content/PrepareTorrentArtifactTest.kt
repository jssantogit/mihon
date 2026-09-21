package tachiyomi.domain.tsuzuki.content

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

class PrepareTorrentArtifactTest {

    @Test
    fun explicitTorrentFileIndexIsPreservedAndNotGuessed() {
        val preparer = PrepareTorrentArtifact(FakeTorrentArtifactEngine())
        val delivery = ContentDelivery.Torrent(
            infoHash = "abc",
            magnetUri = null,
            fileIndex = 4,
            filePath = "Volume 01/chapter05.cbz",
        )

        val request = preparer.toRequest(delivery)

        request.fileIndex shouldBe 4
        request.filePath shouldBe "Volume 01/chapter05.cbz"
    }

    @Test
    fun torrentPathTraversalIsRejectedBeforeArtifactExposure() {
        shouldThrow<IllegalArgumentException> {
            validateTorrentFilePath("../outside.cbz")
        }
        shouldThrow<IllegalArgumentException> {
            validateTorrentFilePath("Volume 01/../../outside.cbz")
        }
        shouldThrow<IllegalArgumentException> {
            validateTorrentFilePath("/absolute/chapter.cbz")
        }
    }

    @Test
    fun completedArchiveBecomesProviderNeutralLocalArchive() = runTest {
        val engine = FakeTorrentArtifactEngine(
            PreparedTorrentArtifact(
                localUri = "content://torrent/chapter.cbz",
                format = "CBZ",
            ),
        )
        val preparer = PrepareTorrentArtifact(engine)

        val result = preparer.execute(delivery()).getOrThrow()

        result shouldBe PreparedChapterContent.LocalArchive("content://torrent/chapter.cbz")
    }

    @Test
    fun completedDirectoryBecomesProviderNeutralLocalDirectory() = runTest {
        val engine = FakeTorrentArtifactEngine(
            PreparedTorrentArtifact(
                localUri = "content://torrent/chapter",
                format = "DIRECTORY",
            ),
        )
        val preparer = PrepareTorrentArtifact(engine)

        preparer.execute(delivery()).getOrThrow() shouldBe
            PreparedChapterContent.LocalDirectory("content://torrent/chapter")
    }

    @Test
    fun unsupportedCompletedFormatFailsClosed() = runTest {
        val preparer = PrepareTorrentArtifact(
            FakeTorrentArtifactEngine(
                PreparedTorrentArtifact(
                    localUri = "content://torrent/chapter.exe",
                    format = "EXE",
                ),
            ),
        )

        preparer.execute(delivery()).isFailure shouldBe true
    }

    private fun delivery() = ContentDelivery.Torrent(
        infoHash = "abc",
        magnetUri = "magnet:?xt=urn:btih:abc",
        fileIndex = 0,
        filePath = "chapter.cbz",
    )

    private class FakeTorrentArtifactEngine(
        private val artifact: PreparedTorrentArtifact? = null,
    ) : TorrentArtifactEngine {
        var lastRequest: TorrentArtifactRequest? = null

        override suspend fun acquire(request: TorrentArtifactRequest): Result<PreparedTorrentArtifact> {
            lastRequest = request
            return artifact?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("artifact unavailable"))
        }
    }
}
