package eu.kanade.tachiyomi.data.tsuzuki.integration

import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.CapabilityPolicy
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
import tachiyomi.domain.tsuzuki.integration.model.IntegrationPolicy

internal object DefaultIntegrationManifests {
    private fun policies(
        policy: IntegrationPolicy,
        vararg capabilities: IntegrationCapability,
        attribution: String? = null,
    ) = capabilities.associateWith { CapabilityPolicy(policy, attribution = attribution) }

    val all = listOf(
        IntegrationManifest(
            integrationId = IntegrationId("kitsu"),
            displayName = "Kitsu",
            category = IntegrationCategory.METADATA_SERVICE,
            capabilities = policies(
                IntegrationPolicy.ALLOWED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.DISCOVERY,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.METADATA_EDITORIAL,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ),
            legacyTrackerId = 3L,
        ),
        IntegrationManifest(
            integrationId = IntegrationId("mal"),
            displayName = "MyAnimeList",
            category = IntegrationCategory.METADATA_SERVICE,
            capabilities = policies(
                IntegrationPolicy.ALLOWED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.DISCOVERY,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.METADATA_EDITORIAL,
                IntegrationCapability.METADATA_STAFF,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ),
            legacyTrackerId = 1L,
        ),
        IntegrationManifest(
            integrationId = IntegrationId("mangaupdates"),
            displayName = "MangaUpdates",
            category = IntegrationCategory.METADATA_SERVICE,
            capabilities = policies(
                IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION,
                IntegrationCapability.SEARCH,
                IntegrationCapability.DISCOVERY,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.METADATA_EDITORIAL,
                IntegrationCapability.METADATA_STAFF,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
                attribution = "MangaUpdates",
            ),
            legacyTrackerId = 7L,
        ),
        IntegrationManifest(
            integrationId = IntegrationId("bangumi"),
            displayName = "Bangumi",
            category = IntegrationCategory.METADATA_SERVICE,
            capabilities = policies(
                IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION,
                IntegrationCapability.SEARCH,
                IntegrationCapability.DISCOVERY,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.METADATA_EDITORIAL,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
                attribution = "Bangumi",
            ),
            legacyTrackerId = 5L,
        ),
        IntegrationManifest(
            integrationId = IntegrationId("shikimori"),
            displayName = "Shikimori",
            category = IntegrationCategory.METADATA_SERVICE,
            capabilities = policies(
                IntegrationPolicy.UNVERIFIED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ) + policies(
                IntegrationPolicy.ALLOWED,
                IntegrationCapability.RATINGS,
            ),
            legacyTrackerId = 4L,
        ),
        IntegrationManifest(
            integrationId = IntegrationId("hikka"),
            displayName = "Hikka",
            category = IntegrationCategory.METADATA_SERVICE,
            capabilities = policies(
                IntegrationPolicy.UNVERIFIED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ) + policies(
                IntegrationPolicy.ALLOWED,
                IntegrationCapability.RATINGS,
            ),
            legacyTrackerId = 10L,
        ),
        personalServer("komga", "Komga", 6L),
        personalServer("kavita", "Kavita", 8L),
        personalServer(
            "suwayomi",
            "Suwayomi",
            9L,
            IntegrationCapability.READING_CONTENT,
            IntegrationCapability.DOWNLOADS,
        ),
    )

    private fun personalServer(
        id: String,
        name: String,
        legacyTrackerId: Long,
        vararg extra: IntegrationCapability,
    ) = IntegrationManifest(
        integrationId = IntegrationId(id),
        displayName = name,
        category = IntegrationCategory.PERSONAL_SERVER,
        capabilities = policies(
            IntegrationPolicy.USER_OWNED_DATA,
            IntegrationCapability.METADATA_BASIC,
            IntegrationCapability.METADATA_ARTWORK,
            IntegrationCapability.REMOTE_LIBRARY,
            IntegrationCapability.TRACKING,
            *extra,
        ),
        legacyTrackerId = legacyTrackerId,
    )
}
