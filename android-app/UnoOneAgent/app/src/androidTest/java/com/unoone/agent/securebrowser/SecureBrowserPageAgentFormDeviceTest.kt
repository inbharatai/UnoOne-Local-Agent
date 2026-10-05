package com.unoone.agent.securebrowser

import android.webkit.WebView
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packaged DOM adapter + native-owned controller on WebView; scripted model fixture, not real inference. */
class SecureBrowserPageAgentFormDeviceTest {

    @Test
    fun packagedPageAgentFillsOrdinaryLocalFormField() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ready = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<Pair<Boolean, String>>()
        val controllerRef = AtomicReference<SecureWebViewController>()
        val webViewRef = AtomicReference<WebView>()
        val modelStep = AtomicInteger(0)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())

        val handler = SecureBrowserNativeHandler(
            modelPort = BrowserModelPort { invocation ->
                val decision = if (modelStep.getAndIncrement() == 0) {
                    val inputIndex = invocation.userPrompt.lineSequence().mapNotNull { line ->
                        val match = Regex("""\[(\d+)] (\{.*})""").matchEntire(line) ?: return@mapNotNull null
                        val summary = org.json.JSONObject(match.groupValues[2])
                        if (summary.optString("tag") == "input" && summary.optString("id") == "first-name")
                            match.groupValues[1].toInt() else null
                    }.singleOrNull() ?: error("First-name input was not indexed in native DOM snapshot")
                    PageAgentModelDecision(
                        evaluationPreviousGoal = "No previous action",
                        memory = "First name is empty",
                        nextGoal = "Fill first name",
                        actionName = "input_text",
                        actionArgumentsJson = "{\"index\":$inputIndex,\"text\":\"Reeturaj\"}"
                    )
                } else {
                    PageAgentModelDecision(
                        evaluationPreviousGoal = "First name was filled",
                        memory = "Requested field is complete",
                        nextGoal = "Finish",
                        actionName = "done",
                        actionArgumentsJson = "{\"text\":\"Form field completed\",\"success\":true}"
                    )
                }
                Result.success(decision)
            },
            userInteraction = object : BrowserUserInteraction {
                override suspend fun confirm(message: String) = true
                override suspend fun ask(question: String) = ""
                override suspend fun requestTakeover(message: String) = true
            },
            eventSink = BrowserEventSink { type, payload -> events += "$type:$payload" },
            safetyModeProvider = { BrowserSafetyMode.PROTOTYPE_OFF }
        )

        instrumentation.runOnMainSync {
            val webView = WebView(context)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, 1080, 1920)
            webViewRef.set(webView)
            val controller = SecureWebViewController(
                context = context,
                webView = webView,
                domainPolicy = BrowserDomainPolicy(ApprovedOriginPolicy.APPROVED_ORIGINS),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                requestHandler = handler,
                onRuntimeReady = { ready.countDown() }
            )
            controllerRef.set(controller)
            controller.loadLocalHtml(
                """<!doctype html><html><body><main>
                    <label for="first-name">First name</label>
                    <input id="first-name" name="firstName">
                    </main></body></html>""".trimIndent(),
                "ordinary-form.html"
            )
        }

        assertTrue("PageAgent runtime did not initialize", ready.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            controllerRef.get().executeTask("Fill first name with Reeturaj") { success, message ->
                result.set(success to message)
                completed.countDown()
            }
        }
        assertTrue("PageAgent form task timed out", completed.await(30, TimeUnit.SECONDS))
        // Page-observed writes are not independently verified task completion.
        assertEquals(false, result.get()?.first)
        assertTrue("Unexpected native completion: ${result.get()?.second}",
            result.get()?.second?.contains("not independently verified") == true)
        assertEquals("Scripted input + done decisions", 2, modelStep.get())

        val valueRead = CountDownLatch(1)
        val fieldValue = AtomicReference("")
        instrumentation.runOnMainSync {
            webViewRef.get().evaluateJavascript("document.getElementById('first-name').value") {
                fieldValue.set(it.trim('"'))
                valueRead.countDown()
            }
        }
        assertTrue(valueRead.await(10, TimeUnit.SECONDS))
        assertEquals("Native PageAgent events: $events", "Reeturaj", fieldValue.get())
        instrumentation.runOnMainSync { controllerRef.get().stop() }
    }

}
