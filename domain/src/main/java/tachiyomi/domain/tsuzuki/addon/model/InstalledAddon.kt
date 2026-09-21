package tachiyomi.domain.tsuzuki.addon.model

import tachiyomi.domain.tsuzuki.addon.AddonId

data class InstalledAddon(
    val id: AddonId,
    val displayName: String,
    val enabled: Boolean,
    val versionName: String,
    val mihonSourceIds: List<Long>,
    val hasSettings: Boolean,
)
