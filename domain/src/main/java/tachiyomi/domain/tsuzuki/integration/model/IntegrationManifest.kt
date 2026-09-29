package tachiyomi.domain.tsuzuki.integration.model

enum class IntegrationCapability(val configKey: String) {
    SEARCH("search"),
    DISCOVERY("discovery"),
    METADATA_BASIC("metadata"),
    METADATA_ARTWORK("metadata_artwork"),
    METADATA_EDITORIAL("metadata_editorial"),
    METADATA_STAFF("metadata_staff"),
    RATINGS("ratings"),
    RELATIONS("relations"),
    CROSSWALK("crosswalk"),
    TRACKING("tracking"),
    USER_LISTS("user_lists"),
    REMOTE_LIBRARY("remote_library"),
    READING_CONTENT("reading_content"),
    DOWNLOADS("downloads"),
}

enum class IntegrationPolicy {
    ALLOWED,
    ALLOWED_WITH_ATTRIBUTION,
    USER_OWNED_DATA,
    COMMERCIAL_RESTRICTION,
    PERMISSION_REQUIRED,
    UNVERIFIED,
    ;

    val allowsGlobalResolution: Boolean
        get() = this == ALLOWED || this == ALLOWED_WITH_ATTRIBUTION
}

enum class IntegrationCategory {
    METADATA_SERVICE,
    PERSONAL_SERVER,
    COMPATIBILITY,
}

data class CapabilityPolicy(
    val policy: IntegrationPolicy,
    val attribution: String? = null,
    val notes: String? = null,
)

data class IntegrationManifest(
    val integrationId: tachiyomi.domain.tsuzuki.integration.IntegrationId,
    val displayName: String,
    val category: IntegrationCategory,
    val capabilities: Map<IntegrationCapability, CapabilityPolicy>,
    val legacyTrackerId: Long? = null,
) {
    fun declares(capability: IntegrationCapability): Boolean = capability in capabilities

    fun policyFor(capability: IntegrationCapability): CapabilityPolicy? = capabilities[capability]

    fun allowsGlobalResolution(capability: IntegrationCapability): Boolean =
        policyFor(capability)?.policy?.allowsGlobalResolution == true
}
