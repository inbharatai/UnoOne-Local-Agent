package com.unoone.agent.localbrain

import com.unoone.agent.core.device.UnoBrain

/** Model-neutral controller adapter over the supplied facade, never a second native allocation. */
class QwenMnnBrain(
    localBrain: LocalBrain,
    screenshotProvider: SnapshotImageProvider? = null,
    clockMs: () -> Long = android.os.SystemClock::elapsedRealtime
) : UnoBrain by LocalUnoBrain(localBrain, screenshotProvider, clockMs)
