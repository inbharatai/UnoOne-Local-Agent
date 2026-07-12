package com.unoone.agent.securebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface PageAgentRequestHandler {
    suspend fun handle(request: PageAgentBridgeRequest): PageAgentBridgeResponse
}

/**
 * Hardened WebView host for Alibaba PageAgent.
 *
 * The bridge is exposed only through AndroidX WebKit's origin-scoped web-message listener. Web pages
 * never receive a Java object and cannot invoke arbitrary native methods. The compiled PageAgent
 * bundle is loaded from the APK asset `page-agent/unoone-page-agent.js`; missing assets are surfaced
 * explicitly rather than silently falling back to unsafe generic WebView automation.
 */
class SecureWebViewController(
    context: Context,
    private val webView: WebView,
    private val domainPolicy: BrowserDomainPolicy,
    private val scope: CoroutineScope,
    private val requestHandler: PageAgentRequestHandler,
    private val onBlockedNavigation: (String) -> Unit = {},
    private val onRuntimeReady: () -> Unit = {},
    private val onRuntimeError: (String) -> Unit = {},
    val session: BrowserSession = BrowserSession(allowedOrigins = domainPolicy.origins())
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val runtimeBundle: String? by lazy { readRuntimeBundle() }

    @Volatile private var runtimeInjected = false

    init {
        configureWebView()
        installBridge()
    }

    fun isRuntimeAvailable(): Boolean = runtimeBundle != null
    fun isRuntimeInjected(): Boolean = runtimeInjected
    fun canGoBack(): Boolean = webView.canGoBack()
    fun goBack() = webView.goBack()

    fun load(rawUrl: String): NavigationDecision {
        val decision = domainPolicy.evaluate(rawUrl)
        when (decision) {
            is NavigationDecision.Allow -> webView.loadUrl(decision.normalizedUrl)
            is NavigationDecision.Block -> onBlockedNavigation(decision.reason)
        }
        return decision
    }

    /** Executes one PageAgent task after the bundle has initialized on the current approved page. */
    fun executeTask(task: String, callback: (success: Boolean, result: String) -> Unit) {
        if (!runtimeInjected) {
            callback(false, "Alibaba PageAgent runtime is not loaded on this page")
            return
        }
        val clean = task.trim()
        if (clean.isBlank()) {
            callback(false, "Browser task is empty")
            return
        }
        val script = """
            (async () => {
              if (!window.UnoOnePageAgentRuntime) throw new Error('UnoOne PageAgent runtime unavailable');
              return await window.UnoOnePageAgentRuntime.execute(${json.encodeToString(clean)});
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val decoded = runCatching {
                if (raw == null || raw == "null") "No result returned"
                else json.decodeFromString<String>(raw)
            }.getOrElse { raw ?: "Unknown JavaScript result" }
            val failed = decoded.contains("Error", ignoreCase = true) || decoded.contains("exception", ignoreCase = true)
            callback(!failed, decoded)
        }
    }

    fun stopTask() {
        if (!runtimeInjected) return
        webView.evaluateJavascript("window.UnoOnePageAgentRuntime?.stop?.()", null)
    }

    fun stop() {
        session.close()
        stopTask()
        webView.stopLoading()
        runtimeInjected = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME)
        }
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                return when (val decision = domainPolicy.evaluate(url)) {
                    is NavigationDecision.Allow -> false
                    is NavigationDecision.Block -> {
                        onBlockedNavigation(decision.reason)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                runtimeInjected = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val target = url ?: return
                val decision = domainPolicy.evaluate(target)
                if (decision is NavigationDecision.Allow && session.active) {
                    session.activeOrigin = decision.origin
                    injectRuntime(decision.origin)
                }
            }
        }
    }

    private fun installBridge() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "This WebView does not support origin-scoped web message listeners"
        }
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            session.allowedOrigins,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    if (!isMainFrame) {
                        replyProxy.postMessage(errorResponse("unknown", "SUBFRAME_BLOCKED", "Only the main frame may call UnoOne"))
                        return
                    }
                    val raw = message.data ?: ""
                    if (raw.toByteArray(Charsets.UTF_8).size > PageAgentBridgeRequest.MAX_PAYLOAD_BYTES) {
                        replyProxy.postMessage(errorResponse("unknown", "PAYLOAD_TOO_LARGE", "Bridge payload exceeds the limit"))
                        return
                    }
                    val request = try {
                        json.decodeFromString(PageAgentBridgeRequest.serializer(), raw)
                    } catch (_: Exception) {
                        replyProxy.postMessage(errorResponse("unknown", "INVALID_JSON", "Invalid PageAgent bridge request"))
                        return
                    }

                    val validationError = validateRequest(request, sourceOrigin.toString())
                    if (validationError != null) {
                        replyProxy.postMessage(errorResponse(request.requestId, "REQUEST_REJECTED", validationError))
                        return
                    }

                    scope.launch(Dispatchers.Default) {
                        val response = try {
                            requestHandler.handle(request)
                        } catch (e: Exception) {
                            PageAgentBridgeResponse(
                                requestId = request.requestId,
                                success = false,
                                errorCode = "NATIVE_HANDLER_ERROR",
                                errorMessage = e.message ?: "Native handler failed"
                            )
                        }
                        val encoded = json.encodeToString(response)
                        withContext(Dispatchers.Main) { replyProxy.postMessage(encoded) }
                    }
                }
            }
        )
    }

    private fun validateRequest(request: PageAgentBridgeRequest, sourceOrigin: String): String? {
        if (!session.active) return "Browser session is closed"
        if (request.protocolVersion != PageAgentBridgeRequest.PROTOCOL_VERSION) return "Unsupported protocol version"
        if (request.sessionId != session.id) return "Session id mismatch"
        if (request.sessionNonce != session.nonce) return "Session nonce mismatch"
        if (!domainPolicy.isAllowedOrigin(sourceOrigin)) return "Source origin is not approved"
        if (!domainPolicy.isAllowedOrigin(request.origin)) return "Declared origin is not approved"
        if (normalizeOrigin(sourceOrigin) != normalizeOrigin(request.origin)) return "Declared origin does not match source"
        if (session.activeOrigin != null && normalizeOrigin(session.activeOrigin!!) != normalizeOrigin(sourceOrigin)) {
            return "Active page origin changed"
        }
        return null
    }

    private fun injectRuntime(origin: String) {
        val bundle = runtimeBundle
        if (bundle == null) {
            onRuntimeError(
                "PageAgent bundle is missing. Build web-runtime/page-agent-unoone and copy " +
                    "unoone-page-agent.js to securebrowser/src/main/assets/page-agent/."
            )
            return
        }
        val bootstrap = """
            (() => {
              const session = Object.freeze({
                id: ${json.encodeToString(session.id)},
                nonce: ${json.encodeToString(session.nonce)},
                origin: ${json.encodeToString(origin)},
                protocolVersion: ${PageAgentBridgeRequest.PROTOCOL_VERSION}
              });
              Object.defineProperty(window, '__UNOONE_PAGE_AGENT_SESSION__', {
                value: session,
                writable: false,
                configurable: false
              });
              window.dispatchEvent(new CustomEvent('unoone-page-agent-ready', { detail: { origin: session.origin } }));
            })();
        """.trimIndent()
        webView.evaluateJavascript(bootstrap) {
            webView.evaluateJavascript(bundle) {
                webView.evaluateJavascript("Boolean(window.UnoOnePageAgentRuntime)") { available ->
                    runtimeInjected = available == "true"
                    if (runtimeInjected) onRuntimeReady()
                    else onRuntimeError("PageAgent bundle executed but runtime initialization failed")
                }
            }
        }
    }

    private fun readRuntimeBundle(): String? = runCatching {
        appContext.assets.open(RUNTIME_ASSET).bufferedReader().use { it.readText() }
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun errorResponse(requestId: String, code: String, message: String): String =
        json.encodeToString(
            PageAgentBridgeResponse(
                requestId = requestId,
                success = false,
                errorCode = code,
                errorMessage = message
            )
        )

    private fun normalizeOrigin(value: String): String = value.trim().removeSuffix("/").lowercase()

    companion object {
        const val BRIDGE_NAME = "UnoOnePageAgent"
        const val RUNTIME_ASSET = "page-agent/unoone-page-agent.js"
    }
}
