package com.unoone.agent.core.model

/**
 * Represents the result of a risk assessment, including what actions
 * the user can take at each risk level.
 */
data class RiskAssessment(
    val level: RiskLevel,
    val availableActions: Set<UserAction>,
    val editField: String? = null
)

enum class UserAction {
    CANCEL,
    EDIT,
    CONFIRM
}

/**
 * Returns the available user actions for each risk level:
 * - DIRECT → {CANCEL, CONFIRM} (auto-confirmed, but user can still cancel)
 * - CONFIRM → {CANCEL, EDIT, CONFIRM}
 * - STRONG_CONFIRM → {CANCEL, EDIT} (must re-type "confirm")
 * - BLOCK → {CANCEL} (no way to proceed)
 */
fun RiskLevel.availableActions(): Set<UserAction> = when (this) {
    RiskLevel.DIRECT -> setOf(UserAction.CANCEL, UserAction.CONFIRM)
    RiskLevel.CONFIRM -> setOf(UserAction.CANCEL, UserAction.EDIT, UserAction.CONFIRM)
    RiskLevel.STRONG_CONFIRM -> setOf(UserAction.CANCEL, UserAction.EDIT)
    RiskLevel.BLOCK -> setOf(UserAction.CANCEL)
}