package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.tsuzuki.MihonReadingSourceGateway
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONException
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** Local-only Mihon HttpSource + gateway fixture for deterministic runtime integration tests. */
internal class LocalMihonSourceHarness(
    readTimeoutMillis: Long = 2_000,
) : Closeable {
    val server = MockWebServer()

    init {
        server.start()
    }

    val source = FixtureHttpSource(
        baseUrl = server.url("/").toString().trimEnd('/'),
        client = OkHttpClient.Builder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build(),
    )

    val sourceManager = FixtureSourceManager(source)

    val mangaRepository: MangaRepository = mockk(relaxed = true)

    val gateway = MihonReadingSourceGateway(
        sourceManager = sourceManager,
        sourcePreferences = SourcePreferences(InMemoryPreferenceStore()),
        networkToLocalManga = NetworkToLocalManga(mangaRepository),
    )

    fun enqueue(status: Int = 200, body: String = "") {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .body(body)
                .build(),
        )
    }

    fun enqueueDelayed(body: String, delayMillis: Long) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(body)
                .bodyDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    override fun close() {
        server.close()
    }
}

internal class FixtureHttpSource(
    override val baseUrl: String,
    override val client: OkHttpClient,
) : HttpSource() {
    override val name: String = "Runtime Integration Fixture"
    override val lang: String = "en"
    override val supportsLatest: Boolean = false
    override fun getFilterList(): FilterList = FilterList()

    @Deprecated("fixture")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/search")

    @Deprecated("fixture")
    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        if (body == "malformed") throw JSONException("fixture response was malformed")
        if (body == "captcha_required") throw IllegalStateException("captcha_required")
        if (body == "extension_error") throw IllegalStateException("private extension failure")
        if (body == "io_error") throw java.io.IOException("private IO failure")
        if (body == "cancel_search") throw CancellationException("fixture cancellation")
        val mangas = body.lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val (url, title) = line.split('\t', limit = 2)
                SManga.create().apply {
                    this.url = url
                    this.title = title
                }
            }
            .toList()
        return MangasPage(mangas, hasNextPage = false)
    }
}

internal class FixtureSourceManager(
    private val source: CatalogueSource,
) : SourceManager {
    override val sources: Flow<List<Source>> = emptyFlow()

    override suspend fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }

    override suspend fun getOrStub(sourceKey: Long): Source =
        get(sourceKey) ?: StubSource(sourceKey, "", "")

    override suspend fun getAll(): List<Source> = listOf(source)

    override suspend fun getOnlineSources() = listOf(source as HttpSource)

    override suspend fun getStubSources(): List<StubSource> = emptyList()
}
