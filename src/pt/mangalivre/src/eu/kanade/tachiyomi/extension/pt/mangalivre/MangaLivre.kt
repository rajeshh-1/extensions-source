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
import keiyoushi.utils.toJsonString
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
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
        .addNetworkInterceptor(::checkSourceRedirect)
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

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBeforeLast("#")}"

    override fun pageListRequest(chapter: SChapter): Request {
        val readerPath = chapter.url.substringBeforeLast("#")
        val dto = chapter.url.substringAfterLast("#").parseAs<ChapterReferenceDto>()
        return GET("$apiUrl/mangas/${dto.mangaId}/chapters/${dto.chapterId}", headers)
            .newBuilder()
            .tag(ReaderPath::class.java, ReaderPath(readerPath))
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        return try {
            val dataKey = response.header(DATA_KEY_HEADER)
            if (dataKey.isNullOrBlank()) {
                return response.parseJson<PageDto>().toPageList()
            }

            val encrypted = response.parseAs<Map<String, String>>()[dataKey]
                ?: throw IOException("Envelope criptografado inválido.")
            Rabbit.decrypt(encrypted, currentRabbitPassphrase())
                .parseAs<PageDto>()
                .toPageList()
        } catch (apiError: Exception) {
            extractPagesViaWebView(
                readerPath = response.request.tag(ReaderPath::class.java),
            )
                .takeIf { it.isNotEmpty() }
                ?.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
                ?: throw IOException("MangaLivre: API alterada e fallback WebView sem páginas.", apiError)
        }
    }

    private fun currentRabbitPassphrase(): String {
        val utcDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val input = "$utcDate$RABBIT_SITE_SALT$RABBIT_BUILD_SALT"
        val suffix = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(8)
        return "$RABBIT_PASSPHRASE_PREFIX$suffix"
    }

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
    @Volatile private var cachedRouteToken: CachedRouteToken? = null
    @Volatile private var cachedSeedToken: CachedSeedToken? = null
    @Volatile private var cachedInteractionCookies: InteractionCookies? = null
    private val routeTokenLock = Any()
    private val seedTokenLock = Any()
    private val interactionCookieLock = Any()
    private val readerPagesLock = Any()
    private val readerPagesCache = mutableMapOf<ChapterRouteReference, CachedReaderPages>()

    private fun checkSourceRedirect(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.url.host != baseUrlHost || !response.isRedirect) return response

        val destination = response.header("Location")?.let(request.url::resolve)
        if (destination != null && destination.host in LANDING_PAGE_HOSTS && destination.encodedPath == "/") {
            response.close()
            throw IOException("O ToonLivre está redirecionando para a página inicial do Manga Livre, sem catálogo ou capítulos.")
        }
        return response
    }

    /**
     * The current reader obtains a short-lived grant through Cloudflare Turnstile inside the site.
     * Let the WebView complete that flow and return the rendered page URLs as a synthetic response.
     */
    private fun clientHeaderInterceptor(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.url.host != baseUrlHost) {
            return chain.proceed(originalRequest)
        }

        val route = originalRequest.chapterRouteReference()
            ?: return chain.proceed(originalRequest)
        val now = System.currentTimeMillis()
        val cachedPages = synchronized(readerPagesLock) {
            readerPagesCache[route]
                ?.takeIf { now < it.expiresAt }
                ?.pages
        }
        val readerPath = originalRequest.tag(ReaderPath::class.java)
        val pages = cachedPages ?: run {
            if (readerPath == null) return chain.proceed(originalRequest)

            // Detect a redirect away from the reader before opening a WebView.
            chain.proceed(GET("$baseUrl${readerPath.path}", headers)).close()
            extractPagesViaWebView(readerPath)
        }.also { extracted ->
            if (extracted.isNotEmpty()) {
                synchronized(readerPagesLock) {
                    readerPagesCache[route] = CachedReaderPages(
                        pages = extracted,
                        expiresAt = System.currentTimeMillis() + READER_PAGES_CACHE_MS,
                    )
                }
            }
        }
        if (pages.isEmpty()) {
            throw IOException(
                "MangaLivre: a verificação Turnstile do leitor não foi concluída. " +
                    "Abra a fonte na WebView, aguarde as páginas aparecerem e tente novamente.",
            )
        }

        return Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK (Turnstile WebView)")
            .body(PageDto(pages).toJsonString().toResponseBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun Request.chapterRouteReference(): ChapterRouteReference? {
        val segments = url.pathSegments
        if (
            segments.size != 5 ||
            segments[0] != "api" ||
            segments[1] != "mangas" ||
            segments[3] != "chapters"
        ) {
            return null
        }
        return ChapterRouteReference(segments[2], segments[4])
    }

    private fun routeAuthorization(
        route: ChapterRouteReference,
        forceRefresh: Boolean = false,
    ): RouteAuthorization? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedRouteToken?.takeIf { it.matches(route, now) }?.let { return it.authorization }
        }

        return synchronized(routeTokenLock) {
            val lockedNow = System.currentTimeMillis()
            if (!forceRefresh) {
                cachedRouteToken?.takeIf { it.matches(route, lockedNow) }?.let {
                    return@synchronized it.authorization
                }
            }

            val interactionCookies = interactionCookies(forceRefresh)
            val signature = seedToken(forceRefresh) ?: return@synchronized null
            try {
                val tokenUrl = "$apiUrl/chapter-token/${route.mangaId}/${route.chapterId}"
                val tokenHeaders = headers.newBuilder().apply {
                    deviceUserAgent()?.let { set("User-Agent", it) }
                    set(SIGNATURE_HEADER, signature)
                    set("Cookie", interactionCookies.headerValue)
                }.build()
                scrapeClient.newCall(GET(tokenUrl, tokenHeaders)).execute().use { tokenResponse ->
                    if (!tokenResponse.isSuccessful) return@use null
                    val dto = tokenResponse.parseAs<RouteTokenDto>()
                    CachedRouteToken(
                        route = route,
                        value = dto.token,
                        signature = signature,
                        interactionCookies = interactionCookies,
                        expiresAt = lockedNow + dto.expiresMs - ROUTE_TOKEN_SAFETY_MS,
                    ).also { cachedRouteToken = it }.authorization
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Current ToonLivre builds require both interaction cookies while deployments may overlap:
     * toon_i is the deterministic graphic proof and toon_v is the newer visitor interaction flag.
     */
    private fun interactionCookies(forceRefresh: Boolean): InteractionCookies {
        if (!forceRefresh) cachedInteractionCookies?.let { return it }
        return synchronized(interactionCookieLock) {
            if (!forceRefresh) {
                cachedInteractionCookies?.let { return@synchronized it }
            }
            val now = System.currentTimeMillis()
            val values = InteractionCookies(
                proof = ToonInteractionProof.cookie(now),
                visitor = buildString(VISITOR_COOKIE_LENGTH) {
                    repeat(VISITOR_COOKIE_LENGTH) {
                        append(VISITOR_COOKIE_ALPHABET[Random.nextInt(VISITOR_COOKIE_ALPHABET.length)])
                    }
                },
            )
            val cookies = values.asMap().map { (name, value) ->
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(baseUrlHost)
                    .path("/")
                    .expiresAt(now + INTERACTION_COOKIE_MAX_AGE_MS)
                    .build()
            }
            network.client.cookieJar.saveFromResponse(baseUrl.toHttpUrl(), cookies)
            cachedInteractionCookies = values
            values
        }
    }

    private fun seedToken(forceRefresh: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedSeedToken?.takeIf { now < it.expiresAt }?.let { return it.value }
        }
        return synchronized(seedTokenLock) {
            val lockedNow = System.currentTimeMillis()
            if (!forceRefresh) {
                cachedSeedToken?.takeIf { lockedNow < it.expiresAt }?.let {
                    return@synchronized it.value
                }
            }
            try {
                val seedHeaders = headers.newBuilder().apply {
                    deviceUserAgent()?.let { set("User-Agent", it) }
                    cachedInteractionCookies?.let { set("Cookie", it.headerValue) }
                }.build()
                scrapeClient.newCall(GET("$apiUrl/seed", seedHeaders)).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val dto = response.parseAs<SeedTokenDto>()
                    CachedSeedToken(
                        value = dto.token,
                        expiresAt = lockedNow + SEED_TOKEN_CACHE_MS,
                    ).also { cachedSeedToken = it }.value
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private object ToonInteractionProof {
        private const val SIZE = 80
        private const val POINTS = 140
        private const val SEED_XOR = 858_993_459
        private const val UINT_MASK = 0xffff_ffffL
        private const val FNV_OFFSET_BASIS = 2_166_136_261L
        private const val FNV_PRIME = 16_777_619L

        fun cookie(timestamp: Long): String {
            val proof = proof(timestamp)
            return """{"ts":$timestamp,"pow":"$proof"}""".encodeUtf8().base64()
        }

        internal fun proof(timestamp: Long): String {
            val random = SiteRandom(timestamp)
            val pixels = ByteArray(SIZE * SIZE)
            val points = List(POINTS) {
                Point3d(
                    x = random.nextFloat() * 2 - 1,
                    y = random.nextFloat() * 2 - 1,
                    z = random.nextFloat() * 2 - 1,
                )
            }
            val angleX = random.nextFloat() * Math.PI * 2
            val angleY = random.nextFloat() * Math.PI * 2
            val angleZ = random.nextFloat() * Math.PI * 2

            points.zipWithNext().forEach { (from, to) ->
                val rotatedFrom = rotate(from, angleX, angleY, angleZ)
                val rotatedTo = rotate(to, angleX, angleY, angleZ)
                drawLine(
                    pixels = pixels,
                    fromX = project(rotatedFrom.x, rotatedFrom.z),
                    fromY = project(rotatedFrom.y, rotatedFrom.z),
                    toX = project(rotatedTo.x, rotatedTo.z),
                    toY = project(rotatedTo.y, rotatedTo.z),
                )
            }

            var hash = FNV_OFFSET_BASIS
            pixels.forEach { value ->
                hash = hash xor (value.toInt() and 0xff).toLong()
                hash = (hash * FNV_PRIME) and UINT_MASK
            }
            return hash.toString(16).padStart(8, '0')
        }

        private fun rotate(point: Point3d, angleX: Double, angleY: Double, angleZ: Double): Point3d {
            val cosX = cos(angleX)
            val sinX = sin(angleX)
            val yAfterX = point.y * cosX - point.z * sinX
            val zAfterX = point.y * sinX + point.z * cosX
            val cosY = cos(angleY)
            val sinY = sin(angleY)
            val xAfterY = point.x * cosY + zAfterX * sinY
            val zAfterY = -point.x * sinY + zAfterX * cosY
            val cosZ = cos(angleZ)
            val sinZ = sin(angleZ)
            return Point3d(
                x = xAfterY * cosZ - yAfterX * sinZ,
                y = xAfterY * sinZ + yAfterX * cosZ,
                z = zAfterY,
            )
        }

        private fun project(value: Double, depth: Double): Int =
            floor((value / (depth + 2.6) + 1) * (SIZE / 2.2)).toInt()

        private fun drawLine(
            pixels: ByteArray,
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
        ) {
            var x = fromX
            var y = fromY
            val deltaX = abs(toX - x)
            val deltaY = -abs(toY - y)
            val stepX = if (x < toX) 1 else -1
            val stepY = if (y < toY) 1 else -1
            var error = deltaX + deltaY

            while (true) {
                if (x in 0 until SIZE && y in 0 until SIZE) {
                    pixels[y * SIZE + x] = 200.toByte()
                }
                if (x == toX && y == toY) break
                val doubledError = 2 * error
                if (doubledError >= deltaY) {
                    error += deltaY
                    x += stepX
                }
                if (doubledError <= deltaX) {
                    error += deltaX
                    y += stepY
                }
            }
        }

        private class SiteRandom(timestamp: Long) {
            private var state = ((timestamp.toInt() xor SEED_XOR).toLong()) and UINT_MASK

            fun nextFloat(): Double {
                state = (state * 1_664_525L + 1_013_904_223L) and UINT_MASK
                return state.toDouble() / 4_294_967_296.0
            }
        }

        private data class Point3d(val x: Double, val y: Double, val z: Double)
    }

    private fun invalidateRouteToken(route: ChapterRouteReference) {
        synchronized(routeTokenLock) {
            if (cachedRouteToken?.route == route) cachedRouteToken = null
        }
    }

    private fun invalidateSeedToken() {
        synchronized(seedTokenLock) {
            cachedSeedToken = null
        }
    }

    private fun Request.withRouteAuthorization(authorization: RouteAuthorization): Request = newBuilder()
        .header(SIGNATURE_HEADER, authorization.signature)
        .header(ROUTE_TOKEN_HEADER, authorization.routeToken)
        .apply {
            deviceUserAgent()?.let { header("User-Agent", it) }
            header("Cookie", authorization.interactionCookies.headerValue)
        }
        .build()

    private data class ChapterRouteReference(
        val mangaId: String,
        val chapterId: String,
    )

    private data class CachedReaderPages(
        val pages: List<String>,
        val expiresAt: Long,
    )

    private data class CachedRouteToken(
        val route: ChapterRouteReference,
        val value: String,
        val signature: String,
        val interactionCookies: InteractionCookies,
        val expiresAt: Long,
    ) {
        val authorization get() = RouteAuthorization(value, signature, interactionCookies)

        fun matches(other: ChapterRouteReference, now: Long): Boolean =
            route == other && now < expiresAt
    }

    private data class RouteAuthorization(
        val routeToken: String,
        val signature: String,
        val interactionCookies: InteractionCookies,
    )

    private data class CachedSeedToken(
        val value: String,
        val expiresAt: Long,
    )

    private data class InteractionCookies(
        val proof: String,
        val visitor: String,
    ) {
        val headerValue get() = "$PROOF_COOKIE=$proof; $VISITOR_COOKIE=$visitor"

        fun asMap(): Map<String, String> = mapOf(
            PROOF_COOKIE to proof,
            VISITOR_COOKIE to visitor,
        )
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

    private fun extractPagesViaWebView(
        readerPath: ReaderPath?,
    ): List<String> {
        val pageUrl = readerPath?.let { "$baseUrl${it.path}" } ?: return emptyList()
        return try {
            TokenExtractor.fetchChapterPages(
                siteUrl = pageUrl,
            )
        } catch (_: Exception) {
            emptyList()
        }
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
        private val LANDING_PAGE_HOSTS = setOf("mangalivre.net", "www.mangalivre.net")
        private const val MAX_PEEK = 1024L
        private const val DATA_KEY_HEADER = "x-toon-datakey"
        private const val RABBIT_PASSPHRASE_PREFIX = "Dealer-Critter-Catnip4"
        private const val RABBIT_SITE_SALT = "toonlivre.tv::v8"
        private const val RABBIT_BUILD_SALT = "t17_4v19_b2"
        private const val SIGNATURE_HEADER = "x-toon-signature"
        private const val ROUTE_TOKEN_HEADER = "x-toon-route-token"
        private const val ROUTE_TOKEN_SAFETY_MS = 5_000L
        private const val READER_PAGES_CACHE_MS = 5L * 60 * 1_000
        private const val SEED_TOKEN_CACHE_MS = 25L * 60 * 1_000
        private const val PROOF_COOKIE = "toon_i"
        private const val VISITOR_COOKIE = "toon_v"
        private const val VISITOR_COOKIE_LENGTH = 26
        private const val VISITOR_COOKIE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val INTERACTION_COOKIE_MAX_AGE_MS = 365L * 24 * 60 * 60 * 1_000
        private val ROUTE_GATE_CODES = setOf(403, 404)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ASSETS = 8
        private const val MAX_POOL = 12
        private const val MAX_CANDIDATES = 16
        private const val MAX_VALUE_LEN = 40
        private const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou header desatualizado). Abra a fonte na WebView do app e tente de novo."
        // Current fallback values; WebView capture supersedes them when the site rotates again.
        private val AUTH_TOKEN = ClientToken("x-toon-signature", "t8v_authX9")
        private val DECOY_TOKEN = ClientToken("x-toon-signature", "t8v_decoy9")
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
