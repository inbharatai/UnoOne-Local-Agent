package com.unoone.agent.securebrowser

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical-WebView proof for local form loading, runtime injection, and eyes-free page reading. */
class SecureBrowserReadPageDeviceTest {

    @Test
    fun localFormInitializesRuntimeAndReturnsTitlePlusVisibleBodyText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ready = CountDownLatch(1)
        val spokenText = AtomicReference("")
        val read = CountDownLatch(1)
        val controllerRef = AtomicReference<SecureWebViewController>()

        instrumentation.runOnMainSync {
            val controller = SecureWebViewController(
                context = context,
                webView = WebView(context),
                domainPolicy = BrowserDomainPolicy(ApprovedOriginPolicy.APPROVED_ORIGINS),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                requestHandler = PageAgentRequestHandler {
                    PageAgentBridgeResponse(requestId = it.requestId, success = true, payload = "{}")
                },
                onRuntimeReady = { ready.countDown() }
            )
            controllerRef.set(controller)
            controller.loadLocalHtml(
                """<!doctype html><html><head><title>Application form</title></head>
                    <body><main><label>First name <input name="firstName"></label>
                    <button>Submit application</button></main></body></html>""".trimIndent(),
                "application.html"
            )
        }

        assertTrue("PageAgent runtime did not initialize", ready.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            controllerRef.get().readPageText {
                spokenText.set(it)
                read.countDown()
            }
        }
        assertTrue("Page text callback timed out", read.await(10, TimeUnit.SECONDS))
        assertTrue(spokenText.get().contains("Application form"))
        assertTrue(spokenText.get().contains("First name"))
        assertTrue(spokenText.get().contains("Submit application"))

        instrumentation.runOnMainSync { controllerRef.get().stop() }
    }
}
