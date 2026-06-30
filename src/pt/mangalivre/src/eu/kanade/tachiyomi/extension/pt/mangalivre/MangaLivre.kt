package eu.kanade.tachiyomi.extension.pt.mangalivre

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

class MangaLivre :
    HttpSource(),
    ConfigurableSource {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val name: String = "Manga Livre"

    override val baseUrl: String = "https://toonlivre.net"

    override val lang: String = "pt-BR"

    override val supportsLatest: Boolean = true

    override val versionId: Int = 2

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::clientHeaderInterceptor)
        .rateLimit(2, 1.seconds) { it.host == baseUrlHost }
        .build()

    private val scrapeClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .build()
    }

    private val apiUrl: String = "$baseUrl/api"

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "*/*")
        .add("Accept-Language", "pt-BR,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")

    // ============================== Popular =======================================

    private val popularFilter = FilterList(
        listOf(
            OrderByFilter(options = listOf("" to "popular")),
            OrderDirectionFilter(options = listOf("" to "desc")),
        ),
    )

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", popularFilter)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest =======================================

    private val latestFilter = FilterList(
        listOf(
            OrderByFilter(options = listOf("" to "updated")),
            OrderDirectionFilter(options = listOf("" to "desc")),
        ),
    )

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", latestFilter)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Search =======================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/mangas/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> {
                    url.addQueryParameter("sortBy", filter.selected())
                }
                is OrderDirectionFilter -> {
                    url.addQueryParameter("sortOrder", filter.selected())
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<WrapperDto>()
        val mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ============================== Details =======================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/manga-by-slug/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<MangaDto>().toSManga(useAlternativeTitle)

    // ============================== Chapters =======================================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<MangaDto>().toSChapterList()

    // ============================== Pages =======================================

    override fun pageListRequest(chapter: SChapter): Request {
        val dto = chapter.url.substringAfterLast("#").parseAs<ChapterReferenceDto>()
        return GET("$apiUrl/mangas/${dto.mangaId}/chapters/${dto.chapterId}", headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<PageDto>().toPageList()

    override fun imageUrlParse(response: Response): String = ""

    // ============================== Filters =======================================

    override fun getFilterList(): FilterList = FilterList(
        listOf(
            OrderByFilter(
                "Ordem",
                listOf(
                    "Mais Visualizados" to "popular",
                    "Lançamentos" to "release",
                    "Última Atualização" to "updated",
                    "Melhor Avaliação" to "rating",
                    "A-Z" to "title",
                ),
            ),
            Filter.Separator(),
            OrderDirectionFilter(
                "Direção",
                listOf(
                    "↑ Decrescente" to "desc",
                    "↓ Crescente" to "asc",
                ),
            ),
        ),
    )

    val useAlternativeTitle: Boolean get() =
        preferences.getBoolean(ALTERNATIVE_TITLE_PREF, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_TITLE_PREF
            title = "Titulo alternativo"
            summary = buildString {
                append("Use titulos alternativos como principal quando disponivel.")
                append(" Essa opção não tem efeito sobre obras já adicionadas na sua bibilioteca")
            }
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Helper =======================================

    @Volatile
    private var cachedClientHeader: Pair<String, String>? = null

    private fun clientHeaderInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) {
            return chain.proceed(request)
        }

        val (headerName, headerValue) = currentClientHeader()
        val response = chain.proceed(
            request.newBuilder().header(headerName, headerValue).build(),
        )
        if (response.code != 403) {
            return response
        }

        response.close()
        val (newName, newValue) = refreshClientHeader()
        return chain.proceed(
            request.newBuilder().header(newName, newValue).build(),
        )
    }

    private fun currentClientHeader(): Pair<String, String> = cachedClientHeader ?: synchronized(this) {
        cachedClientHeader ?: scrapeClientHeader().also { cachedClientHeader = it }
    }

    private fun refreshClientHeader(): Pair<String, String> = synchronized(this) {
        scrapeClientHeader().also { cachedClientHeader = it }
    }

    private fun scrapeClientHeader(): Pair<String, String> {
        return try {
            val html = listOf("$baseUrl/", "$baseUrl/index.html").firstNotNullOfOrNull { url ->
                scrapeClient.newCall(GET(url, headers)).execute()
                    .use { if (it.isSuccessful) it.body?.string()?.takeIf { s -> s.isNotBlank() } else null }
            } ?: return DEFAULT_HEADER
            val assetPath = ASSET_REGEX.find(html)?.value ?: return DEFAULT_HEADER
            val js = scrapeClient.newCall(GET("$baseUrl$assetPath", headers)).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
                ?: return DEFAULT_HEADER
            extractClientHeader(js) ?: DEFAULT_HEADER
        } catch (_: Exception) {
            DEFAULT_HEADER
        }
    }

    private fun extractClientHeader(js: String): Pair<String, String>? {
        // Captura .append("x-tly-token","v99-web-z") ou qualquer variante futura
        DYNAMIC_REGEX.find(js)?.let { return it.groupValues[1] to it.groupValues[2] }
        // Fallback .set("x-qualquer","web-xyz") - formato anterior do site
        DYNAMIC_REGEX_SET.find(js)?.let { return it.groupValues[1] to it.groupValues[2] }
        // Ultimo recurso: extrai so o valor e usa o header fallback conhecido
        val value = SHAPE_REGEX.findAll(js).map { it.groupValues[1] }.firstOrNull()
            ?: return null
        return FALLBACK_HEADER to value
    }

    companion object {
        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val FALLBACK_HEADER = "x-tly-token"
        private const val DEFAULT_CLIENT = "v99-web-z"
        private val DEFAULT_HEADER = FALLBACK_HEADER to DEFAULT_CLIENT
        private val ASSET_REGEX = Regex("/assets/index-[\\w-]+\\.js")
        // Formato atual: S.append("x-tly-token","v99-web-z") — valor pode ser vNN-web-ZZZ
        private val DYNAMIC_REGEX = Regex("\\.append\\(\"(x-[\\w-]+)\",\"([^\"]+)\"\\)")
        // Formato anterior: .set("x-app-key","web-xyz")
        private val DYNAMIC_REGEX_SET = Regex("\\.set\\(\"(x-[\\w-]+)\",\"([^\"]+)\"\\)")
        // Extrai qualquer token no formato web-xyz ou vNN-web-xyz
        private val SHAPE_REGEX = Regex("\"((?:v\\d+-)?web-[a-z0-9-]+)\"")
    }
}
