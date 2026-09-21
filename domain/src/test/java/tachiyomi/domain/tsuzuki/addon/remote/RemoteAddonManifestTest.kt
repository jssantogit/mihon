package tachiyomi.domain.tsuzuki.addon.remote

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RemoteAddonManifestTest {

    @Test
    fun validHttpsManifestParsesStrictly() {
        val manifest = RemoteAddonManifestParser.parse(
            """
            {
              "id": "example.remote",
              "name": "Example",
              "version": "1.0.0",
              "protocolVersion": 1,
              "capabilities": ["CONTENT", "CHAPTER_PROBE"],
              "endpoints": {
                "content": "https://addon.example/content",
                "chapterProbe": "https://addon.example/chapters"
              }
            }
            """.trimIndent(),
        )

        manifest.id shouldBe "example.remote"
        manifest.protocolVersion shouldBe 1
        manifest.capabilities shouldBe setOf(
            RemoteAddonCapability.CONTENT,
            RemoteAddonCapability.CHAPTER_PROBE,
        )
    }

    @Test
    fun unknownProtocolVersionIsRejected() {
        shouldThrow<IllegalArgumentException> {
            RemoteAddonManifestParser.parse(
                """
                {
                  "id": "example.remote",
                  "name": "Example",
                  "version": "1.0.0",
                  "protocolVersion": 99,
                  "capabilities": ["CONTENT"],
                  "endpoints": {"content": "https://addon.example/content"}
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun nonHttpsRemoteEndpointIsRejected() {
        shouldThrow<IllegalArgumentException> {
            RemoteAddonManifestParser.parse(
                """
                {
                  "id": "example.remote",
                  "name": "Example",
                  "version": "1.0.0",
                  "protocolVersion": 1,
                  "capabilities": ["CONTENT"],
                  "endpoints": {"content": "http://addon.example/content"}
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun localhostHttpRequiresExplicitDevelopmentMode() {
        val raw = """
            {
              "id": "dev.remote",
              "name": "Dev",
              "version": "1.0.0",
              "protocolVersion": 1,
              "capabilities": ["CONTENT"],
              "endpoints": {"content": "http://localhost:8080/content"}
            }
        """.trimIndent()

        shouldThrow<IllegalArgumentException> {
            RemoteAddonManifestParser.parse(raw)
        }
        RemoteAddonManifestParser.parse(
            raw = raw,
            allowLocalhostDevelopment = true,
        ).id shouldBe "dev.remote"
    }

    @Test
    fun declaredCapabilityRequiresItsEndpoint() {
        shouldThrow<IllegalArgumentException> {
            RemoteAddonManifestParser.parse(
                """
                {
                  "id": "example.remote",
                  "name": "Example",
                  "version": "1.0.0",
                  "protocolVersion": 1,
                  "capabilities": ["CONTENT", "CHAPTER_PROBE"],
                  "endpoints": {"content": "https://addon.example/content"}
                }
                """.trimIndent(),
            )
        }
    }
}
