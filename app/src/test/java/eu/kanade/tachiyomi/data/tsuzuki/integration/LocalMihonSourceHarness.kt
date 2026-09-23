package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.tsuzuki.MihonReadingSourceGateway
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.io.IOException

/** Local-only Mihon HttpSource + gateway fixture for deterministic runtime integration tests. */
internal class LocalMihonSourceHarness(
    clientFailure: IOException? = null,
    languages: List<String> = listOf("en"),
) : Closeable {
    private val serversByLanguage = languages.distinct().associateWith { MockWebServer().apply { start() } }
    val server: MockWebServer = serversByLanguage.values.first()
    val sources = serversByLanguage.map { (language, sourceServer) ->
        FixtureHttpSource(
            baseUrl = sourceServer.url("/").toString().trimEnd('/'),
            client = OkHttpClient.Builder()
                .apply {
                    clientFailure?.let { failure ->
                        addInterceptor { throw failure }
                    }
                }
                .build(),
            language = language,
        )
    }
    val source: FixtureHttpSource = sources.first()
    val sourceManager = FixtureSourceManager(sources)

    val mangaRepository: MangaRepository = mockk(relaxed = true)

    val gateway = MihonReadingSourceGateway(
        sourceManager = sourceManager,
        sourcePreferences = SourcePreferences(InMemoryPreferenceStore()),
        networkToLocalManga = NetworkToLocalManga(mangaRepository),
    )

    fun enqueue(status: Int = 200, body: String = "", language: String = source.lang) {
        requireNotNull(serversByLanguage[language]) { "No fixture server for language $language" }.enqueue(
            MockResponse.Builder()
                .code(status)
                .body(body)
                .build(),
        )
    }

    override fun close() {
        serversByLanguage.values.forEach(MockWebServer::close)
    }
}

internal class FixtureHttpSource(
    override val baseUrl: String,
    override val client: OkHttpClient,
    override val lang: String = "en",
) : HttpSource() {
    override val name: String = "Runtime Integration Fixture"
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

    @Deprecated("fixture")
    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}")

    @Deprecated("fixture")
    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        if (body == "malformed") throw JSONException("fixture response was malformed")
        if (body == "extension_error") throw IllegalStateException("private extension failure")
        if (body == "cancel_inventory") throw CancellationException("fixture cancellation")
        return body.lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val (url, name, number, scanlator) = line.split('\t', limit = 4)
                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    chapter_number = number.toFloat()
                    this.scanlator = scanlator
                }
            }
            .toList()
    }
}

internal class FixtureSourceManager(
    private val sourceList: List<CatalogueSource>,
) : SourceManager {
    override val sources: Flow<List<Source>> = emptyFlow()

    override suspend fun get(sourceKey: Long): Source? = sourceList.firstOrNull { it.id == sourceKey }

    override suspend fun getOrStub(sourceKey: Long): Source =
        get(sourceKey) ?: StubSource(sourceKey, "", "")

    override suspend fun getAll(): List<Source> = sourceList

    override suspend fun getOnlineSources() = sourceList.map { it as HttpSource }

    override suspend fun getStubSources(): List<StubSource> = emptyList()
}
