package com.unoone.agent.securebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
import kotlinx.serialization.Serializable
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
    private val onShowFileChooser: (
        ValueCallback<Array<Uri>>,
        WebChromeClient.FileChooserParams
    ) -> Boolean = { _, _ -> false },
    val session: BrowserSession = BrowserSession(
        // C9: always admit the synthetic local-form origin in the bridge filter. This does NOT admit
        // remote navigation to it (BrowserDomainPolicy.evaluate still blocks non-approved https hosts,
        // and loadDataWithBaseURL is the only way a page lands at this origin). It only lets the
        // origin-scoped web-message listener accept PageAgent bridge calls FROM a locally-loaded form.
        allowedOrigins = domainPolicy.origins() + LOCAL_FORM_ORIGIN
    )
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val runtimeBundle: String? by lazy { readRuntimeBundle() }

    @Volatile private var runtimeInjected = false

    /**
     * C9: the synthetic origin of a locally-loaded offline form, or null when browsing a remote
     * approved origin. Set by [loadLocalHtml]; admitted by [isAdmittedOrigin] and the onPageFinished
     * injection path. Reachable ONLY via [loadLocalHtml] (BrowserDomainPolicy blocks navigation to
     * it), so admitting it does not widen the remote attack surface.
     */
    @Volatile private var localFormOrigin: String? = null

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

    /**
     * C9: load a local/offline HTML form (e.g. a user-picked .html file) into the sandboxed WebView
     * at the synthetic [LOCAL_FORM_ORIGIN]. The PageAgent runtime is then injected exactly as for a
     * remote approved page, and every action the agent plans still round-trips through AUTHORIZE_ACTION
     * → BrowserSafetyPolicy — so payment/credential/OTP/captcha/legal/final-submission gates apply
     * unchanged (the policy is origin-agnostic). No safety gate is weakened: the only addition is
     * admitting the synthetic origin, which is reachable solely via this explicit local load.
     *
     * The WebView is locked down in [configureWebView] (file/content access off, file/universal access
     * from URLs off, mixed content never allowed) so the local HTML cannot reach device storage or
     * load http resources; it is sandboxed to its synthetic origin.
     */
    fun loadLocalHtml(html: String, displayName: String) {
        if (html.isBlank()) {
            onRuntimeError("The local form is empty")
            return
        }
        localFormOrigin = LOCAL_FORM_ORIGIN
        runtimeInjected = false
        // baseUrl sets the page's origin; historyUrl null keeps the synthetic origin. The page's
        // location.origin becomes LOCAL_FORM_ORIGIN, which the bridge filter + isAdmittedOrigin accept.
        webView.loadDataWithBaseURL(LOCAL_FORM_ORIGIN + "/", html, "text/html", "utf-8", null)
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

    /**
     * Reads the current page's title + visible body text for the eyes-free "read this page aloud"
     * capability (WS4). Best-effort: returns an empty string if the page, the body, or JS is
     * unavailable. This NEVER invokes the PageAgent bridge or any automation — it only reads what
     * is already rendered, exactly as a sighted user would see it. The result is truncated to keep
     * the spoken readback bounded.
     */
    fun readPageText(callback: (String) -> Unit) {
        val script = """
            (function(){
              try {
                var title = (document.title || '').trim();
                var text = document.body ? (document.body.innerText || '').trim() : '';
                return JSON.stringify({title: title, text: text});
              } catch (e) { return ''; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val out = runCatching {
                if (raw == null || raw == "null" || raw == "\"\"") ""
                else {
                    val page = json.decodeFromString(PageText.serializer(), raw)
                    buildString {
                        if (page.title.isNotBlank()) { append(page.title); append(". ") }
                        append(page.text)
                    }.take(4_000)
                }
            }.getOrElse { "" }
            callback(out)
        }
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
                // C9: a locally-loaded offline form lands at the synthetic local-form origin (set by
                // loadLocalHtml). domainPolicy.evaluate would block it (it is not an approved remote
                // origin), so handle it here: inject the PageAgent runtime so the agent can work the
                // form. All action gates still apply via AUTHORIZE_ACTION → BrowserSafetyPolicy.
                val local = localFormOrigin
                if (local != null && target.startsWith(local)) {
                    if (session.active) {
                        session.activeOrigin = local
                        injectRuntime(local)
                    }
                    return
                }
                val decision = domainPolicy.evaluate(target)
                if (decision is NavigationDecision.Allow && session.active) {
                    session.activeOrigin = decision.origin
                    injectRuntime(decision.origin)
                }
            }
        }

        // Android WebView does not implement <input type="file"> by itself. Forward the chooser
        // request to the Activity-owned launcher; its result is returned to this callback by the
        // ViewModel. Without this client, PageAgent could authorize and click an upload field but
        // the selected URI was never delivered back to the page.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                return onShowFileChooser(filePathCallback, fileChooserParams)
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
        // C9: admit the synthetic local-form origin (reachable only via loadLocalHtml) in addition to
        // approved remote origins. The action safety gates are enforced downstream in BrowserSafetyPolicy.
        if (!isAdmittedOrigin(sourceOrigin)) return "Source origin is not approved"
        if (!isAdmittedOrigin(request.origin)) return "Declared origin is not approved"
        if (normalizeOrigin(sourceOrigin) != normalizeOrigin(request.origin)) return "Declared origin does not match source"
        if (session.activeOrigin != null && normalizeOrigin(session.activeOrigin!!) != normalizeOrigin(sourceOrigin)) {
            return "Active page origin changed"
        }
        return null
    }

    /** Approved remote origins plus, when set, the synthetic local-form origin. */
    private fun isAdmittedOrigin(origin: String): Boolean {
        if (domainPolicy.isAllowedOrigin(origin)) return true
        val local = localFormOrigin ?: return false
        return normalizeOrigin(origin) == normalizeOrigin(local)
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
        /** C9: synthetic https origin a local/offline HTML form is loaded at (via loadDataWithBaseURL). */
        const val LOCAL_FORM_ORIGIN = "https://unoone.local-form"
    }
}

/** Title + visible body text extracted from the current page for the spoken "read this page" path. */
@Serializable
private data class PageText(val title: String = "", val text: String = "")
