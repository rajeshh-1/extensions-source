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
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
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

    // scrapeClient: followRedirects(false) so we can detect redirects to a different host
    private val scrapeClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .followRedirects(false)
            .build()
    }

    // bundleClient: follows redirects — used as fallback in fetchBundle so that on Brazilian
    // carrier networks where www.mangalivre.net resolves, we can still fetch the JS bundle
    private val bundleClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .followRedirects(true)
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
        val dto = response.parseJson<WrapperDto>()
        val mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ============================== Details =======================================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/manga-by-slug/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseJson<MangaDto>().toSManga(useAlternativeTitle)

    // ============================== Chapters =======================================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseJson<MangaDto>().toSChapterList()

    // ============================== Pages =======================================

    override fun pageListRequest(chapter: SChapter): Request {
        val readerPath = chapter.url.substringBeforeLast("#")
        val dto = chapter.url.substringAfterLast("#").parseAs<ChapterReferenceDto>()
        return GET("$apiUrl/mangas/${dto.mangaId}/chapters/${dto.chapterId}", headers)
            .newBuilder()
            .tag(ReaderPath::class.java, ReaderPath(readerPath))
            .build()
    }

    override fun pageListParse(response: Response): List<Page> = response.parseJson<PageDto>().toPageList()

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

    private inline fun <reified T> Response.parseJson(): T {
        val peek = peekBody(MAX_PEEK).string().trimStart()
        if (peek.isEmpty() || peek.startsWith("<")) {
            close()
            throw IOException(NON_JSON_MESSAGE)
        }
        return parseAs<T>()
    }

    @Volatile private var cachedAuthToken: ClientToken? = null
    @Volatile private var cachedDecoyToken: ClientToken? = null
    @Volatile private var cachedVerifyToken: ClientToken? = null
    @Volatile private var webViewUserAgent: String? = null

    /**
     * The site injects different tokens per endpoint type:
     *   /chapters  → AUTH_TOKEN  (required to read chapter pages)
     *   everything else → DECOY_TOKEN  (browse/search/manga-detail endpoints)
     * Both are encoded in the JS bundle as char-code arrays: $([97,117,...]).
     */
    private fun clientHeaderInterceptor(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.url.host != baseUrlHost) {
            return chain.proceed(originalRequest)
        }

        val isChapter = originalRequest.url.encodedPath.contains("/chapters")
        val token = selectToken(isChapter)
        val response = chain.proceed(originalRequest.withClientHeaders(token, cachedVerifyToken))
        if (!response.requiresTokenRetry(originalRequest)) {
            return response
        }

        response.close()
        for (candidate in scrapeStaticCandidates()) {
            if (candidate == token) continue
            val retry = chain.proceed(originalRequest.withClientHeaders(candidate, cachedVerifyToken))
            if (!retry.requiresTokenRetry(originalRequest)) {
                if (isChapter) cachedAuthToken = candidate else cachedDecoyToken = candidate
                return retry
            }
            retry.close()
        }
        val readerPath = originalRequest.tag(ReaderPath::class.java)
        extractTokensViaWebView(readerPath)?.let { tokens ->
            val webViewToken = tokens.firstOrNull {
                it.header.equals(AUTH_TOKEN.header, ignoreCase = true) &&
                    !it.value.contains("decoy", ignoreCase = true)
            } ?: AUTH_TOKEN
            val verifyToken = tokens.firstOrNull {
                it.header.equals(VERIFY_HEADER, ignoreCase = true)
            }
            val retry = chain.proceed(originalRequest.withClientHeaders(webViewToken, verifyToken))
            if (!retry.requiresTokenRetry(originalRequest)) {
                if (isChapter) cachedAuthToken = webViewToken
                cachedVerifyToken = verifyToken
                return retry
            }
            retry.close()
        }
        val finalResponse = chain.proceed(originalRequest.withClientHeaders(token, cachedVerifyToken))
        // DIAGNOSTIC: when a chapter request still 403s after every retry, surface the response
        // fingerprint in the error message so we can tell an app-token rejection ("aplicativo
        // oficial" JSON) from a Cloudflare block (Server: cloudflare + cf-mitigated header).
        if (isChapter && finalResponse.code == 403) {
            val diag = buildString {
                append("MangaLivre 403 | server=").append(finalResponse.header("Server") ?: "?")
                append(" | cf-mitigated=").append(finalResponse.header("cf-mitigated") ?: "-")
                append(" | ct=").append(finalResponse.header("Content-Type") ?: "?")
                append(" | ua=").append((deviceUserAgent() ?: "?").take(30))
                append(" | body=").append(
                    try {
                        finalResponse.peekBody(220).string().replace(Regex("\\s+"), " ").take(150)
                    } catch (_: Exception) {
                        "?"
                    },
                )
            }
            finalResponse.close()
            throw IOException(diag)
        }
        return finalResponse
    }

    private fun selectToken(isChapter: Boolean): ClientToken =
        if (isChapter) cachedAuthToken ?: AUTH_TOKEN else cachedDecoyToken ?: DECOY_TOKEN

    private fun Request.withClientHeaders(
        token: ClientToken,
        verifyToken: ClientToken?,
    ): Request {
        val builder = newBuilder().header(token.header, token.value)
        verifyToken?.let { builder.header(it.header, it.value) }
        // Align the UA with the device WebView so the shared cf_clearance cookie stays valid.
        deviceUserAgent()?.let { builder.header("User-Agent", it) }
        return builder.build()
    }

    /**
     * The device WebView's User-Agent, fetched once and cached. cf_clearance is bound to this UA,
     * so every authenticated request must carry it or Cloudflare answers 403 despite a valid token.
     */
    private fun deviceUserAgent(): String? = webViewUserAgent ?: synchronized(this) {
        webViewUserAgent ?: try {
            TokenExtractor.getUserAgent()?.also { webViewUserAgent = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun scrapeStaticCandidates(): List<ClientToken> = try {
        val js = fetchBundle()
        val pool = (decodeChunkedAtob(js) + decodeAlphabet(js) + decodeCharCodes(js) + decodeAtob(js) + decodeLiterals(js) + decodeHelperCharCodes(js)).distinct()
        val names = pool.filter { NAME_REGEX.matches(it) && it.lowercase() !in STANDARD_HEADERS }.take(MAX_POOL)
        val values = pool.filter { VALUE_REGEX.matches(it) && it.any(Char::isDigit) }.take(MAX_POOL)
        names
            .flatMap { name -> values.mapNotNull { value -> if (name != value) ClientToken(name, value) else null } }
            .sortedByDescending { score(it.value) }
            .take(MAX_CANDIDATES)
    } catch (_: Exception) {
        emptyList()
    }

    private fun fetchBundle(): String {
        val documentHeaders = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "none")
            .set("Upgrade-Insecure-Requests", "1")
            .build()
        val scriptHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Sec-Fetch-Dest", "script")
            .build()

        // First try without redirect: works when toonlivre.net serves directly (some IPs/paths)
        var html = scrapeClient.newCall(GET("$baseUrl/", documentHeaders)).execute()
            .use { if (it.isSuccessful) it.body.string() else "" }

        // Fallback: follow redirects — on Brazilian carrier networks www.mangalivre.net resolves
        // and following the redirect gives us the real SPA HTML with asset paths
        var assetBaseUrl = baseUrl
        if (html.isEmpty()) {
            bundleClient.newCall(GET("$baseUrl/", documentHeaders)).execute().use { resp ->
                if (resp.isSuccessful) {
                    html = resp.body.string()
                    // Remember where we actually landed so we fetch assets from the right host
                    assetBaseUrl = resp.request.url.run { "$scheme://$host" }
                }
            }
        }

        val assets = ASSET_REGEX.findAll(html).map { it.value }.distinct().toList()
        return buildString {
            assets.take(MAX_ASSETS).forEach { path ->
                // Try direct first (no redirect), then follow-redirect fallback
                var js = scrapeClient.newCall(GET("$baseUrl$path", scriptHeaders)).execute()
                    .use { if (it.isSuccessful) it.body.string() else "" }
                if (js.isEmpty() && assetBaseUrl != baseUrl) {
                    js = bundleClient.newCall(GET("$assetBaseUrl$path", scriptHeaders)).execute()
                        .use { if (it.isSuccessful) it.body.string() else "" }
                }
                if (js.isNotEmpty()) append(js)
            }
        }
    }

    private fun decodeChunkedAtob(js: String): List<String> = CHUNKED_ATOB_REGEX.findAll(js)
        .mapNotNull { match ->
            CHUNK_REGEX.findAll(match.groupValues[1])
                .joinToString("") { it.groupValues[1] }
                .decodeBase64()
                ?.utf8()
        }
        .toList()

    private fun decodeCharCodes(js: String): List<String> = CHARCODE_REGEX.findAll(js)
        .mapNotNull { match ->
            val op = match.groupValues[2]
            val k = match.groupValues[3].toIntOrNull() ?: 0
            val codes = match.groupValues[1].split(",").mapNotNull { it.toIntOrNull() }.map { n ->
                when (op) {
                    "-" -> n - k
                    "+" -> n + k
                    "*" -> n * k
                    "^" -> n xor k
                    else -> n
                }
            }
            if (codes.isNotEmpty() && codes.all { it in 32..126 }) {
                codes.map { it.toChar() }.joinToString("")
            } else {
                null
            }
        }
        .toList()

    private fun decodeAtob(js: String): List<String> = ATOB_REGEX.findAll(js)
        .mapNotNull { it.groupValues[1].decodeBase64()?.utf8() }
        .toList()

    // Mecanismo do site: [indices].map(i => alfabeto[i]).join(""), com o alfabeto num literal.
    private fun decodeAlphabet(js: String): List<String> {
        val alphabets = ALPHABET_REGEX.findAll(js).map { it.groupValues[1] }.filter { '-' in it }.distinct().toList()
        if (alphabets.isEmpty()) return emptyList()
        return INDEX_REGEX.findAll(js).flatMap { match ->
            val indices = match.groupValues[1].split(",").mapNotNull { it.toIntOrNull() }
            alphabets.mapNotNull { alpha ->
                if (indices.isNotEmpty() && indices.all { it < alpha.length }) {
                    indices.map { alpha[it] }.joinToString("")
                } else {
                    null
                }
            }
        }.toList()
    }

    private fun decodeLiterals(js: String): List<String> = LITERAL_REGEX.findAll(js).map { it.groupValues[1] }.toList()

    // Handles $([97,117,...]) helper-function pattern used by toonlivre to encode header values
    private fun decodeHelperCharCodes(js: String): List<String> = HELPER_CHARCODE_REGEX.findAll(js)
        .mapNotNull { match ->
            val codes = match.groupValues[1].split(",").mapNotNull { it.toIntOrNull() }
            if (codes.isNotEmpty() && codes.all { it in 32..126 }) {
                codes.map { it.toChar() }.joinToString("")
            } else {
                null
            }
        }
        .toList()

    private fun score(value: String): Int = (if (value.any { it.isDigit() }) 200 else 0) + (MAX_VALUE_LEN - value.length).coerceAtLeast(0)

    private fun extractTokensViaWebView(readerPath: ReaderPath?): List<ClientToken>? = try {
        val pageUrl = readerPath?.let { "$baseUrl${it.path}" } ?: return null
        TokenExtractor.extract(pageUrl, deviceUserAgent())
            .map { ClientToken(it.header, it.value) }
            .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun Response.isHtmlResponse(): Boolean = try {
        header("Content-Type")?.contains("text/html", ignoreCase = true) == true ||
            peekBody(16).string().trimStart().startsWith("<")
    } catch (_: Exception) {
        false
    }

    /**
     * Returns true when we should discard the current token and try a new one.
     *
     * We handle three distinct failure modes:
     *  1. 403 "aplicativo oficial" — server received the request but rejected the token.
     *  2. 3xx redirect to a different host (scrapeClient with followRedirects=false) — the
     *     Cloudflare Worker is redirecting the request away instead of proxying it.
     *  3. client followed a redirect and we ended up on a different host serving HTML —
     *     the redirect dropped the API path (e.g. sent us to www.mangalivre.net/ root), which
     *     means the Cloudflare Worker didn't recognise the token and sent us to the SPA homepage.
     */
    private fun Response.requiresTokenRetry(originalRequest: Request): Boolean {
        val originalHost = originalRequest.url.host
        // Any 403 triggers recovery: either the app rejected the token ("aplicativo oficial") or
        // Cloudflare rejected the session. Both are healed by the WebView pass, which refreshes
        // cf_clearance in the shared cookie jar and lets the final retry through with the right UA.
        return code == 403 ||
            (isRedirect && request.url.resolve(header("Location").orEmpty())?.host != originalHost) ||
            (request.url.host != originalHost && isHtmlResponse())
    }

    private data class ClientToken(val header: String, val value: String)

    private data class ReaderPath(val path: String)

    companion object {
        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val MAX_PEEK = 1024L
        private const val MAX_ASSETS = 8
        private const val MAX_POOL = 12
        private const val MAX_CANDIDATES = 16
        private const val MAX_VALUE_LEN = 40
        private const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou header desatualizado). Abra a fonte na WebView do app e tente de novo."
        // Current token values extracted from /assets/index-D14EYlfC.js charcode arrays
        private val AUTH_TOKEN = ClientToken("toonlivre-pass", "auth2028xy")
        private val DECOY_TOKEN = ClientToken("toonlivre-pass", "decoy99xz")
        private const val VERIFY_HEADER = "x-toon-verify"
        private val STANDARD_HEADERS = setOf("x-csrf-token", "x-requested-with", "x-toonlivre-authenticated-user")
        private val ASSET_REGEX = Regex("/assets/[\\w-]+\\.js")
        private val NAME_REGEX = Regex("[a-z]{2,}(?:-[a-z]{2,})+")
        private val VALUE_REGEX = Regex("[a-z][a-z0-9]{4,19}")
        private val ALPHABET_REGEX = Regex("\"([a-z0-9][a-z0-9-]{24,45})\"")
        private val INDEX_REGEX = Regex("\\[(\\d{1,2}(?:,\\d{1,2}){3,60})\\]\\.map\\(\\w+=>[\\w\$]{1,3}\\[\\w+\\]\\)")
        private val LITERAL_REGEX = Regex("\"([a-z0-9][a-z0-9-]{3,40})\"")
        private val CHARCODE_REGEX = Regex("\\[([\\d,]{5,240})\\][^\\[]{0,60}?fromCharCode\\([a-z]+(?:([-+*^])(\\d{1,4}))?\\)")
        private val ATOB_REGEX = Regex("atob\\(\"([A-Za-z0-9+/=]{1,80})\"\\)")
        private val CHUNKED_ATOB_REGEX = Regex("atob\\(\\[((?:\"[A-Za-z0-9+/=]+\",?)+)]")
        private val CHUNK_REGEX = Regex("\"([A-Za-z0-9+/=]+)\"")
        // Matches $([97,117,...]) helper pattern used by toonlivre to obfuscate header strings
        private val HELPER_CHARCODE_REGEX = Regex("\\w{1,2}\\(\\[(\\d{2,3}(?:,\\d{2,3}){3,30})\\]\\)")
    }
}
