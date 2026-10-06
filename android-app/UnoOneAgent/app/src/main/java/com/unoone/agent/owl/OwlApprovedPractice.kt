package com.unoone.agent.owl

import kotlinx.coroutines.delay

/** Runs only inside the admitted Owl worker's UI lease. Delay injection keeps revocation tests deterministic. */
internal suspend fun <T> runApprovedOwlStart(
    ownPractice: Boolean,
    checkActive: () -> Unit,
    beforeLaunch: () -> Unit,
    launch: () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    run: suspend () -> T
): T {
    checkActive()
    if (ownPractice) {
        repeat(3) {
            checkActive()
            pause(1000)
            checkActive()
        }
        beforeLaunch()
        checkActive()
        launch()
        checkActive()
        pause(1200)
        checkActive()
    }
    checkActive()
    return run().also { checkActive() }
}
