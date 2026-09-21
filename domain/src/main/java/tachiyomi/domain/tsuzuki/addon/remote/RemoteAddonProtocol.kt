package tachiyomi.domain.tsuzuki.addon.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

object RemoteAddonManifestParser {
    const val CURRENT_PROTOCOL_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(
        raw: String,
        allowLocalhostDevelopment: Boolean = false,
    ): RemoteAddonManifest {
        val manifest = json.decodeFromString<RemoteAddonManifest>(raw)
        require(manifest.id.isNotBlank()) { "Remote Add-on id must not be blank" }
        require(manifest.name.isNotBlank()) { "Remote Add-on name must not be blank" }
        require(manifest.version.isNotBlank()) { "Remote Add-on version must not be blank" }
        require(manifest.protocolVersion == CURRENT_PROTOCOL_VERSION) {
            "Unsupported remote Add-on protocol version: ${manifest.protocolVersion}"
        }
        require(manifest.capabilities.isNotEmpty()) { "Remote Add-on must declare at least one capability" }

        manifest.endpoints.content?.let { endpoint ->
            validateEndpoint(endpoint, allowLocalhostDevelopment)
        }
        manifest.endpoints.chapterProbe?.let { endpoint ->
            validateEndpoint(endpoint, allowLocalhostDevelopment)
        }

        if (RemoteAddonCapability.CONTENT in manifest.capabilities) {
            require(!manifest.endpoints.content.isNullOrBlank()) {
                "CONTENT capability requires a content endpoint"
            }
        }
        if (RemoteAddonCapability.CHAPTER_PROBE in manifest.capabilities) {
            require(!manifest.endpoints.chapterProbe.isNullOrBlank()) {
                "CHAPTER_PROBE capability requires a chapterProbe endpoint"
            }
        }
        return manifest
    }

    private fun validateEndpoint(
        value: String,
        allowLocalhostDevelopment: Boolean,
    ) {
        val uri = runCatching { URI(value) }
            .getOrElse { error -> throw IllegalArgumentException("Invalid remote Add-on endpoint", error) }
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "Remote Add-on endpoint must be absolute" }
        if (uri.scheme.equals("https", ignoreCase = true)) return

        val host = uri.host.lowercase()
        val localHost = host == "localhost" || host == "127.0.0.1" || host == "::1"
        require(
            allowLocalhostDevelopment && localHost && uri.scheme.equals("http", ignoreCase = true),
        ) {
            "Remote Add-on endpoints must use HTTPS"
        }
    }
}

@Serializable
data class RemoteAddonContentRequest(
    val canonicalTitleId: String,
    val canonicalChapterId: String,
)

@Serializable
data class RemoteAddonChapterProbeRequest(
    val canonicalTitleId: String,
)

@Serializable
data class RemoteAddonProtocolError(
    val code: String,
    val message: String,
)
