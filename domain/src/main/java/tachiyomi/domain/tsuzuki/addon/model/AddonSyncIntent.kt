package tachiyomi.domain.tsuzuki.addon.model

data class AddonSyncIntent(
    val desiredPackageIds: Set<String> = emptySet(),
    val enabledPackageIds: Set<String> = emptySet(),
) {
    init {
        require(desiredPackageIds.none(String::isBlank)) {
            "Desired Add-on package IDs must not be blank"
        }
        require(enabledPackageIds.none(String::isBlank)) {
            "Enabled Add-on package IDs must not be blank"
        }
        require(enabledPackageIds.all(desiredPackageIds::contains)) {
            "Enabled Add-on package IDs must also be desired"
        }
    }
}
