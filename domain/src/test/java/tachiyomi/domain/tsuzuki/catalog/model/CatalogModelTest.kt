package tachiyomi.domain.tsuzuki.catalog.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class CatalogModelTest {

    @Test
    fun `catalog item preserves provider-specific identity and score`() {
        val score = CatalogScore(
            provider = "kitsu",
            value = 84.5,
            maxValue = 100.0,
            voteCount = 12500,
        )
        val item = CatalogItem(
            provider = "kitsu",
            providerId = "1234",
            title = "Berserk",
            titles = mapOf("en" to "Berserk", "ja_jp" to "ベルセルク"),
            synopsis = "Guts, a former mercenary...",
            coverUrl = "https://kitsu.io/covers/berserk.jpg",
            bannerUrl = "https://kitsu.io/banners/berserk.jpg",
            status = CatalogItemStatus.ONGOING,
            format = CatalogItemFormat.MANGA,
            score = score,
            genres = listOf("Action", "Dark Fantasy"),
            tags = listOf("Demons", "Mercenaries"),
            startDate = "1989-08-25",
            endDate = null,
            chapterCount = null,
            volumeCount = 41,
        )

        item.provider shouldBe "kitsu"
        item.providerId shouldBe "1234"
        item.title shouldBe "Berserk"
        item.score?.value shouldBe 84.5
        item.score?.maxValue shouldBe 100.0
        item.status shouldBe CatalogItemStatus.ONGOING
        item.format shouldBe CatalogItemFormat.MANGA
    }

    @Test
    fun `catalog error taxonomy classifies network, http, and rate limit errors`() {
        val netErr: CatalogError = CatalogError.NetworkError(IllegalStateException("No route to host"))
        val httpErr: CatalogError = CatalogError.HttpError(404, "Not Found")
        val rateErr: CatalogError = CatalogError.RateLimitExceeded(retryAfterSeconds = 60)
        val serErr: CatalogError = CatalogError.SerializationError(IllegalStateException("Malformed JSON"))
        val unavailErr: CatalogError = CatalogError.ProviderUnavailable("Maintenance")
        val notFoundErr: CatalogError = CatalogError.ItemNotFound("1234")

        netErr.shouldBeInstanceOf<CatalogError.NetworkError>()
        httpErr.shouldBeInstanceOf<CatalogError.HttpError>()
        httpErr.statusCode shouldBe 404
        rateErr.shouldBeInstanceOf<CatalogError.RateLimitExceeded>()
        rateErr.retryAfterSeconds shouldBe 60
        serErr.shouldBeInstanceOf<CatalogError.SerializationError>()
        unavailErr.shouldBeInstanceOf<CatalogError.ProviderUnavailable>()
        notFoundErr.shouldBeInstanceOf<CatalogError.ItemNotFound>()
        notFoundErr.providerId shouldBe "1234"
    }

    @Test
    fun `catalog query defaults provide standard pagination and sort`() {
        val query = CatalogQuery(query = "Monster")

        query.query shouldBe "Monster"
        query.sort shouldBe CatalogSort.POPULARITY_DESC
        query.offset shouldBe 0
        query.limit shouldBe 20
    }

    @Test
    fun `discover feed status properties evaluate correctly`() {
        val successPage = Result.success(CatalogPage(emptyList(), false))
        val failurePage = Result.failure<CatalogPage>(CatalogError.ProviderUnavailable("Down"))

        val bothSuccess = DiscoverFeed(successPage, successPage)
        bothSuccess.isDegraded shouldBe false
        bothSuccess.isCompleteFailure shouldBe false

        val trendingFailed = DiscoverFeed(failurePage, successPage)
        trendingFailed.isDegraded shouldBe true
        trendingFailed.isCompleteFailure shouldBe false

        val popularFailed = DiscoverFeed(successPage, failurePage)
        popularFailed.isDegraded shouldBe true
        popularFailed.isCompleteFailure shouldBe false

        val bothFailed = DiscoverFeed(failurePage, failurePage)
        bothFailed.isDegraded shouldBe true
        bothFailed.isCompleteFailure shouldBe true
    }
}
