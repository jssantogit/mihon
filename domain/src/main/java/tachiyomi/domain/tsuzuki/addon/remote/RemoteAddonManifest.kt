package tachiyomi.domain.tsuzuki.addon.remote

import kotlinx.serialization.Serializable

@Serializable
data class RemoteAddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val protocolVersion: Int,
    val capabilities: Set<RemoteAddonCapability>,
    val endpoints: RemoteAddonEndpoints,
)

@Serializable
enum class RemoteAddonCapability {
    CONTENT,
    CHAPTER_PROBE,
}

@Serializable
data class RemoteAddonEndpoints(
    val content: String? = null,
    val chapterProbe: String? = null,
)
