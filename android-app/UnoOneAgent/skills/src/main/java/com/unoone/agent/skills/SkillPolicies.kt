package com.unoone.agent.skills

import com.unoone.agent.core.memory.OutcomeMemoryPolicy
import com.unoone.agent.storage.entity.SkillEntity

data class SkillDefinition(
    val name: String,
    val triggers: List<String>,
    val steps: List<String>,
    val riskLevel: Int = 0
)

/** Small, useful routines installed locally on first run. Every step still enters normal safety. */
object BuiltInSkillCatalog {
    val definitions: List<SkillDefinition> = listOf(
        SkillDefinition(
            name = "Read Screen Aloud",
            triggers = listOf("read my screen aloud", "tell me what is on my screen"),
            steps = listOf("read screen"),
            riskLevel = 1
        ),
        SkillDefinition(
            name = "Start Blind Aid Guidance",
            triggers = listOf("start blind aid guidance", "help me navigate with blind aid"),
            steps = listOf("start blind aid"),
            riskLevel = 2
        ),
        SkillDefinition(
            name = "Fill an Offline PDF Form",
            triggers = listOf("fill a pdf form", "complete a pdf form offline"),
            steps = listOf("fill pdf form")
        ),
        SkillDefinition(
            name = "Fill an Offline DOCX Template",
            triggers = listOf("fill a docx template", "complete a word template offline"),
            steps = listOf("fill docx template")
        )
    )

    val names: Set<String> = definitions.map { it.name }.toSet()
}

/**
 * Converts repeated successful, low-risk usage into a disabled skill suggestion. It never stores
 * message bodies, recipients, form data, or other user arguments, and never auto-enables a skill.
 */
object SkillLearningPolicy {
    const val SUGGESTION_THRESHOLD = 3
    const val SUGGESTION_PREFIX = "Suggested · "

    fun usageKey(tool: String, routineName: String): String =
        "skill_usage:$tool:${normalizeKey(routineName)}"

    private fun normalizeKey(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    fun shouldSuggest(successCount: Int): Boolean = successCount >= SUGGESTION_THRESHOLD

    fun suggestionFor(command: String, tool: String): SkillDefinition? = when (tool) {
        "open_calendar" -> SkillDefinition(
            name = "${SUGGESTION_PREFIX}Open Calendar",
            triggers = listOf("open my calendar", "show my calendar"),
            steps = listOf("open calendar")
        )
        "check_calendar" -> SkillDefinition(
            name = "${SUGGESTION_PREFIX}Today's Calendar",
            triggers = listOf("check today's calendar", "what is on my calendar today"),
            steps = listOf("check calendar"),
            riskLevel = 1
        )
        "open_chrome" -> SkillDefinition(
            name = "${SUGGESTION_PREFIX}Open Chrome",
            triggers = listOf("open my browser", "start chrome"),
            steps = listOf("open chrome")
        )
        "open_app" -> if (OutcomeMemoryPolicy.signature(command).contains("whatsapp")) {
            SkillDefinition(
                name = "${SUGGESTION_PREFIX}Open WhatsApp",
                triggers = listOf("open my whatsapp", "start whatsapp"),
                steps = listOf("open whatsapp app")
            )
        } else null
        else -> null
    }
}

/** Boundary-aware, deterministic trigger selection. Avoids `mail` matching `email`, etc. */
object SkillTriggerMatcher {
    fun bestMatch(input: String, skills: List<SkillEntity>): SkillEntity? {
        val normalizedInput = normalize(input)
        if (normalizedInput.isBlank()) return null

        return skills.mapNotNull { skill ->
            val score = skill.triggerPhrases.split(",")
                .map(::normalize)
                .filter { it.isNotBlank() }
                .maxOfOrNull { trigger -> score(normalizedInput, trigger) }
                ?: 0
            skill.takeIf { score > 0 }?.let { it to score }
        }.sortedWith(
            compareByDescending<Pair<SkillEntity, Int>> { it.second }
                .thenByDescending { it.first.updatedAt }
        ).firstOrNull()?.first
    }

    private fun score(input: String, trigger: String): Int {
        if (input == trigger) return 100_000 + trigger.length
        // Single-word substring triggers are too broad. They are accepted only as exact commands.
        if (!trigger.contains(' ')) return 0
        val bounded = Regex("(?:^| )${Regex.escape(trigger)}(?:$| )")
        return if (bounded.containsMatchIn(input)) 10_000 + trigger.length else 0
    }

    internal fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
