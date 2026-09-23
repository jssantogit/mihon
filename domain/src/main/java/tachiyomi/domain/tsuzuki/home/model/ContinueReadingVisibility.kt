package tachiyomi.domain.tsuzuki.home.model

data class ContinueReadingVisibility(
    val canonicalTitleId: String,
    val hiddenAt: Long?,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        require(hiddenAt == null || hiddenAt >= 0) {
            "Continue Reading hidden time must not be negative"
        }
    }
}
