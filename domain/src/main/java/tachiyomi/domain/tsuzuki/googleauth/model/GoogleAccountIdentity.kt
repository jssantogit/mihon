package tachiyomi.domain.tsuzuki.googleauth.model

data class GoogleAccountIdentity(
    val accountName: String,
) {
    init {
        require(accountName.isNotBlank()) { "Google account name must not be blank" }
    }
}
