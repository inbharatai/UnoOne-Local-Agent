package com.unoone.agent.localbrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun systemInstructionContainsAllToolNames() {
        val instruction = PromptBuilder.buildSystemInstruction()
        val expectedTools = listOf(
            "create_note", "search_notes", "summarize_text", "speak_response",
            "open_chrome", "open_app", "open_url", "open_camera",
            "system_control", "read_screen", "ocr_screen", "create_skill",
            "draft_email", "send_whatsapp", "check_calendar", "open_calendar_insert",
            "open_dialer", "share_text", "delete_notes", "delete_all_notes",
            "export_data", "detect_objects", "deactivate_blind_aid"
        )
        for (tool in expectedTools) {
            assertTrue("System instruction should mention $tool", instruction.contains(tool))
        }
    }

    @Test
    fun systemInstructionForbidsSilentMessages() {
        val instruction = PromptBuilder.buildSystemInstruction()
        assertTrue(
            "System instruction must tell Gemma never to send/pay silently",
            instruction.contains("never send", ignoreCase = true) ||
                instruction.contains("never pay", ignoreCase = true) ||
                instruction.contains("only DRAFT", ignoreCase = true)
        )
    }

    @Test
    fun userMessageIncludesCommand() {
        val message = PromptBuilder.buildUserMessage("open chrome", ContextSnapshot())
        assertTrue(message.contains("open chrome"))
    }

    @Test
    fun userMessageIncludesContextWhenProvided() {
        val snapshot = ContextSnapshot(
            currentPackage = "com.whatsapp",
            currentActivity = ".Main",
            visibleText = "Chat list",
            ocrText = "",
            recentNotes = listOf("buy milk"),
            userMemory = "prefers Hindi",
            activeSkills = listOf("Morning")
        )
        val message = PromptBuilder.buildUserMessage("send a message", snapshot)
        assertTrue(message.contains("com.whatsapp"))
        assertTrue(message.contains("Chat list"))
        assertTrue(message.contains("buy milk"))
        assertTrue(message.contains("prefers Hindi"))
        assertTrue(message.contains("Morning"))
    }

    @Test
    fun userMessageTruncatesLongVisibleText() {
        val longText = "a".repeat(10_000)
        val snapshot = ContextSnapshot(visibleText = longText)
        val message = PromptBuilder.buildUserMessage("read screen", snapshot)
        assertTrue(message.length < 10_000)
    }
}
