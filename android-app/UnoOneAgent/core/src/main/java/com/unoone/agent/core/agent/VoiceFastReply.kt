package com.unoone.agent.core.agent

/**
 * Deterministic, zero-model replies for common microphone checks and greetings.
 *
 * These phrases must never enter the agent planner: doing so turned a basic "can you hear me?"
 * check into a 20+ second inference and made a healthy microphone look broken.
 */
object VoiceFastReply {
    private val hearChecks = listOf(
        "can you hear me",
        "can u hear me",
        "could you hear me",
        "do you hear me",
        "are you listening",
        "can anyone hear me",
        "no one can hear me",
        "kya aap mujhe sun",
        "kya tum mujhe sun",
        "aap mujhe sun rahe",
        "tum mujhe sun rahe",
        "meri awaaz aa rahi",
        "sun sakte ho",
        "sun pa rahe ho",
        "क्या आप मुझे सुन",
        "क्या तुम मुझे सुन",
        "आप मुझे सुन रहे",
        "तुम मुझे सुन रहे",
        "मेरी आवाज़ आ रही",
        "सुन सकते हो"
    )

    private val greetings = setOf(
        "hi", "hello", "hey", "hi uno one", "hello uno one", "hey uno one",
        "namaste", "नमस्ते"
    )

    fun replyFor(text: String, languageCode: String): String? {
        val normalized = text
            .lowercase()
            // Keep combining marks: Devanagari vowel signs are \p{M}, not \p{L}.
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val isHindi = languageCode.lowercase().startsWith("hi")
        return when {
            hearChecks.any(normalized::contains) ->
                if (isHindi) "हाँ, आपकी आवाज़ सुनाई दे रही है। मैं आपकी कैसे मदद करूँ?"
                else "Yes, I can hear you. How can I help?"
            normalized in greetings ->
                if (isHindi) "नमस्ते। मैं आपकी कैसे मदद करूँ?"
                else "Hello. How can I help?"
            else -> null
        }
    }
}
