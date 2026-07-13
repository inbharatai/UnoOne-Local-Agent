package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the scene-description builder: foreground-context framing, OCR line
 * extraction + dedup + cap, aspect focus, and the honest "could not read" fallback when no signal
 * is available. The LiteRT-LM vision path that augments this is device-time-only; these tests pin
 * the always-available fallback contract.
 */
class SceneDescriptionBuilderTest {

    @Test
    fun framesForegroundAppAndActivity() {
        val desc = SceneDescriptionBuilder.build(
            SceneInput(currentPackage = "com.example.bank", currentActivity = "LoginActivity")
        )
        assertTrue(desc.startsWith("Scene: com.example.bank (LoginActivity)"))
    }

    @Test
    fun usesPackageOnlyWhenActivityAbsent() {
        val desc = SceneDescriptionBuilder.build(SceneInput(currentPackage = "com.android.chrome"))
        assertTrue(desc.startsWith("Scene: com.android.chrome"))
        assertFalse(desc.contains("("))
    }

    @Test
    fun extractsAndDedupsOcrLines() {
        val desc = SceneDescriptionBuilder.build(
            SceneInput(ocrText = "Welcome back\nUsername\nUsername\nPassword\nLogin")
        )
        assertTrue(desc.contains("Visible text: Welcome back; Username; Password; Login"))
        // duplicate "Username" collapsed to one entry
        assertEquals(1, desc.split("Username").size - 1)
    }

    @Test
    fun capsOcrLinesToMax() {
        val many = (1..20).joinToString("\n") { "line$it" }
        val desc = SceneDescriptionBuilder.build(SceneInput(ocrText = many))
        // Only MAX_OCR_LINES (8) lines appear.
        val visible = desc.substringAfter("Visible text: ")
        assertEquals(SceneDescriptionBuilder.MAX_OCR_LINES, visible.split("; ").size)
    }

    @Test
    fun includesAspectFocusWhenProvided() {
        val desc = SceneDescriptionBuilder.build(
            SceneInput(currentPackage = "com.example.shop", aspect = "the total amount")
        )
        assertTrue(desc.contains("Looking for: the total amount"))
    }

    @Test
    fun honestlyReportsUnreadableScreenWhenNoSignals() {
        val desc = SceneDescriptionBuilder.build(SceneInput())
        assertEquals("I could not read any text on the screen.", desc)
    }

    @Test
    fun unreadableScreenMentionsAspectWhenProvided() {
        val desc = SceneDescriptionBuilder.build(SceneInput(aspect = "any OTP"))
        assertEquals("I could not read any text on the screen to look for any OTP.", desc)
    }

    @Test
    fun blankOcrIsIgnoredNotReportedAsEmptyLine() {
        val desc = SceneDescriptionBuilder.build(
            SceneInput(currentPackage = "com.example.app", ocrText = "\n  \n\t")
        )
        assertTrue(desc.startsWith("Scene: com.example.app"))
        assertFalse(desc.contains("Visible text:"))
    }

    @Test
    fun neverReturnsBlank() {
        // Every combination of empty inputs still yields a non-blank, honest sentence.
        assertFalse(SceneDescriptionBuilder.build(SceneInput()).isBlank())
        assertFalse(SceneDescriptionBuilder.build(SceneInput(aspect = "x")).isBlank())
        assertFalse(SceneDescriptionBuilder.build(SceneInput(ocrText = "hi")).isBlank())
    }
}