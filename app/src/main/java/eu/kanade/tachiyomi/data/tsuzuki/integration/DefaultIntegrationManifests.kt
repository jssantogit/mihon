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
            IntegrationId("kitsu"),\n            "Kitsu",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
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
        ),
        IntegrationManifest(
            IntegrationId("mal"),\n            "MyAnimeList",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
                IntegrationPolicy.ALLOWED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.METADATA_EDITORIAL,
                IntegrationCapability.METADATA_STAFF,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ),
        ),
        IntegrationManifest(
            IntegrationId("mangaupdates"),\n            "MangaUpdates",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
                IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.METADATA_EDITORIAL,
                IntegrationCapability.METADATA_STAFF,
                IntegrationCapability.RATINGS,
                IntegrationCapability.RELATIONS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
                attribution = "MangaUpdates",
            ),
        ),
        IntegrationManifest(
            IntegrationId("mangabaka"),\n            "MangaBaka",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
                IntegrationPolicy.COMMERCIAL_RESTRICTION,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.CROSSWALK,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
                attribution = "MangaBaka",
            ),
        ),
        IntegrationManifest(
            IntegrationId("bangumi"),\n            "Bangumi",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
                IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
                attribution = "Bangumi",
            ),
        ),
        IntegrationManifest(
            IntegrationId("shikimori"),\n            "Shikimori",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
                IntegrationPolicy.UNVERIFIED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ),
        ),
        IntegrationManifest(
            IntegrationId("hikka"),\n            "Hikka",\n            IntegrationCategory.METADATA_SERVICE,
            policies(
                IntegrationPolicy.UNVERIFIED,
                IntegrationCapability.SEARCH,
                IntegrationCapability.METADATA_BASIC,
                IntegrationCapability.METADATA_ARTWORK,
                IntegrationCapability.RATINGS,
                IntegrationCapability.TRACKING,
                IntegrationCapability.USER_LISTS,
            ),
        ),
        IntegrationManifest(
            IntegrationId("anilist"),\n            "AniList",\n            IntegrationCategory.COMPATIBILITY,
            policies(
                IntegrationPolicy.PERMISSION_REQUIRED,
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
        ),
        personalServer("komga", "Komga"),
        personalServer("kavita", "Kavita"),
        personalServer(
            "suwayomi",
            "Suwayomi",
            IntegrationCapability.READING_CONTENT,
            IntegrationCapability.DOWNLOADS,
        ),
    )

    private fun personalServer(
        id: String,
        name: String,
        vararg extra: IntegrationCapability,
    ) = IntegrationManifest(
        IntegrationId(id),
        name,
        IntegrationCategory.PERSONAL_SERVER,
        policies(
            IntegrationPolicy.USER_OWNED_DATA,
            IntegrationCapability.METADATA_BASIC,
            IntegrationCapability.METADATA_ARTWORK,
            IntegrationCapability.REMOTE_LIBRARY,
            IntegrationCapability.TRACKING,
            *extra,
        ),
    )
}
