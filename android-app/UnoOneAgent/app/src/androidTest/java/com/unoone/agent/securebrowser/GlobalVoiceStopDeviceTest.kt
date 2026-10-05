package com.unoone.agent.securebrowser

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.browser.PendingBrowserHandoff
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual application ingress and running WebView loop; model response intentionally waits forever. */
class GlobalVoiceStopDeviceTest {
    @Test fun acceptedAppVoiceStopRevokesActiveBrowserAndPendingNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<UnoOneApplication>()
        val ready = CountDownLatch(1)
        val inference = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val nativeCancel = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pending = PendingBrowserHandoff()
        val request = pending.offer("https://example.com", "fill form", false)!!
        pending.navigationStarted(request.origin)
        lateinit var controller: SecureWebViewController
        val wasEnabled = AgentRuntimeGate.isEnabled()
        AgentRuntimeGate.setEnabled(true)
        instrumentation.runOnMainSync {
            controller = SecureWebViewController(app, WebView(app), BrowserDomainPolicy(emptySet()),
                scope = scope,
                requestHandler = PageAgentRequestHandler {
                    inference.countDown()
                    try { awaitCancellation() } finally { cancelled.countDown() }
                }, onRuntimeReady = { ready.countDown() })
            controller.loadLocalHtml("<html><body><input name='name'></body></html>", "test")
        }
        val registration = GlobalTaskCancellation.register(controller) {
            pending.revoke()
            nativeCancel.set(true)
            it.stopTask()
        }
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { controller.executeTask("fill name") { _, _ -> } }
            assertTrue(inference.await(10, TimeUnit.SECONDS))
            // A pending phone/device command must be cleared by the real orchestrator as well.
            val orchestratorField = UnoOneApplication::class.java.getDeclaredField("orchestrator").apply { isAccessible = true }
            val orchestrator = orchestratorField.get(app)
            val pendingField = orchestrator.javaClass.getDeclaredField("pendingCommand").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val phonePending = pendingField.get(orchestrator) as java.util.concurrent.atomic.AtomicReference<String?>
            phonePending.set("test pending phone command")
            app.postVoiceCommand("stop") // Background ingress; not merely VoiceControlPolicy callback testing.
            assertTrue(nativeCancel.get())
            assertNull(phonePending.get())
            assertNull(pending.consume(request.generation, request.origin, request.origin, true, false))
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(controller.session.active) // Stop is not browser/session destruction.
        } finally {
            registration.close()
            instrumentation.runOnMainSync { controller.stop() }
            scope.cancel()
            AgentRuntimeGate.setEnabled(wasEnabled)
        }
    }
}
