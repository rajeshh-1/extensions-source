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
    fun extract(siteUrl: String, userAgent: String? = null): Token? {
        val context = Injekt.get<Application>()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var result: Token? = null
        var webView: WebView? = null

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
                    if (latch.count > 0L) {
                        result = Token(header, value)
                        latch.countDown()
                    }
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
                    if (latch.count > 0L) {
                        val reqHeaders = request.requestHeaders ?: emptyMap()
                        for ((key, value) in reqHeaders) {
                            val lk = key.lowercase()
                            if (lk !in STANDARD_HEADER_NAMES && value.length < 60 && value.isNotBlank()) {
                                result = Token(key, value)
                                latch.countDown()
                                break
                            }
                        }
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

        return result
    }
}
