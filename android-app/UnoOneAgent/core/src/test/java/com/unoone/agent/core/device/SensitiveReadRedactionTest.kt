package com.unoone.agent.core.device

import org.junit.Assert.*
import org.junit.Test

class SensitiveReadRedactionTest {
    private val bounds = RectData(0, 0, 100, 100)
    private fun node(id: String, text: String, description: String = "") = UiNode(id, 1, "0.$id", "example.app", "android.widget.TextView", text = text, description = description, bounds = bounds)
    private fun snapshot(nodes: List<UiNode>) = UiSnapshot("s", 0, 0, bounds, listOf(UiWindow(1, "example.app", bounds, nodes)))

    @Test fun otpLabelAndNonPasswordTextViewNeverReachReadOrModel() {
        val s = snapshot(listOf(node("label", "OTP"), node("value", "123456", "123456")))
        assertTrue(s.nodes.all { it.text.isEmpty() && it.description.isEmpty() })
        assertFalse(SensitiveReadRedaction.readScreen(s, "example.app").contains("123456"))
        assertFalse(DeviceContextCompactor.compact(PerceptionState(s)).contains("123456"))
    }
    @Test fun ordinaryDigitsAndDatesStayReadableWithoutSecretContext() {
        val s = snapshot(listOf(node("count", "Order 123456"), node("date", "2026-10-05")))
        val read = SensitiveReadRedaction.readScreen(s, "example.app")
        assertTrue(read.contains("123456")); assertTrue(read.contains("2026-10-05"))
    }
    @Test fun labelsAndHintsAreDenialOnlyAndLegacyOcrHandsOver() {
        for (label in listOf("password", "passcode", "verification code", "card number", "security code", "OTP")) {
            assertTrue(SensitiveReadRedaction.shouldRedact(label, "123456", false))
            assertFalse(SensitiveReadRedaction.redactText("$label\n123456").contains("123456"))
        }
        val s = snapshot(listOf(node("ordinary", "Hello")))
        val ocr = listOf(OcrRegion("OTP", bounds, 1f), OcrRegion("123456", bounds, 1f))
        assertFalse(DeviceContextCompactor.compact(PerceptionState(s, ocr)).contains("123456"))
        assertTrue(PerceptionFusion.fuse(s, ocr).ocr.isEmpty())
    }
}
