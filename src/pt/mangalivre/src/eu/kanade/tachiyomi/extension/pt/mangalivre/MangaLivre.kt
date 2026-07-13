package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.util.Base64
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
import okhttp3.Dns
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
        network.client.newBuilder().build()
    }

    // Cliente com DNS fixo nos IPs do Cloudflare — bypassa redirecionamento DNS corporativo
    private val scrapeClientWithIp: OkHttpClient by lazy {
        network.client.newBuilder()
            .dns { hostname ->
                when {
                    hostname == baseUrlHost -> CF_IPS.map { java.net.InetAddress.getByName(it) }
                    hostname == "mangalivre.net" -> ML_CF_IPS.map { java.net.InetAddress.getByName(it) }
                    else -> okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            }
            .build()
    }

    // Headers de navegação (document mode) para a homepage — evita redirect do Cloudflare
    private val scrapeHomepageHeaders: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .build()

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

    @Volatile private var cachedClientHeader: Pair<String, String>? = null
    @Volatile private var cacheTimestamp: Long = 0L

    private fun clientHeaderInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrlHost) return chain.proceed(request)

        val (headerName, headerValue) = currentClientHeader()
        val response = chain.proceed(
            request.newBuilder().header(headerName, headerValue).build(),
        )
        if (response.code != 403) return response

        // 403 recebido: invalida cache e tenta com token novo
        response.close()
        val (newName, newValue) = refreshClientHeader()
        return chain.proceed(
            request.newBuilder().header(newName, newValue).build(),
        )
    }

    // Melhoria 3: valida TTL do cache — re-scrapa se token tiver mais de 30 min
    private fun currentClientHeader(): Pair<String, String> {
        val cached = cachedClientHeader
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < TOKEN_TTL_MS) return cached
        return synchronized(this) {
            val c2 = cachedClientHeader
            if (c2 != null && now - cacheTimestamp < TOKEN_TTL_MS) return c2
            scrapeClientHeader().also {
                cachedClientHeader = it
                cacheTimestamp = System.currentTimeMillis()
            }
        }
    }

    private fun refreshClientHeader(): Pair<String, String> = synchronized(this) {
        scrapeClientHeader().also {
            cachedClientHeader = it
            cacheTimestamp = System.currentTimeMillis()
        }
    }

    // Melhoria 1: multiplos fallbacks para buscar a homepage
    private fun fetchHomepage(): String? {
        // Tentativa nos dois dominios — toonlivre.net pode redirecionar, tenta tambem mangalivre.net
        val candidateUrls = listOf(
            "$baseUrl/",
            "$baseUrl/index.html",
            "https://mangalivre.net/",
            "https://mangalivre.net/index.html",
        )
        for (url in candidateUrls) {
            runCatching {
                scrapeClient.newCall(GET(url, scrapeHomepageHeaders)).execute()
                    .use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.string()
                                ?.takeIf { it.contains("/assets/index-") }
                                ?.let { return it }
                        }
                    }
            }
        }

        // Tentativa com DNS fixo nos IPs do Cloudflare (bypassa redirect DNS corporativo)
        for (url in candidateUrls) {
            runCatching {
                scrapeClientWithIp.newCall(GET(url, scrapeHomepageHeaders)).execute()
                    .use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.string()
                                ?.takeIf { it.contains("/assets/index-") }
                                ?.let { return it }
                        }
                    }
            }
        }
        return null
    }

    private fun scrapeClientHeader(): Pair<String, String> {
        return try {
            val html = fetchHomepage() ?: return DEFAULT_HEADER
            val assetPath = ASSET_REGEX.find(html)?.value ?: return DEFAULT_HEADER
            // Determina o dominio de origem do asset baseado na URL da homepage encontrada
            // Sempre usa baseUrl — o redirect para www.mangalivre.net é seguido automaticamente
            val js = scrapeClient.newCall(GET("$baseUrl$assetPath", headers)).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
                ?: return DEFAULT_HEADER
            val candidate = extractClientHeader(js) ?: return DEFAULT_HEADER
            // Valida o token antes de cachealo — detecta token errado imediatamente
            validateHeader(candidate)
        } catch (_: Exception) {
            DEFAULT_HEADER
        }
    }

    // Melhoria 2: decodifica multiplos niveis de base64 (atob(atob("...")))
    private fun decodeAtob(encoded: String, maxDepth: Int = 5): String {
        var current = encoded
        repeat(maxDepth) {
            val decoded = runCatching {
                Base64.decode(current.trim(), Base64.DEFAULT).toString(Charsets.UTF_8)
            }.getOrElse { return current }
            // Para quando nao mudou ou contem bytes nao-ASCII (nao é base64 valido)
            if (decoded == current || decoded.any { it.code > 126 || it.code < 32 }) return current
            current = decoded
        }
        return current
    }

    // ============================== Decoders ======================================

    // Charcode: [120,45,...].map(X=>String.fromCharCode(X)).join("")
    private fun decodeCharCodeArray(numbers: String): String = runCatching {
        numbers.split(",").map { it.trim().toInt().toChar() }.joinToString("")
    }.getOrDefault("")

    private fun extractCharCodeStrings(js: String): List<String> =
        CHARCODE_REGEX.findAll(js)
            .map { decodeCharCodeArray(it.groupValues[1]) }
            .filter(String::isNotBlank)
            .toList()

    // Hex: "\x78\x2d\x74\x6c\x79" -> "x-tly"
    private fun decodeHexEscapes(s: String): String =
        HEX_ESCAPE_REGEX.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    // Unicode: "x-..." -> "x-tly"
    private fun decodeUnicodeEscapes(s: String): String =
        UNICODE_ESCAPE_REGEX.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    // XOR: [25^121, 30^91, ...] -> chars
    private fun decodeXorArray(js: String): List<String> =
        XOR_ARRAY_REGEX.findAll(js).mapNotNull { m ->
            runCatching {
                m.groupValues[1].split(",").map { pair ->
                    val (a, b) = pair.trim().split("^").map { it.trim().toInt() }
                    (a xor b).toChar()
                }.joinToString("")
            }.getOrNull()
        }.filter(String::isNotBlank).toList()

    // Reversed string: "suxen-ylt-x".split("").reverse().join("") -> "x-tly-nexus"
    private fun extractReversedStrings(js: String): List<String> =
        REVERSED_STR_REGEX.findAll(js).map { m ->
            decodeHexEscapes(decodeUnicodeEscapes(m.groupValues[1])).reversed()
        }.filter(String::isNotBlank).toList()

    // String concat: "x-tly" + "-" + "nexus" -> "x-tly-nexus"
    private fun extractConcatStrings(js: String): List<String> =
        CONCAT_STR_REGEX.findAll(js).mapNotNull { m ->
            val full = decodeHexEscapes(decodeUnicodeEscapes(m.value))
            STR_PART_REGEX.findAll(full).map { it.groupValues[1] }.joinToString("").takeIf(String::isNotBlank)
        }.toList()

    // Extrai todas as strings candidatas do JS usando todos os decoders
    private fun extractAllCandidateStrings(js: String): List<String> = buildList {
        // Contexto relevante: regiao ao redor do x-csrf-token (onde fica o auth interceptor)
        val ctx = run {
            val idx = js.indexOf("x-csrf-token").takeIf { it >= 0 } ?: return@run js
            js.substring(maxOf(0, idx - 2000), minOf(js.length, idx + 2000))
        }
        // Charcode arrays
        addAll(extractCharCodeStrings(ctx))
        // XOR arrays
        addAll(decodeXorArray(ctx))
        // Reversed strings
        addAll(extractReversedStrings(ctx))
        // Concat strings
        addAll(extractConcatStrings(ctx))
        // Hex/unicode encoded literals no contexto
        PLAIN_STR_REGEX.findAll(ctx).forEach { m ->
            val decoded = decodeHexEscapes(decodeUnicodeEscapes(m.groupValues[1]))
            if (decoded.isNotBlank()) add(decoded)
        }
    }.distinct()

    private fun extractClientHeader(js: String): Pair<String, String>? {
        // Estrategia 1: charcode arrays (formato atual)
        // Padrao: [append], [header-name], [header-value] — consecutivos
        val charcodeStrings = extractCharCodeStrings(js)
        val appendIdx = charcodeStrings.indexOfFirst { it == "append" }
        if (appendIdx >= 0 && appendIdx + 2 < charcodeStrings.size) {
            val name  = charcodeStrings[appendIdx + 1]
            val value = charcodeStrings[appendIdx + 2]
            if (name.isNotBlank() && value.isNotBlank()) return name to value
        }

        // Estrategia 2: atob (base64, incluindo multinivel)
        ATOB_REGEX.find(js)?.let { m ->
            runCatching {
                val name  = decodeAtob(m.groupValues[1])
                val value = decodeAtob(m.groupValues[2])
                if (name.isNotBlank() && value.isNotBlank()) return name to value
            }
        }

        // Estrategia 3: strings literais diretas (.append ou .set)
        DYNAMIC_REGEX.find(js)?.let { return it.groupValues[1] to it.groupValues[2] }
        DYNAMIC_REGEX_SET.find(js)?.let { return it.groupValues[1] to it.groupValues[2] }

        // Estrategia 4: busca ampla — extrai todos os candidatos via todos os decoders
        // e tenta encontrar par (header-name, header-value) valido
        val candidates = extractAllCandidateStrings(js)
        val headerNameIdx = candidates.indexOfFirst { HEADER_NAME_PATTERN.matches(it) }
        if (headerNameIdx >= 0) {
            // Pega o proximo candidato que parece um token
            val value = candidates.drop(headerNameIdx + 1).firstOrNull { HEADER_VALUE_PATTERN.matches(it) }
            if (value != null) return candidates[headerNameIdx] to value
        }

        // Ultimo recurso: shape regex historico
        val value = SHAPE_REGEX.findAll(js).map { it.groupValues[1] }.firstOrNull() ?: return null
        return FALLBACK_HEADER to value
    }

    // Valida o token extraido fazendo uma requisicao teste leve
    private fun validateHeader(candidate: Pair<String, String>): Pair<String, String> {
        return try {
            val (name, value) = candidate
            val testHeaders = headers.newBuilder().set(name, value).build()
            val resp = scrapeClient.newCall(GET("$apiUrl/mangas/search?page=1&limit=1", testHeaders)).execute()
            val code = resp.code
            resp.close()
            if (code != 403) candidate else DEFAULT_HEADER
        } catch (_: Exception) {
            candidate
        }
    }

    companion object {
        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val FALLBACK_HEADER = "x-tly-nexus"
        private const val DEFAULT_CLIENT = "c77-block-q"
        private const val TOKEN_TTL_MS = 30 * 60 * 1000L // 30 minutos
        private val DEFAULT_HEADER = FALLBACK_HEADER to DEFAULT_CLIENT
        // IPs fixos do Cloudflare para toonlivre.net — usados como fallback de DNS
        private val CF_IPS = listOf("104.21.35.220", "172.67.180.59")
        // IPs do Cloudflare para mangalivre.net (dominio alternativo de fallback para scraping)
        private val ML_CF_IPS = listOf("104.21.1.168", "172.67.129.165")
        private val ASSET_REGEX = Regex("/assets/index-[\\w-]+\\.js")

        // --- Decoders para cada tecnica de ofuscacao ---

        // Charcode: [120,45,...].map(G=>String.fromCharCode(G)).join("")
        private val CHARCODE_REGEX = Regex("""\[(\d+(?:,\d+)+)\]\.map\(\w+=>\w+\.fromCharCode\(\w+\)\)\.join\(""\)""")
        // atob: S.append(atob("BASE64"), atob("BASE64"))
        private val ATOB_REGEX = Regex("""\.append\(atob\("([A-Za-z0-9+/=]+)"\),\s*atob\("([A-Za-z0-9+/=]+)"\)\)""")
        // String literal: .append("header","value") ou .set("header","value")
        private val DYNAMIC_REGEX = Regex("\\.append\\(\"([\\w-]+)\",\"([^\"]+)\"\\)")
        private val DYNAMIC_REGEX_SET = Regex("\\.set\\(\"([\\w-]+)\",\"([^\"]+)\"\\)")
        // Hex escapes: "\x78\x2d\x74\x6c\x79"
        private val HEX_ESCAPE_REGEX = Regex("\\\\x([0-9a-fA-F]{2})")
        // Unicode escapes: "x-..."
        private val UNICODE_ESCAPE_REGEX = Regex("\\\\u([0-9a-fA-F]{4})")
        // XOR: [25^121, 30^91, ...]
        private val XOR_ARRAY_REGEX = Regex("""\[(\d+\^\d+(?:,\s*\d+\^\d+)+)\]""")
        // String reversed: "esrever".split("").reverse().join("")
        private val REVERSED_STR_REGEX = Regex(""""([^"]{3,30})"\.split\(""\)\.reverse\(\)\.join\(""\)""")
        // String concat: "x-tly" + "-" + "nexus"
        private val CONCAT_STR_REGEX = Regex(""""[^"]{2,15}"(?:\s*\+\s*"[^"]{1,15}"){1,5}""")
        private val STR_PART_REGEX = Regex(""""([^"]+)"""")
        // Strings literais no contexto (hex/unicode encoded)
        private val PLAIN_STR_REGEX = Regex(""""((?:\\x[0-9a-fA-F]{2}|\\u[0-9a-fA-F]{4}|[^"\\]){3,30})"""")
        // Padrao de header name e header value validos
        private val HEADER_NAME_PATTERN = Regex("[a-zA-Z][\\w-]{2,25}")
        private val HEADER_VALUE_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{2,30}")
        // Shape historico como ultimo recurso
        private val SHAPE_REGEX = Regex("\"([a-z][0-9]+-web-[a-z0-9-]+)\"")
    }
}
