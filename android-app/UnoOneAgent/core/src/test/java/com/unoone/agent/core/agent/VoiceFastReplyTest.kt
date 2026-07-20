package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceFastReplyTest {
    @Test
    fun englishHearingCheckIsImmediateAndStable() {
        assertEquals(
            "Yes, I can hear you. How can I help?",
            VoiceFastReply.replyFor("Hi, can you hear me?", "en")
        )
        assertEquals(
            "Yes, I can hear you. How can I help?",
            VoiceFastReply.replyFor("NO ONE CAN HEAR ME", "en-IN")
        )
    }

    @Test
    fun hindiHearingChecksSupportNativeAndRomanizedSpeech() {
        val expected = "हाँ, आपकी आवाज़ सुनाई दे रही है। मैं आपकी कैसे मदद करूँ?"
        assertEquals(expected, VoiceFastReply.replyFor("क्या आप मुझे सुन सकते हो?", "hi"))
        assertEquals(expected, VoiceFastReply.replyFor("kya aap mujhe sun rahe ho", "hi-IN"))
    }

    @Test
    fun greetingsAreFastButActionsAreNotIntercepted() {
        assertEquals("Hello. How can I help?", VoiceFastReply.replyFor("hello", "en"))
        assertEquals("नमस्ते। मैं आपकी कैसे मदद करूँ?", VoiceFastReply.replyFor("namaste", "hi"))
        assertNull(VoiceFastReply.replyFor("open calendar", "en"))
        assertNull(VoiceFastReply.replyFor("activate blind aid", "en"))
    }
}
