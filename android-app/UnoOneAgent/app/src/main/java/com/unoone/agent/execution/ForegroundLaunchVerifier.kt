package com.unoone.agent.execution

import com.unoone.agent.phonecontrol.LaunchAttempt

/**
 * Pure foreground-verification policy kept separate from the polling code so package matching and
 * user-visible failure wording remain deterministic and JVM-testable.
 */
internal object ForegroundLaunchVerifier {
    fun matches(attempt: LaunchAttempt, observedPackage: String?): Boolean {
        val observed = observedPackage?.trim().orEmpty()
        return observed.isNotEmpty() && observed in attempt.expectedForegroundPackages
    }

    fun unavailableMessage(actionLabel: String): String =
        "$actionLabel launch was requested, but UnoOne cannot verify it because Accessibility " +
            "access is off. Enable UnoOne Accessibility in Android Settings and try again."

    fun mismatchMessage(actionLabel: String): String =
        "$actionLabel did not reach the foreground. Android or the phone's background-start " +
            "settings may have blocked it. Open UnoOne's battery and autostart settings, allow " +
            "background starts, and try again."
}
