package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TokenExtractor {

    private const val TIMEOUT_SECONDS = 45L
    private const val WEBVIEW_WIDTH = 1080
    private const val WEBVIEW_HEIGHT = 1920

    private val STANDARD_HEADER_NAMES = setOf(
        "accept", "accept-language", "accept-encoding", "content-type", "content-length",
        "authorization", "user-agent", "referer", "origin", "cookie", "cache-control",
        "pragma", "connection", "host", "dnt", "x-csrf-token", "x-requested-with",
        "priority", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
        "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "purpose", "range",
    )

    data class Token(val header: String, val value: String)

    private val IMAGE_URL_REGEX = Regex(
        """https://cdn\.toonlivre\.net/obras/[^"' <>()]+?\.(?:avif|gif|jpe?g|png|webp)(?:\?[^"' <>()]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Reads the device WebView's User-Agent string. Cloudflare binds the cf_clearance cookie to
     * the exact UA that solved the challenge, so the extension's HTTP client must send that same
     * UA — otherwise Cloudflare rejects the request with 403 even though the cookie is present.
     */
    fun getUserAgent(): String? {
        val context = Injekt.get<Application>()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var ua: String? = null
        handler.post {
            try {
                val wv = WebView(context)
                ua = wv.settings.userAgentString
                wv.destroy()
            } catch (_: Exception) {
                // Ignore — caller falls back to the default UA.
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return ua
    }

    @Synchronized
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun extract(siteUrl: String, userAgent: String? = null): List<Token> {
        val context = Injekt.get<Application>()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val result = mutableListOf<Token>()
        var webView: WebView? = null

        fun capture(header: String, value: String) {
            val normalized = header.lowercase()
            if (
                normalized in STANDARD_HEADER_NAMES ||
                header.isBlank() ||
                value.isBlank() ||
                value.length >= 256
            ) {
                return
            }
            synchronized(result) {
                val token = Token(header, value)
                if (token !in result) result.add(token)
                val hasAuth = result.any {
                    it.header.equals("toonlivre-pass", ignoreCase = true) &&
                        !it.value.contains("decoy", ignoreCase = true)
                }
                val hasVerify = result.any {
                    it.header.equals("x-toon-verify", ignoreCase = true)
                }
                if (hasAuth && hasVerify && latch.count > 0L) latch.countDown()
            }
        }

        handler.post {
            val wv = WebView(context)
            webView = wv

            wv.layoutParams = ViewGroup.LayoutParams(WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, WEBVIEW_WIDTH, WEBVIEW_HEIGHT)

            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                if (!userAgent.isNullOrBlank()) userAgentString = userAgent
            }

            // JS bridge — fallback in case shouldInterceptRequest misses something
            val bridge = object : Any() {
                @JavascriptInterface
                fun onToken(header: String, value: String) {
                    capture(header, value)
                }
            }
            wv.addJavascriptInterface(bridge, "TokenBridge")

            wv.webViewClient = object : WebViewClient() {

                // Primary mechanism: intercept every WebView request and inspect headers.
                // shouldInterceptRequest fires before the network call, so there's no race
                // condition with JS injection. Works with fetch, XHR, and service workers.
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val reqHeaders = request.requestHeaders ?: emptyMap()
                    for ((key, value) in reqHeaders) {
                        capture(key, value)
                    }
                    return null // let WebView proceed normally
                }

                // Secondary mechanism: patch window.fetch and XHR after page finishes loading.
                override fun onPageFinished(view: WebView, url: String) {
                    if (latch.count > 0L) {
                        view.evaluateJavascript(
                            """
                            (function() {
                                const _std = new Set([
                                    'accept','accept-language','accept-encoding','content-type',
                                    'content-length','authorization','user-agent','referer','origin',
                                    'cookie','cache-control','pragma','connection','host','dnt',
                                    'x-csrf-token','x-requested-with','priority','sec-fetch-dest',
                                    'sec-fetch-mode','sec-fetch-site','sec-ch-ua','sec-ch-ua-mobile',
                                    'sec-ch-ua-platform',
                                ]);
                                const _report = function(k, v) {
                                    if (k && v && !_std.has(String(k).toLowerCase()) && String(v).length < 60) {
                                        TokenBridge.onToken(k, v);
                                    }
                                };
                                const _fetch = window.fetch;
                                window.fetch = function(input, init) {
                                    const headers = (init && init.headers) ? init.headers : {};
                                    const entries = headers instanceof Headers
                                        ? [...headers.entries()]
                                        : Object.entries(headers);
                                    for (const [k, v] of entries) { _report(k, v); }
                                    return _fetch.apply(this, arguments);
                                };
                                const _xhr = XMLHttpRequest.prototype.setRequestHeader;
                                XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                                    _report(k, v);
                                    return _xhr.apply(this, arguments);
                                };
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
            }

            wv.loadUrl(siteUrl)
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        return synchronized(result) { result.toList() }
    }

    /**
     * Last-resort reader path. Instead of reproducing the site's private headers, encryption and
     * JSON schema, let its own WebView application render the chapter and collect the image URLs.
     * This keeps reading functional when the API contract changes but the public reader still works.
     */
    @Synchronized
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun extractPages(siteUrl: String, userAgent: String? = null): List<String> {
        val context = Injekt.get<Application>()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val interceptedPages = mutableListOf<String>()
        var domPages: List<String> = emptyList()
        var webView: WebView? = null

        fun capture(url: String) {
            val pageUrl = IMAGE_URL_REGEX.find(url)?.value ?: return
            synchronized(interceptedPages) {
                if (pageUrl !in interceptedPages) interceptedPages.add(pageUrl)
            }
        }

        handler.post {
            try {
                val view = WebView(context)
                webView = view
                view.layoutParams = ViewGroup.LayoutParams(WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(WEBVIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(WEBVIEW_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, WEBVIEW_WIDTH, WEBVIEW_HEIGHT)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    if (!userAgent.isNullOrBlank()) userAgentString = userAgent
                }

                view.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onPages(serialized: String) {
                            val pages = IMAGE_URL_REGEX.findAll(serialized)
                                .map { it.value.replace("\\/", "/") }
                                .distinct()
                                .toList()
                            if (pages.isNotEmpty()) {
                                domPages = pages
                                latch.countDown()
                            }
                        }
                    },
                    "PageBridge",
                )

                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        capture(request.url.toString())
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        listOf(2_000L, 5_000L, 10_000L).forEach { delay ->
                            handler.postDelayed(
                                {
                                    if (latch.count == 0L) return@postDelayed
                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                            const values = [];
                                            document.querySelectorAll('img, source').forEach(function(el) {
                                                ['src','data-src','data-lazy-src','srcset'].forEach(function(attr) {
                                                    const value = el.getAttribute(attr);
                                                    if (value) values.push(...value.split(',').map(function(v) {
                                                        return v.trim().split(/\s+/)[0];
                                                    }));
                                                });
                                            });
                                            PageBridge.onPages(JSON.stringify(values));
                                        })();
                                        """.trimIndent(),
                                        null,
                                    )
                                },
                                delay,
                            )
                        }
                    }
                }
                view.loadUrl(siteUrl)
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        return domPages.ifEmpty {
            synchronized(interceptedPages) { interceptedPages.toList() }
        }
    }
}
